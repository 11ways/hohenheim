package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.HohenheimSlugs;
import be.elevenways.hohenheim.HohenheimIds;
import be.elevenways.hohenheim.HohenheimMicrocopy;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.hohenheim.HohenheimTemplateIds;
import be.elevenways.hohenheim.model.*;
import be.elevenways.hohenheim.HohenheimCapabilities;
import be.elevenways.hohenheim.instance.InstanceOperations;
import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.hohenheim.server.cms.AppDirectory.App;
import be.elevenways.hohenheim.server.cms.AppDirectory.Cells;
import be.elevenways.hohenheim.server.cms.AppDirectory.HeldApp;
import be.elevenways.hohenheim.server.cms.AppDirectory.Listed;
import be.elevenways.hohenheim.server.cms.AppDirectory.Source;
import be.elevenways.hohenheim.server.cms.AppDirectory.Wording;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.protoblast.common.typed.CoreTypes;
import be.elevenways.zenit.cms.common.action.ActionPlacement;
import be.elevenways.zenit.cms.common.action.ActionStyle;
import be.elevenways.zenit.cms.common.action.CmsActionResult;
import be.elevenways.zenit.cms.common.action.ConfirmationSpec;
import be.elevenways.zenit.cms.common.action.PanelAction;
import be.elevenways.zenit.cms.common.page.CmsRoutes;
import be.elevenways.zenit.cms.common.panel.NavGroup;
import be.elevenways.zenit.cms.common.panel.Panel;
import be.elevenways.zenit.cms.common.panel.PanelRegistry;
import be.elevenways.zenit.cms.common.resource.HealthTone;
import be.elevenways.zenit.cms.common.resource.HeldRecords;
import be.elevenways.zenit.cms.common.resource.RecordHealth;
import be.elevenways.zenit.cms.common.resource.ListChrome;
import be.elevenways.zenit.cms.common.resource.PanelResource;
import be.elevenways.zenit.cms.common.resource.RecordOverview;
import be.elevenways.zenit.cms.common.resource.RelatedPage;
import be.elevenways.zenit.cms.common.resource.ResourceHealth;
import be.elevenways.zenit.cms.common.resource.ResourceList;
import be.elevenways.zenit.cms.common.resource.ResourceReads;
import be.elevenways.zenit.cms.common.resource.ResourceTabs;
import be.elevenways.zenit.cms.common.resource.StorePages;
import be.elevenways.zenit.cms.common.schema.ColumnSpec;
import be.elevenways.zenit.cms.common.schema.FilterSpec;
import be.elevenways.zenit.cms.common.schema.TableSpec;
import be.elevenways.zenit.cms.common.schema.TableView;
import be.elevenways.zenit.common.data.RecordPage;
import be.elevenways.zenit.common.orm.field.EnumField;
import be.elevenways.zenit.common.orm.field.Field;
import be.elevenways.zenit.common.orm.field.StringField;
import be.elevenways.zenit.common.live.LiveFeed;
import be.elevenways.zenit.common.operation.Operation;
import be.elevenways.zenit.common.operation.OperationGate;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.common.ui.Icon;
import be.elevenways.zenit.server.operation.CarriedOperations;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Predicate;

/**
 * The Apps list of both panels: one row per app of the {@link AppDirectory}, each of this server's opening its record
 * page; on the operator's panel an administrator also sees every linked server's apps as they last told them, with a
 * Server column and filter that exist only while this server manages one.
 *
 * AIDEV-NOTE: a typed store over the directory, fully loaded per request and paged in memory
 * ({@link StorePages#inMemory}): the directory already holds only what the viewer may list, and an installation's apps
 * are counted in hundreds. The health column is the verdict each row's own record page leads with ({@link AppHealth},
 * read by the directory, or told by its server); the kind and state filters are the directory's closed {@link Source}
 * set and the framework's {@link HealthTone}, each derived from its home.
 * AIDEV-NOTE: a linked server's row is drawn by this server's cell code from the state it holds and opens this list's
 * own page of it, the app overview drawn from that state alone (AppOverview.listedTab); this server's own row opens its
 * record's page, whose overview leads with the same facts. A silent server's rows recede, draw no state glyph and say
 * when it last answered, and the list's notice over its rows says it does not answer.
 * AIDEV-NOTE: the page of a linked server's app offers Deploy, Restart, Stop and Start as the operations of its own
 * server, carried there (CarriedOperations.over): they run there as the person through that server's own pipeline,
 * never here. Only a workload has them, only while its server answers. Its record's other tabs (logs, console,
 * files, stats, backups, volumes, databases, history) are not offered here: they show what runs on that server and
 * wait for the stream relay; the overview says so in words.
 *
 * @author Jelle De Loecker
 * @since  0.2.0
 */
