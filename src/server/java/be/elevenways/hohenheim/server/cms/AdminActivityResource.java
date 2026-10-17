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
import be.elevenways.plumage.component.ViewerFieldValueText;
import be.elevenways.protoblast.common.i18n.LocaleChain;
import be.elevenways.protoblast.common.i18n.MessageResolver;
import be.elevenways.zenit.common.conduit.Conduit;
import be.elevenways.zenit.common.data.DataItem;
import be.elevenways.zenit.common.data.RecordSource;
import be.elevenways.zenit.common.data.RecordSourceRegistry;
import be.elevenways.zenit.common.orm.history.History;
import be.elevenways.zenit.common.orm.query.SortOrder;
import be.elevenways.zenit.widget.common.data.WidgetItem;
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

import java.util.ArrayList;
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
     * What happened to one record, the newest first, as the record pages' Recent cards list it: the actions of the
     * record's own history and the changes filed on it ({@link History#inContext}, reader-gated), narrowed by
     * {@code rules} over the shared activity source, each the source's item (its heading, its record noun, its link).
     *
     * AIDEV-NOTE: the per-record selection is core's inContext read, never a per-record rule on the source (ActionModel
     * projects no record variable). The rules narrow only the actions of that read's window, so a record whose
     * {@link #RECENT_WINDOW} newest actions are all bookkeeping shows fewer rows, never another record's. A host's
     * hourly heartbeat writes nothing (HostProbe), so its rows no longer crowd that window.
     *
     * @param rules the source rules an action must also meet ({@link ActivitySources#recent()}, {@link #peopleOnly()})
     * @return the items, empty for a reader the source refuses
     */
    public static @NonNull List<WidgetItem> recentFor(@NonNull Model model, @NonNull Object recordId,
                                                      @NonNull Condition rules, @NonNull AccessContext reader,
                                                      int limit) {
        RecordSource<?> source = RecordSourceRegistry.INSTANCE.byToken(CmsSupport.ACTIVITY_SOURCE);
        if (source == null || !source.authorizes(reader)) {
            return List.of();
        }
        List<String> window = new ArrayList<>();
        for (HistoryEntry entry : History.inContext(model, recordId, reader, null, RECENT_WINDOW).entries()) {
            window.add(entry.action().id());
        }
        if (window.isEmpty()) {
            return List.of();
        }
        Conduit conduit = reader.conduit();
        LocaleChain locales = conduit == null ? LocaleChain.empty() : conduit.getLocales();
        MessageResolver resolver = conduit == null ? null : conduit.getMessageResolver();
        List<WidgetItem> items = new ArrayList<>();
        for (Row action : source.buildQuery(null, rules, ActionModel.CREATED_AT, SortOrder.DESC, locales, reader)
                .where(ActionModel.ID.in(window)).limit(limit).all()) {
            DataItem item = source.item(action, locales, reader);
            List<String> lines = new ArrayList<>();
            if (item.subtitle() != null && !item.subtitle().isBlank()) {
                lines.add(item.subtitle());
            }
            lines.add(ViewerFieldValueText.CELL.text(null, ActionModel.CREATED_AT, action.get(ActionModel.CREATED_AT),
                locales, resolver));
            items.add(new WidgetItem(item.title() == null ? item.value() : item.title(), item.editUrl(), lines));
        }
        return items;
    }

    /** How many of a record's newest actions a Recent card narrows (see {@link #recentFor}). */
    private static final int RECENT_WINDOW = 50;

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
     * The sentence links the record the action names inside /admin and the subject model reads as its bare name;
     * every other column keeps the framework's cell (its actor and verb names).
     *
     * AIDEV-NOTE: the admin-panel walk (AdminRecordLinks): this list lives in /admin, so the record links into /admin
     * -- never into the /manage narrowing of the same model, which the panel-aware walk would fall back to. The record
     * is the framework's (ActivityAdmin.recordTarget): the action's subject, else its one change's record, since only
     * an operation stamps a subject and a plain save is no less a record's action.
     */
    private static @Nullable Object cell(@NonNull Row action, @NonNull HistoryEntry entry, @NonNull ColumnSpec column,
                                         @NonNull AccessContext reader) {
        String name = column.name();
        if (column.source() == null && ActivityAdmin.SUMMARY_COLUMN.equals(name)) {
            String model = action.get(ActionModel.SUBJECT_MODEL);
            String record = action.get(ActionModel.SUBJECT_ID);
            if ((model == null || record == null) && entry.changes().size() == 1) {
                model = entry.changes().get(0).model().toString();
                record = entry.changes().get(0).recordId();
            }
            return ActivityAdmin.sentenceCell(entry, reader, AdminRecordLinks.detailForToken(model, record));
        }
        if (ActionModel.SUBJECT_MODEL.getName().equals(name)) {
            String humanized = HistoryText.humanizeModelToken(action.get(ActionModel.SUBJECT_MODEL));
            return humanized.isEmpty() ? null : humanized;
        }
        return null;
    }
}
