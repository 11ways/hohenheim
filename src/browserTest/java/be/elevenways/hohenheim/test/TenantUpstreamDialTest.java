package be.elevenways.hohenheim.test;

import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.hohenheim.server.proxy.ProxyServer;
import be.elevenways.hohenheim.server.proxy.RoutingProblem;
import be.elevenways.zenit.auth.model.GrantSubjectType;
import be.elevenways.zenit.auth.server.RecordGrants;
import be.elevenways.zenit.common.orm.datasource.Row;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static be.elevenways.hohenheim.test.ProxyTestSupport.addDomain;
import static be.elevenways.hohenheim.test.ProxyTestSupport.bootRuntime;
import static be.elevenways.hohenheim.test.ProxyTestSupport.httpPort;
import static be.elevenways.hohenheim.test.ProxyTestSupport.rawRequest;
import static be.elevenways.hohenheim.test.ProxyTestSupport.setupSite;
import static be.elevenways.hohenheim.test.ProxyTestSupport.startProxy;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/**
 * A TENANT-owned site reaches the public internet only, judged when the proxy dials and not
 * just when the setting was written: never loopback (by literal or by a name resolving there),
 * never a unix socket, never a host directory. An operator-owned site with the very same
 * settings keeps reaching its LAN or loopback backend.
 */
class TenantUpstreamDialTest {

    private static ProxyServer proxy;
    private static HttpServer upstream;
    private static final AtomicInteger upstreamHits = new AtomicInteger();

    @BeforeAll
    static void boot() throws Exception {
        bootRuntime();
        upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        upstream.createContext("/", ex -> {
            upstreamHits.incrementAndGet();
            byte[] body = "loopback-backend".getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        upstream.start();
    }

    @AfterAll
    static void stop() {
        if (proxy != null) {
            proxy.stop();
            proxy = null;
        }
        if (upstream != null) {
            upstream.stop(0);
            upstream = null;
        }
    }

    @Test
    @Timeout(60)
    void aTenantOwnedSiteNeverDialsPastThePublicInternet() throws Exception {
        int backendPort = upstream.getAddress().getPort();
        int tenantId = tenantUser();

        // Operator-owned: the reverse-proxy-to-loopback use case the product ships.
        Row operator = setupSite("hohenheim:address", "Dial Operator", "dial-operator",
            Map.of("forward_host", "127.0.0.1", "forward_port", backendPort));
        addDomain(operator, "operator.dial.test", "exact", null, false);

        // Tenant-owned, the same literal loopback target.
        Row literal = tenantSite(tenantId, "hohenheim:address", "Dial Tenant Literal", "dial-literal",
            Map.of("forward_host", "127.0.0.1", "forward_port", backendPort), "literal.dial.test");
        // Tenant-owned, a NAME that resolves to loopback (what a write-time text check misses).
        tenantSite(tenantId, "hohenheim:address", "Dial Tenant Name", "dial-name",
            Map.of("forward_host", "localhost", "forward_port", backendPort), "name.dial.test");
        // Tenant-owned, a public forward_host overridden by a unix socket.
        Row socket = tenantSite(tenantId, "hohenheim:address", "Dial Tenant Socket", "dial-socket",
            Map.of("forward_host", "example.com", "forward_port", 80, "socket", "/run/docker.sock"),
            "socket.dial.test");
        // Tenant-owned static site: a host directory.
        Path root = Files.createTempDirectory("tenant-static");
        Files.writeString(root.resolve("index.html"), "host-file");
        Row statics = tenantSite(tenantId, "hohenheim:static", "Dial Tenant Static", "dial-static",
            Map.of("root_path", root.toString()), "static.dial.test");

        proxy = startProxy();
        int port = httpPort(proxy);

        // Step 1: the operator's loopback backend is reachable, the control that proves the
        // refusals below are about ownership and not about the address being unreachable.
        assertThat(rawRequest(port, "operator.dial.test", "/"))
            .as("step 1: an operator-owned site keeps its loopback backend")
            .contains("200").contains("loopback-backend");
        int afterControl = upstreamHits.get();

        // Step 2: the tenant's literal loopback target is refused before any dial.
        assertThat(rawRequest(port, "literal.dial.test", "/"))
            .as("step 2: a tenant literal loopback upstream is refused")
            .contains("503").doesNotContain("loopback-backend");

        // Step 3: a name resolving to loopback is refused at dial time.
        assertThat(rawRequest(port, "name.dial.test", "/"))
            .as("step 3: a tenant name resolving to loopback is refused")
            .contains("502").doesNotContain("loopback-backend");

        // Step 4: a unix socket overriding a public forward_host is refused.
        assertThat(rawRequest(port, "socket.dial.test", "/"))
            .as("step 4: a tenant unix socket upstream is refused").contains("503");

        // Step 5: a host directory is refused.
        assertThat(rawRequest(port, "static.dial.test", "/"))
            .as("step 5: a tenant static root is refused")
            .contains("503").doesNotContain("host-file");

        assertThat(upstreamHits.get())
            .as("steps 2-5: no tenant request reached the loopback backend")
            .isEqualTo(afterControl);

        // Step 6: every refusal made at handler creation is a queryable routing problem.
        assertThat(proxy.getDispatcher().routingProblems())
            .as("step 6: the refused tenant upstreams are recorded against their sites")
            .extracting(RoutingProblem::siteId, RoutingProblem::reason)
            .contains(
                tuple(literal.get(SiteModel.ID),
                    RoutingProblem.Reason.HANDLER_FAULTED),
                tuple(socket.get(SiteModel.ID),
                    RoutingProblem.Reason.HANDLER_FAULTED),
                tuple(statics.get(SiteModel.ID),
                    RoutingProblem.Reason.HANDLER_FAULTED));
    }

    private static Row tenantSite(int tenantId, String kind, String name, String slug,
                                  Map<String, Object> settings, String hostname) {
        Row site = setupSite(kind, name, slug, settings);
        RecordGrants.grant(GrantSubjectType.USER, tenantId, SiteModel.MODEL_ID, site.get(SiteModel.ID),
            HohenheimAccess.MANAGE, true);
        addDomain(site, hostname, "exact", null, false);
        return site;
    }

    private static int tenantUser() {
        return ApiSupport.user("dial-tenant@hohenheim.local", "Dial Tenant");
    }
}