public final class AppParts {

    private static final StringField NAME = StringField.builder("name").label(HohenheimMicrocopy.APP_LIST.of("name"))
        .build();
    private static final StringField ADDRESS_TEXT = StringField.builder("address_text")
        .label(HohenheimMicrocopy.APP_LIST.of("address")).build();
    private static final StringField KIND = StringField.builder("kind").label(HohenheimMicrocopy.APP_LIST.of("kind"))
        .build();
    private static final StringField HOST = StringField.builder("host").label(HohenheimMicrocopy.APP_LIST.of("host"))
        .build();
    private static final EnumField TYPE = typeField();
    private static final EnumField STATE = stateField();

    /** The two drawn columns no field carries: the address cell and the main address's HTTPS. */
    private static final String ADDRESS_COLUMN = "address";
    private static final String HTTPS_COLUMN = "https";

    private AppParts() {
    }

    /**
     * @param related the slugs of the admin entries an app is made of (sites, instances, stacks, projects) that this
     *                node registers, linked from the list's toolbar
     * @param shown   the slugs of this node's entries whose rows the list draws ({@link AppDirectory#sourceEntrySlugs})
     * @param putOnline the entry that puts something online, null on a node that has none
     * @return the operator's Apps list
     */
    static @NonNull PanelResource<Listed> admin(@NonNull List<String> related, @NonNull List<String> shown,
                                             @Nullable String putOnline) {
        return onward(entry(HohenheimIds.id("app"), HohenheimSlugs.ADMIN, true), related, shown, putOnline, null,
            carriedVerbs())
            .tabs(ResourceTabs.<Listed>of(List.of(AppOverview.listedTab())))
            .build();
    }

    /** Who may ask a linked server's app to do something from here: an administrator of this server. */
    private static final OperationGate ADMINISTRATOR = OperationGate.permission(HohenheimPanel.ACCESS);

    /** Start, carried to the server the app lives on. */
    static final Operation<Listed, Void, Void> START = CarriedOperations.over(InstanceOperations.START,
        HohenheimIds.id("held_app_start"), AppDirectory.APP, ADMINISTRATOR, AppParts::where);

    /** Stop, carried to the server the app lives on. */
    static final Operation<Listed, Void, Void> STOP = CarriedOperations.over(InstanceOperations.STOP,
        HohenheimIds.id("held_app_stop"), AppDirectory.APP, ADMINISTRATOR, AppParts::where);

    /** Restart, carried to the server the app lives on. */
    static final Operation<Listed, Void, Void> RESTART = CarriedOperations.over(InstanceOperations.RESTART,
        HohenheimIds.id("held_app_restart"), AppDirectory.APP, ADMINISTRATOR, AppParts::where);

    /** Deploy, carried to the server the app lives on: its run there is followed by this server's own run. */
    static final Operation<Listed, Void, Void> DEPLOY = CarriedOperations.over(InstanceOperations.DEPLOY,
        HohenheimIds.id("held_app_deploy"), AppDirectory.APP, ADMINISTRATOR, AppParts::where);

    /** Roll back, carried to the server the app lives on, which decides whether it has a release to go back to. */
    static final Operation<Listed, Void, Void> ROLLBACK = CarriedOperations.over(InstanceOperations.ROLLBACK,
        HohenheimIds.id("held_app_rollback"), AppDirectory.APP, ADMINISTRATOR, AppParts::where);

    /** @return where a linked server's workload lives, null for this server's apps and what is no workload */
    private static CarriedOperations.@Nullable Located where(@NonNull Listed app) {
        return app instanceof HeldApp held && held.cells().source() == Source.WORKLOAD
            ? new CarriedOperations.Located(held.held().key(), String.valueOf(held.cells().id())) : null;
    }

    /** @return whether the app is a linked server's workload whose server answers, so its verbs are carried */
    private static boolean carries(@NonNull Listed app) {
        return where(app) != null && !((HeldApp) app).held().silent();
    }

