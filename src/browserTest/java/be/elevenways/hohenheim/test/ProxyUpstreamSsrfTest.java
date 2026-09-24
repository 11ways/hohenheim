package be.elevenways.hohenheim.test;

import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.hohenheim.server.auth.TenantWrites;
import be.elevenways.zenit.auth.model.GrantSubjectType;
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
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.URI;
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
 * for a tenant. A test that only refused would be vacuous, so the operator write and a tenant
 * RENAME (of its own site, and of an operator-aimed LAN proxy delegated to it) must both still
 * pass. Since 2026-09-23 a tenant cannot re-aim a site at all (the settings column is frozen
 * for tenants); the upstream judgement still speaks first with the precise refusal and is
 * the defence that survives a later widening of that allow-list.
 */
class ProxyUpstreamSsrfTest extends HohenheimTestBase {

    /** The cloud-metadata service: link-local, unauthenticated, the canonical SSRF target. */
    private static final String METADATA_IP = "169.254.169.254";

    /** A public address the tenant's site is seeded with; judging it resolves no DNS. */
    private static final String PUBLIC_IP = "93.184.216.34";

    private static Integer tenantId;
    private static Integer tenantSiteId;
    private static UserPrincipal tenantPrincipal;
    private static UserPrincipal adminPrincipal;

    @BeforeAll
    static void seed() {
        Model siteModel = Models.get(SiteModel.class);

        Row admin = AuthModels.users().find()
            .where(UserModel.EMAIL.eq("test@hohenheim.local")).first();
        adminPrincipal = new UserPrincipal(admin.get(UserModel.ID), "Test Admin");

        tenantId = ApiSupport.user("tenant-ssrf@hohenheim.local", "SSRF Tenant");
        tenantPrincipal = new UserPrincipal(tenantId, "SSRF Tenant");

        // The tenant's OWN proxy site, pointed at a public host to begin with.
        Row tenantSite = proxySite(siteModel, "SSRF Tenant Site", "ssrf-tenant", PUBLIC_IP);
        TenantConduits.as(adminPrincipal, () -> siteModel.save(tenantSite));
        tenantSiteId = tenantSite.get(SiteModel.ID);
        RecordGrants.grant(GrantSubjectType.USER, tenantId, SiteModel.MODEL_ID, tenantSiteId,
            HohenheimAccess.MANAGE, true);
    }

    private static Row proxySite(Model model, String name, String slug, String forwardHost) {
        Row row = model.createEmptyRow();
        row.set(SiteModel.NAME, name);
        row.set(SiteModel.SLUG, slug);
        row.set(SiteModel.UPSTREAM_KIND, "hohenheim:address");
        Map<String, Object> settings = new HashMap<>();
        settings.put("forward_scheme", "http");
        settings.put("forward_host", forwardHost);
        settings.put("forward_port", 80);
        row.set(SiteModel.SETTINGS, settings);
        row.set(SiteModel.STATUS, "active");
        row.set(SiteModel.ENABLED, true);
        return row;
    }

