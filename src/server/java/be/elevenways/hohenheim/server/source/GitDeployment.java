package be.elevenways.hohenheim.server.source;

import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.server.ProcessConfinement;
import be.elevenways.hohenheim.server.SystemUsers;
import be.elevenways.hohenheim.server.build.BuildQuota;
import be.elevenways.hohenheim.server.process.ProcessGroupSupport;
import be.elevenways.hohenheim.server.security.ProcessNetworkPolicy;
import be.elevenways.hohenheim.server.sitetype.SiteHealth;
import be.elevenways.hohenheim.server.sitetype.SiteRequestHandler;
import be.elevenways.hohenheim.server.upstream.UpstreamKindHandler;
import be.elevenways.hohenheim.server.util.EnvVars;
import be.elevenways.protoblast.common.Blast;
import be.elevenways.zenit.common.orm.datasource.Row;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Orchestrates a single deploy attempt using the dual-slot strategy.
 */
public class GitDeployment {

    private final int siteId;
    private final Row site;
    private final UpstreamKindHandler typeHandler;
    private final Map<String, Object> typeSettings;
    private final Map<String, Object> sourceSettings;
    private final GitRepository gitRepo;
    private final File siteDir;
    private final SystemUsers.@Nullable RunAsUser runAs;

    /** Step + build output captured for the deployment record (capped). */
    private final StringBuilder deployLog = new StringBuilder();
    private static final int DEPLOY_LOG_CAP = 200_000;

    public GitDeployment(int siteId, Row site, UpstreamKindHandler typeHandler,
                          Map<String, Object> typeSettings, Map<String, Object> sourceSettings,
                          GitRepository gitRepo, File siteDir, SystemUsers.@Nullable RunAsUser runAs) {
        this.siteId = siteId;
        this.site = site;
        this.typeHandler = typeHandler;
        this.typeSettings = typeSettings;
        this.sourceSettings = sourceSettings;
        this.gitRepo = gitRepo;
        this.siteDir = siteDir;
        this.runAs = runAs;
    }

    /**
     * Execute the deploy. Returns the new inner handler on success, null on failure.
     * The caller is responsible for swapping the handler and managing the old one.
     */
    public DeployResult execute(SiteRequestHandler oldHandler) {
        try {
            // Determine target slot at execution time
            String activeSlot = readActiveSlot();
            String targetSlot = "a".equals(activeSlot) ? "b" : "a";
            File targetDir = new File(siteDir, targetSlot);

            Blast.log("GIT: site", siteId, "deploying to slot", targetSlot,
                "(active:", activeSlot + ")");
            log("Deploying to slot " + targetSlot + " (active: " + activeSlot + ")");

            // Step 1: Prepare standby slot
            if (Thread.interrupted()) throw new InterruptedException();
            if (!prepareSlot(targetDir)) {
                return DeployResult.failure("Failed to prepare slot " + targetSlot);
            }

            // Step 2: Record commit SHA
            String commitSha = gitRepo.getCurrentCommit(targetDir);
            Blast.log("GIT: site", siteId, "at commit", commitSha);
            log("Checked out commit " + commitSha);

            // Step 3: Run build command
            if (Thread.interrupted()) throw new InterruptedException();
            String buildCommand = (String) sourceSettings.get("build_command");
            if (buildCommand != null && !buildCommand.isEmpty()) {
                if (!runBuild(targetDir, buildCommand)) {
                    // The checkout succeeded, so this failure has a commit identity --
                    // provider status reporting attributes it to the right sha.
                    return DeployResult.failure("Build command failed", commitSha);
                }
            }

            // Step 4: Create new inner handler with adjusted paths
            if (Thread.interrupted()) throw new InterruptedException();
            Map<String, Object> adjustedSettings = adjustPaths(targetDir, commitSha);
            SiteRequestHandler newHandler = typeHandler.createHandler(site, adjustedSettings);
            return activate(newHandler, targetSlot, commitSha);

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log("Deploy cancelled");
            return DeployResult.failure("Deploy cancelled");
        } catch (Exception e) {
            Blast.log("GIT: deploy error for site", siteId, "-", e.getMessage());
            log("Deploy error: " + e.getMessage());
            return DeployResult.failure(e.getMessage());
        }
    }

