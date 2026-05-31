package be.elevenways.hohenheim.server.handlers;

import be.elevenways.hohenheim.HohenheimEndpoints;
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
            var proxy = ServerMain.getProxyServer();
            Map<String, Object> result = new HashMap<>();

            if (proxy != null) {
                var managedOpt = HandlerSupport.managedHandler(siteId);
                if (managedOpt.isPresent()) {
                    var managed = managedOpt.get();
                    List<Map<String, Object>> processes = new ArrayList<>();

                    for (var proc : managed.getProcesses()) {
                        Map<String, Object> info = new HashMap<>();
                        info.put("pid", proc.pid());
                        info.put("port", proc.port());
                        info.put("cpu", proc.cpuPercent());
                        info.put("memory", proc.memoryKb());
                        info.put("isolated", proc.isIsolated());
                        info.put("ready", proc.isReady());
                        info.put("startTime", proc.startTime().toString());
                        info.put("fingerprints", proc.activeFingerprintCount());
                        processes.add(info);
                    }

                    result.put("running", !processes.isEmpty());
                    result.put("processes", processes);
                } else {
                    result.put("running", false);
                    result.put("processes", List.of());
                    result.put("type", "not_managed");
                }
            }

            return HandlerSupport.jsonUntyped(result);
        });

        // POST /sites/:id/processes/start - Start a new process
        HohenheimEndpoints.SITES_PROCESS_START.setHandler(conduit -> {
            Integer siteId = conduit.getParameter(HohenheimEndpoints.SITE_ID);

            HandlerSupport.managedHandler(siteId).ifPresent(managed -> {
                managed.startProcess();
                HandlerSupport.audit(conduit, "started_process", "site", siteId, null);
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
                    HandlerSupport.audit(conduit, "killed_process", "site", siteId, "PID " + pid);
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
                    HandlerSupport.audit(conduit, "isolated_process", "site", siteId, "PID " + pid);
                }
            });

            return HandlerSupport.redirectUntyped("/sites/" + siteId);
        });
    }
}
