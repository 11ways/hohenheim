package be.elevenways.hohenheim.test;

import be.elevenways.hohenheim.server.dns.DnsRateLimiter;
import be.elevenways.hohenheim.server.dns.DnsRateLimiter.Verdict;
import org.junit.jupiter.api.Test;
import org.xbill.DNS.DClass;
import org.xbill.DNS.Message;
import org.xbill.DNS.Name;
import org.xbill.DNS.Record;
import org.xbill.DNS.Type;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Response-rate-limit verdicts: under-limit traffic flows, over-limit traffic
 * alternates SLIP (truncated, retry over TCP) and DROP, buckets are per
 * client-prefix + query, and loopback / disabled limits are exempt.
 */
class DnsRateLimiterTest {

    private static Message query(String name, int type) throws Exception {
        return Message.newQuery(Record.newRecord(Name.fromString(name + "."), type, DClass.IN));
    }

    @Test
    void underTheLimitEverythingIsAllowed() throws Exception {
        DnsRateLimiter limiter = new DnsRateLimiter(() -> 10);
        InetAddress client = InetAddress.getByName("203.0.113.5");
        Message query = query("www.example.com", Type.A);
        for (int i = 0; i < 10; i++) {
            assertThat(limiter.check(client, query)).isEqualTo(Verdict.ALLOW);
        }
    }

    @Test
    void overTheLimitAlternatesSlipAndDrop() throws Exception {
        DnsRateLimiter limiter = new DnsRateLimiter(() -> 5);
        InetAddress client = InetAddress.getByName("203.0.113.5");
        Message query = query("www.example.com", Type.A);

        List<Verdict> verdicts = new ArrayList<>();
        for (int i = 0; i < 11; i++) {
            verdicts.add(limiter.check(client, query));
        }
        assertThat(verdicts.subList(0, 5)).containsOnly(Verdict.ALLOW);
        // Every second limited response slips through as TC so a legitimate
        // client behind the limited prefix can still fail over to TCP.
        assertThat(verdicts.subList(5, 11)).containsExactly(
            Verdict.SLIP, Verdict.DROP, Verdict.SLIP, Verdict.DROP, Verdict.SLIP, Verdict.DROP);
    }

    @Test
    void bucketsArePerPrefixAndPerQuery() throws Exception {
        DnsRateLimiter limiter = new DnsRateLimiter(() -> 2);
        InetAddress clientA = InetAddress.getByName("203.0.113.5");
        InetAddress sameNet = InetAddress.getByName("203.0.113.99");
        InetAddress otherNet = InetAddress.getByName("198.51.100.5");

        Message queryA = query("www.example.com", Type.A);
        limiter.check(clientA, queryA);
        limiter.check(clientA, queryA);
        // Same /24 shares the bucket (spoofed sources inside one network can't widen it)...
        assertThat(limiter.check(sameNet, queryA)).isNotEqualTo(Verdict.ALLOW);
        // ...but another network and another query are independent buckets.
        assertThat(limiter.check(otherNet, queryA)).isEqualTo(Verdict.ALLOW);
        assertThat(limiter.check(clientA, query("mail.example.com", Type.A))).isEqualTo(Verdict.ALLOW);
    }

    @Test
    void loopbackAndDisabledLimitsAreExempt() throws Exception {
        DnsRateLimiter limited = new DnsRateLimiter(() -> 1);
        InetAddress loopback = InetAddress.getByName("127.0.0.1");
        Message query = query("www.example.com", Type.A);
        for (int i = 0; i < 5; i++) {
            assertThat(limited.check(loopback, query)).isEqualTo(Verdict.ALLOW);
        }

        DnsRateLimiter disabled = new DnsRateLimiter(() -> 0);
        InetAddress client = InetAddress.getByName("203.0.113.5");
        for (int i = 0; i < 5; i++) {
            assertThat(disabled.check(client, query)).isEqualTo(Verdict.ALLOW);
        }
    }
}