    /**
     * Activate an existing slot without cloning or building: create its handler and,
     * once healthy, flip the active symlink. Used by deploys and by rollback.
     */
    public DeployResult activateSlot(String slot) {
        try {
            File slotDir = new File(siteDir, slot);
            if (!slotDir.isDirectory() || !gitRepo.isMatchingRepo(slotDir)) {
                return DeployResult.failure("Slot " + slot + " has no matching checkout to roll back to");
            }
            String commitSha = gitRepo.getCurrentCommit(slotDir);
            log("Rolling back to slot " + slot + " at commit " + commitSha);
            Map<String, Object> adjustedSettings = adjustPaths(slotDir, commitSha);
            SiteRequestHandler newHandler = typeHandler.createHandler(site, adjustedSettings);
            return activate(newHandler, slot, commitSha);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log("Rollback cancelled");
            return DeployResult.failure("Rollback cancelled");
        } catch (Exception e) {
            Blast.log("GIT: rollback error for site", siteId, "-", e.getMessage());
            log("Rollback error: " + e.getMessage());
            return DeployResult.failure(e.getMessage());
        }
    }

    /** Health-gate the new handler, then atomically flip the active symlink to its slot. */
    private DeployResult activate(SiteRequestHandler newHandler, String targetSlot, String commitSha)
            throws InterruptedException {
        if (newHandler.getHealth() != SiteHealth.UP) {
            Blast.log("GIT: site", siteId, "waiting for new handler to become healthy");
            log("Waiting for the new handler to become healthy");
            long deadline = System.currentTimeMillis() + 60_000; // 60s max wait
            while (newHandler.getHealth() != SiteHealth.UP) {
                if (Thread.interrupted()) {
                    newHandler.destroy();
                    throw new InterruptedException();
                }
                if (System.currentTimeMillis() > deadline) {
                    Blast.log("GIT: site", siteId, "new handler did not become healthy in 60s");
                    newHandler.destroy();
                    return DeployResult.failure(
                        "New handler did not become healthy within 60 seconds", commitSha);
                }
                Thread.sleep(500);
            }
        }

        flipSymlink(targetSlot);
        log("Activated slot " + targetSlot);
        return DeployResult.success(newHandler, commitSha, targetSlot);
    }

    /** Append a line to the captured deploy log (silently capped). */
    private void log(String line) {
        int remaining = DEPLOY_LOG_CAP - deployLog.length();
        if (remaining <= 0) {
            return;
        }
        int lineLength = Math.min(line.length(), remaining);
        deployLog.append(line, 0, lineLength);
        if (lineLength < remaining) {
            deployLog.append('\n');
        }
    }

    /** The captured step + build output of this deploy attempt. */
    public String getLog() {
        return deployLog.toString();
    }

    /**
     * Reconnect: create a handler from the existing live slot without cloning or building.
     */
    public SiteRequestHandler reconnect() {
        String activeSlot = readActiveSlot();
        if (activeSlot == null) return null;

        File activeDir = new File(siteDir, activeSlot);
        if (!activeDir.isDirectory() || !gitRepo.isMatchingRepo(activeDir)) {
            return null;
        }

        Map<String, Object> adjustedSettings = adjustPaths(activeDir, gitRepo.getCurrentCommit(activeDir));
        return typeHandler.createHandler(site, adjustedSettings);
    }

    // -----------------------------------------------------------------------

