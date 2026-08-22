package be.elevenways.hohenheim.test;

import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.hohenheim.model.SiteDomainModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.server.cms.SiteResource;
import be.elevenways.hohenheim.server.proxy.ProxyServer;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.security.AccessContext;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static be.elevenways.hohenheim.test.ProxyTestSupport.bootRuntime;
import static be.elevenways.hohenheim.test.ProxyTestSupport.httpPort;
import static be.elevenways.hohenheim.test.ProxyTestSupport.rawRequest;
import static be.elevenways.hohenheim.test.ProxyTestSupport.resetDatabase;
import static be.elevenways.hohenheim.test.ProxyTestSupport.setupSite;
import static be.elevenways.hohenheim.test.ProxyTestSupport.startProxy;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Why the released-claim quarantine has to judge REGEX rows: the regex tier really does
 * serve a hostname whose owner walked away.
 *
 * AIDEV-NOTE: the quarantine's original scope cut rested on "HostnamePatterns answers regex
 * by literal equality, and the regex tier is consulted LAST, which bounds the damage to
 * hosts no exact or wildcard row claims". For a RELEASED hostname that bound is EMPTY by
 * construction -- nothing claiming it is exactly what "released" means -- so last is FIRST
 * there, and a regex row spelling a different claim key walked past a key-and-glob-only
 * quarantine straight into the dangling CNAME. This test drives that routing half with the
 * quarantine turned OFF (a supported configuration: 0 days), so the takeover it enables
 * stays observable instead of being argued from a docblock. The refusal half lives in
 * RouteOwnershipInvariantTest, where the tenant-grant fixtures are.
 */
class ReleasedClaimRegexShadowTest {

    private static boolean initialized;
    private ProxyServer proxy;
    private final List<HttpServer> upstreams = new ArrayList<>();

    private static final String VICTIM_HOST = "shop.regexshadow.test";
    private static final String SHADOW_PATTERN = "^shop\\.regexshadow\\.test$";

    @BeforeAll
    static void initRuntime() throws Exception {
        if (initialized) {
            return;
        }
        initialized = true;
        bootRuntime();
    }

    @AfterEach
    void cleanUp() {
        if (this.proxy != null) {
            this.proxy.stop();
            this.proxy = null;
        }
        for (HttpServer upstream : this.upstreams) {
            upstream.stop(0);
        }
        this.upstreams.clear();
    }

    /** Upstream answering with a tag that identifies WHICH site served the request. */
    private int startUpstream(String tag) throws Exception {
        HttpServer upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        upstream.createContext("/", exchange -> {
            byte[] body = tag.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        upstream.start();
        this.upstreams.add(upstream);
        return upstream.getAddress().getPort();
    }

    private Row proxySite(String name, String slug, String tag) throws Exception {
        return setupSite("hohenheim:address", name, slug,
            Map.of("forward_host", "127.0.0.1", "forward_port", startUpstream(tag)));
    }

    /** A domain row saved through the real write pipeline, so every invariant judges it. */
    private static Row domain(Row site, String hostname, String matchType) {
        Model domains = Models.get(SiteDomainModel.class);
        Row row = domains.createEmptyRow();
        row.set(SiteDomainModel.SITE_ID, site.get(SiteModel.ID));
        row.set(SiteDomainModel.HOSTNAME, hostname);
        row.set(SiteDomainModel.MATCH_TYPE, matchType);
        row.set(SiteDomainModel.FORCE_SSL, false);
        domains.save(row);
        return row;
    }

    private static long liveClaimsOn(String hostname) {
        long claims = 0;
        for (Row row : Models.get(SiteDomainModel.class).find()
                .where(SiteDomainModel.HOSTNAME.eq(hostname)).all()) {
            if (row.get(SiteDomainModel.LIVE_ROUTE_KEY) != null) {
                claims++;
            }
        }
        return claims;
    }

    @Test
    void aRegexRowServesTheHostnameAnExactRowGaveUp() throws Exception {
        resetDatabase();
        HohenheimSettings.VALUES.setValue(HohenheimSettings.Security.RELEASE_QUARANTINE_DAYS, 0);

        // 1. The victim serves the hostname on an exact row, and the proxy proves it.
        Row victim = proxySite("Regex Shadow Victim", "regex-shadow-victim", "victim-upstream");
        domain(victim, VICTIM_HOST, SiteDomainModel.MATCH_EXACT);
        this.proxy = startProxy();
        int port = httpPort(this.proxy);
        assertThat(rawRequest(port, VICTIM_HOST, "/"))
            .as("step 1: the exact row serves the hostname").contains("victim-upstream");

        // 2. The victim abandons the site. Nothing claims the hostname any more -- but the
        //    CNAME pointing here from a zone we do not host is still live.
        new SiteResource().deleteRow(Models.get(SiteModel.class).findById(victim.get(SiteModel.ID)),
            AccessContext.anonymous());
        assertThat(liveClaimsOn(VICTIM_HOST))
            .as("step 2: the released hostname is claimed by nobody").isEqualTo(0);

        // 3. Somebody else claims it as a REGEX row: a different claim key, and no glob for
        //    a hostname-set comparison to intersect.
        Row raider = proxySite("Regex Shadow Raider", "regex-shadow-raider", "raider-upstream");
        Row shadow = domain(raider, SHADOW_PATTERN, SiteDomainModel.MATCH_REGEX);
        assertThat((String) Models.get(SiteDomainModel.class)
                .findById(shadow.get(SiteDomainModel.ID)).get(SiteDomainModel.LIVE_ROUTE_KEY))
            .as("step 3: with the quarantine off the regex row is claimed normally").isNotNull();

        // 4. The takeover: the dangling CNAME now lands in the raider's site.
        this.proxy.reload();
        assertThat(rawRequest(port, VICTIM_HOST, "/"))
            .as("step 4: the abandoned hostname is served by the raider")
            .contains("raider-upstream");
    }
}
