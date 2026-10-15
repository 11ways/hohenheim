package be.elevenways.hohenheim.test;

import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.hohenheim.HohenheimSources;
import be.elevenways.hohenheim.server.HohenheimRetiredNames;
import be.elevenways.hohenheim.server.proxy.ProxyServer;
import be.elevenways.hohenheim.site.UnknownHostAnswer;
import be.elevenways.protoblast.common.time.Now;
import be.elevenways.zenit.common.Zenit;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.server.setting.DryFileSource;
import be.elevenways.zenit.server.setting.RetiredName;
import be.elevenways.zenit.server.setting.SettingsEditor;
import be.elevenways.zenit.server.setting.SettingAccess;
import be.elevenways.zenit.test.support.TestAccessContexts;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static be.elevenways.hohenheim.test.ProxyTestSupport.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The operator's answer for a hostname no site serves (npmplus's "default site"), walked through the real proxy: each
 * choice of {@code proxy.unknown_hosts} against an unknown Host header, and every target that is missing or unusable
 * failing closed to the 404 page.
 */
class UnknownHostAnswerTest {

    private static final String UNKNOWN = "nobody-serves.test";

    private ProxyServer proxy;
    private final List<HttpServer> upstreams = new ArrayList<>();

    @BeforeAll
    static void initRuntime() throws Exception {
        bootRuntime();
    }

    @AfterEach
    void cleanup() {
        if (proxy != null) {
            proxy.stop();
            proxy = null;
        }
        for (HttpServer upstream : upstreams) {
            upstream.stop(0);
        }
        upstreams.clear();
        // The answer first: clearing a target the standing answer needs is refused.
        answer(UnknownHostAnswer.NOT_FOUND);
        Zenit.SETTINGS_VALUES.setValue(HohenheimSettings.Proxy.UNKNOWN_HOSTS_FORWARD, "");
        Zenit.SETTINGS_VALUES.setValue(HohenheimSettings.Proxy.UNKNOWN_HOSTS_REDIRECT, "");
        Zenit.SETTINGS_VALUES.setValue(HohenheimSettings.Proxy.UNKNOWN_HOSTS_SITE, "");
    }

