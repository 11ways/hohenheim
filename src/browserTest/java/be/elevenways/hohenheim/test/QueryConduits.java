package be.elevenways.hohenheim.test;

import be.elevenways.zenit.cms.common.page.CmsEndpoints;
import be.elevenways.zenit.common.conduit.Conduit;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.common.security.AnonymousPrincipal;
import be.elevenways.zenit.common.security.PermissionChecker;
import be.elevenways.zenit.test.support.EndpointConduit;

import java.util.Map;

/**
 * A synthetic cms request: the panel it renders under, a query string and its own request-scoped attributes, so a
 * test can prove per-request state is per REQUEST.
 *
 * @author Jelle De Loecker
 * @since 0.1.0
 */
public final class QueryConduits {

    private QueryConduits() {
    }

    /** A full-page request rendered under {@code panelSlug} carrying {@code query}. */
    public static Conduit request(String panelSlug, Map<String, String> query) {
        return EndpointConduit.fullPageRequest().setQuery(query).setParameter(CmsEndpoints.PANEL_PARAM, panelSlug);
    }

    /** An anonymous access context whose request is {@code conduit}. */
    public static AccessContext accessOf(Conduit conduit) {
        return AccessContext.of(conduit, AnonymousPrincipal.INSTANCE, PermissionChecker.DENY_ALL);
    }
}