    private boolean prepareSlot(File targetDir) throws InterruptedException {
        if (targetDir.isDirectory() && gitRepo.isMatchingRepo(targetDir)) {
            // Existing matching repo: fetch + reset
            Blast.log("GIT: site", siteId, "updating existing slot", targetDir.getName());
            log("Updating existing slot " + targetDir.getName() + " (fetch + reset)");
            GitRepository.GitResult result = gitRepo.fetchAndReset(targetDir);
            if (!result.success()) {
                Blast.log("GIT: fetch+reset failed, trying fresh clone:", result.output());
                log("Fetch + reset failed, retrying with a fresh clone: " + result.output());
                deleteDirectory(targetDir);
                return freshClone(targetDir);
            }
            return true;
        } else {
            // Fresh clone
            if (targetDir.exists()) deleteDirectory(targetDir);
            return freshClone(targetDir);
        }
    }

    private boolean freshClone(File targetDir) throws InterruptedException {
        targetDir.getParentFile().mkdirs();
        if (runAs != null) {
            // Create the slot dir as Hohenheim (which owns the parent siteDir), then
            // hand ownership to the site user so the uid-dropped clone/build can write
            // into it. See docs/hohenext-roadmap.md for the slot-ownership model.
            targetDir.mkdirs();
            if (!chownToSiteUser(targetDir)) {
                Blast.log("GIT: chown of slot to uid", runAs.uid(), "failed for site", siteId);
                return false;
            }
        }
        log("Cloning repository into slot " + targetDir.getName());
        GitRepository.GitResult result = gitRepo.clone(targetDir);
        if (!result.success()) {
            Blast.log("GIT: clone failed for site", siteId, "-", result.output());
            log("Clone failed: " + result.output());
            return false;
        }
        return true;
    }

