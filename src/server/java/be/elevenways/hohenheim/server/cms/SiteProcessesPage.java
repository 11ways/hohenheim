package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.HohenheimEndpoints;
import be.elevenways.hohenheim.HohenheimParams;
import be.elevenways.hohenheim.model.ProclogModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.server.ServerMain;
import be.elevenways.hohenheim.server.process.ManagedProcessSiteHandler;
import be.elevenways.hohenheim.server.sitetype.SiteHandlers;
import be.elevenways.hohenheim.server.sitetype.SiteTypes;
import be.elevenways.hohenheim.sitetype.SiteTypeInfo;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.cms.common.page.CmsEndpoints;
import be.elevenways.zenit.cms.common.page.CmsRoutes;
import be.elevenways.zenit.cms.common.resource.RecordScopedPage;
import be.elevenways.zenit.common.conduit.Conduit;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.orm.query.SortOrder;
import be.elevenways.zenit.common.result.ActionResult;
import be.elevenways.zenit.common.result.RenderTemplateResult;
import be.elevenways.zenit.common.routing.RouteTarget;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.common.ui.Icon;
import be.elevenways.zenit.server.http.ReturnTarget;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Processes tab on a managed site: the live process table with
 * start/kill/isolate actions, the interactive terminal, and the stored
 * process-log history (a {@code ?log=<id>} query renders one stored log).
 */
public final class SiteProcessesPage implements RecordScopedPage<Row>, TerminalCspPage {

    /** The route slug, shared with the terminal's scoped-CSP claim. */
    public static final String SLUG = "processes";

    @Override public @NonNull Identifier id() { return Identifier.of("hohenheim", "site_processes"); }
    @Override public @NonNull Microcopy label() { return Microcopy.of("processes").withFilter("scope", "site"); }
    @Override public @NonNull String slug() { return SLUG; }
    @Override public @NonNull Icon icon() { return Icon.of("microchip"); }

    @Override
    public boolean visibleFor(@NonNull Row site) {
        // Host-process types only: a Docker site supports env injection too since the
        // isolation wave, but its runtime is a container, not a spawned process table.
        SiteTypeInfo type = SiteTypes.getHandler(site.get(SiteModel.SITE_TYPE));
        return type != null && type.supportsEnvInjection() && !type.containerRuntime();
    }

