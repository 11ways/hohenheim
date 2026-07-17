package be.elevenways.hohenheim.test;

import be.elevenways.hohenheim.server.dns.DnsRateLimiter;
import be.elevenways.hohenheim.server.dns.DnsRateLimiter.Verdict;
import org.junit.jupiter.api.Test;
import org.xbill.DNS.DClass;
import org.xbill.DNS.Flags;
import org.xbill.DNS.Message;
import org.xbill.DNS.NSRecord;
import org.xbill.DNS.Name;
import org.xbill.DNS.Rcode;
import org.xbill.DNS.Record;
import org.xbill.DNS.SOARecord;
import org.xbill.DNS.Section;
import org.xbill.DNS.Type;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Response-rate-limit verdicts: under-limit traffic flows, over-limit traffic
 * alternates SLIP (truncated, retry over TCP) and DROP, buckets are per
 * client-prefix + response key, NXDOMAIN keys per ZONE so random-subdomain
 * floods share one bucket, and loopback / disabled limits are exempt.
 */
class DnsRateLimiterTest {

    private static final String KEY = "www.example.com|1";

    private static Message query(String name, int type) throws Exception {
        return Message.newQuery(Record.newRecord(Name.fromString(name + "."), type, DClass.IN));
    }

    private static Message nxdomainFor(Message query, String zone) throws Exception {
        Message response = new Message(query.getHeader().getID());
        response.getHeader().setFlag(Flags.QR);
        response.getHeader().setRcode(Rcode.NXDOMAIN);
        response.addRecord(query.getQuestion(), Section.QUESTION);
        Name origin = Name.fromString(zone + ".");
        response.addRecord(new SOARecord(origin, DClass.IN, 300, origin, origin, 1, 1, 1, 1, 300),
            Section.AUTHORITY);
        return response;
    }

    private static Message noerrorFor(Message query) {
        Message response = new Message(query.getHeader().getID());
        response.getHeader().setFlag(Flags.QR);
        response.addRecord(query.getQuestion(), Section.QUESTION);
        return response;
    }

    @Test
    void referralsKeyPerDelegationPointNotPerQname() throws Exception {
        // Random names under a delegated child are all NOERROR referrals; they
        // must share one bucket or they re-open the reflection dodge that the
        // per-zone NXDOMAIN keying closed.
        Message q1 = query("r1.child.example.com", Type.A);
        Message q2 = query("r2.child.example.com", Type.AAAA);
        String k1 = DnsRateLimiter.keyFor(q1, referralFor(q1, "child.example.com"));
        String k2 = DnsRateLimiter.keyFor(q2, referralFor(q2, "child.example.com"));
        assertThat(k1).isEqualTo(k2).startsWith("ref|");

        // Referrals for a different delegation stay independent.
        Message q3 = query("r1.other.example.com", Type.A);
        assertThat(DnsRateLimiter.keyFor(q3, referralFor(q3, "other.example.com"))).isNotEqualTo(k1);

        // An AUTHORITATIVE answer that happens to carry NS records is not a referral.
        Message authoritative = noerrorFor(q1);
        authoritative.getHeader().setFlag(Flags.AA);
        authoritative.addRecord(new NSRecord(Name.fromString("child.example.com."), DClass.IN, 300,
            Name.fromString("ns1.example.com.")), Section.AUTHORITY);
        assertThat(DnsRateLimiter.keyFor(q1, authoritative)).doesNotStartWith("ref|");
    }

    private static Message referralFor(Message query, String delegation) throws Exception {
        Message response = new Message(query.getHeader().getID());
        response.getHeader().setFlag(Flags.QR);
        response.addRecord(query.getQuestion(), Section.QUESTION);
        Name owner = Name.fromString(delegation + ".");
        response.addRecord(new NSRecord(owner, DClass.IN, 300, Name.fromString("ns1." + delegation + ".")),
            Section.AUTHORITY);
        return response;
    }

    @Test
    void underTheLimitEverythingIsAllowed() throws Exception {
        DnsRateLimiter limiter = new DnsRateLimiter(() -> 10);
        InetAddress client = InetAddress.getByName("203.0.113.5");
        for (int i = 0; i < 10; i++) {
            assertThat(limiter.check(client, KEY)).isEqualTo(Verdict.ALLOW);
        }
    }

