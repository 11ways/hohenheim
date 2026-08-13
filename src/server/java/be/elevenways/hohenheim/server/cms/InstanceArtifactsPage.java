package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.instance.InstanceArtifactView;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.protoblast.common.http.Uri;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.protoblast.common.time.RelativeTimeWording;
import be.elevenways.zenit.cms.common.page.CmsRoutes;
import be.elevenways.zenit.cms.common.render.action.InvokeActionState;
import be.elevenways.zenit.cms.common.render.table.EnumBadgeState;
import be.elevenways.zenit.cms.common.resource.RecordScopedPage;
import be.elevenways.zenit.cms.common.resource.RowResource;
import be.elevenways.zenit.cms.server.render.action.ActionStateTranslator;
import be.elevenways.zenit.common.conduit.Conduit;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.field.EnumField;
import be.elevenways.zenit.common.orm.field.DateTimeField;
import be.elevenways.zenit.common.orm.field.IntegerField;
import be.elevenways.zenit.common.orm.query.SortOrder;
import be.elevenways.zenit.common.result.ActionResult;
import be.elevenways.zenit.common.result.RenderTemplateResult;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.server.http.ReturnTarget;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The shared half of the per-instance Snapshots and Backups tabs: the SAME store-backed
 * rows the panel-wide resource lists, narrowed to one record.
 *
 * A scoped VIEW, not a second UI (the {@link InstanceSchedulesPage} shape): every row
 * relays its own resource's declared row actions through the standard action-state
 * translation -- so restore keeps the typed confirmation and the operator-only refusal it
 * declares there -- and links to the generated record page, which stays the one place a
 * row is deleted or inspected in full.
 *
 * AIDEV-NOTE: the resource is INJECTED because /admin and /manage hold different ones
 * ({@code ManageInstanceBackupResource} declares NO row actions at all, which is how
 * restore-to-new stays operator-only). Constructing one here would put a second answer
 * beside the panel's, which is exactly the drift the injection prevents.
 */
abstract class InstanceArtifactsPage implements RecordScopedPage<Row> {

    /** How many of the newest artifacts this scoped tab renders; the full list lives on the resource. */
    private static final int RECENT_LIMIT = 50;

    private final RowResource resource;
    private final ActionStateTranslator actions = new ActionStateTranslator();

    InstanceArtifactsPage(@NonNull RowResource resource) {
        this.resource = resource;
    }

    /** The record capability this tab and its operations answer to. */
    abstract @NonNull String capability();

    /** The artifact model's instance-id column. */
    abstract @NonNull IntegerField instanceIdField();

    abstract @NonNull EnumField statusField();

    abstract @NonNull DateTimeField createdAtField();

    /** The row's note text, blank when the model carries none (backups do not). */
    abstract @NonNull String noteOf(@NonNull Row artifact);

    abstract long sizeOf(@NonNull Row artifact);

    abstract @NonNull String errorOf(@NonNull Row artifact);

    /** The microcopy scope this page's own copy lives in. */
    abstract @NonNull String scope();

    /**
     * Hide AND enforce on the record capability the artifacts answer to, exactly like the
     * exec tab: an unoffered slug 404s, so this is a gate on the route and not only on
     * the tab strip.
     */
    @Override
    public boolean visibleFor(@NonNull Row record, @NonNull AccessContext accessContext) {
        return HohenheimAccess.hasInstanceCapability(
            accessContext, record.get(InstanceModel.ID), this.capability());
    }

    @Override
    public @NonNull ActionResult<?> render(@NonNull Conduit conduit,
                                           @NonNull AccessContext accessContext,
                                           @NonNull Row instance) {
        Integer instanceId = instance.get(InstanceModel.ID);
        String name = String.valueOf((Object) instance.get(InstanceModel.NAME));
        String panel = CmsSupport.panelSlug(conduit);
        String pageUrl = CmsRoutes.subpage(panel, "instances", instanceId, this.slug()).toUrl();

        // The newest page only: the panel-wide resource paginates, and this scoped view
        // must not turn into an unbounded load of every artifact an instance ever made.
        List<InstanceArtifactView> rows = new ArrayList<>();
        for (Row artifact : this.resource.model().find()
                .where(this.instanceIdField().eq(instanceId))
                .orderBy(this.createdAtField(), SortOrder.DESC)
                .limit(RECENT_LIMIT)
                .all()) {
            Object artifactId = artifact.get(this.resource.model().getPrimaryKeyField());
            rows.add(new InstanceArtifactView(
                artifactId instanceof Integer id ? id : 0,
                EnumBadgeState.of(this.statusField(), artifact.get(this.statusField())),
                this.noteOf(artifact),
                this.sizeOf(artifact),
                isoOf(artifact.get(this.createdAtField())),
                this.errorOf(artifact),
                CmsRoutes.detail(panel, this.resource.slug(), artifactId),
                this.invokesFor(artifact, accessContext, panel, artifactId, pageUrl)));
        }

        Map<String, Object> vars = new HashMap<>();
        vars.put("title", CmsSupport.pageTitle(conduit, this.scope(), name));
        vars.put("instanceName", name);
        vars.put("instanceId", instanceId);
        vars.put("artifacts", rows);
        vars.put("timeWording", RelativeTimeWording.resolve(
            conduit.getLocales(), conduit.getMessageResolver()));
        vars.put("recordTabs", recordTabs(conduit));
        return new RenderTemplateResult(
            Identifier.of("hohenheim", "cms/instance-artifacts"), vars);
    }

    /** That resource's own actions for THIS row and viewer, targeting its invoke route. */
    private @NonNull List<InvokeActionState> invokesFor(@NonNull Row artifact,
                                                        @NonNull AccessContext accessContext,
                                                        @NonNull String panel,
                                                        @NonNull Object artifactId,
                                                        @NonNull String pageUrl) {
        // AIDEV-NOTE: RowAction.Url is Uri-typed, so the typed target renders here.
        ActionStateTranslator.RowActionPresentation presentation =
            this.actions.translateRowActionsForList(this.resource.rowActions(), artifact,
                (actionId, row) -> new Uri(ReturnTarget.bind(
                    CmsRoutes.invokeRow(panel, this.resource.slug(), artifactId, actionId),
                    pageUrl).toUrl()),
                accessContext);
        List<InvokeActionState> invokes = new ArrayList<>(presentation.inlineInvokes());
        invokes.addAll(presentation.overflowInvokes());
        return invokes;
    }

    static @Nullable String isoOf(@Nullable Object value) {
        return value instanceof Instant instant ? instant.toString() : null;
    }

    static @NonNull String blankable(@Nullable Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}
