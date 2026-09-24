package be.elevenways.hohenheim.test.application;

import be.elevenways.hohenheim.model.ReleaseOperationModel;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The release-operation status vocabulary has ONE declaring home: every stored status value
 * is a {@link ReleaseOperationModel.Phase} member carrying its in-flight and took-traffic facts,
 * and the status sets every reader queries are derived from those facts.
 *
 * @author Jelle De Loecker <jelle@elevenways.be>
 * @since 0.1.0
 */
class ReleaseOperationPhaseDriftTest {

    @Test
    void everyStatusDeclaresItsPhaseFactsAndTheSetsDeriveFromThem() {
        // 1. The column's values and the phase members are the same set.
        List<String> tokens = new ArrayList<>();
        for (ReleaseOperationModel.Phase phase : ReleaseOperationModel.Phase.values()) {
            tokens.add(phase.token());
        }
        assertThat(tokens).as("step 1: every phase member names a stored status and vice versa")
            .containsExactlyInAnyOrderElementsOf(ReleaseOperationModel.STATUS.getValues().keySet());

        // 2. The in-flight set is exactly the pre-settlement phases; a settled status is never in it.
        assertThat(ReleaseOperationModel.IN_FLIGHT_STATUSES)
            .as("step 2: in flight = pending through draining")
            .containsExactlyInAnyOrder(ReleaseOperationModel.STATUS_PENDING,
                ReleaseOperationModel.STATUS_DEPLOYING, ReleaseOperationModel.STATUS_PROBING,
                ReleaseOperationModel.STATUS_SWITCHING, ReleaseOperationModel.STATUS_DRAINING);

        // 3. Taking traffic starts at the switch and survives success, never failure.
        assertThat(ReleaseOperationModel.TRAFFIC_TAKEN_STATUSES)
            .as("step 3: took traffic = switching, draining, succeeded")
            .containsExactlyInAnyOrder(ReleaseOperationModel.STATUS_SWITCHING,
                ReleaseOperationModel.STATUS_DRAINING, ReleaseOperationModel.STATUS_SUCCEEDED);

        // 4. An unknown stored value has no phase, so no reader can count it as either.
        assertThat(ReleaseOperationModel.Phase.of("mystery"))
            .as("step 4: an undeclared status fails closed").isNull();
    }
}
