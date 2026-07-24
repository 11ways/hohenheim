package be.elevenways.hohenheim.server.security;

import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.spamservice.client.Reputation;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure policy tests with injected lookup/sink/executor seams -- no HTTP, no
 * DB: weighted net scoring over configured categories, fail-open behavior,
 * dataset-flag immunity, per-IP throttling and the unconfigured-is-inert guarantee.
 */
class ReputationBanPolicyTest {

    private static final Reputation.RiskFlags ALL_RISKY =
        new Reputation.RiskFlags(true, true, true, true, true, true, true);
    private static final Reputation.RiskFlags CLEAN =
        new Reputation.RiskFlags(false, false, false, false, false, false, false);

    private final AtomicLong now = new AtomicLong(1_000_000_000L);
    private final List<String> bans = new ArrayList<>();

    private static Reputation reputation(String ip, Reputation.RiskFlags flags,
                                         Map<String, Long> counts) {
        return reputation(ip, flags, counts, Map.of());
    }

    private static Reputation reputation(String ip, Reputation.RiskFlags flags,
                                         Map<String, Long> counts,
                                         Map<String, Reputation.CategoryEvents> positive) {
        Map<String, Reputation.CategoryEvents> events = new java.util.LinkedHashMap<>();
        counts.forEach((category, count) ->
            events.put(category, new Reputation.CategoryEvents(count, 0L)));
        return new Reputation(ip, flags, Map.of(), events, positive, 7);
    }

    private ReputationBanPolicy newPolicy(String url, String key, String categories,
                                           int threshold,
                                           ReputationBanPolicy.ReputationLookup lookup) {
        return newPolicy(url, key, categories, threshold, 10, lookup);
    }

    private ReputationBanPolicy newPolicy(String url, String key, String categories,
                                           int threshold, int positiveWeight,
                                           ReputationBanPolicy.ReputationLookup lookup) {
        return new ReputationBanPolicy(now::get,
            () -> url != null && !url.isBlank() && key != null && !key.isBlank(),
            () -> categories, () -> threshold, () -> positiveWeight,
            lookup, (ip, reason) -> bans.add(ip + "|" + reason), Runnable::run);
    }

    @Test
    void exactWeightedNetThresholdBansWithAuditableTotals() {
        ReputationBanPolicy policy = newPolicy("https://spam.example.com", "key", "spam,auth", 25,
            ip -> reputation(ip, CLEAN, Map.of("spam", 20L, "auth", 15L), Map.of(
                "auth", new Reputation.CategoryEvents(1, 0L))));

        policy.noteRequest("203.0.113.5");

        assertThat(bans).hasSize(1);
        assertThat(bans.get(0)).startsWith("203.0.113.5|");
        assertThat(bans.get(0)).contains("net 25 >= threshold 25")
            .contains("gross negative 35")
            .contains("applied positive credit 10")
            .contains("spam=20-(0*10)=20")
            .contains("auth=15-(1*10)=5")
            .contains("window 7d");
    }

    @Test
    void sumsBelowTheThresholdDoNotBan() {
        ReputationBanPolicy policy = newPolicy("https://spam.example.com", "key", "spam,auth", 25,
            ip -> reputation(ip, CLEAN, Map.of("spam", 12L, "auth", 12L)));

        policy.noteRequest("203.0.113.6");
        assertThat(bans).isEmpty();
    }

    @Test
    void onlyConfiguredCategoriesCount() {
        // Massive http/proxy activity is invisible when the policy watches spam,auth.
        ReputationBanPolicy policy = newPolicy("https://spam.example.com", "key", "spam,auth", 25,
            ip -> reputation(ip, CLEAN, Map.of("http", 500L, "proxy", 500L, "spam", 3L)));

        policy.noteRequest("203.0.113.7");
        assertThat(bans).isEmpty();
    }

    @Test
    void nullReputationFailsOpen() {
        ReputationBanPolicy policy = newPolicy("https://spam.example.com", "key", "spam,auth", 25,
            ip -> null);

        policy.noteRequest("203.0.113.8");
        assertThat(bans).isEmpty();
    }

    @Test
    void datasetRiskFlagsAreNeverABanSignal() {
        // Every dataset flag set, zero behavioral events: no ban.
        ReputationBanPolicy policy = newPolicy("https://spam.example.com", "key", "spam,auth", 25,
            ip -> reputation(ip, ALL_RISKY, Map.of()));

        policy.noteRequest("203.0.113.9");
        assertThat(bans).isEmpty();
    }

