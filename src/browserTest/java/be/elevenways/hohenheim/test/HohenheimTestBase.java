package be.elevenways.hohenheim.test;

import be.elevenways.hawkeye.testSupport.HawkeyeBrowserTestBase;
import be.elevenways.hohenheim.HohenheimEndpoints;
import be.elevenways.hohenheim.server.AuthHelper;
import be.elevenways.hohenheim.server.HohenheimDatabase;
import be.elevenways.hohenheim.server.HohenheimHandlers;
import be.elevenways.hohenheim.server.sitetype.SiteTypes;
import be.elevenways.zenit.common.Zenit;
import be.elevenways.zenit.server.ServerZenitRuntime;
import be.elevenways.zenit.server.http.ZenitHttpServer;
import com.microsoft.playwright.options.Cookie;

import java.io.File;
import java.util.List;

/**
 * Browser test base for Hohenheim.
 */
public abstract class HohenheimTestBase extends HawkeyeBrowserTestBase {

    private static ZenitHttpServer zenitServer;
    private static int port;
    protected static String sessionToken;

    @Override
    protected int startServer() throws Exception {
        if (ServerZenitRuntime.INSTANCE != null) {
            return port;
        }

        // Delete stale db from previous test class forks
        File db = new File("hohenheim.db");
        if (db.exists()) {
            db.delete();
        }

        SiteTypes.register();
        HohenheimEndpoints.init();
        HohenheimDatabase.init();

        ServerZenitRuntime.init();
        Zenit.getHawkeye().setClientScriptLocation("/hohenheim.js");

        HohenheimHandlers.init();

        // Create a test user so the auth middleware doesn't block
        AuthHelper.createUser("test@hohenheim.local", "Test Admin", "testpassword123");
        sessionToken = AuthHelper.createSession(1);

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

    @Override
    protected void navigateToApp(String path) {
        // Set session cookie before navigation so auth middleware allows access
        page.context().addCookies(List.of(
            new Cookie("hh_session", sessionToken)
                .setDomain("localhost")
                .setPath("/")
        ));
        super.navigateToApp(path);
    }
}