    @Test
    void eachAnswerMeetsAnUnknownHostAndAMissingTargetFailsClosed() throws Exception {
        resetDatabase();
        int mainPort = startUpstream("main-site-upstream");
        int backendPort = startUpstream("fallback-backend");
        Row main = setupSite("hohenheim:address", "Main Site", "main-site", proxySettings(mainPort));
        addDomain(main, "main.test", "exact", null, false);
        Row patterned = setupSite("hohenheim:address", "Pattern Site", "pattern-site", proxySettings(mainPort));
        addDomain(patterned, "*.pattern.test", "wildcard", null, false);
        proxy = startProxy();
        int port = httpPort(proxy);

        // 1. The default: Hohenheim's 404 page, and a served name is untouched.
        assertThat(UnknownHostAnswer.fromKeyOrDefault(
            Zenit.SETTINGS_VALUES.getValue(HohenheimSettings.Proxy.UNKNOWN_HOSTS)))
            .as("step 1: the 404 page is the default answer").isEqualTo(UnknownHostAnswer.NOT_FOUND);
        assertThat(rawRequest(port, UNKNOWN, "/x")).as("step 1: an unknown host gets the 404 page")
            .startsWith("HTTP/1.1 404");
        assertThat(rawRequest(port, "main.test", "/x")).as("step 1: a served name keeps its site")
            .contains("main-site-upstream");

        // 2. Forward: the request is proxied to the fallback address (the Node original's "everything else to
        //    Apache"), never redirected to it.
        Zenit.SETTINGS_VALUES.setValue(HohenheimSettings.Proxy.UNKNOWN_HOSTS_FORWARD,
            "http://127.0.0.1:" + backendPort);
        answer(UnknownHostAnswer.FORWARD);
        String forwarded = rawRequest(port, UNKNOWN, "/x");
        assertThat(forwarded).as("step 2: the fallback backend answers").startsWith("HTTP/1.1 200")
            .contains("fallback-backend");

        // 3. A fallback that is not an http(s) address cannot be forwarded to: the 404 page.
        Zenit.SETTINGS_VALUES.setValue(HohenheimSettings.Proxy.UNKNOWN_HOSTS_FORWARD, "localhost:8080");
        assertThat(rawRequest(port, UNKNOWN, "/x")).as("step 3: an unusable address fails closed")
            .startsWith("HTTP/1.1 404");

        // 4. Redirect: a temporary redirect to exactly the operator's URL.
        Zenit.SETTINGS_VALUES.setValue(HohenheimSettings.Proxy.UNKNOWN_HOSTS_REDIRECT, "https://example.org/welcome");
        answer(UnknownHostAnswer.REDIRECT);
        String redirected = rawRequest(port, UNKNOWN, "/x");
        assertThat(redirected).as("step 4: a 302").startsWith("HTTP/1.1 302");
        assertThat(redirected).as("step 4: to the configured URL").contains("Location: https://example.org/welcome");

        // 5. One of your sites: the chosen site answers through its own route.
        Zenit.SETTINGS_VALUES.setValue(HohenheimSettings.Proxy.UNKNOWN_HOSTS_SITE, "main-site");
        answer(UnknownHostAnswer.SITE);
        assertThat(rawRequest(port, UNKNOWN, "/x")).as("step 5: the main site answers the unknown host")
            .startsWith("HTTP/1.1 200").contains("main-site-upstream");

        // 6. A site serving no exact name (only a pattern) has no route to lend: the 404 page.
        Zenit.SETTINGS_VALUES.setValue(HohenheimSettings.Proxy.UNKNOWN_HOSTS_SITE, "pattern-site");
        assertThat(rawRequest(port, UNKNOWN, "/x")).as("step 6: a pattern-only site fails closed")
            .startsWith("HTTP/1.1 404");

        // 7. No response: the connection closes without a byte, at once rather than at the read timeout.
        answer(UnknownHostAnswer.DROP);
        long before = Now.millis();
        assertThat(rawRequest(port, UNKNOWN, "/x")).as("step 7: nothing is answered").isEmpty();
        assertThat(Now.millis() - before).as("step 7: the connection was closed, not left hanging")
            .isLessThan(4000);
        assertThat(rawRequest(port, "main.test", "/x")).as("step 7: a served name still answers")
            .contains("main-site-upstream");

        // 8. An answer without its target is refused at the write, with nothing applied.
        Zenit.SETTINGS_VALUES.setValue(HohenheimSettings.Proxy.UNKNOWN_HOSTS_REDIRECT, "");
        Zenit.SETTINGS_VALUES.setValue(HohenheimSettings.Proxy.UNKNOWN_HOSTS_SITE, "");
        assertThatThrownBy(() -> answer(UnknownHostAnswer.REDIRECT)).as("step 8: redirect needs its URL")
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("unknown_hosts_redirect");
        assertThatThrownBy(() -> answer(UnknownHostAnswer.SITE)).as("step 8: a site answer needs its site")
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("unknown_hosts_site");
        assertThat(Zenit.SETTINGS_VALUES.getValue(HohenheimSettings.Proxy.UNKNOWN_HOSTS))
            .as("step 8: the standing answer is unchanged").isEqualTo(UnknownHostAnswer.DROP.storageKey());
    }

    /**
     * The fallback address was host-only (only the host's settings file set it); it is now changed in Settings like
     * npmplus's default site, by the operator's system tier only, and only to an http(s) address.
     */
    @Test
    void theFallbackAddressIsChangedInSettingsByTheSystemTier() {
        AccessContext system = TestAccessContexts.withPermissions(HohenheimSources.ADMIN_SYSTEM.value(),
            HohenheimSources.ADMIN_ACCESS.value());
        AccessContext admin = TestAccessContexts.withPermissions(HohenheimSources.ADMIN_ACCESS.value());

        // 1. The system tier changes it inside the application; it is no longer refused to everyone as host-only.
        assertThat(SettingAccess.writeRefusal(HohenheimSettings.Proxy.UNKNOWN_HOSTS_FORWARD, system))
            .as("step 1: the system tier may change the fallback address").isNull();

        // 2. An ordinary admin may not: forwarding reaches any address this host can, an operator's power.
        assertThat(SettingAccess.writeRefusal(HohenheimSettings.Proxy.UNKNOWN_HOSTS_FORWARD, admin))
            .as("step 2: an ordinary admin may not").isEqualTo(SettingsEditor.RejectionKind.NOT_PERMITTED);

        // 3. The answer itself, its redirect URL and its site are the ordinary admin's, as npmplus's default site is.
        assertThat(SettingAccess.writeRefusal(HohenheimSettings.Proxy.UNKNOWN_HOSTS, admin))
            .as("step 3: the answer is the admin's").isNull();
        assertThat(SettingAccess.writeRefusal(HohenheimSettings.Proxy.UNKNOWN_HOSTS_SITE, admin))
            .as("step 3: and so is its site").isNull();

        // 4. Only an absolute http(s) address is stored; a bare host:port is refused, a blank value unsets it.
        assertThat(HohenheimSettings.Proxy.UNKNOWN_HOSTS_FORWARD.coerce("http://127.0.0.1:8080").accepted())
            .as("step 4: an http address is accepted").isTrue();
        assertThat(HohenheimSettings.Proxy.UNKNOWN_HOSTS_FORWARD.coerce("localhost:8080").accepted())
            .as("step 4: a bare host:port is refused").isFalse();
        assertThat(HohenheimSettings.Proxy.UNKNOWN_HOSTS_FORWARD.coerce("").accepted())
            .as("step 4: blank unsets it").isTrue();
    }

