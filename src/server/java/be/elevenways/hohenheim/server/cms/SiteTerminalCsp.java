package be.elevenways.hohenheim.server.cms;

import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.cms.common.CmsSettings;
import be.elevenways.zenit.cms.common.panel.PanelRegistry;
import be.elevenways.zenit.common.security.ContentSecurityPolicies;
import be.elevenways.zenit.server.http.Middleware;
import be.elevenways.zenit.server.http.ScopedCspMiddleware;

/**
 * The pl-terminal page's scoped-CSP VARIANT: the processes subpage boots
 * ghostty (wasm compiled from a data: URI), which needs two concessions the
 * rest of the admin panel must not have.
 *
 * AIDEV-NOTE: this is an OVERRIDE, not an independent claim. The processes page
 * lives at {@code /{panel}/{resource}/{id}/page/processes}, INSIDE the subtree
 * zenit-cms' panel wiring already claims, so it deliberately re-stamps a
 * widened policy on that one path at
 * {@link ScopedCspMiddleware#VARIANT_WEIGHT} -- which is also why it must go
 * through installVariant and not install (equal weights would leave the winner
 * to registration order, and the overlap diagnostic would fire on every
 * terminal request).
 *
 * A CUSTOMIZED cms.csp is never widened: the variant answers the terminal
 * policy only while cms.csp is still the shared STRICT_ADMIN default, and
 * otherwise re-stamps the admin's own value unchanged. Silently adding
 * 'wasm-unsafe-eval' to a hand-written policy would defeat the point of
 * writing one; the operator who tightens cms.csp owns the terminal breaking.
 *
 * @author Jelle De Loecker
 */
public final class SiteTerminalCsp {

    public static final Identifier ID = Identifier.of("hohenheim", "terminal_csp");

    private static volatile Middleware instance;

    private SiteTerminalCsp() {}

    public static synchronized Middleware install() {
        if (instance != null) {
            return instance;
        }

        instance = ScopedCspMiddleware.installVariant(ID,
            SiteTerminalCsp::policy,
            SiteTerminalCsp::claims);
        return instance;
    }

    private static String policy() {
        String configured = CmsSettings.VALUES.getValue(CmsSettings.CSP);
        if (configured == null || configured.isBlank()) {
            return "";
        }
        return ContentSecurityPolicies.STRICT_ADMIN.equals(configured)
            ? ContentSecurityPolicies.STRICT_ADMIN_TERMINAL
            : configured;
    }

    /** The record-subpage route of the processes tab, under any registered panel. */
    private static boolean claims(String path) {
        if (!path.endsWith("/page/" + SiteProcessesPage.SLUG)) {
            return false;
        }
        String slug = ScopedCspMiddleware.firstSegment(path);
        return !slug.isEmpty() && PanelRegistry.getBySlug(slug) != null;
    }
}
