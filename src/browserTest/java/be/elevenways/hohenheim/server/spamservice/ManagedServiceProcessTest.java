package be.elevenways.hohenheim.server.spamservice;

import be.elevenways.hohenheim.server.SystemUsers;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** Verifies platform-thread output draining, redaction, and setsid group cleanup. */
class ManagedServiceProcessTest {

    @Test
    void outputPumpsRedactSecrets() throws Exception {
        String secret = "controller-secret-never-log";
        ProcessBuilder builder = new ProcessBuilder("/bin/sh", "-c",
            "printf '%s\\n' '" + secret + "'; printf '%s\\n' '" + secret + "' >&2");
        SystemUsers.setEnvironment(builder, SystemUsers.safeEnvironment(System.getProperty("user.home")));

        ManagedServiceProcess process = ManagedServiceProcess.start(builder, null,
            text -> text.replace(secret, "[REDACTED]"));
        assertThat(process.waitFor(5_000)).isTrue();
        process.stop(100);

        assertThat(process.output()).doesNotContain(secret).contains("[REDACTED]");
    }

    @Test
    void outputRedactionSurvivesPipeReadBoundaries() throws Exception {
        String secret = "controller-secret-never-log";
        ProcessBuilder builder = new ProcessBuilder("/bin/sh", "-c",
            "printf 'controller-'; sleep 0.1; printf 'secret-never-log\\n'");
        SystemUsers.setEnvironment(builder, SystemUsers.safeEnvironment(System.getProperty("user.home")));

        ManagedServiceProcess process = ManagedServiceProcess.start(builder, null,
            text -> text.replace(secret, "[REDACTED]"));
        assertThat(process.waitFor(5_000)).isTrue();
        process.stop(100);

        assertThat(process.output()).doesNotContain(secret).contains("[REDACTED]");
    }

    @Test
    void stopTerminatesTheSetsidProcessGroup() throws Exception {
        ProcessBuilder builder = SystemUsers.executionBuilder(null,
            SystemUsers.safeEnvironment(System.getProperty("user.home")),
            List.of("/bin/sh", "-c", "sleep 60 & wait"), true);
        ManagedServiceProcess process = ManagedServiceProcess.start(builder, null, value -> value);

        assertThat(process.isAlive()).isTrue();
        assertThat(process.stop(500)).isTrue();
        assertThat(process.process().waitFor(5, TimeUnit.SECONDS)).isTrue();
        assertThat(process.isAlive()).isFalse();
    }
}
