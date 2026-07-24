package be.elevenways.hohenheim.server.security;

import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.hohenheim.server.spamservice.SpamserviceManager;
import be.elevenways.protoblast.common.Blast;
import be.elevenways.spamservice.client.Reputation;
import be.elevenways.spamservice.client.SpamserviceClient;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * Reputation-informed banning: IPs that pass the proxy's ban check are looked
 * up (ASYNC, off the request path, throttled per IP) against Hohenheim's managed
 * Spamservice runtime, and banned when the weighted net behavioral event counts across
 * {@code security.reputation_ban_categories} reach
 * {@code security.reputation_ban_threshold}. Dataset risk flags
 * (vpn/hosting/tor/proxy) are NEVER a ban signal -- only what trusted clients
 * actually reported counts. Fail-open by contract: a null reputation (down,
 * slow, unknown IP) does nothing, and an unavailable manager disables the feature
 * with near-zero request-path overhead. The ban itself goes through
 * {@link BanService#autoBan}, so every guard (never_ban, own/private IPs,
 * enforcement toggle) still applies and the ban row stays auditable -- for a
 * v6 actor that means the whole /64 (spamservice rolls reputation up per /64
 * server-side, BanService normalizes the ban target). Positive events offset
 * negative events only within the same category using
 * {@code security.reputation_positive_event_weight}; sustained lookup failure
 * reports into {@link SpamserviceHealth}.
 */
public final class ReputationBanPolicy {

    public static final ReputationBanPolicy INSTANCE = new ReputationBanPolicy();

    private static final long SETTINGS_TTL_MS = 10_000;
    /**
     * Per-actor recheck throttle: v4 uses the address and v6 uses its /64; the client's TTL cache makes repeat lookups
     * cheap, but without this every request would still queue a job.
     */
    private static final long RECHECK_TTL_MS = 60_000;
    static final int MAX_TRACKED = 50_000;

    /** Reputation lookup seam (the real one wraps {@link SpamserviceClient}). */
    interface ReputationLookup {
        @Nullable Reputation lookup(@NonNull String ip);
    }

    /** Ban seam: receives the IP and the human-readable reputation reason. */
    interface BanSink {
        void ban(@NonNull String ip, @NonNull String reason);
    }

    private final LongSupplier clock;
    private final Supplier<Boolean> availabilitySource;
    private final Supplier<SpamserviceClient> clientSource;
    private final Supplier<String> categoriesSource;
    private final Supplier<Integer> thresholdSource;
    private final Supplier<Integer> positiveWeightSource;
    private final @Nullable ReputationLookup lookupOverride;
    private final BanSink banSink;
    private final Consumer<Runnable> executor;

    // Settings snapshot, refreshed at most every SETTINGS_TTL_MS.
    private volatile long settingsReadAt = 0;
    private volatile ReputationScore.Settings settings =
        new ReputationScore.Settings(Set.of(), 25, 10, false);

    private final ConcurrentHashMap<String, Long> recentlyChecked = new ConcurrentHashMap<>();

    // AIDEV-NOTE: ONE named daemon platform thread drains the lookup queue.
    // Each lookup can block up to the client's 10s HTTP timeout, so a
    // thread-per-lookup executor would mint unbounded blocked threads under a
    // flood; and blocking HTTP on a virtual thread risks carrier pinning (see
    // ManagedProcessSiteHandler's pump-thread incident) -- a single platform
    // thread is correct. Queue overflow DROPS the lookup: reputation banning
    // is fail-open by contract, a missed lookup only delays a ban.
    private static final int LOOKUP_QUEUE_CAPACITY = 256;
    private static final long DROP_LOG_THROTTLE_MS = 60_000;
    private final ArrayBlockingQueue<Runnable> lookupQueue =
        new ArrayBlockingQueue<>(LOOKUP_QUEUE_CAPACITY);
    private final AtomicBoolean workerStarted = new AtomicBoolean();
    private final AtomicLong droppedLookups = new AtomicLong();
    private volatile long lastDropLogMs = 0;

    private ReputationBanPolicy() {
        this.clock = System::currentTimeMillis;
        this.availabilitySource = () -> SpamserviceManager.get().client() != null;
        this.clientSource = () -> SpamserviceManager.get().client();
        this.categoriesSource = () -> HohenheimSettings.VALUES.getValue(HohenheimSettings.Security.REPUTATION_BAN_CATEGORIES);
        this.thresholdSource = () -> HohenheimSettings.VALUES.getValue(HohenheimSettings.Security.REPUTATION_BAN_THRESHOLD);
        this.positiveWeightSource = () -> HohenheimSettings.VALUES.getValue(
            HohenheimSettings.Security.REPUTATION_POSITIVE_EVENT_WEIGHT);
        this.lookupOverride = null;
        this.banSink = (ip, reason) -> BanService.INSTANCE.autoBan(ip, "reputation", reason);
        this.executor = this::enqueueLookup;
    }

