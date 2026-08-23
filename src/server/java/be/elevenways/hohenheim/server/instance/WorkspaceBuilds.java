package be.elevenways.hohenheim.server.instance;

import be.elevenways.hohenheim.model.BuildOperationModel;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.hohenheim.server.build.BuildLog;
import be.elevenways.hohenheim.server.build.BuildQuota;
import be.elevenways.hohenheim.server.build.SandboxedBuilds;
import be.elevenways.hohenheim.server.instance.InstanceService.Resolved;
import be.elevenways.hohenheim.server.orm.RecordStamp;
import be.elevenways.hohenheim.server.runtime.ContainerState;
import be.elevenways.hohenheim.server.runtime.ExecSupport;
import be.elevenways.hohenheim.server.runtime.InstanceStatus;
import be.elevenways.hohenheim.server.source.DeployStatuses;
import be.elevenways.hohenheim.server.source.GitProviderClient;
import be.elevenways.hohenheim.server.source.GitProviders;
import be.elevenways.hohenheim.server.source.GitRepository;
import be.elevenways.hohenheim.server.source.SiteSources;
import be.elevenways.hohenheim.source.GitSourceSchema;
import be.elevenways.protoblast.common.Blast;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.zenit.common.orm.activity.ActivityLog;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.validation.Violations;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * THE workspace deploy verb: check the source out and build it INSIDE the container, as the
 * workspace's own uid, then restart the workload.
 *
 * AIDEV-NOTE: inside, not beside. An application builds in a sandbox on the control plane
 * because its artifact is an IMAGE; a workspace has no artifact -- the checkout and whatever
 * the build writes are the workspace's own files, in its own home, owned by its own uid. A
 * host-side checkout would have to be copied in and re-owned, and the copy is where the
 * ownership, the .gitignore'd build output and the running process stop agreeing.
 *
 * AIDEV-NOTE: the provider token reaches the clone through the exec ENVIRONMENT and nothing
 * else. {@code GitProviders.credentialEnv} hands back GIT_CONFIG_* variables carrying an
 * Authorization header, so the credential is never a URL component and never a
 * {@code .git/config} entry -- {@code WorkspaceKindTest} greps the volume after a clone
 * precisely because "the token is not written down" is the kind of claim that rots silently.
 *
 * @author Jelle De Loecker
 * @since  0.1.0
 */
public final class WorkspaceBuilds {

    /** Where a workspace's source lives inside its home volume. */
    public static final String CHECKOUT_PATH = WorkspaceKind.HOME_PATH + "/app";

    /** What the checkout prints its commit identity behind; see {@link #markedCommit}. */
    static final String COMMIT_MARKER = "hohenheim-commit:";

    /** Hard wall-clock cap on one in-container checkout or build. */
    static final long BUILD_TIMEOUT_MS = 20 * 60 * 1000;

    private final @NonNull InstanceService instances;

    public WorkspaceBuilds() {
        this(new InstanceService());
    }

    public WorkspaceBuilds(@NonNull InstanceService instances) {
        this.instances = instances;
    }

    /**
     * What one deploy did, for the surface that asked for it.
     *
     * @param status      the state the workload came back in
     * @param operationId the {@code build_operations} row this deploy is recorded on
     */
    public record Outcome(@Nullable String commitSha, boolean built, @NonNull String output,
                          @NonNull InstanceStatus status, int operationId) {
    }

    /**
     * Whether deploying this record means deploying its SOURCE: a workspace that names a
     * repository. {@link InstanceService#deploy} asks this to pick its lane.
     *
     * <p>A workspace with no repository is NOT a source deploy and stays a plain
     * container up, which is what an operator who wants a bare box asks for.</p>
     */
    public static boolean deploysSource(@Nullable Row instance) {

        if (instance == null) {
            return false;
        }

        InstanceKindHandler handler = InstanceKinds.getHandler(instance.get(InstanceModel.KIND));

        if (handler == null || !WorkspaceKind.ID.equals(handler.typeId())) {
            return false;
        }

        return instance.get(InstanceModel.SETTINGS) instanceof Map<?, ?> map
            && SiteSources.hasRepository(castSettings(map));
    }

