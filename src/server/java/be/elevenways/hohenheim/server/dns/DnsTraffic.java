package be.elevenways.hohenheim.server.dns;

import be.elevenways.hohenheim.server.traffic.HohenheimTraffic;
import be.elevenways.protoblast.common.time.Now;
import be.elevenways.protoblast.common.util.BlastString;
import be.elevenways.zenit.traffic.common.TrafficHit;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.xbill.DNS.Message;
import org.xbill.DNS.Rcode;
import org.xbill.DNS.Record;
import org.xbill.DNS.Type;

import java.net.InetAddress;

/**
 * Counts each answered DNS question into the DNS traffic store, one source per zone: the name asked and its type, the
 * outcome, and the resolver that asked.
 *
 * AIDEV-NOTE: the traffic engine counts web-shaped hits, so a question is one: its host the name asked, its path
 * {@code <name> <TYPE>} (the "most asked names" rows), its method {@code DNS} and its status the outcome as
 * {@link #statusOf} spells it, which puts "no such name" and "refused" in the engine's refused-paths table and
 * an answer in its 2xx class. A question for a zone this server does not hold has no source and is not counted.
 * Only questions are counted: a zone transfer (AXFR, IXFR) and a NOTIFY are replication between servers, never a
 * resolver asking a name, and stay out; so does a UDP question the rate limiter drops or answers truncated (a
 * resolver asks it again over TCP, where it is counted once).
 *
 * @author Jelle De Loecker
 * @since  0.1.0
 */
final class DnsTraffic {

    private DnsTraffic() {
    }

    /**
     * Counts one answered question once its answer was sent; never throws.
     *
     * @param reply   the answer's wire, whose header carries its outcome
     * @param started the stopwatch started when the question arrived
     */
    static void record(@Nullable Message query, byte @Nullable [] reply, int queryBytes, @NonNull InetAddress resolver,
                       Now.@NonNull Stopwatch started) {
        if (query == null || reply == null || reply.length < 4) {
            return;
        }
        try {
            Record question = query.getQuestion();
            // A transfer asked over UDP is still replication, never a question.
            if (question == null || question.getType() == Type.AXFR || question.getType() == Type.IXFR) {
                return;
            }
            DnsZoneSnapshot zone = DnsZoneStore.INSTANCE.findZoneFor(question.getName());
            if (zone == null) {
                return;
            }
            String name = BlastString.lower(question.getName().toString(true));
            HohenheimTraffic.count(HohenheimTraffic.Counted.DNS, new TrafficHit(Now.instant(),
                HohenheimTraffic.zoneSource(zone.getZoneId()), name, name + " " + Type.string(question.getType()),
                "DNS", statusOf(reply[3] & 0x0F), reply.length, queryBytes, started.realNanos() / 1000,
                resolver.getHostAddress(), null, null));
        } catch (RuntimeException ignored) {
            // Counting a question never breaks its answer.
        }
    }

    /**
     * @return the web status a DNS outcome is counted as: answered 200, no such name 404, refused 403, a malformed
     *         question 400, not implemented 501, and every other outcome a failure, 500
     */
    static int statusOf(int rcode) {
        return switch (rcode) {
            case Rcode.NOERROR -> 200;
            case Rcode.NXDOMAIN -> 404;
            case Rcode.REFUSED -> 403;
            case Rcode.FORMERR -> 400;
            case Rcode.NOTIMP -> 501;
            default -> 500;
        };
    }
}
