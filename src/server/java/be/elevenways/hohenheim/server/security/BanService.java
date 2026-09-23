package be.elevenways.hohenheim.server.security;

import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.hohenheim.model.BanModel;
import be.elevenways.hohenheim.security.BanScope;
import be.elevenways.hohenheim.server.notification.Alerts;
import be.elevenways.hohenheim.server.notification.NotificationEvents;
import be.elevenways.hohenheim.server.task.UpdateSystemIpAddresses;
import be.elevenways.protoblast.common.Blast;
import be.elevenways.protoblast.common.time.Now;
import be.elevenways.zenit.common.net.AddressScope;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
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
 *
 * AIDEV-NOTE: the proxy calls in from Undertow I/O threads (the ban check on every request,
 * the threat scorer's threshold trigger), so those two entry points never touch the database,
 * nftables or this object's monitor. {@link #isBanned} is a read of the volatile snapshot, and
 * {@link #autoBan} only screens the target in memory and hands the write to ONE background ban
 * writer; the writer does the lookup, the row, the {@code sudo nft} call and the budget under
 * the monitor, where only it and the admin-driven mutations meet.
 */
public final class BanService {

    public static final BanService INSTANCE = new BanService(new NftService());

    private static final long AUTO_BAN_WINDOW_MS = 3_600_000;

    /** How many distinct automatic bans may wait for the writer before new ones are dropped. */
    private static final int WRITER_QUEUE_CAPACITY = 1024;

    private final NftService nft;
    private final LongSupplier clock;
    private final SecurityNotifier notifier;
    private final Executor writer;

    // Keys (target + scope) an automatic ban is queued for, so a flood of threshold crossings
    // from one actor is one write, not one per request.
    private final Set<String> queuedAutoBans = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean refreshQueued = new AtomicBoolean();
    private final AtomicLong droppedAutoBans = new AtomicLong();
    private volatile long lastDropLogMs;
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

    /** The production shape: the real clock, operator alerts and the background ban writer. */
    BanService(@NonNull NftService nft) {
        this(nft, Now::millis, Alerts::send, backgroundWriter());
    }

    /** Test constructor: inject the clock the auto-ban budget window uses; writes run inline. */
    BanService(@NonNull NftService nft, @NonNull LongSupplier clock) {
        this(nft, clock, Alerts::send);
    }

    /** Test constructor: additionally inject the operator-notification sink; writes run inline. */
    BanService(@NonNull NftService nft, @NonNull LongSupplier clock,
               @NonNull SecurityNotifier notifier) {
        this(nft, clock, notifier, Runnable::run);
    }

    /**
     * @param writer where automatic bans and cache loads run; {@code Runnable::run} makes them
     *               synchronous, which is what a test asserting on the row right after wants
     */
    BanService(@NonNull NftService nft, @NonNull LongSupplier clock,
               @NonNull SecurityNotifier notifier, @NonNull Executor writer) {
        this.nft = nft;
        this.clock = clock;
        this.notifier = notifier;
        this.writer = writer;
    }

    /** The one daemon thread every automatic ban and background cache load runs on. */
    private static @NonNull Executor backgroundWriter() {
        ThreadPoolExecutor executor = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
            new LinkedBlockingQueue<>(WRITER_QUEUE_CAPACITY),
            runnable -> Thread.ofPlatform().daemon().name("ban-writer").unstarted(runnable));
        return executor;
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
                nft.setup(NftService.configuredPorts(), NftService.configuredSshPorts());
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
        if (!this.cacheLoaded) {
            // Boot warms the cache; this is the early-datasource retry, and it never runs the
            // query on the caller's (I/O) thread.
            queueCacheLoad();
        }
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
     * Automatic-ban entry point (threat scorer, reputation policy): create a ban with the
     * configured TTL and the caller's reason, in the scope the event type names. Idempotent
     * for an actor already banned IN THAT SCOPE, a no-op when bans are disabled, and subject
     * to the global {@code security.auto_ban_budget_per_hour} budget.
     *
     * Returns before the ban exists: the lookup, the row and the kernel element are the ban
     * writer's work (see the class note), and {@link #awaitPendingWrites} is the seam for a
     * caller that must observe the row.
     */
    public void autoBan(@NonNull String ip, @Nullable String eventType, @NonNull String reason) {
        if (!enforcementEnabled()) {
            return;
        }
        String trimmed = ip.trim();
        // Screened in memory on the caller's thread: a protected or unparseable target never
        // occupies the writer.
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
        BanScope scope = BanScope.forEventType(eventType);
        String queueKey = scope.token() + " " + normalized;
        if (!this.queuedAutoBans.add(queueKey)) {
            return;   // this actor's ban in this scope is already on its way
        }
        try {
            this.writer.execute(() -> {
                try {
                    writeAutoBan(ip, normalized, eventType, scope, reason);
                } catch (RuntimeException failure) {
                    Blast.log("BANS: auto-ban failed for", ip, "-", failure.getMessage());
                } finally {
                    this.queuedAutoBans.remove(queueKey);
                }
            });
        } catch (RejectedExecutionException full) {
            this.queuedAutoBans.remove(queueKey);
            noteDroppedAutoBan(normalized);
        }
    }

    /** The writer half of {@link #autoBan}: dedupe per scope, spend the budget, create the row. */
    private void writeAutoBan(@NonNull String ip, @NonNull String normalized,
                              @Nullable String eventType, @NonNull BanScope scope,
                              @NonNull String reason) {
        int exhaustedBudget = 0;
        synchronized (this) {
            BanModel bans = Models.get(BanModel.class);
            // AIDEV-NOTE: the dedupe is PER SCOPE. It used to find any active row for the
            // address, so a web ban from hostname scanning silently suppressed the SSH ban
            // for the same actor's brute force (and vice versa) -- the kernel set for the
            // other scope never got the element.
            if (findActiveBan(bans, normalized, scope) != null) {
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
                        eventType, scope, Duration.ofHours(Math.max(1, ttlHours)), true);
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

    /** A full writer queue drops the ban (the actor keeps scoring and retries) and says so. */
    private void noteDroppedAutoBan(@NonNull String normalized) {
        long dropped = this.droppedAutoBans.incrementAndGet();
        long now = Now.millis();
        if (now - this.lastDropLogMs >= REFUSAL_LOG_THROTTLE_MS) {
            this.lastDropLogMs = now;
            Blast.log("BANS: ban writer queue full; automatic ban of", normalized,
                "dropped (" + dropped + " dropped so far) -- the actor stays scored and will"
                    + " trigger again");
        }
    }

    /**
     * Block until every automatic ban and cache load queued so far has been written.
     *
     * @throws IllegalStateException when the writer does not drain within ten seconds
     */
    public void awaitPendingWrites() throws InterruptedException {
        CountDownLatch drained = new CountDownLatch(1);
        try {
            this.writer.execute(drained::countDown);
        } catch (RejectedExecutionException full) {
            throw new IllegalStateException("the ban writer queue is full");
        }
        if (!drained.await(10, TimeUnit.SECONDS)) {
            throw new IllegalStateException("the ban writer did not drain within 10s");
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
     * Create a web-scoped ban (null ttl = permanent). Returns the existing active web
     * row when the IP is already banned there.
     *
     * @throws IllegalArgumentException when the IP is unparseable or protected
     *         (loopback/private/link-local or one of this server's own addresses)
     */
    public synchronized @NonNull Row createBan(@NonNull String ip, @Nullable String reason,
                                               @NonNull String source,
                                               @Nullable String eventType,
                                               @Nullable Duration ttl) {
        return createBan(ip, reason, source, eventType, BanScope.WEB, ttl);
    }

    /**
     * Create a ban in an explicit enforcement scope (null ttl = permanent). Returns the
     * existing active row when the IP is already banned IN THAT SCOPE: the two scopes are two
     * kernel sets and two refusals, so a web ban never stands in for an SSH one or the other
     * way around, and each is lifted on its own.
     *
     * @throws IllegalArgumentException when the IP is unparseable or protected
     */
    public synchronized @NonNull Row createBan(@NonNull String ip, @Nullable String reason,
                                               @NonNull String source,
                                               @Nullable String eventType,
                                               @NonNull BanScope scope,
                                               @Nullable Duration ttl) {
        String trimmed = ip.trim();
        String problem = protectionProblem(trimmed);
        if (problem != null) {
            throw new IllegalArgumentException(problem);
        }
        // Non-null: protectionProblem already refused unnormalizable values.
        String normalized = normalizeBanTarget(trimmed);

        BanModel bans = Models.get(BanModel.class);
        Row existing = findActiveBan(bans, normalized, scope);
        if (existing != null) {
            return existing;
        }

        return createBanNormalized(bans, normalized, reason, source, eventType, scope, ttl, false);
    }

    private @NonNull Row createBanNormalized(@NonNull BanModel bans, @NonNull String normalized,
                                             @Nullable String reason, @NonNull String source,
                                             @Nullable String eventType, @NonNull BanScope scope,
                                             @Nullable Duration ttl,
                                             boolean rollbackOnNftFailure) {
        Instant now = Now.instant();
        Row row = bans.createEmptyRow();
        row.set(BanModel.IP, normalized);
        row.set(BanModel.REASON, truncate(reason, 255));
        row.set(BanModel.SOURCE, source);
        row.set(BanModel.EVENT_TYPE, eventType);
        row.set(BanModel.SCOPE, scope.token());
        row.set(BanModel.EXPIRES_AT, ttl != null ? now.plus(ttl) : null);
        row.set(BanModel.ACTIVE, true);
        bans.save(row);

        boolean nftApplied = nft.addBan(scope, normalized, ttl != null ? ttl.toSeconds() : null);
        if (!nftApplied && rollbackOnNftFailure) {
            if (!bans.delete(row)) {
                throw new IllegalStateException("Could not roll back ban after nftables failure");
            }
            refreshCache();
            throw new IllegalStateException("nftables rejected the automatic ban");
        }
        refreshCache();
        Blast.log("BANS: banned", normalized, "(" + source + "/" + scope.token() + ")",
            ttl != null ? "for " + ttl : "permanently");
        return row;
    }

    /** The active, unexpired row banning this key in this scope (a pre-scope row is WEB). */
    private static @Nullable Row findActiveBan(@NonNull BanModel bans, @NonNull String normalized,
                                               @NonNull BanScope scope) {
        for (Row row : bans.find()
                .where(BanModel.IP.eq(normalized))
                .where(BanModel.ACTIVE.eq(true))
                .all()) {
            if (!isExpired(row) && BanScope.fromToken(row.get(BanModel.SCOPE)) == scope) {
                return row;
            }
        }
        return null;
    }

    /**
     * Lift an active ban: stamps the lift columns on the ban row and removes the kernel
     * element.
     *
     * AIDEV-NOTE: "audit" here means the BAN ROW ITSELF, which is the trail (BanResource
     * makes the list unupdatable and undeletable). It is deliberately NOT the framework
     * activity log: this is an {@code updateAll()}, which fires no per-row write hooks,
     * so no {@code zenit_activity} row is written. {@code lifted_by} carries the actor.
     */
    public synchronized void lift(@NonNull Row ban, @Nullable String liftedBy) {
        BanModel bans = Models.get(BanModel.class);
        Integer id = ban.get(BanModel.ID);
        if (id == null) {
            return;
        }
        bans.find().where(BanModel.ID.eq(id))
            .assign(BanModel.ACTIVE, false)
            .assign(BanModel.LIFTED_AT, Now.instant())
            .assign(BanModel.LIFTED_BY, truncate(liftedBy, 200))
            .updateAll();

        String ip = ban.get(BanModel.IP);
        BanScope scope = scopeOf(ban);
        if (ip != null && scope != null && findActiveBan(bans, ip, scope) == null) {
            nft.removeBan(scope, ip);
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
            .where(BanModel.EXPIRES_AT.lte(Now.instant()))
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
        Instant now = Now.instant();
        for (Row row : listActive()) {
            String ip = row.get(BanModel.IP);
            BanScope scope = scopeOf(row);
            if (ip == null || scope == null) {
                continue;
            }
            Instant expires = row.get(BanModel.EXPIRES_AT);
            Long ttl = expires != null ? Math.max(1, Duration.between(now, expires).toSeconds()) : null;
            active.add(new NftService.ActiveBan(scope, ip, ttl));
        }
        nft.resync(active);
    }

    /**
     * The enforcement scope of a stored ban row, FAIL-CLOSED: a token this build does not
     * know is programmed nowhere and says so, because there is no safe superset -- guessing
     * WEB would silently drop an unknown-scoped actor off customer websites, and guessing
     * SSH would silently stop refusing it there.
     *
     * @return the scope, or null when the stored token is unknown
     */
    private static @Nullable BanScope scopeOf(@NonNull Row ban) {
        String token = ban.get(BanModel.SCOPE);
        BanScope scope = BanScope.fromToken(token);
        if (scope == null) {
            Blast.log("BANS: ban", ban.get(BanModel.IP), "carries an unknown scope",
                "'" + token + "' and is enforced NOWHERE; lift it or upgrade the controller");
        }
        return scope;
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
            // WEB scope only: this cache IS the proxy's HTTP/TLS refusal, and an SSH
            // brute-forcer was never declared unwelcome on a customer's website.
            Set<String> ips = new HashSet<>();
            for (Row row : listActive()) {
                String ip = row.get(BanModel.IP);
                if (ip != null && BanScope.WEB == BanScope.fromToken(row.get(BanModel.SCOPE))) {
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

    /** Load the cache on the writer, at most one load queued at a time. */
    private void queueCacheLoad() {
        if (!this.refreshQueued.compareAndSet(false, true)) {
            return;
        }
        try {
            this.writer.execute(() -> {
                try {
                    if (!this.cacheLoaded) {
                        refreshCache();
                    }
                } finally {
                    this.refreshQueued.set(false);
                }
            });
        } catch (RejectedExecutionException full) {
            this.refreshQueued.set(false);
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
        String scopeProblem = scopeProblem(literal);
        if (scopeProblem != null) {
            return scopeProblem;
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
        // ::1 lives in ::/64, whose network address is the unspecified :: -- the
        // network-address classification therefore covers the protected /64s.
        String scopeProblem = scopeProblem(network);
        if (scopeProblem != null) {
            return scopeProblem;
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

    /**
     * The refusal an address's zenit {@link AddressScope} earns: this host (loopback, the
     * unspecified address) and every local network (RFC 1918, the shared 100.64/10 space
     * tailnets use, link-, unique- and site-local) are never banned.
     *
     * AIDEV-NOTE: an exhaustive switch over the scope's REACH, no default, so a reach zenit
     * adds fails the build here instead of silently becoming bannable. The special-purpose
     * families (documentation, benchmarking, reserved, ...) stay bannable exactly as before:
     * a client presenting one is not an address the operator depends on.
     */
    private static @Nullable String scopeProblem(byte @NonNull [] address) {
        return switch (AddressScope.of(address).reach()) {
            case THIS_HOST -> "loopback address";
            case LOCAL_NETWORK -> "private address";
            case INTERNET, SPECIAL -> null;
        };
    }

    // Per-IP refusal-log throttle so a scanning loop cannot flood the log.
    private static final long REFUSAL_LOG_THROTTLE_MS = 60_000;
    private static final ConcurrentHashMap<String, Long> refusalLogTimes = new ConcurrentHashMap<>();

    private static void logRefusalThrottled(@NonNull String ip, @Nullable String reason) {
        long now = Now.millis();
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

    private static boolean isExpired(@NonNull Row ban) {
        Instant expires = ban.get(BanModel.EXPIRES_AT);
        return expires != null && expires.isBefore(Now.instant());
    }

    private static @Nullable String truncate(@Nullable String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }
}
