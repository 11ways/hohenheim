package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.HohenheimIds;
import be.elevenways.hohenheim.HohenheimTemplateIds;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.hohenheim.server.cms.AppDirectory.App;
import be.elevenways.hohenheim.server.cms.AppDirectory.Source;
import be.elevenways.protoblast.common.i18n.Microcopy;
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

    /** The entry's slug on both panels. */
    public static final String SLUG = "apps";

    private static final StringField NAME = StringField.builder("name").label(copy("name")).build();
    private static final StringField ADDRESS_TEXT = StringField.builder("address_text").label(copy("address")).build();
    private static final StringField KIND = StringField.builder("kind").label(copy("kind")).build();
    private static final StringField HOST = StringField.builder("host").label(copy("host")).build();
    private static final EnumField TYPE = typeField();
    private static final EnumField STATE = stateField();

    private AppParts() {
    }

    /**
     * @param related the slugs of the admin entries an app is made of (sites, instances, stacks, projects) that this
     *                node registers, linked from the list's toolbar
     * @param putOnline the entry that puts something online, null on a node that has none
     * @return the operator's Apps list
     */
    static @NonNull PanelResource<App> admin(@NonNull List<String> related, @Nullable String putOnline) {
        return onward(entry("app", HohenheimPanel.SLUG, true), related, putOnline, null).build();
    }

    /**
     * @param related   the slugs of the /manage entries an app is made of that this node registers, linked from the
     *                  list's toolbar
     * @param putOnline the entry that puts something online, null on a node that has none
     * @return the /manage twin: the apps made of the sites and instances the caller may list there; no host column,
     *         since the delegated panel names no host
     */
    static @NonNull PanelResource<App> manage(@NonNull List<String> related, @Nullable String putOnline) {
        return onward(entry("manage_app", ManagePanel.SLUG, false), related, putOnline,
                InstanceTemplateParts::offersTenantCatalog)
            // NAV-ONLY: a tenant granted nothing an app is made of sees no empty entry; the list stays scoped.
            .hasInScopeRecords(access -> HohenheimAccess.managesAnySite(access)
                || HohenheimAccess.reachesAny(access, InstanceModel.MODEL_ID, HohenheimAccess.VIEW))
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

    private static PanelResource.@NonNull Builder<App> entry(@NonNull String id, @NonNull String panelSlug,
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
                return this.inMemory(applied, apps(panelSlug, access), AppParts::cell, access);
            }
        };
        ResourceList.Builder<App> list = ResourceList.store(table(withHost), pages)
            .chrome(ListChrome.MINIMAL)
            .emptyDescription(copy("empty"));
        if (withHost) {
            list.search(NAME.getName(), ADDRESS_TEXT.getName(), HOST.getName());
        } else {
            list.search(NAME.getName(), ADDRESS_TEXT.getName());
        }
        return PanelResource.builder(HohenheimIds.id(id), SLUG,
                SubjectType.of(HohenheimIds.id("app"), App.class, App::key))
            .label(copy("plural"))
            .recordLabel(copy("singular"))
            .description(Microcopy.of("nav_hint").withFilter("scope", "app"))
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
                .values(app -> Map.of(NAME.getName(), app.name()))
                .cells(AppParts::cell)
                .build()
                .title(App::name));
    }

    /** The table: state glyph, app, address, kind, host (operator only), HTTPS, fix; the filter columns stay hidden. */
    private static @NonNull TableSpec<App> table(boolean withHost) {
        TableSpec.Builder<App> table = TableSpec.<App>builder()
            .column(ResourceHealth.column())
            .column(ColumnSpec.fromField(NAME).sortable().build())
            .column(ColumnSpec.virtual("address", copy("address"))
                .renderer(HohenheimTemplateIds.CELL_SITE_HOSTNAMES).build())
            .column(ColumnSpec.fromField(ADDRESS_TEXT).hidden().build())
            .column(ColumnSpec.fromField(KIND).sortable().build());
        if (withHost) {
            // A website no instance serves runs on no host: the board's dash, never the framework's "None".
            table.column(ColumnSpec.fromField(HOST).sortable().absent(copy("host_none")).build());
        }
        // The main address's HTTPS in the Addresses list's words and cell, never the app's overall verdict; then the
        // fix its verdict offers (board Apps-List's "Get a certificate"), drawn as its own record's band offers it.
        table.column(ColumnSpec.virtual("https", copy("https")).renderer(HohenheimTemplateIds.CELL_DOMAIN_CERTIFICATE)
                .build())
            .column(ColumnSpec.virtual("fix", copy("fix")).renderer(HohenheimTemplateIds.CELL_APP_FIX).labelHidden()
                .alignment(ColumnSpec.Alignment.RIGHT).build())
            .column(ColumnSpec.fromField(TYPE).hidden().build())
            .column(ColumnSpec.fromField(STATE).hidden().build())
            .filter(FilterSpec.leaf(TYPE, CoreTypes.EQUALS).label(copy("kind")).build());
        if (withHost) {
            table.filter(FilterSpec.leaf(HOST, CoreTypes.CONTAINS).label(copy("host")).build());
        }
        return table.filter(FilterSpec.leaf(STATE, CoreTypes.EQUALS).label(copy("state")).build()).build();
    }

    private static @Nullable Object cell(@NonNull App app, @NonNull ColumnSpec column) {
        return switch (column.name()) {
            case "name" -> app.name();
            case "address" -> app.address();
            case "address_text" -> app.addressText();
            case "kind" -> app.kind();
            case "host" -> app.host();
            case "https" -> app.https();
            case "fix" -> app.fix();
            case "type" -> app.source().token();
            case "state" -> app.health().tone().token();
            default -> null;
        };
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
            .label(copy("put_online"))
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
            builder.value(source.token(), spec -> spec.label(copy("type_" + source.token())));
        }
        return builder.label(copy("kind")).build();
    }

    /** The state filter's values: the framework's verdict tones, worded for an app. */
    private static @NonNull EnumField stateField() {
        EnumField.Builder builder = EnumField.builder("state");
        for (HealthTone tone : HealthTone.values()) {
            builder.value(tone.token(), spec -> spec.label(copy("state_" + tone.token())));
        }
        return builder.label(copy("state")).build();
    }

    private static @NonNull Microcopy copy(@NonNull String key) {
        return Microcopy.of(key).withFilter("scope", "app_list");
    }
}
