package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.HohenheimEndpoints;
import be.elevenways.hohenheim.model.BuildOperationModel;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.ReleaseOperationModel;
import be.elevenways.hohenheim.model.SiteDomainModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.server.application.ApplicationReleases;
import be.elevenways.hohenheim.server.application.ReleaseEngine;
import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.hohenheim.server.instance.InstanceKinds;
import be.elevenways.hohenheim.server.instance.WorkspaceBuilds;
import be.elevenways.hohenheim.server.source.GitWebhookHandler;
import be.elevenways.hohenheim.source.GitSourceSchema;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.protoblast.common.time.RelativeTimeWording;
import be.elevenways.zenit.cms.common.resource.RecordScopedPage;
import be.elevenways.zenit.common.conduit.Conduit;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.field.EnumField;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.result.ActionResult;
import be.elevenways.zenit.common.result.RenderTemplateResult;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.common.ui.Icon;
import be.elevenways.zenit.server.http.ReturnTarget;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Deploys tab on a record whose deploy has a HISTORY worth reading: an application's
 * releases, or a source-declared workspace's checkouts and builds. Captured logs,
 * deploy-now (and rollback, where releases exist) and the push webhook.
 *
 * AIDEV-NOTE: this REPLACED the site's Deployments tab when the release engine was
 * re-keyed to the application (phase-0 brief 7): the deploy history belongs to the
 * record that OWNS the releases, and an application that no site exposes yet still
 * deploys. The site keeps only what a site is -- a hostname.
 *
 * AIDEV-NOTE: the workspace lane reads {@code build_operations} instead of
 * {@code release_operations} and everything else is the same page, deliberately. A
 * workspace deploy has no release and no rollback (its home volume IS its state, and
 * nothing about a previous checkout survives to roll back TO), so those two controls
 * are the only difference the reader sees -- widening this page rather than growing a
 * second one is what keeps "where do I see my deploy" one answer.
 */
public final class InstanceDeploymentsPage implements RecordScopedPage<Row> {

    public static final String SLUG = "deployments";

    @Override public @NonNull Identifier id() { return Identifier.of("hohenheim", "instance_deployments"); }
    @Override public @NonNull Microcopy label() { return Microcopy.of("title").withFilter("scope", "deployments"); }
    @Override public @NonNull String slug() { return SLUG; }
    @Override public @NonNull Icon icon() { return Icon.of("rocket"); }

    /**
     * A record has this tab when deploying it means deploying a SOURCE: a release-managed
     * kind always does, a workspace does once it names a repository. A workspace with no
     * repository deploys a bare container and has no history to show.
     */
    @Override
    public boolean visibleFor(@NonNull Row record) {
        return InstanceKinds.isReleaseManaged(record.get(InstanceModel.KIND))
            || WorkspaceBuilds.deploysSource(record);
    }

    @Override
    public @NonNull ActionResult<?> render(@NonNull Conduit conduit,
                                           @NonNull AccessContext accessContext,
                                           @NonNull Row instance) {
        Integer instanceId = instance.get(InstanceModel.ID);
        Map<String, Object> vars = new HashMap<>();
        vars.put("title", CmsSupport.pageTitle(conduit, "instance_deployments",
            instance.get(InstanceModel.NAME)));
        vars.put("instanceId", instanceId);
        vars.put("instanceName", instance.get(InstanceModel.NAME));

        boolean releaseManaged = InstanceKinds.isReleaseManaged(instance.get(InstanceModel.KIND));
        // The trigger column is the release lane's own vocabulary (deploy/webhook/rollback);
        // a workspace build operation records no such word, and a column of blanks reads as
        // missing data rather than as "not applicable".
        vars.put("showTrigger", releaseManaged);
        vars.put("columnCount", releaseManaged ? 5 : 4);

        WithheldFailure failures = WithheldFailure.of(conduit);
        if (releaseManaged) {
            putReleaseVars(vars, instanceId, failures);
        } else {
            putWorkspaceVars(vars, instanceId, failures);
        }

        if (HohenheimAccess.isAdmin(accessContext)) {
            putAdminOnlyVars(vars, instance);
        }

        // The deploy/rollback forms echo this as _return so their handlers redirect
        // back to whichever panel rendered this page.
        vars.put("returnUrl", ReturnTarget.capture(conduit));
        // AIDEV-NOTE: the hidden field NAME comes from the framework constant --
        // ReturnTarget is server-only, so the common template cannot reach it.
        vars.put("returnParam", ReturnTarget.PARAM);
        vars.put("deployTarget", HohenheimEndpoints.INSTANCES_DEPLOY
            .with(HohenheimEndpoints.INSTANCE_ID, instanceId));
        vars.put("rollbackTarget", HohenheimEndpoints.INSTANCES_ROLLBACK
            .with(HohenheimEndpoints.INSTANCE_ID, instanceId));
        vars.put("recordTabs", recordTabs(conduit));
        vars.put("timeWording", RelativeTimeWording.resolve(
            conduit.getLocales(), conduit.getMessageResolver()));

        return new RenderTemplateResult(Identifier.of("hohenheim", "cms/instance-deployments"), vars);
    }