    @Override
    public @NonNull ActionResult<?> render(@NonNull Conduit conduit,
                                           @NonNull AccessContext accessContext,
                                           @NonNull Row site) {
        Integer siteId = site.get(SiteModel.ID);
        Map<String, Object> vars = new HashMap<>();
        vars.put("title", CmsSupport.pageTitle(conduit, "site_processes",
            site.get(SiteModel.NAME)));
        vars.put("siteId", siteId);
        vars.put("siteName", site.get(SiteModel.NAME));

        boolean managed = false;
        List<Map<String, Object>> processes = new ArrayList<>();
        ManagedProcessSiteHandler handler = SiteHandlers.managedProcess(siteId);
        if (handler != null) {
            managed = true;
            for (var proc : handler.getProcesses()) {
                Map<String, Object> info = new HashMap<>();
                info.put("pid", proc.pid());
                info.put("port", proc.port());
                info.put("cpu", proc.cpuPercent());
                info.put("memoryMb", proc.memoryKb() / 1024);
                info.put("isolated", proc.isIsolated());
                info.put("ready", proc.isReady());
                info.put("fingerprints", proc.activeFingerprintCount());
                info.put("isolateTarget", HohenheimEndpoints.SITES_PROCESS_ISOLATE
                    .with(HohenheimEndpoints.SITE_ID, siteId)
                    .with(HohenheimEndpoints.PID, proc.pid()));
                info.put("killTarget", HohenheimEndpoints.SITES_PROCESS_KILL
                    .with(HohenheimEndpoints.SITE_ID, siteId)
                    .with(HohenheimEndpoints.PID, proc.pid()));
                // AIDEV-NOTE: WebSocketEndpoint is not a RouteTarget and has no with(...),
                // and pl-terminal takes a wsUrl STRING anyway, so the socket route is
                // RENDERED from its own declaration -- never concatenated.
                info.put("terminalWsUrl", HohenheimEndpoints.PROCESS_TERMINAL.toUrl(Map.of(
                    HohenheimEndpoints.SITE_ID, siteId,
                    HohenheimEndpoints.PID, proc.pid())));
                processes.add(info);
            }
        }
        vars.put("isManaged", managed);
        vars.put("startTarget", HohenheimEndpoints.SITES_PROCESS_START
            .with(HohenheimEndpoints.SITE_ID, siteId));
        // A process-control action that could not run says so HERE; the handlers redirect
        // back with ?error= rather than reloading the page as though the action worked.
        String error = conduit.getQueryParam("error");
        vars.put("error", error != null ? error : "");
        vars.put("processes", processes);

        // Stored proclog history; ?log=<id> renders one stored log inline.
        List<Map<String, Object>> proclogs = new ArrayList<>();
        for (Row log : Models.get(ProclogModel.class).find()
                .where(ProclogModel.SITE_ID.eq(siteId))
                .orderBy(ProclogModel.CREATED_AT, SortOrder.DESC)
                .limit(50)
                .all()) {
            Map<String, Object> entry = new HashMap<>();
            entry.put("id", log.get(ProclogModel.ID));
            entry.put("pid", log.get(ProclogModel.PID));
            entry.put("lineCount", log.get(ProclogModel.LINE_COUNT));
            entry.put("createdAt", String.valueOf(log.get(ProclogModel.CREATED_AT)));
            entry.put("target", logTarget(conduit, siteId, log.get(ProclogModel.ID)));
            proclogs.add(entry);
        }
        vars.put("proclogs", proclogs);

        String selectedLog = conduit.getQueryParam("log");
        String selectedLogText = "";
        String selectedLogTitle = "";
        if (selectedLog != null && !selectedLog.isBlank()) {
            try {
                Row log = Models.get(ProclogModel.class).findById(Integer.parseInt(selectedLog));
                // Ownership guard: a proclog id from another site must not render here.
                if (log != null && siteId.equals(log.get(ProclogModel.SITE_ID))) {
                    // AIDEV-NOTE: the log_html column name is historical -- the payload is
                    // the child's stdout VERBATIM, which an anonymous visitor to a proxied
                    // site controls (request path, User-Agent). It leaves here as TEXT and
                    // the template renders it as a text node. Never hand it to a raw-HTML
                    // render.
                    String text = log.get(ProclogModel.LOG_HTML);
                    selectedLogText = text != null ? text : "";
                    selectedLogTitle = Microcopy.of("stored_log_heading")
                        .withFilter("scope", "site_processes")
                        .withArg("pid", String.valueOf((Object) log.get(ProclogModel.PID)))
                        .withArg("created", String.valueOf(
                            (Object) log.get(ProclogModel.CREATED_AT)))
                        .resolve(conduit.getLocales(), conduit.getMessageResolver());
                }
            } catch (NumberFormatException ignored) {
                // Bad id: just render the page without a selected log.
            }
        }
        vars.put("selectedLogText", selectedLogText);
        vars.put("selectedLogTitle", selectedLogTitle);
        // The start/kill/isolate forms echo this as _return so their handlers
        // redirect back to whichever panel rendered this page (?log= included).
        vars.put("returnUrl", ReturnTarget.capture(conduit));
        // AIDEV-NOTE: the hidden field NAME comes from the framework constant --
        // ReturnTarget is server-only, so the common template cannot reach it.
        vars.put("returnParam", ReturnTarget.PARAM);
        vars.put("recordTabs", recordTabs(conduit));

        return new RenderTemplateResult(Identifier.of("hohenheim", "cms/site-processes"), vars);
    }

    /**
     * This tab, with one stored proclog selected.
     *
     * AIDEV-NOTE: composed off CmsEndpoints rather than CmsRoutes.subpage because a CMS
     * route PLUS a query parameter cannot be built from CmsRoutes -- its builders return
     * the RouteTarget interface, which has no with(...).
     */
    private static @NonNull RouteTarget logTarget(@NonNull Conduit conduit,
                                                  @NonNull Integer siteId,
                                                  @NonNull Integer logId) {
        return CmsEndpoints.RECORD_SUBPAGE
            .with(CmsEndpoints.PANEL_PARAM, CmsSupport.panelSlug(conduit))
            .with(CmsEndpoints.RESOURCE_PARAM, "sites")
            .with(CmsEndpoints.RESOURCE_ID_PARAM, String.valueOf(siteId))
            .with(CmsEndpoints.SUBPAGE_PARAM, SLUG)
            .with(HohenheimParams.SELECTED_LOG, logId);
    }
}
