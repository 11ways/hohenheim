package be.elevenways.hohenheim.test;

import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.hohenheim.model.BanModel;
import be.elevenways.hohenheim.server.proxy.ProxyServer;
import be.elevenways.hohenheim.server.security.BanService;
import be.elevenways.hohenheim.server.security.HohenheimSecurity;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.server.security.SecurityEvent;
import be.elevenways.zenit.server.security.SecurityEvents;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static be.elevenways.hohenheim.test.ProxyTestSupport.*;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Explicit BanService bans are enforced at the proxy even with nftables
 * disabled: the dispatcher's early HTTP check refuses the client (403 before
 * routing), and the same {@code isBanned} union backs the TLS handshake gate
 * (SniKeyManager is wired to {@code dispatcher::isBanned}). The banned client
 * IP is delivered via the trusted-remote-proxy header pair, so a loopback test
 * socket can carry a bannable public address.
 */
class BanEnforcementTest {

    private static final String KEY = "ban-enforce-test-key";
    private static boolean initialized = false;
    private ProxyServer proxy;

    @BeforeAll
    static void initRuntime() throws Exception {
        if (initialized) return;
        initialized = true;
        bootRuntime();
    }

    @AfterEach
    void cleanup() {
        if (proxy != null) {
            proxy.stop();
            proxy = null;
        }
        HohenheimSettings.VALUES.setValue(HohenheimSettings.Proxy.TRUSTED_PROXY_KEYS, List.of());
    }

    @Test
    void dbBanIsRefusedAtHttpAndClearedByLift() throws Exception {
        HohenheimSettings.VALUES.setValue(HohenheimSettings.Proxy.TRUSTED_PROXY_KEYS, List.of(KEY));
        proxy = startProxy();

        Row ban = BanService.INSTANCE.createBan("203.0.113.150", "test",
            BanModel.SOURCE_MANUAL, null, null);
        assertThat(proxy.getDispatcher().isBanned("203.0.113.150")).isTrue();

        String refused = rawRequest(httpPort(proxy), "whatever.test", "/",
            "X-Hohenheim-Key: " + KEY, "X-Real-IP: 203.0.113.150");
        assertThat(refused).contains("403");

        // A different client is not collateral.
        String allowed = rawRequest(httpPort(proxy), "whatever.test", "/",
            "X-Hohenheim-Key: " + KEY, "X-Real-IP: 203.0.113.151");
        assertThat(allowed).doesNotContain("403");

        BanService.INSTANCE.lift(ban, "test");
        assertThat(proxy.getDispatcher().isBanned("203.0.113.150")).isFalse();
        String afterLift = rawRequest(httpPort(proxy), "whatever.test", "/",
            "X-Hohenheim-Key: " + KEY, "X-Real-IP: 203.0.113.150");
        assertThat(afterLift).doesNotContain("403");
    }

    @Test
    void thresholdedDomainMissesReportThroughTheSecurityEventsFunnel() throws Exception {
        HohenheimSecurity.boot();   // installs the in-process SecurityEvents sink (idempotent)
        HohenheimSettings.VALUES.setValue(HohenheimSettings.Proxy.TRUSTED_PROXY_KEYS, List.of(KEY));
        proxy = startProxy();

        // Domain misses must still travel the core funnel (that is what the
        // The manager-installed remote sink forwards to the local Spamservice;
        // storage itself remains Spamservice's job.
        java.util.List<SecurityEvent> captured =
            java.util.Collections.synchronizedList(new java.util.ArrayList<>());
        be.elevenways.zenit.server.security.SecurityEventSink sink = captured::add;
        SecurityEvents.addSink(sink);
        try {
            // Default DOMAIN_MISS_THRESHOLD is 5: the first 4 misses stay below
            // the reporting gate, the fifth (and later) report through the funnel.
            for (int i = 0; i < 6; i++) {
                rawRequest(httpPort(proxy), "miss-" + i + ".example", "/probe",
                    "X-Hohenheim-Key: " + KEY, "X-Real-IP: 203.0.113.170");
            }

            var misses = captured.stream()
                .filter(event -> "proxy.domain_miss".equals(event.type())
                    && "203.0.113.170".equals(event.remoteIp()))
                .toList();
            assertThat(misses).hasSize(2);
            assertThat(misses.get(0).detail()).containsEntry("path", "/probe");
        } finally {
            SecurityEvents.removeSink(sink);
        }
    }

