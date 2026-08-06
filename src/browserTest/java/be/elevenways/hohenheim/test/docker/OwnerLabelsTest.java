package be.elevenways.hohenheim.test.docker;

import be.elevenways.hohenheim.server.ControllerIdentity;
import be.elevenways.hohenheim.test.HohenheimTestRuntime;
import org.junit.jupiter.api.BeforeAll;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.server.docker.OwnerLabels;
import be.elevenways.protoblast.common.registry.Identifier;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The ONE producer and parser of the owner-label pair: round-trip fidelity, and
 * refusal of every shape that is not an explicit two-label record claim.
 */
class OwnerLabelsTest {

    /** Names and labels are controller-namespaced, so this needs a real identity. */
    @BeforeAll
    static void controllerIdentity() {
        HohenheimTestRuntime.ensureDatasource();
    }


    @Test
    void producesThreeLabelsThatParseBackToTheSameOwner() {
        // 1. Produce: scope + discriminator + the controller the id belongs to, values
        //    in RecordGrants spelling.
        Map<String, String> labels = OwnerLabels.of(SiteModel.MODEL_ID, 42);
        assertThat(labels)
            .as("exactly the three owner labels, model as Identifier.toString, id stringified")
            .containsExactlyInAnyOrderEntriesOf(Map.of(
                "be.elevenways.hohenheim.owner.model", "hohenheim:site",
                "be.elevenways.hohenheim.owner.id", "42",
                "be.elevenways.hohenheim.owner.controller", ControllerIdentity.token()));

        // 2. Parse the produced map back: the same model identity, id and controller.
        OwnerLabels.Owner owner = OwnerLabels.parse(labels);
        assertThat(owner).as("produced labels parse").isNotNull();
        assertThat(owner.model()).as("model round-trips by identity").isEqualTo(SiteModel.MODEL_ID);
        assertThat(owner.id()).as("id round-trips as string").isEqualTo("42");
        assertThat(owner.controller()).as("controller round-trips")
            .isEqualTo(ControllerIdentity.token());
        assertThat(OwnerLabels.isOurs(owner)).as("our own claim is ours").isTrue();

        // 3. Parse survives extra unrelated labels sitting beside the pair (real
        //    containers carry image/testcontainers/compose labels too).
        Map<String, Object> crowded = new HashMap<>(labels);
        crowded.put("org.opencontainers.image.version", "1.0");
        OwnerLabels.Owner fromCrowded = OwnerLabels.parse(crowded);
        assertThat(fromCrowded).isNotNull();
        assertThat(fromCrowded.model()).isEqualTo(SiteModel.MODEL_ID);
    }

    @Test
    void refusesEveryShapeThatIsNotAFullExplicitClaim() {
        // 1. No labels at all / null map.
        assertThat(OwnerLabels.parse(null)).as("null labels").isNull();
        assertThat(OwnerLabels.parse(Map.of())).as("empty labels").isNull();

        // 2. Half a claim: model without id, id without model.
        assertThat(OwnerLabels.parse(Map.of(OwnerLabels.MODEL, "hohenheim:site")))
            .as("model without id is no claim").isNull();
        assertThat(OwnerLabels.parse(Map.of(OwnerLabels.ID, "42")))
            .as("id without model is no claim").isNull();

        // 3. A model value without an explicit namespace must NOT be defaulted into
        //    one (Identifier.tryParse would invent "elevenways:" -- a claim nobody made).
        assertThat(OwnerLabels.parse(Map.of(OwnerLabels.MODEL, "site", OwnerLabels.ID, "42")))
            .as("namespace-less model refused").isNull();
        assertThat(OwnerLabels.parse(Map.of(OwnerLabels.MODEL, ":site", OwnerLabels.ID, "42")))
            .as("empty namespace refused").isNull();

        // 4. Blank id is no claim.
        assertThat(OwnerLabels.parse(Map.of(OwnerLabels.MODEL, "hohenheim:site", OwnerLabels.ID, "")))
            .as("blank id refused").isNull();

        // 5. A claim by ANOTHER controller parses (so it can be reported and refused by
        //    name), but it is never OURS -- a record id only means something inside the
        //    database that allocated it.
        OwnerLabels.Owner foreign = OwnerLabels.parse(Map.of(
            OwnerLabels.MODEL, "hohenheim:site", OwnerLabels.ID, "42",
            OwnerLabels.CONTROLLER, "zq7m4x1b"));
        assertThat(foreign).as("a foreign controller's claim still parses").isNotNull();
        assertThat(foreign.controller()).isEqualTo("zq7m4x1b");
        assertThat(OwnerLabels.isOurs(foreign))
            .as("another controller's identical (model, id) is NOT ours").isFalse();

        // 6. A pre-namespace claim carries no controller at all, and is ours least of all.
        OwnerLabels.Owner preNamespace = OwnerLabels.parse(Map.of(
            OwnerLabels.MODEL, "hohenheim:site", OwnerLabels.ID, "42"));
        assertThat(preNamespace).as("a pre-namespace claim still parses").isNotNull();
        assertThat(preNamespace.controller()).as("with no controller").isNull();
        assertThat(OwnerLabels.isOurs(preNamespace))
            .as("an unattributable resource is never ours").isFalse();
    }

    @Test
    void producerAndParserAgreeOnTheLabelKeys() {
        // The constants ARE the wire contract; a drifting key would strand every
        // labelled resource as foreign.
        assertThat(OwnerLabels.MODEL).isEqualTo("be.elevenways.hohenheim.owner.model");
        assertThat(OwnerLabels.ID).isEqualTo("be.elevenways.hohenheim.owner.id");
        assertThat(OwnerLabels.CONTROLLER)
            .isEqualTo("be.elevenways.hohenheim.owner.controller");
        assertThat(OwnerLabels.of(Identifier.of("hohenheim", "database"), "7"))
            .containsOnlyKeys(OwnerLabels.MODEL, OwnerLabels.ID, OwnerLabels.CONTROLLER);
    }
}