    @Test
    void blankUrlOrKeyIsFullyInert() {
        AtomicInteger lookups = new AtomicInteger();
        ReputationBanPolicy.ReputationLookup counting = ip -> {
            lookups.incrementAndGet();
            return reputation(ip, CLEAN, Map.of("spam", 100L));
        };

        newPolicy("", "key", "spam,auth", 25, counting).noteRequest("203.0.113.10");
        newPolicy("https://spam.example.com", "", "spam,auth", 25, counting)
            .noteRequest("203.0.113.10");

        assertThat(lookups.get()).isZero();
        assertThat(bans).isEmpty();
    }

    @Test
    void perIpLookupsAreThrottledUntilTheRecheckTtlPasses() {
        AtomicInteger lookups = new AtomicInteger();
        ReputationBanPolicy policy = newPolicy("https://spam.example.com", "key", "spam", 1000,
            ip -> {
                lookups.incrementAndGet();
                return null;
            });

        policy.noteRequest("203.0.113.11");
        policy.noteRequest("203.0.113.11");
        policy.noteRequest("203.0.113.11");
        assertThat(lookups.get()).isEqualTo(1);

        // A different IP is evaluated independently.
        policy.noteRequest("203.0.113.12");
        assertThat(lookups.get()).isEqualTo(2);

        // Past the recheck TTL the same IP is looked up again.
        now.addAndGet(61_000);
        policy.noteRequest("203.0.113.11");
        assertThat(lookups.get()).isEqualTo(3);
    }

    @Test
    void ipv6LookupsAreThrottledPerSlash64Actor() {
        AtomicInteger lookups = new AtomicInteger();
        ReputationBanPolicy policy = newPolicy("https://spam.example.com", "key", "spam", 1000,
            ip -> {
                lookups.incrementAndGet();
                return reputation(ip, CLEAN, Map.of());
            });

        policy.noteRequest("2001:db8:12:34::1");
        policy.noteRequest("2001:db8:12:34::2");
        policy.noteRequest("2001:db8:12:35::1");

        assertThat(lookups.get()).isEqualTo(2);
        assertThat(policy.trackedCount()).isEqualTo(2);
    }

    @Test
    void queuedEvaluationUsesOneImmutableSettingsSnapshot() {
        AtomicReference<String> categories = new AtomicReference<>("spam");
        AtomicInteger threshold = new AtomicInteger(15);
        AtomicInteger positiveWeight = new AtomicInteger(1);
        List<Runnable> jobs = new ArrayList<>();
        ReputationBanPolicy policy = new ReputationBanPolicy(now::get, () -> true,
            categories::get, threshold::get, positiveWeight::get,
            ip -> reputation(ip, CLEAN, Map.of("spam", 20L), Map.of(
                "spam", new Reputation.CategoryEvents(5, now.get()))),
            (ip, reason) -> bans.add(ip + "|" + reason), jobs::add);

        policy.noteRequest("203.0.113.13");
        categories.set("auth");
        threshold.set(100);
        positiveWeight.set(10);
        now.addAndGet(11_000);
        policy.noteRequest("203.0.113.14");

        assertThat(jobs).hasSize(2);
        jobs.get(0).run();
        assertThat(bans).singleElement().satisfies(reason -> assertThat(reason)
            .contains("net 15 >= threshold 15")
            .contains("spam=20-(5*1)=15")
            .doesNotContain("auth="));
    }

    @Test
    void nonLiteralIpsAreIgnored() {
        AtomicInteger lookups = new AtomicInteger();
        ReputationBanPolicy policy = newPolicy("https://spam.example.com", "key", "spam", 1,
            ip -> {
                lookups.incrementAndGet();
                return null;
            });

        policy.noteRequest(null);
        policy.noteRequest("local");
        policy.noteRequest("evil.example.com");
        assertThat(lookups.get()).isZero();
    }

    @Test
    void zeroOrNegativeThresholdIsClampedToOne() {
        // A 0 threshold must not ban IPs with zero events in the categories.
        ReputationBanPolicy zero = newPolicy("https://spam.example.com", "key", "spam,auth", 0,
            ip -> reputation(ip, CLEAN, Map.of()));
        zero.noteRequest("203.0.113.30");
        assertThat(bans).isEmpty();

        // A negative threshold behaves like 1: a single real event still bans.
        ReputationBanPolicy negative = newPolicy("https://spam.example.com", "key", "spam", -5,
            ip -> reputation(ip, CLEAN, Map.of("spam", 1L)));
        negative.noteRequest("203.0.113.31");
        assertThat(bans).hasSize(1);
        assertThat(bans.get(0)).startsWith("203.0.113.31|").contains(">= threshold 1");
    }

