package be.elevenways.hohenheim.server;

import be.elevenways.hohenheim.HohenheimEndpoints;
import be.elevenways.hohenheim.HohenheimPaths;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.server.api.PaasApi;
import be.elevenways.hohenheim.server.cms.HohenheimPanel;
import be.elevenways.hohenheim.server.files.InstanceFileEndpoints;
import be.elevenways.hohenheim.server.instance.InstanceApi;
import be.elevenways.hohenheim.server.instance.InstanceStatsHandler;
import be.elevenways.hohenheim.server.instance.InstanceTemplateHandlers;
import be.elevenways.hohenheim.server.sitetype.SiteRequestHandler;
import be.elevenways.hohenheim.server.source.GitSiteRequestHandler;
import be.elevenways.protoblast.common.util.BlastString;
import be.elevenways.zenit.auth.model.ApiKeyPrincipal;
import be.elevenways.zenit.common.conduit.ConduitAttributes;
import be.elevenways.zenit.common.orm.activity.ActivityLog;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.forms.server.path.FilesystemBrowserRegistry;
import be.elevenways.zenit.forms.server.path.FilesystemBrowserSource;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Installs the host-declared endpoints beside the zenit-cms panel: the health check,
 * the automation API, and every domain handler class in this package.
 */
public final class HohenheimHandlers {

    private HohenheimHandlers() {
    }

    public static void init() {
        FilesystemBrowserRegistry.INSTANCE.register(FilesystemBrowserSource.of(
            HohenheimPaths.SERVER_FILES, HohenheimPanel.ACCESS, Path.of("/")));
        initHealth();
        InstanceTemplateHandlers.init();
        CertificateHandlers.init();
        AccessRuleHandlers.init();
        DnsZoneHandlers.initZones();
        DnsRecordApiHandlers.init();
        DnsZoneHandlers.initRemoteRecords();
        DynamicDnsHandlers.init();
        DatabaseHandlers.init();
        ServerMediaHandlers.init();
        SiteControlHandlers.initProcessControl();
        SiteControlHandlers.initDeployControl();
        SiteControlHandlers.initTerminal();
        SiteControlHandlers.initInstanceConsole();
        SiteControlHandlers.initDevTunnel();
        initApi();
        InstanceApi.init();
        PaasApi.init();
        InstanceFileEndpoints.init();
        InstanceStatsHandler.init();
    }

    // -----------------------------------------------------------------------
    // Automation API: znit_ bearer keys (zenit-auth). State-changing calls
    // REFUSE session principals -- only header-carried API keys may act, which
    // is what makes the csrfExempt declaration safe.
    // -----------------------------------------------------------------------

    private static void initApi() {
        HohenheimEndpoints.API_SITES.setHandler(conduit -> {
            List<Map<String, Object>> sites = new ArrayList<>();
            var proxy = ServerMain.getProxyServer();
            for (Row site : Models.get(SiteModel.class).find()
                    .where(SiteModel.DELETED_AT.isNull()).all()) {
                Integer siteId = site.get(SiteModel.ID);
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("id", siteId);
                entry.put("name", site.get(SiteModel.NAME));
                entry.put("slug", site.get(SiteModel.SLUG));
                entry.put("type", String.valueOf(site.get(SiteModel.SITE_TYPE)));
                entry.put("source", site.get(SiteModel.SOURCE));
                entry.put("enabled", Boolean.TRUE.equals(site.get(SiteModel.ENABLED)));
                SiteRequestHandler handler = proxy != null && siteId != null
                    ? proxy.getDispatcher().findHandlerBySiteId(siteId) : null;
                entry.put("health", handler != null
                    ? BlastString.lower(handler.getHealth().name()) : "unknown");
                if (handler instanceof GitSiteRequestHandler git) {
                    entry.put("current_commit", git.getCurrentCommit());
                    entry.put("deploying", git.isDeploying());
                }
                sites.add(entry);
            }
            return HandlerSupport.jsonUntyped(Map.of("sites", sites));
        });

        HohenheimEndpoints.API_SITES_DEPLOY.setHandler(conduit -> {
            if (!(conduit.getAttribute(ConduitAttributes.PRINCIPAL) instanceof ApiKeyPrincipal)) {
                conduit.forbidden();
                return null;
            }
            Integer siteId = conduit.getParameter(HohenheimEndpoints.SITE_ID);
            var git = SiteControlHandlers.gitHandler(siteId);
            if (git.isEmpty()) {
                conduit.notFound();
                return null;
            }
            git.get().enqueueDeploy("api");
            ActivityLog.record(Models.get(SiteModel.class), siteId, "deploy_triggered", "api");
            return HandlerSupport.jsonUntyped(Map.of("status", "queued", "site", siteId));
        });
    }

    private static void initHealth() {
        // GET / is owned by zenit-cms's landing redirect (CmsPanels, installed by
        // HohenheimHostWiring): operators land on /admin, manage-only tenants on /manage.
        HohenheimEndpoints.HEALTH.setHandler(conduit ->
            HandlerSupport.jsonUntyped(Map.of("status", "ok")));
    }

    /**
     * The Fetch-Metadata cross-site decision, kept here as the name the guard test asks for;
     * the implementation lives with the rest of the shared plumbing.
     */
    static boolean isCrossSiteFetch(@Nullable String secFetchSite) {
        return HandlerSupport.isCrossSiteFetch(secFetchSite);
    }
}
