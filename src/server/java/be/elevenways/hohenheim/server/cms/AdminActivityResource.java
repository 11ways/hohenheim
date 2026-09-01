package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.activity.ActivityRecordCell;
import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.cms.common.page.CmsRoutes;
import be.elevenways.zenit.cms.common.panel.Panel;
import be.elevenways.zenit.cms.common.panel.PanelPeer;
import be.elevenways.zenit.cms.common.panel.PanelRegistry;
import be.elevenways.zenit.cms.common.render.activity.ActivityPresentation;
import be.elevenways.zenit.cms.common.resource.ActivityResource;
import be.elevenways.zenit.cms.common.resource.ListChrome;
import be.elevenways.zenit.cms.common.resource.RowResource;
import be.elevenways.zenit.cms.common.schema.ColumnSpec;
import be.elevenways.zenit.cms.common.schema.FilterSpec;
import be.elevenways.zenit.cms.common.schema.FilterState;
import be.elevenways.zenit.cms.common.schema.SortSpec;
import be.elevenways.zenit.cms.common.schema.TableSpec;
import be.elevenways.zenit.cms.common.schema.TableView;
import be.elevenways.zenit.common.orm.activity.ActivityModel;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.query.rules.Rule;
import be.elevenways.zenit.common.orm.query.rules.RuleGroup;
import be.elevenways.zenit.common.orm.query.rules.RuleOperator;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.common.security.Accountability;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.List;

/**
 * The framework activity log with a hohenheim-authored sidebar description, a readable
 * verb and subject, and a list that opens on what a PERSON did.
 *
 * AIDEV-NOTE: group, order, slug and every behaviour stay the framework's -- this exists
 * only because {@code description()} is a per-panel editorial decision and the shared
 * resource cannot know which sentence fits this product.
 */
public final class AdminActivityResource extends ActivityResource {

    /** The cell renderer for the record column; see {@code cms/cell/activity-record.hwk}. */
    private static final String RECORD_RENDERER = "hohenheim:cms/cell/activity-record";

    /**
     * The default scope: everything a PERSON did, background writes excluded.
     *
     * AIDEV-NOTE: the discriminator is the ORIGIN, never the verb. Several verbs
     * ("deployed", "stopped", "reaped_controller_objects", "restored_backup",
     * "app_updated") are written by BOTH the operator lane and a sweeper, so a verb
     * denylist would hide real operator actions. The IS_EMPTY arm keeps a row whose
     * origin was never stamped visible: unknown provenance is not background
     * provenance, and IS_EMPTY on a TEXT variable matches null as well as "".
     */
    private static final RuleGroup HIDE_BACKGROUND = RuleGroup.and(
        RuleGroup.or(
            Rule.of(ActivityModel.ORIGIN.getName(), RuleOperator.NOT_EQUALS,
                Accountability.ORIGIN_SYSTEM),
            Rule.of(ActivityModel.ORIGIN.getName(), RuleOperator.IS_EMPTY)));

    /**
     * The framework's own columns, with the two that were unreadable given a renderer:
     * the verb resolves through {@link ActivityPresentation} in {@link #cellValue} and the
     * record id renders as a link to the record it names.
     *
     * AIDEV-NOTE: the column list is COPIED from the framework resource rather than derived,
     * because {@code TableSpec} has no {@code toBuilder()}. A column added upstream will not
     * appear here -- the browser test asserts the two spellings still describe the same
     * columns, so the copy cannot drift silently.
     */
    private final TableSpec<Row> tableSpec = TableSpec.<Row>builder()
        .column(ColumnSpec.fromField(ActivityModel.CREATED_AT).build())
        .column(ColumnSpec.fromField(ActivityModel.ACTOR_LABEL).build())
        .column(ColumnSpec.fromField(ActivityModel.ACTION).filterable().build())
        .column(ColumnSpec.fromField(ActivityModel.MODEL).filterable().build())
        .column(ColumnSpec.fromField(ActivityModel.RECORD_ID)
            .renderer(RECORD_RENDERER).filterable().build())
        .column(ColumnSpec.fromField(ActivityModel.ORIGIN).filterable().build())
        .filter(FilterSpec.forField(ActivityModel.MODEL, FilterSpec.Kind.TEXT).build())
        .filter(FilterSpec.forField(ActivityModel.RECORD_ID, FilterSpec.Kind.TEXT).build())
        .filter(FilterSpec.forField(ActivityModel.ACTION, FilterSpec.Kind.TEXT).build())
        .filter(FilterSpec.forField(ActivityModel.ACTOR_LABEL, FilterSpec.Kind.TEXT).build())
        .filter(FilterSpec.forField(ActivityModel.ORIGIN, FilterSpec.Kind.TEXT).build())
        .defaultSort(SortSpec.desc(ActivityModel.CREATED_AT.getName()))
        .build();

    /**
     * The notice {@code /admin/activity} shows while recording is off, for any other
     * surface rendering the same log; null while recording is on.
     *
     * AIDEV-NOTE: asks the framework resource's own {@code emptyDescription()} instead of
     * reading {@code activity.enabled} a second time -- the fact and the sentence keep one
     * declaring home, so a dashboard band can never disagree with the activity page about
     * whether anything is being written down.
     */
    public static @Nullable Microcopy recordingNotice() {
        return new AdminActivityResource().emptyDescription();
    }

    @Override
    public @Nullable Microcopy description() {
        return Microcopy.of("nav_hint").withFilter("scope", "activity");
    }

