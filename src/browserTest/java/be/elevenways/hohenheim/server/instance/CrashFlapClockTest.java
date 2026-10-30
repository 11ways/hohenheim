package be.elevenways.hohenheim.server.instance;

import org.junit.jupiter.api.extension.RegisterExtension;
import be.elevenways.zenit.test.support.ServedApp;
import be.elevenways.hohenheim.test.HohenheimServedApp;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The crash-flap clock counts the crashes of ONE controller's workload: record #7 of another controller (another
 * database, as every test class sharing a JVM has) crashed by itself, and its crashes never push this controller's
 * #7 into a crash loop. Keyed by the bare record id, InstanceConsoleCrashRestartTest and InstanceStatusReconcileTest
 * tripped each other's flap protection depending on which ran first in a shared fork.
 *
 * @author Jelle De Loecker
 * @since 0.1.0
 */
class CrashFlapClockTest {

    @RegisterExtension
    static final ServedApp APP = HohenheimServedApp.app();

    @AfterAll
    static void restoreTimings() {
        InstanceConsoles.overrideTimingsForTest(null, null);
    }

    @Test
    void anotherControllersCrashesNeverCountTowardsThisOnesLoop() throws Exception {
        InstanceConsoles.overrideTimingsForTest(null, null);
        APP.renewDatabase();

        // 1. Controller A's record #7 crashes twice: below the threshold, so no loop yet.
        assertThat(InstanceConsoles.flapExceeded(7)).as("step 1: the first crash is no loop").isFalse();
        assertThat(InstanceConsoles.flapExceeded(7)).as("step 1: nor the second").isFalse();

        // 2. A fresh database is another controller: its record #7 is another workload, whose first crash is a first.
        APP.renewDatabase();
        assertThat(InstanceConsoles.flapExceeded(7))
            .as("step 2: another controller's #7 starts its own count").isFalse();

        // 3. The threshold still trips for the workload that does crash three times inside the window.
        assertThat(InstanceConsoles.flapExceeded(7)).as("step 3: its second crash is no loop").isFalse();
        assertThat(InstanceConsoles.flapExceeded(7)).as("step 3: its third one is").isTrue();
    }
}