    /**
     * Check the source out, build it, and restart the workspace.
     *
     * AIDEV-NOTE: the workload is brought UP first (and only if it is not already), because
     * the checkout is an exec INSIDE it. That is what makes this the whole deploy of a
     * source-declared workspace rather than only the half a forge webhook needed: the first
     * press of Deploy has no container at all, a push has one that is already serving.
     *
     * AIDEV-NOTE: and only if the TRIGGER may. A webhook deploy of a workspace that is
     * down stops here -- see {@link DeployStartPolicy}. The decision is taken after the
     * durable operation row exists, so a push that changes nothing is still visible as a
     * push that arrived.
     *
     * @param  ref the branch/tag/sha to deploy, or null for the source's declared branch
     * @throws Violations naming the refusal (no repository, declined start, checkout, build)
     */
    public @NonNull Outcome deploy(int instanceId, @Nullable String ref,
                                   @NonNull DeployTrigger trigger) {

        // The same gate a power action asks for: a deploy replaces the running process.
        HohenheimAccess.requireOperationCapability(instanceId, HohenheimAccess.POWER);

        Resolved resolved = this.instances.resolve(instanceId);
        requireWorkspace(resolved);
        Map<String, Object> settings = resolved.settings();

        if (!SiteSources.hasRepository(settings)) {
            throw Violations.ofForm(violation("source_no_repository"));
        }

        String branch = ref != null && !ref.isBlank() ? ref : declaredBranch(settings);
        BuildLog log = new BuildLog(BuildQuota.fromSettings().logBytes());
        long startedAt = System.currentTimeMillis();
        // The durable in-flight mark, SandboxedBuilds' contract verbatim: it lands before
        // any daemon work, so a controller that dies mid-deploy leaves `running` behind as
        // visible evidence instead of a clean-looking absence.
        int operationId = startOperation(instanceId, branch);

        // The workload's live state, read ONCE: the trigger policy needs it, and so does
        // the bring-up below -- and asking the daemon twice would let the two answers
        // disagree about the very thing being decided.
        ContainerState state = this.instances.liveStatus(instanceId).state();
        Microcopy declined =
            DeployStartPolicy.declineToStart(trigger, state, resolved.row());

        if (declined != null) {
            String message = DeployStartPolicy.textOf(declined);
            log.line("[hohenheim] deploying " + branch);
            log.line("[hohenheim] not deployed: " + message);
            // REFUSED, not FAILED: nothing broke. The row is the honest record that the
            // push arrived, which branch it named, and what was decided about it.
            finish(operationId, BuildOperationModel.STATUS_REFUSED, null, message, log,
                startedAt);
            reportDeclined(settings, message);
            throw Violations.ofForm(declined);
        }

        try {
            DeployStatuses.report(settings, null, GitProviderClient.StatusState.PENDING,
                DeployStatuses.CONTEXT_DEPLOY, "Deploying", null);
            log.line("[hohenheim] deploying " + branch);
            ensureRunning(instanceId, state);
            String commitSha = checkout(resolved, branch, settings, log);
            boolean built = build(resolved, settings, log);
            // The workload restarts LAST: the files it will serve are on disk and owned by
            // the uid it comes back as, so there is no window where a live process reads a
            // half-written checkout. restartWorkload and not restart: restart re-enters
            // InstanceService.deploy, whose workspace branch is THIS method.
            InstanceStatus status = this.instances.restartWorkload(instanceId);
            ActivityLog.record(Models.get(InstanceModel.class), instanceId,
                InstanceService.ACTIVITY_DEPLOY_ACTION, trigger.word());
            DeployStatuses.report(settings, commitSha, GitProviderClient.StatusState.SUCCESS,
                DeployStatuses.CONTEXT_DEPLOY, "Deployed", null);
            finish(operationId, BuildOperationModel.STATUS_SUCCEEDED, commitSha, null, log,
                startedAt);
            return new Outcome(commitSha, built, log.text(), status, operationId);
        } catch (RuntimeException failed) {
            String message = String.valueOf(failed.getMessage());
            // Appended THROUGH the log, so the secrets the checkout registered are redacted
            // out of a refusal that quotes git's own output back.
            log.line("[hohenheim] deploy failed: " + message);
            finish(operationId, BuildOperationModel.STATUS_FAILED, null, message, log,
                startedAt);
            reportFailure(settings, null, message);
            throw failed;
        }
    }

