package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.HohenheimSlugs;
import be.elevenways.hohenheim.HohenheimTemplateIds;
import be.elevenways.hohenheim.instance.InstanceArtifactView;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.protoblast.common.time.RelativeTimeWording;
import be.elevenways.zenit.cms.common.page.CmsRoutes;
import be.elevenways.zenit.cms.common.panel.PanelEntry;
import be.elevenways.zenit.cms.common.panel.PanelRequest;
import be.elevenways.zenit.cms.common.render.action.InvokeActionState;
import be.elevenways.zenit.cms.common.render.table.EnumBadgeState;
import be.elevenways.zenit.cms.common.resource.PanelResource;
import be.elevenways.zenit.cms.common.resource.RecordTab;
import be.elevenways.zenit.cms.server.panel.PanelActionOffers;
import be.elevenways.zenit.cms.server.panel.PartsReads;
import be.elevenways.zenit.cms.server.render.action.ActionStateTranslator;
import be.elevenways.zenit.cms.server.render.action.RowOffer;
import be.elevenways.zenit.common.conduit.Conduit;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.field.EnumField;
import be.elevenways.zenit.common.orm.field.DateTimeField;
import be.elevenways.zenit.common.orm.field.IntegerField;
import be.elevenways.zenit.common.orm.query.SortOrder;
import be.elevenways.zenit.common.result.ActionResult;
import be.elevenways.zenit.common.result.RenderTemplateResult;
import be.elevenways.zenit.common.routing.ReturnPath;
import be.elevenways.zenit.common.security.AccessContext;
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
 * relays its own entry's placed operations through the standard row banding -- so restore
 * keeps the confirmation and the operator-only refusal it declares there -- and links to the
 * generated record page, which stays the one place a row is deleted or inspected in full.
 *
 * AIDEV-NOTE: the entry is READ FROM THE HOSTING PANEL by slug because /admin and /manage
 * hold different ones (the /manage backup twin places no restore-to-new, which is how it
 * stays operator-only). Constructing one here would put a second answer beside the
 * panel's, which is exactly the drift reading the panel's own prevents.
 */
abstract class InstanceArtifactsPage implements RecordTab.Rendered<Row> {

    /** How many of the newest artifacts this scoped tab renders; the full list lives on the resource. */
    private static final int RECENT_LIMIT = 50;

    private final String entrySlug;

    /** @param entrySlug the artifact entry both panels register under, admin and tenant twin alike */
    InstanceArtifactsPage(@NonNull String entrySlug) {
        this.entrySlug = entrySlug;
    }

    /**
     * Snapshots and backups are housekeeping: both tabs live in the strip's "More"
     * menu, declared once here because both subclasses answer the same way.
     */
    @Override public boolean secondaryTab() { return true; }

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
    public @NonNull ActionResult<?> render(@NonNull PanelRequest request, @NonNull Row instance) {
        Conduit conduit = request.conduit();
        AccessContext accessContext = request.access();
        Integer instanceId = instance.get(InstanceModel.ID);
        String name = String.valueOf((Object) instance.get(InstanceModel.NAME));
        String panel = request.panelSlug();
        String pageUrl = CmsRoutes.subpage(panel, HohenheimSlugs.INSTANCES, instanceId, this.slug()).toUrl();
        WithheldFailure failures = WithheldFailure.of(conduit);
        PanelEntry resource = this.entryOf(request);

        // The newest page only: the panel-wide resource paginates, and this scoped view
        // must not turn into an unbounded load of every artifact an instance ever made.
        List<InstanceArtifactView> rows = new ArrayList<>();
        for (Row artifact : PartsReads.model(resource).find()
                .where(this.instanceIdField().eq(instanceId))
                .orderBy(this.createdAtField(), SortOrder.DESC)
                .limit(RECENT_LIMIT)
                .all()) {
            Object artifactId = artifact.get(PartsReads.model(resource).getPrimaryKeyField());
            rows.add(new InstanceArtifactView(
                artifactId instanceof Integer id ? id : 0,
                EnumBadgeState.of(this.statusField(), artifact.get(this.statusField())),
                this.noteOf(artifact),
                this.sizeOf(artifact),
                isoOf(artifact.get(this.createdAtField())),
                failures.shown(this.errorOf(artifact)),
                CmsRoutes.detail(panel, this.entrySlug, artifactId),
                this.invokesFor(request, resource, artifact, pageUrl)));
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
            HohenheimTemplateIds.INSTANCE_ARTIFACTS, vars);
    }

    /**
     * The panel's own row entry this tab lists the records of.
     *
     * @throws IllegalStateException when the hosting panel does not register it beside the instance entry
     */
    private @NonNull PanelEntry entryOf(@NonNull PanelRequest request) {
        PanelEntry entry = request.panel().entryBySlug(this.entrySlug);
        if (entry instanceof PanelResource<?> parts) {
            return parts;
        }
        throw new IllegalStateException("Panel " + request.panelSlug() + " registers no row entry "
            + this.entrySlug + " beside its instances");
    }

    /** That entry's placed operations for THIS row and viewer. */
    private @NonNull List<InvokeActionState> invokesFor(@NonNull PanelRequest request,
                                                        @NonNull PanelEntry resource,
                                                        @NonNull Row artifact,
                                                        @NonNull String pageUrl) {
        List<RowOffer> placed = PanelActionOffers.rowOffers(request, resource, artifact, request.access(),
            ReturnPath.of(pageUrl));
        // Every band, the destructive tail included: restore IS destructive, and summing
        // the inline and overflow buckets by hand silently dropped it.
        return ActionStateTranslator.bandRowOffers(placed, 0).allInvokes();
    }


    static @Nullable String isoOf(@Nullable Object value) {
        return value instanceof Instant instant ? instant.toString() : null;
    }

    static @NonNull String blankable(@Nullable Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}
