package be.elevenways.hohenheim.test;

import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.zenit.auth.AuthKeys;
import be.elevenways.zenit.auth.model.GrantSubjectType;
import be.elevenways.zenit.auth.model.RecordGrantModel;
import be.elevenways.zenit.auth.model.UserModel;
import be.elevenways.zenit.auth.model.UserPrincipal;
import be.elevenways.zenit.auth.server.AuthCookieSupport;
import be.elevenways.zenit.auth.server.AuthModels;
import be.elevenways.zenit.auth.server.RecordGrants;
import be.elevenways.zenit.auth.server.ZenitAuth;
import be.elevenways.zenit.common.Zenit;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.security.csrf.CsrfTokens;
import be.elevenways.zenit.common.session.Session;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.net.http.WebSocketHandshakeException;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Per-site access control: a non-admin principal is refused everywhere until it
 * holds a manage grant on a specific site, admin-only installation endpoints
 * refuse non-admins outright, and the terminal WebSocket enforces both login
 * (handshake 401) and per-site authorization (policy close).
 */
class SiteAccessControlTest extends HohenheimTestBase {

    private static Integer siteAId;
    private static Integer siteBId;
    private static Integer limitedUserId;
    private static String limitedSession;
    private static String limitedCsrf;

    @BeforeAll
    static void seedSitesAndLimitedUser() {
        var siteModel = Models.get(SiteModel.class);

        Row siteA = siteModel.createEmptyRow();
        siteA.set(SiteModel.NAME, "Access Site A");
        siteA.set(SiteModel.SLUG, "access-site-a");
        siteA.set(SiteModel.SITE_TYPE, "hohenheim:static");
        siteA.set(SiteModel.SETTINGS, Map.of("root_path", "/tmp"));
        siteA.set(SiteModel.STATUS, "active");
        siteA.set(SiteModel.ENABLED, true);
        siteModel.save(siteA);
        siteAId = siteA.get(SiteModel.ID);

        Row siteB = siteModel.createEmptyRow();
        siteB.set(SiteModel.NAME, "Access Site B");
        siteB.set(SiteModel.SLUG, "access-site-b");
        siteB.set(SiteModel.SITE_TYPE, "hohenheim:static");
        siteB.set(SiteModel.SETTINGS, Map.of("root_path", "/tmp"));
        siteB.set(SiteModel.STATUS, "active");
        siteB.set(SiteModel.ENABLED, true);
        siteModel.save(siteB);
        siteBId = siteB.get(SiteModel.ID);

        // A dedicated NON-admin user with its own session; the shared admin
        // session stays untouched.
        Row user = AuthModels.users().createEmptyRow();
        user.set(UserModel.EMAIL, "limited@hohenheim.local");
        user.set(UserModel.DISPLAY_NAME, "Limited User");
        user.set(UserModel.ENABLED, true);
        user.set(UserModel.CREATED_AT, Instant.now());
        user.set(UserModel.UPDATED_AT, Instant.now());
        AuthModels.users().save(user);
        limitedUserId = user.get(UserModel.ID);

        Session session = Zenit.getSessionStore().create();
        session.set(AuthKeys.USER_ID, limitedUserId.longValue());
        limitedCsrf = ZenitAuth.randomToken();
        session.set(CsrfTokens.TOKEN, limitedCsrf);
        Zenit.getSessionStore().save(session);
        limitedSession = session.token().secret();
    }

