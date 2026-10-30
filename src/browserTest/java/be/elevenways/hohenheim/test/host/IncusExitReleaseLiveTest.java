package be.elevenways.hohenheim.test.host;

import org.junit.jupiter.api.extension.RegisterExtension;
import be.elevenways.zenit.test.support.ServedApp;
import be.elevenways.hohenheim.test.HohenheimServedApp;
import be.elevenways.hohenheim.server.ControllerScope;
import be.elevenways.zenit.common.orm.datasource.Db;
import be.elevenways.zenit.common.orm.datasource.sql.SqlDatasource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What a live class leaves on a real daemon when the wall ends its run before its tearDown: the JVM's exit release
 * ({@code LiveIncusHost.releaseAtExit}) hands back the root key, the trust entry and every instance named with the
 * class's controller prefix, so a killed run leaves nothing for a human to clean.
 *
 * AIDEV-NOTE: the kill is stood in for by calling the exit release directly while the class still holds all three,
 * exactly the state a run cut off mid-test is in; the leftover instance is an empty one (no image), so the proof
 * costs no pull.
 *
 * @author Jelle De Loecker
 * @since  0.10.0
 */
@Tag("slow") // live lane: needs a real daemon/host/image; runs via `zenit-dev test --all`
class IncusExitReleaseLiveTest {

    @RegisterExtension
    static final ServedApp APP = HohenheimServedApp.app();

    // AIDEV-NOTE: unique per live class BY REQUIREMENT (see LiveIncusHost.authorizeKey).
    private static final String HOST = "live-exit-release";
    /** The trust client name; also the comment the product mints on the host's ssh key ({@code hohenheim-<HOST>}). */
    private static final String CLIENT = "hohenheim-" + HOST;

    private static SqlDatasource datasource;
    private static LiveIncusHost remote;

    @BeforeAll
    static void setUp() throws Exception {
        remote = LiveIncusHost.requirePrimary();
        datasource = APP.database();
    }

    @AfterAll
    static void tearDown() {
        if (remote != null) {
            LiveIncusHost.releaseAtExit();
            System.out.println("=== cleanup: trust -> " + remote.releaseTrustEntries());
        }
    }

    @Test
    void theExitReleaseRemovesTheKeyTheTrustEntryAndThePrefixedInstancesJourney() {
        Db.run(datasource, () -> {
            // 1. A class mid-run: enrolled on the daemon, its root key installed, a workload of its own running there.
            remote.enrollDaemonTrustThroughProduct(HOST, CLIENT);
            remote.enrollSshLaneThroughProduct(HOST);
            String leftover = ControllerScope.scoped("") + "exit-probe";
            host("incus", "create", "--empty", leftover);
            assertThat(held(leftover))
                .as("step 1: the class holds a root key, a trust entry and an instance of its own")
                .isEqualTo(Map.of("key", true, "trust", true, "instance", true));
            assertThat(host("sh", "-c", "'grep \" " + CLIENT + "$\" ~/.ssh/authorized_keys'"))
                .as("step 1: the root key expires on its own, should every cleanup miss it")
                .startsWith("expiry-time=\"");

            // 2. The wall ends the run: the JVM's exit release runs with all three still held.
            LiveIncusHost.releaseAtExit();
            assertThat(held(leftover))
                .as("step 2: nothing of the class is left on the daemon")
                .isEqualTo(Map.of("key", false, "trust", false, "instance", false));
        });
    }

    /** What of the class the daemon still holds, read on the host itself. */
    private static Map<String, Boolean> held(String instance) {
        return Map.of(
            "key", !host("sh", "-c", "'grep -c \" " + CLIENT + "$\" "
                + "~/.ssh/authorized_keys || true'").equals("0"),
            "trust", host("incus", "config", "trust", "list", "--format", "csv", "-c", "n").lines()
                .anyMatch(CLIENT::equals),
            "instance", host("incus", "list", "--format", "csv", "-c", "n").lines().anyMatch(instance::equals));
    }

    private static String host(String... command) {
        try {
            return remote.hostCommand(command);
        } catch (IOException failed) {
            throw new UncheckedIOException(failed);
        }
    }
}
