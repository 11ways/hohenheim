package be.elevenways.hohenheim.test.application;

import be.elevenways.hohenheim.model.ArtifactOperationModel;
import be.elevenways.hohenheim.model.BuildOperationModel;
import be.elevenways.hohenheim.model.OperationLifecycle;
import be.elevenways.hohenheim.model.OperationStatus;
import be.elevenways.hohenheim.model.ReleaseOperationModel;
import be.elevenways.hohenheim.model.StackDeploymentModel;
import be.elevenways.protoblast.common.i18n.LocaleChain;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.zenit.common.orm.field.EnumField;
import be.elevenways.zenit.server.microcopy.ShippedCatalogs;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * The operation records share ONE status vocabulary ({@link OperationStatus}); each record's
 * {@link OperationLifecycle} picks its members and spells them in its column, and every status
 * set a reader queries derives from the members' facts.
 *
 * @author Jelle De Loecker <jelle@elevenways.be>
 * @since 0.1.0
 */
class OperationLifecycleDriftTest {

    @Test
    void everyOperationRecordStoresItsStatusesThroughTheOneVocabulary() {
        // 1. Each status column's values are exactly its lifecycle's stored spellings.
        assertColumn(BuildOperationModel.STATUS, BuildOperationModel.LIFECYCLE);
        assertColumn(ReleaseOperationModel.STATUS, ReleaseOperationModel.LIFECYCLE);
        assertColumn(StackDeploymentModel.STATUS, StackDeploymentModel.LIFECYCLE);

        // 2. Stored values never change: a stack deploy's success stays "success", every other record says
        //    "succeeded", and both read back as the one member.
        assertThat(StackDeploymentModel.LIFECYCLE.stored(OperationStatus.SUCCEEDED))
            .as("step 2: stack deployments keep their stored spelling").isEqualTo("success");
        assertThat(StackDeploymentModel.LIFECYCLE.read("success"))
            .as("step 2: and it reads as succeeded").isEqualTo(OperationStatus.SUCCEEDED);
        for (OperationLifecycle lifecycle : List.of(ArtifactOperationModel.LIFECYCLE, BuildOperationModel.LIFECYCLE,
                ReleaseOperationModel.LIFECYCLE)) {
            assertThat(lifecycle.stored(OperationStatus.SUCCEEDED))
                .as("step 2: the others store the member's own token").isEqualTo("succeeded");
            assertThat(lifecycle.stored(OperationStatus.FAILED))
                .as("step 2: and \"failed\" is spelled once, by the member").isEqualTo("failed");
        }

        // 3. The release's in-flight set is exactly its pre-settlement statuses; a settled status is never in it.
        assertThat(ReleaseOperationModel.IN_FLIGHT_STATUSES)
            .as("step 3: in flight = pending through draining")
            .containsExactly("pending", "deploying", "probing", "switching", "draining");

        // 4. Taking traffic starts at the switch and survives success, never failure.
        assertThat(ReleaseOperationModel.TRAFFIC_TAKEN_STATUSES)
            .as("step 4: took traffic = switching, draining, succeeded")
            .containsExactly("switching", "draining", "succeeded");

        // 5. Unknown values fail closed: no member reads them, and a lifecycle refuses a member it does not store.
        assertThat(ReleaseOperationModel.LIFECYCLE.read("mystery"))
            .as("step 5: an undeclared stored status has no member").isNull();
        assertThat(BuildOperationModel.LIFECYCLE.read("success"))
            .as("step 5: the stack's spelling means nothing to a build").isNull();
        assertThat(catchThrowable(() -> StackDeploymentModel.LIFECYCLE.stored(OperationStatus.PROBING)))
            .as("step 5: a stack deploy never stores a release status").isInstanceOf(IllegalArgumentException.class);

        // 6. Every member reads as words in both shipped languages; the key is the member's token, which no
        //    literal-key scan sees.
        ShippedCatalogs catalogs = new ShippedCatalogs();
        List<String> missing = new ArrayList<>();
        for (OperationStatus status : OperationStatus.values()) {
            Microcopy words = status.label();
            for (String language : List.of("en", "nl")) {
                if (catalogs.resolveSource(words.key(), LocaleChain.ofTags(language), words.filters()) == null) {
                    missing.add(status + " in " + language);
                }
            }
        }
        assertThat(missing).as("step 6: every status ships its words in en and nl").isEmpty();
    }

    private static void assertColumn(EnumField column, OperationLifecycle lifecycle) {
        assertThat(column.getValues().keySet())
            .as("step 1: column %s stores exactly its lifecycle", column.getName())
            .containsExactlyInAnyOrderElementsOf(lifecycle.stored(status -> true));
    }
}
