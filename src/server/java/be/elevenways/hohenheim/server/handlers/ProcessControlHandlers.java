package be.elevenways.hohenheim.server.handlers;

import be.elevenways.hohenheim.HohenheimEndpoints;
import be.elevenways.hohenheim.model.AuditLogModel;
import be.elevenways.hohenheim.server.ServerMain;

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
    }
}
