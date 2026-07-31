package be.elevenways.hohenheim.server.cms;

import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.cms.common.CmsSettings;
import be.elevenways.zenit.cms.common.panel.Panel;
import be.elevenways.zenit.cms.common.panel.PanelRegistry;
import be.elevenways.zenit.cms.common.resource.RecordScopedPage;
import be.elevenways.zenit.cms.common.resource.RecordSubpageRegistry;
import be.elevenways.zenit.cms.common.resource.Resource;
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

    /**
     * True only for the record-subpage route that actually dispatches to THIS
     * page: {@code {panel}/{resource}/{id}/page/processes} where the panel is
     * registered, the resource is one of its peers, and the subpage that slug
     * resolves to really is {@link SiteProcessesPage}.
     *
     * AIDEV-NOTE: this used to be a SUFFIX test ("ends with /page/processes")
     * plus "the first segment is a registered panel", which handed the widened
     * policy to any depth of path under a panel slug -- 404s included, and any
     * future resource that grows a "processes" tab of its own. The claim now
     * walks the same registrations the request does (PanelRegistry ->
     * Panel.peerBySlug -> RecordSubpageRegistry.resolve(resource.subpages())),
     * so a path that cannot render the terminal cannot be claimed by it. It stays
     * STRUCTURAL: no record is loaded, so a claim check never queries.
     */
    private static boolean claims(String path) {
        String[] segments = path.split("/", -1);
        if (segments.length != 5
                || !"page".equals(segments[3])
                || !SiteProcessesPage.SLUG.equals(segments[4])) {
            return false;
        }

        Panel panel = segments[0].isEmpty() ? null : PanelRegistry.getBySlug(segments[0]);
        if (panel == null || segments[1].isEmpty() || segments[2].isEmpty()) {
            return false;
        }
        if (!(panel.peerBySlug(segments[1]) instanceof Resource<?> resource)) {
            return false;
        }

        // resolve() applies the shadowing rule the dispatcher applies, so the page
        // claimed here is the page that would render.
        for (RecordScopedPage<?> subpage : RecordSubpageRegistry.INSTANCE.resolve(resource.subpages())) {
            if (SiteProcessesPage.SLUG.equals(subpage.slug())) {
                return subpage instanceof SiteProcessesPage;
            }
        }
        return false;
    }
}
