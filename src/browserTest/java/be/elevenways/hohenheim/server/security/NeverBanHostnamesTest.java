package be.elevenways.hohenheim.server.security;

import be.elevenways.hohenheim.test.TestDatabases;
import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.hohenheim.model.BanModel;
import be.elevenways.hohenheim.server.HohenheimDatabase;
import be.elevenways.hohenheim.test.HohenheimTestRuntime;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Hostname entries in {@code security.never_ban}: background-resolved (fake
 * DNS through the resolver seam, never real DNS), fail-safe on resolution
 * failure, /64-wide protection for resolved v6 addresses, and unchanged
 * behavior for literal-only lists.
 */
class NeverBanHostnamesTest {

    private static boolean initialized = false;

    @BeforeAll
    static void initDb() throws Exception {
        if (initialized) return;
        initialized = true;
        TestDatabases.freshDatabase();
        HohenheimTestRuntime.ensureBooted();
    }

    @AfterEach
    void reset() {
        NeverBanHostnames.INSTANCE.setResolver(null);
        NeverBanHostnames.INSTANCE.resetForTests();
        HohenheimSettings.VALUES.setValue(HohenheimSettings.Security.NEVER_BAN, List.of());
        HohenheimSettings.VALUES.setValue(HohenheimSettings.Security.BANS_ENABLED, true);
    }

    private BanService newService() {
        NftService nft = new NftService((args, stdin) -> new NftRunner.Result(0, "", ""), () -> false);
        return new BanService(nft, System::currentTimeMillis);
    }

    @Test
    void hostnameEntriesAreExtractedLiteralsAreNot() {
        assertThat(NeverBanHostnames.extractHostnames(
            List.of("203.0.113.7", "home.example.net", "198.51.100.0/24",
                "vpn.example.org", "2001:db8::5")))
            .containsExactly("home.example.net", "vpn.example.org");
        assertThat(NeverBanHostnames.extractHostnames(null)).isEmpty();
        assertThat(NeverBanHostnames.extractHostnames(List.of("  "))).isEmpty();
    }

    @Test
    void resolvedHostnameAddressesAreUnbannable() {
        HohenheimSettings.VALUES.setValue(HohenheimSettings.Security.NEVER_BAN,
            List.of("home.example.net"));
        NeverBanHostnames.INSTANCE.setResolver(hostname ->
            List.of("203.0.113.77", "2001:db8:9:9::abcd"));
        NeverBanHostnames.INSTANCE.refresh();

        BanService service = newService();
        assertThatThrownBy(() -> service.createBan("203.0.113.77", null,
                BanModel.SOURCE_MANUAL, null, null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("never_ban");

        // The resolved v6 address protects its WHOLE /64.
        assertThatThrownBy(() -> service.createBan("2001:db8:9:9::1", null,
                BanModel.SOURCE_MANUAL, null, null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("never_ban");

        // Auto-bans refuse too (no row, no enforcement).
        service.autoBan("203.0.113.77", "auth.lockout", "score 40 over threshold");
        assertThat(Models.get(BanModel.class).find()
            .where(BanModel.IP.eq("203.0.113.77")).count()).isZero();

        // Unrelated addresses stay bannable.
        Row allowed = service.createBan("203.0.113.78", null, BanModel.SOURCE_MANUAL, null, null);
        assertThat(allowed.get(BanModel.ACTIVE)).isTrue();
    }

    @Test
    void resolutionFailureKeepsThePreviousAddressesProtected() {
        HohenheimSettings.VALUES.setValue(HohenheimSettings.Security.NEVER_BAN,
            List.of("home.example.net"));
        NeverBanHostnames.INSTANCE.setResolver(hostname -> List.of("203.0.113.80"));
        NeverBanHostnames.INSTANCE.refresh();
        assertThat(BanService.protectionProblem("203.0.113.80")).contains("never_ban");

        // DNS breaks: the last successful resolution stays in force.
        NeverBanHostnames.INSTANCE.setResolver(hostname -> {
            throw new IllegalStateException("dns down");
        });
        NeverBanHostnames.INSTANCE.refresh();
        assertThat(BanService.protectionProblem("203.0.113.80")).contains("never_ban");

        // DNS recovers with a NEW address: protection follows the operator.
        NeverBanHostnames.INSTANCE.setResolver(hostname -> List.of("203.0.113.81"));
        NeverBanHostnames.INSTANCE.refresh();
        assertThat(BanService.protectionProblem("203.0.113.81")).contains("never_ban");
        assertThat(BanService.protectionProblem("203.0.113.80")).isNull();
    }

    @Test
    void removedHostnamesLoseTheirProtection() {
        HohenheimSettings.VALUES.setValue(HohenheimSettings.Security.NEVER_BAN,
            List.of("home.example.net"));
        NeverBanHostnames.INSTANCE.setResolver(hostname -> List.of("203.0.113.85"));
        NeverBanHostnames.INSTANCE.refresh();
        assertThat(BanService.protectionProblem("203.0.113.85")).contains("never_ban");

        HohenheimSettings.VALUES.setValue(HohenheimSettings.Security.NEVER_BAN, List.of());
        NeverBanHostnames.INSTANCE.refresh();
        assertThat(BanService.protectionProblem("203.0.113.85")).isNull();
    }

    @Test
    void literalOnlyListsBehaveExactlyAsBefore() {
        HohenheimSettings.VALUES.setValue(HohenheimSettings.Security.NEVER_BAN,
            List.of("203.0.113.7", "198.51.100.192/26"));
        NeverBanHostnames.INSTANCE.setResolver(hostname -> {
            throw new AssertionError("no hostname entries, the resolver must never run");
        });
        NeverBanHostnames.INSTANCE.refresh();

        assertThat(BanService.protectionProblem("203.0.113.7")).contains("never_ban");
        assertThat(BanService.protectionProblem("198.51.100.200")).contains("never_ban");
        assertThat(BanService.protectionProblem("203.0.113.8")).isNull();
    }

    @Test
    void hostnamesNeverResolveOnTheBanPath() {
        // A hostname in the list but NOT yet refreshed: the ban path must not
        // resolve it (the resolver seam would throw), and a hostname VALUE as
        // ban target still refuses via the literal pre-check.
        HohenheimSettings.VALUES.setValue(HohenheimSettings.Security.NEVER_BAN,
            List.of("home.example.net"));
        NeverBanHostnames.INSTANCE.setResolver(hostname -> {
            throw new AssertionError("resolved on a ban path");
        });

        assertThat(BanService.protectionProblem("203.0.113.90")).isNull();
        assertThat(BanService.protectionProblem("home.example.net"))
            .isEqualTo("not a literal IP address");
    }

    @Test
    void settingChangesRefreshHostnamesAsynchronously() throws Exception {
        CountDownLatch resolved = new CountDownLatch(1);
        NeverBanHostnames.INSTANCE.setResolver(hostname -> {
            resolved.countDown();
            return List.of("203.0.113.91");
        });
        HohenheimSecurity.boot();

        HohenheimSettings.VALUES.setValue(HohenheimSettings.Security.NEVER_BAN,
            List.of("updated.example.net"));

        assertThat(resolved.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(BanService.protectionProblem("203.0.113.91")).contains("never_ban");
    }
}
