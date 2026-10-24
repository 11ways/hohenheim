package be.elevenways.hohenheim.server.spamservice;

import be.elevenways.hohenheim.server.SystemUsers;
import be.elevenways.protoblast.testsupport.time.Poll;
import be.elevenways.protoblast.server.process.Subprocess;
import be.elevenways.zenit.common.security.KnownValueRedactor;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Verifies output draining, redaction, and setsid session cleanup, also after the leader exited. */
class ManagedServiceProcessTest {

    @Test
    void outputPumpsRedactSecrets() throws Exception {
        String secret = "controller-secret-never-log";
        Subprocess process = SystemUsers.withEnvironment(Subprocess.of("/bin/sh", "-c",
                "printf '%s\\n' '" + secret + "'; printf '%s\\n' '" + secret + "' >&2"),
            SystemUsers.safeEnvironment(System.getProperty("user.home")));

        ManagedServiceProcess managed = ManagedServiceProcess.start(process, List.of(secret));
        assertThat(managed.waitFor(5_000)).isTrue();
        managed.stop();

        assertThat(managed.output()).doesNotContain(secret).contains(KnownValueRedactor.PLACEHOLDER);
    }

    @Test
    void outputRedactionSurvivesPipeReadBoundaries() throws Exception {
        String secret = "controller-secret-never-log";
        Subprocess process = SystemUsers.withEnvironment(Subprocess.of("/bin/sh", "-c",
                "printf 'before controller-'; sleep 0.1; printf 'secret-'; sleep 0.1; printf 'never-log after\\n'"),
            SystemUsers.safeEnvironment(System.getProperty("user.home")));

        ManagedServiceProcess managed = ManagedServiceProcess.start(process, List.of(secret));
        assertThat(managed.waitFor(5_000)).isTrue();
        managed.stop();

        assertThat(managed.output()).isEqualTo("before " + KnownValueRedactor.PLACEHOLDER + " after\n");
    }

    /** A process that ends mid-secret leaves a held prefix, which the end-of-stream flush masks instead of dropping. */
    @Test
    void outputCutOffMidSecretIsMaskedAtTheEnd() throws Exception {
        String secret = "controller-secret-never-log";
        Subprocess process = SystemUsers.withEnvironment(Subprocess.of("/bin/sh", "-c",
                "printf 'key: controller-'; sleep 0.1; printf 'secret-nev'"),
            SystemUsers.safeEnvironment(System.getProperty("user.home")));

        ManagedServiceProcess managed = ManagedServiceProcess.start(process, List.of(secret));
        assertThat(managed.waitFor(5_000)).isTrue();
        managed.stop();

        assertThat(managed.output()).isEqualTo("key: " + KnownValueRedactor.PLACEHOLDER);
    }

    @Test
    void aSecretTooShortToRedactIsRefusedBeforeStarting() {
        Subprocess process = SystemUsers.withEnvironment(Subprocess.of("/bin/sh", "-c", "echo short"),
            SystemUsers.safeEnvironment(System.getProperty("user.home")));

        assertThatThrownBy(() -> ManagedServiceProcess.start(process, List.of("short")))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void stopTerminatesTheSetsidProcessGroup() throws Exception {
        Subprocess process = SystemUsers.execution(null,
                SystemUsers.safeEnvironment(System.getProperty("user.home")),
                List.of("/bin/sh", "-c", "sleep 60 & wait"), true)
            .stopGrace(Duration.ofMillis(500));
        ManagedServiceProcess managed = ManagedServiceProcess.start(process, List.of());

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
        ManagedServiceProcess managed = ManagedServiceProcess.start(process, List.of());
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