    @Override
    public @NonNull TableSpec<Row> tableSpec() {
        return this.tableSpec;
    }

    /**
     * The actor column names a PERSON even when the entry stored no label, the verb reads
     * as a localized word, the model token reads as its bare name, and the record id
     * becomes a link to the record it names.
     *
     * AIDEV-NOTE: an entry carries the display name AS IT WAS at the time of acting, and
     * that stays authoritative whenever it is there -- an audit trail must not rewrite who
     * a row said acted. Only the blank case is resolved, and it is resolved by
     * {@link HohenheimAccess#subjectLabel} rather than a lookup spelled here, because the
     * stored actor is a bare principal id and every other surface in this panel renders
     * that id through the packed {@code user:5} vocabulary. Unresolvable renders the raw
     * token, which is the shared home's deliberate answer for a deleted user.
     */
    @Override
    public @Nullable Object cellValue(@NonNull Row row, @NonNull ColumnSpec column) {

        String name = column.name();

        if (ActivityModel.ACTION.getName().equals(name)) {
            String action = row.get(ActivityModel.ACTION);
            // ActivityPresentation.label falls open to the raw verb for a key nobody
            // registered, which is what the detail page and the dashboard feed show too.
            return action == null || action.isBlank() ? null : ActivityPresentation.label(action);
        }

        if (ActivityModel.MODEL.getName().equals(name)) {
            String token = row.get(ActivityModel.MODEL);
            String humanized = ActivityPresentation.humanizeModelToken(token);
            return humanized.isEmpty() ? null : humanized;
        }

        if (ActivityModel.RECORD_ID.getName().equals(name)) {
            return recordCellOf(row);
        }

        Object value = super.cellValue(row, column);
        if (!ActivityModel.ACTOR_LABEL.getName().equals(name)
                || (value instanceof String label && !label.isBlank())) {
            return value;
        }
        String actor = row.get(ActivityModel.ACTOR);
        return actor == null || actor.isBlank() ? value
            : HohenheimAccess.subjectLabel("user:" + actor);
    }

    /**
     * The list opens on operator activity: background writes are excluded UNLESS the
     * request names an origin itself, so the origin filter genuinely flips the scope.
     *
     * AIDEV-NOTE: the exclusion rides the ADVANCED filter tier rather than a base criteria,
     * because a base criteria is a scope the operator cannot escape and this one must be
     * escapable -- typing "system" into the origin filter has to show the sweepers. It
     * stands down for the rule and query tiers too: those can name the origin column
     * themselves, and two disagreeing origin constraints would AND to nothing.
     */
    @Override
    public @NonNull List<Row> listRows(TableView.Applied<Row> applied,
                                       @NonNull AccessContext accessContext) {
        return super.listRows(scopedToOperators(applied), accessContext);
    }

    /** The count under the same scope as {@link #listRows}, or the pager lies. */
    @Override
    public long countRows(TableView.Applied<Row> applied, @NonNull AccessContext accessContext) {
        return super.countRows(scopedToOperators(applied), accessContext);
    }

    /**
     * The widest table in the panel and the highest row count, so the column gear stays --
     * but an audit trail is read forwards from now, never through a saved view.
     */
    @Override
    public @NonNull ListChrome listChrome() {
        return CmsSupport.WIDE_LIST;
    }

    /**
     * The applied state with the background exclusion folded in, or the state untouched
     * when the request already says which origins it wants.
     */
    private static TableView.@NonNull Applied<Row> scopedToOperators(
            TableView.@NonNull Applied<Row> applied) {

        FilterState filter = applied.filter();
        if (filter.get(ActivityModel.ORIGIN.getName()) != null
                || filter.advanced() != null || filter.query() != null) {
            return applied;
        }
        return applied.withFilter(filter.withAdvanced(HIDE_BACKGROUND));
    }

    /** The record column's cell: the stored title, linked when a resource serves the model. */
    private static @Nullable ActivityRecordCell recordCellOf(@NonNull Row row) {

        String recordId = row.get(ActivityModel.RECORD_ID);
        if (recordId == null || recordId.isBlank()) {
            return null;
        }
        String title = row.get(ActivityModel.RECORD_TITLE);
        String label = title != null && !title.isBlank() ? title : recordId;
        return new ActivityRecordCell(label, recordUrl(row.get(ActivityModel.MODEL), recordId));
    }

    /**
     * The admin detail URL of the record an activity row names, or null when no registered
     * resource serves that model.
     *
     * AIDEV-NOTE: a COPY of the private {@code ActivityDetailPageRenderer.resolveRecordUrl}
     * walk in zenit-cms -- same panel/peer iteration, same ActivityResource skip, same
     * model-id match. It is copied because the upstream helper is private; it should
     * collapse into a zenit-cms API (a public "record url for (model token, id)") the
     * moment a second consumer needs it, and this copy deleted then.
     */
    private static @Nullable String recordUrl(@Nullable String modelToken,
                                              @NonNull String recordId) {

        Identifier modelId = modelToken == null || modelToken.isBlank()
            ? null : Identifier.tryParse(modelToken);
        if (modelId == null) {
            return null;
        }
        for (Panel panel : PanelRegistry.all()) {
            for (PanelPeer peer : panel.peers()) {
                if (peer instanceof ActivityResource || !(peer instanceof RowResource resource)) {
                    continue;
                }
                if (modelId.equals(resource.model().getModelId())) {
                    return CmsRoutes.detail(panel.slug(), resource.slug(), recordId).toUrl();
                }
            }
        }
        return null;
    }
}