    @Test
    void bannedIpStillGetsAcmeChallengesButNothingElse() throws Exception {
        HohenheimSettings.VALUES.setValue(HohenheimSettings.Proxy.TRUSTED_PROXY_KEYS, List.of(KEY));
        proxy = startProxy();

        proxy.getAcmeService().offerHttpChallenge("test-token-xyz",
            "test-token-xyz.key-auth", Set.of("renew.example"));
        Row ban = BanService.INSTANCE.createBan("203.0.113.190", "test",
            BanModel.SOURCE_MANUAL, null, null);
        try {
            // Cert renewal survives a mistaken ban: the challenge is served.
            String challenge = rawRequest(httpPort(proxy), "renew.example",
                "/.well-known/acme-challenge/test-token-xyz",
                "X-Hohenheim-Key: " + KEY, "X-Real-IP: 203.0.113.190");
            assertThat(challenge).contains("200");
            assertThat(challenge).contains("test-token-xyz.key-auth");

            // Everything else stays refused.
            String other = rawRequest(httpPort(proxy), "renew.example", "/anything",
                "X-Hohenheim-Key: " + KEY, "X-Real-IP: 203.0.113.190");
            assertThat(other).contains("403");
        } finally {
            BanService.INSTANCE.lift(ban, "test");
        }
    }

    @Test
    void enforcementComesOnlyFromBanRows() throws Exception {
        HohenheimSettings.VALUES.setValue(HohenheimSettings.Proxy.TRUSTED_PROXY_KEYS, List.of(KEY));
        proxy = startProxy();

        // A PROTECTED (private) IP pushed over the threshold gets no ban row,
        // so it is NOT refused at HTTP: the scorer never enforces directly.
        for (int i = 0; i < 30; i++) {
            rawRequest(httpPort(proxy), "scan-" + i + ".example", "/",
                "X-Hohenheim-Key: " + KEY, "X-Real-IP: 10.99.88.77");
        }
        assertThat(HohenheimSecurity.scorer().isOverThreshold("10.99.88.77")).isTrue();
        assertThat(Models.get(BanModel.class).find()
            .where(BanModel.IP.eq("10.99.88.77")).count()).isZero();
        assertThat(proxy.getDispatcher().isBanned("10.99.88.77")).isFalse();
        String stillServed = rawRequest(httpPort(proxy), "whatever.test", "/",
            "X-Hohenheim-Key: " + KEY, "X-Real-IP: 10.99.88.77");
        assertThat(stillServed).doesNotContain("403");

        // A PUBLIC IP over the threshold gets an auditable auto-ban row, and
        // THAT row is what refuses it.
        for (int i = 0; i < 30; i++) {
            rawRequest(httpPort(proxy), "scan-" + i + ".example", "/",
                "X-Hohenheim-Key: " + KEY, "X-Real-IP: 203.0.113.195");
        }
        Row autoBan = Models.get(BanModel.class).find()
            .where(BanModel.IP.eq("203.0.113.195"))
            .where(BanModel.ACTIVE.eq(true))
            .first();
        assertThat(autoBan).isNotNull();
        assertThat(autoBan.get(BanModel.SOURCE)).isEqualTo(BanModel.SOURCE_AUTO);
        assertThat(proxy.getDispatcher().isBanned("203.0.113.195")).isTrue();
        String refused = rawRequest(httpPort(proxy), "whatever.test", "/",
            "X-Hohenheim-Key: " + KEY, "X-Real-IP: 203.0.113.195");
        assertThat(refused).contains("403");

        BanService.INSTANCE.lift(autoBan, "test");
    }

    @Test
    void disabledEnforcementLetsBannedIpsThrough() throws Exception {
        HohenheimSettings.VALUES.setValue(HohenheimSettings.Proxy.TRUSTED_PROXY_KEYS, List.of(KEY));
        proxy = startProxy();
        BanService.INSTANCE.createBan("203.0.113.160", "test", BanModel.SOURCE_MANUAL, null, null);

        HohenheimSettings.VALUES.setValue(HohenheimSettings.Security.BANS_ENABLED, false);
        try {
            assertThat(proxy.getDispatcher().isBanned("203.0.113.160")).isFalse();
            String response = rawRequest(httpPort(proxy), "whatever.test", "/",
                "X-Hohenheim-Key: " + KEY, "X-Real-IP: 203.0.113.160");
            assertThat(response).doesNotContain("403");
        } finally {
            HohenheimSettings.VALUES.setValue(HohenheimSettings.Security.BANS_ENABLED, true);
        }
    }
}
