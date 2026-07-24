package be.elevenways.hohenheim.server.security;

import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.hohenheim.model.BanModel;
import be.elevenways.hohenheim.server.notification.Alerts;
import be.elevenways.hohenheim.server.notification.NotificationEvents;
import be.elevenways.hohenheim.server.task.UpdateSystemIpAddresses;
import be.elevenways.protoblast.common.Blast;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;

/**
 * IP ban lifecycle: creation (auto from the threat scorer and the reputation
 * policy, manual from the admin), lifting, expiry, the O(1) in-memory
 * active-ban cache the proxy hot paths consult, the nftables enforcement
 * tier, and the global auto-ban budget every automatic path shares. The bans
 * table is the source of truth; the cache refreshes on every mutation and at
 * boot.
 *
 * Ban granularity: v4 actors are banned by exact address, v6 actors by their
 * /64 network (a single v6 actor controls the whole /64) -- every entry point
 * normalizes v6 targets to the {@code <network>/64} key stored in the ip
 * column, and lookups normalize the incoming address the same way (O(1), no
 * CIDR scans).
 */
public final class BanService {

    public static final BanService INSTANCE = new BanService(new NftService());

    private static final long AUTO_BAN_WINDOW_MS = 3_600_000;

    private final NftService nft;
    private final LongSupplier clock;
    private final SecurityNotifier notifier;
    private final AtomicBoolean nftBootStarted = new AtomicBoolean();
    private volatile @Nullable Thread nftBootThread;

    // Snapshot of active, unexpired banned IPs; swapped atomically on refresh.
    private volatile Set<String> activeIps = Set.of();
    private volatile boolean cacheLoaded = false;

    // AIDEV-NOTE: the auto-ban budget lives HERE, at the autoBan funnel, so
    // every current and future automatic trigger (threat scorer, reputation
    // policy, ...) is covered without remembering to wire it. One compromised
    // trusted reporter or a spamservice bug must not convert into a mass ban
    // of all visitors: the budget turns that into a bounded incident with a
    // loud log signal. Manual admin bans go through createBan directly and
    // are deliberately never budget-limited.
    private final ArrayDeque<Long> completedAutoBans = new ArrayDeque<>();
    private long budgetSuppressed;
    private boolean budgetWarned;

    BanService(@NonNull NftService nft) {
        this(nft, System::currentTimeMillis);
    }

    /** Test constructor: inject the clock the auto-ban budget window uses. */
    BanService(@NonNull NftService nft, @NonNull LongSupplier clock) {
        this(nft, clock, Alerts::send);
    }

    /** Test constructor: additionally inject the operator-notification sink. */
    BanService(@NonNull NftService nft, @NonNull LongSupplier clock,
               @NonNull SecurityNotifier notifier) {
        this.nft = nft;
        this.clock = clock;
        this.notifier = notifier;
    }

    NftService nft() {
        return this.nft;
    }

    /**
     * Warm the app-level ban cache synchronously, then reconcile nftables off
     * the critical server boot path. The proxy can enforce from the cache as
     * soon as this method returns even when sudo/nft is unusually slow.
     */
    public void boot() {
        refreshCache();
        if (nft.isEnabled() && this.nftBootStarted.compareAndSet(false, true)) {
            this.nftBootThread = Thread.ofPlatform().daemon().name("nft-resync").start(() -> {
                Blast.log("NFT: background boot reconciliation started");
                nft.setup(NftService.configuredPorts());
                resyncNftables();
                Blast.log("NFT: background boot reconciliation finished");
            });
        }
    }

    // -----------------------------------------------------------------------
    // Hot path
    // -----------------------------------------------------------------------

    /**
     * O(1) check against the cached active-ban set (app-level enforcement
     * only): v4 addresses match exactly, v6 addresses match their /64 key.
     */
    public boolean isBanned(@NonNull String ip) {
        if (!enforcementEnabled()) {
            return false;
        }
        ensureCacheLoaded();
        if (ip.indexOf(':') >= 0) {
            String key = IpLiterals.subnetKey(ip);
            return key != null && activeIps.contains(key);
        }
        return activeIps.contains(ip);
    }

