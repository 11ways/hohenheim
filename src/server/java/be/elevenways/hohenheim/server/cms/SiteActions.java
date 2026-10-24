package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.HohenheimIds;
import be.elevenways.hohenheim.HohenheimMicrocopy;
import be.elevenways.hohenheim.HohenheimSlugs;
import be.elevenways.hohenheim.model.ProtectedPathModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.site.ProtectPath;
import be.elevenways.hohenheim.site.SiteOperations;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.key.IdentifierKey;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.protoblast.common.text.SlugText;
import be.elevenways.zenit.cms.common.action.ActionPlacement;
import be.elevenways.zenit.cms.common.action.ActionStyle;
import be.elevenways.zenit.cms.common.action.CmsActionResult;
import be.elevenways.zenit.cms.common.action.ConfirmationSpec;
import be.elevenways.zenit.cms.common.action.PanelAction;
import be.elevenways.zenit.cms.common.page.CmsRoutes;
import be.elevenways.zenit.cms.common.panel.PanelRequest;
import be.elevenways.zenit.common.conduit.Conduit;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.field.IntegerField;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.routing.RouteTarget;
import be.elevenways.zenit.common.routing.UrlTarget;
import be.elevenways.zenit.common.ui.Icon;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * THE site verbs, built once for both panels: the operator list and the delegated subset read the same builders, so
 * an action's presentation exists exactly once ({@link SiteOperations}, handlers in {@link SiteOperationHandlers}).
 *
 * AIDEV-NOTE: enable and disable ride the OVERFLOW: rows keep exactly Edit and Delete inline, and the Enabled column
 * already answers the question an inline button would. Both are CONFIRMED, and the dialog NAMES the hostnames that
 * change state, the whole consequence of the click (measured 2026-08-27: one unconfirmed click on the site proxying
 * the panel was four minutes of "404 - No site configured" on a live host); the disable of that very site is
 * offered-but-dead with the reason on screen, the operation's availability.
 *
 * @author Jelle De Loecker
 * @since  0.9.0
 */
final class SiteActions {

    /** The health band's fixes ({@link AppHealth}): each is offered exactly where its verdict says it applies. */
    static final Identifier FIX_HTTPS = HohenheimIds.id("site_fix_https");
    static final Identifier ADD_ADDRESS = HohenheimIds.id("site_add_address");
    static final Identifier FIX_PROTECTION = HohenheimIds.id("site_fix_protection");
    static final Identifier CHANGE_CONFIGURATION = HohenheimIds.id("site_change_configuration");
    static final Identifier OPEN_SITE = HohenheimIds.id("site_open");

    private SiteActions() {
    }

    /**
     * The operator panel's placed operations, in the order the legacy row actions had, then the health fixes; only the
     * operator's form changes what a site serves, so only this family carries {@link #CHANGE_CONFIGURATION}.
     */
    static @NonNull List<PanelAction<Row>> operator() {
        return List.of(openSiteAction(OPEN_SITE, AppHealth::openSites, SiteModel.ID), enableAction(),
            disableAction(), cloneAction(),
            rollbackAction(), protectPathAction(), fixHttpsAction(), stopForcingHttpsAction(), addAddressAction(),
            fixProtectionAction(), changeConfigurationAction());
    }

    /**
     * The delegated panel's subset: the two switches and the health fixes; the record-creating clone and the rollback
     * stay operator acts.
     */
    static @NonNull List<PanelAction<Row>> delegated() {
        return List.of(openSiteAction(OPEN_SITE, AppHealth::openSites, SiteModel.ID), enableAction(),
            disableAction(), protectPathAction(), fixHttpsAction(), stopForcingHttpsAction(), addAddressAction(),
            fixProtectionAction());
    }

    /**
     * The app's own address in a new tab, the record heading's first action (Open site), offered only
     * while visitors reach it: the instance's twin passes {@link AppHealth#openInstances}.
     *
     * AIDEV-NOTE: hidden while the app is not serving (broken, unable to start or
     * stopped): a link to the error page visitors get offers nothing, and the health band
     * beside it carries the fix. It reads the verdict's own serving half (AppHealth), never a second check of what
     * "serving" means, for the operator and the tenant alike.
     *
     * AIDEV-NOTE: the addresses of a rendered page are read ONCE, by the shown condition, and kept on the request
     * (Conduit.memo, per action) for the link targets of the rows it shows; a route asked without that read (no
     * request, or a row it never judged) reads its one row afresh.
     *
     * @param open the addresses of the records Open site is offered on, by id, for a page of records
     * @param key  the records' id field
     */
    static @NonNull PanelAction<Row> openSiteAction(@NonNull Identifier id,
                                                    @NonNull Function<List<Row>, Map<Integer, String>> open,
                                                    @NonNull IntegerField key) {
        IdentifierKey<Map<Integer, String>> read = IdentifierKey.of("hohenheim", "open_urls_" + id.getPath());
        return PanelAction.<Row>link(id, ActionPlacement.ROW)
            .label(HohenheimMicrocopy.APP_OVERVIEW.of("open_site"))
            .icon(Icon.of("up-right-from-square"))
            .inlineInRow(false)
            .openInNewTab()
            .shownWhenAmong((rows, access) -> {
                Map<Integer, String> urls = open.apply(rows);
                Conduit conduit = access.conduit();
                if (conduit != null) {
                    conduit.memo(read, HashMap::new).putAll(urls);
                }
                return row -> urls.containsKey(row.get(key));
            })
            .route((row, request) -> {
                Integer rowId = row.get(key);
                String url = request.conduit().memo(read, HashMap::new).get(rowId);
                return new UrlTarget(Objects.requireNonNull(url != null ? url : open.apply(List.of(row)).get(rowId),
                    "Open site is shown only while the app has an address"));
            })
            .build();
    }

