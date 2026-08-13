package be.elevenways.hohenheim.server.source;

import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.hohenheim.server.ProcessConfinement;
import be.elevenways.hohenheim.server.SystemUsers;
import be.elevenways.hohenheim.server.security.NftRunner;
import be.elevenways.hohenheim.server.security.ProcessNetworkPolicy;
import be.elevenways.hohenheim.server.sitetype.SiteTypeHandler;
import be.elevenways.hohenheim.server.sitetype.types.CommandSiteType;
import be.elevenways.hohenheim.server.sitetype.types.StaticSiteType;
import be.elevenways.hohenheim.test.HohenheimTestRuntime;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Covers Git command validation, output draining, and process-group cleanup. */
class GitDeploymentTest {

    /** A real site type, so fixtures carry the shape a deployment actually gets. */
    private static final SiteTypeHandler SPAWNING_TYPE = new CommandSiteType();

    /** Builds but never spawns: the type whose unconfined build is a lane of its own. */
    private static final SiteTypeHandler STATIC_TYPE = new StaticSiteType();

    /** nft chain and table names are controller-namespaced, so isolation needs an identity. */
    @BeforeAll
    static void controllerIdentity() {
        HohenheimTestRuntime.ensureDatasource();
    }

    /**
     * The IDENTITY-LESS build, pinned as the DOCUMENTED RESIDUAL it is rather than as a
     * refusal that does not happen.
     *
     * The exposure is real: a repository's own build content (a postinstall script, a
     * Makefile rule) executes as the Hohenheim daemon, and no per-uid deny can be keyed on
     * the control plane's own identity. Refusing it here for a STATIC site -- the case
     * where the build is the site's ONLY code execution, so the "its runtime is equally
     * exposed anyway" argument has nothing to point at -- was built and reverted the same
     * day; see {@code GitDeployment.isolateBuild}'s note for the trade-off and the real
     * fix. What is asserted here is what the code DOES: the build runs, and it says so.
     *
     * The refusals that DO happen are one layer up and are pinned by
     * {@code WorkloadIdentityTest}: enforcement is on by DEFAULT (so no site reaches this
     * at all on a default install) and TENANT-managed sites are refused unconditionally
     * whatever the setting says (so the hostile-repo threat model never reaches it either).
     */
    @Test
    void anIdentityLessBuildRunsUnconfinedAndSaysSoInTheDeployLog() throws Exception {
        Path siteDir = Files.createTempDirectory("hohenheim-build-no-identity");
        Path ranMarker = siteDir.resolve("build-ran.txt");
        String command = "printf 'x' > '" + ranMarker + "'; echo build-ran";

        // 1. A site type whose RUNTIME shares the absent identity: the build runs, and the
        //    deploy log an operator reads names the exposure and the action.
        GitDeployment spawning = new GitDeployment(9407, null, SPAWNING_TYPE, Map.of(), Map.of(),
            repository("https://example.test/project.git"), siteDir.toFile(), null);
        assertThat(spawning.runBuild(siteDir.toFile(), command))
            .as("step 1: with the dedicated-user requirement turned off, the build runs")
            .isTrue();
        assertThat(spawning.getLog())
            .as("step 1: and the operator is told, out loud, that it is not isolated")
            .contains("NOT network-isolated")
            .contains("Configure a system user");
        assertThat(Files.exists(ranMarker)).as("step 1: the build really ran").isTrue();

        // 2. A STATIC site -- which never spawns, so this build is its whole execution --
        //    is treated IDENTICALLY today. This assertion is the residual, written down: if
        //    a later wave closes it, this step is what will fail and point at the decision.
        Files.delete(ranMarker);
        GitDeployment staticSite = new GitDeployment(9408, null, STATIC_TYPE, Map.of(), Map.of(),
            repository("https://example.test/project.git"), siteDir.toFile(), null);
        assertThat(staticSite.runBuild(siteDir.toFile(), command))
            .as("step 2: RESIDUAL -- a site that never spawns still runs repo content as"
                + " the daemon; GitDeployment.isolateBuild records why and what the fix is")
            .isTrue();
        assertThat(staticSite.getLog())
            .as("step 2: with the same warning, so it is never silent")
            .contains("NOT network-isolated");

        // 3. The moment there IS an identity, the deny policy is applied and keyed on it --
        //    so the gap is exactly "no uid to key on", not "isolation is optional here".
        RecordingNft recording = new RecordingNft();
        SystemUsers.RunAsUser runAs = new SystemUsers.RunAsUser("site-9408", 4408, null,
            siteDir.toString());
        ProcessNetworkPolicy.overrideForTest(new ProcessNetworkPolicy(recording,
            () -> true, Path.of("/etc/resolv.conf")));
        try {
            GitDeployment identified = new GitDeployment(9408, null, STATIC_TYPE, Map.of(),
                Map.of(), repository("https://example.test/project.git"), siteDir.toFile(),
                runAs);
            identified.runBuild(siteDir.toFile(), command);
            assertThat(identified.getLog())
                .as("step 3: with an identity to confine, isolation is not the refusal")
                .doesNotContain("Build refused");
            assertThat(String.join("\n", recording.rulesets))
                .as("step 3: and the deny policy is keyed on THAT uid")
                .contains("out_uid_4408");
        } finally {
            ProcessNetworkPolicy.overrideForTest(null);
        }
    }

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
        GitDeployment deployment = new GitDeployment(9401, null, SPAWNING_TYPE, Map.of(), sourceSettings,
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
        GitDeployment deployment = new GitDeployment(9402, null, SPAWNING_TYPE, Map.of(), Map.of(),
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

    /**
     * The host build lane's two halves of confinement: the site's RUNTIME variables never
     * reach the build, and a build that cannot be confined is refused instead of run.
     *
     * AIDEV-NOTE: step 1 reads the build's OWN environment back off disk, not the deploy
     * log. A test that only checked the log would pass against a build that received the
     * database password and simply did not print it -- the leak is the delivery, and a
     * repo's postinstall script does not need the log to exfiltrate it. The build-time
     * variable is the positive control: it proves env delivery works at all, so an absent
     * runtime secret means "not passed", never "nothing is passed".
     */
    @Test
    void theBuildSeesBuildTimeVariablesOnlyAndIsRefusedWhenItCannotBeConfined()
            throws Exception {
        Path siteDir = Files.createTempDirectory("hohenheim-build-confinement");
        Path envFile = siteDir.resolve("env.txt");
        String runtimeSecret = "runtime-database-password-9403";
        String buildValue = "build-time-value-9403";
        Map<String, Object> typeSettings = Map.of("environment_variables",
            Map.of("DATABASE_PASSWORD", runtimeSecret));
        Map<String, Object> sourceSettings = Map.of("build_environment_variables",
            Map.of("BUILD_TOKEN", buildValue));
        String dumpEnvironment = "env > '" + envFile + "'; echo build-ran";

        // 1. The legitimate build still succeeds (the positive anchor) and its environment
        //    carries the build-time variable and NOT the site's runtime secret.
        GitDeployment deployment = new GitDeployment(9403, null, SPAWNING_TYPE, typeSettings,
            sourceSettings, repository("https://example.test/project.git"), siteDir.toFile(),
            null);
        assertThat(deployment.runBuild(siteDir.toFile(), dumpEnvironment))
            .as("step 1: the legitimate build must still succeed").isTrue();
        String environment = Files.readString(envFile);
        assertThat(environment)
            .as("step 1: the declared BUILD-time variable must reach the build")
            .contains("BUILD_TOKEN=" + buildValue);
        assertThat(environment)
            .as("step 1: the site's RUNTIME secret must never reach the build")
            .doesNotContain(runtimeSecret)
            .doesNotContain("DATABASE_PASSWORD");
        assertThat(deployment.getLog())
            .as("step 1: nor the deploy log a tenant with site manage can read")
            .contains("Build succeeded")
            .doesNotContain(runtimeSecret);

        // 2. A host that cannot enforce the build quota REFUSES the build by name rather
        //    than running it uncapped, and the command never runs at all.
        Files.delete(envFile);
        ProcessConfinement.overrideForTest(new ProcessConfinement.Availability(
            ProcessConfinement.Mode.NONE, "no systemd on this host (test override)"));
        try {
            GitDeployment unconfined = new GitDeployment(9404, null, SPAWNING_TYPE, typeSettings,
                sourceSettings, repository("https://example.test/project.git"),
                siteDir.toFile(), null);
            assertThat(unconfined.runBuild(siteDir.toFile(), dumpEnvironment))
                .as("step 2: an unenforceable build quota must refuse the build").isFalse();
            assertThat(unconfined.getLog())
                .as("step 2: and the refusal must name the mechanism and the reason")
                .contains("Build refused")
                .contains("no systemd on this host (test override)");
            assertThat(Files.exists(envFile))
                .as("step 2: the refused build command must never have run").isFalse();
        } finally {
            ProcessConfinement.overrideForTest(null);
        }

        // 3. A uid-dropped build asks the managed-process tier's applier for that uid's
        //    policy BEFORE it spawns, and a host where the policy cannot be applied
        //    refuses the build instead of running it wide open.
        RecordingNft recording = new RecordingNft();
        SystemUsers.RunAsUser runAs = new SystemUsers.RunAsUser("site-9405", 4405, null,
            siteDir.toString());
        ProcessNetworkPolicy.overrideForTest(new ProcessNetworkPolicy(recording,
            () -> false, Path.of("/etc/resolv.conf")));
        try {
            GitDeployment unisolated = new GitDeployment(9405, null, SPAWNING_TYPE, typeSettings,
                sourceSettings, repository("https://example.test/project.git"),
                siteDir.toFile(), runAs);
            assertThat(unisolated.runBuild(siteDir.toFile(), dumpEnvironment))
                .as("step 3: a build that cannot be network-isolated must be refused")
                .isFalse();
            assertThat(unisolated.getLog())
                .as("step 3: named, with the operator action in it")
                .contains("Build refused")
                .contains("per-process network policy is not enforceable");
            assertThat(recording.rulesets)
                .as("step 3: a disabled applier never even reaches nft").isEmpty();
            assertThat(Files.exists(envFile))
                .as("step 3: the refused build command must never have run").isFalse();
        } finally {
            ProcessNetworkPolicy.overrideForTest(null);
        }

        // 4. With enforcement available, the SAME build applies the deny policy keyed on
        //    the build's own run-as uid -- the identity half is what makes it isolation.
        ProcessNetworkPolicy.overrideForTest(new ProcessNetworkPolicy(recording,
            () -> true, Path.of("/etc/resolv.conf")));
        try {
            GitDeployment isolated = new GitDeployment(9405, null, SPAWNING_TYPE, typeSettings,
                sourceSettings, repository("https://example.test/project.git"),
                siteDir.toFile(), runAs);
            isolated.runBuild(siteDir.toFile(), dumpEnvironment);
            assertThat(isolated.getLog())
                .as("step 4: isolation must NOT be the thing that refuses this build")
                .doesNotContain("Build refused");
            assertThat(String.join("\n", recording.rulesets))
                .as("step 4: the applied ruleset must deny the tenant ranges for THIS uid")
                .contains("meta skuid 4405 ip daddr 169.254.0.0/16 drop")
                .contains("meta skuid 4405 ip daddr 10.0.0.0/8 drop")
                .contains("out_uid_4405");
        } finally {
            ProcessNetworkPolicy.overrideForTest(null);
        }
    }

    /**
     * The host's build time quota BINDS this lane: a site that declares a longer timeout
     * than the operator allows is killed at the operator's number, not at its own.
     */
    @Test
    void aSiteDeclaredBuildTimeoutCannotOutlastTheHostsBuildTimeQuota() throws Exception {
        Path siteDir = Files.createTempDirectory("hohenheim-build-quota-timeout");
        Path childPidFile = siteDir.resolve("child.pid");
        Integer restore = HohenheimSettings.VALUES.getValue(
            HohenheimSettings.Builds.TIMEOUT_SECONDS);
        HohenheimSettings.VALUES.setValue(HohenheimSettings.Builds.TIMEOUT_SECONDS, 10);
        try {
            // A site asking for ten minutes on a host that allows ten seconds.
            GitDeployment deployment = new GitDeployment(9406, null, SPAWNING_TYPE, Map.of(),
                Map.of("build_timeout", 600),
                repository("https://example.test/project.git"), siteDir.toFile(), null);

            long started = System.nanoTime();
            assertThat(deployment.runBuild(siteDir.toFile(), noisyCommand(childPidFile)))
                .as("a build over the host's time quota must fail").isFalse();
            long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

            awaitDead(Long.parseLong(Files.readString(childPidFile).trim()));
            assertThat(deployment.getLog())
                .as("and it must be killed at the HOST's number, not the site's")
                .contains("Build failed (timed out after 10 seconds)");
            assertThat(elapsedMillis)
                .as("the site's own 600s must never have been waited out")
                .isLessThan(60_000);
        } finally {
            HohenheimSettings.VALUES.setValue(HohenheimSettings.Builds.TIMEOUT_SECONDS, restore);
        }
    }

    /**
     * An nft runner that accepts a ruleset and then answers the kernel-truth read-back
     * with exactly what it was given, so the applier's own verification passes.
     */
    private static final class RecordingNft implements NftRunner {

        private final List<String> rulesets = new ArrayList<>();
        private final List<String> applied = new ArrayList<>();

        @Override
        public NftRunner.Result run(List<String> nftArgs, String stdin) {
            if (stdin != null) {
                this.rulesets.add(stdin);
                this.applied.clear();
                for (String line : stdin.split("\n")) {
                    if (line.startsWith("add rule ")) {
                        int chainEnd = line.indexOf(" out_uid_");
                        int ruleStart = line.indexOf(' ', chainEnd + 1);
                        this.applied.add(line.substring(ruleStart + 1).trim());
                    }
                }
                return new NftRunner.Result(0, "", "");
            }
            if (nftArgs.contains("list")) {
                StringBuilder listing = new StringBuilder(
                    "type filter hook output priority -10; policy accept;\n");
                for (String rule : this.applied) {
                    listing.append(rule).append('\n');
                }
                return new NftRunner.Result(0, listing.toString(), "");
            }
            return new NftRunner.Result(0, "", "");
        }
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