    /**
     * Bring the workload up unless it already is: the checkout runs inside it.
     *
     * AIDEV-NOTE: {@code liveStatus} never throws, so an absent container reads as ABSENT
     * and gets deployed rather than blowing up here.
     */
    private void ensureRunning(int instanceId, @NonNull ContainerState state) {
        if (state != ContainerState.RUNNING) {
            this.instances.deployWorkload(instanceId);
        }
    }

    /** Tell the forge the push landed and was deliberately not deployed. */
    private static void reportDeclined(@NonNull Map<String, Object> settings,
                                       @NonNull String message) {
        try {
            DeployStatuses.report(settings, null, GitProviderClient.StatusState.FAILURE,
                DeployStatuses.CONTEXT_DEPLOY, "Not deployed: " + message, null);
        } catch (RuntimeException unreported) {
            Blast.log("WORKSPACE: could not report the declined deploy -",
                unreported.getMessage());
        }
    }

    /** {@link #deploy} for fire-and-forget callers (webhooks); refusals are logged. */
    public void deployQuietly(int instanceId, @Nullable String ref,
                              @NonNull DeployTrigger trigger) {
        try {
            deploy(instanceId, ref, trigger);
        } catch (RuntimeException refused) {
            Blast.log("WORKSPACE: deploy of workspace", instanceId, "refused -",
                refused.getMessage());
        }
    }

    /**
     * Clone or fetch the declared ref into {@link #CHECKOUT_PATH}, inside the container.
     *
     * @return the commit the checkout landed on
     */
    public String checkout(@NonNull Resolved resolved, @NonNull String ref,
                    @NonNull Map<String, Object> settings, @NonNull BuildLog log) {

        String boundUrl = GitProviders.boundCloneUrl(settings);
        String repository = boundUrl != null ? boundUrl : str(settings.get("repository_url"));

        if (repository.isEmpty()) {
            throw Violations.ofForm(violation("source_no_repository"));
        }

        Map<String, String> credentials = Map.of();

        if (boundUrl != null) {
            try {
                Map<String, String> env = GitProviders.credentialEnv(settings);
                credentials = env == null ? Map.of() : env;
            } catch (IOException unavailable) {
                throw Violations.ofForm(violation("source_checkout_failed")
                    .withArg("reason", String.valueOf(unavailable.getMessage())));
            }
        }

        // Registered BEFORE the first append: a builder we do not control decides what it
        // echoes, and git prints a remote's URL back on plenty of ordinary failures. The
        // embedded half covers a raw URL stored before InstanceDeclarations refused those.
        for (String value : credentials.values()) {
            log.redact(value);
        }
        String embedded = GitRepository.embeddedCredential(repository);
        if (embedded != null) {
            log.redact(embedded);
        }

        // AIDEV-NOTE: the URL handed to git carries NO credential, and no `-c` option is
        // passed to `git clone` either -- `git clone -c k=v` PERSISTS the setting into the
        // new repository's config, which is exactly how a token ends up written into the
        // volume this method promises never to write it into. The credential travels only
        // in GIT_CONFIG_* environment variables, which git reads and never records.
        String quotedRef = shellQuote(ref);
        String quotedUrl = shellQuote(repository);
        String quotedDir = shellQuote(CHECKOUT_PATH);
        String script = "set -e\n"
            + "if [ -d " + quotedDir + "/.git ]; then\n"
            + "  git -C " + quotedDir + " remote set-url origin " + quotedUrl + "\n"
            + "  git -C " + quotedDir + " fetch --prune --tags origin " + quotedRef + "\n"
            + "  git -C " + quotedDir + " checkout --detach FETCH_HEAD\n"
            + "else\n"
            + "  rm -rf " + quotedDir + "\n"
            + "  git clone --branch " + quotedRef + " " + quotedUrl + " " + quotedDir + "\n"
            + "fi\n"
            + "echo " + shellQuote(COMMIT_MARKER) + "$(git -C " + quotedDir
            + " rev-parse HEAD)\n";

        ExecSupport.ExecOutcome run = exec(resolved, script,
            ExecSupport.ExecOptions.in(WorkspaceKind.HOME_PATH).withEnv(credentials));
        log.append(run.outputTail());

        if (!run.succeeded()) {
            throw Violations.ofForm(violation("source_checkout_failed")
                .withArg("reason", tail(run.outputTail())));
        }

        String commit = markedCommit(run.outputTail());

        if (commit.isBlank()) {
            throw Violations.ofForm(violation("source_checkout_failed")
                .withArg("reason", "no commit identity"));
        }

        return commit;
    }

