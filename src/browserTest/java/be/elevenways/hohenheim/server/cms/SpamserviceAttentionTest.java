package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.AttentionItem;
import be.elevenways.hohenheim.server.spamservice.SpamserviceManager;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The spamservice attention item warns only about an ENABLED service that is not
 * ready: an unconfigured or deliberately disabled one is a choice, not a defect
 * (the old guard warned on every fresh install), and the item it does raise must
 * explain itself and offer somewhere to act.
 */
class SpamserviceAttentionTest {

    private static SpamserviceManager.Snapshot snapshot(boolean configured, boolean enabled,
                                                        String state, String lastError) {
        return new SpamserviceManager.Snapshot(configured, enabled, state,
            null, null, null, 0, lastError);
    }

    @Test
    void onlyAnEnabledUnreadyServiceWarnsAndTheItemExplainsItself() {
        // Step 1: a fresh install (nothing configured) raises nothing.
        assertThat(AttentionCollector.spamserviceIssue(snapshot(false, false, "stopped", null)))
            .as("an unconfigured spamservice must not warn")
            .isNull();

        // Step 2: a configured but deliberately disabled service raises nothing.
        assertThat(AttentionCollector.spamserviceIssue(snapshot(true, false, "stopped", null)))
            .as("a disabled spamservice is an operator choice, not a defect")
            .isNull();

        // Step 3: an enabled, ready service raises nothing.
        assertThat(AttentionCollector.spamserviceIssue(snapshot(true, true, "ready", null)))
            .as("a ready spamservice must not warn")
            .isNull();

        // Step 4: enabled but unready, no recorded error: a warning with a
        // localized state sentence and a destination (the positive anchor).
        AttentionItem starting = AttentionCollector.spamserviceIssue(
            snapshot(true, true, "starting", null));
        assertThat(starting).as("an enabled unready spamservice must warn").isNotNull();
        assertThat(starting.severity()).as("severity is a warning").isEqualTo("warning");
        assertThat(starting.detail()).as("the item explains itself").isNotNull();
        assertThat(starting.detail().isLiteral())
            .as("without an error the detail is localized microcopy, never a raw state token")
            .isFalse();
        assertThat(starting.detail().key())
            .as("the detail is the attention sentence carrying the state as an arg")
            .isEqualTo("spamservice_not_ready");
        assertThat(starting.target())
            .as("the item links to the settings mount where the service is administered")
            .isNotNull();

        // Step 5: a recorded error beats the state sentence -- it is the actual
        // explanation, rendered verbatim.
        AttentionItem crashed = AttentionCollector.spamserviceIssue(
            snapshot(true, true, "crashed", "Spamservice exited with code 137"));
        assertThat(crashed).as("an erroring spamservice must warn").isNotNull();
        assertThat(crashed.detail()).isNotNull();
        assertThat(crashed.detail().isLiteral())
            .as("a recorded error is user-facing verbatim content")
            .isTrue();
    }
}
