package be.elevenways.hohenheim.server.handlers;

import be.elevenways.hohenheim.HohenheimEndpoints;
import be.elevenways.hohenheim.server.process.IpcChannel;
import be.elevenways.hohenheim.server.process.ManagedProcess;
import be.elevenways.hohenheim.server.process.ManagedProcessSiteHandler;
import be.elevenways.hohenheim.server.process.ProcessTerminalHandler;
import be.elevenways.hohenheim.server.stats.DashboardWebSocketHandler;

import java.util.Optional;

/**
 * WebSocket handler factory registrations.
 */
public final class WebSocketHandlers {

    private WebSocketHandlers() {
    }

    public static void init() {
        HohenheimEndpoints.DASHBOARD_LIVE.setHandlerFactory(
            DashboardWebSocketHandler::new);

        HohenheimEndpoints.PROCESS_TERMINAL.setHandlerFactory(session -> {
            Integer siteId = session.getParameter(HohenheimEndpoints.SITE_ID);
            Long pid = session.getParameter(HohenheimEndpoints.PID);
            ManagedProcess proc = null;
            IpcChannel ipc = null;
            Optional<ManagedProcessSiteHandler> managedOpt = HandlerSupport.managedHandler(siteId);
            if (managedOpt.isPresent() && pid != null) {
                ManagedProcessSiteHandler managed = managedOpt.get();
                proc = managed.getProcess(pid);
                ipc = managed.getIpcChannel(pid);
            }
            return new ProcessTerminalHandler(session, proc, ipc);
        });
    }
}