    public boolean enforcementEnabled() {
        return Boolean.TRUE.equals(
            HohenheimSettings.VALUES.getValue(HohenheimSettings.Security.BANS_ENABLED));
    }

    // -----------------------------------------------------------------------
    // Mutations
    // -----------------------------------------------------------------------

    /**
     * Automatic-ban entry point (threat scorer, reputation policy): create a
     * ban with the configured TTL and the caller's reason. Idempotent for
     * already-banned IPs, a no-op when bans are disabled, and subject to the
     * global {@code security.auto_ban_budget_per_hour} budget.
     */
    public void autoBan(@NonNull String ip, @Nullable String eventType, @NonNull String reason) {
        if (!enforcementEnabled()) {
            return;
        }
        int exhaustedBudget = 0;
        synchronized (this) {
            String trimmed = ip.trim();
            String problem = protectionProblem(trimmed);
            if (problem != null) {
                logRefusalThrottled(ip, problem);
                return;
            }
            String normalized = normalizeBanTarget(trimmed);
            if (normalized == null) {
                logRefusalThrottled(ip, "not a literal IP address");
                return;
            }

            BanModel bans = Models.get(BanModel.class);
            Row existing = findActiveBan(bans, normalized);
            if (existing != null) {
                return;
            }

            long now = this.clock.getAsLong();
            int budget = configuredAutoBanBudget();
            pruneAutoBanBudget(now, budget);
            if (this.completedAutoBans.size() >= budget) {
                this.budgetSuppressed++;
                if (!this.budgetWarned) {
                    this.budgetWarned = true;
                    exhaustedBudget = budget;
                    Blast.slog("security.auto_ban_budget_exceeded", Map.of("budget", budget));
                    Blast.log("BANS: global auto-ban budget of", budget,
                        "per sliding hour EXHAUSTED; further automatic bans are SUPPRESSED"
                            + " (possible trigger runaway or event poisoning)");
                }
            } else {
                try {
                    int ttlHours = HohenheimSettings.VALUES.getValue(
                        HohenheimSettings.Security.AUTO_BAN_TTL_HOURS);
                    createBanNormalized(bans, normalized, reason, BanModel.SOURCE_AUTO,
                        eventType, Duration.ofHours(Math.max(1, ttlHours)), true);
                    this.completedAutoBans.addLast(now);
                } catch (RuntimeException e) {
                    Blast.log("BANS: auto-ban failed for", ip, "-", e.getMessage());
                }
            }
        }
        if (exhaustedBudget > 0) {
            notifyBudgetExhausted(exhaustedBudget);
        }
    }

    /**
     * Global sliding-hour auto-ban budget over successfully completed unique rows.
     */
    private int configuredAutoBanBudget() {
        Integer configured = HohenheimSettings.VALUES.getValue(
            HohenheimSettings.Security.AUTO_BAN_BUDGET_PER_HOUR);
        return Math.max(1, configured != null ? configured : 50);
    }

    private void pruneAutoBanBudget(long now, int budget) {
        long cutoff = now - AUTO_BAN_WINDOW_MS;
        while (!this.completedAutoBans.isEmpty()
                && this.completedAutoBans.peekFirst() <= cutoff) {
            this.completedAutoBans.removeFirst();
        }
        if (this.completedAutoBans.size() < budget && this.budgetWarned) {
            if (this.budgetSuppressed > 0) {
                Blast.log("BANS: auto-ban budget available again -",
                    this.budgetSuppressed, "automatic ban(s) were suppressed");
            }
            this.budgetSuppressed = 0;
            this.budgetWarned = false;
        }
    }

    private void notifyBudgetExhausted(int budget) {
        try {
            this.notifier.send(NotificationEvents.AUTO_BAN_BUDGET_EXHAUSTED,
                "Auto-ban budget exhausted",
                "The global auto-ban budget of " + budget + " per sliding hour is"
                    + " exhausted; further AUTOMATIC bans are suppressed until a completed"
                    + " ban ages out (possible trigger runaway or event poisoning)."
                    + " Manual bans still work.");
        } catch (RuntimeException e) {
            Blast.log("BANS: could not send budget-exhaustion notification -",
                e.getMessage());
        }
    }

