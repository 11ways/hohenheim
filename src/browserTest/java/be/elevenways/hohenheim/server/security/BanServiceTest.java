package be.elevenways.hohenheim.server.security;

import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.hohenheim.model.BanModel;
import be.elevenways.hohenheim.server.HohenheimDatabase;
import be.elevenways.hohenheim.server.task.UpdateSystemIpAddresses;
import be.elevenways.hohenheim.test.HohenheimTestRuntime;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * BanService lifecycle against a real database: create/lift/expiry sweep,
 * cache behavior, permanent bans, private-IP refusal, and the nft calls a
 * mutation produces (recorded through the executor seam, no real nft).
 */
class BanServiceTest {

    private static boolean initialized = false;

    private final List<String> nftCommands = new ArrayList<>();

    @BeforeAll
    static void initDb() throws Exception {
        if (initialized) return;
        initialized = true;
        File db = File.createTempFile("hohenheim-bans-test", ".db");
        db.delete();
        db.deleteOnExit();
        HohenheimSettings.VALUES.setValue(HohenheimSettings.Database.PATH, db.getAbsolutePath());
        HohenheimDatabase.init();
        HohenheimTestRuntime.ensureBooted();
    }

    @AfterEach
    void resetSettings() {
        HohenheimSettings.VALUES.setValue(HohenheimSettings.Security.BANS_ENABLED, true);
        HohenheimSettings.VALUES.setValue(HohenheimSettings.Security.NEVER_BAN, "");
    }

    private BanService newService(boolean nftEnabled) {
        nftCommands.clear();
        NftService nft = new NftService(args -> {
            nftCommands.add(String.join(" ", args));
            return new NftService.Result(0, "");
        }, () -> nftEnabled);
        return new BanService(nft);
    }

    @Test
    void createBanActivatesCacheAndNftElement() {
        BanService service = newService(true);
        Row ban = service.createBan("198.51.100.10", "test", BanModel.SOURCE_MANUAL,
            null, Duration.ofHours(1));

        assertThat(ban.get(BanModel.ACTIVE)).isTrue();
        assertThat(ban.get(BanModel.EXPIRES_AT)).isAfter(Instant.now());
        assertThat(service.isBanned("198.51.100.10")).isTrue();
        assertThat(service.isBanned("198.51.100.11")).isFalse();
        assertThat(nftCommands).contains(
            "add element inet hohenheim banned_v4 { 198.51.100.10 timeout 3600s }");
    }

    @Test
    void creatingAnAlreadyBannedIpReturnsTheExistingRow() {
        BanService service = newService(false);
        Row first = service.createBan("198.51.100.20", "one", BanModel.SOURCE_MANUAL, null, null);
        Row second = service.createBan("198.51.100.20", "two", BanModel.SOURCE_MANUAL, null, null);
        assertThat(second.get(BanModel.ID)).isEqualTo(first.get(BanModel.ID));
        assertThat(second.get(BanModel.REASON)).isEqualTo("one");
    }

    @Test
    void permanentBanHasNoExpiry() {
        BanService service = newService(true);
        Row ban = service.createBan("198.51.100.30", null, BanModel.SOURCE_MANUAL, null, null);
        assertThat(ban.get(BanModel.EXPIRES_AT)).isNull();
        assertThat(nftCommands).contains(
            "add element inet hohenheim banned_v4 { 198.51.100.30 }");
    }

    @Test
    void liftDeactivatesAuditsAndRemovesTheElement() {
        BanService service = newService(true);
        Row ban = service.createBan("198.51.100.40", "lift me", BanModel.SOURCE_MANUAL,
            null, Duration.ofHours(1));
        assertThat(service.isBanned("198.51.100.40")).isTrue();

        service.lift(ban, "Test Admin");

        assertThat(service.isBanned("198.51.100.40")).isFalse();
        Row reloaded = Models.get(BanModel.class).findById(ban.get(BanModel.ID));
        assertThat(reloaded.get(BanModel.ACTIVE)).isFalse();
        assertThat(reloaded.get(BanModel.LIFTED_AT)).isNotNull();
        assertThat(reloaded.get(BanModel.LIFTED_BY)).isEqualTo("Test Admin");
        assertThat(nftCommands).contains(
            "delete element inet hohenheim banned_v4 { 198.51.100.40 }");
    }

    @Test
    void expiredBansAreDeactivatedBySweepAndIgnoredByTheCache() throws Exception {
        BanService service = newService(false);
        service.createBan("198.51.100.50", "short", BanModel.SOURCE_AUTO,
            "proxy.domain_miss", Duration.ofMillis(1));
        Thread.sleep(10);

        // Expired: the cache must not consider it banned even before the sweep.
        service.refreshCache();
        assertThat(service.isBanned("198.51.100.50")).isFalse();

        int deactivated = service.deactivateExpired();
        assertThat(deactivated).isGreaterThanOrEqualTo(1);
        Row row = Models.get(BanModel.class).find()
            .where(BanModel.IP.eq("198.51.100.50")).first();
        assertThat(row.get(BanModel.ACTIVE)).isFalse();
    }

    @Test
    void privateLoopbackAndOwnIpsAreRefused() {
        BanService service = newService(false);
        for (String ip : List.of("127.0.0.1", "::1", "10.1.2.3", "192.168.1.4",
                "172.16.0.9", "169.254.1.1", "fe80::1", "fc00::1", "0.0.0.0", "not-an-ip", "")) {
            assertThatThrownBy(() -> service.createBan(ip, null, BanModel.SOURCE_MANUAL, null, null))
                .as("ip %s", ip)
                .isInstanceOf(IllegalArgumentException.class);
        }

        // The server's own discovered addresses are protected too.
        UpdateSystemIpAddresses.discover();
        for (String own : UpdateSystemIpAddresses.getLocalAddresses()) {
            assertThat(BanService.protectionProblem(own)).as("own ip %s", own).isNotNull();
        }
    }

