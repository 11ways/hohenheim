package be.elevenways.hohenheim.test;

import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.hohenheim.model.AccessListModel;
import be.elevenways.hohenheim.model.ReleasedRouteClaimModel;
import be.elevenways.hohenheim.model.SiteAuthProviderModel;
import be.elevenways.hohenheim.model.SiteDomainModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.hohenheim.server.auth.types.BasicAuthProviderType;
import be.elevenways.hohenheim.server.proxy.ProxyServer;
import be.elevenways.zenit.auth.AuthKeys;
import be.elevenways.zenit.auth.model.GrantSubjectType;
import be.elevenways.zenit.auth.model.UserModel;
import be.elevenways.zenit.auth.server.AuthCookieSupport;
import be.elevenways.zenit.auth.server.AuthModels;
import be.elevenways.zenit.auth.server.RecordGrants;
import be.elevenways.zenit.auth.server.ZenitAuth;
import be.elevenways.zenit.common.Zenit;
import be.elevenways.zenit.common.orm.behaviour.RevisionableBehaviour;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.security.csrf.CsrfTokens;
import be.elevenways.zenit.common.session.Session;
import be.elevenways.zenit.common.validation.Violation;
import be.elevenways.zenit.common.validation.Violations;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Phase 0 / 0.9 route-ownership gate: the framework's generic revision-restore
 * endpoint bypasses the resource-layer enable checks, so a delegated tenant could
 * restore a formerly-enabled snapshot after another site took the hostname and
 * seize the route. The enable invariant now lives in the SiteModel write pipeline,
 * which restore's model.save funnels through. This walks the exact attack through
 * the real /manage and /admin restore routes and proves the seizure is refused.
 */
class RevisionRestoreTakeoverTest extends HohenheimTestBase {

    private static final String CONTESTED_HOST = "restore-takeover.example.com";

