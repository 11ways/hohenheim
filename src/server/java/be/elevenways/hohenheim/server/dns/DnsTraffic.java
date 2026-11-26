package be.elevenways.hohenheim.server.dns;

import be.elevenways.hohenheim.HohenheimMicrocopy;
import be.elevenways.hohenheim.server.traffic.HohenheimTraffic;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.time.Now;
import be.elevenways.protoblast.common.util.BlastString;
import be.elevenways.zenit.traffic.common.StatusClass;
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
 * outcome, and the resolver that asked; and THE home of what each outcome is counted as and called.
 *
 * AIDEV-NOTE: the traffic engine counts web-shaped hits, so a question is one: its host the name asked, its path
 * {@code <name> <TYPE>} (the "most asked names" rows, {@link Asked} reads it back), its method {@code DNS} and
 * its status the outcome's ({@link Outcome}), which puts "no such name" and "refused" in the engine's refused-paths
 * table and an answer in its 2xx class; a zone's steps read the exact status per step, so each outcome is its own
 * series. A question for a zone this server does not hold has no source and is not
 * counted. Only questions are counted: a zone transfer (AXFR, IXFR) and a NOTIFY are replication between servers,
 * never a resolver asking a name, and stay out; so does a UDP question the rate limiter drops or answers truncated (a
 * resolver asks it again over TCP, where it is counted once).
 *
 * @author Jelle De Loecker
 * @since  0.1.0
 */
public final class DnsTraffic {

    /** THE outcomes a question is counted under, each with the web status the engine counts it as. */
    public enum Outcome {
        ANSWERED(Rcode.NOERROR, 200),
        NO_SUCH_NAME(Rcode.NXDOMAIN, 404),
        REFUSED(Rcode.REFUSED, 403),
        MALFORMED(Rcode.FORMERR, 400),
        NOT_IMPLEMENTED(Rcode.NOTIMP, 501),
        /** Every other outcome: the server failed to answer. */
        FAILED(-1, 500);

        private final int rcode;
        private final int status;

        Outcome(int rcode, int status) {
            this.rcode = rcode;
            this.status = status;
        }

        /** @return the web status it is counted as */
        public int status() {
            return this.status;
        }

        /** @return the token a screen and a tool key it by */
        public @NonNull String token() {
            return BlastString.lower(this.name());
        }

        /** @return its words, resolved in the reader's locale */
        public @NonNull Microcopy label() {
            return switch (this) {
                case ANSWERED -> HohenheimMicrocopy.TRAFFIC_SCREEN.of("dns_outcome_answered");
                case NO_SUCH_NAME -> HohenheimMicrocopy.TRAFFIC_SCREEN.of("dns_outcome_no_such_name");
                case REFUSED -> HohenheimMicrocopy.TRAFFIC_SCREEN.of("dns_outcome_refused");
                case MALFORMED -> HohenheimMicrocopy.TRAFFIC_SCREEN.of("dns_outcome_malformed");
                case NOT_IMPLEMENTED -> HohenheimMicrocopy.TRAFFIC_SCREEN.of("dns_outcome_not_implemented");
                case FAILED -> HohenheimMicrocopy.TRAFFIC_SCREEN.of("dns_outcome_failed");
            };
        }

        /** @return the outcome an answer's code names, {@link #FAILED} for any other */
        static @NonNull Outcome ofRcode(int rcode) {
            for (Outcome each : values()) {
                if (each.rcode == rcode && each != FAILED) return each;
            }
            return FAILED;
        }

        /** @return the outcome a counted status stands for, by the statuses table's key; null for none */
        public static @Nullable Outcome ofStatus(@NonNull String status) {
            for (Outcome each : values()) {
                if (String.valueOf(each.status).equals(status)) return each;
            }
            return null;
        }

        /** @return the outcome a token names, null for none */
        public static @Nullable Outcome forToken(@NonNull String token) {
            for (Outcome each : values()) {
                if (each.token().equals(token)) return each;
            }
            return null;
        }
    }

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

    /** @return the web status a DNS outcome is counted as */
    static int statusOf(int rcode) {
        return Outcome.ofRcode(rcode).status;
    }

    /** @return the words of a status class among a zone's questions: the outcomes it holds */
    public static @NonNull Microcopy classWords(@NonNull StatusClass statusClass) {
        return switch (statusClass) {
            case SUCCESS -> Outcome.ANSWERED.label();
            case CLIENT_ERROR -> HohenheimMicrocopy.TRAFFIC_SCREEN.of("dns_class_not_answered");
            case SERVER_ERROR -> HohenheimMicrocopy.TRAFFIC_SCREEN.of("dns_class_failed");
            case INFORMATIONAL, REDIRECTION, OTHER -> HohenheimMicrocopy.TRAFFIC_SCREEN.of("dns_class_other");
        };
    }

    /**
     * A counted question's name and type, read back from its path.
     *
     * @param type "" for a path that carries none
     */
    public record Asked(@NonNull String name, @NonNull String type) {

        /** @return the name and type of a counted question's path, {@code <name> <TYPE>} */
        public static @NonNull Asked of(@NonNull String path) {
            int space = path.lastIndexOf(' ');
            return space < 0 ? new Asked(path, "") : new Asked(path.substring(0, space), path.substring(space + 1));
        }
    }
}
