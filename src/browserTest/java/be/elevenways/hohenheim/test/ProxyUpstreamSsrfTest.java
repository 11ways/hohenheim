package be.elevenways.hohenheim.test;

import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.zenit.auth.model.UserModel;
import be.elevenways.zenit.auth.model.UserPrincipal;
import be.elevenways.zenit.auth.server.AuthModels;
import be.elevenways.zenit.auth.server.RecordGrants;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.validation.Violation;
import be.elevenways.zenit.common.validation.Violations;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer.OrderAnnotation;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.net.URI;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * A delegated tenant holding {@code manage} on a proxy site cannot aim it at a loopback or
 * private address, while an operator still can -- the reverse-proxy-to-LAN use case the
 * product ships. The gate lives on the model write pipeline (TenantWrites), not on any form,
 * so a direct {@code model.save} past every rendered field answers to it too.
 *
 * AIDEV-NOTE: the counterfactuals ARE the attack. Step 1 is the pre-fix behaviour intact --
 * an OPERATOR (which was, before this gate, every writer) lands a proxy whose upstream URI
 * resolves to the cloud-metadata address 169.254.169.254; the exact write is then refused
 * for a tenant. A test that only refused would be vacuous, so a public-host tenant write and
 * the operator write must both still pass.
 */
@TestMethodOrder(OrderAnnotation.class)
class ProxyUpstreamSsrfTest extends HohenheimTestBase {

    /** The cloud-metadata service: link-local, unauthenticated, the canonical SSRF target. */
    private static final String METADATA_IP = "169.254.169.254";

    private static Integer tenantSiteId;
    private static Integer operatorSiteId;
    private static UserPrincipal tenantPrincipal;
    private static UserPrincipal adminPrincipal;

    @BeforeAll
    static void seed() {
        Model siteModel = Models.get(SiteModel.class);

        Row admin = AuthModels.users().find()
            .where(UserModel.EMAIL.eq("test@hohenheim.local")).first();
        adminPrincipal = new UserPrincipal(admin.get(UserModel.ID), "Test Admin");

        Row tenant = AuthModels.users().createEmptyRow();
        tenant.set(UserModel.EMAIL, "tenant-ssrf@hohenheim.local");
        tenant.set(UserModel.DISPLAY_NAME, "SSRF Tenant");
        tenant.set(UserModel.ENABLED, true);
        tenant.set(UserModel.CREATED_AT, Instant.now());
        tenant.set(UserModel.UPDATED_AT, Instant.now());
        AuthModels.users().save(tenant);
        Integer tenantId = tenant.get(UserModel.ID);
        tenantPrincipal = new UserPrincipal(tenantId, "SSRF Tenant");

        // The tenant's OWN proxy site, pointed at a public host to begin with.
        Row tenantSite = proxySite(siteModel, "SSRF Tenant Site", "ssrf-tenant", "93.184.216.34");
        TenantConduits.as(adminPrincipal, () -> siteModel.save(tenantSite));
        tenantSiteId = tenantSite.get(SiteModel.ID);
        RecordGrants.grant("user", tenantId, SiteModel.MODEL_ID, tenantSiteId,
            HohenheimAccess.MANAGE, true);
    }

    private static Row proxySite(Model model, String name, String slug, String forwardHost) {
        Row row = model.createEmptyRow();
        row.set(SiteModel.NAME, name);
        row.set(SiteModel.SLUG, slug);
        row.set(SiteModel.SITE_TYPE, "hohenheim:proxy");
        Map<String, Object> settings = new HashMap<>();
        settings.put("forward_scheme", "http");
        settings.put("forward_host", forwardHost);
        settings.put("forward_port", 80);
        row.set(SiteModel.SETTINGS, settings);
        row.set(SiteModel.STATUS, "active");
        row.set(SiteModel.ENABLED, true);
        return row;
    }

    private static Violation refusalOf(Runnable body) {
        Violations violations = catchThrowableOfType(
            () -> TenantConduits.as(tenantPrincipal, body), Violations.class);
        return violations != null ? violations.all().get(0) : null;
    }