    /**
     * Run the build command inside the checkout.
     *
     * @return whether a build command was declared at all
     */
    public boolean build(@NonNull Resolved resolved, @NonNull Map<String, Object> settings,
                  @NonNull BuildLog log) {

        String command = WorkspaceKind.buildCommandOf(settings,
            RuntimeImages.requireFor(resolved.row()));

        if (command.isEmpty()) {
            return false;
        }

        ExecSupport.ExecOutcome run = exec(resolved, command,
            ExecSupport.ExecOptions.in(CHECKOUT_PATH));
        log.append(run.outputTail());

        if (!run.succeeded()) {
            throw Violations.ofForm(violation("workspace_build_failed")
                .withArg("reason", tail(run.outputTail())));
        }

        return true;
    }

    /** Run one shell snippet inside the workload, as the workspace's own uid. */
    private ExecSupport.@NonNull ExecOutcome exec(@NonNull Resolved resolved,
                                                  @NonNull String script,
                                                  ExecSupport.@NonNull ExecOptions options) {
        if (!(resolved.runtime() instanceof ExecSupport support)) {
            throw Violations.ofForm(violation("exec_unsupported"));
        }
        try {
            return support.runExec(resolved.spec(), List.of("/bin/bash", "-lc", script),
                options, BUILD_TIMEOUT_MS);
        } catch (IOException failed) {
            throw Violations.ofForm(violation("workspace_exec_failed")
                .withArg("reason", String.valueOf(failed.getMessage())));
        }
    }

    // -- the durable operation record ------------------------------------------

    /**
     * The {@code running} row, written before any daemon work.
     *
     * AIDEV-NOTE: the SAME record every sandboxed build writes, deliberately. "What did my
     * last deploy do and what did it print" is one question whatever ran it, and the
     * Deploys tab reads one table -- a workspace-only history table would be a second
     * vocabulary over one job. What a workspace build does NOT have is an artifact, so
     * image_id/tag/peak_disk stay null by construction and never look like a promotable
     * image.
     */
    private static int startOperation(int instanceId, @NonNull String branch) {
        BuildOperationModel model = Models.get(BuildOperationModel.class);
        Row row = model.createEmptyRow();
        row.set(BuildOperationModel.BUILDER_KIND, BuildOperationModel.KIND_WORKSPACE);
        row.set(BuildOperationModel.FOR_MODEL, InstanceModel.MODEL_ID.toString());
        row.set(BuildOperationModel.FOR_ID, instanceId);
        row.set(BuildOperationModel.STATUS, BuildOperationModel.STATUS_RUNNING);
        row.set(BuildOperationModel.SOURCE_REF, branch);
        row.set(BuildOperationModel.TIMEOUT_SECONDS, (int) (BUILD_TIMEOUT_MS / 1000));
        row.set(BuildOperationModel.STARTED_AT, Instant.now());
        model.save(row);
        return row.get(BuildOperationModel.ID);
    }

