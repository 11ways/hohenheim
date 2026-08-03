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
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * IPv6 bans work at /64 granularity: every entry point normalizes a v6 target
 * to its {@code <network>/64} key, the kernel gets ONE prefix element, and
 * enforcement matches any address inside the network without CIDR scans.
 */
class Ipv6BanGranularityTest {

    private static boolean initialized = false;

    private final List<String> nftCommands = new ArrayList<>();

    @BeforeAll
    static void initDb() throws Exception {
        if (initialized) return;
        initialized = true;
        TestDatabases.freshDatabase();
        HohenheimTestRuntime.ensureBooted();
    }

    @AfterEach
    void resetSettings() {
        HohenheimSettings.VALUES.setValue(HohenheimSettings.Security.BANS_ENABLED, true);
        HohenheimSettings.VALUES.setValue(HohenheimSettings.Security.NEVER_BAN, List.of());
        HohenheimSettings.VALUES.setValue(HohenheimSettings.Security.AUTO_BAN_BUDGET_PER_HOUR, 50);
    }

    private BanService newService(boolean nftEnabled) {
        nftCommands.clear();
        NftService nft = new NftService((args, stdin) -> {
            nftCommands.add(String.join(" ", args));
            return new NftRunner.Result(0, "", "");
        }, () -> nftEnabled);
        return new BanService(nft, System::currentTimeMillis);
    }

    @Test
    void subnetKeyNormalization() {
        assertThat(IpLiterals.subnetKey("2001:db8:1:2::5")).isEqualTo("2001:db8:1:2::/64");
        assertThat(IpLiterals.subnetKey("2001:DB8:1:2:ffff:eeee:dddd:cccc"))
            .isEqualTo("2001:db8:1:2::/64");
        assertThat(IpLiterals.subnetKey("198.51.100.10")).isEqualTo("198.51.100.10");
        assertThat(IpLiterals.subnetKey("not-an-ip")).isNull();
        assertThat(BanService.normalizeBanTarget("2001:db8:1:2::/64"))
            .isEqualTo("2001:db8:1:2::/64");
        assertThat(BanService.normalizeBanTarget("2001:db8:1:2::99/64"))
            .isEqualTo("2001:db8:1:2::/64");
        assertThat(BanService.normalizeBanTarget("2001:db8::/32")).isNull();
    }

    @Test
    void v6BanStoresAndEnforcesTheSlash64() {
        BanService service = newService(true);
        Row ban = service.createBan("2001:db8:aa:bb::17", "v6 test", BanModel.SOURCE_MANUAL,
            null, Duration.ofHours(1));

        // The row holds the /64 key, the kernel gets the prefix element.
        assertThat((String) ban.get(BanModel.IP)).isEqualTo("2001:db8:aa:bb::/64");
        assertThat(nftCommands).anySatisfy(cmd -> assertThat(cmd)
            .startsWith("add element inet hohenheim banned_v6 { 2001:db8:aa:bb::/64 timeout "));

        // Any address inside the /64 is refused; a neighboring /64 is not.
        assertThat(service.isBanned("2001:db8:aa:bb::17")).isTrue();
        assertThat(service.isBanned("2001:db8:aa:bb::9999")).isTrue();
        assertThat(service.isBanned("2001:db8:aa:bb:1:2:3:4")).isTrue();
        assertThat(service.isBanned("2001:db8:aa:bc::17")).isFalse();

        // Dedup works on the normalized key: another address in the same /64
        // returns the existing row instead of a second ban.
        Row second = service.createBan("2001:db8:aa:bb::ffff", "again", BanModel.SOURCE_MANUAL,
            null, Duration.ofHours(1));
        assertThat(second.get(BanModel.ID)).isEqualTo(ban.get(BanModel.ID));

        service.lift(ban, "test");
        assertThat(service.isBanned("2001:db8:aa:bb::17")).isFalse();
        assertThat(nftCommands).contains(
            "delete element inet hohenheim banned_v6 { 2001:db8:aa:bb::/64 }");
    }

