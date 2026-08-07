package be.elevenways.hohenheim.server.tls;

import be.elevenways.hohenheim.model.CertificateModel;
import be.elevenways.hohenheim.model.SiteDomainModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.server.ServerMain;
import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.hohenheim.server.proxy.ProxyServer;
import be.elevenways.hohenheim.test.HohenheimTestBase;
import be.elevenways.zenit.auth.model.UserModel;
import be.elevenways.zenit.auth.model.UserPrincipal;
import be.elevenways.zenit.auth.server.AuthCookieSupport;
import be.elevenways.zenit.auth.server.AuthModels;
import be.elevenways.zenit.auth.server.RecordGrants;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer.OrderAnnotation;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Certificate issuance authority: a request must name hostnames this installation actually
 * serves, on sites the caller manages, and a renewal must re-decide that instead of
 * inheriting the fact that issuance once succeeded.
 */
@TestMethodOrder(OrderAnnotation.class)
class CertificateAuthorityTest extends HohenheimTestBase {

    /** Every hostname here ends in this, so no other class in the shared fork can cover it. */
    private static final String ZONE = "certauth.test";

    private static Integer ownedSiteId;
    private static Integer foreignSiteId;
    private static Integer tenantUserId;
    private static CertificateAuthority.Requester tenant;
    private static ProxyServer adoptedProxy;

    @BeforeAll
    static void seedSitesAndTenant() {
        ownedSiteId = seedSite("certauth-owned");
        foreignSiteId = seedSite("certauth-foreign");

        seedDomain(ownedSiteId, "owned." + ZONE, SiteDomainModel.MATCH_EXACT);
        seedDomain(ownedSiteId, "*.wild." + ZONE, SiteDomainModel.MATCH_WILDCARD);
        // Served, but not a legal hostname: it gets a request PAST the authority gate
        // without any CA round-trip, which is how the legitimate lane is proven offline.
        seedLegacyIllegalDomain(ownedSiteId, ILLEGAL_HOST);
        seedDomain(foreignSiteId, "foreign." + ZONE, SiteDomainModel.MATCH_EXACT);

        Row user = AuthModels.users().createEmptyRow();
        user.set(UserModel.EMAIL, "certauth-tenant@hohenheim.local");
        user.set(UserModel.DISPLAY_NAME, "Certauth Tenant");
        user.set(UserModel.ENABLED, true);
        user.set(UserModel.CREATED_AT, Instant.now());
        user.set(UserModel.UPDATED_AT, Instant.now());
        AuthModels.users().save(user);
        tenantUserId = user.get(UserModel.ID);

        RecordGrants.grant("user", tenantUserId, SiteModel.MODEL_ID, ownedSiteId,
            HohenheimAccess.MANAGE, true);
        tenant = CertificateAuthority.Requester.ofSubject(tenantUserId);

        // The POST handler reaches the service through the proxy; an unstarted one is
        // enough (nothing here contacts a CA).
        if (ServerMain.getProxyServer() == null) {
            adoptedProxy = new ProxyServer();
            ServerMain.adoptProxyServer(adoptedProxy);
        }
    }

    @AfterAll
    static void detachProxy() {
        if (adoptedProxy != null) {
            ServerMain.adoptProxyServer(null);
        }
    }

    /**
     * The hostname you do not serve is refused for EVERYONE, including an admin over HTTP,
     * and the refusal leaves no order behind.
     */
    @Test
    @Order(1)
    void aHostnameThisInstallationDoesNotServeIsRefusedEvenForAnAdmin() throws Exception {
        String unserved = "nobody-serves-this." + ZONE;

        // 1. The service refuses it for the installation admin, whose permission gates
        //    every certificate endpoint -- the serving half binds admins too.
        assertThatThrownBy(() -> CertificateAuthority.authorize(
                CertificateAuthority.Requester.SYSTEM, List.of(unserved)))
            .describedAs("a name no domain row covers must be refused for system authority")
            .isInstanceOf(CertificateAuthority.Refused.class)
            .extracting(refused -> ((CertificateAuthority.Refused) refused).refusal())
            .isEqualTo(CertificateAuthority.Refusal.NOT_SERVED);

        // 2. And over the real admin-gated endpoint, which is the surface that used to
        //    accept a free-form hostname list on nothing but requiresPermission.
        HttpResponse<String> response = adminPost(
            "challenge_type=http&dns_mode=manual&nice_name=Unserved&domains=" + unserved);
        assertThat(response.statusCode())
            .describedAs("the request form answers with its error redirect")
            .isIn(302, 303);
        assertThat(java.net.URLDecoder.decode(
                response.headers().firstValue("location").orElse(""), "UTF-8"))
            .describedAs("the refusal names the serving half, not a generic failure")
            .contains("does not serve")
            .contains(unserved);

        // 3. STATE, not just status: no certificate order exists for that name.
        assertThat(certificateFor(unserved))
            .describedAs("a refused request must not leave a pending order behind")
            .isNull();
    }

