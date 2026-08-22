package be.elevenways.hohenheim.server.instance;

import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.hohenheim.server.instance.InstanceService.Resolved;
import be.elevenways.hohenheim.server.runtime.ExecSupport;
import be.elevenways.hohenheim.server.source.DeployStatuses;
import be.elevenways.hohenheim.server.source.GitProviderClient;
import be.elevenways.hohenheim.server.source.GitProviders;
import be.elevenways.hohenheim.server.source.SiteSources;
import be.elevenways.hohenheim.source.GitSourceSchema;
import be.elevenways.protoblast.common.Blast;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.zenit.common.orm.activity.ActivityLog;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.validation.Violations;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.io.IOException;
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

    /** What one deploy did, for the surface that asked for it. */
    public record Outcome(@Nullable String commitSha, boolean built, @NonNull String output) {
    }

    /**
     * Check the source out, build it, and restart the workspace.
     *
     * @param  ref the branch/tag/sha to deploy, or null for the source's declared branch
     * @throws Violations naming the refusal (no repository, checkout, build)
     */
    public @NonNull Outcome deploy(int instanceId, @Nullable String ref,
                                   @NonNull String reason) {

        // The same gate a power action asks for: a deploy replaces the running process.
        HohenheimAccess.requireOperationCapability(instanceId, HohenheimAccess.POWER);

        Resolved resolved = this.instances.resolve(instanceId);
        requireWorkspace(resolved);
        Map<String, Object> settings = resolved.settings();

        if (!SiteSources.hasRepository(settings)) {
            throw Violations.ofForm(violation("source_no_repository"));
        }

        String branch = ref != null && !ref.isBlank() ? ref : declaredBranch(settings);
        String commitSha;
        StringBuilder output = new StringBuilder();

        try {
            DeployStatuses.report(settings, null, GitProviderClient.StatusState.PENDING,
                DeployStatuses.CONTEXT_DEPLOY, "Deploying", null);
            commitSha = checkout(resolved, branch, settings, output);
            boolean built = build(resolved, settings, output);
            // The workload restarts LAST: the files it will serve are on disk and owned by
            // the uid it comes back as, so there is no window where a live process reads a
            // half-written checkout.
            this.instances.restart(instanceId);
            ActivityLog.record(Models.get(InstanceModel.class), instanceId,
                InstanceService.ACTIVITY_DEPLOY_ACTION, reason);
            DeployStatuses.report(settings, commitSha, GitProviderClient.StatusState.SUCCESS,
                DeployStatuses.CONTEXT_DEPLOY, "Deployed", null);
            return new Outcome(commitSha, built, output.toString());
        } catch (RuntimeException failed) {
            reportFailure(settings, null, String.valueOf(failed.getMessage()));
            throw failed;
        }
    }

    /** {@link #deploy} for fire-and-forget callers (webhooks); refusals are logged. */
    public void deployQuietly(int instanceId, @Nullable String ref, @NonNull String reason) {
        try {
            deploy(instanceId, ref, reason);
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
                    @NonNull Map<String, Object> settings, @NonNull StringBuilder output) {

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
        output.append(run.outputTail());

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
                  @NonNull StringBuilder output) {

        String command = WorkspaceKind.buildCommandOf(settings,
            RuntimeImages.requireFor(resolved.row()));

        if (command.isEmpty()) {
            return false;
        }

        ExecSupport.ExecOutcome run = exec(resolved, command,
            ExecSupport.ExecOptions.in(CHECKOUT_PATH));
        output.append(run.outputTail());

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
}