    /**
     * Create a ban (null ttl = permanent). Returns the existing active row when
     * the IP is already banned.
     *
     * @throws IllegalArgumentException when the IP is unparseable or protected
     *         (loopback/private/link-local or one of this server's own addresses)
     */
    public synchronized @NonNull Row createBan(@NonNull String ip, @Nullable String reason,
                                               @NonNull String source,
                                               @Nullable String eventType,
                                               @Nullable Duration ttl) {
        String trimmed = ip.trim();
        String problem = protectionProblem(trimmed);
        if (problem != null) {
            throw new IllegalArgumentException(problem);
        }
        // Non-null: protectionProblem already refused unnormalizable values.
        String normalized = normalizeBanTarget(trimmed);

        BanModel bans = Models.get(BanModel.class);
        Row existing = findActiveBan(bans, normalized);
        if (existing != null) {
            return existing;
        }

        return createBanNormalized(bans, normalized, reason, source, eventType, ttl, false);
    }

    private @NonNull Row createBanNormalized(@NonNull BanModel bans, @NonNull String normalized,
                                             @Nullable String reason, @NonNull String source,
                                             @Nullable String eventType, @Nullable Duration ttl,
                                             boolean rollbackOnNftFailure) {
        Instant now = Instant.now();
        Row row = bans.createEmptyRow();
        row.set(BanModel.IP, normalized);
        row.set(BanModel.REASON, truncate(reason, 255));
        row.set(BanModel.SOURCE, source);
        row.set(BanModel.EVENT_TYPE, eventType);
        row.set(BanModel.EXPIRES_AT, ttl != null ? now.plus(ttl) : null);
        row.set(BanModel.ACTIVE, true);
        bans.save(row);

        boolean nftApplied = nft.addBan(normalized, ttl != null ? ttl.toSeconds() : null);
        if (!nftApplied && rollbackOnNftFailure) {
            if (!bans.delete(row)) {
                throw new IllegalStateException("Could not roll back ban after nftables failure");
            }
            refreshCache();
            throw new IllegalStateException("nftables rejected the automatic ban");
        }
        refreshCache();
        Blast.log("BANS: banned", normalized, "(" + source + ")",
            ttl != null ? "for " + ttl : "permanently");
        return row;
    }

    private static @Nullable Row findActiveBan(@NonNull BanModel bans, @NonNull String normalized) {
        Row existing = bans.find()
            .where(BanModel.IP.eq(normalized))
            .where(BanModel.ACTIVE.eq(true))
            .first();
        return existing != null && !isExpired(existing) ? existing : null;
    }

    /** Lift an active ban: audit-stamps the row and removes the kernel element. */
    public synchronized void lift(@NonNull Row ban, @Nullable String liftedBy) {
        BanModel bans = Models.get(BanModel.class);
        Integer id = ban.get(BanModel.ID);
        if (id == null) {
            return;
        }
        bans.find().where(BanModel.ID.eq(id))
            .assign(BanModel.ACTIVE, false)
            .assign(BanModel.LIFTED_AT, Instant.now())
            .assign(BanModel.LIFTED_BY, truncate(liftedBy, 200))
            .updateAll();

        String ip = ban.get(BanModel.IP);
        if (ip != null && !stillActivelyBanned(ip)) {
            nft.removeBan(ip);
        }
        refreshCache();
    }

    /** All active, unexpired ban rows. */
    public @NonNull List<Row> listActive() {
        BanModel bans = Models.get(BanModel.class);
        List<Row> result = new ArrayList<>();
        for (Row row : bans.find().where(BanModel.ACTIVE.eq(true)).all()) {
            if (!isExpired(row)) {
                result.add(row);
            }
        }
        return result;
    }

    /**
     * Deactivate expired DB rows (the kernel already timed their elements out)
     * and refresh the cache. Called by the sweeper task.
     *
     * @return the number of rows deactivated
     */
    public synchronized int deactivateExpired() {
        BanModel bans = Models.get(BanModel.class);
        int updated = bans.find()
            .where(BanModel.ACTIVE.eq(true))
            .where(BanModel.EXPIRES_AT.isNotNull())
            .where(BanModel.EXPIRES_AT.lte(Instant.now()))
            .assign(BanModel.ACTIVE, false)
            .updateAll();
        if (updated > 0) {
            refreshCache();
        }
        return updated;
    }

