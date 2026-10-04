package be.elevenways.hohenheim.test;

import be.elevenways.zenit.browsertest.ZenitBrowserTestBase;
import be.elevenways.hohenheim.HohenheimEndpoints;
import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.hohenheim.HohenheimSlugs;
import be.elevenways.hohenheim.server.HohenheimSettingsBoot;
import be.elevenways.hohenheim.server.ServerMain;
import be.elevenways.zenit.auth.AuthKeys;
import be.elevenways.zenit.auth.AuthSettings;
import be.elevenways.zenit.auth.model.UserModel;
import be.elevenways.zenit.auth.server.AuthCookieSupport;
import be.elevenways.zenit.auth.server.ZenitAuth;
import be.elevenways.zenit.cms.common.page.CmsRoutes;
import be.elevenways.zenit.cms.common.render.action.CmsConfirmation;
import be.elevenways.zenit.cms.test.support.PanelResourceCalls;
import be.elevenways.zenit.common.Zenit;
import be.elevenways.zenit.common.operation.Operation;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.security.csrf.CsrfTokens;
import be.elevenways.zenit.common.session.Session;
import com.microsoft.playwright.options.Cookie;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import be.elevenways.zenit.common.session.SessionToken;
import be.elevenways.zenit.common.flash.FlashNotice;
import be.elevenways.zenit.test.support.FlashHandoff;
import be.elevenways.zenit.test.support.RateLimitExemption;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.junit.jupiter.api.BeforeEach;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

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
public abstract class HohenheimTestBase extends ZenitBrowserTestBase {

    protected static String sessionToken;
    protected static String csrfToken;
    /** Every session {@link #sessionFor} minted in this JVM, drained with the admin one before each test. */
    private static final Set<String> MINTED_SESSIONS = ConcurrentHashMap.newKeySet();

    @Override
    protected void bootHost() throws Exception {
        // The shared harness DECLARES its role set instead of inheriting it by
        // omission: every role on, the full-node shape this suite has always
        // exercised. load() below snapshots these into HohenheimRoles.
        HohenheimSettingsBoot.forceDefinitions();
        Zenit.SETTINGS_VALUES.setValue(HohenheimSettings.Roles.PROXY, true);
        Zenit.SETTINGS_VALUES.setValue(HohenheimSettings.Roles.DNS, true);
        Zenit.SETTINGS_VALUES.setValue(HohenheimSettings.Roles.FIREWALL, true);
        Zenit.SETTINGS_VALUES.setValue(HohenheimSettings.Roles.STACKS, true);
        Zenit.SETTINGS_VALUES.setValue(HohenheimSettings.Roles.DATABASES, true);

        // Load the settings chain the way ServerMain does. The lane's zenit.settings.root
        // points settings/local.dry into the build directory, so the settings page writes
        // there and never into the developer's own file.
        HohenheimSettingsBoot.load();

        HohenheimEndpoints.init();
        // Before the migrations, exactly as ServerMain does it: the declarations carry the
        // per-model liveness definition zenit-auth's orphan-purge migration consults.
        HohenheimTestRuntime.declareAccessModelsOnce();
        // Claims the database path too, AFTER HohenheimSettingsBoot.load() so a loaded
        // settings file can never point the suite at a developer's real database.
        try {
            TestDatabases.freshDatabase();
        } catch (Exception e) {
            throw new RuntimeException("Failed to create the test database", e);
        }

        // zenit-auth installs itself exactly as in production: the discovered ZenitAuthModule
        // at the MODULES stage, which HohenheimHostWiring runs after (ManagePanel wraps the
        // permission checker zenit-auth installs, so that order must not invert). Production
        // disables the module's default auth panel BEFORE boot (ServerMain does the same):
        // the users/roles resources are wired into HohenheimPanel instead.
        Zenit.SETTINGS_VALUES.setValue(AuthSettings.CMS_AUTO_PANEL, false);
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
        // The dedicated rate-limit test lifts the exemption for its hammer.
        RateLimitExemption.exemptAll();
    }

    @Override
    protected void afterServerStart() {
        System.out.println("Hohenheim test server started on http://localhost:" + this.getServerPort());
    }

    /** The test admin ({@link TenantConduits#operatorUser}) and an active session for it; returns the session id.
     *  Package-visible: isolated boot tests (RoleRestrictedBootTest) reuse it instead of copying. */
    static String seedAuthenticatedAdmin() {
        Row user = TenantConduits.operatorUser();

        Session session = Zenit.getSessionStore().create();
        session.set(AuthKeys.USER_ID, ((Integer) user.get(UserModel.ID)).longValue());
        csrfToken = ZenitAuth.randomToken();
        session.set(CsrfTokens.TOKEN, csrfToken);   // so direct-POST tests can send X-Csrf-Token
        Zenit.getSessionStore().save(session);
        return session.token().secret();
    }

