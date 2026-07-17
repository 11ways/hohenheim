package be.elevenways.hohenheim.server.dns;

import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.xbill.DNS.DClass;
import org.xbill.DNS.Message;
import org.xbill.DNS.Name;
import org.xbill.DNS.Record;
import org.xbill.DNS.SOARecord;
import org.xbill.DNS.Section;
import org.xbill.DNS.SimpleResolver;
import org.xbill.DNS.Type;

import java.time.Duration;

/**
 * Reads a zone's SOA serial from a specific nameserver over UDP; the shared
 * primitive behind secondary refresh checks and ACME propagation waits.
 */
public final class DnsSoaProbe {

    private DnsSoaProbe() {}

    /** @return the SOA serial served by {@code host:port} for {@code origin}, or null on any failure */
    public static @Nullable Long serial(@NonNull String host, int port, @NonNull Name origin) {
        try {
            SimpleResolver resolver = new SimpleResolver(host);
            resolver.setPort(port);
            resolver.setTimeout(Duration.ofSeconds(5));
            Message query = Message.newQuery(Record.newRecord(origin, Type.SOA, DClass.IN));
            Message response = resolver.send(query);
            for (Record record : response.getSection(Section.ANSWER)) {
                if (record instanceof SOARecord soa) {
                    return soa.getSerial();
                }
            }
        }
        catch (Exception ignored) {
            // Unreachable or non-authoritative: caller treats null as "not yet caught up".
        }
        return null;
    }

    /** RFC 1982 serial arithmetic: true when {@code candidate} is >= {@code target}. */
    public static boolean serialReached(long candidate, long target) {
        long diff = (candidate - target) & 0xFFFFFFFFL;
        return diff == 0 || diff < 0x80000000L;
    }
}
