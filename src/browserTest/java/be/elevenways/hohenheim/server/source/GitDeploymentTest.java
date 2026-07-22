package be.elevenways.hohenheim.server.source;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Covers Git command validation, output draining, and process-group cleanup. */
class GitDeploymentTest {

    @Test
    void repositoryValidationPreservesSupportedLocations() {
        assertThatCode(() -> repository("/srv/git/project.git")).doesNotThrowAnyException();
        assertThatCode(() -> repository("https://example.test/project.git")).doesNotThrowAnyException();
        assertThatCode(() -> repository("ssh://git@example.test/project.git")).doesNotThrowAnyException();
        assertThatCode(() -> repository("git@example.test:project.git")).doesNotThrowAnyException();
    }

    @Test
    void repositoryValidationRejectsUriCredentialsWithoutEchoingThem() {
        assertThatThrownBy(() -> repository("https://deploy:super-secret@example.test/project.git"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("must not contain embedded user-info credentials")
            .hasMessageNotContaining("deploy")
            .hasMessageNotContaining("super-secret");
        assertThatThrownBy(() -> repository("ssh://git:super-secret@example.test/project.git"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageNotContaining("super-secret");
        assertThatThrownBy(() -> repository("https://access-token@example.test/project.git"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageNotContaining("access-token");
        assertThatThrownBy(() -> repository("https://deploy:super-secret@[broken/project.git"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageNotContaining("super-secret");
    }

    @Test
    void gitFailureOutputRedactsUriCredentials() throws Exception {
        GitRepository repository = repository("https://example.test/project.git");
        Path workDir = Files.createTempDirectory("hohenheim-git-redaction");

        GitRepository.GitResult result = repository.execute(List.of("/bin/sh", "-c",
            "printf '%s\\n' 'fatal: https://deploy:super-secret@example.test/project.git'; exit 1"),
            workDir.toFile(), null);

        assertThat(result.success()).isFalse();
        assertThat(result.output())
            .contains("https://[REDACTED]@example.test/project.git")
            .doesNotContain("deploy")
            .doesNotContain("super-secret");

        // SUCCESS output is sanitized too: a repo's own insteadOf/submodule
        // config can echo a credentialed URL even when the command exits 0.
        GitRepository.GitResult success = repository.execute(List.of("/bin/sh", "-c",
            "printf '%s\\n' 'remote: https://deploy:success-secret@example.test/project.git'"),
            workDir.toFile(), null);
        assertThat(success.success()).isTrue();
        assertThat(success.output())
            .contains("https://[REDACTED]@example.test/project.git")
            .doesNotContain("deploy")
            .doesNotContain("success-secret");
    }

    @Test
    void gitTimeoutRemainsEffectiveWhileOutputIsDrainedAndKillsTheGroup() throws Exception {
        Path workDir = Files.createTempDirectory("hohenheim-git-timeout");
        Path childPidFile = workDir.resolve("child.pid");
        GitRepository repository = new GitRepository("https://example.test/project.git", "main",
            false, false, null, 250);
        String command = noisyCommand(childPidFile);

        long started = System.nanoTime();
        GitRepository.GitResult result = repository.execute(
            List.of("/bin/sh", "-c", command), workDir.toFile(), null);
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

        long childPid = Long.parseLong(Files.readString(childPidFile).trim());
        awaitDead(childPid);
        assertThat(result.success()).isFalse();
        assertThat(result.output()).contains("timed out");
        assertThat(elapsedMillis).isLessThan(10_000);
    }

    @Test
    void interruptingGitKillsTheCompleteProcessGroup() throws Exception {
        Path workDir = Files.createTempDirectory("hohenheim-git-interrupt");
        Path childPidFile = workDir.resolve("child.pid");
        GitRepository repository = repository("https://example.test/project.git");
        AtomicBoolean interrupted = new AtomicBoolean();
        Thread runner = Thread.ofPlatform().start(() -> {
            try {
                repository.execute(List.of("/bin/sh", "-c", noisyCommand(childPidFile)),
                    workDir.toFile(), null);
            } catch (InterruptedException e) {
                interrupted.set(true);
            }
        });

        awaitFile(childPidFile);
        long childPid = Long.parseLong(Files.readString(childPidFile).trim());
        runner.interrupt();
        runner.join(10_000);

        assertThat(runner.isAlive()).isFalse();
        assertThat(interrupted).isTrue();
        awaitDead(childPid);
    }

    @Test
    void buildTimeoutDrainsOutputAndKillsTheCompleteProcessGroup() throws Exception {
        Path siteDir = Files.createTempDirectory("hohenheim-build-timeout");
        Path childPidFile = siteDir.resolve("child.pid");
        Map<String, Object> sourceSettings = Map.of("build_timeout", 1);
        GitDeployment deployment = new GitDeployment(9401, null, null, Map.of(), sourceSettings,
            repository("https://example.test/project.git"), siteDir.toFile(), null);
        String command = noisyCommand(childPidFile);

        long started = System.nanoTime();
        boolean succeeded = deployment.runBuild(siteDir.toFile(), command);
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

        long childPid = Long.parseLong(Files.readString(childPidFile).trim());
        awaitDead(childPid);
        assertThat(succeeded).isFalse();
        assertThat(deployment.getLog())
            .contains("Build started")
            .contains("Build failed (timed out after 1 seconds)")
            .doesNotContain(command);
        assertThat(elapsedMillis).isLessThan(10_000);
    }

    @Test
    void secretBearingBuildCommandIsNeverCapturedInDeploymentLog() throws Exception {
        Path siteDir = Files.createTempDirectory("hohenheim-build-command-secret");
        GitDeployment deployment = new GitDeployment(9402, null, null, Map.of(), Map.of(),
            repository("https://example.test/project.git"), siteDir.toFile(), null);
        String secret = "build-command-secret-9402";
        String command = "printf '%s\\n' 'operator-safe output' "
            + "'cloning https://deploy:build-url-secret@example.test/project.git' # " + secret;

        assertThat(deployment.runBuild(siteDir.toFile(), command)).isTrue();
        assertThat(deployment.getLog())
            .contains("Build started")
            .contains("operator-safe output")
            .contains("Build succeeded")
            // Credentialed URLs echoed by a SUCCESSFUL build are redacted too.
            .contains("https://[REDACTED]@example.test/project.git")
            .doesNotContain("build-url-secret")
            .doesNotContain(command)
            .doesNotContain(secret);
    }

    private static GitRepository repository(String url) {
        return new GitRepository(url, "main", false, false, null);
    }

    private static String noisyCommand(Path childPidFile) {
        return "trap '' TERM; sleep 600 & echo $! > '" + childPidFile
            + "'; yes process-output & wait";
    }

    private static void awaitFile(Path path) throws Exception {
        for (int i = 0; i < 100; i++) {
            if (Files.exists(path) && !Files.readString(path).isBlank()) {
                return;
            }
            Thread.sleep(25);
        }
        throw new AssertionError("Timed out waiting for " + path);
    }

    private static void awaitDead(long pid) throws Exception {
        for (int i = 0; i < 100; i++) {
            if (ProcessHandle.of(pid).map(process -> !process.isAlive()).orElse(true)) {
                return;
            }
            Thread.sleep(25);
        }
        throw new AssertionError("Process " + pid + " survived process-group cleanup");
    }
}
