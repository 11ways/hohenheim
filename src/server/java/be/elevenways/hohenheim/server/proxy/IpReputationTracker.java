package be.elevenways.hohenheim.server.proxy;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Per-source-IP reputation for the proxy: a source that racks up many domain misses
 * (a hostname-scanning bot) without a single real hit is considered banned. Consulted both at the
 * HTTP stage and, for HTTPS, at the TLS handshake (via {@code SniKeyManager}) to drop bad IPs before
 * a certificate is served.
 *
 * @author  Jelle De Loecker
 * @since   0.1.0
 */
public final class IpReputationTracker {

    private static final int REJECT_THRESHOLD = 100;
    private static final int MAX_ENTRIES = 50_000;
    private static final long EVICT_AGE_MS = 3_600_000;

    private static final class Entry {
        final AtomicInteger hits = new AtomicInteger(0);
        final AtomicInteger misses = new AtomicInteger(0);
        final AtomicLong lastMissTime = new AtomicLong(0);
    }

    private final ConcurrentHashMap<String, Entry> entries = new ConcurrentHashMap<>();

    /** Record a successful route hit for this IP. */
    public void recordHit(String ip) {
        entries.computeIfAbsent(ip, k -> new Entry()).hits.incrementAndGet();
    }

    /** Record a domain miss for this IP and return its new cumulative miss count. */
    public int recordMiss(String ip) {
        Entry entry = entries.computeIfAbsent(ip, k -> new Entry());
        int misses = entry.misses.incrementAndGet();
        entry.lastMissTime.set(System.currentTimeMillis());

        if (entries.size() > MAX_ENTRIES) {
            long cutoff = System.currentTimeMillis() - EVICT_AGE_MS;
            entries.entrySet().removeIf(e -> e.getValue().lastMissTime.get() < cutoff);
        }
        return misses;
    }

    /** Whether this source IP is currently reputation-banned (at/over the miss threshold, zero hits). */
    public boolean isBanned(String ip) {
        Entry entry = entries.get(ip);
        return entry != null && entry.misses.get() >= REJECT_THRESHOLD && entry.hits.get() == 0;
    }
}