    /** A name served by a site the caller cannot manage is refused, and creates no order. */
    @Test
    @Order(2)
    void aNameServedByAnUnmanagedSiteIsRefused() {
        String foreign = "foreign." + ZONE;

        assertThatThrownBy(() -> CertificateAuthority.authorize(tenant, List.of(foreign)))
            .isInstanceOf(CertificateAuthority.Refused.class)
            .extracting(refused -> ((CertificateAuthority.Refused) refused).refusal())
            .describedAs("the tenant serves nothing on that site")
            .isEqualTo(CertificateAuthority.Refusal.NOT_MANAGED);

        // The refusal happens before the order key is claimed and before the row exists.
        assertThatThrownBy(() -> acme().requestCertificate(List.of(foreign), "Foreign Cert",
                null, tenant))
            .isInstanceOf(CertificateAuthority.Refused.class);
        assertThat(certificateFor(foreign))
            .describedAs("no certificate row may be created for a refused request")
            .isNull();
    }

    /**
     * A wildcard SAN needs a wildcard claim: holding one exact host under the parent is an
     * INTERSECTING claim, not a covering one, and issuing on it would hand the holder every
     * sibling host.
     */
    @Test
    @Order(3)
    void aWildcardRequestNeedsAWildcardClaim() {
        assertThatThrownBy(() -> CertificateAuthority.authorize(tenant, List.of("*." + ZONE)))
            .isInstanceOf(CertificateAuthority.Refused.class)
            .extracting(refused -> ((CertificateAuthority.Refused) refused).refusal())
            .describedAs("owned." + ZONE + " intersects *." + ZONE + " but does not cover it")
            .isEqualTo(CertificateAuthority.Refusal.NOT_SERVED);

        // The wildcard row the tenant DOES hold covers its own set.
        assertThat(CertificateAuthority.authorize(tenant, List.of("*.wild." + ZONE)))
            .describedAs("a wildcard claim authorizes its own wildcard SAN")
            .containsKey("*.wild." + ZONE);
    }

    /** The legitimate lane opens: the owner's own hostname authorizes and the order starts. */
    @Test
    @Order(4)
    void theSiteOwnerGetsThroughForItsOwnHostname() {
        String owned = "owned." + ZONE;

        Map<String, Integer> declaring = CertificateAuthority.authorize(tenant, List.of(owned));
        assertThat(declaring)
            .describedAs("the owner is authorized and the declaring domain row is named")
            .containsKey(owned);
        assertThat(declaring.get(owned))
            .describedAs("the declaring row is the tenant's own domain row")
            .isEqualTo(domainIdOf(owned));

        // End to end through the service: the gate opens, the order is created and stamped
        // with the requester, and the failure that follows is a HOSTNAME failure -- not an
        // authority refusal -- which is as far as an offline test can honestly go.
        int certId = acme().requestCertificate(List.of(ILLEGAL_HOST),
            "Certauth Legit", null, tenant);
        assertThat(certId)
            .describedAs("the order was placed and only then failed on the hostname")
            .isEqualTo(-1);

        Row cert = Models.get(CertificateModel.class).find()
            .where(CertificateModel.NICE_NAME.eq("Certauth Legit")).first();
        assertThat(cert).describedAs("an authorized request creates its order row").isNotNull();
        assertThat((Integer) cert.get(CertificateModel.REQUESTED_BY_USER_ID))
            .describedAs("the order records the subject a renewal must re-authorize")
            .isEqualTo(tenantUserId);
        assertThat((String) cert.get(CertificateModel.RENEWAL_ERROR))
            .describedAs("the gate opened; the failure is hostname syntax")
            .contains("Invalid hostnames");
    }

