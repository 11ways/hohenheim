package be.elevenways.hohenheim.server.application;

import be.elevenways.hohenheim.model.BuildOperationModel;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.server.instance.DeployStartPolicy;
import be.elevenways.hohenheim.server.instance.DeployTrigger;
import be.elevenways.hohenheim.server.instance.InstanceService;
import be.elevenways.hohenheim.server.source.DeployStatuses;
import be.elevenways.hohenheim.server.source.GitCheckout;
import be.elevenways.hohenheim.server.source.GitProviderClient;
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

import java.io.File;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * THE deploy verb of an application: check the source out, then converge a release.
 *
 * AIDEV-NOTE: this replaces the whole deleted host-slot deploy lane
 * ({@code GitSiteRequestHandler} + {@code GitDeployment} + {@code GitDeploymentQueue} +
 * the {@code deployments} table). That lane cloned into a numbered slot beside the site,
 * built on the HOST as a claimed unix uid, and swapped a symlink -- a second, weaker
 * blue/green implementation of what the release engine already does with health gating,
 * digest pinning and rollback. It had been unreachable since brief 5 dropped
 * {@code sites.source}; nothing here is a port of it.
 *
 * AIDEV-NOTE: an image-sourced application deploys too. It simply has no checkout, so the
 * verb collapses to the converge -- which is what re-resolves a moved tag to a fresh digest
 * and gates the new image behind the health probe like any other release.
 *
 * @author Jelle De Loecker
 * @since  0.1.0
 */
public final class ApplicationDeploys {

    private ApplicationDeploys() {
    }

    /**
     * Deploy an application: fresh checkout when it is git-sourced, then a converge.
     *
     * AIDEV-NOTE: the trigger policy runs FIRST, before the checkout. A push that may not
     * start this application spends no clone, no sandbox build and no image; what it does
     * spend is a durable {@code build_operations} row saying it arrived and was refused --
     * the same visibility the workspace lane gives, and the reason this is not a silent
     * skip.
     *
     * @param  ref the branch/tag/sha to deploy, or null for the source's declared branch
     * @return the release that ends up serving
     * @throws Violations naming the refusal (declined start, no repository, checkout,
     *         build, probe)
     */
    public static ApplicationReleases.@NonNull Release deploy(int applicationId,
                                                              @Nullable String ref,
                                                              @NonNull DeployTrigger trigger) {

        Row application = ApplicationReleases.requireApplication(applicationId);
        Map<String, Object> settings = ApplicationReleases.storedSettings(application);
        Map<String, Object> overrides = new LinkedHashMap<>();
        String commitSha = null;

        // THE policy, on the STORED status of the release the operator's stop settled on.
        // An application owns no container of its own, so the daemon has nothing to
        // report about it -- and the daemon could not tell a stop from a crash anyway.
        Microcopy declined = DeployStartPolicy.declineToStartStored(trigger,
            ApplicationReleases.ownedServing(applicationId), application);

        if (declined != null) {
            String message = DeployStartPolicy.textOf(declined);
            recordRefusal(applicationId, settings, ref, message);
            reportDeclined(settings, message);
            throw Violations.ofForm(declined);
        }

        if (SiteSources.hasRepository(settings)) {
            String branch = ref != null && !ref.isBlank() ? ref : declaredBranch(settings);
            File checkout = GitCheckout.directoryFor(InstanceModel.MODEL_ID, applicationId);
            try {
                DeployStatuses.report(settings, null, GitProviderClient.StatusState.PENDING,
                    DeployStatuses.CONTEXT_DEPLOY, "Deploying", null);
                commitSha = GitCheckout.materialize(InstanceModel.MODEL_ID, applicationId,
                    branch, settings, checkout);
            } catch (Violations refused) {
                reportFailure(settings, null, refused.getMessage());
                throw refused;
            } catch (Exception failed) {
                reportFailure(settings, null, String.valueOf(failed.getMessage()));
                throw Violations.ofForm(Microcopy.of("source_checkout_failed")
                    .withFilter("scope", "violations")
                    .withArg("reason", String.valueOf(failed.getMessage())));
            }
            overrides.put("build_context", checkout.getAbsolutePath());
            overrides.put("commit_sha", commitSha);
        }

        try {
            ApplicationReleases.Release release =
                ApplicationReleases.converge(applicationId, overrides);
            // The SAME action word every other instance deploy is recorded under -- a
            // second spelling would split one verb across two vocabularies.
            ActivityLog.record(Models.get(InstanceModel.class), applicationId,
                InstanceService.ACTIVITY_DEPLOY_ACTION, trigger.word());
            if (commitSha != null) {
                DeployStatuses.report(settings, commitSha,
                    GitProviderClient.StatusState.SUCCESS, DeployStatuses.CONTEXT_DEPLOY,
                    "Deployed", null);
            }
            return release;
        } catch (RuntimeException failed) {
            reportFailure(settings, commitSha, String.valueOf(failed.getMessage()));
            throw failed;
        }
    }