    private HttpResponse<String> limitedGet(String path) throws Exception {
        HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:" + port() + path))
            .header("Cookie", AuthCookieSupport.sessionCookieName() + "=" + limitedSession)
            .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> limitedPost(String path) throws Exception {
        HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:" + port() + path))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .header("Cookie", AuthCookieSupport.sessionCookieName() + "=" + limitedSession)
            .header("X-Csrf-Token", limitedCsrf)
            .POST(HttpRequest.BodyPublishers.ofString(""))
            .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private int port() {
        return getServerPort();
    }

    /**
     * A bare 403 cannot say WHO refused: CsrfMiddleware (weight 25) runs ahead of BOTH
     * authorization tiers -- zenit-auth's path baselines (weight 30) and core's endpoint
     * permissions (weight 50) -- and every one of them answers 403, so an authorization
     * regression would still "pass" whenever CSRF happened to reject first. The BODY is the
     * discriminator, and authorization has exactly two shapes here: the JSON ErrorResponse
     * with code FORBIDDEN (this client sends no Accept header, so acceptsHtml() is false),
     * and zenit-auth's RENDERED forbidden page, which a path baseline returns as a template
     * result without consulting Accept at all. CSRF can produce neither -- it always ends as
     * an ErrorResult carrying CSRF_INVALID / CSRF_ORIGIN / CSRF_NO_STORE.
     */
    private static void assertAuthorizationRefusal(HttpResponse<String> response, String step) {
        assertThat(response.statusCode()).describedAs(step + ": refused with 403").isEqualTo(403);
        assertThat(response.body())
            .describedAs(step + ": and the refusal is AUTHORIZATION's, never an earlier CSRF"
                + " rejection wearing the same status")
            .satisfiesAnyOf(
                body -> assertThat(body).contains("\"code\":\"FORBIDDEN\""),
                body -> assertThat(body).contains("zenitauth:auth/forbidden"));
    }

    /**
     * One continuous journey: the limited principal is refused everywhere, a manage grant on
     * site A unlocks only site A over HTTP and the terminal socket, and then trashing site A
     * withdraws that authority for good -- restoring it hands nothing back.
     *
     * AIDEV-NOTE: this WAS two @Order-coupled methods over shared @BeforeAll statics with no
     * per-test reset, so step 8 consumed the grant step 5 created. That is one journey wearing
     * two names, and it split a single defect into two failures. The 54 other browserTest
     * classes using OrderAnnotation are a house convention, not a target.
     */
    @Test
    void manageGrantJourney() throws Exception {
        // 1. Per-site endpoints refuse a principal holding no grant on the site.
        assertAuthorizationRefusal(limitedPost("/sites/" + siteAId + "/deploy"),
            "step 1: deploy on an ungranted site");
        assertAuthorizationRefusal(limitedPost("/sites/" + siteAId + "/rollback"),
            "step 1: rollback on an ungranted site");
        assertAuthorizationRefusal(limitedPost("/sites/" + siteAId + "/processes/start"),
            "step 1: process start on an ungranted site");

        // 2. Installation-scoped sensitive endpoints are admin-only.
        assertAuthorizationRefusal(limitedGet("/certificates/1/download"),
            "step 2: a certificate download is installation-scoped");
        assertAuthorizationRefusal(limitedPost("/admin/certificates-request"),
            "step 2: so is requesting a certificate");

        // AIDEV-NOTE: the managed-database dump is deliberately NOT admin-only any more
        // (ccd1bd5): it is requiresLogin and answers to the per-database capability, and
        // its URL is keyed by NAME, so absence and refusal are ONE answer -- a 403 here
        // would confirm which database names exist to any logged-in caller. What this
        // journey pins is that identity plus the state: refused, and no dump on the wire.
        HttpResponse<String> dump = limitedGet("/databases/somedb/backup");
        assertThat(dump.statusCode())
            .as("step 3: a database the caller holds no capability on is MISSING, never forbidden")
            .isEqualTo(404);
        assertThat(dump.headers().firstValue("Content-Disposition"))
            .as("step 3: and no dump crossed the wire")
            .isEmpty();
        assertThat(dump.body())
            .as("step 3: and the refusal never echoes the requested name back")
            .doesNotContain("somedb");

        // 4. The grant that changes everything below.
        RecordGrants.grant(GrantSubjectType.USER, limitedUserId, SiteModel.MODEL_ID, siteAId,
            HohenheimAccess.MANAGE, true);

        // 5. Site A passes authorization: no git source configured, so the handler falls
        //    through to its redirect -- the point is it no longer 403s. These two are also
        //    the control for step 1: the SAME client, the SAME CSRF token, now accepted.
        assertThat(limitedPost("/sites/" + siteAId + "/deploy").statusCode())
            .describedAs("step 5: deploy passes once the grant exists")
            .isIn(302, 303);
        assertThat(limitedPost("/sites/" + siteAId + "/processes/start").statusCode())
            .describedAs("step 5: and so does starting a process")
            .isIn(302, 303);

        // 6. The grant is per RECORD: site B stays refused.
        assertAuthorizationRefusal(limitedPost("/sites/" + siteBId + "/deploy"),
            "step 6: a grant on site A unlocks nothing on site B");

        // 7. The policy's principal-facing views agree with the wire.
        UserPrincipal principal = new UserPrincipal(limitedUserId, "Limited User");
        assertThat(HohenheimAccess.managedSiteIds(principal))
            .describedAs("step 7: exactly the granted site is managed")
            .containsExactly(siteAId);
        assertThat(HohenheimAccess.canManageSite(principal, siteAId))
            .describedAs("step 7: site A is manageable").isTrue();
        assertThat(HohenheimAccess.canManageSite(principal, siteBId))
            .describedAs("step 7: site B is not").isFalse();

        // 8. The terminal socket refuses an anonymous handshake outright.
        HttpClient anonymous = HttpClient.newHttpClient();
        try {
            anonymous.newWebSocketBuilder()
                .buildAsync(URI.create("ws://localhost:" + port() + "/ws/terminal/" + siteBId + "/1"),
                    new WebSocket.Listener() {})
                .get(15, TimeUnit.SECONDS);
            throw new AssertionError("Anonymous terminal handshake was accepted");
        } catch (Exception e) {
            Throwable cause = e instanceof CompletionException || e.getCause() != null ? e.getCause() : e;
            assertThat(cause).isInstanceOf(WebSocketHandshakeException.class);
            assertThat(((WebSocketHandshakeException) cause).getResponse().statusCode()).isEqualTo(401);
        }

        CompletableFuture<Integer> closeCode = new CompletableFuture<>();
        WebSocket.Listener listener = new WebSocket.Listener() {
            @Override
            public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
                closeCode.complete(statusCode);
                return null;
            }
        };

        HttpClient client = HttpClient.newHttpClient();
        // Site B: authenticated (handshake passes requiresLogin) but no grant.
        WebSocket socket = client.newWebSocketBuilder()
            .header("Cookie", AuthCookieSupport.sessionCookieName() + "=" + limitedSession)
            .buildAsync(URI.create("ws://localhost:" + port() + "/ws/terminal/" + siteBId + "/1"), listener)
            .get(15, TimeUnit.SECONDS);
        socket.request(10);

        assertThat(closeCode.get(15, TimeUnit.SECONDS))
            .describedAs("step 8: an authenticated socket on an ungranted site closes on policy")
            .isEqualTo(1008);

        // AIDEV-NOTE: from here the journey turns to trashing. Sites soft-delete by HAND
        // (SiteResource stamps deleted_at without SoftDeleteBehaviour), so the row stays
        // physically present. The framework's presence-only liveness therefore counted a
        // trashed site as a live grant target: its grants survived the orphan sweep and came
        // back on restore. The declaration's liveWhen predicate is what makes deleted_at mean
        // dead to zenit-auth as well.
        var siteModel = Models.get(SiteModel.class);

        // 9. Trash site A the way the admin resource does it.
        Row siteA = siteModel.find().where(SiteModel.ID.eq(siteAId)).first();
        siteA.set(SiteModel.DELETED_AT, Instant.now());
        siteModel.save(siteA);

        assertThat(siteModel.find().where(SiteModel.ID.eq(siteAId)).count())
            .describedAs("step 9: the trashed row must still be physically present")
            .isEqualTo(1);
        assertThat(HohenheimAccess.canManageSite(principal, siteAId))
            .describedAs("step 9: a trashed site must hold no authority")
            .isFalse();

        // 10. A grant cannot be planted on a trashed site either -- the SAME liveness
        //     definition guards the write path.
        assertThatThrownBy(() -> RecordGrants.grant(GrantSubjectType.USER, limitedUserId, SiteModel.MODEL_ID,
                siteAId, HohenheimAccess.MANAGE, true))
            .describedAs("step 10: a trashed site is not a grant target")
            .isInstanceOf(IllegalArgumentException.class);

        // 11. RESTORE: clearing deleted_at must not resurrect the withdrawn authority.
        Row restored = siteModel.find().where(SiteModel.ID.eq(siteAId)).first();
        restored.set(SiteModel.DELETED_AT, null);
        siteModel.save(restored);

        assertThat(HohenheimAccess.canManageSite(principal, siteAId))
            .describedAs("step 11: restoring a site must not revive the grants its delete withdrew")
            .isFalse();
        assertThat(HohenheimAccess.managedSiteIds(principal))
            .describedAs("step 11: and the principal manages nothing again")
            .isEmpty();

        // 12. The site is grantable again now that it is live, so the refusal was about
        //     liveness and not about the site being permanently poisoned.
        assertThat(RecordGrants.grant(GrantSubjectType.USER, limitedUserId, SiteModel.MODEL_ID, siteAId,
            HohenheimAccess.MANAGE, true).get(RecordGrantModel.VALUE))
            .describedAs("step 12: a live site accepts a grant again")
            .isTrue();
        assertThat(HohenheimAccess.canManageSite(principal, siteAId))
            .describedAs("step 12: and the authority is back")
            .isTrue();
    }
}
