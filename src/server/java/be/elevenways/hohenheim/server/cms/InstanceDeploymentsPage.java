package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.RawValues;
import be.elevenways.hohenheim.model.OperationStatus;
import be.elevenways.hohenheim.HohenheimEndpoints;
import be.elevenways.hohenheim.HohenheimIds;
import be.elevenways.hohenheim.HohenheimMicrocopy;
import be.elevenways.hohenheim.HohenheimTemplateIds;
import be.elevenways.hohenheim.HohenheimParams;
import be.elevenways.hohenheim.HohenheimSlugs;
import be.elevenways.hohenheim.model.BuildOperationModel;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.PreviewDeploymentModel;
import be.elevenways.hohenheim.model.ReleaseOperationModel;
import be.elevenways.hohenheim.model.SiteDomainModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.model.WebhookDeliveryModel;
import be.elevenways.hohenheim.server.application.ApplicationReleases;
import be.elevenways.hohenheim.server.application.ReleaseEngine;
import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.hohenheim.server.instance.InstanceKinds;
import be.elevenways.hohenheim.server.instance.WorkspaceBuilds;
import be.elevenways.hohenheim.server.source.GitWebhookHandler;
import be.elevenways.hohenheim.server.source.WebhookOutcome;
import be.elevenways.hohenheim.source.GitSourceSchema;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.cms.common.page.CmsRoutes;
import be.elevenways.zenit.cms.common.panel.PanelEntry;
import be.elevenways.zenit.cms.common.panel.PanelRequest;
import be.elevenways.zenit.cms.common.resource.PanelResource;
import be.elevenways.zenit.cms.common.resource.RecordTab;
import be.elevenways.zenit.cms.server.panel.PanelActionOffers;
import be.elevenways.zenit.cms.server.render.action.ActionStateTranslator;
import be.elevenways.zenit.cms.server.render.action.RowOffer;
import be.elevenways.zenit.common.routing.ReturnPath;
import be.elevenways.zenit.common.conduit.Conduit;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.field.EnumField;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.result.ActionResult;
import be.elevenways.zenit.common.result.RenderTemplateResult;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.widget.common.data.WidgetBadge;
import be.elevenways.zenit.common.ui.Icon;
import be.elevenways.zenit.server.http.ReturnTarget;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;

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
public final class InstanceDeploymentsPage implements RecordTab.Rendered<Row> {

    @Override public @NonNull Identifier id() { return HohenheimIds.id("instance_deployments"); }
    @Override public @NonNull Microcopy label() { return HohenheimMicrocopy.DEPLOYMENTS.of("title"); }
    @Override public @NonNull String slug() { return HohenheimSlugs.Tab.DEPLOYMENTS; }
    @Override public @NonNull Icon icon() { return Icon.of("rocket"); }

    /**
     * A record has this tab when deploying it means deploying a SOURCE: a release-managed
     * kind always does, a workspace does once it names a repository. A workspace with no
     * repository deploys a bare container and has no history to show.
     */
    @Override
    public boolean visibleFor(@NonNull Row record, @NonNull AccessContext access) {
        return InstanceKinds.isReleaseManaged(record.get(InstanceModel.KIND))
            || WorkspaceBuilds.deploysSource(record);
    }

    @Override
    public @NonNull ActionResult<?> render(@NonNull PanelRequest request, @NonNull Row instance) {
        Conduit conduit = request.conduit();
        AccessContext accessContext = request.access();
        Integer instanceId = instance.get(InstanceModel.ID);
        Map<String, Object> vars = new HashMap<>();
        vars.put("title", CmsSupport.pageTitle(conduit, HohenheimMicrocopy.INSTANCE_DEPLOYMENTS,
            instance.get(InstanceModel.NAME)));
        vars.put("instanceId", instanceId);
        vars.put("instanceName", instance.get(InstanceModel.NAME));

        boolean releaseManaged = InstanceKinds.isReleaseManaged(instance.get(InstanceModel.KIND));
        // The trigger column is the release lane's own vocabulary (deploy/webhook/rollback);
        // a workspace build operation records no such word, and a column of blanks reads as
        // missing data rather than as "not applicable".
        vars.put("showTrigger", releaseManaged);
        // The live band leads both lanes; only a release lane keeps a release to go back to.
        vars.put("keepsReleases", releaseManaged);
        vars.put("columnCount", releaseManaged ? 5 : 4);

        WithheldFailure failures = WithheldFailure.of(conduit);
        if (releaseManaged) {
            putReleaseVars(vars, instance, failures);
            putPreviewVars(vars, request, instance);
        } else {
            putWorkspaceVars(vars, instanceId, failures);
        }
        putSourceVars(vars, instance);

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
        vars.put("head", recordHead(conduit));
        vars.put("timeWording", CmsSupport.timeWording(conduit));

        return new RenderTemplateResult(HohenheimTemplateIds.INSTANCE_DEPLOYMENTS, vars);
    }

