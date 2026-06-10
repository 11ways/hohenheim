package be.elevenways.hohenheim.server.handlers;

import be.elevenways.hohenheim.HohenheimEndpoints;
import be.elevenways.hohenheim.model.AuditLogModel;
import be.elevenways.hohenheim.model.ProclogModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.server.ServerMain;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.orm.query.SortOrder;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Process control request handlers.
 */
public final class ProcessControlHandlers {

    private ProcessControlHandlers() {
    }

    public static void init() {
        // GET /sites/:id/processes - JSON process list
        HohenheimEndpoints.SITES_PROCESSES.setHandler(conduit -> {
            Integer siteId = conduit.getParameter(HohenheimEndpoints.SITE_ID);
            Map<String, Object> result = new HashMap<>();

            // managedHandler() already null-checks the proxy; an empty Optional covers both
            // "proxy not initialized" (empty result) and "proxy up but site not managed".
            boolean proxyUp = ServerMain.getProxyServer() != null;
            HandlerSupport.managedHandler(siteId).ifPresentOrElse(managed -> {
                List<Map<String, Object>> processes = new ArrayList<>();
                for (var proc : managed.getProcesses()) {
                    Map<String, Object> info = HandlerSupport.processToMap(proc);
                    info.put("memory", proc.memoryKb());
                    info.put("startTime", proc.startTime().toString());
                    processes.add(info);
                }
                result.put("running", !processes.isEmpty());
                result.put("processes", processes);
            }, () -> {
                if (proxyUp) {
                    result.put("running", false);
                    result.put("processes", List.of());
                    result.put("type", "not_managed");
                }
            });

            return HandlerSupport.jsonUntyped(result);
        });

        // POST /sites/:id/processes/start - Start a new process
        HohenheimEndpoints.SITES_PROCESS_START.setHandler(conduit -> {
            Integer siteId = conduit.getParameter(HohenheimEndpoints.SITE_ID);

            HandlerSupport.managedHandler(siteId).ifPresent(managed -> {
                managed.startProcess();
                HandlerSupport.audit(conduit, AuditLogModel.ACTION_STARTED_PROCESS,
                    AuditLogModel.RESOURCE_SITE, siteId, null);
            });

            return HandlerSupport.redirectUntyped("/sites/" + siteId);
        });

        // POST /sites/:id/processes/:pid/kill - Kill a process
        HohenheimEndpoints.SITES_PROCESS_KILL.setHandler(conduit -> {
            Integer siteId = conduit.getParameter(HohenheimEndpoints.SITE_ID);
            Long pid = conduit.getParameter(HohenheimEndpoints.PID);

            HandlerSupport.managedHandler(siteId).ifPresent(managed -> {
                var proc = managed.getProcess(pid);
                if (proc != null) {
                    proc.kill();
                    HandlerSupport.audit(conduit, AuditLogModel.ACTION_KILLED_PROCESS,
                        AuditLogModel.RESOURCE_SITE, siteId, "PID " + pid);
                }
            });

            return HandlerSupport.redirectUntyped("/sites/" + siteId);
        });

        // POST /sites/:id/processes/:pid/isolate - Toggle isolation
        HohenheimEndpoints.SITES_PROCESS_ISOLATE.setHandler(conduit -> {
            Integer siteId = conduit.getParameter(HohenheimEndpoints.SITE_ID);
            Long pid = conduit.getParameter(HohenheimEndpoints.PID);

            HandlerSupport.managedHandler(siteId).ifPresent(managed -> {
                var proc = managed.getProcess(pid);
                if (proc != null) {
                    proc.setIsolated(!proc.isIsolated());
                    HandlerSupport.audit(conduit, AuditLogModel.ACTION_ISOLATED_PROCESS,
                        AuditLogModel.RESOURCE_SITE, siteId, "PID " + pid);
                }
            });

            return HandlerSupport.redirectUntyped("/sites/" + siteId);
        });

        // GET /sites/:id/proclogs - Stored process log history
        HohenheimEndpoints.SITES_PROCLOGS.setHandler(conduit -> {
            Integer siteId = conduit.getParameter(HohenheimEndpoints.SITE_ID);
            Row site = Models.get(SiteModel.class).findById(siteId);
            if (site == null) {
                return HandlerSupport.redirectUntyped("/sites");
            }

            List<Map<String, Object>> logs = new ArrayList<>();
            for (Row row : Models.get(ProclogModel.class).find()
                    .where(ProclogModel.SITE_ID.eq(siteId))
                    .orderBy(ProclogModel.CREATED_AT, SortOrder.DESC)
                    .limit(100)
                    .all()) {
                logs.add(proclogToMap(siteId, row));
            }

            Map<String, Object> vars = new HashMap<>();
            vars.put("siteId", siteId);
            vars.put("siteName", site.get(SiteModel.NAME));
            vars.put("proclogs", logs);
            return HandlerSupport.renderUntyped(
                Identifier.of("hohenheim", "hohenheim/sites/proclog-list"), vars);
        });

        // GET /sites/:id/proclogs/:proclogId - Single stored log, rendered in a terminal
        HohenheimEndpoints.SITES_PROCLOG_DETAIL.setHandler(conduit -> {
            Integer siteId = conduit.getParameter(HohenheimEndpoints.SITE_ID);
            Integer proclogId = conduit.getParameter(HohenheimEndpoints.PROCLOG_ID);

            Row site = Models.get(SiteModel.class).findById(siteId);
            Row log = Models.get(ProclogModel.class).findById(proclogId);
            // Ownership guard: a proclog id from another site must not render here.
            if (site == null || log == null || !siteId.equals(log.get(ProclogModel.SITE_ID))) {
                return HandlerSupport.redirectUntyped("/sites/" + siteId + "/proclogs");
            }

            Map<String, Object> vars = proclogToMap(siteId, log);
            vars.put("siteName", site.get(SiteModel.NAME));
            String content = log.get(ProclogModel.LOG_HTML);
            vars.put("content", content != null ? content : "");
            return HandlerSupport.renderUntyped(
                Identifier.of("hohenheim", "hohenheim/sites/proclog-detail"), vars);
        });
    }

    /** Listing fields for one stored proclog row. */
    private static Map<String, Object> proclogToMap(int siteId, Row row) {
        Integer id = row.get(ProclogModel.ID);
        Map<String, Object> item = new HashMap<>();
        item.put("id", id);
        item.put("siteId", siteId);
        item.put("pid", row.get(ProclogModel.PID));
        item.put("lineCount", HandlerSupport.valueOrEmpty(row.get(ProclogModel.LINE_COUNT)));
        item.put("createdAt", HandlerSupport.valueOrEmpty(row.get(ProclogModel.CREATED_AT)));
        item.put("savedAt", HandlerSupport.valueOrEmpty(row.get(ProclogModel.SAVED_AT)));
        return item;
    }
}
