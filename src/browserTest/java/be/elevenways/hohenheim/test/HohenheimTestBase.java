package be.elevenways.hohenheim.test;

import be.elevenways.hawkeye.testSupport.HawkeyeBrowserTestBase;
import be.elevenways.hohenheim.HohenheimEndpoints;
import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.hohenheim.server.HohenheimDatabase;
import be.elevenways.hohenheim.server.HohenheimHandlers;
import be.elevenways.hohenheim.server.ServerMain;
import be.elevenways.hohenheim.server.auth.SiteAuthProviders;
import be.elevenways.hohenheim.server.cms.HohenheimPanel;
import be.elevenways.hohenheim.server.sitetype.SiteTypes;
import be.elevenways.zenit.auth.AuthKeys;
import be.elevenways.zenit.auth.model.GrantModel;
import be.elevenways.zenit.auth.model.UserModel;
import be.elevenways.zenit.cms.server.page.ResourcePageEndpoints;
import be.elevenways.zenit.auth.server.AuthCookieSupport;
import be.elevenways.zenit.auth.server.AuthModels;
import be.elevenways.zenit.auth.server.ZenitAuth;
import be.elevenways.zenit.common.Zenit;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.security.csrf.CsrfTokens;
import be.elevenways.zenit.common.session.Session;
import be.elevenways.zenit.server.ServerZenitRuntime;
import be.elevenways.zenit.server.http.ZenitHttpServer;
import com.microsoft.playwright.options.Cookie;

import java.io.File;
import java.io.IOException;
import java.time.Instant;
import java.util.List;

/**
 * Browser test base for Hohenheim. Authenticates via zenit-auth: seeds an admin user and mints
 * a session, injected as the auth cookie so the (zenit-auth-gated) admin pages are reachable.
 */
public abstract class HohenheimTestBase extends HawkeyeBrowserTestBase {

    private static ZenitHttpServer zenitServer;
    private static int port;
    protected static String sessionToken;
    protected static String csrfToken;

    @Override
    protected int startServer() throws Exception {
        if (ServerZenitRuntime.INSTANCE != null) {
            return port;
        }

        // Use temp files for the test database and the settings write-back so
        // tests never pollute the working directory or the developer's local.dry.
        try {
            File db = File.createTempFile("hohenheim-test", ".db");
            db.delete();
            db.deleteOnExit();
            HohenheimSettings.VALUES.setValue(HohenheimSettings.Database.PATH, db.getAbsolutePath());

            File localDry = File.createTempFile("hohenheim-test-local", ".dry");
            localDry.delete();
            localDry.deleteOnExit();
            System.setProperty("hohenheim.local_settings", localDry.getAbsolutePath());
        } catch (IOException e) {
            throw new RuntimeException("Failed to create temp database file", e);
        }

        SiteTypes.boot();
        HohenheimEndpoints.init();
        // Force-load the zenit-cms panel routes (all /{panel}/... endpoints).
        Object cmsRoutes = ResourcePageEndpoints.LIST;
        HohenheimDatabase.init();

        // Run the real boot path (MODELS discovery registers the model singletons); HTTP auto-start
        // is disabled inside ensureBooted() since this base binds its own server below.
        HohenheimTestRuntime.ensureBooted();
        Zenit.getHawkeye().setClientScriptLocation("/cms.js");

        // Install auth exactly as production does, then seed a logged-in admin for the tests.
        ZenitAuth.init(HohenheimDatabase.datasource());
        ServerMain.installAuthBaselines();
        HohenheimHandlers.init();
        new HohenheimPanel();

        sessionToken = seedAuthenticatedAdmin();

        zenitServer = ServerZenitRuntime.createServer(0);
        zenitServer.start();
        port = zenitServer.getPort();

        System.out.println("Hohenheim test server started on http://localhost:" + port);
        return port;
    }

    /** Create an enabled admin user and an active session for it; returns the session id (cookie value). */
    private static String seedAuthenticatedAdmin() {
        Row user = AuthModels.users().createEmptyRow();
        user.set(UserModel.EMAIL, "test@hohenheim.local");
        user.set(UserModel.DISPLAY_NAME, "Test Admin");
        user.set(UserModel.ENABLED, true);
        user.set(UserModel.CREATED_AT, Instant.now());
        user.set(UserModel.UPDATED_AT, Instant.now());
        AuthModels.users().save(user);
        ZenitAuth.markSeeded();   // a user exists, so the setup gate must not redirect

        // Grant everything (the /setup admin's shape) so the CMS panel's
        // hohenheim.admin.access permission check passes.
        Row grant = AuthModels.grants().createEmptyRow();
        grant.set(GrantModel.SUBJECT_TYPE, "user");
        grant.set(GrantModel.SUBJECT_ID, user.get(UserModel.ID));
        grant.set(GrantModel.PERMISSION, "*");
        grant.set(GrantModel.VALUE, true);
        AuthModels.grants().save(grant);

        Session session = Zenit.getSessionStore().create();
        session.set(AuthKeys.USER_ID, ((Integer) user.get(UserModel.ID)).longValue());
        csrfToken = ZenitAuth.randomToken();
        session.set(CsrfTokens.TOKEN, csrfToken);   // so direct-POST tests can send X-Csrf-Token
        Zenit.getSessionStore().save(session);
        return session.id();
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
        // Inject the zenit-auth session cookie before navigation so gated admin pages are reachable.
        page.context().addCookies(List.of(
            new Cookie(AuthCookieSupport.sessionCookieName(), sessionToken)
                .setDomain("localhost")
                .setPath("/")
        ));
        super.navigateToApp(path);
    }
}