    private HttpResponse<String> post(String path, String body, String session, String csrf)
            throws Exception {
        HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:" + getServerPort() + path))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .header("Cookie", AuthCookieSupport.sessionCookieName() + "=" + session)
            .header("X-Csrf-Token", csrf)
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    /** Persist an active site of the static type; enabled per the flag. */
    private static Row site(String name, String slug, boolean enabled) {
        var siteModel = Models.get(SiteModel.class);
        Row row = siteModel.createEmptyRow();
        row.set(SiteModel.NAME, name);
        row.set(SiteModel.SLUG, slug);
        row.set(SiteModel.UPSTREAM_KIND, "hohenheim:static");
        row.set(SiteModel.SETTINGS, Map.of("root_path", "/tmp"));
        row.set(SiteModel.STATUS, "active");
        row.set(SiteModel.ENABLED, enabled);
        siteModel.save(row);
        return row;
    }

    private static void domain(Row s, String hostname) {
        var domainModel = Models.get(SiteDomainModel.class);
        Row d = domainModel.createEmptyRow();
        d.set(SiteDomainModel.SITE_ID, s.get(SiteModel.ID));
        d.set(SiteDomainModel.HOSTNAME, hostname);
        d.set(SiteDomainModel.MATCH_TYPE, SiteDomainModel.MATCH_EXACT);
        d.set(SiteDomainModel.FORCE_SSL, false);
        domainModel.save(d);
    }

    /**
     * The route-ownership set the proxy's RouteTable is built from: enabled,
     * non-deleted sites (SiteDispatcher.reload reads siteModel.findEnabled()) that
     * claim the hostname. Counting owners -- not resolving one -- is what reveals a
     * takeover, because SiteDispatcher resolves FIRST-WINS and would silently hide
     * the second owner.
     */
    private static long enabledOwnersOf(String hostname) {
        var domainModel = Models.get(SiteDomainModel.class);
        long owners = 0;
        for (Row s : Models.get(SiteModel.class).findEnabled()) {
            for (Row d : domainModel.findBySiteId(s.get(SiteModel.ID))) {
                String canonical = SiteDomainModel.canonicalHostname(
                    d.get(SiteDomainModel.HOSTNAME), d.get(SiteDomainModel.MATCH_TYPE));
                if (hostname.equalsIgnoreCase(canonical)) {
                    owners++;
                    break;
                }
            }
        }
        return owners;
    }

    /** The revision number whose snapshot has the site enabled. */
    private static int enabledRevisionOf(Model siteModel, int siteId) {
        RevisionableBehaviour rev = SiteModel.REVISIONABLE;
        int latest = rev.latestRevisionOf(siteModel, siteId);
        for (int n = 1; n <= latest; n++) {
            if (Boolean.TRUE.equals(rev.snapshotOf(siteModel, siteId, n).get("enabled"))) {
                return n;
            }
        }
        throw new AssertionError("no enabled revision found for site " + siteId);
    }

    private static boolean hasEnableConflictViolation(Violations violations) {
        for (Violation v : violations.all()) {
            if ("enabled".equals(v.fieldName())
                    && "enable_route_conflict".equals(v.message().key())) {
                return true;
            }
        }
        return false;
    }

    @Test
    void restoringAFormerlyEnabledRevisionCannotSeizeAnotherSitesHostname() throws Exception {
        Model siteModel = Models.get(SiteModel.class);

        // 1. Site A once owned the contested hostname while enabled: that save is A's
        //    enabled revision, the snapshot the attacker will later replay.
        Row siteA = site("Restore Attacker A", "restore-attacker-a", true);
        int aId = siteA.get(SiteModel.ID);
        domain(siteA, CONTESTED_HOST);
        int enabledRevA = enabledRevisionOf(siteModel, aId);

        // 2. A is disabled -- disabled sites are exempt from the cross-site route check,
        //    which is exactly what lets somebody else claim the hostname next.
        siteA.set(SiteModel.ENABLED, false);
        siteModel.save(siteA);
        assertThat((Boolean) siteModel.findById(aId).get(SiteModel.ENABLED))
            .as("A is now disabled").isFalse();

        // 3. Site B goes live on the same hostname; with A disabled there is no conflict.
        Row siteB = site("Restore Victim B", "restore-victim-b", true);
        int bId = siteB.get(SiteModel.ID);
        domain(siteB, CONTESTED_HOST);
        assertThat(enabledOwnersOf(CONTESTED_HOST))
            .as("exactly one enabled owner of the hostname: B").isEqualTo(1);

        // 4. A delegated operator is granted MANAGE over A (a tenant's own staged site).
        Row operator = AuthModels.users().createEmptyRow();
        operator.set(UserModel.EMAIL, "restore-operator@hohenheim.local");
        operator.set(UserModel.DISPLAY_NAME, "Restore Operator");
        operator.set(UserModel.ENABLED, true);
        operator.set(UserModel.CREATED_AT, Instant.now());
        operator.set(UserModel.UPDATED_AT, Instant.now());
        AuthModels.users().save(operator);
        int operatorId = operator.get(UserModel.ID);
        Session operatorSession = Zenit.getSessionStore().create();
        operatorSession.set(AuthKeys.USER_ID, (long) operatorId);
        String operatorCsrf = ZenitAuth.randomToken();
        operatorSession.set(CsrfTokens.TOKEN, operatorCsrf);
        Zenit.getSessionStore().save(operatorSession);
        RecordGrants.grant(GrantSubjectType.USER, operatorId, SiteModel.MODEL_ID, aId, HohenheimAccess.MANAGE, true);

        try {
            // 5. The attack: the tenant POSTs the real /manage revision-restore route to
            //    replay A's enabled snapshot. It must be REFUSED, not a 500 -- A stays
            //    disabled and the hostname keeps its single owner (B).
            HttpResponse<String> manageRestore = post(
                "/manage/sites/" + aId + "/revision/" + enabledRevA + "/restore",
                confirmed(""), operatorSession.token().secret(), operatorCsrf);
            // 404, not a redirect: ManageSiteResource.subpages() deliberately omits the
            // revision history, and the revision ROUTES are now bound to that
            // declaration (they used to consult the model's behaviour alone and served
            // the delegated tenant regardless). That is a STRICTLY stronger refusal
            // than the write-pipeline one this test originally pinned -- the request
            // never reaches the model. The invariant itself is still proven at step 6
            // through /admin, which does offer the subpage.
            assertThat(manageRestore.statusCode())
                .as("the delegated surface does not offer revision restore at all")
                .isEqualTo(404);
            assertThat((Boolean) siteModel.findById(aId).get(SiteModel.ENABLED))
                .as("A stays disabled after the /manage restore attempt").isFalse();
            assertThat(enabledOwnersOf(CONTESTED_HOST))
                .as("the hostname still has exactly one owner after the /manage attempt")
                .isEqualTo(1);

            // 6. /admin reaches the same generic endpoint; it is refused identically.
            HttpResponse<String> adminRestore = post(
                "/admin/sites/" + aId + "/revision/" + enabledRevA + "/restore",
                confirmed(""), sessionToken, csrfToken);
            assertThat(adminRestore.statusCode())
                .as("the /admin restore is refused, never a server error")
                .isIn(200, 302, 303);
            assertThat((Boolean) siteModel.findById(aId).get(SiteModel.ENABLED))
                .as("A stays disabled after the /admin restore attempt").isFalse();
            assertThat(enabledOwnersOf(CONTESTED_HOST))
                .as("the hostname still has exactly one owner after the /admin attempt")
                .isEqualTo(1);

            // 7. The refusal is the SPECIFIC enable invariant, not a generic failure:
            //    the write pipeline every restore funnels through throws the
            //    enable_route_conflict violation on the enabled field.
            Row replay = siteModel.findById(aId);
            replay.set(SiteModel.ENABLED, true);
            assertThatThrownBy(() -> siteModel.save(replay))
                .as("the write pipeline refuses the disabled->enabled transition")
                .isInstanceOfSatisfying(Violations.class, violations ->
                    assertThat(hasEnableConflictViolation(violations))
                        .as("refusal carries the enable_route_conflict violation on 'enabled'")
                        .isTrue());
            assertThat((Boolean) siteModel.findById(aId).get(SiteModel.ENABLED))
                .as("the refused direct save left A disabled").isFalse();

            // 8. Positive control: once B stands down, the SAME restore succeeds -- so the
            //    refusals above were the conflict invariant, not a broken request or a
            //    missing permission. A takes the hostname and is again its sole owner.
            siteB.set(SiteModel.ENABLED, false);
            siteModel.save(siteB);

            // 8a. B standing down RELEASES the hostname, and a released hostname is not a
            //     free hostname (ReleasedClaims): B was operator-owned, A carries a tenant
            //     manage grant, so the restore is still refused -- now by the quarantine
            //     tier rather than the live-conflict tier. Prove that, then lift the
            //     quarantine the way an administrator does, so step 8's positive control
            //     still proves what it claims.
            HttpResponse<String> quarantinedRestore = post(
                "/admin/sites/" + aId + "/revision/" + enabledRevA + "/restore",
                confirmed(""), sessionToken, csrfToken);
            assertThat(quarantinedRestore.statusCode())
                .as("the quarantined restore is refused, never a server error")
                .isIn(200, 302, 303);
            assertThat((Boolean) siteModel.findById(aId).get(SiteModel.ENABLED))
                .as("a different owner cannot restore onto a just-released hostname").isFalse();
            Models.get(ReleasedRouteClaimModel.class).find().delete();

            HttpResponse<String> cleanRestore = post(
                "/admin/sites/" + aId + "/revision/" + enabledRevA + "/restore",
                confirmed(""), sessionToken, csrfToken);
            assertThat(cleanRestore.statusCode())
                .as("with the conflict gone the restore goes through")
                .isIn(200, 302, 303);
            assertThat((Boolean) siteModel.findById(aId).get(SiteModel.ENABLED))
                .as("A is enabled again once nobody else owns the hostname").isTrue();
            assertThat(enabledOwnersOf(CONTESTED_HOST))
                .as("A is now the single owner of the hostname").isEqualTo(1);
        } finally {
            RecordGrants.revoke(GrantSubjectType.USER, operatorId, SiteModel.MODEL_ID, aId, HohenheimAccess.MANAGE);
            var domainModel = Models.get(SiteDomainModel.class);
            for (Row d : domainModel.findBySiteId(aId)) domainModel.delete(d);
            for (Row d : domainModel.findBySiteId(bId)) domainModel.delete(d);
            siteModel.delete(siteA);
            siteModel.delete(siteB);
            // Tearing live sites down IS a release, so the fixture ledgers quarantine rows
            // that would otherwise refuse another test class's claim on these hostnames.
            Models.get(ReleasedRouteClaimModel.class).find().delete();
        }
    }

    private static final String GATED_HOST = "restore-gate.example.com";

    /**
     * The takeover the read-only audit named: a delegated tenant replaying a revision
     * that predates the operator's auth gate. {@code access_list_id} and
     * {@code auth_provider_id} are plain IntegerFields -- not secret, not encrypted,
     * not lifecycle -- so they ride every snapshot, while ManageSiteResource offers
     * exactly three ALWAYS_EDITABLE form fields. This walks the attack through the real
     * HTTP routes AND the live proxy, so both the DB write and the served effect are
     * settled.
     */
    @Test
    void aDelegatedTenantCannotRewindTheSitesAuthGate() throws Exception {
        Model siteModel = Models.get(SiteModel.class);
        var providerModel = Models.get(SiteAuthProviderModel.class);

        HttpServer upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        upstream.createContext("/", ex -> {
            byte[] body = "gated-upstream-ok".getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        upstream.start();

        Row provider = providerModel.createEmptyRow();
        provider.set(SiteAuthProviderModel.NAME, "Restore Gate");
        provider.set(SiteAuthProviderModel.PROVIDER_TYPE, "hohenheim:basic");
        provider.set(SiteAuthProviderModel.CONFIG, new BasicAuthProviderType()
            .normalizeConfigForSave(Map.of("credentials", Map.of("alice", "s3cret")), null));
        providerModel.save(provider);
        int providerId = provider.get(SiteAuthProviderModel.ID);

        Row accessList = Models.get(AccessListModel.class).createEmptyRow();
        accessList.set(AccessListModel.NAME, "Restore Gate Allowlist");
        Models.get(AccessListModel.class).save(accessList);
        int accessListId = accessList.get(AccessListModel.ID);

        // 1. The tenant's site goes live UNGATED: that save is the revision the
        //    attacker will later replay.
        Row site = siteModel.createEmptyRow();
        site.set(SiteModel.NAME, "Restore Gate Victim");
        site.set(SiteModel.SLUG, "restore-gate-victim");
        site.set(SiteModel.UPSTREAM_KIND, "hohenheim:address");
        site.set(SiteModel.SETTINGS, Map.of("forward_host", "127.0.0.1",
            "forward_port", upstream.getAddress().getPort()));
        site.set(SiteModel.STATUS, "active");
        site.set(SiteModel.ENABLED, true);
        // Explicit nulls, exactly like the admin create form submits them: a snapshot
        // only carries keys the saved row actually HAD (Row.has), so a column never
        // touched at creation is absent from history and cannot be rewound at all.
        // The gate columns must be present-and-null for the pre-gate revision to mean
        // "ungated" -- which is the real-world shape and the attackable one.
        site.set(SiteModel.AUTH_PROVIDER_ID, null);
        site.set(SiteModel.ACCESS_LIST_ID, null);
        siteModel.save(site);
        int siteId = site.get(SiteModel.ID);
        int ungatedRevision = SiteModel.REVISIONABLE.latestRevisionOf(siteModel, siteId);

        var domainModel = Models.get(SiteDomainModel.class);
        Row domain = domainModel.createEmptyRow();
        domain.set(SiteDomainModel.SITE_ID, siteId);
        domain.set(SiteDomainModel.HOSTNAME, GATED_HOST);
        domain.set(SiteDomainModel.MATCH_TYPE, SiteDomainModel.MATCH_EXACT);
        domain.set(SiteDomainModel.FORCE_SSL, false);
        domainModel.save(domain);

        // 2. The OPERATOR then puts the gate on: basic auth plus an IP allowlist.
        site.set(SiteModel.AUTH_PROVIDER_ID, providerId);
        site.set(SiteModel.ACCESS_LIST_ID, accessListId);
        siteModel.save(site);

        Row operator = AuthModels.users().createEmptyRow();
        operator.set(UserModel.EMAIL, "restore-gate-operator@hohenheim.local");
        operator.set(UserModel.DISPLAY_NAME, "Restore Gate Operator");
        operator.set(UserModel.ENABLED, true);
        operator.set(UserModel.CREATED_AT, Instant.now());
        operator.set(UserModel.UPDATED_AT, Instant.now());
        AuthModels.users().save(operator);
        int operatorId = operator.get(UserModel.ID);
        Session tenantSession = Zenit.getSessionStore().create();
        tenantSession.set(AuthKeys.USER_ID, (long) operatorId);
        String tenantCsrf = ZenitAuth.randomToken();
        tenantSession.set(CsrfTokens.TOKEN, tenantCsrf);
        Zenit.getSessionStore().save(tenantSession);
        RecordGrants.grant(GrantSubjectType.USER, operatorId, SiteModel.MODEL_ID, siteId,
            HohenheimAccess.MANAGE, true);

        HohenheimSettings.VALUES.setValue(HohenheimSettings.Proxy.HTTP_PORT, 0);
        ProxyServer proxy = new ProxyServer();
        proxy.start();
        int proxyPort = ((InetSocketAddress) proxy.getHttpListenerInfo().getAddress()).getPort();

        try {
            // 3. The gate is genuinely live on the wire: an unauthenticated request to
            //    the site's own hostname is challenged, not served.
            assertThat(proxyStatus(proxyPort, GATED_HOST))
                .as("step 3: the operator's basic-auth gate answers the open internet")
                .isEqualTo(401);

            // 4. THE ATTACK: an ordinary manage grant replays the pre-gate revision.
            HttpResponse<String> attack = post(
                "/manage/sites/" + siteId + "/revision/" + ungatedRevision + "/restore",
                confirmed(""), tenantSession.token().secret(), tenantCsrf);

            // 5. The SERVED effect first, because that is what the attack is FOR: the
            //    hostname must still be challenged. A refusal that left the site
            //    reachable would be no refusal at all.
            proxy.reload();
            assertThat(proxyStatus(proxyPort, GATED_HOST))
                .as("step 5: the gate is still up on the wire after the attack")
                .isEqualTo(401);

            // 6. And the DB write that must not have happened. These are the two
            //    columns the delegated form never offers, and they are what the gate IS.
            Row afterAttack = siteModel.findById(siteId);
            assertThat((Integer) afterAttack.get(SiteModel.AUTH_PROVIDER_ID))
                .as("step 6: the tenant must not be able to null the site's auth provider")
                .isEqualTo(providerId);
            assertThat((Integer) afterAttack.get(SiteModel.ACCESS_LIST_ID))
                .as("step 6: the tenant must not be able to null the site's IP allowlist")
                .isEqualTo(accessListId);
            assertThat(attack.statusCode())
                .as("step 6: the delegated surface offers no revision route at all")
                .isEqualTo(404);

            // 7. Positive anchor: an ADMIN restoring the SAME revision through /admin --
            //    a surface that DOES offer the revisions subpage and whose form exposes
            //    both columns -- still succeeds, and the effect is real. So step 5
            //    refused for the declared reason, not because restore stopped working.
            HttpResponse<String> adminRestore = post(
                "/admin/sites/" + siteId + "/revision/" + ungatedRevision + "/restore",
                confirmed(""), sessionToken, csrfToken);
            assertThat(adminRestore.statusCode())
                .as("step 7: the admin restore goes through")
                .isIn(200, 302, 303);
            Row afterAdmin = siteModel.findById(siteId);
            assertThat((Integer) afterAdmin.get(SiteModel.AUTH_PROVIDER_ID))
                .as("step 7: the admin restore really did rewind the auth provider")
                .isNull();
            assertThat((Integer) afterAdmin.get(SiteModel.ACCESS_LIST_ID))
                .as("step 7: the admin restore really did rewind the access list")
                .isNull();

            proxy.reload();
            assertThat(proxyStatus(proxyPort, GATED_HOST))
                .as("step 7: with the gate rewound by an admin, the hostname serves openly")
                .isEqualTo(200);
        } finally {
            proxy.stop();
            upstream.stop(0);
            RecordGrants.revoke(GrantSubjectType.USER, operatorId, SiteModel.MODEL_ID, siteId,
                HohenheimAccess.MANAGE);
            for (Row d : domainModel.findBySiteId(siteId)) domainModel.delete(d);
            siteModel.delete(site);
            providerModel.delete(provider);
            Models.get(AccessListModel.class).delete(accessList);
            Models.get(ReleasedRouteClaimModel.class).find().delete();
        }
    }

    /** Status code of an unauthenticated GET through the proxy for one Host header. */
    private static int proxyStatus(int port, String host) throws Exception {
        try (Socket socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(5000);
            String request = "GET / HTTP/1.1\r\nHost: " + host + "\r\nConnection: close\r\n\r\n";
            socket.getOutputStream().write(request.getBytes(StandardCharsets.UTF_8));
            socket.getOutputStream().flush();

            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            InputStream in = socket.getInputStream();
            byte[] chunk = new byte[4096];
            int read;
            while ((read = in.read(chunk)) != -1) {
                buffer.write(chunk, 0, read);
            }
            String response = buffer.toString(StandardCharsets.UTF_8);
            int firstSpace = response.indexOf(' ');
            return Integer.parseInt(response.substring(firstSpace + 1, firstSpace + 4));
        }
    }
}
