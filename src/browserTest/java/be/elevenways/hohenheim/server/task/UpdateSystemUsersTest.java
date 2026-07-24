package be.elevenways.hohenheim.server.task;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Verifies that user discovery retains only selectable runtime identities. */
class UpdateSystemUsersTest {

    @Test
    void dedicatedSpamserviceSystemAccountSurvivesTheDaemonAccountFilter() {
        assertThat(UpdateSystemUsers.isEligibleUser("spamservice", 997)).isTrue();
        assertThat(UpdateSystemUsers.isEligibleUser("daemon", 998)).isFalse();
        assertThat(UpdateSystemUsers.isEligibleUser("root", 0)).isTrue();
        assertThat(UpdateSystemUsers.isEligibleUser("app", 1_000)).isTrue();
    }
}