    /**
     * Renewal RE-DECIDES authority: revoking the grant that authorized issuance stops the
     * renewal, and the decided behaviour is refuse-and-surface (auto-renew stays on, so a
     * re-granted site heals itself on the next sweep).
     */
    @Test
    @Order(5)
    void revokingTheGrantStopsTheRenewal() {
        var certModel = Models.get(CertificateModel.class);
        Row cert = certModel.createEmptyRow();
        cert.set(CertificateModel.NICE_NAME, "Certauth Renewal");
        cert.set(CertificateModel.PROVIDER, CertificateModel.PROVIDER_LETSENCRYPT);
        cert.set(CertificateModel.STATUS, CertificateModel.STATUS_ACTIVE);
        cert.set(CertificateModel.DOMAIN_NAMES_TEXT, "owned." + ZONE);
        cert.set(CertificateModel.CHALLENGE_TYPE, CertificateModel.CHALLENGE_HTTP);
        cert.set(CertificateModel.AUTO_RENEW, true);
        cert.set(CertificateModel.REQUESTED_BY_USER_ID, tenantUserId);
        certModel.save(cert);

        // 1. The authority that issued it is withdrawn.
        assertThat(RecordGrants.revoke("user", tenantUserId, SiteModel.MODEL_ID, ownedSiteId,
                HohenheimAccess.MANAGE))
            .describedAs("the manage grant that authorized issuance is revoked")
            .isTrue();
        assertThat(HohenheimAccess.canManageSite(
                new UserPrincipal(tenantUserId, "Certauth Tenant"),
                ownedSiteId))
            .describedAs("the grant is really gone")
            .isFalse();

        // 2. The renewal sweep refuses instead of re-ordering.
        acme().renewCertificate(cert, certModel);

        Row after = certModel.findById(cert.get(CertificateModel.ID));
        assertThat((String) after.get(CertificateModel.STATUS))
            .describedAs("a refused renewal is an error, not a silent skip")
            .isEqualTo(CertificateModel.STATUS_ERROR);
        assertThat((String) after.get(CertificateModel.RENEWAL_ERROR))
            .describedAs("the surfaced reason names the authority refusal")
            .contains("NOT_MANAGED")
            .contains("owned." + ZONE);
        assertThat((Boolean) after.get(CertificateModel.AUTO_RENEW))
            .describedAs("refuse-and-surface: auto-renew stays on so a re-grant heals itself")
            .isTrue();
        assertThat((Instant) after.get(CertificateModel.NEXT_ATTEMPT_AT))
            .describedAs("the ordinary escalating backoff applies")
            .isNotNull();

        // 3. Restoring the grant makes the certificate renewable again, so the refusal was
        //    about live authority and not a permanent poisoning.
        RecordGrants.grant("user", tenantUserId, SiteModel.MODEL_ID, ownedSiteId,
            HohenheimAccess.MANAGE, true);
        assertThat(CertificateAuthority.authorize(tenant, List.of("owned." + ZONE)))
            .containsKey("owned." + ZONE);
    }

    // -----------------------------------------------------------------------

    private static AcmeService acme() {
        return ServerMain.getProxyServer().getAcmeService();
    }

    private static @org.checkerframework.checker.nullness.qual.Nullable Row certificateFor(String hostname) {
        return Models.get(CertificateModel.class).find()
            .where(CertificateModel.DOMAIN_NAMES_TEXT.eq(hostname)).first();
    }

    private static Integer domainIdOf(String hostname) {
        return Models.get(SiteDomainModel.class).findByHostname(hostname).get(0)
            .get(SiteDomainModel.ID);
    }

    private static Integer seedSite(String slug) {
        var siteModel = Models.get(SiteModel.class);
        Row site = siteModel.createEmptyRow();
        site.set(SiteModel.NAME, slug);
        site.set(SiteModel.SLUG, slug);
        site.set(SiteModel.SITE_TYPE, "hohenheim:dead");
        site.set(SiteModel.STATUS, "active");
        site.set(SiteModel.ENABLED, true);
        siteModel.save(site);
        return site.get(SiteModel.ID);
    }

    /** The hostname string the ACME layer must refuse on syntax rather than on authority. */
    private static final String ILLEGAL_HOST = "not a legal host";

    /**
     * A domain row holding a hostname the WRITE PIPELINE no longer accepts -- seeded legal
     * and then rewritten with a set-based update, which runs no schema hook.
     *
     * AIDEV-NOTE: this is deliberately the shape of a row that predates
     * {@code SiteDomainModel.validateHostnameSyntax} (M079's declared residue: a hostname
     * it cannot repair is left alone rather than invented). Keeping it is what still proves
     * AcmeService's OWN syntax check is load-bearing -- the model refusal is the first
     * gate, not the only one, and a stored legacy row is exactly the case where the second
     * one has to answer.
     */
    private static void seedLegacyIllegalDomain(int siteId, String hostname) {
        var domainModel = Models.get(SiteDomainModel.class);
        Row domain = domainModel.createEmptyRow();
        domain.set(SiteDomainModel.SITE_ID, siteId);
        domain.set(SiteDomainModel.HOSTNAME, "legacy-illegal." + ZONE);
        domain.set(SiteDomainModel.MATCH_TYPE, SiteDomainModel.MATCH_EXACT);
        domainModel.save(domain);
        domainModel.find().where(SiteDomainModel.ID.eq(domain.get(SiteDomainModel.ID)))
            .assign(SiteDomainModel.HOSTNAME, hostname)
            .updateAll();
    }

    private static void seedDomain(int siteId, String hostname, String matchType) {
        var domainModel = Models.get(SiteDomainModel.class);
        Row domain = domainModel.createEmptyRow();
        domain.set(SiteDomainModel.SITE_ID, siteId);
        domain.set(SiteDomainModel.HOSTNAME, hostname);
        domain.set(SiteDomainModel.MATCH_TYPE, matchType);
        domainModel.save(domain);
    }

    private HttpResponse<String> adminPost(String body) throws Exception {
        HttpClient client = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER).build();
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:" + getServerPort() + "/admin/certificates-request"))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .header("Cookie", AuthCookieSupport.sessionCookieName() + "=" + sessionToken)
            .header("X-Csrf-Token", csrfToken)
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
