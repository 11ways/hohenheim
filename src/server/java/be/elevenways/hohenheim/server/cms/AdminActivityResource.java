package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.HohenheimMicrocopy;
import be.elevenways.hohenheim.HohenheimTemplateIds;
import be.elevenways.hohenheim.activity.ActivityRecordCell;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.typed.CoreTypes;
import be.elevenways.protoblast.common.typed.rule.Condition;
import be.elevenways.zenit.cms.common.panel.NavGroup;
import be.elevenways.zenit.cms.common.resource.ActivitySources;
import be.elevenways.zenit.cms.common.resource.PanelResource;
import be.elevenways.zenit.cms.common.schema.ColumnSpec;
import be.elevenways.zenit.cms.common.schema.FilterSpec;
import be.elevenways.zenit.cms.common.schema.SortSpec;
import be.elevenways.zenit.cms.common.schema.TableSpec;
import be.elevenways.zenit.cms.server.resource.ActivityAdmin;
import be.elevenways.zenit.common.orm.activity.ActivityLog;
import be.elevenways.zenit.common.orm.activity.ActivityModel;
import be.elevenways.zenit.common.orm.activity.ActivityRules;
import be.elevenways.zenit.common.orm.activity.ActivityText;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.query.rules.RuleText;
import be.elevenways.zenit.common.routing.BoundEndpoint;
import be.elevenways.zenit.common.security.AccountabilityOrigin;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.List;

/**
 * The framework activity log composed for this panel: a hohenheim-authored sidebar description, a readable subject, a
 * record column linking to what it names, and a list that opens on what a PERSON did.
 *
 * AIDEV-NOTE: group, order, slug, search, the actor and verb cells and the detail stay the framework's
 * ({@link ActivityAdmin#builder}); this composes only the editorial parts the shared log cannot know. Still named
 * AdminActivityResource so its callers keep their spelling; it is a parts holder, never instantiated.
 *
 * @author Jelle De Loecker
 * @since  0.9.0
 */
public final class AdminActivityResource {

    /** The cell renderer for the record column; see {@code cms/cell/activity-record.hwk}. */
    private static final String RECORD_RENDERER = HohenheimTemplateIds.CELL_ACTIVITY_RECORD;

    /**
     * The default scope: everything a PERSON did -- a RuleText expression on the origin TEXT filter, so the panel
     * renders it as a removable chip and the framework owns every override/clear rule.
     *
     * AIDEV-NOTE: the discriminator is the ORIGIN, never the verb. Several verbs ("deployed", "stopped",
     * "reaped_controller_objects", "restored_backup", "app_updated") are written by BOTH the operator lane and a
     * sweeper, so a verb denylist would hide real operator actions. The surfaces that act for a person are the origin
     * vocabulary's own fact ({@link AccountabilityOrigin#actsForAPerson()}), so system work, seeds and work that
     * declared no identity at all ("Unattributed") stay out without a list here. The "is empty" arm keeps a row whose
     * origin was never stamped visible: unknown provenance is not background provenance, and IS_EMPTY on a TEXT
     * variable matches null as well as "".
     */
    private static final String HIDE_BACKGROUND_EXPRESSION = ActivityModel.ORIGIN.getName() + " in "
        + quotedList(AccountabilityOrigin.personTokens()) + " or " + ActivityModel.ORIGIN.getName() + " is empty";


    /**
     * The framework's own columns with the record id given a renderer, and every filter an operator needs to reach
     * one record.
     *
     * AIDEV-NOTE: the column list is COPIED from {@link ActivityAdmin#table()} rather than derived, because
     * {@code TableSpec} has no {@code toBuilder()}; the browser test asserts the two still describe the same columns.
     * Like the framework's, it shows the time and the sentence and offers the rest in the column picker.
     */
    private static final TableSpec<Row> TABLE = TableSpec.<Row>builder()
        .column(ColumnSpec.fromField(ActivityModel.CREATED_AT).build())
        .column(ActivityAdmin.summaryColumn())
        .column(ColumnSpec.fromField(ActivityModel.ACTOR).sortable().hidden().build())
        .column(ColumnSpec.fromField(ActivityModel.ACTION).filterable().hidden().build())
        .column(ColumnSpec.fromField(ActivityModel.MODEL).filterable().hidden().build())
        .column(ColumnSpec.fromField(ActivityModel.RECORD_ID)
            .renderer(RECORD_RENDERER).filterable().hidden().build())
        .column(ColumnSpec.fromField(ActivityModel.ORIGIN).filterable().hidden().build())
        .filter(FilterSpec.leaf(ActivityModel.MODEL, CoreTypes.CONTAINS).build())
        .filter(FilterSpec.leaf(ActivityModel.RECORD_ID, CoreTypes.CONTAINS).build())
        .filter(FilterSpec.leaf(ActivityModel.ACTION, CoreTypes.CONTAINS).build())
        .filter(FilterSpec.forPrincipal(ActivityModel.ACTOR_PRINCIPAL).build())
        .filter(FilterSpec.leaf(ActivityModel.ORIGIN, CoreTypes.CONTAINS).build())
        .filter(ActivityAdmin.onePerCommandFilter())
        .filter(ActivityAdmin.internalFilter())
        .defaultSort(SortSpec.desc(ActivityModel.CREATED_AT.getName()))
        .build();

    private AdminActivityResource() {
    }