    /**
     * Before the answer existed, a settings file's fallback address alone forwarded unknown hosts; boot moves that key
     * to the forward answer's target and writes the forward answer beside it, once, so such an install keeps
     * forwarding.
     */
    @Test
    void anInstallWhoseFileSetTheFallbackAddressKeepsForwarding(@TempDir Path dir) throws Exception {
        Path local = dir.resolve("local.dry");

        // 1. The old key alone: moved to the forward target, and the answer written is forward.
        Files.writeString(local, "{\"hohenheim\":{\"proxy\":{\"fallback_address\":\"http://127.0.0.1:8081\"}}}");
        assertThat(RetiredName.moveIn(local, HohenheimRetiredNames.RETIRED)).as("step 1: the old key moved")
            .containsExactly("hohenheim.proxy.fallback_address");
        Map<?, ?> proxy = proxyGroupOf(local);
        assertThat(proxy).as("step 1: the target under its new key and the forward answer, the old key gone")
            .isEqualTo(Map.of("unknown_hosts_forward", "http://127.0.0.1:8081",
                "unknown_hosts", UnknownHostAnswer.FORWARD.storageKey()));
        assertThat(UnknownHostAnswer.fromKeyOrDefault((String) proxy.get("unknown_hosts")).target())
            .as("step 1: the answer forwards to the moved address")
            .isEqualTo(HohenheimSettings.Proxy.UNKNOWN_HOSTS_FORWARD);

        // 2. An answer the file already chose is kept.
        Files.writeString(local, "{\"hohenheim\":{\"proxy\":{\"fallback_address\":\"http://127.0.0.1:8081\","
            + "\"unknown_hosts\":\"not_found\"}}}");
        RetiredName.moveIn(local, HohenheimRetiredNames.RETIRED);
        assertThat(proxyGroupOf(local).get("unknown_hosts")).as("step 2: the chosen answer stays")
            .isEqualTo(UnknownHostAnswer.NOT_FOUND.storageKey());

        // 3. A file naming the target under its new key and no answer (set in Settings) keeps the 404 page.
        Files.writeString(local, "{\"hohenheim\":{\"proxy\":{\"unknown_hosts_forward\":\"http://127.0.0.1:8081\"}}}");
        assertThat(RetiredName.moveIn(local, HohenheimRetiredNames.RETIRED)).as("step 3: nothing to move").isEmpty();
        assertThat(proxyGroupOf(local)).as("step 3: no answer was written")
            .isEqualTo(Map.of("unknown_hosts_forward", "http://127.0.0.1:8081"));
    }

    private static Map<?, ?> proxyGroupOf(Path local) {
        return (Map<?, ?>) ((Map<?, ?>) new DryFileSource(local).snapshot().get("hohenheim")).get("proxy");
    }

    private static void answer(UnknownHostAnswer answer) {
        Zenit.SETTINGS_VALUES.setValue(HohenheimSettings.Proxy.UNKNOWN_HOSTS, answer.storageKey());
    }

    private static Map<String, Object> proxySettings(int port) {
        return Map.of("forward_host", "127.0.0.1", "forward_port", port);
    }

    private int startUpstream(String tag) throws Exception {
        HttpServer upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        upstream.createContext("/", exchange -> {
            byte[] body = tag.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        upstream.start();
        upstreams.add(upstream);
        return upstream.getAddress().getPort();
    }
}