    @Test
    void v6SetIsCreatedWithIntervalFlags() throws Exception {
        BanService service = newService(true);
        service.boot();
        service.awaitNftBootForTests();
        assertThat(nftCommands).contains(
            "add set inet hohenheim banned_v6 { type ipv6_addr ; flags interval, timeout ; }");
        // v4 stays a plain address set.
        assertThat(nftCommands).contains(
            "add set inet hohenheim banned_v4 { type ipv4_addr ; flags timeout ; }");
    }

    @Test
    void rotatingV6AddressesInOneSlash64ProduceOneBan() {
        AtomicLong now = new AtomicLong(1_000_000_000L);
        BanService service = newService(true);
        ThreatScorer scorer = new ThreatScorer(now::get, () -> 300, () -> 25, () -> 2, () -> 1);
        scorer.setAutoBanTrigger((ip, type, score) ->
            service.autoBan(ip, type, "score " + score + " over threshold"));

        // An actor rotating through addresses of one /64 crosses the shared threshold.
        for (int i = 0; i < 30; i++) {
            scorer.recordEvent("2001:db8:77:88::" + Integer.toHexString(i + 1),
                "proxy.domain_miss", 1);
        }

        BanModel bans = Models.get(BanModel.class);
        assertThat(bans.find()
            .where(BanModel.IP.eq("2001:db8:77:88::/64"))
            .where(BanModel.ACTIVE.eq(true)).count()).isEqualTo(1);
        // Only that ONE row was created for the whole flood.
        assertThat(bans.find().where(BanModel.IP.like("2001:db8:77:88%")).count()).isEqualTo(1);
        assertThat(nftCommands).anySatisfy(cmd -> assertThat(cmd)
            .startsWith("add element inet hohenheim banned_v6 { 2001:db8:77:88::/64 timeout "));

        // The neighboring /64 is unaffected.
        assertThat(service.isBanned("2001:db8:77:89::1")).isFalse();
        assertThat(scorer.isOverThreshold("2001:db8:77:89::1")).isFalse();
    }

    @Test
    void protectedAddressInsideTheRangeVetoesTheWholeSlash64() {
        // One never_ban address inside the /64 protects the entire range.
        HohenheimSettings.VALUES.setValue(HohenheimSettings.Security.NEVER_BAN,
            List.of("2001:db8:5:5::1337"));
        BanService service = newService(false);

        assertThatThrownBy(() -> service.createBan("2001:db8:5:5::42", null,
                BanModel.SOURCE_MANUAL, null, null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("never_ban");
        assertThat(BanService.protectionProblem("2001:db8:5:5::/64")).contains("never_ban");

        // A wider never_ban range protects every /64 inside it too.
        HohenheimSettings.VALUES.setValue(HohenheimSettings.Security.NEVER_BAN,
            List.of("2001:db8:6::/48"));
        assertThat(BanService.protectionProblem("2001:db8:6:77::1")).contains("never_ban");

        // The neighboring /64 stays bannable.
        HohenheimSettings.VALUES.setValue(HohenheimSettings.Security.NEVER_BAN,
            List.of("2001:db8:5:5::1337"));
        Row allowed = service.createBan("2001:db8:5:6::42", null, BanModel.SOURCE_MANUAL,
            null, null);
        assertThat((String) allowed.get(BanModel.IP)).isEqualTo("2001:db8:5:6::/64");
    }

    @Test
    void protectedV6RangesAreRefusedAtTheSlash64Level() {
        BanService service = newService(false);
        // Loopback, link-local and unique-local addresses collapse into
        // protected /64s and refuse.
        for (String ip : List.of("::1", "fe80::1", "fc00::1", "fd12:3456:789a::1")) {
            assertThatThrownBy(() -> service.createBan(ip, null, BanModel.SOURCE_MANUAL,
                    null, null))
                .as("ip %s", ip)
                .isInstanceOf(IllegalArgumentException.class);
        }
    }
}