    /**
     * The verbs a linked server's workload offers on its page here, in the board's order: Deploy, Roll back, Restart,
     * Stop, then Start.
     */
    private static @NonNull List<PanelAction<Listed>> carriedVerbs() {
        return List.of(
            PanelAction.<Listed, Void>placesRun(DEPLOY, ActionPlacement.ROW)
                .label(HohenheimMicrocopy.INSTANCE.of("deploy"))
                .hiddenWhen(app -> !carries(app))
                .build(),
            PanelAction.<Listed, Void>placesRun(ROLLBACK, ActionPlacement.ROW)
                .label(HohenheimMicrocopy.INSTANCE.of("rollback"))
                .inlineInRow(false)
                .hiddenWhen(app -> !carries(app))
                .build(),
            PanelAction.<Listed, Void>places(RESTART, ActionPlacement.ROW, (request, result) ->
                    CmsActionResult.refreshWithToast(HohenheimMicrocopy.INSTANCE.of("restarted_toast")
                        .withArg("name", request.subject().cells().name())))
                .label(HohenheimMicrocopy.INSTANCE.of("restart"))
                .icon(Icon.of("rotate-right"))
                .inlineInRow(false)
                .hiddenWhen(app -> !carries(app))
                .confirmation(ConfirmationSpec.verb(HohenheimMicrocopy.INSTANCE.of("restart"),
                    HohenheimMicrocopy.INSTANCE.of("restart_confirm"), ActionStyle.DEFAULT))
                .build(),
            PanelAction.<Listed, Void>places(STOP, ActionPlacement.ROW, (request, result) ->
                    CmsActionResult.refreshWithToast(HohenheimMicrocopy.INSTANCE.of("stopped_toast")
                        .withArg("name", request.subject().cells().name())))
                .label(HohenheimMicrocopy.INSTANCE.of("stop"))
                .icon(Icon.of("stop"))
                .inlineInRow(false)
                .style(ActionStyle.DESTRUCTIVE)
                .hiddenWhen(app -> !carries(app))
                .confirmation(ConfirmationSpec.verb(HohenheimMicrocopy.INSTANCE.of("stop"),
                    HohenheimMicrocopy.INSTANCE.of("stop_confirm"), ActionStyle.DESTRUCTIVE))
                .build(),
            PanelAction.<Listed, Void>places(START, ActionPlacement.ROW, (request, result) ->
                    CmsActionResult.refreshWithToast(HohenheimMicrocopy.HELD_APP.of("held_app_started_toast")
                        .withArg("name", request.subject().cells().name())))
                .icon(Icon.of("play"))
                .inlineInRow(false)
                .hiddenWhen(app -> !carries(app))
                .build());
    }

    /**
     * @param related   the slugs of the /manage entries an app is made of that this node registers, linked from the
     *                  list's toolbar
     * @param shown     the slugs of this node's /manage entries whose rows the list draws
     *                  ({@link AppDirectory#sourceEntrySlugs})
     * @param putOnline the entry that puts something online, null on a node that has none
     * @return the /manage twin: the apps made of the sites and instances the caller may list there; no host column,
     *         since the delegated panel names no host
     */
    static @NonNull PanelResource<Listed> manage(@NonNull List<String> related, @NonNull List<String> shown,
                                              @Nullable String putOnline) {
        // A tenant granted nothing an app is made of sees no empty entry; the list stays scoped.
        return ManageTwin.listed(onward(entry(ManageTwin.id("app"), HohenheimSlugs.MANAGE, false), related, shown,
                    putOnline, InstanceTemplateParts::offersTenantCatalog, List.of()),
                access -> HohenheimAccess.managesAnySite(access)
                    || HohenheimAccess.reachesAny(access, InstanceModel.MODEL_ID, HohenheimCapabilities.VIEW))
            .build();
    }

