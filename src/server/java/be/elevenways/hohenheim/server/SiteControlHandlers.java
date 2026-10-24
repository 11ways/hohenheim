package be.elevenways.hohenheim.server;

import be.elevenways.hohenheim.HohenheimEndpoints;
import be.elevenways.hohenheim.server.devtunnel.DevTunnelServerHandler;
import be.elevenways.hohenheim.server.instance.InstanceConsoleHandler;
import be.elevenways.hohenheim.server.instance.InstanceShellHandler;
import be.elevenways.hohenheim.server.instance.VmScreens;

/**
 * Operational control handlers: the instance console and the dev tunnel.
 *
 * AIDEV-NOTE: the Deploys-tab deploy form that lived here (a POST holding the request for a bounded settle window) is
 * gone: a panel deploy is the placed background operation InstanceOperations.DEPLOY, like both rollbacks.
 */
final class SiteControlHandlers {

    private SiteControlHandlers() {
    }

    static void initInstanceConsole() {
        HohenheimEndpoints.INSTANCE_CONSOLE.setHandlerFactory(session ->
            new InstanceConsoleHandler(session,
                session.getParameter(HohenheimEndpoints.INSTANCE_ID)));

        // The interactive shell rides the SAME transport as the console and is a SEPARATE
        // endpoint on purpose: it answers to `shell`, not `console` (see the endpoint's
        // own note). Registered here so nothing new has to be added to HohenheimHandlers.
        HohenheimEndpoints.INSTANCE_SHELL.setHandlerFactory(session ->
            new InstanceShellHandler(session,
                session.getParameter(HohenheimEndpoints.INSTANCE_ID)));

        VmScreens.install();
    }

    static void initDevTunnel() {
        HohenheimEndpoints.DEV_TUNNEL.setHandlerFactory(DevTunnelServerHandler::new);
    }

}
