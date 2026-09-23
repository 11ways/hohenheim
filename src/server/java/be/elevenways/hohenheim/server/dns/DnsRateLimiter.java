package be.elevenways.hohenheim.server.dns;

import be.elevenways.protoblast.common.time.Now;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.xbill.DNS.Flags;
import org.xbill.DNS.Message;
import org.xbill.DNS.Name;
import org.xbill.DNS.Rcode;
import org.xbill.DNS.Record;
import org.xbill.DNS.Section;
import org.xbill.DNS.Type;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntSupplier;

/**
 * DNS response-rate-limiting for the UDP listener: per-second counters keyed
 * by client network prefix plus the response's SOURCE (the zone for NXDOMAIN, the
 * delegation for a referral, the wildcard owner for a synthesized answer, the owner name
 * otherwise), the standard mitigation against reflection abuse toward spoofed sources.
 * Every {@code SLIP}th limited response is answered truncated (TC) so a legitimate client
 * behind the limited prefix retries over TCP, which is never limited.
 *
 * AIDEV-NOTE: when an attacker diversifies keys past the tracking cap, an UNTRACKED key is
 * answered SLIP (an answerless TC response), never ALLOW. Until 2026-09-23 this failed OPEN
 * ("dropping real answers is worse than briefly amplifying"), which made the cap itself the
 * way around the limiter: a spoofed-source flood only had to be diverse enough. A TC answer
 * is no larger than the query, so it amplifies nothing, and a real client still gets its
 * answer over TCP -- the closed answer costs a legitimate client one retry, not the answer.
 */
public final class DnsRateLimiter {

    public enum Verdict { ALLOW, SLIP, DROP }

    private static final int SLIP = 2;
    private static final int MAX_TRACKED = 100_000;

    private final IntSupplier limitPerSecond;
    private final int maxTracked;
    private final ConcurrentHashMap<String, AtomicInteger> counts = new ConcurrentHashMap<>();
    private volatile long epochSecond;

    public DnsRateLimiter(@NonNull IntSupplier limitPerSecond) {
        this(limitPerSecond, MAX_TRACKED);
    }

    /** For tests: a limiter whose tracking cap is small enough to reach. */
    public DnsRateLimiter(@NonNull IntSupplier limitPerSecond, int maxTracked) {
        this.limitPerSecond = limitPerSecond;
        this.maxTracked = maxTracked;
    }

    /** The bucket key for a computed response whose answer owner is its qname. */
    public static @NonNull String keyFor(@NonNull Message query, @NonNull Message response) {
        return keyFor(query, response, null);
    }

    /**
     * The bucket key for a computed response. NXDOMAIN keys per ZONE, not per
     * qname: a random-subdomain flood must share one bucket or RRL is useless
     * against the standard reflection attack (classic BIND/NSD RRL semantics).
     * Referrals key per delegation point for the same reason. Authoritative
     * answers key per SOURCE owner and qtype; other rcodes share an error bucket.
     *
     * AIDEV-NOTE: the source is what makes a WILDCARD answer share one bucket. A wildcard
     * synthesizes its records under whatever qname was asked, so keying on the qname gave
     * every random label its own bucket -- the random-subdomain reflection dodge the
     * NXDOMAIN keying closed, re-opened by any zone with a {@code *} record.
     *
     * @param source the owner name the answer was drawn from ({@link DnsResponder.Answer}),
     *               or null to key on the qname
     */
    public static @NonNull String keyFor(@NonNull Message query, @NonNull Message response,
                                         @Nullable Name source) {
        int rcode = response.getHeader().getRcode();
        if (rcode == Rcode.NXDOMAIN) {
            for (Record record : response.getSection(Section.AUTHORITY)) {
                if (record.getType() == Type.SOA) {
                    return "nx|" + record.getName().toString(true).toLowerCase(Locale.ROOT);
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
                    return "ref|" + record.getName().toString(true).toLowerCase(Locale.ROOT);
                }
            }
        }
        Record question = query.getQuestion();
        if (question == null) {
            return "-";
        }
        Name owner = source != null ? source : question.getName();
        return owner.toString(true).toLowerCase(Locale.ROOT) + "|" + question.getType();
    }

    public @NonNull Verdict check(@NonNull InetAddress client, @NonNull String bucket) {
        int limit = limitPerSecond.getAsInt();
        if (limit <= 0 || client.isLoopbackAddress()) {
            return Verdict.ALLOW;
        }

        long now = Now.millis() / 1000;
        if (now != epochSecond) {
            synchronized (this) {
                if (now != epochSecond) {
                    counts.clear();
                    epochSecond = now;
                }
            }
        }

        String key = prefixOf(client) + "|" + bucket;
        if (counts.size() >= this.maxTracked && !counts.containsKey(key)) {
            return Verdict.SLIP;
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
