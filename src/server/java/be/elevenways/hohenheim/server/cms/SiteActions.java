package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.HohenheimIds;
import be.elevenways.hohenheim.HohenheimSlugs;
import be.elevenways.hohenheim.model.SiteModel;
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
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.text.Slugs;
import be.elevenways.zenit.common.ui.Icon;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.List;
import java.util.Map;
import java.util.Objects;
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

    private SiteActions() {
    }

    /** The operator panel's placed operations, in the order the legacy row actions had, then the health fixes. */
    static @NonNull List<PanelAction<Row>> operator() {
        return List.of(enableAction(), disableAction(), cloneAction(), rollbackAction(), fixHttpsAction(),
            addAddressAction(), fixProtectionAction());
    }

    /**
     * The delegated panel's subset: the two switches and the health fixes; the record-creating clone and the rollback
     * stay operator acts.
     */
    static @NonNull List<PanelAction<Row>> delegated() {
        return List.of(enableAction(), disableAction(), fixHttpsAction(), addAddressAction(), fixProtectionAction());
    }

    /** To the site's addresses, where each name forced without a certificate gets one. */
    private static @NonNull PanelAction<Row> fixHttpsAction() {
        return fixLink(FIX_HTTPS, "fix_https", "lock", SiteParts.DOMAINS_TAB, AppHealth::needsCertificate);
    }

    /** To the site's addresses, for a site visitors cannot reach because it answers on no name. */
    private static @NonNull PanelAction<Row> addAddressAction() {
        return fixLink(ADD_ADDRESS, "add_address", "plus", SiteParts.DOMAINS_TAB, AppHealth::needsAddress);
    }

    /** To the site's protected paths, for a path whose protection lets everyone in. */
    private static @NonNull PanelAction<Row> fixProtectionAction() {
        return fixLink(FIX_PROTECTION, "fix_protection", "lock", ProtectedPathParts.SLUG, AppHealth::hasOpenPath);
    }

    private static @NonNull PanelAction<Row> fixLink(@NonNull Identifier id, @NonNull String key, @NonNull String icon,
                                                     @NonNull String tab, @NonNull Predicate<Row> applies) {
        return PanelAction.<Row>link(id, ActionPlacement.ROW)
            .label(Microcopy.of(key).withFilter("scope", "app_health"))
            .icon(Icon.of(icon))
            .inlineOnRecord(false)
            .inlineInRow(false)
            .shownWhen((site, access) -> applies.test(site))
            .route((site, request) -> CmsRoutes.subpage(request.panelSlug(), HohenheimSlugs.SITES,
                site.get(SiteModel.ID), tab))
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
        return PanelAction.<Row, Integer>places(SiteOperations.CLONE, ActionPlacement.ROW,
                (request, result) -> CmsActionResult.redirect(new Uri(CmsRoutes.detail(request.request().panelSlug(),
                    HohenheimSlugs.SITES, Objects.requireNonNull(result.value(),
                        "a clone answers the copy's id")).toUrl())))
            .inlineOnRecord(false)
            .inlineInRow(false)
            .confirmation(ConfirmationSpec.builder()
                .title(Microcopy.of("clone").withFilter("scope", "site"))
                .body(Microcopy.of("clone_confirm").withFilter("scope", "site"))
                .build())
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
