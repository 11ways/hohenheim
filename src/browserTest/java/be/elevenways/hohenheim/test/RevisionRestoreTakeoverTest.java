package be.elevenways.hohenheim.test;

import be.elevenways.hohenheim.model.ReleasedRouteClaimModel;
import be.elevenways.hohenheim.model.SiteDomainModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.zenit.auth.AuthKeys;
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
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
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
        row.set(SiteModel.SITE_TYPE, "hohenheim:static");
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
        RecordGrants.grant("user", operatorId, SiteModel.MODEL_ID, aId, HohenheimAccess.MANAGE, true);

        try {
            // 5. The attack: the tenant POSTs the real /manage revision-restore route to
            //    replay A's enabled snapshot. It must be REFUSED, not a 500 -- A stays
            //    disabled and the hostname keeps its single owner (B).
            HttpResponse<String> manageRestore = post(
                "/manage/sites/" + aId + "/revision/" + enabledRevA + "/restore",
                "", operatorSession.id(), operatorCsrf);
            assertThat(manageRestore.statusCode())
                .as("the /manage restore is refused, never a server error")
                .isIn(200, 302, 303);
            assertThat((Boolean) siteModel.findById(aId).get(SiteModel.ENABLED))
                .as("A stays disabled after the /manage restore attempt").isFalse();
            assertThat(enabledOwnersOf(CONTESTED_HOST))
                .as("the hostname still has exactly one owner after the /manage attempt")
                .isEqualTo(1);

            // 6. /admin reaches the same generic endpoint; it is refused identically.
            HttpResponse<String> adminRestore = post(
                "/admin/sites/" + aId + "/revision/" + enabledRevA + "/restore",
                "", sessionToken, csrfToken);
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
                "", sessionToken, csrfToken);
            assertThat(quarantinedRestore.statusCode())
                .as("the quarantined restore is refused, never a server error")
                .isIn(200, 302, 303);
            assertThat((Boolean) siteModel.findById(aId).get(SiteModel.ENABLED))
                .as("a different owner cannot restore onto a just-released hostname").isFalse();
            Models.get(ReleasedRouteClaimModel.class).find().delete();

            HttpResponse<String> cleanRestore = post(
                "/admin/sites/" + aId + "/revision/" + enabledRevA + "/restore",
                "", sessionToken, csrfToken);
            assertThat(cleanRestore.statusCode())
                .as("with the conflict gone the restore goes through")
                .isIn(200, 302, 303);
            assertThat((Boolean) siteModel.findById(aId).get(SiteModel.ENABLED))
                .as("A is enabled again once nobody else owns the hostname").isTrue();
            assertThat(enabledOwnersOf(CONTESTED_HOST))
                .as("A is now the single owner of the hostname").isEqualTo(1);
        } finally {
            RecordGrants.revoke("user", operatorId, SiteModel.MODEL_ID, aId, HohenheimAccess.MANAGE);
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
}
