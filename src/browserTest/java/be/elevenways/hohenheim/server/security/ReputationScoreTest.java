package be.elevenways.hohenheim.server.security;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Shared reputation arithmetic stays category-local, normalized, and saturating. */
class ReputationScoreTest {

    @Test
    void positiveCreditCannotSpillAcrossCategories() {
        ReputationScore.Settings settings = ReputationScore.Settings.of(" Spam, AUTH ", 25, 10, true);
        ReputationScore score = ReputationScore.calculate(
            Map.of("SPAM", 25L, "auth", 1L), Map.of("AUTH", 100L), settings);

        assertThat(score.net()).isEqualTo(25);
        assertThat(score.grossNegative()).isEqualTo(26);
        assertThat(score.appliedPositiveCredit()).isEqualTo(1);
        assertThat(score.categories()).extracting(ReputationScore.CategoryScore::category)
            .containsExactly("spam", "auth");
    }

    @Test
    void giantAndNegativeValuesNeverWrap() {
        ReputationScore.Settings settings = ReputationScore.Settings.of(
            "spam,auth,http", 25, Integer.MAX_VALUE, true);
        ReputationScore score = ReputationScore.calculate(
            Map.of("spam", 25L, "auth", Long.MAX_VALUE, "http", Long.MAX_VALUE),
            Map.of("auth", Long.MAX_VALUE, "spam", -50L), settings);

        assertThat(score.grossNegative()).isEqualTo(Long.MAX_VALUE);
        assertThat(score.appliedPositiveCredit()).isEqualTo(Long.MAX_VALUE);
        assertThat(score.net()).isEqualTo(Long.MAX_VALUE);
        assertThat(score.reaches(settings)).isTrue();
    }

    @Test
    void nonPositiveThresholdAndWeightAreClampedInTheSnapshot() {
        ReputationScore.Settings settings = ReputationScore.Settings.of("spam", -5, -10, true);
        assertThat(settings.threshold()).isEqualTo(1);
        assertThat(settings.positiveWeight()).isZero();
        assertThat(settings.enabled()).isTrue();
    }
}
