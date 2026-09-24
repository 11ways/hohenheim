package be.elevenways.hohenheim.test;

import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.hohenheim.model.ReleasedRouteClaimModel;
import be.elevenways.hohenheim.model.SiteDomainModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.hohenheim.server.cms.ManageSiteResource;
import be.elevenways.hohenheim.server.cms.SiteResource;
import be.elevenways.hohenheim.server.proxy.ProxyServer;
import be.elevenways.zenit.auth.model.GrantSubjectType;
import be.elevenways.zenit.auth.model.UserPrincipal;
import be.elevenways.zenit.auth.server.RecordGrants;
import be.elevenways.zenit.common.edit.EditView;
import be.elevenways.zenit.common.edit.FormEntry;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.validation.Violations;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * An operator can vouch for the loopback or LAN upstream of a tenant-owned site
 * ({@code sites.trusted_upstream}), and a tenant can never vouch for one: without the flag the
 * dial refuses the private backend, with it the site is served, and a tenant write carrying
 * the flag is refused as a frozen column on every lane.
 *
 * @author Jelle De Loecker <jelle@elevenways.be>
 * @since 0.1.0
 */
class TrustedUpstreamTest extends HohenheimTestBase {

    private static final String HOST = "trusted-upstream.example.com";

    @Test
    void anOperatorVouchesForATenantSitesPrivateUpstreamAndATenantCannot() throws Exception {
        Model siteModel = Models.get(SiteModel.class);
        var domainModel = Models.get(SiteDomainModel.class);

        HttpServer upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        upstream.createContext("/", exchange -> {
            byte[] body = "trusted-backend".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        upstream.start();

        // The operator points a site at a loopback backend: the reverse-proxy-to-LAN case.
        Row site = siteModel.createEmptyRow();
        site.set(SiteModel.NAME, "Trusted Upstream Site");
        site.set(SiteModel.SLUG, "trusted-upstream-site");
        site.set(SiteModel.UPSTREAM_KIND, "hohenheim:address");
        site.set(SiteModel.SETTINGS, Map.of("forward_host", "127.0.0.1",
            "forward_port", upstream.getAddress().getPort()));
        site.set(SiteModel.STATUS, "active");
        site.set(SiteModel.ENABLED, true);
        siteModel.save(site);
        int siteId = site.get(SiteModel.ID);

        Row domain = domainModel.createEmptyRow();
        domain.set(SiteDomainModel.SITE_ID, siteId);
        domain.set(SiteDomainModel.HOSTNAME, HOST);
        domain.set(SiteDomainModel.MATCH_TYPE, SiteDomainModel.MATCH_EXACT);
        domain.set(SiteDomainModel.FORCE_SSL, false);
        domainModel.save(domain);

        int tenantId = ApiSupport.user("trusted-upstream-tenant@hohenheim.local",
            "Trusted Upstream Tenant");
        UserPrincipal tenantPrincipal = new UserPrincipal(tenantId, "Trusted Upstream Tenant");

        HohenheimSettings.VALUES.setValue(HohenheimSettings.Proxy.HTTP_PORT, 0);
        ProxyServer proxy = new ProxyServer();
        proxy.start();
        int proxyPort = ((InetSocketAddress) proxy.getHttpListenerInfo().getAddress()).getPort();

        try {
            // 1. Operator-owned, the loopback backend is served: the baseline the flag keeps.
            assertThat(proxyStatus(proxyPort, HOST))
                .as("step 1: an operator-owned site reaches its loopback backend").isEqualTo(200);
            assertThat((Boolean) siteModel.findById(siteId).get(SiteModel.TRUSTED_UPSTREAM))
                .as("step 1: a new site is not trusted by default").isNotEqualTo(Boolean.TRUE);

            // 2. A manage grant makes it tenant-owned, and without the flag the dial refuses
            //    the private backend.
            RecordGrants.grant(GrantSubjectType.USER, tenantId, SiteModel.MODEL_ID, siteId,
                HohenheimAccess.MANAGE, true);
            proxy.reload();
            assertThat(proxyStatus(proxyPort, HOST))
                .as("step 2: a tenant-owned site without the flag may not dial loopback").isEqualTo(503);

            // 3. The tenant cannot vouch for its own upstream: a direct model save carrying
            //    the flag is refused as a frozen column and nothing is stored.
            Violations refused = catchThrowableOfType(() -> TenantConduits.as(tenantPrincipal, () -> {
                Row row = siteModel.findById(siteId);
                row.set(SiteModel.TRUSTED_UPSTREAM, true);
                siteModel.save(row);
            }), Violations.class);
            assertThat((Throwable) refused).as("step 3: a tenant setting the flag is refused").isNotNull();
            assertThat(refused.all().get(0).fieldName())
                .as("step 3: the refusal names the flag").isEqualTo(SiteModel.TRUSTED_UPSTREAM.getName());
            assertThat(refused.all().get(0).message().key())
                .as("step 3: as an operator-only column").isEqualTo("tenant_field_frozen");
            assertThat((Boolean) siteModel.findById(siteId).get(SiteModel.TRUSTED_UPSTREAM))
                .as("step 3: the flag stays off").isNotEqualTo(Boolean.TRUE);

            // 4. Nor through the delegated surface: /manage offers no such field, and a
            //    hand-posted one writes nothing.
            assertThat(new ManageSiteResource().formSpec().forView(EditView.EDIT).entries().stream()
                    .map(FormEntry::name))
                .as("step 4: the /manage form does not offer the flag")
                .doesNotContain(SiteModel.TRUSTED_UPSTREAM.getName());
            TestSession tenantSession = sessionFor(tenantId);
            httpPostForm("/manage/sites/" + siteId,
                "name=Trusted+Upstream+Site&enabled=true&trusted_upstream=true",
                tenantSession.token(), tenantSession.csrf());
            assertThat((Boolean) siteModel.findById(siteId).get(SiteModel.TRUSTED_UPSTREAM))
                .as("step 4: a hand-posted flag on /manage writes nothing").isNotEqualTo(Boolean.TRUE);
            proxy.reload();
            assertThat(proxyStatus(proxyPort, HOST))
                .as("step 4: and the dial still refuses").isEqualTo(503);

            // 5. The admin form offers it, and the operator setting it serves the site again.
            assertThat(new SiteResource().formSpec().forView(EditView.EDIT).entries().stream()
                    .map(FormEntry::name))
                .as("step 5: the admin form offers the flag")
                .contains(SiteModel.TRUSTED_UPSTREAM.getName());
            assertThat(adminGet("/admin/sites/" + siteId).body())
                .as("step 5: and renders its input").contains("name=\"trusted_upstream\"");
            Row trusted = siteModel.findById(siteId);
            trusted.set(SiteModel.TRUSTED_UPSTREAM, true);
            siteModel.save(trusted);
            proxy.reload();
            assertThat(proxyStatus(proxyPort, HOST))
                .as("step 5: a trusted tenant-owned site reaches the operator's backend").isEqualTo(200);

            // 6. The flag is the lever, not the grant: clearing it refuses the dial again.
            Row untrusted = siteModel.findById(siteId);
            untrusted.set(SiteModel.TRUSTED_UPSTREAM, false);
            siteModel.save(untrusted);
            proxy.reload();
            assertThat(proxyStatus(proxyPort, HOST))
                .as("step 6: without the flag the tenant-owned site is refused again").isEqualTo(503);
        } finally {
            proxy.stop();
            upstream.stop(0);
            RecordGrants.revoke(GrantSubjectType.USER, tenantId, SiteModel.MODEL_ID, siteId,
                HohenheimAccess.MANAGE);
            for (Row d : domainModel.findBySiteId(siteId)) domainModel.delete(d);
            siteModel.delete(site);
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