    /**
     * @return the admin panel's activity log: the widest table in the panel (the column gear stays, but an audit trail
     *         is read forwards from now), opening on operator activity -- a DEFAULT the operator removes or replaces,
     *         never a base criteria they cannot escape
     */
    public static @NonNull PanelResource<Row> admin() {
        return ActivityAdmin.builder(NavGroup.SYSTEM, 90)
            .description(CmsSupport.navHint(HohenheimMicrocopy.SCOPE))
            .reads(ActivityAdmin.reads(AdminActivityResource::cell))
            .list(ActivityAdmin.list(TABLE)
                .chrome(CmsSupport.WIDE_LIST)
                .defaultFilter(ActivityAdmin.defaultFilter().with(ActivityModel.ORIGIN.getName(),
                        HIDE_BACKGROUND_EXPRESSION),
                    filter -> ActivityModel.ORIGIN.getName().equals(filter)
                        ? Microcopy.of("people_only").withFilter("scope", HohenheimMicrocopy.SCOPE)
                        : ActivityAdmin.defaultFilterChip(filter))
                .build())
            .build();
    }

    /**
     * What a person did, as one rule tree: the list's default origin scope plus the rows of models declared internal
     * left out. The dashboard's recent activity reads THIS, so it never shows rows the list opens without.
     *
     * AIDEV-NOTE: the internal half is spelled over the MODEL variable, not the list's "internal" filter, because the
     * dashboards' zenit.activity source derives its vocabulary from its projection and has no such variable; both
     * read the same declarations ({@link ActivityLog#internalModelTokens}), resolved per call because they are made
     * at class load of the modules that own the models.
     *
     * @return the rule tree selecting what a person did
     */
    public static @NonNull Condition peopleOnly() {
        StringBuilder text = new StringBuilder("(").append(HIDE_BACKGROUND_EXPRESSION).append(")");
        List<String> internal = ActivityLog.internalModelTokens();
        if (!internal.isEmpty()) {
            text.append(" and ").append(ActivityModel.MODEL.getName()).append(" not in ").append(quotedList(internal));
        }
        return RuleText.parse(text.toString()).require();
    }

    /**
     * What a person did to one record: the host page's recent activity.
     *
     * AIDEV-NOTE: a host's bookkeeping writes (the hourly heartbeat HostProbe stamps) run under
     * ActivityLog.suppressed since W5b, but the rows they wrote before stay in the log, and a host nobody touches kept
     * them as its ten newest rows forever (DEP9: Starfleet's local host, hourly "System changed local Server" rows
     * up to the W5b deploy). Reading what a person did leaves those rows in the log and off the card.
     *
     * @return the rule tree selecting what a person did to this record
     */
    public static @NonNull Condition peopleOnlyFor(@NonNull Model model, @NonNull Object recordId) {
        return Condition.all(ActivityRules.forRecord(model, recordId), peopleOnly());
    }

    /** @return the tokens as a RuleText list literal ({@code ["a", "b"]}) */
    private static @NonNull String quotedList(@NonNull List<String> tokens) {
        StringBuilder text = new StringBuilder("[");
        for (int i = 0; i < tokens.size(); i++) {
            text.append(i == 0 ? "\"" : ", \"").append(tokens.get(i)).append('"');
        }
        return text.append(']').toString();
    }

    /** @return the notice the activity page shows while recording is off, null while recording is on */
    public static @Nullable Microcopy recordingNotice() {
        return ActivityAdmin.recordingNotice();
    }

    /**
     * The recent-activity feed of the dashboard: what a person did ({@link #peopleOnly()}), one row per action, the
     * reading the activity list opens on.
     *
     * @return the rule tree the dashboard's recent activity reads
     */
    public static @NonNull Condition recentActions() {
        return Condition.all(peopleOnly(), ActivitySources.onePerCommand());
    }

    /**
     * The sentence links to the record it names inside /admin, the model token reads as its bare name and the record id
     * becomes a link to the record it names; every other column keeps the framework's cell (its actor and verb names).
     */
    private static @Nullable Object cell(@NonNull Row row, @NonNull ColumnSpec column) {
        String name = column.name();
        if (column.source() == null && ActivityAdmin.SUMMARY_COLUMN.equals(name)) {
            return ActivityAdmin.sentenceCell(row,
                AdminRecordLinks.detailForToken(row.get(ActivityModel.MODEL), row.get(ActivityModel.RECORD_ID)));
        }
        if (ActivityModel.MODEL.getName().equals(name)) {
            String humanized = ActivityText.humanizeModelToken(row.get(ActivityModel.MODEL));
            return humanized.isEmpty() ? null : humanized;
        }
        if (ActivityModel.RECORD_ID.getName().equals(name)) {
            return recordCellOf(row);
        }
        return null;
    }

    /** The record column's cell: the stored title, linked when a resource serves the model. */
    private static @Nullable ActivityRecordCell recordCellOf(@NonNull Row row) {
        String recordId = row.get(ActivityModel.RECORD_ID);
        if (recordId == null || recordId.isBlank()) {
            return null;
        }
        String title = row.get(ActivityModel.RECORD_TITLE);
        String label = title != null && !title.isBlank() ? title : recordId;
        // The admin-panel walk (AdminRecordLinks): this list lives in /admin, so the record links into /admin -- never
        // into the /manage narrowing of the same model, which the panel-aware walk would fall back to.
        BoundEndpoint<?> target = AdminRecordLinks.detailForToken(row.get(ActivityModel.MODEL), recordId);
        return new ActivityRecordCell(label, target != null ? target.toUrl() : null);
    }
}