    /**
     * Both twins' sidebar place, toolbar pages, primary verb and the entries whose rows they draw, so a site's or an
     * instance's record trails "Apps / record", never through its own list.
     *
     * @param offered who is offered the primary verb, null for everyone the panel admits
     * @param verbs   the record verbs the page offers beside the primary verb
     */
    private static PanelResource.@NonNull Builder<Listed> onward(PanelResource.@NonNull Builder<Listed> builder,
                                                              @NonNull List<String> related,
                                                              @NonNull List<String> shown,
                                                              @Nullable String putOnline,
                                                              @Nullable Predicate<AccessContext> offered,
                                                              @NonNull List<PanelAction<Listed>> verbs) {
        builder.navGroup(NavGroup.DEFAULT).navOrder(20).showsRowsOf(shown.toArray(String[]::new));
        List<RelatedPage> pages = new ArrayList<>();
        for (String slug : related) {
            pages.add(RelatedPage.toPeer(slug));
        }
        builder.relatedPages(pages.toArray(RelatedPage[]::new));
        List<PanelAction<Listed>> actions = new ArrayList<>();
        if (putOnline != null) {
            actions.add(putOnlineAction(putOnline, offered));
        }
        actions.addAll(verbs);
        if (!actions.isEmpty()) {
            builder.actions(actions);
        }
        return builder;
    }

    /**
     * The feed the app lists hear: every model an app's row and its health read, so a change there reaches the list at
     * once; what the linked servers tell wakes the operator's list through its declaration ({@link ClusterLists}).
     */
    private static final LiveFeed APPS_FEED = LiveFeed.of(AccessContext::isAccount, InstanceModel.MODEL_ID,
        SiteModel.MODEL_ID, SiteDomainModel.MODEL_ID, StackModel.MODEL_ID, StackServiceModel.MODEL_ID,
        HostModel.MODEL_ID, CertificateModel.MODEL_ID, ProtectedPathModel.MODEL_ID,
        BuildOperationModel.MODEL_ID, ReleaseOperationModel.MODEL_ID);

    private static PanelResource.@NonNull Builder<Listed> entry(@NonNull Identifier id, @NonNull String panelSlug,
                                                                boolean withHost) {
        // The operator's list also shows every linked server's apps, as they last told them (THE declaration of a
        // list of every server's things); the /manage twin lists the tenant's apps of this server alone.
        HeldRecords<Listed> held = withHost ? held() : null;
        StorePages<Listed> pages = new StorePages<>() {
            @Override
            public @NonNull List<Field<?, ?>> fields() {
                return held != null ? List.of(NAME, ADDRESS_TEXT, KIND, HOST, held.field(), TYPE, STATE)
                    : List.of(NAME, ADDRESS_TEXT, KIND, TYPE, STATE);
            }

            @Override
            public @NonNull List<String> searchColumns() {
                return withHost ? List.of(NAME.getName(), ADDRESS_TEXT.getName(), HOST.getName())
                    : List.of(NAME.getName(), ADDRESS_TEXT.getName());
            }

            @Override
            public @NonNull RecordPage<Listed> page(TableView.@NonNull Applied<Listed> applied,
                                                    @NonNull AccessContext access) {
                // This server's own apps: the list's held merge sets the linked servers' beside them.
                return this.inMemory(applied, own(panelSlug, access), (app, column) -> values(app).get(column.name()),
                    access);
            }
        };
        ResourceList.Builder<Listed> list = ResourceList.store(table(held), pages, APPS_FEED)
            // The notice about a silent server stands over the rows it speaks of (board FO119).
            .chrome(ListChrome.MINIMAL.withNoticeOverRows(true))
            .emptyDescription(HohenheimMicrocopy.APP_LIST.of("empty"));
        if (held != null) {
            list.search(NAME.getName(), ADDRESS_TEXT.getName(), HOST.getName());
            list.held(held);
            list.notice(held::notice);
            list.toolNote(access -> held.counted(access, own(panelSlug, access).size()));
        } else {
            list.search(NAME.getName(), ADDRESS_TEXT.getName());
        }
        return PanelResource.builder(id, HohenheimSlugs.APPS, AppDirectory.APP)
            .label(HohenheimMicrocopy.APP_LIST.of("plural"))
            .recordLabel(HohenheimMicrocopy.APP_LIST.of("singular"))
            // The operator reads the data model it lists; a tenant reads their apps, never "sites, instances and
            // stacks".
            .description(withHost ? HohenheimMicrocopy.APP.of("nav_hint")
                : HohenheimMicrocopy.MANAGE_APP.of("nav_hint"))
            .icon(Icon.of("cubes"))
            // The one count the sidebar carries: this server's apps with a problem, as the Apps tile says them. A
            // linked server's apps are left out of it until Jelle decides whether they count.
            .navBadge(access -> {
                Panel panel = PanelRegistry.getBySlug(panelSlug);
                int problems = panel == null ? 0 : AppDirectory.withProblem(AppDirectory.read(panel, access));
                return problems == 0 ? null : (long) problems;
            })
            .health(ResourceHealth.of((app, access) -> app.cells().health()))
            .list(list.rowLink((app, request) -> switch (app) {
                case App local -> local.target();
                // A linked server's app opens this list's own page of it, drawn here from what that server told.
                case HeldApp linked -> CmsRoutes.subpage(panelSlug, HohenheimSlugs.APPS, linked.cells().key(),
                    RecordOverview.SLUG);
            }).build())
            // The list's own page is a linked server's app's; this server's app opens its record's page, so its key
            // names nothing here. Read from the request's one read of the held apps, so a page of rows whose verbs
            // each load their app reads nothing more per row.
            .reads(ResourceReads.<Listed>typed(app -> app.cells().key())
                .load((key, access) -> held == null ? null : held.records(access).stream()
                    .filter(app -> app.cells().key().equals(key)).findFirst().orElse(null))
                .values(AppParts::values)
                .build()
                .title(app -> Microcopy.literalOrNull(app.cells().name())));
    }