    /** Reset a surface fixture's database and sessions while retaining the shared HTTP server. */
    protected static void freshSeededDatabase() throws Exception {
        TestDatabases.freshBootedDatasource();
        MINTED_SESSIONS.clear();
        sessionToken = seedAuthenticatedAdmin();
    }


    /**
     * Take the admin session's flash toast that loading {@code answer}'s redirect would show.
     *
     * AIDEV-NOTE: outcome messages ride the SESSION, never the redirect URL -- the
     * seven query parameters that used to carry them are deleted. A redirect hands the
     * stashed message off under a one-shot token on its Location, so a test follows that
     * Location: it takes the message through Flash.take (FlashHandoff), which keeps the
     * tests off the session layout, and compares landingOf(answer) for the destination.
     */
    protected static @Nullable FlashNotice popFlash(@NonNull HttpResponse<?> answer) {
        return popFlash(answer, sessionToken);
    }

    /** Take the flash toast of an ARBITRARY session (a tenant's, not the admin's) that {@code answer} leads to. */
    protected static @Nullable FlashNotice popFlash(@NonNull HttpResponse<?> answer, @NonNull String token) {
        Session session = Zenit.getSessionStore().get(SessionToken.of(token));
        if (session == null) {
            return null;
        }
        return FlashHandoff.take(session, answer.headers().firstValue("Location").orElse(null));
    }

    /** @return the redirect's Location without its flash handoff token, or "" without a Location */
    protected static @NonNull String landingOf(@NonNull HttpResponse<?> answer) {
        return answer.headers().firstValue("Location").map(FlashHandoff::landing).orElse("");
    }

    // -- shared HTTP transport ------------------------------------------------
    //
    // THE one copy of the request helpers ~60 test classes used to each hand-roll.
    // Session requests carry the auth cookie (plus CSRF on writes); key requests
    // carry X-Api-Key and no cookie. A shape the verbs do not cover (multipart, a
    // custom header) builds on requestTo + sendRequest, never on its own HttpClient.

    /** An authenticated session plus its CSRF token, for driving requests as one user. */
    protected record TestSession(String token, String csrf) {
    }

    /** Mint an authenticated session (with CSRF token) for an existing user id. */
    protected static TestSession sessionFor(int userId) {
        Session session = Zenit.getSessionStore().create();
        session.set(AuthKeys.USER_ID, (long) userId);
        String csrf = ZenitAuth.randomToken();
        session.set(CsrfTokens.TOKEN, csrf);
        Zenit.getSessionStore().save(session);
        String token = session.token().secret();
        MINTED_SESSIONS.add(token);
        return new TestSession(token, csrf);
    }

    /**
     * Discard every flash notice pending in the admin session and every session {@link #sessionFor} minted.
     *
     * AIDEV-NOTE: a test that asserts a redirect's landingOf(answer) without redeeming its notice through popFlash
     * leaves that notice pending, and these sessions outlive the test (the admin one serves the whole JVM, a class may
     * keep a tenant one in a static field), so the next test would start with a stranger's toast waiting. Both
     * shipped session stores commit an update on the spot and make save a no-op, so no save follows the drain.
     */
    @BeforeEach
    void drainPendingFlash() {
        if (sessionToken != null) {
            drainPendingFlash(sessionToken);
        }
        for (String token : MINTED_SESSIONS) {
            drainPendingFlash(token);
        }
    }

    private static void drainPendingFlash(@NonNull String token) {
        // A revoked or expired session resolves to null: nothing of it can reach a later test.
        Session session = Zenit.getSessionStore().get(SessionToken.of(token));
        if (session != null) {
            FlashHandoff.drain(session);
        }
    }

    protected @NonNull String baseUrl() {
        return "http://localhost:" + getServerPort();
    }

    /**
     * A confirmed CMS POST body: a delete or confirmed action whose body carries no
     * {@link CmsConfirmation#FIELD} proof gets the interstitial instead of mutating, so
     * every raw form POST that used to rely on the client dialog must stamp it here.
     */
    protected static @NonNull String confirmed(@Nullable String body) {
        return confirmed(body, null);
    }

    /**
     * @param typedPhrase the phrase the action's typed confirmation requires, null for a
     *                    single-click confirmation
     */
    protected static @NonNull String confirmed(@Nullable String body, @Nullable String typedPhrase) {
        String proof = CmsConfirmation.FIELD + "=" + URLEncoder.encode(
            CmsConfirmation.proofValue(typedPhrase, typedPhrase), StandardCharsets.UTF_8);
        return body == null || body.isEmpty() ? proof : body + "&" + proof;
    }

    /**
     * The invoke route of a placed operation on the admin sites list: a row verb names its one site in the query; a
     * bulk verb (no site given) takes its selection in the posted body.
     */
    protected static @NonNull String siteInvoke(@NonNull Operation<?, ?, ?> verb, int... siteIds) {
        String url = CmsRoutes.invoke(HohenheimSlugs.ADMIN, HohenheimSlugs.SITES, verb.id()).toUrl();
        return siteIds.length == 0 ? url : url + "?ids=" + siteIds[0];
    }

