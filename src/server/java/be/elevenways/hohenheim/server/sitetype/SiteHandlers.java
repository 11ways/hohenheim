package be.elevenways.hohenheim.server.sitetype;

import be.elevenways.hohenheim.server.ServerMain;
import be.elevenways.hohenheim.server.process.ManagedProcessSiteHandler;
import be.elevenways.hohenheim.server.source.GitSiteRequestHandler;
import org.checkerframework.checker.nullness.qual.Nullable;

/** Resolves live site handlers through deployment wrappers. */
public final class SiteHandlers {

    private SiteHandlers() {
    }

    public static @Nullable ManagedProcessSiteHandler managedProcess(Integer siteId) {
        var proxy = ServerMain.getProxyServer();
        if (proxy == null || siteId == null) {
            return null;
        }
        SiteRequestHandler handler = proxy.getDispatcher().findHandlerBySiteId(siteId);
        if (handler instanceof GitSiteRequestHandler git) {
            handler = git.innerHandler();
        }
        return handler instanceof ManagedProcessSiteHandler managed ? managed : null;
    }
}