    /**
     * The operator's Apps list's declaration of every linked server's apps: as they last told them, drawn by this
     * server's own cells, in the list's own order by name; their verbs are carried to their server (carriedVerbs).
     */
    private static @NonNull HeldRecords<Listed> held() {
        return ClusterLists.<Listed>declare(HohenheimIds.id("app"),
                access -> List.copyOf(AppStates.held(Wording.of(access))),
                app -> app instanceof HeldApp linked ? linked.held() : null, app -> app.cells().key(), "held_band",
                "list_app_count")
            .cells((app, column) -> values(app).get(column.name()))
            .health(app -> app.cells().health())
            .order(Comparator.comparing((Listed app) -> app.cells().name().toLowerCase(Locale.ROOT))
                .thenComparing(app -> app.cells().key()))
            .actsOnHeld()
            .build();
    }

    /**
     * The table: state glyph, app, address, kind, host and server (operator only; the server while one is linked),
     * HTTPS, fix; the filter columns stay hidden.
     *
     * @param held the operator's declaration of every linked server's apps, null on the /manage twin
     */
    private static @NonNull TableSpec<Listed> table(@Nullable HeldRecords<Listed> held) {
        TableSpec.Builder<Listed> table = TableSpec.<Listed>builder()
            .column(ResourceHealth.column())
            .column(ColumnSpec.fromField(NAME).sortable().build())
            .column(ColumnSpec.virtual(ADDRESS_COLUMN, HohenheimMicrocopy.APP_LIST.of("address"))
                .renderer(HohenheimTemplateIds.CELL_SITE_HOSTNAMES).build())
            .column(ColumnSpec.fromField(ADDRESS_TEXT).hidden().build())
            .column(ColumnSpec.fromField(KIND).sortable().build());
        if (held != null) {
            // A website no instance serves runs on no host: a dash, never the framework's "None".
            table.column(ColumnSpec.fromField(HOST).sortable().absent(HohenheimMicrocopy.APP_LIST.of("host_none"))
                .build());
            table.column(held.column());
        }
        // The main address's HTTPS in the Addresses list's words and cell, never the app's overall verdict; then the
        // framework's fix cell ("Get a certificate"), offered as the app's record's band offers it.
        table.column(ColumnSpec.virtual(HTTPS_COLUMN, HohenheimMicrocopy.APP_LIST.of("https"))
            .renderer(HohenheimTemplateIds.CELL_STATE_LINE).build())
            .column(ResourceHealth.fixColumn())
            .column(ColumnSpec.fromField(TYPE).hidden().build())
            .column(ColumnSpec.fromField(STATE).hidden().build())
            .filter(FilterSpec.leaf(TYPE, CoreTypes.EQUALS).label(HohenheimMicrocopy.APP_LIST.of("kind")).build());
        if (held != null) {
            table.filter(FilterSpec.leaf(HOST, CoreTypes.CONTAINS).label(HohenheimMicrocopy.APP_LIST.of("host"))
                .build());
        }
        table.filter(FilterSpec.leaf(STATE, CoreTypes.EQUALS).label(HohenheimMicrocopy.APP_LIST.of("state")).build());
        if (held != null) {
            // The board's order: Kind, Host, State, Server.
            table.filter(held.filter());
        }
        return table.build();
    }