    /**
     * The application lane: release operations, the live release and the one release kept for rollback.
     *
     * AIDEV-NOTE: a release operation records no commit of its own; the commit lives in the stored settings of the
     * release instance it created ({@code candidate_instance_id}), read here in one batch. A release whose instance
     * was removed keeps its row but shows no commit. Which history row is "live" or "kept" is read from the
     * instances' roles (serving, retired), never from the operation's status.
     */
    private static void putReleaseVars(Map<String, Object> vars, Row application, WithheldFailure failures) {
        int instanceId = application.get(InstanceModel.ID);
        ReleaseOperationModel model = Models.get(ReleaseOperationModel.class);
        List<Row> operations = model.findForOwner(InstanceModel.MODEL_ID.toString(), instanceId, 50);

        // AIDEV-NOTE: "in flight" is the model's own answer (findInFlight), never a status
        // list spelled here: this page used to list pending/deploying/probing and missed
        // switching and draining, so Deploy and Rollback were offered mid-switch.
        boolean inFlight = !model.findInFlight(InstanceModel.MODEL_ID.toString(), instanceId).isEmpty();
        Row serving = ApplicationReleases.ownedServing(instanceId);
        Row kept = ReleaseEngine.newestRetired(instanceId);
        vars.put("isDeploying", inFlight);
        vars.put("canRollback", !inFlight && kept != null && serving != null);
        vars.put("currentCommit", serving == null ? "" : commitOf(serving));
        vars.put("keptCommit", kept == null ? "" : commitOf(kept));
        Instant liveSince = serving == null ? null : serving.get(InstanceModel.CREATED_AT);
        vars.put("liveSinceIso", liveSince == null ? "" : liveSince.toString());
        Integer servingId = serving == null ? null : serving.get(InstanceModel.ID);
        Integer keptId = kept == null ? null : kept.get(InstanceModel.ID);

        Map<Integer, String> commits = releaseCommits(operations);
        List<Map<String, Object>> deployments = new ArrayList<>();
        for (Row row : operations) {
            Integer candidate = row.get(ReleaseOperationModel.CANDIDATE_INSTANCE_ID);
            Map<String, Object> entry = entry(row.get(ReleaseOperationModel.ID),
                ReleaseOperationModel.STATUS, row.get(ReleaseOperationModel.STATUS),
                Objects.toString(row.get(ReleaseOperationModel.KIND), ""),
                candidate == null ? null : commits.get(candidate),
                row.get(ReleaseOperationModel.DURATION_MS),
                failures.shown(row.get(ReleaseOperationModel.FAILURE_REASON)),
                row.get(ReleaseOperationModel.STARTED_AT),
                // The engine's step log carries daemon text (ReleaseEngine.reasonOf): operators
                // only. A workspace BUILD log below stays visible -- it is the output of the
                // tenant's own checkout and build, and without it they cannot fix a build.
                failures.operatorOnly(row.get(ReleaseOperationModel.STEP_LOG)));
            entry.put("kindLabel",
                CmsSupport.enumLabel(ReleaseOperationModel.KIND, row.get(ReleaseOperationModel.KIND)));
            boolean succeeded = ReleaseOperationModel.LIFECYCLE.is(
                    row.get(ReleaseOperationModel.STATUS), OperationStatus.SUCCEEDED);
            ReleaseMark mark = !succeeded || candidate == null ? null
                : candidate.equals(servingId) ? ReleaseMark.LIVE
                : candidate.equals(keptId) ? ReleaseMark.KEPT
                : ReleaseMark.REPLACED;
            entry.put("mark", mark == null ? "" : mark.token);
            if (mark != null) {
                entry.put("statusLabel", CmsSupport.resolvedTextOrDefault(mark.label));
            }
            deployments.add(entry);
        }
        vars.put("deployments", deployments);
    }

    /** The short commit each release instance named, keyed by instance id, trashed instances included. */
    private static @NonNull Map<Integer, String> releaseCommits(@NonNull List<Row> operations) {
        Set<Integer> ids = new java.util.HashSet<>();
        for (Row row : operations) {
            Integer candidate = row.get(ReleaseOperationModel.CANDIDATE_INSTANCE_ID);
            if (candidate != null) {
                ids.add(candidate);
            }
        }
        Map<Integer, String> commits = new HashMap<>();
        if (ids.isEmpty()) {
            return commits;
        }
        for (Row release : Models.get(InstanceModel.class).find().withTrashed()
                .where(InstanceModel.ID.in(ids)).all()) {
            commits.put(release.get(InstanceModel.ID), commitOf(release));
        }
        return commits;
    }