    @Test
    void overTheLimitAlternatesSlipAndDrop() throws Exception {
        DnsRateLimiter limiter = new DnsRateLimiter(() -> 5);
        InetAddress client = InetAddress.getByName("203.0.113.5");

        List<Verdict> verdicts = new ArrayList<>();
        for (int i = 0; i < 11; i++) {
            verdicts.add(limiter.check(client, KEY));
        }
        assertThat(verdicts.subList(0, 5)).containsOnly(Verdict.ALLOW);
        // Every second limited response slips through as TC so a legitimate
        // client behind the limited prefix can still fail over to TCP.
        assertThat(verdicts.subList(5, 11)).containsExactly(
            Verdict.SLIP, Verdict.DROP, Verdict.SLIP, Verdict.DROP, Verdict.SLIP, Verdict.DROP);
    }

    @Test
    void bucketsArePerPrefixAndPerKey() throws Exception {
        DnsRateLimiter limiter = new DnsRateLimiter(() -> 2);
        InetAddress clientA = InetAddress.getByName("203.0.113.5");
        InetAddress sameNet = InetAddress.getByName("203.0.113.99");
        InetAddress otherNet = InetAddress.getByName("198.51.100.5");

        limiter.check(clientA, KEY);
        limiter.check(clientA, KEY);
        // Same /24 shares the bucket (spoofed sources inside one network can't widen it)...
        assertThat(limiter.check(sameNet, KEY)).isNotEqualTo(Verdict.ALLOW);
        // ...but another network and another key are independent buckets.
        assertThat(limiter.check(otherNet, KEY)).isEqualTo(Verdict.ALLOW);
        assertThat(limiter.check(clientA, "mail.example.com|1")).isEqualTo(Verdict.ALLOW);
    }

    @Test
    void nxdomainKeysPerZoneNotPerQname() throws Exception {
        // A random-subdomain flood must land in ONE bucket, or RRL is useless
        // against the standard reflection attack.
        Message q1 = query("a1b2c3.example.com", Type.A);
        Message q2 = query("z9y8x7.example.com", Type.AAAA);
        String k1 = DnsRateLimiter.keyFor(q1, nxdomainFor(q1, "example.com"));
        String k2 = DnsRateLimiter.keyFor(q2, nxdomainFor(q2, "example.com"));
        assertThat(k1).isEqualTo(k2);

        // A different zone's NXDOMAINs stay independent.
        Message q3 = query("a1b2c3.other.org", Type.A);
        assertThat(DnsRateLimiter.keyFor(q3, nxdomainFor(q3, "other.org"))).isNotEqualTo(k1);

        // Positive answers keep per-qname/qtype buckets.
        String p1 = DnsRateLimiter.keyFor(q1, noerrorFor(q1));
        String p2 = DnsRateLimiter.keyFor(q2, noerrorFor(q2));
        assertThat(p1).isNotEqualTo(p2);

        // Non-NOERROR rcodes share one error bucket per rcode.
        Message refused = noerrorFor(q1);
        refused.getHeader().setRcode(Rcode.REFUSED);
        Message refused2 = noerrorFor(q2);
        refused2.getHeader().setRcode(Rcode.REFUSED);
        assertThat(DnsRateLimiter.keyFor(q1, refused))
            .isEqualTo(DnsRateLimiter.keyFor(q2, refused2));
    }

    @Test
    void loopbackAndDisabledLimitsAreExempt() throws Exception {
        DnsRateLimiter limited = new DnsRateLimiter(() -> 1);
        InetAddress loopback = InetAddress.getByName("127.0.0.1");
        for (int i = 0; i < 5; i++) {
            assertThat(limited.check(loopback, KEY)).isEqualTo(Verdict.ALLOW);
        }

        DnsRateLimiter disabled = new DnsRateLimiter(() -> 0);
        InetAddress client = InetAddress.getByName("203.0.113.5");
        for (int i = 0; i < 5; i++) {
            assertThat(disabled.check(client, KEY)).isEqualTo(Verdict.ALLOW);
        }
    }
}
