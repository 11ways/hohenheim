package be.elevenways.hohenheim.server.sitetype;

import be.elevenways.hohenheim.server.ServerMain;
import be.elevenways.hohenheim.server.source.GitSiteRequestHandler;
import org.checkerframework.checker.nullness.qual.Nullable;

/** Resolves live site handlers through deployment wrappers. */
public final class SiteHandlers {

    private SiteHandlers() {
    }

    /** The live git deployment wrapper of a site, or null (proxy down, not git-sourced). */
    public static @Nullable GitSiteRequestHandler git(@Nullable Integer siteId) {
        var proxy = ServerMain.getProxyServer();
        if (proxy != null && siteId != null
            && proxy.getDispatcher().findHandlerBySiteId(siteId)
                instanceof GitSiteRequestHandler git) {
            return git;
        }
        return null;
    }
}