    /** The application lane: release operations, the serving commit and the rollback offer. */
    private static void putReleaseVars(Map<String, Object> vars, int instanceId, WithheldFailure failures) {
        ReleaseOperationModel model = Models.get(ReleaseOperationModel.class);
        List<Row> operations = model.findForOwner(InstanceModel.MODEL_ID.toString(), instanceId, 50);

        // AIDEV-NOTE: "in flight" is the model's own answer (findInFlight), never a status
        // list spelled here: this page used to list pending/deploying/probing and missed
        // switching and draining, so Deploy and Rollback were offered mid-switch.
        boolean inFlight = !model.findInFlight(InstanceModel.MODEL_ID.toString(), instanceId).isEmpty();
        vars.put("isDeploying", inFlight);
        vars.put("canRollback", !inFlight && ReleaseEngine.newestRetired(instanceId) != null);

        Row serving = ApplicationReleases.ownedServing(instanceId);
        vars.put("currentCommit", serving == null ? ""
            : shortSha(ApplicationReleases.storedSettings(serving).get("commit_sha")));

        List<Map<String, Object>> deployments = new ArrayList<>();
        for (Row row : operations) {
            deployments.add(entry(row.get(ReleaseOperationModel.ID),
                ReleaseOperationModel.STATUS, row.get(ReleaseOperationModel.STATUS),
                orEmpty(row.get(ReleaseOperationModel.KIND)),
                row.get(ReleaseOperationModel.IMAGE_ID),
                row.get(ReleaseOperationModel.DURATION_MS),
                failures.shown(row.get(ReleaseOperationModel.FAILURE_REASON)),
                row.get(ReleaseOperationModel.STARTED_AT),
                // The engine's step log carries daemon text (ReleaseEngine.reasonOf): operators
                // only. A workspace BUILD log below stays visible -- it is the output of the
                // tenant's own checkout and build, and without it they cannot fix a build.
                failures.operatorOnly(row.get(ReleaseOperationModel.STEP_LOG))));
        }
        vars.put("deployments", deployments);
    }

    /**
     * The workspace lane: the checkout+build operations WorkspaceBuilds records.
     *
     * AIDEV-NOTE: no rollback and no serving release. A workspace's state IS its home
     * volume, so there is no previous artifact to point back at -- offering the control
     * would be an affordance that can only refuse.
     */
    private static void putWorkspaceVars(Map<String, Object> vars, int instanceId, WithheldFailure failures) {

        BuildOperationModel model = Models.get(BuildOperationModel.class);
        List<Row> operations = model.findForOwner(InstanceModel.MODEL_ID.toString(),
            instanceId, 50);

        vars.put("isDeploying", operations.stream().anyMatch(row ->
            BuildOperationModel.STATUS_RUNNING.equals(row.get(BuildOperationModel.STATUS))));
        vars.put("canRollback", false);

        Row latest = model.latestSuccess(InstanceModel.MODEL_ID.toString(), instanceId);
        vars.put("currentCommit", latest == null ? ""
            : shortSha(latest.get(BuildOperationModel.SOURCE_REF)));

        List<Map<String, Object>> deployments = new ArrayList<>();
        for (Row row : operations) {
            deployments.add(entry(row.get(BuildOperationModel.ID),
                BuildOperationModel.STATUS, row.get(BuildOperationModel.STATUS), "",
                row.get(BuildOperationModel.SOURCE_REF),
                row.get(BuildOperationModel.DURATION_MS),
                failures.shown(row.get(BuildOperationModel.FAILURE_REASON)),
                row.get(BuildOperationModel.STARTED_AT),
                row.get(BuildOperationModel.LOG)));
        }
        vars.put("deployments", deployments);
    }

