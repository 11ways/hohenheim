package be.elevenways.hohenheim.test;

import be.elevenways.hawkeye.testSupport.HawkeyeBrowserTestBase;
import be.elevenways.hohenheim.HohenheimEndpoints;
import be.elevenways.hohenheim.server.HohenheimDatabase;
import be.elevenways.zenit.common.Zenit;
import be.elevenways.zenit.server.ServerZenitRuntime;
import be.elevenways.zenit.server.http.ZenitHttpServer;

/**
 * Browser test base for Hohenheim.
 */
public abstract class HohenheimTestBase extends HawkeyeBrowserTestBase {

    private static ZenitHttpServer zenitServer;
    private static int port;

    @Override
    protected int startServer() throws Exception {
        if (ServerZenitRuntime.INSTANCE != null) {
            return port;
        }

        be.elevenways.hohenheim.server.sitetype.SiteTypes.register();
        HohenheimEndpoints.init();
        HohenheimDatabase.init();
        be.elevenways.hohenheim.server.HohenheimHandlers.init();

        ServerZenitRuntime.init();
        Zenit.getHawkeye().setClientScriptLocation("/hohenheim.js");

        zenitServer = ServerZenitRuntime.createServer(0);
        zenitServer.start();
        port = zenitServer.getPort();

        System.out.println("Hohenheim test server started on http://localhost:" + port);
        return port;
    }

    @Override
    protected void stopServer() throws Exception {
        if (zenitServer != null) {
            zenitServer.stop();
            zenitServer = null;
        }
    }

    @Override
    protected int getServerPort() {
        return port;
    }
}
