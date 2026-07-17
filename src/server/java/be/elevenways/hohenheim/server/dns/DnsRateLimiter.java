package be.elevenways.hohenheim.server.dns;

import org.checkerframework.checker.nullness.qual.NonNull;
import org.xbill.DNS.Flags;
import org.xbill.DNS.Message;
import org.xbill.DNS.Rcode;
import org.xbill.DNS.Record;
import org.xbill.DNS.Section;
import org.xbill.DNS.Type;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntSupplier;

/**
 * DNS response-rate-limiting for the UDP listener: per-second counters keyed
 * by client network prefix plus query, the standard mitigation against
 * reflection abuse toward spoofed sources. Every {@code SLIP}th limited
 * response is answered truncated (TC) so a legitimate client behind the
 * limited prefix retries over TCP, which is never limited. Fails open when an
 * attacker diversifies keys past the tracking cap: dropping real answers is
 * worse than briefly amplifying.
 */
public final class DnsRateLimiter {

    public enum Verdict { ALLOW, SLIP, DROP }

    private static final int SLIP = 2;
    private static final int MAX_TRACKED = 100_000;

    private final IntSupplier limitPerSecond;
    private final ConcurrentHashMap<String, AtomicInteger> counts = new ConcurrentHashMap<>();
    private volatile long epochSecond;

    public DnsRateLimiter(@NonNull IntSupplier limitPerSecond) {
        this.limitPerSecond = limitPerSecond;
    }

    /**
     * The bucket key for a computed response. NXDOMAIN keys per ZONE, not per
     * qname: a random-subdomain flood must share one bucket or RRL is useless
     * against the standard reflection attack (classic BIND/NSD RRL semantics).
     * Referrals key per delegation point for the same reason. Authoritative
     * answers key per qname/qtype; other rcodes share an error bucket.
     */
    public static @NonNull String keyFor(@NonNull Message query, @NonNull Message response) {
        int rcode = response.getHeader().getRcode();
        if (rcode == Rcode.NXDOMAIN) {
            for (Record record : response.getSection(Section.AUTHORITY)) {
                if (record.getType() == Type.SOA) {
                    return "nx|" + record.getName().toString(true).toLowerCase();
                }
            }
            return "nx|-";
        }
        if (rcode != Rcode.NOERROR) {
            return "err|" + rcode;
        }
        // A referral (non-authoritative NS in AUTHORITY) keys per delegation
        // point: random names under a delegated child must share one bucket,
        // exactly like the NXDOMAIN case, or they re-open the reflection dodge.
        if (!response.getHeader().getFlag(Flags.AA)) {
            for (Record record : response.getSection(Section.AUTHORITY)) {
                if (record.getType() == Type.NS) {
                    return "ref|" + record.getName().toString(true).toLowerCase();
                }
            }
        }
        Record question = query.getQuestion();
        return question != null
            ? question.getName().toString(true).toLowerCase() + "|" + question.getType()
            : "-";
    }

    public @NonNull Verdict check(@NonNull InetAddress client, @NonNull String bucket) {
        int limit = limitPerSecond.getAsInt();
        if (limit <= 0 || client.isLoopbackAddress()) {
            return Verdict.ALLOW;
        }

        long now = System.currentTimeMillis() / 1000;
        if (now != epochSecond) {
            synchronized (this) {
                if (now != epochSecond) {
                    counts.clear();
                    epochSecond = now;
                }
            }
        }

        String key = prefixOf(client) + "|" + bucket;
        if (counts.size() >= MAX_TRACKED && !counts.containsKey(key)) {
            return Verdict.ALLOW;
        }

        int count = counts.computeIfAbsent(key, k -> new AtomicInteger()).incrementAndGet();
        if (count <= limit) {
            return Verdict.ALLOW;
        }
        return (count - limit) % SLIP == 1 ? Verdict.SLIP : Verdict.DROP;
    }

    /** IPv4 /24, IPv6 /56: one bucket per typical end-customer allocation. */
    private static @NonNull String prefixOf(@NonNull InetAddress client) {
        byte[] address = client.getAddress();
        int keep = client instanceof Inet4Address ? 3 : 7;
        StringBuilder prefix = new StringBuilder();
        for (int i = 0; i < Math.min(keep, address.length); i++) {
            prefix.append(address[i] & 0xFF).append('.');
        }
        return prefix.toString();
    }
}
