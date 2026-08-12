package be.elevenways.hohenheim.test;

import be.elevenways.hawkeye.testSupport.HawkeyeBrowserTestBase;
import be.elevenways.hohenheim.HohenheimEndpoints;
import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.hohenheim.server.HohenheimDatabase;
import be.elevenways.hohenheim.server.HohenheimSettingsFiles;
import be.elevenways.hohenheim.server.ServerMain;
import be.elevenways.hohenheim.server.auth.SiteAuthProviders;
import be.elevenways.hohenheim.server.sitetype.SiteTypes;
import be.elevenways.zenit.auth.AuthKeys;
import be.elevenways.zenit.auth.AuthSettings;
import be.elevenways.zenit.auth.model.GrantModel;
import be.elevenways.zenit.auth.model.UserModel;
import be.elevenways.zenit.auth.server.AuthCookieSupport;
import be.elevenways.zenit.auth.server.AuthModels;
import be.elevenways.zenit.auth.server.ZenitAuth;
import be.elevenways.zenit.common.Zenit;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.security.csrf.CsrfTokens;
import be.elevenways.zenit.common.session.Session;
import be.elevenways.zenit.server.ServerZenitRuntime;
import be.elevenways.zenit.server.http.RateLimitMiddleware;
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
// The tag is @Inherited: every subclass lands in the shared-server gradle bucket
// (parallel forks, one server per JVM). Standalone classes -- which re-point the
// global Database.PATH and re-init the runtime for themselves -- run in the
// browserTestIsolated bucket with a fresh JVM per class, because doing that
// beside a live shared server yanks the database out from under it.
@org.junit.jupiter.api.Tag("shared-server")
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
            File settingsDry = File.createTempFile("hohenheim-test-settings", ".dry");
            settingsDry.delete();
            settingsDry.deleteOnExit();
            System.setProperty("hohenheim.settings", settingsDry.getAbsolutePath());
        } catch (IOException e) {
            throw new RuntimeException("Failed to create temp database file", e);
        }

        // The shared harness DECLARES its role set instead of inheriting it by
        // omission: every role on, the full-node shape this suite has always
        // exercised. load() below snapshots these into HohenheimRoles.
        HohenheimSettingsFiles.forceDefinitions();
        HohenheimSettings.VALUES.setValue(HohenheimSettings.Roles.PROXY, true);
        HohenheimSettings.VALUES.setValue(HohenheimSettings.Roles.DNS, true);
        HohenheimSettings.VALUES.setValue(HohenheimSettings.Roles.FIREWALL, true);
        HohenheimSettings.VALUES.setValue(HohenheimSettings.Roles.STACKS, true);
        HohenheimSettings.VALUES.setValue(HohenheimSettings.Roles.PROCESSES, true);
        HohenheimSettings.VALUES.setValue(HohenheimSettings.Roles.DATABASES, true);

        // Load the (empty) test settings file into the context so the panel's
        // framework SettingsPage can locate its editable DryFileSource.
        HohenheimSettingsFiles.load();

        SiteTypes.boot();
        HohenheimEndpoints.init();
        // Before the migrations, exactly as ServerMain does it: the declarations carry the
        // per-model liveness definition zenit-auth's orphan-purge migration consults.
        HohenheimTestRuntime.declareAccessModelsOnce();
        // Claims the database path too, AFTER HohenheimSettingsFiles.load() so a loaded
        // settings file can never point the suite at a developer's real database.
        try {
            TestDatabases.freshDatabase();
        } catch (Exception e) {
            throw new RuntimeException("Failed to create the test database", e);
        }

        // Install auth exactly as production does, BEFORE the boot stages run:
        // ManagePanel wraps the permission checker zenit-auth installs, so the
        // harness must not invert that order.
        ZenitAuth.init(HohenheimDatabase.datasource());
        // Production disables the module's default auth panel (ServerMain does the
        // same): the users/roles resources are wired into HohenheimPanel instead.
        AuthSettings.VALUES.setValue(AuthSettings.CMS_AUTO_PANEL, false);
        ServerMain.installAuthBaselines();

        // AIDEV-NOTE: ONE order, shared with production. Everything a request needs
        // -- client script location, endpoint handlers (incl. the WebSocket handler
        // factories), both panels, the security engine -- is installed by the
        // discovered HohenheimHostWiring module at the MODULES stage inside this
        // call. The harness used to hand-install those AFTER booting while
        // ServerMain installed them after BINDING, so the suite was a second,
        // safer truth and could never see production's pre-wiring window.
        HohenheimTestRuntime.ensureBooted();

        sessionToken = seedAuthenticatedAdmin();

        // Endpoint rate limits (deploy/db-io/download) share one JVM-wide
        // bucket per principal; a full suite would trip them across classes.
        // The dedicated rate-limit test installs its own strict resolver.
        RateLimitMiddleware.setPolicyResolver((conduit, endpoint, declared) -> null);

        zenitServer = ServerZenitRuntime.createServer(0);
        zenitServer.start();
        port = zenitServer.getPort();

        System.out.println("Hohenheim test server started on http://localhost:" + port);
        return port;
    }

    /** Create an enabled admin user and an active session for it; returns the session id (cookie value).
     *  Package-visible: isolated boot tests (RoleRestrictedBootTest) reuse it instead of copying. */
    static String seedAuthenticatedAdmin() {
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
        return session.token().secret();
    }

    @Override
    protected void stopServer() {
        // Shared across the JVM (the CmsBrowserTestBase pattern): the first class to
        // finish must NOT stop the server every later class in this fork still uses.
        // Teardown rides on JVM shutdown.
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