    /**
     * The HOST build lane of the non-docker git site types (static, node): the operator's
     * {@code build_command} run as the site's own uid, under the managed-process tier's
     * confinement.
     *
     * AIDEV-NOTE: DECIDED 2026-08-08 -- this lane stays a host process and is CONFINED
     * LIKE THE TIER IT BELONGS TO, rather than being routed through BuildSandbox. The
     * evidence for that choice: (1) BuildSandbox produces a docker IMAGE from a declared
     * builder image, and these site types declare no image and need the checkout's FILES
     * back in the slot -- routing them there is a redesign of the types, not a fix (its
     * {@code runPhase} CAN run arbitrary argv and collect a directory, so the missing
     * piece is the IMAGE and the host Docker dependency, not the plumbing; re-checked
     * 2026-08-13, see the isolateBuild note for the full weighing); (2) the
     * command is OPERATOR-authored, never tenant-authored (ManageSiteResource.fieldBindings
     * hands a delegated tenant name/enabled/description only, and the API has no
     * source_settings write), which is the same trust class as CommandSiteType's command,
     * i.e. the managed-process tier; (3) the REPO CONTENT is not operator-authored, so a
     * postinstall script in a tenant's repository is hostile-capable code -- which is why
     * confinement here is not optional.
     *
     * Two things therefore changed with this note. The site's RUNTIME
     * {@code environment_variables} are no longer merged in: {@code build_environment_variables}
     * (GitSourceSchema, declared {@code .secret()}) is the build-time channel, and the
     * deploy log this method captures is readable by any tenant holding site manage over
     * the PaaS API, so a dependency's install script had a straight path from the site's
     * DATABASE_PASSWORD to a tenant-readable log. And the spawn now carries the tier's own
     * isolation: {@link ProcessNetworkPolicy} keyed on the build's run-as uid (the same
     * chain the runtime process of this very site is refused without) plus the
     * {@link ProcessConfinement} cgroup scope sized by the build quota both lanes share.
     * A build that cannot be confined is REFUSED -- BuildSandbox's own doctrine, "a build
     * that starts unprotected is worse than a build that does not start".
     *
     * What this lane still does NOT have, stated so nobody reads more into it: no
     * container, no filesystem namespace and no capability BOUNDING set (see
     * SystemUsers.executionBuilder for why no_new_privs is the reachable half). It is the
     * host-process tier's floor, not the sandbox's.
     */
    boolean runBuild(File targetDir, String buildCommand) throws InterruptedException {
        String buildDir = (String) sourceSettings.get("build_directory");
        File workDir = targetDir;
        if (buildDir != null && !buildDir.isEmpty()) {
            workDir = new File(targetDir, buildDir);
        }

        // AIDEV-NOTE: the per-site build_timeout may TIGHTEN the host's build time quota,
        // never widen it -- the same "a quota may tighten the baseline, it may never widen
        // it" rule BuildQuota.effectivePidsLimit states for PIDs. A site-declared 24h
        // timeout used to make the operator's builds.timeout_seconds cap nothing at all on
        // this lane while the sandboxed lane honoured it.
        int quotaSec = BuildQuota.fromSettings().timeoutSeconds();
        Object timeoutObj = sourceSettings.get("build_timeout");
        int declaredSec = timeoutObj instanceof Integer t && t > 0 ? t : 600;
        int timeoutSec = Math.min(declaredSec, quotaSec);

        Blast.log("GIT: site", siteId, "build started");
        log("Build started");

        try {
            // Run the build under the site's system user when configured, matching the
            // runtime process's uid drop. Without this, a compromised repo's build script
            // would run as the (sudo-capable) Hohenheim user.
            List<String> build = List.of("sh", "-c", buildCommand);
            Map<String, String> env = new LinkedHashMap<>(SystemUsers.safeEnvironment(
                runAs != null ? runAs.home() : System.getProperty("user.home")));
            // BUILD-time variables only. The site's runtime environment_variables are
            // deliberately absent -- see the method note; this is the same separation
            // BuildRequest makes structural for the sandboxed lane.
            mergeEnvVars(env, sourceSettings.get("build_environment_variables"));
            if (runAs != null) {
                if (runAs.home() != null && !runAs.home().isBlank()) {
                    env.put("HOME", runAs.home());
                } else {
                    env.remove("HOME");
                }
            }

            List<String> confinement = confinementPrefix();
            if (confinement == null || !isolateBuild()) {
                return false;
            }
            if (!confinement.isEmpty()) {
                ProcessConfinement.contributeEnvironment(env);
            }
            ProcessBuilder pb = SystemUsers.executionBuilder(runAs, env, build, true,
                confinement);
            pb.directory(workDir);
            pb.redirectErrorStream(true);

            Process process = pb.start();
            ProcessGroupSupport.OutputCapture output = ProcessGroupSupport.drain(
                process.getInputStream(), "git-build-output-" + process.pid(), DEPLOY_LOG_CAP);
            boolean finished;
            try {
                finished = process.waitFor(timeoutSec, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                ProcessGroupSupport.terminate(process, runAs, ProcessGroupSupport.GRACEFUL_TERM_MS);
                output.finish();
                throw e;
            }
            if (!finished) {
                ProcessGroupSupport.TerminationResult termination =
                    ProcessGroupSupport.terminate(process, runAs, ProcessGroupSupport.GRACEFUL_TERM_MS);
                output.finish();
                Blast.log("GIT: build timed out after", timeoutSec, "seconds for site", siteId);
                log("Build failed (timed out after " + timeoutSec + " seconds)");
                if (!termination.successful()) {
                    String cleanup = "Build process group survived cleanup: "
                        + termination.finalGroupState();
                    Blast.log("GIT:", cleanup, "for site", siteId);
                    log(cleanup);
                }
                log(GitRepository.sanitizeOutput(output.output()));
                return false;
            }
            output.finish();

            int exitCode = process.exitValue();
            if (exitCode != 0) {
                Blast.log("GIT: build failed (exit", exitCode + ") for site", siteId);
                log("Build failed (exit " + exitCode + ")");
                // Build output is credential-sanitized on success and failure
                // alike: a repo's own config can echo a credentialed URL.
                String sanitized = GitRepository.sanitizeOutput(output.output());
                log(sanitized);
                // Log last 50 lines of output
                String[] lines = sanitized.split("\n");
                int start = Math.max(0, lines.length - 50);
                for (int i = start; i < lines.length; i++) {
                    Blast.log("  BUILD:", lines[i]);
                }
                return false;
            }

            log(GitRepository.sanitizeOutput(output.output()));
            log("Build succeeded");
            Blast.log("GIT: build succeeded for site", siteId);
            return true;

        } catch (InterruptedException e) {
            throw e;
        } catch (Exception e) {
            Blast.log("GIT: build error for site", siteId, "-", e.getMessage());
            log("Build failed");
            return false;
        }
    }

    private void mergeEnvVars(Map<String, String> env, Object envVarsObj) {
        env.putAll(EnvVars.toMap(envVarsObj));
    }

    /**
     * The cgroup scope the build runs in, sized by the quota BOTH build lanes share.
     *
     * @return the spawn prefix (empty when nothing is declared), or null when a declared
     *         quota cannot be enforced on this host -- the build is then refused
     */
    private @Nullable List<String> confinementPrefix() {
        BuildQuota quota = BuildQuota.fromSettings();
        try {
            return ProcessConfinement.scopePrefix("build-site-" + siteId,
                quota.memoryMb(), quota.cpus());
        } catch (IllegalStateException unenforceable) {
            Blast.log("GIT: build refused for site", siteId, "-", unenforceable.getMessage());
            log("Build refused: the build quota cannot be enforced on this host. "
                + unenforceable.getMessage());
            return null;
        }
    }

    /**
     * Deny the build the tenant-network vocabulary its own runtime process is denied.
     *
     * AIDEV-NOTE: this is the SAME uid chain the deleted process lane's isolate() applied
     * (idempotent, so build and runtime share one chain). Without a run-as uid there is
     * nothing to key a deny on: the only identity the build would have is the DAEMON's
     * own, and a rule keyed on that cuts the control plane off from itself.
     *
     * AIDEV-NOTE: this note used to justify the no-uid warn-and-allow with "a site whose
     * runtime cannot be isolated cannot spawn either, so this refusal never removes a lane
     * the site otherwise had". That sentence is FALSE and was struck 2026-08-13: a STATIC
     * git site builds and never spawns, so for it the build is the site's only code
     * execution and running it unconfined does add a lane. What is TRUE is stated below;
     * read it before concluding this branch is unguarded.
     *
     * The no-uid build is reachable only past TWO refusals that do happen:
     * (1) {@code process.require_dedicated_user} defaults TRUE, so on a DEFAULT install
     *     {@code WorkloadIdentity.forSite} throws and GitSiteRequestHandler never exists --
     *     the whole site faults, build included
     *     ({@code WorkloadIdentityTest.enforcementIsOnByDefaultAndNothingRefusesTurningItBackOn});
     * (2) a TENANT-managed site -- any site a non-admin holds {@code manage} on, which is
     *     the whole hostile-repo threat model -- is refused UNCONDITIONALLY, whatever that
     *     setting says, and fails closed on an unreadable grant table
     *     ({@code WorkloadIdentityTest.tenantManagedSitesAreRefusedUnconditionally}).
     *
     * The RESIDUAL, named rather than hidden: an OPERATOR-owned site on an install where
     * the operator explicitly turned that requirement off builds as the daemon, and its
     * network half is unenforceable because the only uid available to key a deny on is the
     * control plane's own. Refusing it here was BUILT AND REVERTED the same day: it closes
     * a real lane for static sites, but it breaks a configuration the product otherwise
     * supports and its only cheap remedy was deleting the build-log assertions from
     * {@code GitDeploymentFlowTest}.
     *
     * AIDEV-NOTE: this note used to end "the honest fix is to route an identity-less build
     * through BuildSandbox", which CONTRADICTED the {@code runBuild} note thirty lines
     * above that argues the same routing is a redesign and not a fix. Investigated and
     * settled 2026-08-13, in favour of {@code runBuild}: read that note, this branch is
     * where the residual LIVES but not where it gets closed. Three candidates were weighed
     * and all three are worse than the documented residual. BuildSandbox: its
     * {@code runPhase} really can run arbitrary argv and read a directory back, so the
     * shape exists, but there is no image to run an operator's {@code build_command} IN
     * ({@code builds.builder_image} is a daemonless image BUILDER, not a toolchain), a
     * per-site build image cannot be defaulted, and building a node site's native addons
     * against a container's libc and then running them under the HOST node is a broken
     * deploy rather than an isolated one -- and it would make Docker a hard dependency of
     * the deliberately Docker-free tier, so a Docker-less host would refuse the build,
     * which is the reverted refusal wearing a different hat. systemd {@code IPAddressDeny}
     * on the scope this build ALREADY gets: MEASURED 2026-08-13 to be silently ignored in
     * a USER scope ({@code systemd-run --user --scope -p IPAddressDeny=any} still reached
     * 1.1.1.1:80), and a user manager is the ordinary non-root deployment
     * ({@link ProcessConfinement}), so it would claim a boundary it does not have. An nft
     * chain keyed on the build's cgroup instead of a uid: {@code socket cgroupv2} resolves
     * the path to a cgroup id when the RULE IS ADDED, and {@code systemd-run --scope}
     * execs immediately, so it needs a two-phase spawn -- and with no passwordless
     * {@code sudo nft} on a dev host it could only ever be proven against the
     * {@code RecordingNft} fake, which is not proof.
     *
     * @return false when the policy cannot be applied -- the build is refused
     */
    private boolean isolateBuild() {
        if (runAs == null) {
            Blast.log("GIT: site", siteId, "has no system user, so its build runs as the",
                "Hohenheim daemon and CANNOT be network-isolated; configure a system user");
            log("Warning: this site has no system user, so the build runs as the Hohenheim"
                + " daemon and is NOT network-isolated. Configure a system user for it.");
            return true;
        }
        try {
            ProcessNetworkPolicy.current().apply(runAs.uid(), siteLabel());
            return true;
        } catch (IOException | RuntimeException refused) {
            // RuntimeException included deliberately: an applier that cannot resolve its
            // own table (no controller identity) failed to isolate just as completely as
            // one nft refused, and the generic "Build failed" this used to become named
            // neither the lane nor the reason.
            Blast.log("GIT: build refused for site", siteId, "-", refused.getMessage());
            log("Build refused: " + refused.getMessage());
            return false;
        }
    }

    /** The site's name for a refusal an operator has to act on; never an identity. */
    private String siteLabel() {
        Object name = site == null ? null : site.get(SiteModel.NAME);
        return name == null || name.toString().isBlank() ? "site-" + siteId : name.toString();
    }

    /**
     * Adjust type settings by resolving relative paths against the target slot.
     */
    private Map<String, Object> adjustPaths(File slotDir, String commitSha) {
        Map<String, Object> adjusted = new HashMap<>(typeSettings);

        String buildDir = (String) sourceSettings.get("build_directory");
        File baseDir = slotDir;
        if (buildDir != null && !buildDir.isEmpty()) {
            baseDir = new File(slotDir, buildDir);
        }

        // Resolve known path fields
        resolvePathField(adjusted, "script", baseDir);
        resolvePathField(adjusted, "root_path", baseDir);

        // For CommandSiteType: set working_directory to the base dir if not explicitly set
        String workDirValue = (String) adjusted.get("working_directory");
        if (workDirValue == null || workDirValue.isEmpty()) {
            adjusted.put("working_directory", baseDir.getAbsolutePath());
        } else {
            adjusted.put("working_directory", new File(baseDir, workDirValue).getAbsolutePath());
        }

        // Docker sites build their image from the checkout; expose the context dir so
        // DockerSiteRequestHandler builds-from-source instead of pulling a remote image.
        // Harmless for other site types, which ignore unknown settings.
        adjusted.put("build_context", baseDir.getAbsolutePath());
        // The source identity of what is about to be built, recorded on the build
        // operation. Harmless for site types that ignore it.
        if (commitSha != null && !commitSha.isBlank()) {
            adjusted.put("commit_sha", commitSha);
        }

        return adjusted;
    }

    private void resolvePathField(Map<String, Object> settings, String fieldName, File baseDir) {
        Object value = settings.get(fieldName);
        if (value instanceof String path && !path.isEmpty() && !path.startsWith("/")) {
            settings.put(fieldName, new File(baseDir, path).getAbsolutePath());
        }
    }

    private String readActiveSlot() {
        String name = activeSlotName(siteDir);
        return ("a".equals(name) || "b".equals(name)) ? name : null;
    }

    /**
     * The filename the {@code <siteDir>/active} symlink points at (the active deploy slot), or null
     * when there is no valid symlink. Shared with {@code GitSiteRequestHandler}.
     */
    static String activeSlotName(File siteDir) {
        Path symlink = siteDir.toPath().resolve("active");
        try {
            if (Files.isSymbolicLink(symlink)) {
                return Files.readSymbolicLink(symlink).getFileName().toString();
            }
        } catch (Exception ignored) {
            // unreadable/missing symlink -> no active slot
        }
        return null;
    }

    private void flipSymlink(String targetSlot) {
        Path activePath = siteDir.toPath().resolve("active");
        try {
            // Atomic symlink replacement: create temp, then rename over
            Path temp = siteDir.toPath().resolve("active.tmp." + System.nanoTime());
            Files.createSymbolicLink(temp, Path.of(targetSlot));
            Files.move(temp, activePath, StandardCopyOption.ATOMIC_MOVE);
        } catch (Exception e) {
            Blast.log("GIT: failed to flip symlink for site", siteId, "-", e.getMessage());
            // Fallback: delete + create (non-atomic)
            try {
                Files.deleteIfExists(activePath);
                Files.createSymbolicLink(activePath, Path.of(targetSlot));
            } catch (Exception e2) {
                Blast.log("GIT: symlink fallback also failed for site", siteId);
            }
        }
    }

    private void deleteDirectory(File dir) {
        if (dir == null || !dir.exists()) return;
        if (runAs != null) {
            // Slot contents may be owned by the site user; clear those as that user.
            // deleteTree() then removes whatever remains (Hohenheim-owned leftovers and
            // the now-empty slot dir, which Hohenheim can remove via parent-dir write).
            runProcess(SystemUsers.executionBuilder(runAs,
                SystemUsers.safeEnvironment(runAs.home()),
                List.of("rm", "-rf", dir.getAbsolutePath()), false));
        }
        deleteTree(dir);
    }

    private static void deleteTree(File dir) {
        if (dir == null || !dir.exists()) return;
        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) {
                if (f.isDirectory()) deleteTree(f);
                else f.delete();
            }
        }
        dir.delete();
    }

    /** Give the site user ownership of a freshly-created slot directory (sudo chown). */
    private boolean chownToSiteUser(File dir) {
        ProcessBuilder builder = new ProcessBuilder(
            "/usr/bin/sudo", "-n", "chown", String.valueOf(runAs.uid()), dir.getAbsolutePath());
        SystemUsers.setEnvironment(builder, SystemUsers.safeEnvironment(System.getProperty("user.home")));
        return runProcess(builder);
    }

    /** Run a short-lived helper process, draining its output; true on exit code 0. */
    private boolean runProcess(ProcessBuilder builder) {
        try {
            builder.redirectErrorStream(true);
            Process process = builder.start();
            try (var stdout = process.getInputStream()) {
                stdout.readAllBytes();
            }
            return process.waitFor() == 0;
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    // -----------------------------------------------------------------------

    public record DeployResult(boolean success, SiteRequestHandler handler,
                               String commitSha, String slot, String error) {

        static DeployResult success(SiteRequestHandler handler, String commitSha, String slot) {
            return new DeployResult(true, handler, commitSha, slot, null);
        }

        static DeployResult failure(String error) {
            return new DeployResult(false, null, null, null, error);
        }

        static DeployResult failure(String error, String commitSha) {
            return new DeployResult(false, null, commitSha, null, error);
        }
    }
}