    /** @return every column's value of one app, keyed by the column it fills; its server is the list's own */
    private static @NonNull Map<String, Object> values(@NonNull Listed app) {
        Cells cells = app.cells();
        Map<String, Object> values = new HashMap<>();
        values.put(NAME.getName(), cells.name());
        values.put(ADDRESS_COLUMN, cells.address());
        values.put(ADDRESS_TEXT.getName(), cells.addressText());
        values.put(KIND.getName(), cells.kind());
        values.put(HOST.getName(), cells.host());
        values.put(HTTPS_COLUMN, cells.https());
        values.put(TYPE.getName(), cells.source() == null ? null : cells.source().token());
        values.put(STATE.getName(), cells.health().tone().token());
        return values;
    }

    /** @return this server's apps the named panel lists for this viewer; none for a panel this node does not register */
    private static @NonNull List<Listed> own(@NonNull String panelSlug, @NonNull AccessContext access) {
        Panel panel = PanelRegistry.getBySlug(panelSlug);
        return panel == null ? List.of() : List.copyOf(AppDirectory.read(panel, access));
    }

    /**
     * The list's primary verb: the Put something online flow.
     *
     * @param offered who is offered it, null for everyone the panel admits
     */
    private static @NonNull PanelAction<Listed> putOnlineAction(@NonNull String entrySlug,
                                                                @Nullable Predicate<AccessContext> offered) {
        PanelAction.LinkBuilder<Listed> link = PanelAction.<Listed>link(HohenheimIds.id("app_put_online"),
                ActionPlacement.HEADER)
            .label(HohenheimMicrocopy.APP_LIST.of("put_online"))
            .icon(Icon.of("plus"))
            .style(ActionStyle.PRIMARY)
            .inlineInHeader(true)
            .toEntry(entrySlug);
        if (offered != null) {
            link.shownWhen((app, access) -> offered.test(access));
        }
        return link.build();
    }

    /** The kind filter's values: the directory's own closed set. */
    private static @NonNull EnumField typeField() {
        EnumField.Builder builder = EnumField.builder("type");
        for (Source source : Source.values()) {
            builder.value(source.token(), spec -> spec.label(HohenheimMicrocopy.APP_LIST.of("type_" + source.token())));
        }
        return builder.label(HohenheimMicrocopy.APP_LIST.of("kind")).build();
    }

    /** The state filter's values: the framework's verdict tones an app's verdict can carry, worded for an app. */
    private static @NonNull EnumField stateField() {
        EnumField.Builder builder = EnumField.builder("state");
        for (HealthTone tone : HealthTone.values()) {
            if (judgesAnApp(tone)) {
                builder.value(tone.token(), spec -> spec.label(stateLabel(tone.token())));
            }
        }
        return builder.label(HohenheimMicrocopy.APP_LIST.of("state")).build();
    }

    /**
     * @return whether an app's verdict ({@link AppHealth}) can carry this tone, so the state filter offers it: an app
     *         is never judged off (not set up or switched off), so filtering on it could match no app; a silent
     *         server's app carries no verdict of its own then, so no state filters it
     */
    static boolean judgesAnApp(@NonNull HealthTone tone) {
        return switch (tone) {
            case OK, ATTENTION, BROKEN, UNKNOWN, RETIRED -> true;
            case OFF, SILENT -> false;
        };
    }

    /** @return a verdict tone's one word for an app ("Working"), the state filter's */
    static @NonNull Microcopy stateLabel(@NonNull String toneToken) {
        return HohenheimMicrocopy.APP_LIST.of("state_" + toneToken);
    }

    /**
     * The Details card's word for a verdict: its tone's word, except where the tone is unknown, whose word ("Unknown")
     * would contradict a band that knows exactly what holds ("Not deployed yet", "Deploying"); every unknown verdict's
     * headline is that short state.
     */
    static @NonNull Microcopy stateWord(@NonNull RecordHealth verdict) {
        return switch (verdict.tone()) {
            case OK, ATTENTION, BROKEN, RETIRED, OFF -> stateLabel(verdict.tone().token());
            case UNKNOWN, SILENT -> verdict.headline();
        };
    }
}