    /** To the site's addresses, where each name forced without a certificate gets one. */
    private static @NonNull PanelAction<Row> fixHttpsAction() {
        return fixLink(FIX_HTTPS, "fix_https", "lock", SiteParts.DOMAINS_TAB, AppHealth::needingCertificate);
    }

    /**
     * The other way out of the error page: visitors reach the names over plain HTTP until a certificate works. A health
     * fix and a row-menu chore, never a heading button; it confirms first, since it gives up encryption.
     */
    private static @NonNull PanelAction<Row> stopForcingHttpsAction() {
        return PanelAction.<Row, Void>places(SiteOperations.STOP_FORCING_HTTPS, ActionPlacement.ROW,
                (request, result) -> CmsActionResult.refreshWithToast(
                    HohenheimMicrocopy.SITE.of("stop_forcing_https_toast")))
            .inlineOnRecord(false)
            .inlineInRow(false)
            .confirmation(ConfirmationSpec.verb(HohenheimMicrocopy.SITE.of("stop_forcing_https"),
                HohenheimMicrocopy.SITE.of("stop_forcing_https_confirm"), ActionStyle.PRIMARY))
            .build();
    }

    /** To the site's addresses, for a site visitors cannot reach because it answers on no name. */
    private static @NonNull PanelAction<Row> addAddressAction() {
        return fixLink(ADD_ADDRESS, "add_address", "plus", SiteParts.DOMAINS_TAB, AppHealth::needingAddress);
    }

    /**
     * Protect a path in place: the operation's form in a sheet on the site, which lands back on the site's overview,
     * where the Protection card now lists the path in words.
     *
     * AIDEV-NOTE: a record action, not the Protection card's link: a placed operation opens its sheet from its own
     * button, and the card keeps linking to the site's protected paths, where the written path is listed.
     */
    private static @NonNull PanelAction<Row> protectPathAction() {
        return CmsSupport.opensWhatItMade(ProtectPath.OPERATION,
                (panel, pathId) -> SiteParts.recordRoute(panel, siteOfPath(pathId)).toUrl(),
                HohenheimMicrocopy.PROTECT_PATH.of("action"),
                HohenheimMicrocopy.PROTECT_PATH.of("description"))
            .inlineOnRecord(true)
            .inSheet()
            .build();
    }

    /** The site a protected path guards. */
    private static @NonNull Object siteOfPath(@NonNull Integer pathId) {
        Row path = Objects.requireNonNull(Models.get(ProtectedPathModel.class).findById(pathId),
            "the protected path was just written");
        return Objects.requireNonNull(path.get(ProtectedPathModel.SITE_ID), "a protected path belongs to a site");
    }

    /**
     * To the site's Configuration (its record form), for a site the proxy turns every visitor away from because of its
     * own settings: it names no app to serve, an upstream the proxy refuses, or a sign-in provider that cannot work.
     */
    private static @NonNull PanelAction<Row> changeConfigurationAction() {
        return fixLink(CHANGE_CONFIGURATION, "change_configuration", "sliders",
            (sites, access) -> AppHealth::refusedBySettings,
            (site, request) -> CmsRoutes.detail(request.panelSlug(), HohenheimSlugs.SITES, site.get(SiteModel.ID)));
    }

    /** To the site's protected paths, for a path whose protection lets everyone in. */
    private static @NonNull PanelAction<Row> fixProtectionAction() {
        return fixLink(FIX_PROTECTION, "fix_protection", "lock", HohenheimSlugs.PROTECTED_PATHS,
            AppHealth::withOpenPath);
    }

    /**
     * A health fix that leads to one of the site's tabs, drawn for the sites {@code among} names: a page of sites asks
     * it once, never once per row.
     */
    private static @NonNull PanelAction<Row> fixLink(@NonNull Identifier id, @NonNull String key, @NonNull String icon,
                                                     @NonNull String tab,
                                                     @NonNull Function<List<Row>, Set<Integer>> among) {
        return fixLink(id, key, icon, (sites, access) -> {
            Set<Integer> applies = among.apply(sites);
            return site -> applies.contains(site.get(SiteModel.ID));
        }, (site, request) -> CmsRoutes.subpage(request.panelSlug(), HohenheimSlugs.SITES, site.get(SiteModel.ID),
            tab));
    }

