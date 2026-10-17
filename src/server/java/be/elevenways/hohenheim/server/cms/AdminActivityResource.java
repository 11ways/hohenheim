package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.HohenheimMicrocopy;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.typed.CoreTypes;
import be.elevenways.protoblast.common.typed.rule.Condition;
import be.elevenways.zenit.cms.common.panel.NavGroup;
import be.elevenways.zenit.cms.common.resource.ActivitySources;
import be.elevenways.zenit.cms.common.resource.PanelResource;
import be.elevenways.zenit.cms.common.schema.ColumnSpec;
import be.elevenways.zenit.cms.common.schema.FilterSpec;
import be.elevenways.zenit.cms.common.schema.TableSpec;
import be.elevenways.zenit.cms.server.resource.ActivityAdmin;
import be.elevenways.zenit.common.orm.activity.ActivityRules;
import be.elevenways.zenit.common.orm.history.ActionModel;
import be.elevenways.zenit.common.orm.history.HistoryEntry;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.common.orm.history.HistoryText;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.query.rules.RuleText;
import be.elevenways.zenit.common.security.AccountabilityOrigin;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.List;

/**
 * The framework activity log composed for this panel: a hohenheim-authored sidebar description, a sentence linking the
 * record it names inside /admin, an app filter, and a list that opens on what a PERSON did.
 *
 * AIDEV-NOTE: group, order, slug, search, the actor and verb cells and the detail stay the framework's
 * ({@link ActivityAdmin#builder}); this composes only the editorial parts the shared log cannot know. Still named
 * AdminActivityResource so its callers keep their spelling; it is a parts holder, never instantiated.
 *
 * @author Jelle De Loecker
 * @since  0.9.0
 */
public final class AdminActivityResource {

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
    private static final String HIDE_BACKGROUND_EXPRESSION = ActionModel.ORIGIN.getName() + " in "
        + quotedList(AccountabilityOrigin.personTokens()) + " or " + ActionModel.ORIGIN.getName() + " is empty";


    /** The app filter's name, which keys its URL parameter ({@code filter.app=<instance id>}). */
    public static final String APP_FILTER = "app";

    /**
     * The framework's own columns and filters, plus every filter an operator needs to reach one record: the app it is
     * about, the subject record id and the origin that flips the default scope. The row draws only who, the app and
     * internal records (board Activity); the rest keep narrowing through their chips and a link's parameters.
     *
     * AIDEV-NOTE: an app is its workload's record, so the app filter offers instances through the
     * instance record source, whose row scope decides which a reader is offered; a site-only app has no workload and is
     * not offered. Its actions stay reachable through the record filter.
     */
    private static final TableSpec<Row> TABLE = ActivityAdmin.table().toBuilder()
        .filter(ActivityAdmin.subjectFilter(APP_FILTER, InstanceModel.MODEL_ID,
            HohenheimMicrocopy.HOHENHEIM.of("app_filter"), HohenheimMicrocopy.HOHENHEIM.of("app_filter_all")))
        .filter(FilterSpec.leaf(ActionModel.SUBJECT_ID, CoreTypes.CONTAINS).build())
        .filter(FilterSpec.leaf(ActionModel.ORIGIN, CoreTypes.CONTAINS).build())
        .filterRow(ActivityAdmin.ACTOR_FILTER, APP_FILTER, ActivityAdmin.INTERNAL_FILTER)
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
            .description(CmsSupport.navHint(HohenheimMicrocopy.HOHENHEIM))
            .reads(ActivityAdmin.reads(AdminActivityResource::cell))
            .list(ActivityAdmin.list(TABLE)
                .chrome(CmsSupport.WIDE_LIST.withFiltersOpen(true))
                .defaultFilter(ActivityAdmin.defaultFilter().with(ActionModel.ORIGIN.getName(),
                        HIDE_BACKGROUND_EXPRESSION),
                    filter -> ActionModel.ORIGIN.getName().equals(filter)
                        ? HohenheimMicrocopy.HOHENHEIM.of("people_only")
                        : ActivityAdmin.defaultFilterChip(filter))
                .build())
            .build();
    }

    /**
     * What a person did, as one rule tree: the list's default origin scope plus the actions holding a listed change
     * (core's listed criteria, {@link ActivitySources#recent()}). The dashboard's recent activity reads THIS, so it
     * never shows actions the list opens without.
     *
     * @return the rule tree selecting what a person did
     */
    public static @NonNull Condition peopleOnly() {
        return Condition.all(RuleText.parse(HIDE_BACKGROUND_EXPRESSION).require(), ActivitySources.recent());
    }

    /**
     * What a person did to one record: the host page's recent activity.
     *
     * AIDEV-NOTE: a host's bookkeeping writes (the hourly heartbeat HostProbe stamps) run under
     * ActivityLog.suppressed now, but the rows they wrote before stay in the log, and a host nobody touches kept
     * them as its ten newest rows forever (Starfleet's local host, hourly "System changed local Server" rows
     * up to the deploy that suppressed them). Reading what a person did leaves those rows in the log and off the card.
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
     * The recent-activity feed of the dashboard: what a person did ({@link #peopleOnly()}); one row is one action.
     *
     * @return the rule tree the dashboard's recent activity reads
     */
    public static @NonNull Condition recentActions() {
        return peopleOnly();
    }

    /**
     * The sentence links the action's subject record inside /admin and the subject model reads as its bare name;
     * every other column keeps the framework's cell (its actor and verb names).
     *
     * AIDEV-NOTE: the admin-panel walk (AdminRecordLinks): this list lives in /admin, so the record links into /admin
     * -- never into the /manage narrowing of the same model, which the panel-aware walk would fall back to.
     */
    private static @Nullable Object cell(@NonNull Row action, @NonNull HistoryEntry entry, @NonNull ColumnSpec column,
                                         @NonNull AccessContext reader) {
        String name = column.name();
        if (column.source() == null && ActivityAdmin.SUMMARY_COLUMN.equals(name)) {
            return ActivityAdmin.sentenceCell(entry, reader, AdminRecordLinks.detailForToken(
                action.get(ActionModel.SUBJECT_MODEL), action.get(ActionModel.SUBJECT_ID)));
        }
        if (ActionModel.SUBJECT_MODEL.getName().equals(name)) {
            String humanized = HistoryText.humanizeModelToken(action.get(ActionModel.SUBJECT_MODEL));
            return humanized.isEmpty() ? null : humanized;
        }
        return null;
    }
}
