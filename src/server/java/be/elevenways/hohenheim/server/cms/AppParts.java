package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.HohenheimSlugs;
import be.elevenways.hohenheim.HohenheimIds;
import be.elevenways.hohenheim.HohenheimMicrocopy;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.hohenheim.HohenheimTemplateIds;
import be.elevenways.hohenheim.model.*;
import be.elevenways.hohenheim.HohenheimCapabilities;
import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.hohenheim.server.cluster.LinkedServers;
import be.elevenways.hohenheim.server.cms.AppDirectory.App;
import be.elevenways.hohenheim.server.cms.AppDirectory.Cells;
import be.elevenways.hohenheim.server.cms.AppDirectory.Held;
import be.elevenways.hohenheim.server.cms.AppDirectory.HeldApp;
import be.elevenways.hohenheim.server.cms.AppDirectory.Listed;
import be.elevenways.hohenheim.server.cms.AppDirectory.Source;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.protoblast.common.typed.CoreTypes;
import be.elevenways.zenit.cms.common.action.ActionPlacement;
import be.elevenways.zenit.cms.common.action.ActionStyle;
import be.elevenways.zenit.cms.common.action.PanelAction;
import be.elevenways.zenit.cms.common.panel.NavGroup;
import be.elevenways.zenit.cms.common.panel.Panel;
import be.elevenways.zenit.cms.common.panel.PanelRegistry;
import be.elevenways.zenit.cms.common.resource.HealthTone;
import be.elevenways.zenit.cms.common.resource.RecordHealth;
import be.elevenways.zenit.cms.common.resource.ListChrome;
import be.elevenways.zenit.cms.common.resource.PanelResource;
import be.elevenways.zenit.cms.common.resource.RelatedPage;
import be.elevenways.zenit.cms.common.resource.ResourceHealth;
import be.elevenways.zenit.cms.common.resource.ResourceList;
import be.elevenways.zenit.cms.common.resource.ResourceReads;
import be.elevenways.zenit.cms.common.resource.StorePages;
import be.elevenways.zenit.cms.common.schema.ColumnSpec;
import be.elevenways.zenit.cms.common.schema.FilterSpec;
import be.elevenways.zenit.cms.common.schema.TableSpec;
import be.elevenways.zenit.cms.common.schema.TableView;
import be.elevenways.zenit.common.data.RecordPage;
import be.elevenways.zenit.common.operation.SubjectType;
import be.elevenways.zenit.common.orm.field.EnumField;
import be.elevenways.zenit.common.orm.field.Field;
import be.elevenways.zenit.common.orm.field.StringField;
import be.elevenways.zenit.common.peer.PeerRecordModel;
import be.elevenways.zenit.common.live.LiveFeed;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.common.ui.Icon;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
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
 * AIDEV-NOTE: a linked server's row is drawn by this server's cell code from the state it holds and opens nothing yet
 * (its page is the next step of the unified panel): no link, and a plain line saying so. A silent server's rows recede,
 * draw no state glyph and say when it last answered, and the list's notice says it does not answer.
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
    private static final StringField SERVER = StringField.builder("server")
        .label(HohenheimMicrocopy.APP_LIST.of("server_column")).build();
    private static final StringField HELD_NOTE = StringField.builder("held_note")
        .label(HohenheimMicrocopy.APP_LIST.of("held_note_column")).build();
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
        return onward(entry(HohenheimIds.id("app"), HohenheimSlugs.ADMIN, true), related, shown, putOnline, null)
            .build();
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
                    putOnline, InstanceTemplateParts::offersTenantCatalog),
                access -> HohenheimAccess.managesAnySite(access)
                    || HohenheimAccess.reachesAny(access, InstanceModel.MODEL_ID, HohenheimCapabilities.VIEW))
            .build();
    }

    /**
     * Both twins' sidebar place, toolbar pages, primary verb and the entries whose rows they draw, so a site's or an
     * instance's record trails "Apps / record", never through its own list.
     *
     * @param offered who is offered the primary verb, null for everyone the panel admits
     */
    private static PanelResource.@NonNull Builder<Listed> onward(PanelResource.@NonNull Builder<Listed> builder,
                                                              @NonNull List<String> related,
                                                              @NonNull List<String> shown,
                                                              @Nullable String putOnline,
                                                              @Nullable Predicate<AccessContext> offered) {
        builder.navGroup(NavGroup.DEFAULT).navOrder(20).showsRowsOf(shown.toArray(String[]::new));
        List<RelatedPage> pages = new ArrayList<>();
        for (String slug : related) {
            pages.add(RelatedPage.toPeer(slug));
        }
        builder.relatedPages(pages.toArray(RelatedPage[]::new));
        if (putOnline != null) {
            builder.actions(List.of(putOnlineAction(putOnline, offered)));
        }
        return builder;
    }

    /**
     * The feed the app lists hear: every model an app's row and its health read, and the master's keeping of what its
     * servers told (the kept state, the links' last seen), so a change there reaches the list at once.
     */
    private static final LiveFeed APPS_FEED = LiveFeed.of(AccessContext::isAccount, InstanceModel.MODEL_ID,
        SiteModel.MODEL_ID, SiteDomainModel.MODEL_ID, StackModel.MODEL_ID, StackServiceModel.MODEL_ID,
        HostModel.MODEL_ID, CertificateModel.MODEL_ID, ProtectedPathModel.MODEL_ID,
        BuildOperationModel.MODEL_ID, ReleaseOperationModel.MODEL_ID, PeerRecordModel.MODEL_ID,
        LinkedServerModel.MODEL_ID);

    private static PanelResource.@NonNull Builder<Listed> entry(@NonNull Identifier id, @NonNull String panelSlug,
                                                                boolean withHost) {
        StorePages<Listed> pages = new StorePages<>() {
            @Override
            public @NonNull List<Field<?, ?>> fields() {
                return withHost ? List.of(NAME, ADDRESS_TEXT, KIND, HOST, SERVER, HELD_NOTE, TYPE, STATE)
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
                return this.inMemory(applied, apps(panelSlug, access), (app, column) -> values(app).get(column.name()),
                    access);
            }

            @Override
            public @Nullable Microcopy facetLabel(@NonNull FilterSpec facet, @NonNull Listed app) {
                // The board's Server choice for a silent server: "Kuifje, does not answer".
                return SERVER.getName().equals(facet.name()) && app instanceof HeldApp held && held.held().silent()
                    ? HohenheimMicrocopy.APP_LIST.of("server_silent_option").withArg("server", held.held().server())
                    : null;
            }
        };
        ResourceList.Builder<Listed> list = ResourceList.store(table(withHost), pages, APPS_FEED)
            .chrome(ListChrome.MINIMAL)
            .emptyDescription(HohenheimMicrocopy.APP_LIST.of("empty"));
        if (withHost) {
            list.search(NAME.getName(), ADDRESS_TEXT.getName(), HOST.getName());
            list.notice(access -> silentServers(panelSlug, access));
        } else {
            list.search(NAME.getName(), ADDRESS_TEXT.getName());
        }
        return PanelResource.builder(id, HohenheimSlugs.APPS,
                SubjectType.of(HohenheimIds.id("app"), Listed.class, app -> app.cells().key()))
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
                case HeldApp held -> null;
            }).build())
            // A linked server's app has no page here yet: its address answers as a page that does not exist, so the
            // key loads this server's own apps alone.
            .reads(ResourceReads.<Listed>typed(app -> app.cells().key())
                .load((key, access) -> apps(panelSlug, access).stream()
                    .filter(app -> app instanceof App && app.cells().key().equals(key)).findFirst().orElse(null))
                .values(AppParts::values)
                .build()
                .title(app -> Microcopy.literalOrNull(app.cells().name())));
    }

    /**
     * The list's notice while a server it shows apps of does not answer, in the board's words, one sentence per
     * silent server; null while every one answers.
     */
    private static @Nullable Microcopy silentServers(@NonNull String panelSlug, @NonNull AccessContext access) {
        Map<String, Held> silent = new LinkedHashMap<>();
        for (Listed app : apps(panelSlug, access)) {
            if (app instanceof HeldApp held && held.held().silent()) {
                silent.putIfAbsent(held.held().peer(), held.held());
            }
        }
        List<Microcopy> sentences = new ArrayList<>();
        for (Held server : silent.values()) {
            sentences.add(server.lastSeen() == null ? HohenheimMicrocopy.APP_LIST.of("held_silent")
                .withArg("server", server.server()) : HohenheimMicrocopy.APP_LIST.of("held_band")
                .withArg("server", server.server()).withArg("since", server.lastSeen()));
        }
        return sentences.isEmpty() ? null : sentences.size() == 1 ? sentences.getFirst()
            : Microcopy.concat(joined(sentences));
    }

    /** @return the sentences with a space between each two */
    private static @NonNull List<Microcopy> joined(@NonNull List<Microcopy> sentences) {
        List<Microcopy> parts = new ArrayList<>();
        for (Microcopy sentence : sentences) {
            if (!parts.isEmpty()) {
                parts.add(Microcopy.literal(" "));
            }
            parts.add(sentence);
        }
        return parts;
    }

    /**
     * The table: state glyph, app (with its held line under the name), address, kind, host and server (operator only;
     * the server while one is linked), HTTPS, fix; the filter columns stay hidden.
     */
    private static @NonNull TableSpec<Listed> table(boolean withHost) {
        TableSpec.Builder<Listed> table = TableSpec.<Listed>builder()
            .column(ResourceHealth.column())
            .column(withHost ? ColumnSpec.fromField(NAME).sortable().subtext(HELD_NOTE.getName()).build()
                : ColumnSpec.fromField(NAME).sortable().build())
            .column(ColumnSpec.virtual(ADDRESS_COLUMN, HohenheimMicrocopy.APP_LIST.of("address"))
                .renderer(HohenheimTemplateIds.CELL_SITE_HOSTNAMES).build())
            .column(ColumnSpec.fromField(ADDRESS_TEXT).hidden().build())
            .column(ColumnSpec.fromField(KIND).sortable().build());
        if (withHost) {
            // A website no instance serves runs on no host: a dash, never the framework's "None".
            table.column(ColumnSpec.fromField(HOST).sortable().absent(HohenheimMicrocopy.APP_LIST.of("host_none"))
                .build());
            table.column(ColumnSpec.fromField(SERVER).sortable().shownWhen(AppParts::showsServers).build());
            table.column(ColumnSpec.fromField(HELD_NOTE).hidden().shownWhen(AppParts::showsServers).build());
        }
        // The main address's HTTPS in the Addresses list's words and cell, never the app's overall verdict; then the
        // framework's fix cell ("Get a certificate"), offered as the app's record's band offers it.
        table.column(ColumnSpec.virtual(HTTPS_COLUMN, HohenheimMicrocopy.APP_LIST.of("https"))
            .renderer(HohenheimTemplateIds.CELL_STATE_LINE).build())
            .column(ResourceHealth.fixColumn())
            .column(ColumnSpec.fromField(TYPE).hidden().build())
            .column(ColumnSpec.fromField(STATE).hidden().build())
            .filter(FilterSpec.leaf(TYPE, CoreTypes.EQUALS).label(HohenheimMicrocopy.APP_LIST.of("kind")).build())
            // A silent server's rows stay listed as it had them, receding.
            .rowInactive(app -> switch (app) {
                case App local -> false;
                case HeldApp held -> held.held().silent();
            });
        if (withHost) {
            table.filter(FilterSpec.leaf(HOST, CoreTypes.CONTAINS).label(HohenheimMicrocopy.APP_LIST.of("host"))
                .build());
            table.filter(FilterSpec.forValues(SERVER).label(HohenheimMicrocopy.APP_LIST.of("server_column"))
                .emptyLabel(HohenheimMicrocopy.APP_LIST.of("server_all")).shownWhen(AppParts::showsServers).build());
        }
        return table.filter(FilterSpec.leaf(STATE, CoreTypes.EQUALS).label(HohenheimMicrocopy.APP_LIST.of("state"))
            .build()).build();
    }

    /** @return whether this viewer's Apps list holds other servers' apps, so it names each app's server */
    private static boolean showsServers(@NonNull AccessContext access) {
        Panel admin = PanelRegistry.getBySlug(HohenheimSlugs.ADMIN);
        return admin != null && AppDirectory.showsLinkedServers(admin, access);
    }

    /** @return every column's value of one app, keyed by the column it fills */
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
        switch (app) {
            case App local -> values.put(SERVER.getName(), LinkedServers.ownName());
            case HeldApp held -> {
                values.put(SERVER.getName(), held.held().server());
                values.put(HELD_NOTE.getName(), held.note());
            }
        }
        return values;
    }

    /** @return the apps the named panel lists for this viewer; none for a panel this node does not register */
    private static @NonNull List<Listed> apps(@NonNull String panelSlug, @NonNull AccessContext access) {
        Panel panel = PanelRegistry.getBySlug(panelSlug);
        return panel == null ? List.of() : AppDirectory.everyServer(panel, access);
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