    /** A health fix that leads somewhere on the site, offered only where its verdict applies. */
    private static @NonNull PanelAction<Row> fixLink(@NonNull Identifier id, @NonNull String key, @NonNull String icon,
                                                     PanelAction.@NonNull PageShown<Row> applies,
                                                     @NonNull BiFunction<Row, PanelRequest, RouteTarget> route) {
        return PanelAction.<Row>link(id, ActionPlacement.ROW)
            .label(HohenheimMicrocopy.APP_HEALTH.of(key))
            .icon(Icon.of(icon))
            .inlineOnRecord(false)
            .inlineInRow(false)
            .shownWhenAmong(applies)
            .route(route)
            .build();
    }

    private static @NonNull PanelAction<Row> enableAction() {
        ConfirmationSpec enable = ConfirmationSpec.verb(HohenheimMicrocopy.SITE.of("enable"),
            HohenheimMicrocopy.SITE.of("toggle_confirm"), ActionStyle.DEFAULT);
        return PanelAction.<Row, Void>places(SiteOperations.ENABLE, ActionPlacement.ROW,
                (request, result) -> CmsActionResult.refreshWithToast(
                    HohenheimMicrocopy.SITE.of("enabled_toast")))
            .inlineInRow(false)
            // The record-less fallback: a surface drawn without a row still confirms.
            .confirmation(enable)
            .dynamicConfirmation(site -> enable.withBody(toggleBody(false, site)))
            .build();
    }

    private static @NonNull PanelAction<Row> disableAction() {
        ConfirmationSpec disable = ConfirmationSpec.verb(HohenheimMicrocopy.SITE.of("disable"),
            HohenheimMicrocopy.SITE.of("toggle_confirm"), ActionStyle.DESTRUCTIVE);
        return PanelAction.<Row, Void>places(SiteOperations.DISABLE, ActionPlacement.ROW,
                (request, result) -> CmsActionResult.refreshWithToast(
                    HohenheimMicrocopy.SITE.of("disabled_toast")))
            .inlineInRow(false)
            .confirmation(disable)
            .dynamicConfirmation(site -> disable.withBody(toggleBody(true, site)))
            .build();
    }

    /** The switch dialog's body over one site, in the direction the click goes ({@link SiteParts#siteBody}). */
    private static @NonNull Microcopy toggleBody(boolean disabling, @NonNull Row site) {
        return disabling
            ? SiteParts.siteBody(site, "disable_confirm_no_hostnames", "disable_confirm", "disable_confirm_exposing",
                "disable_confirm_ports")
            : SiteParts.siteBody(site, "enable_confirm_no_hostnames", "enable_confirm", null, "enable_confirm_ports");
    }

    /** The record-creating clone; the form opens on {@link #freeCloneName}, and the copy's page is where it lands. */
    private static @NonNull PanelAction<Row> cloneAction() {
        return CmsSupport.opensWhatItMade(SiteOperations.CLONE, (panel, id) -> SiteParts.recordRoute(panel, id).toUrl(),
                HohenheimMicrocopy.SITE.of("clone"),
                HohenheimMicrocopy.SITE.of("clone_confirm"))
            .inputValues((site, request) -> Map.of(SiteOperations.CLONE_NAME.getName(), freeCloneName(site)))
            .build();
    }

    /**
     * The name the clone form opens on: the site's own name followed by the first number from 2 whose slug no site
     * holds, trashed ones included, so the ordinary path never meets the taken-name refusal.
     *
     * AIDEV-NOTE: a NUMBER on purpose, never an English "(copy)": the value is saved as the site's NAME, and a number
     * reads the same in every language, while the opening values are computed from the row alone with no reader
     * locale to word a suffix in. The suggestion is only a convenience; the clone's insert decides a taken name.
     */
    private static @NonNull String freeCloneName(@NonNull Row site) {
        String name = String.valueOf((Object) site.get(SiteModel.NAME));
        for (int number = 2; ; number++) {
            String candidate = name + " " + number;
            if (Models.get(SiteModel.class).find().withTrashed()
                    .where(SiteModel.SLUG.eq(SlugText.slugify(candidate))).first() == null) {
                return candidate;
            }
        }
    }

    /**
     * Roll a Docker site back to its retained release: one durable operation over the digest-pinned prior spec,
     * through the same health gate as a forward release. A background run: it lands on the site's front door, whose
     * band streams the run while it goes, and its finished run opens the site; a site whose application keeps nothing
     * to go back to is dead, with the engine's words.
     */
    private static @NonNull PanelAction<Row> rollbackAction() {
        return PanelAction.<Row, Void>placesRun(SiteOperations.ROLLBACK_RELEASE, ActionPlacement.ROW)
            .startedRunOpens(Row.class, site -> null)
            .inlineInRow(false)
            .description(HohenheimMicrocopy.SITE.of("rollback_hint"))
            .confirmation(ConfirmationSpec.verb(HohenheimMicrocopy.SITE.of("rollback"),
                HohenheimMicrocopy.SITE.of("rollback_confirm"), ActionStyle.DESTRUCTIVE))
            .build();
    }
}
