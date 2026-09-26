package be.elevenways.hohenheim.test.settings;

import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.zenit.common.Zenit;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A setting's refusal is a zenit SettingsRule judged against the proposed state, never a
 * throwing coercer and never a silent substitution by the reader: the reclaim age must be at
 * least an hour, and the public port window must be an unprivileged range inside 65535 --
 * which PortPublications used to "fix" by quietly using 30000 instead.
 */
class HohenheimSettingsRulesTest {

    @Test
    void refusalsAreRulesJudgedOnTheProposedStateAndNamedByTheirCopy() {
        Integer previousAge = Zenit.SETTINGS_VALUES.getValue(HohenheimSettings.Stacks.RECLAIM_MIN_AGE_HOURS);
        Integer previousFirst = Zenit.SETTINGS_VALUES.getValue(HohenheimSettings.Instances.PUBLIC_PORT_FIRST);
        Integer previousCount = Zenit.SETTINGS_VALUES.getValue(HohenheimSettings.Instances.PUBLIC_PORT_COUNT);
        try {
            // 1. The coercer only converts: a numeric string is a number, garbage is not one.
            Zenit.SETTINGS_VALUES.loadFromMap(Map.of("stacks", Map.of("reclaim_min_age_hours", "12")));
            assertThat(Zenit.SETTINGS_VALUES.getValue(HohenheimSettings.Stacks.RECLAIM_MIN_AGE_HOURS))
                .as("step 1: a numeric string converts").isEqualTo(12);

            // 2. The "at least 1" refusal is the RULE's, raised before anything is stored.
            assertThatThrownBy(() -> Zenit.SETTINGS_VALUES.setValue(
                    HohenheimSettings.Stacks.RECLAIM_MIN_AGE_HOURS, 0))
                .as("step 2: zero hours is refused by the rule")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("reclaim_min_age_hours");
            assertThat(Zenit.SETTINGS_VALUES.getValue(HohenheimSettings.Stacks.RECLAIM_MIN_AGE_HOURS))
                .as("step 2: and the value in force is unchanged").isEqualTo(12);
            assertThat(HohenheimSettings.Stacks.RECLAIM_MIN_AGE_AT_LEAST_ONE.copy())
                .as("step 2: the rule carries the copy an editor shows").isNotNull();

            // 3. A privileged first port is REFUSED, where the reader used to swap in 30000.
            assertThatThrownBy(() -> Zenit.SETTINGS_VALUES.setValue(
                    HohenheimSettings.Instances.PUBLIC_PORT_FIRST, 1024))
                .as("step 3: a window starting at 1024 is refused")
                .isInstanceOf(IllegalArgumentException.class);

            // 4. The two keys are judged TOGETHER: a first port that fits alone but runs the
            //    window past 65535 with the standing count is refused too.
            assertThatThrownBy(() -> Zenit.SETTINGS_VALUES.setValue(
                    HohenheimSettings.Instances.PUBLIC_PORT_FIRST, 65000))
                .as("step 4: 65000 + 2000 ports ends beyond 65535")
                .isInstanceOf(IllegalArgumentException.class);

            // 5. A real unprivileged window is accepted.
            assertThatCode(() -> Zenit.SETTINGS_VALUES.loadFromMap(Map.of("instances",
                    Map.of("public_port_first", 40000, "public_port_count", 500))))
                .as("step 5: 40000-40499 is a valid window").doesNotThrowAnyException();
            assertThat(Zenit.SETTINGS_VALUES.getValue(HohenheimSettings.Instances.PUBLIC_PORT_FIRST))
                .isEqualTo(40000);
        } finally {
            Zenit.SETTINGS_VALUES.loadFromMap(Map.of(
                "stacks", Map.of("reclaim_min_age_hours", previousAge),
                "instances", Map.of("public_port_first", previousFirst, "public_port_count", previousCount)));
        }
    }
}