    /** Offer the lookup to the bounded queue (lazily starting the worker), dropping on overflow. */
    private void enqueueLookup(@NonNull Runnable task) {
        if (this.workerStarted.compareAndSet(false, true)) {
            Thread.ofPlatform().daemon().name("reputation-lookup")
                .start(this::drainLookups);
        }
        if (!this.lookupQueue.offer(task)) {
            this.droppedLookups.incrementAndGet();
            long now = this.clock.getAsLong();
            if (now - this.lastDropLogMs >= DROP_LOG_THROTTLE_MS) {
                this.lastDropLogMs = now;
                Blast.log("SECURITY: reputation lookup queue full -",
                    this.droppedLookups.getAndSet(0), "lookup(s) dropped (fail-open)");
            }
        }
    }

    private void drainLookups() {
        while (true) {
            try {
                this.lookupQueue.take().run();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (RuntimeException e) {
                // evaluate() already guards; nothing may kill the single worker.
                Blast.log("SECURITY: reputation lookup task failed -", e.getMessage());
            }
        }
    }

    /**
     * Test constructor: inject clock, settings, the lookup, the ban sink, and
     * an executor (null = the real bounded-queue single-worker path).
     */
    ReputationBanPolicy(@NonNull LongSupplier clock,
                         @NonNull Supplier<Boolean> available,
                         @NonNull Supplier<String> categories, @NonNull Supplier<Integer> threshold,
                        @NonNull Supplier<Integer> positiveWeight,
                        @NonNull ReputationLookup lookup, @NonNull BanSink banSink,
                        @Nullable Consumer<Runnable> executor) {
        this.clock = clock;
        this.availabilitySource = available;
        this.clientSource = () -> null;
        this.categoriesSource = categories;
        this.thresholdSource = threshold;
        this.positiveWeightSource = positiveWeight;
        this.lookupOverride = lookup;
        this.banSink = banSink;
        this.executor = executor != null ? executor : this::enqueueLookup;
    }

    /** Test seam: current size of the per-IP recheck-throttle map. */
    int trackedCount() {
        return this.recentlyChecked.size();
    }

    /**
     * Called on the proxy hot path for every not-banned client IP: cheap
     * throttle checks inline, the actual lookup+decision on a background job.
     */
    public void noteRequest(@Nullable String ip) {
        refreshSettings();
        ReputationScore.Settings snapshot = this.settings;
        if (!snapshot.enabled() || ip == null || !IpLiterals.isLiteral(ip)) {
            return;
        }

        long now = this.clock.getAsLong();
        String actorKey = IpLiterals.subnetKey(ip);
        if (actorKey == null) {
            return;
        }
        Long last = this.recentlyChecked.putIfAbsent(actorKey, now);
        if (last != null && now - last < RECHECK_TTL_MS) {
            return;
        }
        if (last != null && !this.recentlyChecked.replace(actorKey, last, now)) {
            return;
        }
        if (this.recentlyChecked.size() > MAX_TRACKED) {
            // AIDEV-NOTE: hard cap, clear() and nothing smarter. A removeIf
            // cutoff scan provably frees NOTHING under an IP-diverse flood
            // (every entry is recent) while burning an O(n) scan on the
            // request thread. Clearing a throttle map is fail-open: worst
            // case some duplicate (client-side cached) lookups.
            this.recentlyChecked.clear();
        }

        this.executor.accept(() -> evaluate(ip, snapshot));
    }

    /** The async part: pull reputation and ban when the category sum crosses the threshold. */
    private void evaluate(@NonNull String ip, ReputationScore.@NonNull Settings settings) {
        try {
            ReputationLookup lookup = resolveLookup();
            if (lookup == null) {
                return;
            }
            Reputation reputation = lookup.lookup(ip);
            if (reputation == null) {
                // Fail-open: no data (down, unknown, refused) is never a ban
                // signal -- but sustained failure must not stay silent.
                SpamserviceHealth.active().recordFailure("reputation lookup", null);
                return;
            }
            SpamserviceHealth.active().recordSuccess("reputation lookup");

            ReputationScore score = ReputationScore.calculate(reputation, settings);
            if (!score.reaches(settings)) {
                return;
            }
            this.banSink.ban(ip, score.auditReason(settings, reputation.windowDays()));
        } catch (RuntimeException e) {
            // The lookup contract is fail-open; anything else must not kill the job runner.
            Blast.log("SECURITY: reputation evaluation failed for", ip, "-", e.getMessage());
        }
    }

    private @Nullable ReputationLookup resolveLookup() {
        if (this.lookupOverride != null) {
            return this.lookupOverride;
        }
        SpamserviceClient current = this.clientSource.get();
        return current != null ? current::reputation : null;
    }

    private void refreshSettings() {
        long now = this.clock.getAsLong();
        if (now - this.settingsReadAt < SETTINGS_TTL_MS) {
            return;
        }
        this.settingsReadAt = now;

        this.settings = ReputationScore.Settings.of(this.categoriesSource.get(),
            this.thresholdSource.get(), this.positiveWeightSource.get(),
            Boolean.TRUE.equals(this.availabilitySource.get()));
    }

    static @NonNull Set<String> parseCategories(@Nullable String setting) {
        return ReputationScore.parseCategories(setting);
    }

    /** Test seam: forget throttle state and force a settings re-read. */
    void resetForTests() {
        this.recentlyChecked.clear();
        this.settingsReadAt = 0;
    }
}
