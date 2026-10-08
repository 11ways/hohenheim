package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.HohenheimIds;
import be.elevenways.hohenheim.HohenheimSlugs;
import be.elevenways.hohenheim.model.ProtectedPathModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.site.ProtectPath;
import be.elevenways.hohenheim.site.SiteOperations;
import be.elevenways.protoblast.common.http.Uri;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.cms.common.action.ActionPlacement;
import be.elevenways.zenit.cms.common.action.ActionStyle;
import be.elevenways.zenit.cms.common.action.CmsActionResult;
import be.elevenways.zenit.cms.common.action.ConfirmationSpec;
import be.elevenways.zenit.cms.common.action.PanelAction;
import be.elevenways.zenit.cms.common.page.CmsRoutes;
import be.elevenways.zenit.cms.common.panel.PanelRequest;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.routing.RouteTarget;
import be.elevenways.zenit.common.routing.UrlTarget;
import be.elevenways.zenit.common.text.Slugs;
import be.elevenways.zenit.common.ui.Icon;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Predicate;

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
        return List.of(openSiteAction(OPEN_SITE, AppHealth::openUrl, AppHealth::siteServes), enableAction(),
            disableAction(), cloneAction(),
            rollbackAction(), protectPathAction(), fixHttpsAction(), stopForcingHttpsAction(), addAddressAction(),
            fixProtectionAction(), changeConfigurationAction());
    }

    /**
     * The delegated panel's subset: the two switches and the health fixes; the record-creating clone and the rollback
     * stay operator acts.
     */
    static @NonNull List<PanelAction<Row>> delegated() {
        return List.of(openSiteAction(OPEN_SITE, AppHealth::openUrl, AppHealth::siteServes), enableAction(),
            disableAction(), protectPathAction(), fixHttpsAction(), stopForcingHttpsAction(), addAddressAction(),
            fixProtectionAction());
    }

    /**
     * The app's own address in a new tab, the record heading's first action (the board's Open site), offered only
     * while visitors reach it: the instance's twin passes {@link AppHealth#openUrlOfInstance}.
     *
     * AIDEV-NOTE: hidden while the app is not serving (decided by Jelle, 2026-10-06 for BROKEN, widened by D7f to an
     * app that cannot start or is stopped): a link to the error page visitors get offers nothing, and the health band
     * beside it carries the fix. It reads the verdict's own serving half (AppHealth), never a second check of what
     * "serving" means, for the operator and the tenant alike.
     *
     * @param serves the serving half of the verdict of the resource this action is placed on
     */
    static @NonNull PanelAction<Row> openSiteAction(@NonNull Identifier id,
                                                    @NonNull Function<Row, @Nullable String> url,
                                                    @NonNull Predicate<Row> serves) {
        return PanelAction.<Row>link(id, ActionPlacement.ROW)
            .label(Microcopy.of("open_site").withFilter("scope", "app_overview"))
            .icon(Icon.of("up-right-from-square"))
            .inlineInRow(false)
            .openInNewTab()
            .shownWhen((row, access) -> url.apply(row) != null && serves.test(row))
            .route((row, request) -> new UrlTarget(Objects.requireNonNull(url.apply(row),
                "Open site is shown only while the app has an address")))
            .build();
    }

    /** To the site's addresses, where each name forced without a certificate gets one. */
    private static @NonNull PanelAction<Row> fixHttpsAction() {
        return fixLink(FIX_HTTPS, "fix_https", "lock", SiteParts.DOMAINS_TAB, AppHealth::needsCertificate);
    }

    /**
     * The other way out of the error page: visitors reach the names over plain HTTP until a certificate works. A health
     * fix and a row-menu chore, never a heading button; it confirms first, since it gives up encryption.
     */
    private static @NonNull PanelAction<Row> stopForcingHttpsAction() {
        return PanelAction.<Row, Void>places(SiteOperations.STOP_FORCING_HTTPS, ActionPlacement.ROW,
                (request, result) -> CmsActionResult.refreshWithToast(
                    Microcopy.of("stop_forcing_https_toast").withFilter("scope", "site")))
            .inlineOnRecord(false)
            .inlineInRow(false)
            .confirmation(ConfirmationSpec.generic(
                Microcopy.of("stop_forcing_https_confirm").withFilter("scope", "site"), false))
            .build();
    }

    /** To the site's addresses, for a site visitors cannot reach because it answers on no name. */
    private static @NonNull PanelAction<Row> addAddressAction() {
        return fixLink(ADD_ADDRESS, "add_address", "plus", SiteParts.DOMAINS_TAB, AppHealth::needsAddress);
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
                Microcopy.of("action").withFilter("scope", "protect_path"),
                Microcopy.of("description").withFilter("scope", "protect_path"), null)
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
        return fixLink(CHANGE_CONFIGURATION, "change_configuration", "sliders", AppHealth::refusedBySettings,
            (site, request) -> CmsRoutes.detail(request.panelSlug(), HohenheimSlugs.SITES, site.get(SiteModel.ID)));
    }

    /** To the site's protected paths, for a path whose protection lets everyone in. */
    private static @NonNull PanelAction<Row> fixProtectionAction() {
        return fixLink(FIX_PROTECTION, "fix_protection", "lock", ProtectedPathParts.SLUG, AppHealth::hasOpenPath);
    }

    private static @NonNull PanelAction<Row> fixLink(@NonNull Identifier id, @NonNull String key, @NonNull String icon,
                                                     @NonNull String tab, @NonNull Predicate<Row> applies) {
        return fixLink(id, key, icon, applies, (site, request) -> CmsRoutes.subpage(request.panelSlug(),
            HohenheimSlugs.SITES, site.get(SiteModel.ID), tab));
    }

    /** A health fix that leads somewhere on the site, offered only where its verdict applies. */
    private static @NonNull PanelAction<Row> fixLink(@NonNull Identifier id, @NonNull String key, @NonNull String icon,
                                                     @NonNull Predicate<Row> applies,
                                                     @NonNull BiFunction<Row, PanelRequest, RouteTarget> route) {
        return PanelAction.<Row>link(id, ActionPlacement.ROW)
            .label(Microcopy.of(key).withFilter("scope", "app_health"))
            .icon(Icon.of(icon))
            .inlineOnRecord(false)
            .inlineInRow(false)
            .shownWhen((site, access) -> applies.test(site))
            .route(route)
            .build();
    }

    private static @NonNull PanelAction<Row> enableAction() {
        return PanelAction.<Row, Void>places(SiteOperations.ENABLE, ActionPlacement.ROW,
                (request, result) -> CmsActionResult.refreshWithToast(
                    Microcopy.of("enabled_toast").withFilter("scope", "site")))
            .inlineInRow(false)
            // The record-less fallback: a surface drawn without a row still confirms.
            .confirmation(toggleConfirmation(false, null))
            .dynamicConfirmation(site -> toggleConfirmationFor(false, site))
            .build();
    }

    private static @NonNull PanelAction<Row> disableAction() {
        return PanelAction.<Row, Void>places(SiteOperations.DISABLE, ActionPlacement.ROW,
                (request, result) -> CmsActionResult.refreshWithToast(
                    Microcopy.of("disabled_toast").withFilter("scope", "site")))
            .inlineInRow(false)
            .confirmation(toggleConfirmation(true, null))
            .dynamicConfirmation(site -> toggleConfirmationFor(true, site))
            .build();
    }

    /**
     * The switch dialog over one site: the direction's verb, and a body naming the site and its hostnames.
     *
     * AIDEV-NOTE: the four bodies are a deliberate 2x2 (direction x hostnames yes/no): microcopy args echo verbatim,
     * so an empty hostname list would render a dangling colon.
     */
    static @NonNull ConfirmationSpec toggleConfirmationFor(boolean disabling, @NonNull Row site) {
        String hostnames = DeleteImpact.join(DeleteImpact.hostnamesOfSite(site.get(SiteModel.ID)));
        Microcopy body = Microcopy.of((disabling ? "disable_confirm" : "enable_confirm")
                + (hostnames.isEmpty() ? "_no_hostnames" : ""))
            .withFilter("scope", "site")
            .withArg("name", String.valueOf((Object) site.get(SiteModel.NAME)));
        if (!hostnames.isEmpty()) {
            body = body.withArg("hostnames", hostnames);
        }
        return toggleConfirmation(disabling, body);
    }

    /** @param body the per-site body, null for the record-less fallback */
    private static @NonNull ConfirmationSpec toggleConfirmation(boolean disabling, @Nullable Microcopy body) {
        Microcopy verb = Microcopy.of(disabling ? "disable" : "enable").withFilter("scope", "site");
        return ConfirmationSpec.builder()
            .title(verb)
            .body(body != null ? body : Microcopy.of("toggle_confirm").withFilter("scope", "site"))
            .confirmLabel(verb)
            .style(disabling ? ActionStyle.DESTRUCTIVE : ActionStyle.DEFAULT)
            .build();
    }

    /** The record-creating clone; the form opens on {@link #freeCloneName}, and the copy's page is where it lands. */
    private static @NonNull PanelAction<Row> cloneAction() {
        return CmsSupport.opensWhatItMade(SiteOperations.CLONE, (panel, id) -> SiteParts.recordRoute(panel, id).toUrl(),
                Microcopy.of("clone").withFilter("scope", "site"),
                Microcopy.of("clone_confirm").withFilter("scope", "site"), null)
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
                    .where(SiteModel.SLUG.eq(Slugs.slugify(candidate))).first() == null) {
                return candidate;
            }
        }
    }

    /**
     * Roll a Docker site back to its retained release: one durable operation over the digest-pinned prior spec,
     * through the same health gate as a forward release; the engine refuses with a toast when no target exists.
     */
    private static @NonNull PanelAction<Row> rollbackAction() {
        return PanelAction.<Row, Void>places(SiteOperations.ROLLBACK_RELEASE, ActionPlacement.ROW,
                (request, result) -> CmsActionResult.refreshWithToast(
                    Microcopy.of("rollback_done").withFilter("scope", "site")))
            .inlineInRow(false)
            .description(Microcopy.of("rollback_hint").withFilter("scope", "site"))
            .confirmation(ConfirmationSpec.builder()
                .title(Microcopy.of("rollback").withFilter("scope", "site"))
                .body(Microcopy.of("rollback_confirm").withFilter("scope", "site"))
                .style(ActionStyle.DESTRUCTIVE)
                .build())
            .build();
    }
}