    @Test
    void trackedMapOverflowClearsInsteadOfScanning() {
        // An IP-diverse flood (all entries recent) must stay bounded: on
        // crossing MAX_TRACKED the map is cleared outright, never cutoff-scanned.
        ReputationBanPolicy policy = newPolicy("https://spam.example.com", "key", "spam", 1_000_000,
            ip -> null);

        int extra = 10;
        for (int i = 0; i < ReputationBanPolicy.MAX_TRACKED + extra; i++) {
            policy.noteRequest("10." + ((i >> 16) & 255) + "." + ((i >> 8) & 255) + "." + (i & 255));
        }

        // Put MAX_TRACKED+1 overflowed and cleared; only the tail re-filled.
        assertThat(policy.trackedCount()).isEqualTo(extra - 1);
    }

    @Test
    void floodedLookupsUseExactlyOneWorkerThread() throws Exception {
        java.util.concurrent.CountDownLatch drained = new java.util.concurrent.CountDownLatch(1);
        // null executor = the real bounded-queue path with the named worker.
        ReputationBanPolicy policy = new ReputationBanPolicy(now::get,
            () -> true, () -> "spam", () -> 1_000_000,
            () -> 10,
            ip -> {
                drained.countDown();
                return null;
            },
            (ip, reason) -> bans.add(ip + "|" + reason), null);

        long before = countLookupWorkers();
        for (int i = 0; i < 500; i++) {
            policy.noteRequest("11.0." + ((i >> 8) & 255) + "." + (i & 255));
        }

        // The worker demonstrably drains the queue...
        assertThat(drained.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        // ...and the flood minted exactly ONE platform daemon thread.
        assertThat(countLookupWorkers() - before).isEqualTo(1);
        assertThat(bans).isEmpty();
    }

    private static long countLookupWorkers() {
        return Thread.getAllStackTraces().keySet().stream()
            .filter(thread -> "reputation-lookup".equals(thread.getName()))
            .count();
    }

    @Test
    void oneSuccessDoesNotVetoOneThousandFailures() {
        Map<String, Reputation.CategoryEvents> positive = Map.of(
            "auth", new Reputation.CategoryEvents(1, now.get()));
        ReputationBanPolicy policy = newPolicy("https://spam.example.com", "key", "auth", 25,
            ip -> reputation(ip, CLEAN, Map.of("auth", 1_000L), positive));

        policy.noteRequest("203.0.113.40");

        assertThat(bans).singleElement()
            .satisfies(reason -> assertThat(reason).contains("positive credit 10").contains("net 990"));
    }

    @Test
    void authSuccessNeverOffsetsSpam() {
        Map<String, Reputation.CategoryEvents> positive = Map.of(
            "auth", new Reputation.CategoryEvents(1_000, now.get()));
        ReputationBanPolicy policy = newPolicy("https://spam.example.com", "key", "spam,auth", 25,
            ip -> reputation(ip, CLEAN, Map.of("spam", 25L), positive));

        policy.noteRequest("203.0.113.41");

        assertThat(bans).singleElement()
            .satisfies(reason -> assertThat(reason).contains("gross negative 25")
                .contains("positive credit 0").contains("net 25"));
    }

    @Test
    void oneSuccessOffsetsExactlyTenFailuresInItsCategory() {
        Map<String, Reputation.CategoryEvents> positive = Map.of(
            "auth", new Reputation.CategoryEvents(1, now.get()));
        ReputationBanPolicy policy = newPolicy("https://spam.example.com", "key", "auth", 1,
            ip -> reputation(ip, CLEAN,
                Map.of("auth", ip.endsWith(".42") ? 10L : 11L), positive));

        policy.noteRequest("203.0.113.42");
        policy.noteRequest("203.0.113.43");

        assertThat(bans).singleElement()
            .satisfies(reason -> assertThat(reason).startsWith("203.0.113.43|")
                .contains("positive credit 10").contains("net 1"));
    }

    @Test
    void multipleSuccessesAccumulateWithinTheirCategory() {
        Map<String, Reputation.CategoryEvents> positive = Map.of(
            "auth", new Reputation.CategoryEvents(2, now.get()));
        ReputationBanPolicy policy = newPolicy("https://spam.example.com", "key", "auth", 6,
            ip -> reputation(ip, CLEAN, Map.of("auth", 25L), positive));

        policy.noteRequest("203.0.113.44");

        assertThat(bans).isEmpty();
    }

    @Test
    void zeroWeightDisablesPositiveCredit() {
        Map<String, Reputation.CategoryEvents> positive = Map.of(
            "auth", new Reputation.CategoryEvents(1_000, now.get()));
        ReputationBanPolicy policy = newPolicy("https://spam.example.com", "key", "auth", 25, 0,
            ip -> reputation(ip, CLEAN, Map.of("auth", 25L), positive));

        policy.noteRequest("203.0.113.45");

        assertThat(bans).singleElement()
            .satisfies(reason -> assertThat(reason).contains("positive credit 0").contains("net 25"));
    }

    @Test
    void negativeWeightIsClampedToZero() {
        Map<String, Reputation.CategoryEvents> positive = Map.of(
            "auth", new Reputation.CategoryEvents(1_000, now.get()));
        ReputationBanPolicy policy = newPolicy("https://spam.example.com", "key", "auth", 25, -10,
            ip -> reputation(ip, CLEAN, Map.of("auth", 25L), positive));

        policy.noteRequest("203.0.113.46");

        assertThat(bans).singleElement()
            .satisfies(reason -> assertThat(reason).contains("auth=25-(1000*0)=25"));
    }

    @Test
    void overflowSaturatesWithoutChangingTheBanDecision() {
        Map<String, Reputation.CategoryEvents> positive = Map.of(
            "auth", new Reputation.CategoryEvents(Long.MAX_VALUE, now.get()));
        ReputationBanPolicy policy = newPolicy("https://spam.example.com", "key", "spam,auth,http", 25,
            Integer.MAX_VALUE, ip -> reputation(ip, CLEAN, Map.of(
                "spam", 25L, "auth", Long.MAX_VALUE, "http", Long.MAX_VALUE), positive));

        policy.noteRequest("203.0.113.47");

        assertThat(bans).singleElement()
            .satisfies(reason -> assertThat(reason)
                .contains("auth=" + Long.MAX_VALUE + "-(" + Long.MAX_VALUE + "*" + Integer.MAX_VALUE + ")=0")
                .contains("gross negative " + Long.MAX_VALUE)
                .contains("positive credit " + Long.MAX_VALUE)
                .contains("net " + Long.MAX_VALUE + " >= threshold 25"));
    }

    @Test
    void positiveWeightSettingHasTheDocumentedNameAndDefault() {
        assertThat(HohenheimSettings.Security.REPUTATION_POSITIVE_EVENT_WEIGHT.getName())
            .isEqualTo("reputation_positive_event_weight");
        assertThat(HohenheimSettings.Security.REPUTATION_POSITIVE_EVENT_WEIGHT.getDefaultValue())
            .isEqualTo(10);
        assertThat(HohenheimSettings.Security.REPUTATION_TTL_SECONDS.isRestartRequired()).isTrue();
    }

    @Test
    void lookupFailuresFeedTheOutageDetector() {
        List<String> sent = new ArrayList<>();
        SpamserviceHealth.setActive(new SpamserviceHealth(now::get,
            (event, subject, message) -> sent.add(event)));
        try {
            java.util.concurrent.atomic.AtomicBoolean up =
                new java.util.concurrent.atomic.AtomicBoolean(false);
            ReputationBanPolicy policy = newPolicy("https://spam.example.com", "key", "spam", 1000,
                ip -> up.get() ? reputation(ip, CLEAN, Map.of()) : null);

            // Sustained null answers (>= 5 spanning >= 5 min) notify ONCE.
            for (int i = 0; i < 8; i++) {
                policy.noteRequest("203.0.113." + (100 + i));
                now.addAndGet(90_000);
            }
            assertThat(sent).containsExactly("spamservice_outage");

            // The first real answer notifies recovery ONCE.
            up.set(true);
            policy.noteRequest("203.0.113.120");
            policy.noteRequest("203.0.113.121");
            assertThat(sent).containsExactly("spamservice_outage", "spamservice_recovered");
        } finally {
            SpamserviceHealth.setActive(null);
        }
    }

    @Test
    void categoryParsingTrimsLowercasesAndSkipsEmpties() {
        assertThat(ReputationBanPolicy.parseCategories(" Spam , AUTH ,, "))
            .containsExactly("spam", "auth");
        assertThat(ReputationBanPolicy.parseCategories(null)).isEmpty();
        assertThat(ReputationBanPolicy.parseCategories("  ")).isEmpty();
    }
}
