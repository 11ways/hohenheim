package be.elevenways.hohenheim.server.dns;

import org.checkerframework.checker.nullness.qual.NonNull;
import org.xbill.DNS.DClass;
import org.xbill.DNS.DNSSEC;
import org.xbill.DNS.NSECRecord;
import org.xbill.DNS.Name;
import org.xbill.DNS.RRSIGRecord;
import org.xbill.DNS.RRset;
import org.xbill.DNS.Record;
import org.xbill.DNS.Type;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Online DNSSEC signer: augments a built zone's node map in place with RRSIGs
 * over every authoritative RRset, an apex DNSKEY, and an NSEC chain for
 * authenticated denial of existence. Uses a single CSK (algorithm 13). NS at a
 * delegation point stays unsigned (it is not authoritative data); everything
 * below a delegation is occluded.
 */
public final class DnsSecSigner {

    /** Signatures are valid from an hour ago (clock skew) to two weeks out; re-signed daily. */
    private static final Duration INCEPTION_SKEW = Duration.ofHours(1);
    private static final Duration VALIDITY = Duration.ofDays(14);

    private DnsSecSigner() {}

    /**
     * Signs the zone represented by {@code nodes} in place.
     *
     * @return the canonically ordered NSEC owner names (for covering-NSEC lookups)
     */
    public static @NonNull List<Name> sign(@NonNull Name origin,
                                           long defaultTtl,
                                           long negativeTtl,
                                           @NonNull Map<Name, Map<Integer, List<Record>>> nodes,
                                           @NonNull DnsSecKeys keys,
                                           @NonNull Instant now) {
        Instant inception = now.minus(INCEPTION_SKEW);
        Instant expiration = now.plus(VALIDITY);
        var dnsKey = keys.dnsKeyAt(origin, defaultTtl);

        // Apex DNSKEY RRset (the CSK), before NSEC/type bitmaps are computed.
        Map<Integer, List<Record>> apex = nodes.get(origin);
        if (apex == null) {
            return List.of(); // no apex: not a servable zone, nothing to sign
        }
        apex.put(Type.DNSKEY, new ArrayList<>(List.of(dnsKey)));

        Set<Name> delegations = delegationPoints(origin, nodes);

        // Canonical owner order over the non-occluded names.
        List<Name> signable = new ArrayList<>();
        for (Name name : nodes.keySet()) {
            if (!isOccluded(name, origin, delegations)) {
                signable.add(name);
            }
        }
        signable.sort(Comparator.naturalOrder());

        // Phase 1: NSEC records (bitmaps reference the final type set at each owner).
        for (int i = 0; i < signable.size(); i++) {
            Name owner = signable.get(i);
            Name next = signable.get((i + 1) % signable.size());
            Map<Integer, List<Record>> node = nodes.get(owner);
            int[] types = nsecTypes(node, owner, origin, delegations);
            NSECRecord nsec = new NSECRecord(owner, DClass.IN, negativeTtl, next, types);
            node.put(Type.NSEC, new ArrayList<>(List.of(nsec)));
        }

        // Phase 2: sign every authoritative RRset (including the NSEC just added).
        for (Name owner : signable) {
            Map<Integer, List<Record>> node = nodes.get(owner);
            List<RRSIGRecord> sigs = new ArrayList<>();
            for (Map.Entry<Integer, List<Record>> rrset : node.entrySet()) {
                int type = rrset.getKey();
                if (type == Type.RRSIG || !isSignable(type, owner, origin, delegations)) {
                    continue;
                }
                RRset set = new RRset();
                for (Record record : rrset.getValue()) {
                    set.addRR(record);
                }
                try {
                    sigs.add(DNSSEC.sign(set, dnsKey, keys.getPrivateKey(), inception, expiration));
                }
                catch (DNSSEC.DNSSECException e) {
                    throw new IllegalStateException("Signing " + owner + "/" + Type.string(type)
                        + " failed: " + e.getMessage(), e);
                }
            }
            if (!sigs.isEmpty()) {
                node.put(Type.RRSIG, new ArrayList<>(sigs));
            }
        }

        return signable;
    }

    /** Non-apex owners carrying an NS RRset (zone cuts). */
    private static @NonNull Set<Name> delegationPoints(@NonNull Name origin,
                                                       @NonNull Map<Name, Map<Integer, List<Record>>> nodes) {
        Set<Name> points = new LinkedHashSet<>();
        for (Map.Entry<Name, Map<Integer, List<Record>>> node : nodes.entrySet()) {
            if (!node.getKey().equals(origin) && node.getValue().containsKey(Type.NS)) {
                points.add(node.getKey());
            }
        }
        return points;
    }

    /** A name strictly below a delegation point is occluded: not signed, no NSEC. */
    private static boolean isOccluded(@NonNull Name name, @NonNull Name origin,
                                      @NonNull Set<Name> delegations) {
        for (Name delegation : delegations) {
            if (!name.equals(delegation) && name.subdomain(delegation)) {
                return true;
            }
        }
        return false;
    }

    /** NS at a non-apex delegation point is not authoritative, so it is not signed. */
    private static boolean isSignable(int type, @NonNull Name owner, @NonNull Name origin,
                                      @NonNull Set<Name> delegations) {
        if (type == Type.NS && !owner.equals(origin) && delegations.contains(owner)) {
            return false;
        }
        return true;
    }

    /**
     * The NSEC type bitmap: every RR type present at the owner, plus NSEC and
     * RRSIG. Every signable owner carries a signed NSEC, so RRSIG is always
     * present; NS at a delegation point is listed (present) even though unsigned.
     */
    private static int[] nsecTypes(@NonNull Map<Integer, List<Record>> node, @NonNull Name owner,
                                   @NonNull Name origin, @NonNull Set<Name> delegations) {
        Set<Integer> types = new TreeSet<>(node.keySet());
        types.add(Type.NSEC);
        types.add(Type.RRSIG);
        int[] out = new int[types.size()];
        int i = 0;
        for (int type : types) {
            out[i++] = type;
        }
        return out;
    }
}
