package be.elevenways.hohenheim.server.dns;

import be.elevenways.hohenheim.dns.DelegationVerdict;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.xbill.DNS.AAAARecord;
import org.xbill.DNS.ARecord;
import org.xbill.DNS.DClass;
import org.xbill.DNS.Flags;
import org.xbill.DNS.Message;
import org.xbill.DNS.NSRecord;
import org.xbill.DNS.Name;
import org.xbill.DNS.Rcode;
import org.xbill.DNS.Record;
import org.xbill.DNS.SOARecord;
import org.xbill.DNS.Section;
import org.xbill.DNS.SimpleResolver;
import org.xbill.DNS.Type;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;

/**
 * Judges one primary zone's delegation the way a resolver experiences it: the parent's NS
 * RRset and glue (recursion off), compared with the apex NS names we serve, then every
 * delegated server asked for the zone SOA and its serial compared with ours.
 */
public final class DelegationCheck {

    private static final Duration QUERY_TIMEOUT = Duration.ofSeconds(5);

    private final DelegationLookup lookup;

    public DelegationCheck(@NonNull DelegationLookup lookup) {
        this.lookup = lookup;
    }

    /** One observation: a verdict about one subject (a nameserver name, or the zone itself). */
    public record Finding(@NonNull DelegationVerdict verdict, @NonNull String subject) {

        /** The stored line shape: {@code token subject}. */
        public @NonNull String line() {
            return this.verdict.token() + " " + this.subject;
        }
    }

    /** The worst verdict plus every finding behind it; a clean check carries no findings. */
    public record Report(@NonNull DelegationVerdict verdict, @NonNull List<Finding> findings) {

        /** @return the findings as lines, or null when there are none */
        public @Nullable String detail() {
            if (this.findings.isEmpty()) {
                return null;
            }
            StringBuilder text = new StringBuilder();
            for (Finding finding : this.findings) {
                if (!text.isEmpty()) {
                    text.append('\n');
                }
                text.append(finding.line());
            }
            return text.toString();
        }

        /** @return this report plus the extra findings, the verdict re-derived as the worst of all */
        public @NonNull Report with(@NonNull List<Finding> extra) {
            if (extra.isEmpty()) {
                return this;
            }
            List<Finding> all = new ArrayList<>(this.findings);
            all.addAll(extra);
            return report(all);
        }
    }

