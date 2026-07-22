package be.elevenways.hohenheim.server.security;

import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.hohenheim.model.BanModel;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * IP ban lifecycle: creation (auto from the threat scorer, manual from the
 * admin), lifting, expiry, the O(1) in-memory active-ban cache the proxy hot
 * paths consult, and the nftables enforcement tier. The bans table is the
 * source of truth; the cache refreshes on every mutation and at boot.
 */
public final class BanService {

    public static final BanService INSTANCE = new BanService(new NftService());

    private final NftService nft;

    // Snapshot of active, unexpired banned IPs; swapped atomically on refresh.
    private volatile Set<String> activeIps = Set.of();
    private volatile boolean cacheLoaded = false;

    BanService(@NonNull NftService nft) {
        this.nft = nft;
    }

    NftService nft() {
        return this.nft;
    }

    /** Boot wiring: idempotent nftables setup plus a full DB-to-kernel resync. */
    public void boot() {
        if (nft.isEnabled()) {
            nft.setup(NftService.configuredPorts());
            resyncNftables();
        }
        refreshCache();
    }

    // -----------------------------------------------------------------------
    // Hot path
    // -----------------------------------------------------------------------

    /** O(1) check against the cached active-ban set (app-level enforcement only). */
    public boolean isBanned(@NonNull String ip) {
        if (!enforcementEnabled()) {
            return false;
        }
        ensureCacheLoaded();
        return activeIps.contains(ip);
    }

    public boolean enforcementEnabled() {
        return Boolean.TRUE.equals(
            HohenheimSettings.VALUES.getValue(HohenheimSettings.Security.BANS_ENABLED));
    }

    // -----------------------------------------------------------------------
    // Mutations
    // -----------------------------------------------------------------------

    /** See {@link #autoBan(String, String, int, String)}. */
    public void autoBan(@NonNull String ip, @Nullable String eventType, int score) {
        autoBan(ip, eventType, score, null);
    }

    /**
     * Threat-scorer callback: create an automatic ban with the configured TTL.
     * Idempotent for already-banned IPs and a no-op when bans are disabled.
     * The reporter (null = this instance's own events) lands in the reason so
     * a poisoning incident stays auditable per reporter.
     */
    public void autoBan(@NonNull String ip, @Nullable String eventType, int score,
                        @Nullable String reporter) {
        if (!enforcementEnabled()) {
            return;
        }
        try {
            int ttlHours = HohenheimSettings.VALUES.getValue(
                HohenheimSettings.Security.AUTO_BAN_TTL_HOURS);
            String reason = "score " + score + " over threshold"
                + (reporter != null ? " (reporter " + reporter + ")" : "");
            createBan(ip, reason, BanModel.SOURCE_AUTO,
                eventType, Duration.ofHours(Math.max(1, ttlHours)));
        } catch (IllegalArgumentException refused) {
            // Protected IP (loopback/private/own/never_ban): never auto-ban and
            // never crash the hot path -- WITHOUT a ban row there is no
            // enforcement at all, so surface the refusal (throttled per IP).
            logRefusalThrottled(ip, refused.getMessage());
        } catch (RuntimeException e) {
            Blast.log("BANS: auto-ban failed for", ip, "-", e.getMessage());
        }
    }

    /**
     * Create a ban (null ttl = permanent). Returns the existing active row when
     * the IP is already banned.
     *
     * @throws IllegalArgumentException when the IP is unparseable or protected
     *         (loopback/private/link-local or one of this server's own addresses)
     */
    public @NonNull Row createBan(@NonNull String ip, @Nullable String reason,
                                  @NonNull String source, @Nullable String eventType,
                                  @Nullable Duration ttl) {
        String normalized = ip.trim();
        String problem = protectionProblem(normalized);
        if (problem != null) {
            throw new IllegalArgumentException(problem);
        }

        BanModel bans = Models.get(BanModel.class);
        Row existing = bans.find()
            .where(BanModel.IP.eq(normalized))
            .where(BanModel.ACTIVE.eq(true))
            .first();
        if (existing != null && !isExpired(existing)) {
            return existing;
        }

        Instant now = Instant.now();
        Row row = bans.createEmptyRow();
        row.set(BanModel.IP, normalized);
        row.set(BanModel.REASON, truncate(reason, 255));
        row.set(BanModel.SOURCE, source);
        row.set(BanModel.EVENT_TYPE, eventType);
        row.set(BanModel.EXPIRES_AT, ttl != null ? now.plus(ttl) : null);
        row.set(BanModel.ACTIVE, true);
        bans.save(row);

        nft.addBan(normalized, ttl != null ? ttl.toSeconds() : null);
        refreshCache();
        Blast.log("BANS: banned", normalized, "(" + source + ")",
            ttl != null ? "for " + ttl : "permanently");
        return row;
    }

    /** Lift an active ban: audit-stamps the row and removes the kernel element. */
    public void lift(@NonNull Row ban, @Nullable String liftedBy) {
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
    public int deactivateExpired() {
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
    public void resyncNftables() {
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
     * CRITICAL SAFETY: never ban loopback, private/link-local ranges, one of
     * this server's own addresses, or anything on the {@code security.never_ban}
     * operator allowlist -- a poisoned or misread client IP must not be able to
     * firewall the operator (or the server itself) out. The literal pre-check
     * guarantees no DNS lookup ever happens on untrusted input.
     *
     * @return a refusal reason, or null when the IP may be banned
     */
    public static @Nullable String protectionProblem(@NonNull String ip) {
        if (ip.isBlank()) {
            return "empty ip";
        }
        byte[] literal = IpLiterals.parse(ip);
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
        if (address.isSiteLocalAddress() || address.isLinkLocalAddress()
                || isUniqueLocalV6(address)) {
            return "private address";
        }
        for (String own : UpdateSystemIpAddresses.getLocalAddresses()) {
            if (ip.equals(own)) {
                return "one of this server's own addresses";
            }
        }
        String neverBan = HohenheimSettings.VALUES.getValue(HohenheimSettings.Security.NEVER_BAN);
        if (IpLiterals.matchesList(literal, neverBan)) {
            return "on the security.never_ban allowlist";
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