    private static String storedForwardHost(int siteId) {
        Object settings = Models.get(SiteModel.class).findById(siteId).get(SiteModel.SETTINGS);
        return settings instanceof Map<?, ?> map ? String.valueOf(map.get("forward_host")) : null;
    }

    @Test
    @Order(1)
    void anOperatorMayStillProxyToAnyAddressAndItActuallyReachesIt() {
        Model model = Models.get(SiteModel.class);

        // 1. The pre-fix behaviour, intact for the operator: a proxy site aimed straight at
        //    the cloud-metadata service saves without complaint.
        Row site = proxySite(model, "Operator LAN Proxy", "operator-lan", METADATA_IP);
        assertThatCode(() -> TenantConduits.as(adminPrincipal, () -> model.save(site)))
            .as("step 1: an operator may point a proxy at a LAN/metadata address")
            .doesNotThrowAnyException();
        operatorSiteId = site.get(SiteModel.ID);

        // 2. And the stored config genuinely resolves to that forbidden upstream -- this is
        //    the request a proxied hit would issue, aimed at the metadata service.
        assertThat(storedForwardHost(operatorSiteId)).isEqualTo(METADATA_IP);
        URI upstream = URI.create("http://" + storedForwardHost(operatorSiteId));
        assertThat(upstream.getHost())
            .as("step 2: the persisted proxy dials the metadata service")
            .isEqualTo(METADATA_IP);
    }

    @Test
    @Order(2)
    void aTenantMayStillProxyToAPublicAddress() {
        Model model = Models.get(SiteModel.class);
        // Non-vacuity: the gate does not refuse every tenant proxy write.
        assertThatCode(() -> TenantConduits.as(tenantPrincipal, () -> {
            Row row = model.findById(tenantSiteId);
            Object settings = row.get(SiteModel.SETTINGS);
            Map<String, Object> next = new HashMap<>((Map<String, Object>) settings);
            next.put("forward_host", "example.com");
            row.set(SiteModel.SETTINGS, next);
            model.save(row);
        })).as("a tenant may aim their proxy at a public host").doesNotThrowAnyException();
        assertThat(storedForwardHost(tenantSiteId)).isEqualTo("example.com");
    }

    @Test
    @Order(3)
    void aTenantCannotProxyToLoopbackOrPrivateAddresses() {
        Model model = Models.get(SiteModel.class);

        for (String forbidden : java.util.List.of(METADATA_IP, "127.0.0.1", "10.0.0.5",
                "192.168.1.1", "localhost", "[::1]")) {
            Violation refusal = refusalOf(() -> {
                Row row = model.findById(tenantSiteId);
                Object settings = row.get(SiteModel.SETTINGS);
                Map<String, Object> next = new HashMap<>((Map<String, Object>) settings);
                next.put("forward_host", forbidden);
                row.set(SiteModel.SETTINGS, next);
                model.save(row);
            });
            assertThat(refusal).as("'%s' is refused for a tenant", forbidden).isNotNull();
            assertThat(refusal.message().key())
                .as("'%s' refused as a private/loopback upstream", forbidden)
                .isEqualTo("tenant_proxy_upstream_private");
            assertThat(storedForwardHost(tenantSiteId))
                .as("'%s' left the stored host untouched", forbidden)
                .isEqualTo("example.com");
        }
    }

    @Test
    @Order(4)
    void anUpstreamWithEmbeddedCredentialsIsRefusedForEveryone() {
        Model model = Models.get(SiteModel.class);
        Violations refused = catchThrowableOfType(() -> TenantConduits.as(adminPrincipal, () -> {
            Row site = proxySite(model, "Cred Proxy", "cred-proxy", "user:pass@evil.test");
            model.save(site);
        }), Violations.class);
        assertThat((Throwable) refused)
            .as("an upstream carrying credentials is refused for the operator too").isNotNull();
        assertThat(refused.all().get(0).message().key()).isEqualTo("proxy_upstream_invalid");
    }
}
