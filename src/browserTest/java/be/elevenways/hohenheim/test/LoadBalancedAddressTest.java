package be.elevenways.hohenheim.test;

import be.elevenways.hohenheim.HohenheimEndpoints;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.server.proxy.ProxyServer;
import be.elevenways.hohenheim.server.sitetype.ProbeableUpstream;
import be.elevenways.hohenheim.server.upstream.kinds.AddressUpstreamKind;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.validation.Violations;
import be.elevenways.zenit.test.support.ServedApp;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * Drives an address site spread over several backends through the proxy with real requests: requests take turns
 * among the backends, a backend that stops answering is passed over without a visitor seeing an error, and it takes
 * its turn again once it answers; the model refuses a further backend that is no plain host[:port].
 *
 * @author Jelle De Loecker
 * @since 0.10.0
 */
class LoadBalancedAddressTest {

    @RegisterExtension
    static final ServedApp APP = HohenheimServedApp.app();

    private static final String HOST = "balanced.lb.test";

    private static final List<HttpServer> upstreams = new ArrayList<>();
    private static ProxyServer proxy;

    @AfterAll
    static void stop() {
        if (proxy != null) {
            proxy.stop();
            proxy = null;
        }
        upstreams.forEach(upstream -> upstream.stop(0));
        upstreams.clear();
    }

    @Test
    @Timeout(60)
    void requestsTakeTurnsAndPassOverASilentBackend() throws Exception {
        HohenheimEndpoints.init();
        HttpServer first = upstream("backend-one");
        HttpServer second = upstream("backend-two");
        HttpServer third = upstream("backend-three");
        Row site = ProxyTestSupport.setupSite("hohenheim:address", "Balanced Site", "balanced-site",
            Map.of("forward_host", "127.0.0.1", "forward_port", first.getAddress().getPort(),
                "more_backends", "127.0.0.1:" + second.getAddress().getPort() + "\n 127.0.0.1:"
                    + third.getAddress().getPort() + "\n"));
        ProxyTestSupport.addDomain(site, HOST, "exact", null, false);
        proxy = ProxyTestSupport.startProxy();
        int port = ProxyTestSupport.httpPort(proxy);

        // 1. Six requests reach all three backends, two each: they take turns.
        List<String> served = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            served.add(answerOf(ProxyTestSupport.rawRequest(port, HOST, "/")));
        }
        assertThat(served).as("step 1: every request is answered by a backend")
            .allMatch(answer -> answer.startsWith("backend-"));
        assertThat(served.stream().filter("backend-one"::equals).count()).as("step 1: the first takes its turns")
            .isEqualTo(2);
        assertThat(served.stream().filter("backend-two"::equals).count()).as("step 1: the second too").isEqualTo(2);
        assertThat(served.stream().filter("backend-three"::equals).count()).as("step 1: and the third").isEqualTo(2);

        // 2. The second backend stops: no visitor sees an error, before the probe notices or after.
        int secondPort = second.getAddress().getPort();
        second.stop(0);
        upstreams.remove(second);
        Set<String> whileDown = new HashSet<>();
        for (int i = 0; i < 6; i++) {
            String response = ProxyTestSupport.rawRequest(port, HOST, "/");
            assertThat(response).as("step 2: a refused backend falls through to the next one").contains("200");
            whileDown.add(answerOf(response));
        }
        proxy.getDispatcher().probeUpstreams();
        List<String> afterProbe = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            afterProbe.add(answerOf(ProxyTestSupport.rawRequest(port, HOST, "/")));
        }
        whileDown.addAll(afterProbe);
        assertThat(whileDown).as("step 2: the others carry every request").containsExactlyInAnyOrder(
            "backend-one", "backend-three");
        assertThat(afterProbe.stream().filter("backend-one"::equals).count())
            .as("step 2: and share the silent one's turns evenly").isEqualTo(3);

        // 3. It answers again: the next probe gives it its turns back.
        HttpServer back = HttpServer.create(new InetSocketAddress("127.0.0.1", secondPort), 0);
        serve(back, "backend-two");
        proxy.getDispatcher().probeUpstreams();
        Set<String> again = new HashSet<>();
        for (int i = 0; i < 6; i++) {
            again.add(answerOf(ProxyTestSupport.rawRequest(port, HOST, "/")));
        }
        assertThat(again).as("step 3: all three serve again").containsExactlyInAnyOrder(
            "backend-one", "backend-two", "backend-three");

        // 4. A further backend that is no plain host[:port] is refused when stored, as is a port no socket has.
        for (String unusable : List.of("http://user@127.0.0.1:9/path", "127.0.0.1:70000")) {
            Violations refused = catchThrowableOfType(() -> {
                site.set(SiteModel.SETTINGS, Map.of("forward_host", "127.0.0.1", "forward_port",
                    first.getAddress().getPort(), "more_backends", unusable));
                Models.get(SiteModel.class).save(site);
            }, Violations.class);
            assertThat((Throwable) refused).as("step 4: %s is refused", unusable).isNotNull();
            assertThat(refused.all().getFirst().message().key()).as("step 4: as an invalid upstream")
                .isEqualTo("proxy_upstream_invalid");
        }

        // 5. An IPv6 backend in brackets is a plain host:port.
        site.set(SiteModel.SETTINGS, Map.of("forward_host", "127.0.0.1", "forward_port",
            first.getAddress().getPort(), "more_backends", "[::1]:" + secondPort));
        Models.get(SiteModel.class).save(site);
        assertThat(AddressUpstreamKind.moreBackends("[::1]:" + secondPort, "http"))
            .as("step 5: stored and read back as its address").extracting(ProbeableUpstream.Target::host)
            .containsExactly("::1");
    }

    private static HttpServer upstream(String name) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        serve(server, name);
        return server;
    }

    private static void serve(HttpServer server, String name) {
        server.createContext("/", exchange -> {
            byte[] body = name.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        upstreams.add(server);
    }

    /** @return the response body's backend name, or the status line when no backend answered */
    private static String answerOf(String response) {
        int body = response.indexOf("backend-");
        return body < 0 ? response.lines().findFirst().orElse("") : response.substring(body).trim();
    }
}