    /** Flush both kernel sets and re-add every active DB ban with its remaining ttl. */
    public synchronized void resyncNftables() {
        List<NftService.ActiveBan> active = new ArrayList<>();
        Instant now = Instant.now();
        for (Row row : listActive()) {
            String ip = row.get(BanModel.IP);
            if (ip == null) {
                continue;
            }
            Instant expires = row.get(BanModel.EXPIRES_AT);
            Long ttl = expires != null ? Math.max(1, Duration.between(now, expires).toSeconds()) : null;
            active.add(new NftService.ActiveBan(ip, ttl));
        }
        nft.resync(active);
    }

    /** Test seam: wait for the one background boot reconciliation. */
    void awaitNftBootForTests() throws InterruptedException {
        Thread thread = this.nftBootThread;
        if (thread != null) {
            thread.join(5_000);
            if (thread.isAlive()) {
                throw new IllegalStateException("nft boot reconciliation did not finish");
            }
        }
    }

    // -----------------------------------------------------------------------
    // Cache
    // -----------------------------------------------------------------------

    /** Rebuild the in-memory active-IP set from the database. */
    public synchronized void refreshCache() {
        try {
            Set<String> ips = new java.util.HashSet<>();
            for (Row row : listActive()) {
                String ip = row.get(BanModel.IP);
                if (ip != null) {
                    ips.add(ip);
                }
            }
            activeIps = Set.copyOf(ips);
            cacheLoaded = true;
        } catch (RuntimeException e) {
            // Datasource not up yet (early boot): stay empty, retry on next mutation/check.
            Blast.log("BANS: cache refresh failed -", e.getMessage());
        }
    }

    private void ensureCacheLoaded() {
        if (!cacheLoaded) {
            refreshCache();
        }
    }

    /** Test seam: forget the cached set so the next check reloads from the DB. */
    void invalidateCache() {
        cacheLoaded = false;
        activeIps = Set.of();
    }

    // -----------------------------------------------------------------------
    // Safety
    // -----------------------------------------------------------------------

    /**
     * The stored/enforced key of a ban target: v4 stays the exact (canonical)
     * address, a v6 address (or an already-keyed {@code <network>/64}) becomes
     * its canonical /64 CIDR string.
     *
     * @return the normalized key, or null when the value is not bannable text
     */
    static @Nullable String normalizeBanTarget(@NonNull String ip) {
        String trimmed = ip.trim();
        String suffix = "/" + IpLiterals.V6_SUBNET_PREFIX;
        if (trimmed.indexOf(':') >= 0 && trimmed.endsWith(suffix)) {
            byte[] network = IpLiterals.parse(
                trimmed.substring(0, trimmed.length() - suffix.length()));
            return network != null && network.length == 16
                ? IpLiterals.formatV6Subnet(network) : null;
        }
        return IpLiterals.subnetKey(trimmed);
    }

    /**
     * CRITICAL SAFETY: never ban loopback, private/link-local ranges, one of
     * this server's own addresses, or anything on the {@code security.never_ban}
     * operator allowlist (literal entries AND background-resolved hostname
     * entries) -- a poisoned or misread client IP must not be able to firewall
     * the operator (or the server itself) out. The literal pre-check guarantees
     * no DNS lookup ever happens on this path. A v6 target is judged as its
     * whole /64: one protected address inside the range vetoes the range.
     *
     * @return a refusal reason, or null when the IP may be banned
     */
    public static @Nullable String protectionProblem(@NonNull String ip) {
        if (ip.isBlank()) {
            return "empty ip";
        }
        String key = normalizeBanTarget(ip);
        if (key == null) {
            return "not a literal IP address";
        }
        if (key.indexOf(':') >= 0) {
            return v6SubnetProblem(key);
        }
        return v4Problem(key);
    }

