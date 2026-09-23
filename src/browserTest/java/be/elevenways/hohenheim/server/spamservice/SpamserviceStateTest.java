package be.elevenways.hohenheim.server.spamservice;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Spamservice lifecycle vocabulary: one home, facts on the member, and a stored token
 * nobody declares fails closed.
 */
class SpamserviceStateTest {

    @Test
    void theStateVocabularyClassifiesByFactAndFailsClosed() {
        // 1. Every member round-trips its token, and the tokens are distinct.
        Set<String> tokens = new HashSet<>();
        for (SpamserviceState state : SpamserviceState.values()) {
            assertThat(SpamserviceState.fromToken(state.token()))
                .as("step 1: %s round-trips", state).isEqualTo(state);
            assertThat(tokens.add(state.token()))
                .as("step 1: %s's token is its own", state).isTrue();

            // 2. Exactly one member is ready, and a member is at most one of ready,
            //    transitional and failing.
            int facts = (state.ready() ? 1 : 0) + (state.transitional() ? 1 : 0)
                + (state.failing() ? 1 : 0);
            assertThat(facts).as("step 2: %s carries at most one phase fact", state)
                .isLessThanOrEqualTo(1);
            assertThat(state.needsAttention())
                .as("step 2: %s needs attention exactly when it is not ready", state)
                .isEqualTo(!state.ready());
        }
        assertThat(SpamserviceState.READY.token())
            .as("step 1: the ready token the surfaces always rendered").isEqualTo("ready");

        // 3. The snapshot answers through the facts: ready is ready, an enabled runtime
        //    in any other state needs attention, a disabled or unconfigured one never does.
        assertThat(snapshot(true, true, "ready").ready()).as("step 3: ready").isTrue();
        assertThat(snapshot(true, true, "ready").needsAttention())
            .as("step 3: a ready runtime needs nobody").isFalse();
        assertThat(snapshot(true, true, "backoff").needsAttention())
            .as("step 3: an enabled runtime in backoff needs attention").isTrue();
        assertThat(snapshot(true, false, "stopped").needsAttention())
            .as("step 3: a disabled one is a choice, not a warning").isFalse();
        assertThat(snapshot(false, false, "stopped").needsAttention())
            .as("step 3: an unconfigured one too").isFalse();

        // 4. FAIL CLOSED: a token no member carries is never ready, and an enabled runtime
        //    reporting one is surfaced rather than trusted.
        assertThat(SpamserviceState.fromToken("up")).as("step 4: unknown parses to null").isNull();
        assertThat(snapshot(true, true, "up").ready()).as("step 4: never ready").isFalse();
        assertThat(snapshot(true, true, "up").needsAttention())
            .as("step 4: and needs attention").isTrue();
    }

    private static SpamserviceManager.Snapshot snapshot(boolean configured, boolean enabled,
                                                        String state) {
        return new SpamserviceManager.Snapshot(configured, enabled, state, null, null, null, 0, null);
    }
}