    /** {@link #deploy} for fire-and-forget callers (webhooks); refusals are logged. */
    public static void deployQuietly(int applicationId, @Nullable String ref,
                                     @NonNull DeployTrigger trigger) {
        try {
            deploy(applicationId, ref, trigger);
        } catch (RuntimeException refused) {
            Blast.log("APPLICATION: deploy of application", applicationId, "refused -",
                refused.getMessage());
        }
    }

    /**
     * The durable record of a deploy that was REFUSED before anything was spent.
     *
     * AIDEV-NOTE: one terminal row rather than the workspace lane's running-then-finished
     * pair, because the decision is taken before any daemon work: there is no in-flight
     * window for a dying controller to leave behind. The status is REFUSED and never
     * FAILED -- nothing broke, something was decided -- and the reason is the sentence a
     * person reads on the Deploys tab, not a violation key.
     */
    private static void recordRefusal(int applicationId, @NonNull Map<String, Object> settings,
                                      @Nullable String ref, @NonNull String message) {
        BuildOperationModel model = Models.get(BuildOperationModel.class);
        Row row = model.createEmptyRow();
        String branch = ref != null && !ref.isBlank() ? ref : declaredBranch(settings);
        Instant now = Instant.now();
        row.set(BuildOperationModel.BUILDER_KIND,
            BuildOperationModel.kindOrDefault(settings.get("builder")));
        row.set(BuildOperationModel.FOR_MODEL, InstanceModel.MODEL_ID.toString());
        row.set(BuildOperationModel.FOR_ID, applicationId);
        row.set(BuildOperationModel.STATUS, BuildOperationModel.STATUS_REFUSED);
        row.set(BuildOperationModel.SOURCE_REF, branch);
        row.set(BuildOperationModel.FAILURE_REASON, message);
        row.set(BuildOperationModel.LOG, "[hohenheim] deploying " + branch
            + "\n[hohenheim] not deployed: " + message + "\n");
        row.set(BuildOperationModel.STARTED_AT, now);
        row.set(BuildOperationModel.FINISHED_AT, now);
        row.set(BuildOperationModel.DURATION_MS, 0);
        model.save(row);
    }

    /** Tell the forge the push landed and was deliberately not deployed. */
    private static void reportDeclined(@NonNull Map<String, Object> settings,
                                       @NonNull String message) {
        try {
            DeployStatuses.report(settings, null, GitProviderClient.StatusState.FAILURE,
                DeployStatuses.CONTEXT_DEPLOY, "Not deployed: " + message, null);
        } catch (RuntimeException unreported) {
            Blast.log("APPLICATION: could not report the declined deploy -",
                unreported.getMessage());
        }
    }

    /** The branch a source declares, defaulting to {@code main}. */
    public static @NonNull String declaredBranch(@NonNull Map<String, Object> settings) {
        Object branch = settings.get(GitSourceSchema.BRANCH);
        String named = branch == null ? "" : branch.toString().trim();
        return named.isEmpty() ? "main" : named;
    }

    private static void reportFailure(@NonNull Map<String, Object> settings,
                                      @Nullable String commitSha, @Nullable String reason) {
        try {
            DeployStatuses.report(settings, commitSha, GitProviderClient.StatusState.FAILURE,
                DeployStatuses.CONTEXT_DEPLOY, "Deploy failed: " + reason, null);
        } catch (RuntimeException unreported) {
            Blast.log("APPLICATION: could not report the deploy failure -",
                unreported.getMessage());
        }
    }
}