    /** Stamp the terminal status, the log and the timings; a NARROW write, never a save. */
    private static void finish(int operationId, @NonNull String status,
                               @Nullable String commitSha, @Nullable String failureReason,
                               @NonNull BuildLog log, long startedAt) {
        BuildOperationModel model = Models.get(BuildOperationModel.class);
        Row row = model.findById(operationId);
        if (row == null) {
            return;
        }
        Instant finished = Instant.now();
        RecordStamp.on(model, row)
            .set(BuildOperationModel.STATUS, status)
            .set(BuildOperationModel.SOURCE_REF, commitSha != null
                ? commitSha : row.get(BuildOperationModel.SOURCE_REF))
            .set(BuildOperationModel.FAILURE_REASON, failureReason)
            .set(BuildOperationModel.LOG, log.text())
            .set(BuildOperationModel.FINISHED_AT, finished)
            .set(BuildOperationModel.DURATION_MS,
                (int) (finished.toEpochMilli() - startedAt))
            .write();
        model.pruneHistory(InstanceModel.MODEL_ID.toString(),
            row.get(BuildOperationModel.FOR_ID), SandboxedBuilds.historyPerOwner());
    }

    /** The branch a source declares, defaulting to {@code main}. */
    static @NonNull String declaredBranch(@NonNull Map<String, Object> settings) {
        String branch = str(settings.get(GitSourceSchema.BRANCH));
        return branch.isEmpty() ? "main" : branch;
    }

    private static void requireWorkspace(@NonNull Resolved resolved) {
        if (!WorkspaceKind.ID.equals(resolved.handler().typeId())) {
            throw Violations.ofForm(violation("workspace_kind_required"));
        }
    }

    private static void reportFailure(@NonNull Map<String, Object> settings,
                                      @Nullable String commitSha, @Nullable String reason) {
        try {
            DeployStatuses.report(settings, commitSha, GitProviderClient.StatusState.FAILURE,
                DeployStatuses.CONTEXT_DEPLOY, "Deploy failed: " + reason, null);
        } catch (RuntimeException unreported) {
            Blast.log("WORKSPACE: could not report the deploy failure -",
                unreported.getMessage());
        }
    }

    /** POSIX single-quoting: the only quoting that survives an arbitrary ref or URL. */
    static @NonNull String shellQuote(@NonNull String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }

    /**
     * The commit the checkout printed behind its marker.
     *
     * AIDEV-NOTE: a MARKER, not "the last line". A driver hands back stdout and stderr
     * concatenated in whichever order it collected them, and git writes "Cloning into..."
     * to stderr -- so the last line of a perfectly successful clone was the clone's own
     * progress message, and the commit identity silently became a 32-character sentence.
     */
    static @NonNull String markedCommit(@NonNull String text) {
        for (String line : text.split("\n")) {
            int marker = line.indexOf(COMMIT_MARKER);
            if (marker >= 0) {
                return line.substring(marker + COMMIT_MARKER.length()).strip();
            }
        }
        return "";
    }

    private static @NonNull String tail(@NonNull String text) {
        String trimmed = text.strip();
        return trimmed.length() <= 2000 ? trimmed
            : trimmed.substring(trimmed.length() - 2000);
    }

    private static @NonNull Microcopy violation(@NonNull String key) {
        return Microcopy.of(key).withFilter("scope", "violations");
    }

    private static @NonNull String str(@Nullable Object value) {
        return value == null ? "" : value.toString().trim();
    }

    @SuppressWarnings("unchecked")
    private static @NonNull Map<String, Object> castSettings(@NonNull Map<?, ?> settings) {
        return (Map<String, Object>) settings;
    }
}