    /** One history row in the shape both lanes and the shared deploy-detail partial read. */
    private static @NonNull Map<String, Object> entry(@Nullable Object id, @NonNull EnumField statusField,
                                                     @Nullable Object status, @NonNull String reason,
                                                     @Nullable Object commit, @Nullable Object durationMs,
                                                     @NonNull String failure, @Nullable Instant startedAt,
                                                     @Nullable String log) {
        Map<String, Object> entry = new HashMap<>();
        entry.put("id", id);
        entry.put("status", orEmpty(status));
        entry.put("statusVariant", variantOf(statusField, status));
        entry.put("reason", reason);
        entry.put("commit", shortSha(commit));
        entry.put("duration", durationLabel(durationMs));
        entry.put("error", failure);
        entry.put("startedAtIso", startedAt != null ? startedAt.toString() : "");
        entry.put("log", log != null ? log : "");
        entry.put("hasLog", log != null && !log.isBlank());
        return entry;
    }

    /**
     * The ONLY place admin-only template vars may be populated (the webhook push URL +
     * secret): every sensitive var must be added inside this method so the single
     * isAdmin gate at the call site stays an allowlist. The webhook endpoint is
     * intercepted before hostname routing, so any hostname pointing at the proxy works;
     * an exposing site's first exact hostname is the copy-pastable choice.
     */
    private static void putAdminOnlyVars(Map<String, Object> vars, Row instance) {
        Integer instanceId = instance.get(InstanceModel.ID);
        Map<String, Object> settings = ApplicationReleases.storedSettings(instance);
        vars.put("webhookSecret", orEmpty(settings.get(GitSourceSchema.WEBHOOK_SECRET)));
        vars.put("webhookAutoDeploy",
            Boolean.TRUE.equals(settings.get(GitSourceSchema.AUTO_DEPLOY)));

        // AIDEV-NOTE: the git webhook is intercepted by SiteDispatcher BEFORE the zenit
        // conduit chain, so it is deliberately outside the Endpoint framework and has no
        // RouteTarget. Referencing the handler's own PREFIX constant is what keeps this
        // display URL from drifting away from the route that actually answers.
        String path = GitWebhookHandler.PREFIX + instanceId;
        String url = path;
        Row site = Models.get(SiteModel.class).find()
            .where(SiteModel.INSTANCE_ID.eq(instanceId))
            .where(SiteModel.DELETED_AT.isNull())
            .first();
        Row domain = site == null ? null : Models.get(SiteDomainModel.class).find()
            .where(SiteDomainModel.SITE_ID.eq(site.get(SiteModel.ID)))
            .where(SiteDomainModel.MATCH_TYPE.eq(SiteDomainModel.MATCH_EXACT))
            .first();
        if (domain != null) {
            String scheme = Boolean.TRUE.equals(domain.get(SiteDomainModel.FORCE_SSL))
                ? "https" : "http";
            url = scheme + "://" + domain.get(SiteDomainModel.HOSTNAME) + path;
        }
        vars.put("webhookUrl", url);
    }

    /**
     * The badge variant DECLARED on the status enum value itself, for either vocabulary.
     *
     * AIDEV-NOTE: read off the field rather than switched on here, so a new status carries
     * its colour everywhere at once -- and so the two status vocabularies this page renders
     * (release operations, build operations) need no mapping table between them.
     * Unknown/blank degrades to secondary, the honest answer for a value the vocabulary
     * does not contain.
     */
    private static String variantOf(@NonNull EnumField field, @Nullable Object status) {
        EnumField.EnumValue value = status == null
            ? null : field.getValues().get(String.valueOf(status));
        String color = value != null ? value.getColor() : null;
        return color != null ? color : "secondary";
    }

    private static String shortSha(Object sha) {
        String value = sha != null ? String.valueOf(sha) : "";
        return value.length() > 8 ? value.substring(0, 8) : value;
    }

    private static String durationLabel(Object durationMs) {
        if (!(durationMs instanceof Integer ms)) {
            return "";
        }
        if (ms < 1000) {
            return ms + " ms";
        }
        return String.format("%.1f s", ms / 1000.0);
    }

    private static String orEmpty(Object value) {
        return value != null ? String.valueOf(value) : "";
    }
}