    /**
     * The command envelope a hand-posted site CREATE form carries (url-encoded body pairs): the site create is an
     * operation, which refuses a post without its reviewed identity.
     */
    protected static @NonNull String siteCreateEnvelope() {
        return PanelResourceCalls.createEnvelope();
    }

    /**
     * The command envelope a hand-posted site EDIT form carries, admin or /manage: a fresh identity and the site's
     * domain version as of now, so read it at the post, never once for several posts.
     */
    protected static @NonNull String siteEditEnvelope(@NonNull Object siteId) {
        return PanelResourceCalls.editEnvelope(HohenheimSlugs.ADMIN, HohenheimSlugs.SITES, siteId,
            TenantConduits.operator());
    }

    /** A request builder aimed at {@code path} on the test server, for a shape the verbs below do not cover. */
    protected HttpRequest.@NonNull Builder requestTo(@NonNull String path) {
        return HttpRequest.newBuilder().uri(URI.create(baseUrl() + path));
    }

    /** The Cookie header value that carries {@code session}. */
    protected static @NonNull String sessionCookieHeader(@NonNull String session) {
        return AuthCookieSupport.sessionCookieName() + "=" + session;
    }

    protected HttpResponse<String> httpGet(String path, @Nullable String session)
            throws Exception {
        return sendRequest(getRequest(path, session));
    }

    /**
     * A GET that follows redirects to the final answer, for a test asserting where a
     * redirect chain LANDS rather than the redirect itself.
     */
    protected HttpResponse<String> httpGetFollowingRedirects(String path, @Nullable String session)
            throws Exception {
        return sendFollowingRedirects(getRequest(path, session));
    }

    private HttpRequest.@NonNull Builder getRequest(String path, @Nullable String session) {
        HttpRequest.Builder request = requestTo(path).GET();
        if (session != null) {
            request.header("Cookie", sessionCookieHeader(session));
        }
        return request;
    }

    protected HttpResponse<String> httpPost(String path, String body, String session,
                                            String csrf, String contentType) throws Exception {
        return sendRequest(requestTo(path)
            .header("Content-Type", contentType)
            .header("Cookie", sessionCookieHeader(session))
            .header("X-Csrf-Token", csrf)
            .POST(HttpRequest.BodyPublishers.ofString(body)));
    }

    protected HttpResponse<String> httpPostForm(String path, String body, String session,
                                                String csrf) throws Exception {
        return httpPost(path, body, session, csrf, "application/x-www-form-urlencoded");
    }

    protected HttpResponse<String> httpPostDry(String path, String body, String session,
                                               String csrf) throws Exception {
        return httpPost(path, body, session, csrf, "application/dry");
    }

    /** The harness admin's session, for a positive anchor beside a tenant journey. */
    protected HttpResponse<String> adminGet(String path) throws Exception {
        return httpGet(path, sessionToken);
    }

    /** A urlencoded POST as the harness admin, CSRF token included. */
    protected HttpResponse<String> adminPostForm(String path, String body) throws Exception {
        return httpPostForm(path, body, sessionToken, csrfToken);
    }

    /** A DRY POST as the harness admin, CSRF token included. */
    protected HttpResponse<String> adminPostDry(String path, String body) throws Exception {
        return httpPostDry(path, body, sessionToken, csrfToken);
    }

    protected HttpResponse<String> keyGet(String key, String path) throws Exception {
        return sendRequest(requestTo(path)
            .header("X-Api-Key", key)
            .GET());
    }

    protected HttpResponse<String> keyPost(String key, String path, String body)
            throws Exception {
        return sendRequest(requestTo(path)
            .header("X-Api-Key", key)
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(body)));
    }

    protected HttpResponse<String> keyPostDry(String key, String path, String body)
            throws Exception {
        return sendRequest(requestTo(path)
            .header("X-Api-Key", key)
            .header("Content-Type", "application/dry")
            .POST(HttpRequest.BodyPublishers.ofString(body)));
    }

    /** THE transport: redirects are answers here, never followed. */
    protected static HttpResponse<String> sendRequest(HttpRequest.@NonNull Builder builder)
            throws Exception {
        return send(builder, HttpClient.Redirect.NEVER);
    }

    /** The transport for a test asserting where a redirect chain lands. */
    protected static HttpResponse<String> sendFollowingRedirects(HttpRequest.@NonNull Builder builder)
            throws Exception {
        return send(builder, HttpClient.Redirect.NORMAL);
    }

    /** {@link #sendRequest} answering the raw body bytes, for a test comparing a reply byte for byte. */
    protected static HttpResponse<byte[]> sendRequestBytes(HttpRequest.@NonNull Builder builder) {
        HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
        try {
            return client.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while waiting for " + builder.build().uri(), interrupted);
        }
    }

    private static HttpResponse<String> send(HttpRequest.Builder builder,
                                             HttpClient.Redirect redirects) throws Exception {
        HttpClient client = HttpClient.newBuilder().followRedirects(redirects).build();
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
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