    private static @Nullable String v4Problem(@NonNull String key) {
        byte[] literal = IpLiterals.parse(key);
        if (literal == null) {
            return "not a literal IP address";
        }
        InetAddress address;
        try {
            address = InetAddress.getByAddress(literal);
        } catch (UnknownHostException e) {
            return "not a valid IP address";
        }
        if (address.isLoopbackAddress() || address.isAnyLocalAddress()) {
            return "loopback address";
        }
        if (address.isSiteLocalAddress() || address.isLinkLocalAddress()) {
            return "private address";
        }
        for (String own : UpdateSystemIpAddresses.getLocalAddresses()) {
            if (key.equals(own)) {
                return "one of this server's own addresses";
            }
        }
        List<String> neverBan = HohenheimSettings.VALUES.getValue(HohenheimSettings.Security.NEVER_BAN);
        if (IpLiterals.matchesList(literal, neverBan)) {
            return "on the security.never_ban allowlist";
        }
        if (NeverBanHostnames.INSTANCE.protects(key)) {
            return "on the security.never_ban allowlist (resolved hostname)";
        }
        return null;
    }

    /**
     * Judge a whole v6 /64: the checks run against the RANGE, so any protected
     * address inside it (loopback via the ::/64 network, an own address, a
     * never_ban entry or resolved hostname address) vetoes the entire ban.
     */
    private static @Nullable String v6SubnetProblem(@NonNull String key) {
        byte[] network = IpLiterals.parse(key.substring(0, key.indexOf('/')));
        if (network == null) {
            return "not a literal IP address";
        }
        InetAddress address;
        try {
            address = InetAddress.getByAddress(network);
        } catch (UnknownHostException e) {
            return "not a valid IP address";
        }
        // ::1 lives in ::/64, whose network address is the any-local :: --
        // the network-address checks therefore cover the protected /64s.
        if (address.isLoopbackAddress() || address.isAnyLocalAddress()) {
            return "loopback address";
        }
        if (address.isSiteLocalAddress() || address.isLinkLocalAddress()
                || isUniqueLocalV6(address)) {
            return "private address";
        }
        for (String own : UpdateSystemIpAddresses.getLocalAddresses()) {
            if (own.indexOf(':') >= 0 && key.equals(IpLiterals.subnetKey(own))) {
                return "contains one of this server's own addresses";
            }
        }
        List<String> neverBan = HohenheimSettings.VALUES.getValue(HohenheimSettings.Security.NEVER_BAN);
        if (IpLiterals.listOverlapsV6Subnet(network, neverBan)) {
            return "contains an address on the security.never_ban allowlist";
        }
        if (NeverBanHostnames.INSTANCE.protects(key)) {
            return "contains an address on the security.never_ban allowlist (resolved hostname)";
        }
        return null;
    }

    // Per-IP refusal-log throttle so a scanning loop cannot flood the log.
    private static final long REFUSAL_LOG_THROTTLE_MS = 60_000;
    private static final ConcurrentHashMap<String, Long> refusalLogTimes = new ConcurrentHashMap<>();

    private static void logRefusalThrottled(@NonNull String ip, @Nullable String reason) {
        long now = System.currentTimeMillis();
        Long last = refusalLogTimes.get(ip);
        if (last != null && now - last < REFUSAL_LOG_THROTTLE_MS) {
            return;
        }
        refusalLogTimes.put(ip, now);
        if (refusalLogTimes.size() > 4096) {
            refusalLogTimes.clear();
        }
        Blast.log("BANS: over-threshold IP", ip, "is protected and stays UNENFORCED -", reason);
    }

    private static boolean isUniqueLocalV6(@NonNull InetAddress address) {
        byte[] bytes = address.getAddress();
        return bytes.length == 16 && (bytes[0] & 0xFE) == 0xFC;
    }

    private boolean stillActivelyBanned(@NonNull String ip) {
        for (Row row : listActive()) {
            if (ip.equals(row.get(BanModel.IP))) {
                return true;
            }
        }
        return false;
    }

    private static boolean isExpired(@NonNull Row ban) {
        Instant expires = ban.get(BanModel.EXPIRES_AT);
        return expires != null && expires.isBefore(Instant.now());
    }

    private static @Nullable String truncate(@Nullable String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }
}
