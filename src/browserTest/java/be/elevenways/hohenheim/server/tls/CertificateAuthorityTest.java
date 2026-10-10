package be.elevenways.hohenheim.server.tls;

import be.elevenways.hohenheim.HohenheimMicrocopy;
import be.elevenways.hohenheim.model.CertificateModel;
import be.elevenways.hohenheim.model.SiteDomainModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.server.ServerMain;
import be.elevenways.hohenheim.HohenheimCapabilities;
import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.hohenheim.server.proxy.ProxyServer;
import be.elevenways.hohenheim.test.ApiSupport;
import be.elevenways.hohenheim.test.HohenheimTestBase;
import be.elevenways.zenit.auth.model.GrantSubjectType;
import be.elevenways.zenit.auth.model.UserModel;
import be.elevenways.zenit.auth.model.UserPrincipal;
import be.elevenways.zenit.auth.server.RecordGrants;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.security.KnownCapabilities;
import be.elevenways.zenit.common.security.PrincipalRef;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Certificate issuance authority: a request must name hostnames this installation actually
 * serves, on sites the caller manages, and a renewal must re-decide that instead of
 * inheriting the fact that issuance once succeeded. The tests are independent: the one
 * that revokes the tenant's grant restores it in a finally.
 */
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

        tenantUserId = ApiSupport.user("certauth-tenant@hohenheim.local", "Certauth Tenant");

        RecordGrants.grant(GrantSubjectType.USER, tenantUserId, SiteModel.MODEL_ID, ownedSiteId,
            HohenheimCapabilities.MANAGE, true);
        tenant = CertificateAuthority.Requester.ofSubject(PrincipalRef.account(tenantUserId));

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

        // 2. And over the certificate list's request operation, which is the surface that used to accept a free-form
        //    hostname list on nothing but requiresPermission.
        HttpResponse<String> response = adminPost(
            "challenge_type=http&dns_publisher=manual&nice_name=Unserved&domains=" + unserved);
        assertThat(response.statusCode())
            .describedAs("the request answers as a refusal, its input redrawn")
            .isEqualTo(422);
        assertThat(response.body())
            .describedAs("the refusal names the serving half and the hostname, not a generic failure")
            .contains(ApiSupport.shippedText(HohenheimMicrocopy.CERTIFICATE_REQUEST_ERROR.of("hostname_not_served")
                .withArg("hostnames", unserved)));

        // 3. STATE, not just status: no certificate order exists for that name.
        assertThat(certificateFor(unserved))
            .describedAs("a refused request must not leave a pending order behind")
            .isNull();
    }

    /**
     * The certificate GRANT VOCABULARY offers only what something reads.
     *
     * zenit-auth's RecordAccessPage draws one grant column per registered
     * {@code KnownCapability}, so registering a capability nothing consults ships an
     * operator a checkbox that reports success and grants nothing. A {@code request}
     * capability was registered here and never read once: ordering authority is
     * {@link CertificateAuthority#authorize}'s name-coverage walk, which decides against
     * the SITE's {@code manage} grants because the certificate the per-record grant would
     * sit on does not exist yet when the request is made.
     */
    @Test
    void theCertificateVocabularyOffersOnlyTheCapabilityThatIsActuallyRead() {
        // 1. THE STRUCK ONE: `request` is not a certificate capability, so no grant
        //    column for it is drawn and no operator can tick a box that does nothing.
        assertThat(KnownCapabilities.get(CertificateModel.MODEL_ID, "request"))
            .describedAs("step 1: `request` must not be a registered certificate"
                + " capability -- nothing reads it, and RecordAccessPage would render a"
                + " grant control that silently grants nothing")
            .isNull();

        // 2. POSITIVE ANCHOR: `view` IS registered, because the /manage certificate
        //    scope really does consult it -- so step 1 is about an unread capability
        //    and not about the vocabulary being empty.
        assertThat(KnownCapabilities.get(CertificateModel.MODEL_ID, HohenheimCapabilities.VIEW))
            .describedAs("step 2: `view` stays, and it is the whole vocabulary")
            .isNotNull();
        assertThat(KnownCapabilities.isDelegable(CertificateModel.MODEL_ID, HohenheimCapabilities.VIEW))
            .describedAs("step 2: and an operator really can hand it out, which is what"
                + " makes step 1 about an UNREAD capability rather than an empty registry")
            .isTrue();

        // 3. AUTHORITY IS STILL DECIDED -- by coverage, on the site. Removing the
        //    registration took away an affordance, never a gate.
        assertThatThrownBy(() -> CertificateAuthority.authorize(
                CertificateAuthority.Requester.SYSTEM, List.of("nothing-covers." + ZONE)))
            .describedAs("step 3: ordering authority is name coverage, and it still refuses")
            .isInstanceOf(CertificateAuthority.Refused.class);
    }

    /** A name served by a site the caller cannot manage is refused, and creates no order. */
    @Test
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
        AcmeService.RequestOutcome outcome = acme().requestCertificate(List.of(ILLEGAL_HOST),
            "Certauth Legit", null, tenant);
        assertThat(outcome.issued())
            .describedAs("the order was placed and only then failed on the hostname")
            .isFalse();
        assertThat(outcome.certificateId())
            .describedAs("and the failure names the row it wrote")
            .isNotNull();

        Row cert = Models.get(CertificateModel.class).find()
            .where(CertificateModel.NICE_NAME.eq("Certauth Legit")).first();
        assertThat(cert).describedAs("an authorized request creates its order row").isNotNull();
        assertThat(CertificateModel.requesterOf(cert))
            .describedAs("the order records the subject a renewal must re-authorize, as an account")
            .isEqualTo(PrincipalRef.account(tenantUserId));
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
    void revokingTheGrantStopsTheRenewal() {
        var certModel = Models.get(CertificateModel.class);
        Row cert = certModel.createEmptyRow();
        cert.set(CertificateModel.NICE_NAME, "Certauth Renewal");
        cert.set(CertificateModel.PROVIDER, CertificateModel.PROVIDER_LETSENCRYPT);
        cert.set(CertificateModel.STATUS, CertificateModel.STATUS_ACTIVE);
        cert.set(CertificateModel.DOMAIN_NAMES_TEXT, "owned." + ZONE);
        cert.set(CertificateModel.CHALLENGE_TYPE, CertificateModel.CHALLENGE_HTTP);
        cert.set(CertificateModel.AUTO_RENEW, true);
        CertificateModel.setRequester(cert, PrincipalRef.account(tenantUserId));
        certModel.save(cert);

        // 1. The authority that issued it is withdrawn (and restored whatever happens, since
        //    every other test here authorizes through it).
        try {
            renewalAfterRevocation(cert, certModel);
        } finally {
            RecordGrants.grant(GrantSubjectType.USER, tenantUserId, SiteModel.MODEL_ID, ownedSiteId,
                HohenheimCapabilities.MANAGE, true);
        }

        // 3. Restoring the grant makes the certificate renewable again, so the refusal was
        //    about live authority and not a permanent poisoning.
        assertThat(CertificateAuthority.authorize(tenant, List.of("owned." + ZONE)))
            .describedAs("step 3: the restored grant authorizes the owned hostname again")
            .containsKey("owned." + ZONE);
    }

    /**
     * A renewal acts for the stored requester as the account is TODAY: once it is disabled or deleted, the grants it
     * still holds authorize nothing, and re-enabling it restores the renewal.
     */
    @Test
    void aDisabledOrDeletedRequesterRenewsNothing() {
        // 1. An account holding manage on the owned site renews its name.
        int requesterId = ApiSupport.user("certauth-renewer@hohenheim.local", "Certauth Renewer");
        RecordGrants.grant(GrantSubjectType.USER, requesterId, SiteModel.MODEL_ID, ownedSiteId,
            HohenheimCapabilities.MANAGE, true);
        PrincipalRef stored = PrincipalRef.account(requesterId);
        assertThat(CertificateAuthority.authorize(CertificateAuthority.Requester.ofSubject(stored),
                List.of("owned." + ZONE)))
            .describedAs("step 1: the enabled requester renews its name").containsKey("owned." + ZONE);

        // 2. Disabled, it keeps every grant and renews nothing.
        setEnabled(requesterId, false);
        assertThatThrownBy(() -> CertificateAuthority.authorize(CertificateAuthority.Requester.ofSubject(stored),
                List.of("owned." + ZONE)))
            .describedAs("step 2: a disabled requester's renewal is refused")
            .isInstanceOf(CertificateAuthority.Refused.class);

        // 3. Enabled again, it renews again: the refusal was the account's state, not a poisoned certificate.
        setEnabled(requesterId, true);
        assertThat(CertificateAuthority.authorize(CertificateAuthority.Requester.ofSubject(stored),
                List.of("owned." + ZONE)))
            .describedAs("step 3: re-enabled, it renews").containsKey("owned." + ZONE);

        // 4. An id that names no account at all (deleted) renews nothing either.
        assertThatThrownBy(() -> CertificateAuthority.authorize(
                CertificateAuthority.Requester.ofSubject(PrincipalRef.account(987_654_321L)),
                List.of("owned." + ZONE)))
            .describedAs("step 4: a deleted requester's renewal is refused")
            .isInstanceOf(CertificateAuthority.Refused.class);
    }

    private static void setEnabled(int userId, boolean enabled) {
        Models.get(UserModel.class).find().where(UserModel.ID.eq(userId))
            .assign(UserModel.ENABLED, enabled).updateAll();
    }

    /**
     * A stored requester id that names no account (a pair without its kind) renews as nobody: the sweep refuses it
     * by name instead of treating the certificate as an unattended, system-authorized order.
     */
    @Test
    void aStoredRequesterThatIsNoAccountRenewsAsNobodyNeverAsTheSystem() {
        var certModel = Models.get(CertificateModel.class);
        Row cert = certModel.createEmptyRow();
        cert.set(CertificateModel.NICE_NAME, "Certauth Kindless");
        cert.set(CertificateModel.PROVIDER, CertificateModel.PROVIDER_LETSENCRYPT);
        cert.set(CertificateModel.STATUS, CertificateModel.STATUS_ACTIVE);
        cert.set(CertificateModel.DOMAIN_NAMES_TEXT, "owned." + ZONE);
        cert.set(CertificateModel.CHALLENGE_TYPE, CertificateModel.CHALLENGE_HTTP);
        cert.set(CertificateModel.AUTO_RENEW, true);
        // 1. The manager's id is stored, but without the kind that makes it an account.
        cert.set(CertificateModel.REQUESTED_BY_USER_ID, tenantUserId);
        certModel.save(cert);
        assertThat(CertificateModel.requesterOf(cert)).describedAs("step 1: the pair names no principal").isNull();

        // 2. The sweep refuses the names the account itself could have renewed.
        acme().renewCertificate(cert, certModel);
        Row after = certModel.findById(cert.get(CertificateModel.ID));
        assertThat((String) after.get(CertificateModel.STATUS))
            .describedAs("step 2: a renewal without an account subject is an error")
            .isEqualTo(CertificateModel.STATUS_ERROR);
        assertThat((String) after.get(CertificateModel.RENEWAL_ERROR))
            .describedAs("step 2: refused by authority, never ordered as the system").contains("NOT_MANAGED");
    }

    private void renewalAfterRevocation(Row cert, CertificateModel certModel) {
        assertThat(RecordGrants.revoke(GrantSubjectType.USER, tenantUserId, SiteModel.MODEL_ID, ownedSiteId,
                HohenheimCapabilities.MANAGE))
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
        return Models.get(SiteDomainModel.class).findAll(SiteDomainModel.HOSTNAME, hostname).get(0)
            .get(SiteDomainModel.ID);
    }

    private static Integer seedSite(String slug) {
        var siteModel = Models.get(SiteModel.class);
        Row site = siteModel.createEmptyRow();
        site.set(SiteModel.NAME, slug);
        site.set(SiteModel.SLUG, slug);
        site.set(SiteModel.UPSTREAM_KIND, "hohenheim:static");
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
        return adminPostForm(ApiSupport.requestCertificateTarget(), body + "&" + ApiSupport.invokeTransport());
    }
}
