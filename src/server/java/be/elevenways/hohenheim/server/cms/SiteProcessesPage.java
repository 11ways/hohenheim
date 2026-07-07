package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.model.ProclogModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.server.ServerMain;
import be.elevenways.hohenheim.server.process.ManagedProcessSiteHandler;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.cms.common.resource.RecordScopedPage;
import be.elevenways.zenit.common.conduit.Conduit;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.orm.query.SortOrder;
import be.elevenways.zenit.common.result.ActionResult;
import be.elevenways.zenit.common.result.RenderTemplateResult;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.common.ui.Icon;
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
public final class SiteProcessesPage implements RecordScopedPage<Row> {

    @Override public @NonNull Identifier id() { return Identifier.of("hohenheim", "site_processes"); }
    @Override public @NonNull Microcopy label() { return Microcopy.of("hohenheim.site.processes"); }
    @Override public @NonNull String slug() { return "processes"; }
    @Override public @NonNull Icon icon() { return Icon.of("microchip"); }

    @Override
    public @NonNull ActionResult<?> render(@NonNull Conduit conduit,
                                           @NonNull AccessContext accessContext,
                                           @NonNull Row site) {
        Integer siteId = site.get(SiteModel.ID);
        Map<String, Object> vars = new HashMap<>();
        vars.put("title", site.get(SiteModel.NAME) + " - Processes");
        vars.put("siteId", siteId);
        vars.put("siteName", site.get(SiteModel.NAME));

        boolean managed = false;
        List<Map<String, Object>> processes = new ArrayList<>();
        var proxy = ServerMain.getProxyServer();
        if (proxy != null && proxy.getDispatcher().findHandlerBySiteId(siteId)
                instanceof ManagedProcessSiteHandler handler) {
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
                processes.add(info);
            }
        }
        vars.put("isManaged", managed);
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
            proclogs.add(entry);
        }
        vars.put("proclogs", proclogs);

        String selectedLog = conduit.getQueryParam("log");
        String selectedLogHtml = "";
        String selectedLogTitle = "";
        if (selectedLog != null && !selectedLog.isBlank()) {
            try {
                Row log = Models.get(ProclogModel.class).findById(Integer.parseInt(selectedLog));
                // Ownership guard: a proclog id from another site must not render here.
                if (log != null && siteId.equals(log.get(ProclogModel.SITE_ID))) {
                    String html = log.get(ProclogModel.LOG_HTML);
                    selectedLogHtml = html != null ? html : "";
                    selectedLogTitle = "PID " + log.get(ProclogModel.PID)
                        + " (" + log.get(ProclogModel.CREATED_AT) + ")";
                }
            } catch (NumberFormatException ignored) {
                // Bad id: just render the page without a selected log.
            }
        }
        vars.put("selectedLogHtml", selectedLogHtml);
        vars.put("selectedLogTitle", selectedLogTitle);
        vars.put("recordTabs", CmsSupport.siteTabs(siteId, "processes"));

        return new RenderTemplateResult(Identifier.of("hohenheim", "cms/site-processes"), vars);
    }
}
