package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.HohenheimSlugs;
import be.elevenways.hohenheim.HohenheimIds;
import be.elevenways.hohenheim.HohenheimMicrocopy;
import be.elevenways.hohenheim.HohenheimTemplateIds;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.HohenheimCapabilities;
import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.hohenheim.server.cms.AppDirectory.App;
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
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.common.ui.Icon;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * The Apps list of both panels: one row per app of the {@link AppDirectory}, each opening its record page.
 *
 * AIDEV-NOTE: a typed store over the directory, fully loaded per request and paged in memory
 * ({@link StorePages#inMemory}): the directory already holds only what the viewer may list, and an installation's apps
 * are counted in hundreds. The health column is the verdict each row's own record page leads with ({@link AppHealth},
 * read by the directory); the kind and state filters are the directory's closed {@link Source} set and the framework's
 * {@link HealthTone}, each derived from its home.
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
     * @param putOnline the entry that puts something online, null on a node that has none
     * @return the operator's Apps list
     */
    static @NonNull PanelResource<App> admin(@NonNull List<String> related, @Nullable String putOnline) {
        return onward(entry(HohenheimIds.id("app"), HohenheimSlugs.ADMIN, true), related, putOnline, null).build();
    }

    /**
     * @param related   the slugs of the /manage entries an app is made of that this node registers, linked from the
     *                  list's toolbar
     * @param putOnline the entry that puts something online, null on a node that has none
     * @return the /manage twin: the apps made of the sites and instances the caller may list there; no host column,
     *         since the delegated panel names no host
     */
    static @NonNull PanelResource<App> manage(@NonNull List<String> related, @Nullable String putOnline) {
        // A tenant granted nothing an app is made of sees no empty entry; the list stays scoped.
        return ManageTwin.listed(onward(entry(ManageTwin.id("app"), HohenheimSlugs.MANAGE, false), related, putOnline,
                    InstanceTemplateParts::offersTenantCatalog),
                access -> HohenheimAccess.managesAnySite(access)
                    || HohenheimAccess.reachesAny(access, InstanceModel.MODEL_ID, HohenheimCapabilities.VIEW))
            .build();
    }

    /**
     * Both twins' sidebar place, toolbar pages and primary verb.
     *
     * @param offered who is offered the primary verb, null for everyone the panel admits
     */
    private static PanelResource.@NonNull Builder<App> onward(PanelResource.@NonNull Builder<App> builder,
                                                              @NonNull List<String> related,
                                                              @Nullable String putOnline,
                                                              @Nullable Predicate<AccessContext> offered) {
        builder.navGroup(NavGroup.DEFAULT).navOrder(20);
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

    private static PanelResource.@NonNull Builder<App> entry(@NonNull Identifier id, @NonNull String panelSlug,
                                                             boolean withHost) {
        StorePages<App> pages = new StorePages<>() {
            @Override
            public @NonNull List<Field<?, ?>> fields() {
                return withHost ? List.of(NAME, ADDRESS_TEXT, KIND, HOST, TYPE, STATE)
                    : List.of(NAME, ADDRESS_TEXT, KIND, TYPE, STATE);
            }

            @Override
            public @NonNull List<String> searchColumns() {
                return withHost ? List.of(NAME.getName(), ADDRESS_TEXT.getName(), HOST.getName())
                    : List.of(NAME.getName(), ADDRESS_TEXT.getName());
            }

            @Override
            public @NonNull RecordPage<App> page(TableView.@NonNull Applied<App> applied,
                                                 @NonNull AccessContext access) {
                return this.inMemory(applied, apps(panelSlug, access), (app, column) -> values(app).get(column.name()),
                    access);
            }
        };
        ResourceList.Builder<App> list = ResourceList.store(table(withHost), pages)
            .chrome(ListChrome.MINIMAL)
            .emptyDescription(HohenheimMicrocopy.APP_LIST.of("empty"));
        if (withHost) {
            list.search(NAME.getName(), ADDRESS_TEXT.getName(), HOST.getName());
        } else {
            list.search(NAME.getName(), ADDRESS_TEXT.getName());
        }
        return PanelResource.builder(id, HohenheimSlugs.APPS,
                SubjectType.of(HohenheimIds.id("app"), App.class, App::key))
            .label(HohenheimMicrocopy.APP_LIST.of("plural"))
            .recordLabel(HohenheimMicrocopy.APP_LIST.of("singular"))
            // The operator reads the data model it lists; a tenant reads their apps, never "sites, instances and
            // stacks" (DEP10).
            .description(withHost ? HohenheimMicrocopy.APP.of("nav_hint")
                : HohenheimMicrocopy.MANAGE_APP.of("nav_hint"))
            .icon(Icon.of("cubes"))
            // The one count the sidebar carries (board Main): the apps with a problem, as the Apps tile says them.
            .navBadge(access -> {
                int problems = AppDirectory.withProblem(apps(panelSlug, access));
                return problems == 0 ? null : (long) problems;
            })
            .health(ResourceHealth.of((app, access) -> app.health()))
            .list(list.rowLink((app, request) -> app.target()).build())
            .reads(ResourceReads.<App>typed(App::key)
                .load((key, access) -> apps(panelSlug, access).stream()
                    .filter(app -> app.key().equals(key)).findFirst().orElse(null))
                .values(AppParts::values)
                .build()
                .title(App::name));
    }

    /** The table: state glyph, app, address, kind, host (operator only), HTTPS, fix; the filter columns stay hidden. */
    private static @NonNull TableSpec<App> table(boolean withHost) {
        TableSpec.Builder<App> table = TableSpec.<App>builder()
            .column(ResourceHealth.column())
            .column(ColumnSpec.fromField(NAME).sortable().build())
            .column(ColumnSpec.virtual(ADDRESS_COLUMN, HohenheimMicrocopy.APP_LIST.of("address"))
                .renderer(HohenheimTemplateIds.CELL_SITE_HOSTNAMES).build())
            .column(ColumnSpec.fromField(ADDRESS_TEXT).hidden().build())
            .column(ColumnSpec.fromField(KIND).sortable().build());
        if (withHost) {
            // A website no instance serves runs on no host: the board's dash, never the framework's "None".
            table.column(ColumnSpec.fromField(HOST).sortable().absent(HohenheimMicrocopy.APP_LIST.of("host_none"))
                .build());
        }
        // The main address's HTTPS in the Addresses list's words and cell, never the app's overall verdict; then the
        // framework's fix cell (board Apps-List's "Get a certificate"), offered as the app's record's band offers it.
        table.column(ColumnSpec.virtual(HTTPS_COLUMN, HohenheimMicrocopy.APP_LIST.of("https"))
            .renderer(HohenheimTemplateIds.CELL_STATE_LINE).build())
            .column(ResourceHealth.fixColumn())
            .column(ColumnSpec.fromField(TYPE).hidden().build())
            .column(ColumnSpec.fromField(STATE).hidden().build())
            .filter(FilterSpec.leaf(TYPE, CoreTypes.EQUALS).label(HohenheimMicrocopy.APP_LIST.of("kind")).build());
        if (withHost) {
            table.filter(FilterSpec.leaf(HOST, CoreTypes.CONTAINS).label(HohenheimMicrocopy.APP_LIST.of("host"))
                .build());
        }
        return table.filter(FilterSpec.leaf(STATE, CoreTypes.EQUALS).label(HohenheimMicrocopy.APP_LIST.of("state"))
            .build()).build();
    }

    /** @return every column's value of one app, keyed by the column it fills */
    private static @NonNull Map<String, Object> values(@NonNull App app) {
        Map<String, Object> values = new HashMap<>();
        values.put(NAME.getName(), app.name());
        values.put(ADDRESS_COLUMN, app.address());
        values.put(ADDRESS_TEXT.getName(), app.addressText());
        values.put(KIND.getName(), app.kind());
        values.put(HOST.getName(), app.host());
        values.put(HTTPS_COLUMN, app.https());
        values.put(TYPE.getName(), app.source().token());
        values.put(STATE.getName(), app.health().tone().token());
        return values;
    }

    /** @return the apps the named panel lists for this viewer; none for a panel this node does not register */
    private static @NonNull List<App> apps(@NonNull String panelSlug, @NonNull AccessContext access) {
        Panel panel = PanelRegistry.getBySlug(panelSlug);
        return panel == null ? List.of() : AppDirectory.read(panel, access);
    }

    /**
     * The list's primary verb: the Put something online flow.
     *
     * @param offered who is offered it, null for everyone the panel admits
     */
    private static @NonNull PanelAction<App> putOnlineAction(@NonNull String entrySlug,
                                                             @Nullable Predicate<AccessContext> offered) {
        PanelAction.LinkBuilder<App> link = PanelAction.<App>link(HohenheimIds.id("app_put_online"),
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

    /** The state filter's values: the framework's verdict tones, worded for an app. */
    private static @NonNull EnumField stateField() {
        EnumField.Builder builder = EnumField.builder("state");
        for (HealthTone tone : HealthTone.values()) {
            builder.value(tone.token(), spec -> spec.label(HohenheimMicrocopy.APP_LIST.of("state_" + tone.token())));
        }
        return builder.label(HohenheimMicrocopy.APP_LIST.of("state")).build();
    }
}
