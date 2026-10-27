package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.RawValues;
import be.elevenways.hohenheim.model.OperationStatus;
import be.elevenways.hohenheim.HohenheimIds;
import be.elevenways.hohenheim.HohenheimMicrocopy;
import be.elevenways.hohenheim.HohenheimTemplateIds;
import be.elevenways.hohenheim.HohenheimParams;
import be.elevenways.hohenheim.HohenheimSlugs;
import be.elevenways.hohenheim.model.BuildOperationModel;
import be.elevenways.hohenheim.model.DeployTrigger;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.PreviewDeploymentModel;
import be.elevenways.hohenheim.model.ReleaseOperationModel;
import be.elevenways.hohenheim.model.WebhookDeliveryModel;
import be.elevenways.hohenheim.instance.InstanceOperations;
import be.elevenways.hohenheim.server.application.ApplicationReleases;
import be.elevenways.hohenheim.server.application.ReleaseEngine;
import be.elevenways.hohenheim.server.application.SpecDiff;
import be.elevenways.hohenheim.server.application.SpecSnapshots;
import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.hohenheim.server.build.BuildLog;
import be.elevenways.hohenheim.server.build.LiveBuildLogs;
import be.elevenways.hohenheim.server.docker.ReleaseKind;
import be.elevenways.hohenheim.server.instance.InstanceKinds;
import be.elevenways.hohenheim.server.instance.InstanceOperationHandlers;
import be.elevenways.hohenheim.server.source.GitSourcePolls;
import be.elevenways.hohenheim.server.source.GitWebhookHandler;
import be.elevenways.hohenheim.server.source.HookUrls;
import be.elevenways.hohenheim.server.source.WebhookOutcome;
import be.elevenways.hohenheim.source.GitSourceSchema;
import be.elevenways.plumage.component.Pager;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.i18n.wording.Wording;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.common.live.LiveFeed;
import be.elevenways.zenit.cms.common.action.ActionPlacement;
import be.elevenways.zenit.cms.common.action.ActionStyle;
import be.elevenways.protoblast.common.key.IdentifierKey;
import be.elevenways.zenit.cms.common.action.ConfirmationSpec;
import be.elevenways.zenit.cms.common.action.PanelAction;
import be.elevenways.zenit.cms.common.page.CmsEndpoints;
import be.elevenways.zenit.cms.common.page.CmsRoutes;
import be.elevenways.zenit.cms.common.panel.PanelEntry;
import be.elevenways.zenit.cms.common.panel.PanelRequest;
import be.elevenways.zenit.cms.common.resource.PanelResource;
import be.elevenways.zenit.cms.common.resource.RecordTab;
import be.elevenways.zenit.cms.server.page.PageActions;
import be.elevenways.zenit.cms.server.panel.PanelActionOffers;
import be.elevenways.zenit.cms.server.render.action.ActionStateTranslator;
import be.elevenways.zenit.cms.server.render.action.RowOffer;
import be.elevenways.zenit.common.data.FacetUrlState;
import be.elevenways.zenit.common.data.ListState;
import be.elevenways.zenit.common.data.PageWindow;
import be.elevenways.zenit.common.edit.FieldLabels;
import be.elevenways.zenit.common.orm.field.Field;
import be.elevenways.zenit.common.orm.query.QueryBuilder;
import be.elevenways.zenit.common.orm.query.SortOrder;
import be.elevenways.zenit.common.routing.ReturnPath;
import be.elevenways.zenit.common.routing.RouteScope;
import be.elevenways.zenit.common.conduit.Conduit;
import be.elevenways.zenit.common.Zenit;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.field.EnumField;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.result.ActionResult;
import be.elevenways.zenit.common.result.RenderTemplateResult;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.common.security.PrincipalRef;
import be.elevenways.zenit.common.security.StoredRecordPrincipal;
import be.elevenways.zenit.common.orm.history.HistoryText;
import be.elevenways.zenit.common.orm.narration.FieldValueText;
import be.elevenways.zenit.widget.common.data.WidgetBadge;
import be.elevenways.zenit.common.ui.Icon;
import be.elevenways.hohenheim.instance.DeployDetail;
import be.elevenways.hohenheim.instance.InstanceDeploys;
import be.elevenways.zenit.cms.common.panel.PageAddress;
import be.elevenways.zenit.cms.common.panel.Panel;
import be.elevenways.zenit.cms.common.panel.PanelRegistry;
import be.elevenways.zenit.cms.common.render.CmsShellFunctions;
import be.elevenways.zenit.cms.common.render.action.PageFormState;
import be.elevenways.zenit.cms.server.panel.PanelGate;
import be.elevenways.zenit.cms.server.panel.RecordTabGate;
import be.elevenways.zenit.common.live.LiveRead;
import be.elevenways.zenit.common.refusal.DomainRefusal;
import be.elevenways.zenit.common.refusal.ZenitRefusalReason;
import be.elevenways.zenit.common.routing.BoundEndpoint;
import be.elevenways.zenit.server.live.LiveReads;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
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
 * re-keyed to the application: the deploy history belongs to the
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

    /**
     * The tab's content as one live part, its parameter {@code panel,instanceId,page}: heard on every build, release,
     * preview, push and record write, a piece of the record's Deploys subpage (a re-read passes that route's gates),
     * read again through the panel's gate and this tab's own for the reader, so a deploy's progress, its end and a
     * push redraw the part alone.
     */
    static final LiveRead<String, InstanceDeploys> PART =
        LiveRead.of(HohenheimIds.id("instance_deploys"), String.class, InstanceDeploys.class)
            .heard(part -> partAt(part) == null ? List.of() : List.of(LiveFeed.of(AccessContext::isAccount,
                BuildOperationModel.MODEL_ID, ReleaseOperationModel.MODEL_ID, InstanceModel.MODEL_ID,
                PreviewDeploymentModel.MODEL_ID, WebhookDeliveryModel.MODEL_ID)));

    /** History rows per page: a release row carries its logs, so one page stays a screen or two. */
    static final int PAGE_SIZE = 20;

    /**
     * Deploy now: the placed deploy operation over this tab's own record, the same operation, gate, handler and
     * background lane the record's Deploy row action runs. It lands back on this tab at once, the run named in the
     * heading, and refuses before any run while a deploy runs or a database is not ready (dead, with why); the release
     * or build the run starts shows here with its live log.
     */
    private static final PanelAction<Row> DEPLOY = PanelAction.<Row, Void>placesRun(InstanceOperations.DEPLOY,
            ActionPlacement.PAGE)
        .finishedRunOpens(Row.class, InstanceDeploymentsPage::tabOf)
        .startedRunOpens(Row.class, InstanceDeploymentsPage::tabOf)
        .label(HohenheimMicrocopy.DEPLOYMENTS.of("deploy_now"))
        .confirmation(ConfirmationSpec.verb(HohenheimMicrocopy.DEPLOYMENTS.of("deploy_now"),
            HohenheimMicrocopy.DEPLOYMENTS.of("deploy_confirm"), ActionStyle.DEFAULT))
        .selectedByRoute(instance -> String.valueOf((Object) instance.get(InstanceModel.ID)))
        .build();

    /**
     * The one step to the kept release, back or forward: the placed rollback operation over this tab's own record,
     * the same operation, gate, handler and background lane the instance's action band runs. Its words name both
     * releases, and its confirmation names what the switch changes ({@link SpecSnapshots#diff}), a secret setting
     * only as changed. It lands back on this tab at once; the release the run starts shows here with its live log.
     */
    private static final PanelAction<Row> ROLLBACK = PanelAction.<Row, Void>placesRun(InstanceOperations.ROLLBACK,
            ActionPlacement.PAGE)
        .finishedRunOpens(Row.class, InstanceDeploymentsPage::tabOf)
        .startedRunOpens(Row.class, InstanceDeploymentsPage::tabOf)
        .dynamicLabel(application -> Releases.of(application).stepLabel())
        .confirmation(ConfirmationSpec.verb(HohenheimMicrocopy.INSTANCE.of("rollback"),
            HohenheimMicrocopy.INSTANCE.of("rollback_confirm"), ActionStyle.DESTRUCTIVE))
        .dynamicConfirmation(application -> ConfirmationSpec.verb(Releases.of(application).stepLabel(),
            HohenheimMicrocopy.INSTANCE.of("rollback_confirm"), ActionStyle.DESTRUCTIVE))
        .confirmationBody((application, input) -> Releases.of(application).confirmation())
        .selectedByRoute(application -> String.valueOf((Object) application.get(InstanceModel.ID)))
        .build();

    /** The request's live and kept releases per application, read once however many parts of a render ask. */
    private static final IdentifierKey<Map<Integer, Releases>> RELEASES =
        IdentifierKey.of("hohenheim", "instance_deployments_releases");

    /** Serve {@link #PART}; called once by HohenheimHandlers. */
    public static void registerPart() {
        InstanceDeploymentsPage tab = new InstanceDeploymentsPage();
        LiveReads.serve(PART, InstanceDeploymentsPage::page, tab::partRead);
    }

    /** The decoded part parameter: the panel, the record and the history page it names. */
    private record PartAt(@NonNull Panel panel, int instanceId, int page) {

        /** @return the tab's route on this panel, the first history page */
        @NonNull BoundEndpoint<?> route() {
            return CmsRoutes.subpage(this.panel.slug(), HohenheimSlugs.INSTANCES, this.instanceId,
                HohenheimSlugs.Tab.DEPLOYMENTS);
        }

        /** @return the tab's address on its history page */
        @NonNull String target() {
            return this.page > 1 ? this.route().with(CmsEndpoints.LIST_PAGE_PARAM, this.page).toUrl()
                : this.route().toUrl();
        }
    }

    static @NonNull String partOf(@NonNull String panelSlug, int instanceId, int page) {
        return panelSlug + "," + instanceId + "," + page;
    }

    /** @return the tab the parameter names, null when it is not one panel slug, record id and page in canonical form */
    private static @Nullable PartAt partAt(@NonNull String part) {
        String[] at = part.split(",", -1);
        Panel panel = at.length == 3 ? PanelRegistry.getBySlug(at[0]) : null;
        if (panel == null) return null;
        try {
            int instanceId = Integer.parseInt(at[1]);
            int page = Integer.parseInt(at[2]);
            return page < 1 || !partOf(at[0], instanceId, page).equals(part) ? null
                : new PartAt(panel, instanceId, page);
        } catch (NumberFormatException notNumbers) {
            return null;
        }
    }

    /** @return the record subpage the part is a piece of, null for a parameter that names none */
    private static LiveReads.@Nullable Page page(@NonNull String part) {
        PartAt at = partAt(part);
        // The canonical path, no locale prefix: what a route gate sees of the page's own request.
        return at == null ? null : new LiveReads.Page(CmsEndpoints.RECORD_SUBPAGE,
            CmsEndpoints.RECORD_SUBPAGE.toUrl(null, at.route().getParameters()));
    }

    /** The part's read: the panel's gate admits the reader to the instances, then this tab's gate to the record. */
    private @NonNull InstanceDeploys partRead(@NonNull AccessContext access, @NonNull String part) {
        PartAt at = partAt(part);
        Conduit conduit = access.conduit();
        PanelEntry entry = at == null ? null : at.panel().entryBySlug(HohenheimSlugs.INSTANCES);
        if (at == null || entry == null || conduit == null) {
            throw new DomainRefusal(ZenitRefusalReason.NOT_FOUND, "No such deploys tab");
        }
        PanelGate.Outcome.Admitted admitted = PanelGate.admitPart(access, at.panel(), entry);
        CmsShellFunctions.pinPanel(conduit, at.panel());
        String key = String.valueOf(at.instanceId());
        PanelRequest request = new PanelRequest(at.panel(), conduit, admitted.request().access(), null,
            PageAddress.at(at.target(), HohenheimSlugs.INSTANCES, key, HohenheimSlugs.Tab.DEPLOYMENTS));
        RecordTabGate.OpenTab opened = RecordTabGate.admit(request, admitted.entry(), key,
            HohenheimSlugs.Tab.DEPLOYMENTS);
        if (opened == null || !(opened.offered().record() instanceof Row instance)) {
            throw new DomainRefusal(ZenitRefusalReason.NOT_FOUND, "No such deploys tab");
        }
        return this.read(request, instance, at.page());
    }

    @Override public @NonNull Microcopy label() { return HohenheimMicrocopy.DEPLOYMENTS.of("title"); }
    @Override public @NonNull String slug() { return HohenheimSlugs.Tab.DEPLOYMENTS; }
    @Override public @NonNull Icon icon() { return Icon.of("rocket"); }

    @Override
    public @NonNull List<PanelAction<Row>> actions() {
        return List.of(DEPLOY, ROLLBACK);
    }

    /** @return the slug of this tab, which a started deploy or rollback lands on and its finished run opens */
    static @NonNull String tabOf(@NonNull Row application) {
        return HohenheimSlugs.Tab.DEPLOYMENTS;
    }

    /**
     * A record has this tab when deploying it means deploying code ({@code InstanceKinds.deploysCode}): a
     * release-managed kind always does, a workspace once it names a repository. A workspace with no repository
     * deploys a bare container and has no history to show.
     */
    @Override
    public boolean visibleFor(@NonNull Row record, @NonNull AccessContext access) {
        return InstanceKinds.deploysCode(record);
    }

    @Override
    public @NonNull ActionResult<?> render(@NonNull PanelRequest request, @NonNull Row instance) {
        Conduit conduit = request.conduit();
        int instanceId = instance.get(InstanceModel.ID);
        int page = FacetUrlState.readState(ListState.Shape.PLAIN, conduit::getQueryParams).page();
        Map<String, Object> vars = new HashMap<>();
        vars.put("title", CmsSupport.pageTitle(conduit, HohenheimMicrocopy.INSTANCE_DEPLOYMENTS,
            instance.get(InstanceModel.NAME)));
        vars.put("head", recordHead(conduit));
        vars.put("timeWording", CmsSupport.timeWording(conduit));
        vars.put("part", LiveReads.at(PART, partOf(request.panelSlug(), instanceId, page),
            () -> this.read(request, instance, page)));
        return new RenderTemplateResult(HohenheimTemplateIds.INSTANCE_DEPLOYMENTS, vars);
    }

    /** The tab's content for the request's reader, on the requested history page (clamped to the history). */
    private @NonNull InstanceDeploys read(@NonNull PanelRequest request, @NonNull Row instance, int requestedPage) {
        AccessContext reader = request.access();
        int instanceId = instance.get(InstanceModel.ID);
        boolean releaseManaged = InstanceKinds.isReleaseManaged(instance.get(InstanceModel.KIND));
        WithheldFailure failures = WithheldFailure.of(request);
        Lane lane = releaseManaged ? releaseLane(instance, failures, reader, requestedPage)
            : workspaceLane(instanceId, failures, requestedPage);
        // One page of history needs no pager, and an undrawn one must not ride the hydration payload either.
        BoundEndpoint<?> tab = CmsRoutes.subpage(request.panelSlug(), HohenheimSlugs.INSTANCES, instanceId,
            HohenheimSlugs.Tab.DEPLOYMENTS);
        Pager pager = lane.window().pageCount() <= 1 ? null : Pager.of(lane.window(), lane.total(),
            number -> number > 1 ? tab.with(CmsEndpoints.LIST_PAGE_PARAM, number).toUrl() : tab.toUrl());
        List<InstanceDeploys.Deploy> deployments = lane.deploying()
            ? withLiveLog(lane.deployments(), instanceId, releaseManaged && !failures.delegated())
            : lane.deployments();
        // Deploy now is the placed deploy operation's own form, drawn only where its offer stands for this reader.
        PageFormState deployForm = InstanceOperationHandlers.offered(InstanceOperations.DEPLOY, reader, instance)
            && PageActions.open(request, this, instance, DEPLOY.id()) instanceof PageActions.Form form
            ? form.state() : null;
        // The way back is the placed rollback operation's own form, drawn only where its offer stands for this
        // reader (a stranger, a generated row or a workspace never gets one).
        PageFormState rollbackForm = lane.canRollback()
            && InstanceOperationHandlers.offered(InstanceOperations.ROLLBACK, reader, instance)
            && PageActions.open(request, this, instance, ROLLBACK.id()) instanceof PageActions.Form form
            ? form.state() : null;
        Map<String, Object> settings = ApplicationReleases.storedSettings(instance);
        InstanceDeploys.Band band = lane.currentCommit().isEmpty() ? null : new InstanceDeploys.Band(
            lane.currentCommit(), Objects.toString(settings.get(GitSourceSchema.BRANCH), ""), lane.keptCommit(),
            lane.liveSinceIso(), releaseManaged, lane.keptSameCommit(), lane.keptNewer(), rollbackForm);
        return new InstanceDeploys(instanceId, band,
            new InstanceDeploys.History(releaseManaged, deployments, deployForm, pager),
            releaseManaged ? previewsOf(request, instance) : null,
            // The trigger vocabulary and the push settings are the release lane's; a workspace build records none.
            releaseManaged ? sourceOf(instance, settings, HohenheimAccess.isAdmin(reader) ? webhookOf(instance) : null)
                : null);
    }

    /**
     * One lane's page of history and what serves now: the page window, how many rows the whole history holds, whether
     * a deploy runs now, and the band's facts (empty commits while nothing serves).
     */
    private record Lane(@NonNull List<InstanceDeploys.Deploy> deployments, @NonNull PageWindow window, long total,
                        boolean deploying, @NonNull String currentCommit, @NonNull String keptCommit,
                        @NonNull String liveSinceIso, boolean canRollback, boolean keptSameCommit,
                        boolean keptNewer) {
    }

    /**
     * Open the live log on the deploy that runs now: the newest in-flight release (its build runs inside it), else the
     * newest running build, seeded with what the build has written so far (LiveBuildLogs: a running build's log is
     * stored only when it ends) and, where this panel shows it, the release's step log; the hh-deploy-log element
     * follows both over the deploy-log channel from there.
     */
    private static @NonNull List<InstanceDeploys.Deploy> withLiveLog(@NonNull List<InstanceDeploys.Deploy> deployments,
                                                                     int instanceId, boolean showSteps) {
        InstanceDeploys.Deploy live = deployments.stream()
            .filter(InstanceDeploys.Deploy::inFlight)
            .min(Comparator.comparing((InstanceDeploys.Deploy deploy) -> !deploy.release()))
            .orElse(null);
        if (live == null) {
            return deployments;
        }
        BuildLog running = LiveBuildLogs.of(InstanceModel.MODEL_ID, instanceId);
        boolean steps = live.release() && showSteps;
        InstanceDeploys.LiveLog log = new InstanceDeploys.LiveLog(running != null ? running.text()
            : live.release() ? "" : live.detail().log(), steps ? live.detail().log() : "", steps);
        return deployments.stream().map(deploy -> deploy == live ? deploy.withLive(log) : deploy).toList();
    }

    /**
     * The application lane: release operations, the live release and the one release kept for rollback.
     *
     * AIDEV-NOTE: a release operation records its own commit since release attribution (2026-10-23); an older row
     * reads the commit from the stored settings of the release instance it created ({@code candidate_instance_id}),
     * read here in one batch, and shows none once that instance was removed. Who or what started each row names its
     * principal through HistoryText, every name on the page read in one call. Which history row is "live" or "kept"
     * is read from the instances' roles (serving, retired), never from the operation's status.
     *
     * AIDEV-NOTE: the history is the releases and the builds that never became one, newest first, one PageWindow over
     * both: each is counted, each read newest first only as far as the window reaches, and the two merged by start.
     * Everything a row needs beyond its own columns is then read once for the page's rows, so a page costs the same
     * reads at 3 rows as at 30 (ReleasesTabJourneyTest measures it).
     */
    private static @NonNull Lane releaseLane(@NonNull Row application, @NonNull WithheldFailure failures,
                                             @NonNull AccessContext reader, int requestedPage) {
        int instanceId = application.get(InstanceModel.ID);
        long releaseCount = releasesOf(instanceId).count();
        long unreleasedCount = unreleasedOf(instanceId).count();
        PageWindow window = PageWindow.of(requestedPage, releaseCount + unreleasedCount, PAGE_SIZE,
            PageWindow.OutOfRange.CLAMP);
        int reach = window.offset() + window.pageSize();
        List<Row> releasesRead = releaseCount == 0 ? List.of() : releasesOf(instanceId).limit(reach).all();
        // A build that did not succeed never became a release: listed beside them, or a deploy a push or the
        // scheduled check started and the build refused would leave no trace on this page. A build that did
        // succeed shows on the release it made (builtImages).
        List<Row> unreleasedRead = unreleasedCount == 0 ? List.of() : unreleasedOf(instanceId).limit(reach).all();
        List<Row> operations = new ArrayList<>();
        List<Row> unreleased = new ArrayList<>();
        int nextRelease = 0;
        int nextBuild = 0;
        for (int index = 0; index < reach
                && (nextRelease < releasesRead.size() || nextBuild < unreleasedRead.size()); index++) {
            boolean release = nextBuild >= unreleasedRead.size() || (nextRelease < releasesRead.size()
                && !startOf(releasesRead.get(nextRelease).get(ReleaseOperationModel.STARTED_AT))
                    .isBefore(startOf(unreleasedRead.get(nextBuild).get(BuildOperationModel.STARTED_AT))));
            Row row = release ? releasesRead.get(nextRelease++) : unreleasedRead.get(nextBuild++);
            if (index >= window.offset()) {
                (release ? operations : unreleased).add(row);
            }
        }

        // AIDEV-NOTE: "in flight" is the release engine's own answer (a release in flight or a build running), never
        // a status list spelled here: this page used to list pending/deploying/probing and missed switching and
        // draining, so Deploy and Rollback were offered mid-switch.
        boolean inFlight = ApplicationReleases.deploying(instanceId);
        List<Row> builds = buildsAround(instanceId, operations);
        Map<String, Row> builtImages = builtImages(builds);
        Releases releases = Releases.of(application);
        Row serving = releases.serving();
        Row kept = releases.kept();
        // A kept release of the live release's own commit is no way back (a redeploy of one commit leaves one): going
        // "back" would deploy the very commit that serves now, so the band says so instead of offering it.
        boolean keptSameCommit = releases.keptSameCommit();
        Instant liveSince = serving == null ? null : serving.get(InstanceModel.CREATED_AT);
        Integer servingId = serving == null ? null : serving.get(InstanceModel.ID);
        Integer keptId = kept == null ? null : kept.get(InstanceModel.ID);

        Map<Integer, String> commits = legacyCommits(operations);
        Map<PrincipalRef, String> triggerers = triggerers(operations, reader);
        Map<Object, Row> ranBuilds = buildsRun(operations, builds);
        List<InstanceDeploys.Deploy> deployments = new ArrayList<>();
        for (Row row : operations) {
            Integer candidate = row.get(ReleaseOperationModel.CANDIDATE_INSTANCE_ID);
            String ownCommit = row.get(ReleaseOperationModel.COMMIT_SHA);
            boolean succeeded = ReleaseOperationModel.LIFECYCLE.is(
                    row.get(ReleaseOperationModel.STATUS), OperationStatus.SUCCEEDED);
            ReleaseMark mark = !succeeded || candidate == null ? null
                : candidate.equals(servingId) ? ReleaseMark.LIVE
                : candidate.equals(keptId) ? ReleaseMark.KEPT
                : ReleaseMark.REPLACED;
            InstanceDeploys.Status status = statusOf(ReleaseOperationModel.STATUS,
                row.get(ReleaseOperationModel.STATUS));
            // The output of the build that made this release's image is the tenant's own build, visible like the
            // log of a build row: without it a release that runs the wrong thing cannot be traced to its build.
            Row build = builtImages.get(Objects.toString(row.get(ReleaseOperationModel.IMAGE_ID), ""));
            Started started = startedBy(row, triggerers);
            deployments.add(deploy("release-" + row.get(ReleaseOperationModel.ID),
                mark == null ? status : new InstanceDeploys.Status(status.value(),
                    CmsSupport.resolvedTextOrDefault(mark.label), status.variant(), status.colorSet(), status.known()),
                ownCommit != null ? ownCommit : candidate == null ? null : commits.get(candidate),
                releaseDurationMs(row, ranBuilds.get(row.get(ReleaseOperationModel.ID))),
                row.get(ReleaseOperationModel.STARTED_AT),
                Objects.toString(CmsSupport.enumLabel(ReleaseOperationModel.KIND, row.get(ReleaseOperationModel.KIND)),
                    ""),
                true,
                ReleaseOperationModel.IN_FLIGHT_STATUSES
                    .contains(Objects.toString(row.get(ReleaseOperationModel.STATUS), "")),
                started, mark == null ? "" : mark.token,
                // The engine's step log carries daemon text (ReleaseEngine.reasonOf): operators only. A workspace
                // BUILD log stays visible -- it is the output of the tenant's own checkout and build, and without it
                // they cannot fix a build.
                DeployDetail.of(failures.shown(row.get(ReleaseOperationModel.FAILURE_REASON)),
                    failures.operatorOnly(row.get(ReleaseOperationModel.STEP_LOG)),
                    build == null ? null : build.get(BuildOperationModel.LOG))));
        }
        for (Row row : unreleased) {
            deployments.add(buildDeploy(row, failures));
        }
        deployments.sort(Comparator.comparing(InstanceDeploys.Deploy::startedAtIso).reversed());
        // The engine orders releases by their instance id (newestRetired): after a rollback the kept release is the
        // newer one, so the one step goes forward to it, never "back".
        return new Lane(deployments, window, releaseCount + unreleasedCount, inFlight,
            serving == null ? "" : commitOf(serving), kept == null ? "" : commitOf(kept),
            liveSince == null ? "" : liveSince.toString(),
            !inFlight && kept != null && serving != null && !keptSameCommit, keptSameCommit, releases.keptNewer());
    }

    /** One application's release operations, newest start first. */
    private static @NonNull QueryBuilder<Row> releasesOf(int applicationId) {
        return Models.get(ReleaseOperationModel.class).find()
            .where(ReleaseOperationModel.FOR_MODEL.eq(InstanceModel.MODEL_ID.toString()))
            .where(ReleaseOperationModel.FOR_ID.eq(applicationId))
            .orderBy(ReleaseOperationModel.STARTED_AT, SortOrder.DESC)
            .orderBy(ReleaseOperationModel.ID, SortOrder.DESC);
    }

    /** One record's builds that did not succeed (running, failed, refused), newest start first. */
    private static @NonNull QueryBuilder<Row> unreleasedOf(int instanceId) {
        return buildsOf(instanceId)
            .where(BuildOperationModel.STATUS.notIn(
                List.of(BuildOperationModel.LIFECYCLE.stored(OperationStatus.SUCCEEDED))));
    }

    /** One record's builds, newest start first. */
    private static @NonNull QueryBuilder<Row> buildsOf(int instanceId) {
        return Models.get(BuildOperationModel.class).find()
            .where(BuildOperationModel.FOR_MODEL.eq(InstanceModel.MODEL_ID.toString()))
            .where(BuildOperationModel.FOR_ID.eq(instanceId))
            .orderBy(BuildOperationModel.STARTED_AT, SortOrder.DESC)
            .orderBy(BuildOperationModel.ID, SortOrder.DESC);
    }

    /** @return a stored start for ordering; one never stamped sorts after every stamped one, as the query puts it */
    private static @NonNull Instant startOf(@Nullable Instant started) {
        return started == null ? Instant.MIN : started;
    }

    /**
     * The builds a page of releases can have run: every build that started inside the page's span, and every succeeded
     * build of an image one of them deployed; newest first, each once.
     */
    private static @NonNull List<Row> buildsAround(int instanceId, @NonNull List<Row> releases) {
        Instant earliest = null;
        Instant latest = null;
        boolean running = false;
        Set<String> images = new HashSet<>();
        for (Row release : releases) {
            Instant started = release.get(ReleaseOperationModel.STARTED_AT);
            Instant finished = release.get(ReleaseOperationModel.FINISHED_AT);
            if (started != null && (earliest == null || started.isBefore(earliest))) {
                earliest = started;
            }
            if (finished == null) {
                running = true;
            } else if (latest == null || finished.isAfter(latest)) {
                latest = finished;
            }
            String image = Objects.toString(release.get(ReleaseOperationModel.IMAGE_ID), "");
            if (!image.isEmpty()) {
                images.add(image);
            }
        }
        Map<Object, Row> byId = new HashMap<>();
        if (earliest != null) {
            QueryBuilder<Row> spanned = buildsOf(instanceId).where(BuildOperationModel.STARTED_AT.gte(earliest));
            if (!running && latest != null) {
                spanned = spanned.where(BuildOperationModel.STARTED_AT.lte(latest));
            }
            for (Row build : spanned.all()) {
                byId.put(build.get(BuildOperationModel.ID), build);
            }
        }
        if (!images.isEmpty()) {
            for (Row build : buildsOf(instanceId)
                    .where(BuildOperationModel.STATUS.eq(BuildOperationModel.LIFECYCLE.stored(OperationStatus.SUCCEEDED)))
                    .where(BuildOperationModel.IMAGE_ID.in(images)).all()) {
                byId.put(build.get(BuildOperationModel.ID), build);
            }
        }
        List<Row> builds = new ArrayList<>(byId.values());
        builds.sort(Comparator.comparing((Row build) -> ((Number) build.get(BuildOperationModel.ID)).intValue())
            .reversed());
        return builds;
    }

    /**
     * The build each release ran, keyed by the release's id: the build that started inside the release's own span, else
     * the newest succeeded build of its image that started before it, unless another release ran that build.
     *
     * AIDEV-NOTE: a forward release creates its row and THEN resolves its spec by building ({@code ReleaseEngine
     * .release}), so its build starts milliseconds after it and its own time already covers the build; only a first
     * release is handed an image built just before it, its row timing the switch alone (a first static release read
     * "548 ms"). Pairing only builds that started before a release matched every forward release with an older build
     * of the same image: walk-site's live release read "59 min 1 s", from the previous release's build an hour
     * earlier. A build inside any release's span, a failed one's included, is that release's and never another's. A
     * rollback, or a restart of an unchanged image, runs no build: the build of its image belongs to the release that
     * ran it first.
     *
     * @param releases the owner's releases, newest first
     * @param builds   the owner's builds, newest first
     */
    static @NonNull Map<Object, Row> buildsRun(@NonNull List<Row> releases, @NonNull List<Row> builds) {
        Map<Object, Row> ran = new HashMap<>();
        Set<Object> taken = new HashSet<>();
        // 1. The build a release ran inside its own span, whatever became of either.
        for (Row release : releases) {
            Instant started = release.get(ReleaseOperationModel.STARTED_AT);
            if (started == null) {
                continue;
            }
            Instant finished = release.get(ReleaseOperationModel.FINISHED_AT);
            for (Row build : builds) {
                Instant built = build.get(BuildOperationModel.STARTED_AT);
                if (built != null && !built.isBefore(started) && (finished == null || !built.isAfter(finished))
                        && taken.add(build.get(BuildOperationModel.ID))) {
                    ran.put(release.get(ReleaseOperationModel.ID), build);
                    break;
                }
            }
        }
        // 2. A release that built nothing itself was handed its image: the newest build of it, unless a release ran it.
        for (Row release : releases.reversed()) {
            String image = Objects.toString(release.get(ReleaseOperationModel.IMAGE_ID), "");
            Instant started = release.get(ReleaseOperationModel.STARTED_AT);
            if (image.isEmpty() || started == null || ran.containsKey(release.get(ReleaseOperationModel.ID))) {
                continue;
            }
            for (Row build : builds) {
                Instant built = build.get(BuildOperationModel.STARTED_AT);
                if (image.equals(Objects.toString(build.get(BuildOperationModel.IMAGE_ID), ""))
                        && BuildOperationModel.LIFECYCLE.is(build.get(BuildOperationModel.STATUS),
                            OperationStatus.SUCCEEDED)
                        && built != null && built.isBefore(started)) {
                    if (taken.add(build.get(BuildOperationModel.ID))) {
                        ran.put(release.get(ReleaseOperationModel.ID), build);
                    }
                    break;
                }
            }
        }
        return ran;
    }

    /**
     * @return how long the whole release took: from the start of a build it was handed to its own finish, its own
     *         time when it ran its build itself or ran none; null while it runs
     */
    static @Nullable Integer releaseDurationMs(@NonNull Row release, @Nullable Row build) {
        Integer own = release.get(ReleaseOperationModel.DURATION_MS);
        Instant started = release.get(ReleaseOperationModel.STARTED_AT);
        Instant finished = release.get(ReleaseOperationModel.FINISHED_AT);
        Instant built = build == null ? null : build.get(BuildOperationModel.STARTED_AT);
        if (own == null || started == null || finished == null || built == null || !built.isBefore(started)) {
            return own;
        }
        return (int) (finished.toEpochMilli() - built.toEpochMilli());
    }

    /**
     * The succeeded build behind each image id, the newest when one image was built twice: a release names the image
     * it deployed and the build that loaded that image stored the same id.
     *
     * @param builds the owner's builds, newest first
     */
    private static @NonNull Map<String, Row> builtImages(@NonNull List<Row> builds) {
        Map<String, Row> byImage = new HashMap<>();
        for (Row build : builds) {
            String image = Objects.toString(build.get(BuildOperationModel.IMAGE_ID), "");
            if (!image.isEmpty() && BuildOperationModel.LIFECYCLE.is(build.get(BuildOperationModel.STATUS),
                    OperationStatus.SUCCEEDED)) {
                byImage.putIfAbsent(image, build);
            }
        }
        return byImage;
    }

    /**
     * The short commit each legacy row's release instance names, keyed by instance id, trashed instances included: one
     * read for the whole page, and none once every row records its own commit.
     */
    private static @NonNull Map<Integer, String> legacyCommits(@NonNull List<Row> operations) {
        Set<Integer> ids = new HashSet<>();
        for (Row row : operations) {
            Integer candidate = row.get(ReleaseOperationModel.CANDIDATE_INSTANCE_ID);
            if (candidate != null && row.get(ReleaseOperationModel.COMMIT_SHA) == null) {
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
        return shortSha(ApplicationReleases.storedCommit(release));
    }

    /**
     * The name of every principal that started one of these operations, for the reader, read in one call.
     *
     * AIDEV-NOTE: a principal whose kind names a record (a build hook) is named by that record's title, core's
     * StoredRecordPrincipal reading; HistoryText names any non-account kind by its kind label alone, which would read
     * "Build hook Build hook". One read per credential model, so the page stays flat.
     */
    private static @NonNull Map<PrincipalRef, String> triggerers(@NonNull List<Row> operations,
                                                                 @NonNull AccessContext reader) {
        Set<PrincipalRef> principals = new HashSet<>();
        for (Row row : operations) {
            PrincipalRef principal = ReleaseOperationModel.TRIGGERER.read(row);
            if (principal != null) {
                principals.add(principal);
            }
        }
        if (principals.isEmpty()) {
            return Map.of();
        }
        Map<PrincipalRef, String> named = new HashMap<>(HistoryText.principals(principals, reader));
        StoredRecordPrincipal.resolveAll(principals).forEach((reference, record) ->
            named.put(reference, record.displayName()));
        return named;
    }

    /** What started a row, its trigger token and in words; a row that recorded none says nothing. */
    private record Started(@NonNull String trigger, @NonNull String text) {
        static final Started NONE = new Started("", "");
    }

    /**
     * What started a release row, in words: "Manual by Jelle" when the trigger was pressed by its principal, "Build
     * hook Nightly" when the principal is the credential that asked, "System, requested by Jelle" when the principal
     * only caused it, the bare trigger without a principal; a legacy row that recorded no trigger says nothing.
     */
    private static @NonNull Started startedBy(@NonNull Row operation, @NonNull Map<PrincipalRef, String> triggerers) {
        String trigger = operation.get(ReleaseOperationModel.TRIGGER);
        if (trigger == null) {
            return Started.NONE;
        }
        String label = Objects.toString(CmsSupport.enumLabel(ReleaseOperationModel.TRIGGER, trigger), "");
        PrincipalRef principal = ReleaseOperationModel.TRIGGERER.read(operation);
        String by = principal == null ? null : triggerers.get(principal);
        if (by == null) {
            return new Started(trigger, label);
        }
        DeployTrigger started = DeployTrigger.of(trigger);
        Microcopy words = started.principalIsTrigger() ? HohenheimMicrocopy.DEPLOYMENTS.of("trigger_named")
            : started.pressedByPerson() ? HohenheimMicrocopy.DEPLOYMENTS.of("triggered_by")
            : HohenheimMicrocopy.DEPLOYMENTS.of("trigger_requested_by");
        return new Started(trigger, CmsSupport.resolvedTextOrDefault(
            words.withArg("trigger", label).withArg("actor", by)));
    }

    /** @return whether two releases name one and the same commit; a release naming none matches nothing */
    private static boolean sameCommit(@NonNull Row left, @NonNull Row right) {
        String commit = ApplicationReleases.storedCommit(left);
        return !commit.isEmpty() && commit.equals(ApplicationReleases.storedCommit(right));
    }

    /**
     * The application's live release and the one kept beside it, as the instances' roles name them: what the band
     * shows and what the rollback action's words and confirmation read.
     */
    private static final class Releases {

        private final @Nullable Row serving;
        private final @Nullable Row kept;
        /** Built on first ask: the diff reads both releases' stored snapshots. */
        private @Nullable Microcopy confirmation;

        private Releases(@Nullable Row serving, @Nullable Row kept) {
            this.serving = serving;
            this.kept = kept;
        }

        /** @return the application's releases, read once per request ({@link RouteScope#memo}) */
        static @NonNull Releases of(@NonNull Row application) {
            int applicationId = application.get(InstanceModel.ID);
            return RouteScope.memo(RELEASES, HashMap::new).computeIfAbsent(applicationId,
                id -> new Releases(ApplicationReleases.ownedServing(id), ReleaseEngine.newestRetired(id)));
        }

        @Nullable Row serving() {
            return this.serving;
        }

        @Nullable Row kept() {
            return this.kept;
        }

        /** @return whether the kept release is newer than the live one (a rollback ran), so the step goes forward */
        boolean keptNewer() {
            return this.kept != null && this.serving != null
                && this.kept.get(InstanceModel.ID).intValue() > this.serving.get(InstanceModel.ID).intValue();
        }

        /** @return whether the kept release runs the live release's own commit, so there is nothing to step to */
        boolean keptSameCommit() {
            return this.kept != null && this.serving != null && sameCommit(this.kept, this.serving);
        }

        /** @return the step's words: back, or forward after a rollback, to the kept release's commit */
        @NonNull Microcopy stepLabel() {
            return HohenheimMicrocopy.DEPLOYMENTS.of(this.keptNewer() ? "forward_to" : "rollback_to")
                .withArg("commit", this.kept == null ? "" : commitOf(this.kept));
        }

        /**
         * The confirmation's sentence: which release stops serving, which one takes over, and what that changes,
         * diffed over the redacted snapshots each release's own operation stored, so a secret is never more than
         * "changed" and the diff names what each release deployed. Built once per request, with the releases.
         */
        @NonNull Microcopy confirmation() {
            if (this.confirmation != null) {
                return this.confirmation;
            }
            SpecDiff diff = this.serving == null || this.kept == null ? new SpecDiff(List.of(), List.of())
                : diffOf(this.serving, this.kept);
            this.confirmation = HohenheimMicrocopy.DEPLOYMENTS.of(this.keptNewer() ? "forward_confirm"
                    : "rollback_confirm")
                .withArg("commit", this.kept == null ? "" : commitOf(this.kept))
                .withArg("current", this.serving == null ? "" : commitOf(this.serving))
                // AIDEV-NOTE: resolved here and handed over as text: passed as a nested Microcopy (itself holding a
                // list argument) it reached the shell's confirm dialog empty, the sentence ending after "stops
                // serving." (observed 2026-10-24). putTrigger hands its nested label over the same way.
                .withArg("changes", CmsSupport.resolvedTextOrDefault(changesOf(diff)));
            return this.confirmation;
        }

        /**
         * What replacing the serving release's spec by the kept one's changes, from the snapshot the newest succeeded
         * operation naming each release stored (one read for both).
         *
         * AIDEV-NOTE: a release made before release attribution (2026-10-23) has no stored snapshot; that side alone
         * falls back to a snapshot of its instance's settings taken now, which is what a rollback would deploy but
         * costs the variable overlay's reads.
         */
        private static @NonNull SpecDiff diffOf(@NonNull Row serving, @NonNull Row kept) {
            Integer servingId = serving.get(InstanceModel.ID);
            Integer keptId = kept.get(InstanceModel.ID);
            Map<Integer, String> stored = new HashMap<>();
            for (Row operation : Models.get(ReleaseOperationModel.class).find()
                    .where(ReleaseOperationModel.CANDIDATE_INSTANCE_ID.in(List.of(servingId, keptId)))
                    .where(ReleaseOperationModel.STATUS.eq(
                        ReleaseOperationModel.LIFECYCLE.stored(OperationStatus.SUCCEEDED)))
                    .orderBy(ReleaseOperationModel.ID, SortOrder.DESC).all()) {
                String snapshot = operation.get(ReleaseOperationModel.SPEC_SNAPSHOT);
                if (snapshot != null && !snapshot.isBlank()) {
                    stored.putIfAbsent(operation.get(ReleaseOperationModel.CANDIDATE_INSTANCE_ID), snapshot);
                }
            }
            return SpecSnapshots.diff(snapshotOf(serving, stored.get(servingId)),
                snapshotOf(kept, stored.get(keptId)));
        }

        private static @NonNull String snapshotOf(@NonNull Row release, @Nullable String stored) {
            return stored != null ? stored : SpecSnapshots.snapshot(ApplicationReleases.storedSettings(release));
        }
    }

    /**
     * What a spec diff changes, in words: each setting by its label (a plain value from and to, worded as the field
     * words it for the reader; a secret only as changed), each map by the names added, removed and changed, never a
     * value; "the same" when nothing differs.
     */
    static @NonNull Microcopy changesOf(@NonNull SpecDiff diff) {
        if (diff.isEmpty()) {
            return HohenheimMicrocopy.DEPLOYMENTS.of("spec_unchanged");
        }
        List<Microcopy> changes = new ArrayList<>();
        for (SpecDiff.Setting setting : diff.settings()) {
            Microcopy label = settingLabel(setting.key());
            changes.add(setting.secret() ? HohenheimMicrocopy.DEPLOYMENTS.of("spec_secret").withArg("setting", label)
                : setting.from() == null ? HohenheimMicrocopy.DEPLOYMENTS.of("spec_set").withArg("setting", label)
                    .withArg("to", valueText(setting.key(), setting.to()))
                : setting.to() == null ? HohenheimMicrocopy.DEPLOYMENTS.of("spec_cleared").withArg("setting", label)
                : HohenheimMicrocopy.DEPLOYMENTS.of("spec_value").withArg("setting", label)
                    .withArg("from", valueText(setting.key(), setting.from()))
                    .withArg("to", valueText(setting.key(), setting.to())));
        }
        for (SpecDiff.Entries entries : diff.maps()) {
            Microcopy label = settingLabel(entries.key());
            addNames(changes, "spec_names_changed", label, entries.changed());
            addNames(changes, "spec_names_added", label, entries.added());
            addNames(changes, "spec_names_removed", label, entries.removed());
        }
        return HohenheimMicrocopy.DEPLOYMENTS.of("spec_changes").withArg("changes", changes);
    }

    private static void addNames(@NonNull List<Microcopy> changes, @NonNull String key, @NonNull Microcopy label,
                                 @NonNull List<String> names) {
        if (names.isEmpty()) {
            return;
        }
        List<Microcopy> words = new ArrayList<>();
        for (String name : names) {
            words.add(Microcopy.literal(name));
        }
        changes.add(HohenheimMicrocopy.DEPLOYMENTS.of(key).withArg("setting", label).withArg("names", words));
    }

    /** @return the release schema field's own label; the diff names only keys the schema declares */
    private static @NonNull Microcopy settingLabel(@NonNull String key) {
        return FieldLabels.labelFor(settingField(key));
    }

    /** @return a stored setting value worded as its field words it for this request's reader ("512 MB") */
    private static @NonNull String valueText(@NonNull String key, @Nullable Object value) {
        return FieldValueText.standard().text(ReleaseKind.SETTINGS_SCHEMA, settingField(key), value,
            CmsSupport.currentReader(), Zenit.getMessageResolver());
    }

    private static @NonNull Field<?, ?> settingField(@NonNull String key) {
        return Objects.requireNonNull(ReleaseKind.SETTINGS_SCHEMA.getField(key),
            () -> "the release schema declares " + key);
    }

    /**
     * The previews of this application, each with its own row actions (the preview entry's placed operations, offered
     * as its list offers them), and the link that starts a new one; null while previews are off and none stands.
     */
    private static InstanceDeploys.@Nullable Previews previewsOf(@NonNull PanelRequest request,
                                                                 @NonNull Row application) {
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
        if (!enabled && previews.isEmpty()) {
            return null;
        }
        PanelEntry entry = request.panel().entryBySlug(HohenheimSlugs.PREVIEWS);
        if (!(entry instanceof PanelResource<?>)) {
            return new InstanceDeploys.Previews(List.of(), "");
        }
        @SuppressWarnings("unchecked")
        PanelResource<Row> previewEntry = (PanelResource<Row>) entry;
        String pageUrl = CmsRoutes.subpage(request.panelSlug(), HohenheimSlugs.INSTANCES, instanceId,
            HohenheimSlugs.Tab.DEPLOYMENTS).toUrl();
        Function<Row, List<RowOffer>> offers = PanelActionOffers.rowsForRender(request, previewEntry, null, previews,
            request.access(), ReturnPath.of(pageUrl));
        List<InstanceDeploys.Preview> rows = new ArrayList<>();
        for (Row preview : previews) {
            Object status = preview.get(PreviewDeploymentModel.STATUS);
            WidgetBadge.Colors colors = WidgetBadge.colorsOf(PreviewDeploymentModel.STATUS, status);
            Integer pr = preview.get(PreviewDeploymentModel.PR_NUMBER);
            String hostname = Objects.toString(preview.get(PreviewDeploymentModel.HOSTNAME), "");
            Instant expires = preview.get(PreviewDeploymentModel.EXPIRES_AT);
            rows.add(new InstanceDeploys.Preview(Objects.toString(preview.get(PreviewDeploymentModel.GIT_REF), ""),
                pr == null ? "" : String.valueOf(pr), hostname,
                // A preview answers on its own generated name over plain HTTP until a certificate covers it.
                hostname.isEmpty() ? "" : "http://" + hostname,
                CmsSupport.enumLabel(PreviewDeploymentModel.STATUS, status), colors.variant(), colors.colorSet(),
                PreviewDeploymentModel.STATUS_RUNNING.equals(status), expires == null ? "" : expires.toString(),
                CmsRoutes.open(request.panelSlug(), HohenheimSlugs.PREVIEWS, preview.get(PreviewDeploymentModel.ID))
                    .toUrl(),
                ActionStateTranslator.bandRowOffers(offers.apply(preview), 0).allInvokes()));
        }
        return new InstanceDeploys.Previews(rows, enabled
            ? CmsRoutes.create(request.panelSlug(), HohenheimSlugs.PREVIEWS).toUrl()
                + "?" + HohenheimParams.PREVIEW_APPLICATION.getName() + "=" + instanceId
            : "");
    }

    /**
     * Where the deploys come from and what the last pushes did: the source line every reader may see, and each recent
     * delivery's outcome in words.
     *
     * @param webhook the push address and secret, only ever from {@link #webhookOf} behind the operator check
     */
    private static InstanceDeploys.@NonNull Source sourceOf(@NonNull Row application,
                                                           @NonNull Map<String, Object> settings,
                                                           InstanceDeploys.@Nullable Webhook webhook) {
        String repositoryUrl = Objects.toString(settings.get(GitSourceSchema.REPOSITORY_URL), "");
        // The scheduled check: how often it asks, and what its last answer was since this process started.
        int pollSeconds = GitSourceSchema.pollSeconds(settings);
        GitSourcePolls.Check check = pollSeconds == 0 ? null
            : GitSourcePolls.lastCheck(application.get(InstanceModel.ID));
        List<InstanceDeploys.Push> pushes = new ArrayList<>();
        for (Row delivery : Models.get(WebhookDeliveryModel.class).findRecent(application.get(InstanceModel.ID), 10)) {
            WebhookOutcome outcome = WebhookOutcome.ofToken(delivery.get(WebhookDeliveryModel.ACTION));
            Instant received = delivery.get(WebhookDeliveryModel.RECEIVED_AT);
            pushes.add(new InstanceDeploys.Push(Objects.toString(delivery.get(WebhookDeliveryModel.EVENT), ""),
                outcome == null
                    ? CmsSupport.resolvedTextOrDefault(HohenheimMicrocopy.WEBHOOK_OUTCOME.of("received"))
                    : CmsSupport.resolvedTextOrDefault(outcome.label()),
                outcome != null && outcome.acted(), received == null ? "" : received.toString()));
        }
        return new InstanceDeploys.Source(repositoryUrl.isEmpty()
                ? Objects.toString(settings.get(GitSourceSchema.REPOSITORY), "") : repositoryUrl,
            Objects.toString(settings.get(GitSourceSchema.BRANCH), ""), GitSourceSchema.autoDeploys(settings),
            pollSeconds / 60, check == null ? "" : check.at().toString(), check != null && check.failed(),
            check == null ? "" : CmsSupport.resolvedTextOrDefault(findingOf(check)), pushes, webhook);
    }

    /** What a scheduled check found, in words: what it found about the commit it saw, or why it saw none. */
    private static @NonNull Microcopy findingOf(GitSourcePolls.@NonNull Check check) {
        Microcopy failure = check.failure();
        if (failure != null) {
            return failure;
        }
        return Objects.requireNonNull(check.finding(), "a check that read the head names a finding")
            .wording(shortSha(check.remoteCommit()));
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
    private static @NonNull Lane workspaceLane(int instanceId, @NonNull WithheldFailure failures, int requestedPage) {
        BuildOperationModel model = Models.get(BuildOperationModel.class);
        long total = buildsOf(instanceId).count();
        PageWindow window = PageWindow.of(requestedPage, total, PAGE_SIZE, PageWindow.OutOfRange.CLAMP);
        List<Row> operations = total == 0 ? List.of()
            : buildsOf(instanceId).offset(window.offset()).limit(window.pageSize()).all();

        // Asked of the whole history, never of the page on screen: page 2 still knows a deploy runs.
        boolean deploying = buildsOf(instanceId)
            .where(BuildOperationModel.STATUS.eq(BuildOperationModel.LIFECYCLE.stored(OperationStatus.RUNNING)))
            .count() > 0;
        Row latest = model.latestSuccess(InstanceModel.MODEL_ID.toString(), instanceId);
        Instant liveSince = latest == null ? null : latest.get(BuildOperationModel.FINISHED_AT);
        List<InstanceDeploys.Deploy> deployments = new ArrayList<>();
        for (Row row : operations) {
            deployments.add(buildDeploy(row, failures));
        }
        return new Lane(deployments, window, total, deploying,
            latest == null ? "" : shortSha(latest.get(BuildOperationModel.SOURCE_REF)), "",
            liveSince == null ? "" : liveSince.toString(), false, false, false);
    }

    /**
     * One checkout-and-build row: its log is the output of the record's own checkout and build, which its owner needs
     * to fix a build, so it stays visible where a release's daemon step log does not.
     */
    private static InstanceDeploys.@NonNull Deploy buildDeploy(@NonNull Row row, @NonNull WithheldFailure failures) {
        return deploy("build-" + row.get(BuildOperationModel.ID),
            statusOf(BuildOperationModel.STATUS, row.get(BuildOperationModel.STATUS)),
            row.get(BuildOperationModel.SOURCE_REF), row.get(BuildOperationModel.DURATION_MS),
            row.get(BuildOperationModel.STARTED_AT),
            // The release lane's What column names each row; a build that has not (yet) become a release is a build.
            CmsSupport.resolvedTextOrDefault(HohenheimMicrocopy.DEPLOYMENTS.of("kind_build")), false,
            BuildOperationModel.LIFECYCLE.stored(OperationStatus::inFlight)
                .contains(Objects.toString(row.get(BuildOperationModel.STATUS), "")),
            Started.NONE, "",
            DeployDetail.of(failures.shown(row.get(BuildOperationModel.FAILURE_REASON)),
                row.get(BuildOperationModel.LOG), null));
    }

    /**
     * A status with the shared enum facets (WidgetBadge), the one projection both lanes use so semantic roles and
     * categorical hues survive.
     */
    static InstanceDeploys.@NonNull Status statusOf(@NonNull EnumField field, @Nullable Object status) {
        WidgetBadge.Colors colors = WidgetBadge.colorsOf(field, status);
        return new InstanceDeploys.Status(Objects.toString(status, ""), CmsSupport.enumLabel(field, status),
            colors.variant(), colors.colorSet(), colors.known());
    }

    /** One history row in the shape both lanes draw; its live log is set only on the deploy that runs now. */
    private static InstanceDeploys.@NonNull Deploy deploy(@NonNull String key, InstanceDeploys.@NonNull Status status,
                                                          @Nullable Object commit, @Nullable Object durationMs,
                                                          @Nullable Instant startedAt, @NonNull String kindLabel,
                                                          boolean release, boolean inFlight,
                                                          @NonNull Started started, @NonNull String mark,
                                                          @NonNull DeployDetail detail) {
        // A row that never reached a commit (a refused deploy, a failed checkout) stores the
        // branch it was asked for: it reads as that branch, never as its first eight letters.
        String source = Objects.toString(commit, "");
        boolean isCommit = COMMIT_SHA.matcher(source).matches();
        return new InstanceDeploys.Deploy(key, status, isCommit ? shortSha(source) : "", isCommit ? "" : source,
            durationLabel(durationMs), startedAt != null ? startedAt.toString() : "", kindLabel, release, inFlight,
            started.trigger(), started.text(), mark, detail, null);
    }

    /**
     * The ONLY place admin-only content may be read (the webhook push URL and the secret's reveal): every sensitive
     * value must be added inside this method so the single isAdmin gate at the call site stays an allowlist.
     * The webhook endpoint is intercepted before hostname routing, so any hostname pointing at the proxy works; an
     * exposing site's first exact hostname is the copy-pastable choice.
     *
     * AIDEV-NOTE: the secret itself never enters the page: only the reference of its reveal
     * ({@link HohenheimSecrets#WEBHOOK_SECRET}), fetched per press through core's reveal route (admin-gated, recorded).
     */
    private static InstanceDeploys.@NonNull Webhook webhookOf(@NonNull Row instance) {
        Integer instanceId = instance.get(InstanceModel.ID);
        Map<String, Object> settings = ApplicationReleases.storedSettings(instance);
        // AIDEV-NOTE: the git webhook is intercepted by SiteDispatcher BEFORE the zenit
        // conduit chain, so it is deliberately outside the Endpoint framework and has no
        // RouteTarget. Referencing the handler's own PREFIX constant is what keeps this
        // display URL from drifting away from the route that actually answers.
        return new InstanceDeploys.Webhook(HookUrls.forInstance(instanceId, GitWebhookHandler.PREFIX + instanceId),
            Objects.toString(settings.get(GitSourceSchema.WEBHOOK_SECRET), "").isEmpty()
                ? null : HohenheimSecrets.WEBHOOK_SECRET.ref(instanceId),
            GitSourceSchema.autoDeploys(settings));
    }

    /** A git object name, full or abbreviated; anything else in a commit slot is a ref. */
    private static final Pattern COMMIT_SHA = Pattern.compile("[0-9a-fA-F]{7,64}");

    private static String shortSha(Object sha) {
        String value = sha != null ? String.valueOf(sha) : "";
        return value.length() > 8 ? value.substring(0, 8) : value;
    }

    /** @return a stored run length in milliseconds as the request's reader words a span ("0,7 s", "1 min"), else "" */
    static @NonNull String durationLabel(@Nullable Object durationMs) {
        if (!(durationMs instanceof Number ms)) {
            return "";
        }
        return Wording.durationUnits(CmsSupport.currentReader(), Duration.ofMillis(ms.longValue()),
            Zenit.getMessageResolver());
    }

}