    /** A proxy site the OPERATOR aims straight at the cloud-metadata service. */
    private static int operatorMetadataProxy(Model model, String name, String slug) {
        Row site = proxySite(model, name, slug, METADATA_IP);
        TenantConduits.as(adminPrincipal, () -> model.save(site));
        return site.get(SiteModel.ID);
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
    void anOperatorMayStillProxyToAnyAddressAndItActuallyReachesIt() {
        Model model = Models.get(SiteModel.class);

        // 1. The pre-fix behaviour, intact for the operator: a proxy site aimed straight at
        //    the cloud-metadata service saves without complaint.
        Row site = proxySite(model, "Operator LAN Proxy", "operator-lan", METADATA_IP);
        assertThatCode(() -> TenantConduits.as(adminPrincipal, () -> model.save(site)))
            .as("step 1: an operator may point a proxy at a LAN/metadata address")
            .doesNotThrowAnyException();
        int operatorSiteId = site.get(SiteModel.ID);

        // 2. And the stored config genuinely resolves to that forbidden upstream -- this is
        //    the request a proxied hit would issue, aimed at the metadata service.
        assertThat(storedForwardHost(operatorSiteId)).isEqualTo(METADATA_IP);
        URI upstream = URI.create("http://" + storedForwardHost(operatorSiteId));
        assertThat(upstream.getHost())
            .as("step 2: the persisted proxy dials the metadata service")
            .isEqualTo(METADATA_IP);
    }

    @Test
    void aTenantCannotReAimItsSiteButMayStillRenameOne() {
        Model model = Models.get(SiteModel.class);

        // 1. A public host passes the SSRF judgement, but the settings map is operator
        //    authority now (TenantWrites.SITE_TENANT_WRITABLE): the write is refused as a
        //    frozen column, and nothing moves.
        Violation frozen = refusalOf(() -> {
            Row row = model.findById(tenantSiteId);
            Map<String, Object> next = new HashMap<>((Map<String, Object>) row.get(SiteModel.SETTINGS));
            next.put("forward_host", "93.184.216.35");
            row.set(SiteModel.SETTINGS, next);
            model.save(row);
        });
        assertThat(frozen).as("step 1: a tenant may not re-aim its site at all").isNotNull();
        assertThat(frozen.message().key()).as("step 1: refused as a frozen column")
            .isEqualTo("tenant_field_frozen");
        assertThat(storedForwardHost(tenantSiteId)).as("step 1: the stored host is untouched")
            .isEqualTo(PUBLIC_IP);

        // 2. The delegated columns still land: a rename through a direct save.
        assertThatCode(() -> TenantConduits.as(tenantPrincipal, () -> {
            Row row = model.findById(tenantSiteId);
            row.set(SiteModel.NAME, "SSRF Tenant Site (renamed)");
            model.save(row);
        })).as("step 2: a tenant may still rename its own site").doesNotThrowAnyException();

        // 3. A site the OPERATOR aimed at the metadata service and then delegated keeps
        //    working for its tenant: a rename does not re-aim the upstream, so the SSRF
        //    judgement never runs on the operator's choice (it used to refuse the rename).
        int operatorSiteId = operatorMetadataProxy(model, "Delegated LAN Proxy", "delegated-lan");
        RecordGrants.grant(GrantSubjectType.USER, tenantId, SiteModel.MODEL_ID, operatorSiteId,
            HohenheimAccess.MANAGE, true);
        assertThatCode(() -> TenantConduits.as(tenantPrincipal, () -> {
            Row row = model.findById(operatorSiteId);
            row.set(SiteModel.DESCRIPTION, "delegated LAN proxy");
            model.save(row);
        })).as("step 3: renaming an operator-aimed LAN proxy is not re-aiming it")
            .doesNotThrowAnyException();
        assertThat(storedForwardHost(operatorSiteId)).as("step 3: the operator's upstream stays")
            .isEqualTo(METADATA_IP);
    }

    @Test
    void aTenantCannotProxyToLoopbackOrPrivateAddresses() {
        Model model = Models.get(SiteModel.class);

        for (String forbidden : java.util.List.of(METADATA_IP, "127.0.0.1", "10.0.0.5",
                "192.168.1.1", "localhost", "[::1]", "100.64.0.1", "0.0.0.0", "[fe80::1]")) {
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
                .isEqualTo(PUBLIC_IP);
        }
    }

    @Test
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

    /**
     * Every dial target a tenant could spell is judged, not only the address kind's host:
     * the socket that OVERRIDES that host, the TLS-passthrough host, a static host path,
     * and a public-looking NAME that resolves to a private address.
     */
    @Test
    void everyDialTargetIsJudgedNotOnlyTheLiteralForwardHost() {
        Model model = Models.get(SiteModel.class);

        // 1. A unix socket overrides forward_host in the address kind; the host-only check
        //    let /var/run/docker.sock through beside a perfectly public host.
        Violation socket = refusalOf(() -> {
            Row row = model.findById(tenantSiteId);
            Map<String, Object> next = new HashMap<>((Map<String, Object>) row.get(SiteModel.SETTINGS));
            next.put("socket", "/var/run/docker.sock");
            row.set(SiteModel.SETTINGS, next);
            model.save(row);
        });
        assertThat(socket).as("step 1: a socket upstream is refused for a tenant").isNotNull();
        assertThat(socket.message().key()).as("step 1: as a non-public upstream")
            .isEqualTo("tenant_proxy_upstream_private");

        // 2. The TLS-passthrough kind dials its own forward_host raw.
        Violation passthrough = refusalOf(() -> {
            Row row = model.findById(tenantSiteId);
            Map<String, Object> next = new HashMap<>();
            next.put("forward_host", "10.1.2.3");
            next.put("forward_port", 443);
            row.set(SiteModel.UPSTREAM_KIND, "hohenheim:tls_passthrough");
            row.set(SiteModel.SETTINGS, next);
            model.save(row);
        });
        assertThat(passthrough).as("step 2: a private passthrough target is refused").isNotNull();
        assertThat(passthrough.message().key()).as("step 2: as a non-public upstream")
            .isEqualTo("tenant_proxy_upstream_private");

        // 3. A static root is a HOST path: operator authority outright.
        Violation root = refusalOf(() -> {
            Row row = model.findById(tenantSiteId);
            row.set(SiteModel.UPSTREAM_KIND, "hohenheim:static");
            row.set(SiteModel.SETTINGS, new HashMap<>(Map.of("root_path", "/etc")));
            model.save(row);
        });
        assertThat(root).as("step 3: a tenant static root is refused").isNotNull();
        assertThat(root.message().key()).as("step 3: as an operator-only setting")
            .isEqualTo("tenant_field_frozen");

        // 4. A NAME is resolved, not only read: one answering with a link-local address is
        //    the metadata service under a public-looking spelling.
        TenantWrites.resolveTenantUpstreamsWith(host -> "metadata.rebind.test".equals(host)
            ? new InetAddress[] {InetAddress.getByName(METADATA_IP)}
            : InetAddress.getAllByName(host));
        try {
            Violation resolved = refusalOf(() -> {
                Row row = model.findById(tenantSiteId);
                Map<String, Object> next = new HashMap<>((Map<String, Object>) row.get(SiteModel.SETTINGS));
                next.put("forward_host", "metadata.rebind.test");
                row.set(SiteModel.SETTINGS, next);
                model.save(row);
            });
            assertThat(resolved).as("step 4: a name resolving to link-local is refused").isNotNull();
            assertThat(resolved.message().key()).as("step 4: as a non-public upstream")
                .isEqualTo("tenant_proxy_upstream_private");
        } finally {
            TenantWrites.resolveTenantUpstreamsWith(null);
        }

        // 5. None of the refused writes moved the site: same kind, same public host.
        Row stored = model.findById(tenantSiteId);
        assertThat((String) stored.get(SiteModel.UPSTREAM_KIND)).as("step 5: the kind is untouched")
            .isEqualTo("hohenheim:address");
        assertThat(storedForwardHost(tenantSiteId)).as("step 5: the host is untouched")
            .isEqualTo(PUBLIC_IP);
    }
}
