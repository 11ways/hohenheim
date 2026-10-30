package be.elevenways.hohenheim.test;

import be.elevenways.zenit.browsertest.ZenitBrowserTestBase;
import be.elevenways.hohenheim.HohenheimSlugs;
import be.elevenways.zenit.auth.AuthKeys;
import be.elevenways.zenit.auth.model.UserModel;
import be.elevenways.zenit.auth.server.ZenitAuth;
import be.elevenways.zenit.common.secret.RevealableSecret;
import be.elevenways.zenit.common.secret.SecretRef;
import be.elevenways.zenit.common.secret.SecretReveals;
import be.elevenways.zenit.cms.common.RecordsCopy;
import be.elevenways.zenit.auth.server.GrantMatrixSnapshots;
import be.elevenways.zenit.auth.server.RecordSharing;
import be.elevenways.zenit.cms.common.page.CmsRoutes;
import be.elevenways.zenit.cms.common.render.action.CmsConfirmation;
import be.elevenways.zenit.cms.test.support.PanelResourceCalls;
import be.elevenways.zenit.cms.server.page.FormConcurrency;
import be.elevenways.zenit.cms.test.support.CmsFormSnapshots;
import be.elevenways.zenit.common.Zenit;
import be.elevenways.zenit.common.operation.Operation;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.security.csrf.CsrfTokens;
import be.elevenways.zenit.common.session.Session;
import be.elevenways.zenit.server.http.SessionCookies;
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
import be.elevenways.protoblast.common.i18n.LocaleChain;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.server.microcopy.ShippedCatalogs;
import be.elevenways.zenit.test.support.FlashHandoff;
import be.elevenways.zenit.test.support.ServedApp;
import be.elevenways.zenit.test.support.ServedAppBinding;
import be.elevenways.zenit.common.orm.datasource.sql.SqlDatasource;
import be.elevenways.hawkeye.common.Hawkeye;
import be.elevenways.zenit.server.flash.Flash;
import be.elevenways.zenit.server.security.SecureTokens;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.IntSupplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Browser test base for Hohenheim. Authenticates via zenit-auth: seeds an admin user and mints
 * a session, injected as the auth cookie so the (zenit-auth-gated) admin pages are reachable.
 *
 * AIDEV-NOTE: every class runs on zenit's served app ({@link HohenheimServedApp#app()}): a database of its own,
 * the JVM's one runtime and one server port (the browser's caches key the bundle by origin), and an admin session
 * seeded over each database, {@link #freshSeededDatabase()} included.
 */
// The tag is @Inherited: every subclass lands in the shared-server gradle bucket (parallel forks, one runtime and one
// server per JVM). Classes that boot the runtime themselves run in browserTestSolo, a JVM per class.
@org.junit.jupiter.api.Tag("shared-server")
public abstract class HohenheimTestBase extends ZenitBrowserTestBase {

    @RegisterExtension
    protected static final ServedApp APP = HohenheimServedApp.app().keepingServerForJvm().with(new AdminSession());

    protected static String sessionToken;
    protected static String csrfToken;
    /** Every session {@link #sessionFor} minted in this JVM, drained with the admin one before each test. */
    private static final Set<String> MINTED_SESSIONS = ConcurrentHashMap.newKeySet();

    @Override
    protected IntSupplier servedPort() {
        return APP::port;
    }

    /** The pages load the lane's bundle, and every class database gets the harness admin and its session. */
    private static final class AdminSession implements ServedAppBinding {

        @Override
        public void bind(@NonNull SqlDatasource database) {
            serveLaneBundle();
            MINTED_SESSIONS.clear();
            sessionToken = seedAuthenticatedAdmin();
        }

        @Override
        public void unbind() {
            MINTED_SESSIONS.clear();
            sessionToken = null;
            csrfToken = null;
        }
    }

    /** The test admin ({@link TenantConduits#operatorUser}) and an active session for it; returns the session id.
     *  Package-visible: isolated boot tests (RoleRestrictedBootTest) reuse it instead of copying. */
    static String seedAuthenticatedAdmin() {
        Row user = TenantConduits.operatorUser();

        Session session = Zenit.getSessionStore().create();
        session.set(AuthKeys.USER_ID, user.get(UserModel.ID));
        csrfToken = ZenitAuth.randomToken();
        session.set(CsrfTokens.TOKEN, csrfToken);   // so direct-POST tests can send X-Csrf-Token
        Zenit.getSessionStore().save(session);
        return session.token().secret();
    }

    /** Moves the class onto a fresh database, seeded with the admin and its session, on the same server. */
    protected static void freshSeededDatabase() {
        APP.renewDatabase();
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

    /**
     * Take the flash toast of an ARBITRARY session (a tenant's, not the admin's) that {@code answer} leads to.
     *
     * AIDEV-NOTE: a redirect's toast is taken by its handoff token ALONE. Flash.take also serves the load the
     * untabbed bucket and every handoff of that bucket older than Flash.HANDOFF_REDEEM_MILLIS (10s), as a real
     * document load would; on a loaded host an earlier action's unread toast ("created") had aged into that fallback
     * and answered for a later action that handed nothing off (SiteCrudTest, archdev). Redeeming under a fresh tab id
     * leaves only the token's own bucket eligible, and a redirect without a token reports that nothing was handed off.
     */
    protected static @Nullable FlashNotice popFlash(@NonNull HttpResponse<?> answer, @NonNull String token) {
        Session session = Zenit.getSessionStore().get(SessionToken.of(token));
        if (session == null) {
            return null;
        }
        String location = answer.headers().firstValue("Location").orElse(null);
        if (location == null) {
            return FlashHandoff.take(session, null);
        }
        String handoff = FlashHandoff.token(location);
        if (handoff == null) {
            return null;
        }
        return Flash.take(FlashHandoff.conduitFor(session, handoff)
            .setRequestHeader(Hawkeye.TAB_ID_HEADER, SecureTokens.randomToken()));
    }

    /** @return the toast's words in {@code languageTag}, resolved from the shipped catalogs the shell reads */
    protected static @NonNull String toastText(@NonNull FlashNotice notice, @NonNull String languageTag) {
        return notice.message().resolve(LocaleChain.ofTags(languageTag), new ShippedCatalogs()::resolveSource);
    }

    /** @return the redirect's Location without its flash handoff token, or "" without a Location */
    protected static @NonNull String landingOf(@NonNull HttpResponse<?> answer) {
        return answer.headers().firstValue("Location").map(FlashHandoff::landing).orElse("");
    }

    /** @return the page header's breadcrumb trail markup ({@code zenitcms:breadcrumbs}), "" for a page without one */
    protected static @NonNull String trailOf(@NonNull String page) {
        int start = page.indexOf("data-cms-trail");
        int end = start < 0 ? -1 : page.indexOf("</pl-breadcrumb>", start);
        return start < 0 || end < 0 ? "" : page.substring(start, end);
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
    protected static TestSession sessionFor(@NonNull UUID userId) {
        Session session = Zenit.getSessionStore().create();
        session.set(AuthKeys.USER_ID, userId);
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
        return SessionCookies.name() + "=" + session;
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

    /** The {@code ?q=} part narrowing a list to a rule expression; a bare word is no expression and narrows nothing. */
    protected static String listQuery(String rule) {
        return "?q=" + URLEncoder.encode(rule, StandardCharsets.UTF_8);
    }

    /** A urlencoded POST as the harness admin, CSRF token included. */
    protected HttpResponse<String> adminPostForm(String path, String body) throws Exception {
        return httpPostForm(path, body, sessionToken, csrfToken);
    }

    /**
     * An EDIT POST as the harness admin, carrying the live {@code cms__snapshot} its edit form renders at {@code path},
     * as the browser posts it: an edit without one is refused as stale and saves nothing.
     */
    protected HttpResponse<String> adminPostEdit(String path, String body) throws Exception {
        return adminPostEdit(path, path, body);
    }

    /** {@link #adminPostEdit(String, String)} for a form rendered at {@code formPath} and posted to {@code path}. */
    protected HttpResponse<String> adminPostEdit(String formPath, String path, String body) throws Exception {
        return adminPostForm(path, withSnapshot(body, snapshotAt(formPath, sessionToken)));
    }

    /** {@link #adminPostEdit(String, String)} as the caller holding {@code session}, its form rendered for that caller. */
    protected HttpResponse<String> httpPostEdit(String path, String body, String session, String csrf)
            throws Exception {
        return httpPostForm(path, withSnapshot(body, snapshotAt(path, session)), session, csrf);
    }

    /**
     * The live {@code cms__snapshot} of the one edit form {@code formPath} renders for {@code session}; a guard posting
     * as a caller the form refuses reads it as the admin, so its refusal is the guard's own, never the stale one.
     */
    protected String snapshotAt(String formPath, String session) throws Exception {
        return CmsFormSnapshots.ofHtml(httpGet(formPath, session).body());
    }

    /** @return the url-encoded {@code body} with {@code snapshot} appended as {@link FormConcurrency#FIELD} */
    protected static String withSnapshot(String body, String snapshot) {
        return (body.isEmpty() ? "" : body + "&") + FormConcurrency.FIELD + "="
            + URLEncoder.encode(snapshot, StandardCharsets.UTF_8);
    }

    /**
     * Asserts an edit answer is NOT the stale-snapshot refusal, so a guard asserting a refused or unchanged write
     * proves its own reason rather than passing on the conflict every snapshot-less edit gets.
     *
     * @return {@code answer}, for the guard's own assertions
     */
    protected static HttpResponse<String> assertNotStale(HttpResponse<String> answer) {
        assertThat(answer.body())
            .as("the edit was judged on its own merits, not refused as a stale snapshot")
            .doesNotContain(ApiSupport.shippedText(RecordsCopy.COPY.of("conflict")));
        return answer;
    }

    /**
     * What the Access tab's Save posts beside its rows: the tab, the confirmation proof and the grid's snapshot of now,
     * which the sharing save requires from every caller (a missing one is the conflict).
     */
    protected static String accessTabFields(Identifier model, Object recordId) {
        return "_tab=access&_confirmed=1&" + GrantMatrixSnapshots.FIELD + "="
            + URLEncoder.encode(RecordSharing.snapshot(model, recordId), StandardCharsets.UTF_8) + "&";
    }

    /** A DRY POST as the harness admin, CSRF token included. */
    protected HttpResponse<String> adminPostDry(String path, String body) throws Exception {
        return httpPostDry(path, body, sessionToken, csrfToken);
    }

    /**
     * Presses a stored secret's reveal as the caller holding {@code session}: the POST the {@code pl-secret-reveal}
     * control sends to core's reveal route, CSRF token included.
     */
    protected HttpResponse<String> revealSecret(@NonNull RevealableSecret secret, int recordId,
                                                @NonNull String session, @NonNull String csrf) throws Exception {
        SecretRef ref = secret.ref(recordId);
        return httpPostForm(SecretReveals.ENDPOINT.with(SecretReveals.SECRET, ref.secret())
            .with(SecretReveals.RECORD, ref.record()).with(SecretReveals.FIELD, ref.field()).toUrl(), "", session,
            csrf);
    }

    /** @return the value a 200 reveal answered, decoded from its DRY body */
    protected static @NonNull String revealedValue(@NonNull HttpResponse<String> answer) {
        assertThat(answer.statusCode()).as("the reveal answered").isEqualTo(200);
        return String.valueOf(Zenit.DRY.parse(answer.body()));
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
        this.navigateToAppAs(path, sessionToken);
    }

    /**
     * {@link #navigateToApp} under another account's session, for a page as a delegate sees it.
     *
     * AIDEV-NOTE: a session swap leaves the open page first, as signOutBrowser does: still signed in as the previous
     * reader, it hears the session change (its live socket, a read refused) and reloads itself, which aborted this
     * navigation (LiveFigureJourneyTest step 6, net::ERR_ABORTED).
     */
    protected void navigateToAppAs(String path, String session) {
        boolean swapped = page.context().cookies().stream()
            .noneMatch(cookie -> SessionCookies.name().equals(cookie.name) && session.equals(cookie.value));
        if (swapped && !page.url().startsWith("about:")) {
            page.navigate("about:blank");
        }
        page.context().addCookies(List.of(
            new Cookie(SessionCookies.name(), session)
                .setDomain("localhost")
                .setPath("/")
        ));
        super.navigateToApp(path);
    }

    /**
     * Opens the ordinary menu of one list row on the page at {@code path} and answers the markup of its items: a row
     * menu draws its items when it first opens, so the served HTML carries its trigger alone.
     *
     * @param rowKey the row's {@code data-row-key}, or null for the first row
     */
    protected String openedRowMenu(String path, String rowKey) {
        navigateToApp(path);
        waitForHydration();
        String row = rowKey == null ? "pl-table-row[data-row-key]" : "pl-table-row[data-row-key='" + rowKey + "']";
        page.locator(row + " .cms-row-action-more pl-dropdown-menu-trigger").first().evaluate("el => el.click()");
        String openItems = "() => [...document.querySelectorAll('.pl-dropdown-menu-content__popup')]"
            + ".filter(popup => popup.getClientRects().length > 0 && popup.querySelector('.cms-menu-action'))";
        page.waitForCondition(() -> Boolean.TRUE.equals(page.evaluate("() => (" + openItems + ")().length > 0")));
        return (String) page.evaluate("() => (" + openItems + ")().map(popup => popup.innerHTML).join('')");
    }
}
