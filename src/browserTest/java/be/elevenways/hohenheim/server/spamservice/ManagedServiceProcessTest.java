package be.elevenways.hohenheim.server.spamservice;

import be.elevenways.hohenheim.server.SystemUsers;
import be.elevenways.hohenheim.test.Poll;
import be.elevenways.protoblast.server.process.Subprocess;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Verifies output draining, redaction, and setsid session cleanup, also after the leader exited. */
class ManagedServiceProcessTest {

    @Test
    void outputPumpsRedactSecrets() throws Exception {
        String secret = "controller-secret-never-log";
        Subprocess process = SystemUsers.withEnvironment(Subprocess.of("/bin/sh", "-c",
                "printf '%s\\n' '" + secret + "'; printf '%s\\n' '" + secret + "' >&2"),
            SystemUsers.safeEnvironment(System.getProperty("user.home")));

        ManagedServiceProcess managed = ManagedServiceProcess.start(process,
            text -> text.replace(secret, "[REDACTED]"));
        assertThat(managed.waitFor(5_000)).isTrue();
        managed.stop();

        assertThat(managed.output()).doesNotContain(secret).contains("[REDACTED]");
    }

    @Test
    void outputRedactionSurvivesPipeReadBoundaries() throws Exception {
        String secret = "controller-secret-never-log";
        Subprocess process = SystemUsers.withEnvironment(Subprocess.of("/bin/sh", "-c",
                "printf 'controller-'; sleep 0.1; printf 'secret-never-log\\n'"),
            SystemUsers.safeEnvironment(System.getProperty("user.home")));

        ManagedServiceProcess managed = ManagedServiceProcess.start(process,
            text -> text.replace(secret, "[REDACTED]"));
        assertThat(managed.waitFor(5_000)).isTrue();
        managed.stop();

        assertThat(managed.output()).doesNotContain(secret).contains("[REDACTED]");
    }

    @Test
    void stopTerminatesTheSetsidProcessGroup() throws Exception {
        Subprocess process = SystemUsers.execution(null,
                SystemUsers.safeEnvironment(System.getProperty("user.home")),
                List.of("/bin/sh", "-c", "sleep 60 & wait"), true)
            .stopGrace(Duration.ofMillis(500));
        ManagedServiceProcess managed = ManagedServiceProcess.start(process, value -> value);

        assertThat(managed.isAlive()).isTrue();
        assertThat(managed.stop()).isTrue();
        assertThat(managed.isAlive()).isFalse();
    }

    /**
     * A session outlives its leader: what the leader started in the background keeps the group, and stop() after the
     * leader's own exit still ends it, so a restart never meets an orphan holding the port.
     */
    @Test
    void stopAfterTheLeaderExitedStillEndsTheSession() throws Exception {
        // 1. The leader starts a background sleep in its session and exits at once.
        Subprocess process = SystemUsers.execution(null,
                SystemUsers.safeEnvironment(System.getProperty("user.home")),
                List.of("/bin/sh", "-c", "sleep 60 >/dev/null 2>&1 </dev/null & echo $!"), true)
            .stopGrace(Duration.ofMillis(500));
        ManagedServiceProcess managed = ManagedServiceProcess.start(process, value -> value);
        assertThat(managed.waitFor(10_000)).as("step 1: the leader exits on its own").isTrue();
        long orphan = Long.parseLong(managed.output().trim());
        assertThat(ProcessHandle.of(orphan).map(ProcessHandle::isAlive).orElse(false))
            .as("step 1: its background sleep still runs in the session").isTrue();

        // 2. Stopping the exited service reaches the session and reports it ended.
        assertThat(managed.stop()).as("step 2: the session is swept").isTrue();
        Poll.until("step 2: the orphan is gone", Duration.ofSeconds(5), Duration.ofMillis(50),
            () -> !ProcessHandle.of(orphan).map(ProcessHandle::isAlive).orElse(false));
    }
}
