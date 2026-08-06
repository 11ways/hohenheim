package be.elevenways.hohenheim.server.security;

import be.elevenways.hohenheim.test.TestDatabases;
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
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

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
        TestDatabases.freshDatabase();
        HohenheimTestRuntime.ensureBooted();
    }

    @AfterEach
    void resetSettings() {
        HohenheimSettings.VALUES.setValue(HohenheimSettings.Security.BANS_ENABLED, true);
        HohenheimSettings.VALUES.setValue(HohenheimSettings.Security.NEVER_BAN, List.of());
        HohenheimSettings.VALUES.setValue(HohenheimSettings.Security.AUTO_BAN_BUDGET_PER_HOUR, 50);
    }

    private final List<String> notifications = new ArrayList<>();

    private BanService newService(boolean nftEnabled) {
        return newService(nftEnabled, System::currentTimeMillis);
    }

    private BanService newService(boolean nftEnabled, java.util.function.LongSupplier clock) {
        nftCommands.clear();
        notifications.clear();
        NftService nft = new NftService((args, stdin) -> {
            nftCommands.add(String.join(" ", args));
            return new NftRunner.Result(0, "", "");
        }, () -> nftEnabled);
        return new BanService(nft, clock,
            (event, subject, message) -> notifications.add(event));
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
            "add element inet " + NftService.table() + " banned_v4 { 198.51.100.10 timeout 3600s }");
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
            "add element inet " + NftService.table() + " banned_v4 { 198.51.100.30 }");
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
            "delete element inet " + NftService.table() + " banned_v4 { 198.51.100.40 }");
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
        service.autoBan("198.51.100.60", "auth.lockout", "score 30 over threshold");

        Row ban = Models.get(BanModel.class).find()
            .where(BanModel.IP.eq("198.51.100.60"))
            .where(BanModel.ACTIVE.eq(true))
            .first();
        assertThat(ban).isNotNull();
        assertThat(ban.get(BanModel.SOURCE)).isEqualTo(BanModel.SOURCE_AUTO);
        assertThat(ban.get(BanModel.EVENT_TYPE)).isEqualTo("auth.lockout");
        assertThat(ban.get(BanModel.EXPIRES_AT)).isNotNull();

        // Repeat triggers while banned are no-ops, and protected IPs never throw.
        service.autoBan("198.51.100.60", "auth.lockout", "score 40 over threshold");
        long count = Models.get(BanModel.class).find()
            .where(BanModel.IP.eq("198.51.100.60")).count();
        assertThat(count).isEqualTo(1);
        service.autoBan("127.0.0.1", "auth.lockout", "score 99 over threshold");
        assertThat(BanService.protectionProblem("127.0.0.1")).isNotNull();
    }

    @Test
    void disabledEnforcementTurnsOffChecksAndAutoBans() {
        BanService service = newService(false);
        service.createBan("198.51.100.70", null, BanModel.SOURCE_MANUAL, null, null);
        assertThat(service.isBanned("198.51.100.70")).isTrue();

        HohenheimSettings.VALUES.setValue(HohenheimSettings.Security.BANS_ENABLED, false);
        assertThat(service.isBanned("198.51.100.70")).isFalse();
        service.autoBan("198.51.100.71", "auth.lockout", "score 30 over threshold");
        assertThat(Models.get(BanModel.class).find()
            .where(BanModel.IP.eq("198.51.100.71")).count()).isZero();
    }

    @Test
    void neverBanAllowlistRefusesExactAndCidrMatches() {
        HohenheimSettings.VALUES.setValue(HohenheimSettings.Security.NEVER_BAN,
            List.of("203.0.113.7", "198.51.100.192/26", "2001:db8::/32"));
        BanService service = newService(false);

        // Exact v4, CIDR v4, CIDR v6: manual creates all refuse.
        for (String ip : List.of("203.0.113.7", "198.51.100.200", "198.51.100.255", "2001:db8::5")) {
            assertThatThrownBy(() -> service.createBan(ip, null, BanModel.SOURCE_MANUAL, null, null))
                .as("ip %s", ip)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("never_ban");
        }

        // Auto-bans on allowlisted IPs are refused too: no row, no enforcement.
        service.autoBan("198.51.100.200", "auth.lockout", "score 40 over threshold");
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
        service.autoBan("198.51.100.90", "auth.lockout", "reputation test reason");

        Row ban = Models.get(BanModel.class).find()
            .where(BanModel.IP.eq("198.51.100.90"))
            .where(BanModel.ACTIVE.eq(true))
            .first();
        assertThat(ban).isNotNull();
        assertThat(ban.get(BanModel.REASON)).isEqualTo("reputation test reason");
        assertThat(nftCommands).anySatisfy(cmd ->
            assertThat(cmd).startsWith("add element inet " + NftService.table() + " banned_v4 { 198.51.100.90 timeout "));
    }

    @Test
    void exhaustedAutoBanBudgetSuppressesFurtherAutoBans() {
        HohenheimSettings.VALUES.setValue(HohenheimSettings.Security.AUTO_BAN_BUDGET_PER_HOUR, 2);
        BanService service = newService(false);

        service.autoBan("192.0.2.10", "auth.lockout", "budget test 1");
        service.autoBan("192.0.2.11", "auth.lockout", "budget test 2");
        service.autoBan("192.0.2.12", "auth.lockout", "budget test 3");

        BanModel bans = Models.get(BanModel.class);
        assertThat(bans.find().where(BanModel.IP.eq("192.0.2.10")).count()).isEqualTo(1);
        assertThat(bans.find().where(BanModel.IP.eq("192.0.2.11")).count()).isEqualTo(1);
        // The third auto-ban is over budget: no row, no enforcement.
        assertThat(bans.find().where(BanModel.IP.eq("192.0.2.12")).count()).isZero();
        assertThat(service.isBanned("192.0.2.12")).isFalse();
    }

    @Test
    void protectedTargetsDoNotConsumeTheAutoBanBudget() {
        HohenheimSettings.VALUES.setValue(HohenheimSettings.Security.AUTO_BAN_BUDGET_PER_HOUR, 1);
        BanService service = newService(false);

        service.autoBan("127.0.0.1", "auth.lockout", "protected");
        service.autoBan("192.0.2.60", "auth.lockout", "first completed ban");
        service.autoBan("192.0.2.61", "auth.lockout", "over budget");

        BanModel bans = Models.get(BanModel.class);
        assertThat(bans.find().where(BanModel.IP.eq("192.0.2.60")).count()).isEqualTo(1);
        assertThat(bans.find().where(BanModel.IP.eq("192.0.2.61")).count()).isZero();
    }

    @Test
    void duplicateTargetsDoNotConsumeAnotherAutoBanSlot() {
        HohenheimSettings.VALUES.setValue(HohenheimSettings.Security.AUTO_BAN_BUDGET_PER_HOUR, 2);
        BanService service = newService(false);

        service.autoBan("192.0.2.62", "auth.lockout", "first");
        service.autoBan("192.0.2.62", "auth.lockout", "duplicate");
        service.autoBan("192.0.2.63", "auth.lockout", "second unique");
        service.autoBan("192.0.2.64", "auth.lockout", "over budget");

        BanModel bans = Models.get(BanModel.class);
        assertThat(bans.find().where(BanModel.IP.eq("192.0.2.62")).count()).isEqualTo(1);
        assertThat(bans.find().where(BanModel.IP.eq("192.0.2.63")).count()).isEqualTo(1);
        assertThat(bans.find().where(BanModel.IP.eq("192.0.2.64")).count()).isZero();
    }

    @Test
    void ipv6RotationsInOneSlash64ConsumeOneAutoBanSlot() {
        HohenheimSettings.VALUES.setValue(HohenheimSettings.Security.AUTO_BAN_BUDGET_PER_HOUR, 2);
        BanService service = newService(false);

        service.autoBan("2001:db8:55:66::1", "reputation", "first address");
        service.autoBan("2001:db8:55:66::ffff", "reputation", "same actor");
        service.autoBan("192.0.2.65", "reputation", "second unique");
        service.autoBan("192.0.2.66", "reputation", "over budget");

        BanModel bans = Models.get(BanModel.class);
        assertThat(bans.find().where(BanModel.IP.eq("2001:db8:55:66::/64")).count()).isEqualTo(1);
        assertThat(bans.find().where(BanModel.IP.eq("192.0.2.65")).count()).isEqualTo(1);
        assertThat(bans.find().where(BanModel.IP.eq("192.0.2.66")).count()).isZero();
    }

    @Test
    void concurrentIpv6RotationsSerializeIntoOneCompletedSlot() throws Exception {
        HohenheimSettings.VALUES.setValue(HohenheimSettings.Security.AUTO_BAN_BUDGET_PER_HOUR, 2);
        BanService service = newService(false);
        int workers = 12;
        CountDownLatch ready = new CountDownLatch(workers);
        CountDownLatch start = new CountDownLatch(1);
        List<Thread> threads = new ArrayList<>();
        for (int i = 0; i < workers; i++) {
            String ip = "2001:db8:77:88::" + Integer.toHexString(i + 1);
            threads.add(Thread.ofPlatform().start(() -> {
                ready.countDown();
                try {
                    start.await();
                    service.autoBan(ip, "reputation", "concurrent rotation");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }));
        }
        assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
        start.countDown();
        for (Thread thread : threads) {
            thread.join(5_000);
            assertThat(thread.isAlive()).isFalse();
        }

        service.autoBan("192.0.2.71", "reputation", "second unique");
        service.autoBan("192.0.2.72", "reputation", "over budget");
        BanModel bans = Models.get(BanModel.class);
        assertThat(bans.find().where(BanModel.IP.eq("2001:db8:77:88::/64")).count()).isEqualTo(1);
        assertThat(bans.find().where(BanModel.IP.eq("192.0.2.71")).count()).isEqualTo(1);
        assertThat(bans.find().where(BanModel.IP.eq("192.0.2.72")).count()).isZero();
    }

    @Test
    void nftFailureRollsBackTheRowAndDoesNotConsumeABudgetSlot() {
        HohenheimSettings.VALUES.setValue(HohenheimSettings.Security.AUTO_BAN_BUDGET_PER_HOUR, 1);
        java.util.concurrent.atomic.AtomicBoolean fail = new java.util.concurrent.atomic.AtomicBoolean(true);
        NftService nft = new NftService((args, stdin) -> fail.get()
            ? new NftRunner.Result(1, "", "injected failure")
            : new NftRunner.Result(0, "", ""), () -> true);
        BanService service = new BanService(nft, System::currentTimeMillis,
            (event, subject, message) -> notifications.add(event));

        service.autoBan("192.0.2.67", "reputation", "nft fails");
        assertThat(Models.get(BanModel.class).find()
            .where(BanModel.IP.eq("192.0.2.67")).count()).isZero();

        fail.set(false);
        service.autoBan("192.0.2.68", "reputation", "completed");
        service.autoBan("192.0.2.69", "reputation", "over budget");
        assertThat(Models.get(BanModel.class).find()
            .where(BanModel.IP.eq("192.0.2.68")).count()).isEqualTo(1);
        assertThat(Models.get(BanModel.class).find()
            .where(BanModel.IP.eq("192.0.2.69")).count()).isZero();
    }

    @Test
    void manualBansAreNeverBudgetLimited() {
        HohenheimSettings.VALUES.setValue(HohenheimSettings.Security.AUTO_BAN_BUDGET_PER_HOUR, 1);
        BanService service = newService(false);

        service.autoBan("192.0.2.20", "auth.lockout", "budget consumed");
        service.autoBan("192.0.2.21", "auth.lockout", "suppressed");
        assertThat(Models.get(BanModel.class).find()
            .where(BanModel.IP.eq("192.0.2.21")).count()).isZero();

        // Admin (SOURCE_MANUAL via createBan) still works with the budget spent.
        Row manual = service.createBan("192.0.2.22", "manual while exhausted",
            BanModel.SOURCE_MANUAL, null, null);
        assertThat(manual.get(BanModel.ACTIVE)).isTrue();
        assertThat(service.isBanned("192.0.2.22")).isTrue();
    }

    @Test
    void autoBanBudgetIsATrueSlidingHourAtTheBoundary() {
        HohenheimSettings.VALUES.setValue(HohenheimSettings.Security.AUTO_BAN_BUDGET_PER_HOUR, 2);
        java.util.concurrent.atomic.AtomicLong now =
            new java.util.concurrent.atomic.AtomicLong(1_000_000_000L);
        BanService service = newService(false, now::get);

        service.autoBan("192.0.2.30", "auth.lockout", "first window");
        now.addAndGet(3_599_999L);
        service.autoBan("192.0.2.31", "auth.lockout", "second near boundary");
        BanModel bans = Models.get(BanModel.class);
        assertThat(bans.find().where(BanModel.IP.eq("192.0.2.30")).count()).isEqualTo(1);
        assertThat(bans.find().where(BanModel.IP.eq("192.0.2.31")).count()).isEqualTo(1);

        // Only the oldest slot expires at the hour boundary. A fixed window
        // would incorrectly allow both following bans in this millisecond.
        now.incrementAndGet();
        service.autoBan("192.0.2.32", "auth.lockout", "oldest slot expired");
        service.autoBan("192.0.2.33", "auth.lockout", "still over sliding budget");
        assertThat(bans.find().where(BanModel.IP.eq("192.0.2.32")).count()).isEqualTo(1);
        assertThat(bans.find().where(BanModel.IP.eq("192.0.2.33")).count()).isZero();
    }

    @Test
    void budgetExhaustionNotifiesExactlyOncePerWindow() {
        HohenheimSettings.VALUES.setValue(HohenheimSettings.Security.AUTO_BAN_BUDGET_PER_HOUR, 1);
        java.util.concurrent.atomic.AtomicLong now =
            new java.util.concurrent.atomic.AtomicLong(1_000_000_000L);
        BanService service = newService(false, now::get);

        // Within budget: silent.
        service.autoBan("192.0.2.40", "auth.lockout", "budget notify 1");
        assertThat(notifications).isEmpty();

        // First exhaustion notifies once; repeats in the same window stay silent.
        service.autoBan("192.0.2.41", "auth.lockout", "suppressed");
        service.autoBan("192.0.2.42", "auth.lockout", "suppressed");
        service.autoBan("192.0.2.43", "auth.lockout", "suppressed");
        assertThat(notifications).containsExactly("auto_ban_budget_exhausted");

        // A new window re-arms the notification.
        now.addAndGet(3_600_001L);
        service.autoBan("192.0.2.44", "auth.lockout", "second window");
        assertThat(notifications).containsExactly("auto_ban_budget_exhausted");
        service.autoBan("192.0.2.45", "auth.lockout", "suppressed again");
        assertThat(notifications).containsExactly(
            "auto_ban_budget_exhausted", "auto_ban_budget_exhausted");
    }

    @Test
    void manualBansIgnoreReputationHamSignals() {
        // The ham exemption lives ONLY in the reputation policy: nothing about
        // a positive signal can make a manual createBan refuse.
        BanService service = newService(false);
        Row manual = service.createBan("192.0.2.50", "manual regardless of ham",
            BanModel.SOURCE_MANUAL, null, null);
        assertThat(manual.get(BanModel.ACTIVE)).isTrue();
        assertThat(service.isBanned("192.0.2.50")).isTrue();
        service.lift(manual, "test");
    }

    @Test
    void persistedReputationReasonKeepsTheDecisionSummaryBeforeTruncation() {
        ReputationScore.Settings settings = ReputationScore.Settings.of(
            "spam,auth,http,proxy,mail,forms", 25, Integer.MAX_VALUE, true);
        ReputationScore score = ReputationScore.calculate(Map.of(
            "spam", Long.MAX_VALUE, "auth", Long.MAX_VALUE, "http", Long.MAX_VALUE,
            "proxy", Long.MAX_VALUE, "mail", Long.MAX_VALUE, "forms", Long.MAX_VALUE),
            Map.of("auth", Long.MAX_VALUE), settings);
        String reason = score.auditReason(settings, 7);
        assertThat(reason.length()).isGreaterThan(255);

        BanService service = newService(false);
        service.autoBan("192.0.2.70", "reputation", reason);

        Row row = Models.get(BanModel.class).find()
            .where(BanModel.IP.eq("192.0.2.70")).first();
        assertThat(row).isNotNull();
        assertThat(row.get(BanModel.REASON)).hasSize(255)
            .startsWith("spamservice reputation: net " + Long.MAX_VALUE + " >= threshold 25")
            .contains("gross negative " + Long.MAX_VALUE)
            .contains("applied positive credit " + Long.MAX_VALUE)
            .contains("window 7d");
    }

    @Test
    void bootPopulatesTheOwnAddressGuard() {
        HohenheimSecurity.boot();
        assertThat(UpdateSystemIpAddresses.getLocalAddresses()).isNotEmpty();
    }

    @Test
    void bootResyncsActiveBansIntoTheKernel() throws Exception {
        BanService service = newService(true);
        service.createBan("198.51.100.80", null, BanModel.SOURCE_MANUAL, null, Duration.ofHours(2));
        service.createBan("198.51.100.81", null, BanModel.SOURCE_MANUAL, null, null);
        nftCommands.clear();

        service.boot();
        service.awaitNftBootForTests();

        assertThat(nftCommands).anySatisfy(cmd ->
            assertThat(cmd).startsWith("add table inet " + NftService.table()));
        assertThat(nftCommands).contains(
            "flush set inet " + NftService.table() + " banned_v4",
            "flush set inet " + NftService.table() + " banned_v6",
            "add element inet " + NftService.table() + " banned_v4 { 198.51.100.81 }");
        assertThat(nftCommands).anySatisfy(cmd ->
            assertThat(cmd).startsWith("add element inet " + NftService.table() + " banned_v4 { 198.51.100.80 timeout "));
    }

    @Test
    void slowKernelResyncNeverBlocksServerBoot() throws Exception {
        CountDownLatch runnerEntered = new CountDownLatch(1);
        CountDownLatch releaseRunner = new CountDownLatch(1);
        NftService slowNft = new NftService((args, stdin) -> {
            runnerEntered.countDown();
            try {
                releaseRunner.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return new NftRunner.Result(0, "", "");
        }, () -> true);
        BanService service = new BanService(slowNft);

        Thread bootCaller = Thread.ofPlatform().start(service::boot);
        try {
            assertThat(runnerEntered.await(1, TimeUnit.SECONDS)).isTrue();
            bootCaller.join(500);
            assertThat(bootCaller.isAlive()).isFalse();
        } finally {
            releaseRunner.countDown();
            bootCaller.join(5_000);
            service.awaitNftBootForTests();
        }
    }
}