    private static @NonNull String commitOf(@NonNull Row release) {
        return shortSha(ApplicationReleases.storedSettings(release).get("commit_sha"));
    }

    /**
     * The previews of this application, each with its own row actions (the preview entry's placed operations, offered
     * as its list offers them), and the link that starts a new one.
     */
    private static void putPreviewVars(Map<String, Object> vars, PanelRequest request, Row application) {
        int instanceId = application.get(InstanceModel.ID);
        Map<String, Object> settings = ApplicationReleases.storedSettings(application);
        boolean enabled = RawValues.isOn(settings, GitSourceSchema.PREVIEWS_ENABLED, false);
        List<Row> previews = new ArrayList<>();
        for (Row preview : Models.get(PreviewDeploymentModel.class).findLiveByApplicationId(instanceId)) {
            Object status = preview.get(PreviewDeploymentModel.STATUS);
            if (!PreviewDeploymentModel.STATUS_DESTROYED.equals(status)
                    && !PreviewDeploymentModel.STATUS_EXPIRED.equals(status)) {
                previews.add(preview);
            }
        }
        vars.put("previewsShown", enabled || !previews.isEmpty());
        PanelEntry entry = request.panel().entryBySlug(HohenheimSlugs.PREVIEWS);
        if (!(entry instanceof PanelResource<?>) || (!enabled && previews.isEmpty())) {
            vars.put("previews", List.of());
            vars.put("previewCreateUrl", "");
            return;
        }
        @SuppressWarnings("unchecked")
        PanelResource<Row> previewEntry = (PanelResource<Row>) entry;
        String pageUrl = CmsRoutes.subpage(request.panelSlug(), HohenheimSlugs.INSTANCES, instanceId,
            HohenheimSlugs.Tab.DEPLOYMENTS).toUrl();
        Function<Row, List<RowOffer>> offers = PanelActionOffers.rowsForRender(request, previewEntry, null, previews,
            request.access(), ReturnPath.of(pageUrl));
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Row preview : previews) {
            Map<String, Object> row = new HashMap<>();
            Object status = preview.get(PreviewDeploymentModel.STATUS);
            WidgetBadge.Colors colors = WidgetBadge.colorsOf(PreviewDeploymentModel.STATUS, status);
            row.put("ref", Objects.toString(preview.get(PreviewDeploymentModel.REF), ""));
            Integer pr = preview.get(PreviewDeploymentModel.PR_NUMBER);
            row.put("pullRequest", pr == null ? "" : String.valueOf(pr));
            String hostname = Objects.toString(preview.get(PreviewDeploymentModel.HOSTNAME), "");
            row.put("hostname", hostname);
            // A preview answers on its own generated name over plain HTTP until a certificate covers it.
            row.put("liveUrl", hostname.isEmpty() ? "" : "http://" + hostname);
            row.put("statusLabel", CmsSupport.enumLabel(PreviewDeploymentModel.STATUS, status));
            row.put("statusVariant", colors.variant());
            row.put("statusColorSet", colors.colorSet());
            row.put("running", PreviewDeploymentModel.STATUS_RUNNING.equals(status));
            Instant expires = preview.get(PreviewDeploymentModel.EXPIRES_AT);
            row.put("expiresIso", expires == null ? "" : expires.toString());
            row.put("openUrl", CmsRoutes.open(request.panelSlug(), HohenheimSlugs.PREVIEWS,
                preview.get(PreviewDeploymentModel.ID)).toUrl());
            row.put("invokes", ActionStateTranslator.bandRowOffers(offers.apply(preview), 0).allInvokes());
            rows.add(row);
        }
        vars.put("previews", rows);
        vars.put("previewCreateUrl", enabled
            ? CmsRoutes.create(request.panelSlug(), HohenheimSlugs.PREVIEWS).toUrl()
                + "?" + HohenheimParams.PREVIEW_APPLICATION.getName() + "=" + instanceId
            : "");
    }

    /**
     * Where the deploys come from and what the last pushes did: the source line every reader may see, and each recent
     * delivery's outcome in words. The push address and secret stay admin-only (putAdminOnlyVars).
     */
    private static void putSourceVars(Map<String, Object> vars, Row application) {
        Map<String, Object> settings = ApplicationReleases.storedSettings(application);
        vars.put("sourceRepository", Objects.toString(settings.get(GitSourceSchema.REPOSITORY_URL), "").isEmpty()
            ? Objects.toString(settings.get(GitSourceSchema.REPOSITORY), "")
            : Objects.toString(settings.get(GitSourceSchema.REPOSITORY_URL), ""));
        vars.put("sourceBranch", Objects.toString(settings.get(GitSourceSchema.BRANCH), ""));
        vars.put("autoDeploy", GitSourceSchema.autoDeploys(settings));
        List<Map<String, Object>> pushes = new ArrayList<>();
        for (Row delivery : Models.get(WebhookDeliveryModel.class).findRecent(application.get(InstanceModel.ID), 10)) {
            Map<String, Object> push = new HashMap<>();
            WebhookOutcome outcome = WebhookOutcome.ofToken(delivery.get(WebhookDeliveryModel.ACTION));
            push.put("event", Objects.toString(delivery.get(WebhookDeliveryModel.EVENT), ""));
            push.put("outcome", outcome == null
                ? CmsSupport.resolvedTextOrDefault(HohenheimMicrocopy.WEBHOOK_OUTCOME.of("received"))
                : CmsSupport.resolvedTextOrDefault(outcome.label()));
            push.put("acted", outcome != null && outcome.acted());
            Instant received = delivery.get(WebhookDeliveryModel.RECEIVED_AT);
            push.put("receivedIso", received == null ? "" : received.toString());
            pushes.add(push);
        }
        vars.put("pushes", pushes);
    }

    /** Where a succeeded release stands now: serving, kept for one-step rollback, or replaced and removed. */
    private enum ReleaseMark {
        LIVE("live", HohenheimMicrocopy.DEPLOYMENTS.of("mark_live")),
        KEPT("kept", HohenheimMicrocopy.DEPLOYMENTS.of("mark_kept")),
        REPLACED("replaced", HohenheimMicrocopy.DEPLOYMENTS.of("mark_replaced"));

        private final String token;
        private final Microcopy label;

        ReleaseMark(String token, Microcopy label) {
            this.token = token;
            this.label = label;
        }
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
            BuildOperationModel.LIFECYCLE.is(row.get(BuildOperationModel.STATUS), OperationStatus.RUNNING)));
        vars.put("canRollback", false);

        Row latest = model.latestSuccess(InstanceModel.MODEL_ID.toString(), instanceId);
        vars.put("currentCommit", latest == null ? ""
            : shortSha(latest.get(BuildOperationModel.SOURCE_REF)));
        Instant liveSince = latest == null ? null : latest.get(BuildOperationModel.FINISHED_AT);
        vars.put("liveSinceIso", liveSince == null ? "" : liveSince.toString());

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
    static @NonNull Map<String, Object> entry(@Nullable Object id, @NonNull EnumField statusField,
                                                     @Nullable Object status, @NonNull String reason,
                                                     @Nullable Object commit, @Nullable Object durationMs,
                                                     @NonNull String failure, @Nullable Instant startedAt,
                                                     @Nullable String log) {
        Map<String, Object> entry = new HashMap<>();
        entry.put("id", id);
        entry.put("status", Objects.toString(status, ""));
        // AIDEV-NOTE: forward the shared enum facets so semantic roles and categorical hues survive both lanes.
        WidgetBadge.Colors colors = WidgetBadge.colorsOf(statusField, status);
        entry.put("statusVariant", colors.variant());
        entry.put("statusColorSet", colors.colorSet());
        entry.put("statusKnown", colors.known());
        entry.put("statusLabel", CmsSupport.enumLabel(statusField, status));
        entry.put("reason", reason);
        // A row that never reached a commit (a refused deploy, a failed checkout) stores the
        // branch it was asked for: it reads as that branch, never as its first eight letters.
        String source = Objects.toString(commit, "");
        boolean isCommit = COMMIT_SHA.matcher(source).matches();
        entry.put("commit", isCommit ? shortSha(source) : "");
        entry.put("ref", isCommit ? "" : source);
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
        vars.put("webhookSecret", Objects.toString(settings.get(GitSourceSchema.WEBHOOK_SECRET), ""));
        vars.put("webhookAutoDeploy",
            GitSourceSchema.autoDeploys(settings));

        // AIDEV-NOTE: the git webhook is intercepted by SiteDispatcher BEFORE the zenit
        // conduit chain, so it is deliberately outside the Endpoint framework and has no
        // RouteTarget. Referencing the handler's own PREFIX constant is what keeps this
        // display URL from drifting away from the route that actually answers.
        String path = GitWebhookHandler.PREFIX + instanceId;
        String url = path;
        Row site = Models.get(SiteModel.class).find()
            .where(SiteModel.INSTANCE_ID.eq(instanceId))
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

    /** A git object name, full or abbreviated; anything else in a commit slot is a ref. */
    private static final Pattern COMMIT_SHA = Pattern.compile("[0-9a-fA-F]{7,64}");

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
        long seconds = Math.round(ms / 1000.0);
        return seconds < 60 ? seconds + " s" : (seconds / 60) + " min " + (seconds % 60) + " s";
    }

}