    /**
     * @param origin     the zone
     * @param ourNs      the apex NS names this primary serves, lowercase without trailing dot
     * @param ourSerial  the serial this primary serves
     */
    public @NonNull Report check(@NonNull Name origin, @NonNull List<String> ourNs, long ourSerial) {
        List<Finding> findings = new ArrayList<>();

        List<InetSocketAddress> parents;
        try {
            parents = this.lookup.parentNameservers(origin);
        }
        catch (Exception e) {
            parents = List.of();
        }
        Message referral = null;
        for (InetSocketAddress parent : parents) {
            referral = query(parent, origin, Type.NS);
            if (referral != null) {
                break;
            }
        }
        if (referral == null) {
            return report(findings, new Finding(DelegationVerdict.PARENT_UNREACHABLE, plain(origin)));
        }

        // The parent answers a delegation as a referral (AUTHORITY) or, when it also hosts
        // the child, authoritatively (ANSWER); glue rides ADDITIONAL either way.
        Map<String, List<InetSocketAddress>> delegated = new LinkedHashMap<>();
        for (int section : new int[] {Section.ANSWER, Section.AUTHORITY}) {
            for (Record record : referral.getSection(section)) {
                if (record instanceof NSRecord ns && record.getName().equals(origin)) {
                    delegated.computeIfAbsent(plain(ns.getTarget()), k -> new ArrayList<>());
                }
            }
        }
        if (delegated.isEmpty()) {
            return report(findings, new Finding(DelegationVerdict.NOT_DELEGATED, plain(origin)));
        }
        for (Record record : referral.getSection(Section.ADDITIONAL)) {
            List<InetSocketAddress> glue = delegated.get(plain(record.getName()));
            if (glue == null) {
                continue;
            }
            if (record instanceof ARecord a) {
                glue.add(new InetSocketAddress(a.getAddress(), this.lookup.nameserverPort()));
            }
            else if (record instanceof AAAARecord aaaa) {
                glue.add(new InetSocketAddress(aaaa.getAddress(), this.lookup.nameserverPort()));
            }
        }

        TreeSet<String> ours = new TreeSet<>();
        for (String name : ourNs) {
            ours.add(name.toLowerCase(Locale.ROOT));
        }
        for (String name : ours) {
            if (!delegated.containsKey(name)) {
                findings.add(new Finding(DelegationVerdict.LISTED_NOT_DELEGATED, name));
            }
        }
        for (Map.Entry<String, List<InetSocketAddress>> entry : delegated.entrySet()) {
            String name = entry.getKey();
            if (!ours.contains(name)) {
                findings.add(new Finding(DelegationVerdict.DELEGATED_NOT_LISTED, name));
            }
            List<InetSocketAddress> addresses = entry.getValue();
            boolean inBailiwick = isInBailiwick(name, origin);
            if (addresses.isEmpty()) {
                if (inBailiwick) {
                    findings.add(new Finding(DelegationVerdict.MISSING_GLUE, name));
                }
                try {
                    addresses = this.lookup.addressesOf(Name.fromString(name + "."));
                }
                catch (Exception e) {
                    addresses = List.of();
                }
            }
            Long served = null;
            for (InetSocketAddress address : addresses) {
                served = authoritativeSerial(address, origin);
                if (served != null) {
                    break;
                }
            }
            if (served == null) {
                findings.add(new Finding(DelegationVerdict.NS_UNREACHABLE, name));
            }
            else if (!DnsSoaProbe.serialReached(served, ourSerial)) {
                findings.add(new Finding(DelegationVerdict.NS_STALE_SERIAL,
                    name + " serial " + served + " < " + ourSerial));
            }
        }
        return report(findings);
    }

    private static @NonNull Report report(@NonNull List<Finding> findings, Finding... extra) {
        for (Finding finding : extra) {
            findings.add(finding);
        }
        DelegationVerdict worst = DelegationVerdict.MATCHES;
        for (Finding finding : findings) {
            worst = worst.worseOf(finding.verdict());
        }
        return new Report(worst, List.copyOf(findings));
    }

    /** @return the SOA serial the server answers AUTHORITATIVELY, or null for anything else */
    private static @Nullable Long authoritativeSerial(@NonNull InetSocketAddress server,
                                                      @NonNull Name origin) {
        Message response = query(server, origin, Type.SOA);
        if (response == null || !response.getHeader().getFlag(Flags.AA)) {
            return null;
        }
        for (Record record : response.getSection(Section.ANSWER)) {
            if (record instanceof SOARecord soa && soa.getName().equals(origin)) {
                return soa.getSerial();
            }
        }
        return null;
    }

    /** A non-recursive query; null on transport failure or a server error rcode. */
    private static @Nullable Message query(@NonNull InetSocketAddress server, @NonNull Name name,
                                           int type) {
        try {
            SimpleResolver resolver = new SimpleResolver(server);
            resolver.setTimeout(QUERY_TIMEOUT);
            Message query = Message.newQuery(Record.newRecord(name, type, DClass.IN));
            query.getHeader().unsetFlag(Flags.RD);
            Message response = resolver.send(query);
            int rcode = response.getRcode();
            if (rcode != Rcode.NOERROR && rcode != Rcode.NXDOMAIN) {
                return null;
            }
            return response;
        }
        catch (Exception e) {
            return null;
        }
    }

    private static boolean isInBailiwick(@NonNull String nameserver, @NonNull Name origin) {
        try {
            return Name.fromString(nameserver + ".").subdomain(origin);
        }
        catch (Exception e) {
            return false;
        }
    }

    private static @NonNull String plain(@NonNull Name name) {
        return name.toString(true).toLowerCase(Locale.ROOT);
    }
}