    @Test
    void autoBanUsesTheConfiguredTtlAndDedupes() {
        BanService service = newService(false);
        service.autoBan("198.51.100.60", "auth.lockout", 30);

        Row ban = Models.get(BanModel.class).find()
            .where(BanModel.IP.eq("198.51.100.60"))
            .where(BanModel.ACTIVE.eq(true))
            .first();
        assertThat(ban).isNotNull();
        assertThat(ban.get(BanModel.SOURCE)).isEqualTo(BanModel.SOURCE_AUTO);
        assertThat(ban.get(BanModel.EVENT_TYPE)).isEqualTo("auth.lockout");
        assertThat(ban.get(BanModel.EXPIRES_AT)).isNotNull();

        // Repeat triggers while banned are no-ops, and protected IPs never throw.
        service.autoBan("198.51.100.60", "auth.lockout", 40);
        long count = Models.get(BanModel.class).find()
            .where(BanModel.IP.eq("198.51.100.60")).count();
        assertThat(count).isEqualTo(1);
        service.autoBan("127.0.0.1", "auth.lockout", 99);
        assertThat(BanService.protectionProblem("127.0.0.1")).isNotNull();
    }

    @Test
    void disabledEnforcementTurnsOffChecksAndAutoBans() {
        BanService service = newService(false);
        service.createBan("198.51.100.70", null, BanModel.SOURCE_MANUAL, null, null);
        assertThat(service.isBanned("198.51.100.70")).isTrue();

        HohenheimSettings.VALUES.setValue(HohenheimSettings.Security.BANS_ENABLED, false);
        assertThat(service.isBanned("198.51.100.70")).isFalse();
        service.autoBan("198.51.100.71", "auth.lockout", 30);
        assertThat(Models.get(BanModel.class).find()
            .where(BanModel.IP.eq("198.51.100.71")).count()).isZero();
    }

    @Test
    void neverBanAllowlistRefusesExactAndCidrMatches() {
        HohenheimSettings.VALUES.setValue(HohenheimSettings.Security.NEVER_BAN,
            "203.0.113.7, 198.51.100.192/26, 2001:db8::/32");
        BanService service = newService(false);

        // Exact v4, CIDR v4, CIDR v6: manual creates all refuse.
        for (String ip : List.of("203.0.113.7", "198.51.100.200", "198.51.100.255", "2001:db8::5")) {
            assertThatThrownBy(() -> service.createBan(ip, null, BanModel.SOURCE_MANUAL, null, null))
                .as("ip %s", ip)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("never_ban");
        }

        // Auto-bans on allowlisted IPs are refused too: no row, no enforcement.
        service.autoBan("198.51.100.200", "auth.lockout", 40);
        assertThat(Models.get(BanModel.class).find()
            .where(BanModel.IP.eq("198.51.100.200")).count()).isZero();

        // Just outside the /26 (192-255) stays bannable.
        Row allowed = service.createBan("198.51.100.100", null, BanModel.SOURCE_MANUAL, null, null);
        assertThat(allowed.get(BanModel.ACTIVE)).isTrue();
    }

    @Test
    void nonLiteralValuesAreRefusedWithoutDnsResolution() {
        BanService service = newService(false);
        // Hostnames must refuse via the literal pre-check, never resolve.
        for (String value : List.of("localhost", "example.com", "1.2.3", "1.2.3.4.5",
                "256.1.1.1", "fe80::1%eth0", "::g", "1.2.3.4/24")) {
            assertThat(BanService.protectionProblem(value)).as("value %s", value).isNotNull();
            assertThatThrownBy(() -> service.createBan(value, null, BanModel.SOURCE_MANUAL, null, null))
                .as("value %s", value)
                .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void realAutoBanReachesTheKernelWhenNftablesIsEnabled() {
        // Live BanService -> NftService wiring: a real autoBan must produce the
        // add-element argv through the runner seam (not just a DB row).
        BanService service = newService(true);
        service.autoBan("198.51.100.90", "auth.lockout", 30, "site-x");

        Row ban = Models.get(BanModel.class).find()
            .where(BanModel.IP.eq("198.51.100.90"))
            .where(BanModel.ACTIVE.eq(true))
            .first();
        assertThat(ban).isNotNull();
        assertThat(ban.get(BanModel.REASON)).contains("reporter site-x");
        assertThat(nftCommands).anySatisfy(cmd ->
            assertThat(cmd).startsWith("add element inet hohenheim banned_v4 { 198.51.100.90 timeout "));
    }

    @Test
    void bootPopulatesTheOwnAddressGuard() {
        HohenheimSecurity.boot();
        assertThat(UpdateSystemIpAddresses.getLocalAddresses()).isNotEmpty();
    }

    @Test
    void bootResyncsActiveBansIntoTheKernel() {
        BanService service = newService(true);
        service.createBan("198.51.100.80", null, BanModel.SOURCE_MANUAL, null, Duration.ofHours(2));
        service.createBan("198.51.100.81", null, BanModel.SOURCE_MANUAL, null, null);
        nftCommands.clear();

        service.boot();

        assertThat(nftCommands).anySatisfy(cmd ->
            assertThat(cmd).startsWith("add table inet hohenheim"));
        assertThat(nftCommands).contains(
            "flush set inet hohenheim banned_v4",
            "flush set inet hohenheim banned_v6",
            "add element inet hohenheim banned_v4 { 198.51.100.81 }");
        assertThat(nftCommands).anySatisfy(cmd ->
            assertThat(cmd).startsWith("add element inet hohenheim banned_v4 { 198.51.100.80 timeout "));
    }
}
