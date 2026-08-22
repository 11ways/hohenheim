package be.elevenways.hohenheim.test;

import be.elevenways.hohenheim.model.SiteDomainModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.server.proxy.ProxyServer;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.validation.Violation;
import be.elevenways.zenit.common.validation.Violations;
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
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * The FQDN root dot is not a second hostname: {@code victim.test.} and {@code victim.test}
 * are one route, one claim and one certificate.
 *
 * AIDEV-NOTE: this is driven through a real {@link ProxyServer} over raw sockets, the
 * standard ReleasedClaimRegexShadowTest set, because the whole defect lived in the gap
 * between what was STORED and what was ROUTED. Before the fold, the dotted spelling was a
 * route table entry of its own that no stored claim intersected (HostnamePatterns.labelsOf
 * yields a trailing EMPTY label, which matches nothing), so a second tenant could claim it
 * and serve it -- while the TLS handshake for that same request could only ever carry the
 * DOTLESS name in SNI (RFC 6066 forbids the dot, and java.net.SNIHostName rejects it), so
 * the connection presented the victim's certificate over the raider's upstream with no
 * browser warning, and put the victim's /.well-known/acme-challenge/ under the raider too.
 * Asserting the refusal alone would not see the routing half; asserting the routing alone
 * would not see that the claim is now genuinely contested. Both are here.
 */
class TrailingDotRouteTest {

    private static boolean initialized;
    private ProxyServer proxy;
    private final List<HttpServer> upstreams = new ArrayList<>();

    private static final String VICTIM_HOST = "shop.dottest.test";
    private static final String DOTTED_HOST = VICTIM_HOST + ".";

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
    private static Row domain(Row site, String hostname) {
        Model domains = Models.get(SiteDomainModel.class);
        Row row = domains.createEmptyRow();
        row.set(SiteDomainModel.SITE_ID, site.get(SiteModel.ID));
        row.set(SiteDomainModel.HOSTNAME, hostname);
        row.set(SiteDomainModel.MATCH_TYPE, SiteDomainModel.MATCH_EXACT);
        row.set(SiteDomainModel.FORCE_SSL, false);
        domains.save(row);
        return row;
    }

    @Test
    void theDottedSpellingIsTheSameRouteAndCannotBeClaimedTwice() throws Exception {
        resetDatabase();

        // 1. The victim serves the hostname, and the proxy proves it.
        Row victim = proxySite("Dot Victim", "dot-victim", "victim-upstream");
        Row victimDomain = domain(victim, VICTIM_HOST);
        this.proxy = startProxy();
        int port = httpPort(this.proxy);
        assertThat(rawRequest(port, VICTIM_HOST, "/"))
            .as("step 1: the exact row serves the hostname").contains("victim-upstream");

        // 2. The DOTTED spelling of that same name resolves to the SAME route. This is the
        //    routing half: the request side folds the root dot exactly like the stored side,
        //    so there is no second hostname left for anyone to take.
        assertThat(rawRequest(port, DOTTED_HOST, "/"))
            .as("step 2: the root dot is the same name, so the victim serves it")
            .contains("victim-upstream");

        // 3. A raider claiming the dotted spelling is refused, and the refusal NAMES the
        //    holder -- a bare status would not distinguish this from a CSRF or a coercion
        //    failure, and the two spellings collapsing to one claim key is the whole point.
        Row raider = proxySite("Dot Raider", "dot-raider", "raider-upstream");
        Violations refused = catchThrowableOfType(
            () -> domain(raider, DOTTED_HOST), Violations.class);
        assertThat((Throwable) refused).as("step 3: the dotted spelling is a claim on a held route")
            .isNotNull();
        Violation refusal = refused.all().get(0);
        assertThat(refusal.message().key())
            .as("step 3: refused as a route another site already holds, not as bad syntax")
            .isEqualTo("route_taken_other_site");
        assertThat(refusal.fieldName()).as("step 3: anchored on the hostname").isEqualTo("hostname");

        // 4. STATE, not the status: the refusal wrote nothing, in EITHER spelling.
        assertThat(Models.get(SiteDomainModel.class).find()
            .where(SiteDomainModel.SITE_ID.eq(raider.get(SiteModel.ID))).all())
            .as("step 4: the raider holds no domain row at all").isEmpty();

        // 5. And the route table still has exactly one owner for both spellings.
        this.proxy.reload();
        assertThat(rawRequest(port, DOTTED_HOST, "/"))
            .as("step 5: the dotted host is still the victim's").contains("victim-upstream");
        assertThat(rawRequest(port, VICTIM_HOST, "/"))
            .as("step 5: and so is the dotless one").contains("victim-upstream");

        // 6. POSITIVE ANCHOR: a legitimate row written WITH the root dot still saves -- the
        //    fold is a canonicalization, not a refusal -- and stores the dotless form, which
        //    is what makes it one claim key everywhere.
        Row folded = domain(victim, "extra.dottest.test.");
        assertThat((String) Models.get(SiteDomainModel.class)
            .findById(folded.get(SiteDomainModel.ID)).get(SiteDomainModel.HOSTNAME))
            .as("step 6: the stored hostname carries no root dot")
            .isEqualTo("extra.dottest.test");
        assertThat((String) Models.get(SiteDomainModel.class)
            .findById(victimDomain.get(SiteDomainModel.ID)).get(SiteDomainModel.LIVE_ROUTE_KEY))
            .as("step 6: and the original row still holds its live claim").isNotNull();
    }
}
