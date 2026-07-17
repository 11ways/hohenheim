package be.elevenways.hohenheim.server.dns;

import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.xbill.DNS.CNAMERecord;
import org.xbill.DNS.ExtendedFlags;
import org.xbill.DNS.Flags;
import org.xbill.DNS.Header;
import org.xbill.DNS.MXRecord;
import org.xbill.DNS.Message;
import org.xbill.DNS.NSRecord;
import org.xbill.DNS.Name;
import org.xbill.DNS.OPTRecord;
import org.xbill.DNS.Opcode;
import org.xbill.DNS.Rcode;
import org.xbill.DNS.Record;
import org.xbill.DNS.SRVRecord;
import org.xbill.DNS.Section;
import org.xbill.DNS.TextParseException;
import org.xbill.DNS.Type;

import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Authoritative-only query logic: AA answers, NXDOMAIN vs NODATA with SOA
 * authority data, wildcard synthesis, in-zone CNAME chasing, referrals for
 * delegated children, EDNS sizing with UDP truncation. Recursion, transfers
 * and out-of-zone names are refused.
 */
public final class DnsResponder {

    /** EDNS payload we advertise and the ceiling we honor from clients (DNS flag day 2020). */
    static final int EDNS_PAYLOAD = 1232;
    private static final int PLAIN_UDP_PAYLOAD = 512;
    private static final int MAX_CNAME_DEPTH = 8;

    private final DnsZoneStore store;

    public DnsResponder(@NonNull DnsZoneStore store) {
        this.store = store;
    }

    /**
     * @return the wire response, or null when the datagram must be dropped
     *         (unparseable without a salvageable id, or a response packet)
     */
    public byte @Nullable [] respondToWire(byte @NonNull [] wire, boolean tcp) {
        Message query;
        try {
            query = new Message(wire);
        }
        catch (IOException e) {
            return formErrFor(wire);
        }

        Message response = this.respond(query);
        if (response == null) {
            return null;
        }

        return wireFor(query, response, tcp);
    }

    /** Serializes an already-computed response within the query's transport limits (TC on overflow). */
    public byte @NonNull [] wireFor(@NonNull Message query, @NonNull Message response, boolean tcp) {
        int maxLength = tcp ? 65535 : udpPayloadLimit(query);
        return response.toWire(maxLength);
    }

    /** @return the response message, or null when the query must be dropped */
    public @Nullable Message respond(@NonNull Message query) {
        Header header = query.getHeader();
        if (header.getFlag(Flags.QR)) {
            return null;
        }

        if (header.getOpcode() != Opcode.QUERY) {
            return errorResponse(query, Rcode.NOTIMP);
        }

        Record question = query.getQuestion();
        if (question == null) {
            return errorResponse(query, Rcode.FORMERR);
        }

        if (question.getDClass() != org.xbill.DNS.DClass.IN) {
            return errorResponse(query, Rcode.REFUSED);
        }

        int qtype = question.getType();
        if (qtype == Type.AXFR || qtype == Type.IXFR) {
            return errorResponse(query, Rcode.REFUSED);
        }

        Name qname = question.getName();
        DnsZoneSnapshot zone = this.store.findZoneFor(qname);
        if (zone == null) {
            return errorResponse(query, Rcode.REFUSED);
        }

        Message response = baseResponse(query);
        boolean sign = wantsDnssec(query) && zone.isSigned();

        Name delegation = zone.findDelegation(qname);
        if (delegation != null) {
            // The parent side answers DS queries at the delegation point
            // authoritatively (RFC 4035 3.1.4.1); everything else refers.
            if (qtype == Type.DS && qname.equals(delegation)) {
                response.getHeader().setFlag(Flags.AA);
                answerDelegationDs(response, zone, delegation, sign);
                finishEdns(query, response);
                return response;
            }
            addReferral(response, zone, delegation, sign);
            finishEdns(query, response);
            return response;
        }

        response.getHeader().setFlag(Flags.AA);
        resolve(zone, qname, qtype, response, 0, new HashSet<>(), sign);
        addAdditionalData(response, zone);
        finishEdns(query, response);
        return response;
    }

    private void resolve(@NonNull DnsZoneSnapshot zone,
                         @NonNull Name qname,
                         int qtype,
                         @NonNull Message response,
                         int depth,
                         @NonNull Set<Name> visited,
                         boolean sign) {

        if (depth > MAX_CNAME_DEPTH || !visited.add(qname)) {
            return;
        }

        Map<Integer, List<Record>> node = zone.getNode(qname);

        if (node == null) {
            Name closestEncloser = zone.closestEncloser(qname);
            Name wildcardName = wildcardName(closestEncloser);
            Map<Integer, List<Record>> wildcard = wildcardName != null ? zone.getNode(wildcardName) : null;
            if (wildcard == null) {
                response.getHeader().setRcode(Rcode.NXDOMAIN);
                addNxDomainProof(response, zone, qname, sign);
                return;
            }
            answerFromWildcard(zone, wildcard, wildcardName, qname, qtype, response, depth, visited, sign);
            return;
        }

        answerFromNode(zone, node, qname, qtype, response, depth, visited, sign);
    }

    private void answerFromNode(@NonNull DnsZoneSnapshot zone,
                                @NonNull Map<Integer, List<Record>> node,
                                @NonNull Name qname,
                                int qtype,
                                @NonNull Message response,
                                int depth,
                                @NonNull Set<Name> visited,
                                boolean sign) {

        List<Record> cnames = node.get(Type.CNAME);
        if (cnames != null && !cnames.isEmpty() && qtype != Type.CNAME && qtype != Type.ANY) {
            CNAMERecord cname = (CNAMERecord) cnames.get(0);
            addAnswer(response, cname, qname, false);
            if (sign) {
                addRrsigs(response, zone.getRrsig(qname, Type.CNAME), Section.ANSWER);
            }
            Name target = cname.getTarget();
            if (zone.contains(target)) {
                if (zone.findDelegation(target) == null) {
                    this.resolve(zone, target, qtype, response, depth + 1, visited, sign);
                }
            }
            return;
        }

        if (qtype == Type.ANY) {
            boolean any = false;
            for (Map.Entry<Integer, List<Record>> rrset : node.entrySet()) {
                if (rrset.getKey() == Type.RRSIG || rrset.getKey() == Type.NSEC) {
                    continue;
                }
                for (Record record : rrset.getValue()) {
                    addAnswer(response, record, qname, false);
                    any = true;
                }
                if (sign) {
                    addRrsigs(response, zone.getRrsig(qname, rrset.getKey()), Section.ANSWER);
                }
            }
            if (!any) {
                addNoDataProof(response, zone, qname, sign);
            }
            return;
        }

        List<Record> rrset = node.get(qtype);
        if (rrset == null || rrset.isEmpty()) {
            addNoDataProof(response, zone, qname, sign);
            return;
        }

        for (Record record : rrset) {
            addAnswer(response, record, qname, false);
        }
        if (sign) {
            addRrsigs(response, zone.getRrsig(qname, qtype), Section.ANSWER);
        }
    }

    /**
     * A wildcard match: records AND their RRSIGs are served under the queried
     * name (only the RRSIG's labels field keeps the wildcard's count, which is
     * what signals the expansion), plus the NSEC proving the exact qname does
     * not exist itself (RFC 4035 3.1.3.3).
     */
    private void answerFromWildcard(@NonNull DnsZoneSnapshot zone,
                                    @NonNull Map<Integer, List<Record>> wildcard,
                                    @NonNull Name wildcardOwner,
                                    @NonNull Name qname,
                                    int qtype,
                                    @NonNull Message response,
                                    int depth,
                                    @NonNull Set<Name> visited,
                                    boolean sign) {
        List<Record> rrset = wildcard.get(qtype);
        if (rrset == null || rrset.isEmpty()) {
            List<Record> cnames = wildcard.get(Type.CNAME);
            if (cnames != null && !cnames.isEmpty() && qtype != Type.CNAME) {
                CNAMERecord cname = (CNAMERecord) cnames.get(0);
                addAnswer(response, cname, qname, true);
                if (sign) {
                    addExpandedRrsigs(response, zone.getRrsig(wildcardOwner, Type.CNAME), qname);
                    addWildcardExpansionProof(response, zone, qname);
                }
                Name target = cname.getTarget();
                if (zone.contains(target) && zone.findDelegation(target) == null) {
                    this.resolve(zone, target, qtype, response, depth + 1, visited, sign);
                }
                return;
            }
            if (!sign) {
                addNoDataProof(response, zone, qname, false);
                return;
            }
            // Wildcard NODATA: the wildcard's own NSEC (the type is absent
            // there) plus the NSEC covering the qname (which does not exist).
            addSignedSoa(response, zone);
            addSignedNsec(response, zone, wildcardOwner);
            Name covering = zone.coveringNsecOwner(qname);
            if (covering != null && !covering.equals(wildcardOwner)) {
                addSignedNsec(response, zone, covering);
            }
            return;
        }
        for (Record record : rrset) {
            addAnswer(response, record, qname, true);
        }
        if (sign) {
            addExpandedRrsigs(response, zone.getRrsig(wildcardOwner, qtype), qname);
            addWildcardExpansionProof(response, zone, qname);
        }
    }

    /** Wildcard RRSIGs follow the synthesized owner; the labels field already counts the wildcard's labels. */
    private static void addExpandedRrsigs(@NonNull Message response, @NonNull List<Record> sigs,
                                          @NonNull Name qname) {
        for (Record sig : sigs) {
            response.addRecord(sig.withName(qname), Section.ANSWER);
        }
    }

    /** The NSEC (plus its RRSIG) proving a wildcard-expanded qname does not exist as a node. */
    private static void addWildcardExpansionProof(@NonNull Message response,
                                                  @NonNull DnsZoneSnapshot zone, @NonNull Name qname) {
        Name covering = zone.coveringNsecOwner(qname);
        if (covering != null) {
            addSignedNsec(response, zone, covering);
        }
    }

    private static @Nullable Name wildcardName(@NonNull Name closestEncloser) {
        try {
            return Name.fromString("*", closestEncloser);
        }
        catch (TextParseException e) {
            return null;
        }
    }

    private static void addAnswer(@NonNull Message response,
                                  @NonNull Record record,
                                  @NonNull Name qname,
                                  boolean synthesized) {
        Record answer = synthesized ? record.withName(qname) : record;
        response.addRecord(answer, Section.ANSWER);
    }

    private static void addRrsigs(@NonNull Message response, @NonNull List<Record> sigs, int section) {
        for (Record sig : sigs) {
            response.addRecord(sig, section);
        }
    }

    /** NODATA: SOA (signed variant serves the real apex SOA + RRSIG) plus the owner's NSEC. */
    private static void addNoDataProof(@NonNull Message response, @NonNull DnsZoneSnapshot zone,
                                       @NonNull Name qname, boolean sign) {
        if (!sign) {
            response.addRecord(zone.getNegativeSoa(), Section.AUTHORITY);
            return;
        }
        addSignedSoa(response, zone);
        addSignedNsec(response, zone, qname);
    }

    /** NXDOMAIN: SOA + the NSEC covering the missing name + the NSEC denying its wildcard. */
    private static void addNxDomainProof(@NonNull Message response, @NonNull DnsZoneSnapshot zone,
                                         @NonNull Name qname, boolean sign) {
        if (!sign) {
            response.addRecord(zone.getNegativeSoa(), Section.AUTHORITY);
            return;
        }
        addSignedSoa(response, zone);
        Name covering = zone.coveringNsecOwner(qname);
        if (covering != null) {
            addSignedNsec(response, zone, covering);
        }
        // Deny the wildcard at the CLOSEST ENCLOSER (RFC 4035 3.1.3.2): we only
        // reach NXDOMAIN when that wildcard has no node, so a covering NSEC
        // exists for it; often it is the same NSEC that covers the qname.
        Name wildcard = wildcardName(zone.closestEncloser(qname));
        if (wildcard != null) {
            Name wildcardCovering = zone.coveringNsecOwner(wildcard);
            if (wildcardCovering != null && !wildcardCovering.equals(covering)) {
                addSignedNsec(response, zone, wildcardCovering);
            }
        }
    }

    /** DS at a delegation point: the one qtype the PARENT answers authoritatively. */
    private static void answerDelegationDs(@NonNull Message response, @NonNull DnsZoneSnapshot zone,
                                           @NonNull Name delegation, boolean sign) {
        List<Record> ds = zone.getRrset(delegation, Type.DS);
        if (ds != null && !ds.isEmpty()) {
            for (Record record : ds) {
                response.addRecord(record, Section.ANSWER);
            }
            if (sign) {
                addRrsigs(response, zone.getRrsig(delegation, Type.DS), Section.ANSWER);
            }
            return;
        }
        // No DS: NODATA whose NSEC (at the delegation) proves the child is insecure.
        addNoDataProof(response, zone, delegation, sign);
    }

    private static void addSignedSoa(@NonNull Message response, @NonNull DnsZoneSnapshot zone) {
        // The negative-TTL SOA copy; its RRSIG verifies via the original-TTL field.
        response.addRecord(zone.getNegativeSoa(), Section.AUTHORITY);
        addRrsigs(response, zone.getRrsig(zone.getOrigin(), Type.SOA), Section.AUTHORITY);
    }

    private static void addSignedNsec(@NonNull Message response, @NonNull DnsZoneSnapshot zone, @NonNull Name owner) {
        List<Record> nsec = zone.getRrset(owner, Type.NSEC);
        if (nsec == null) {
            return;
        }
        for (Record record : nsec) {
            response.addRecord(record, Section.AUTHORITY);
        }
        addRrsigs(response, zone.getRrsig(owner, Type.NSEC), Section.AUTHORITY);
    }

    private static void addReferral(@NonNull Message response,
                                    @NonNull DnsZoneSnapshot zone,
                                    @NonNull Name delegation,
                                    boolean sign) {
        List<Record> nsSet = zone.getRrset(delegation, Type.NS);
        if (nsSet == null) {
            return;
        }
        for (Record ns : nsSet) {
            response.addRecord(ns, Section.AUTHORITY);
            Name target = ((NSRecord) ns).getTarget();
            if (zone.contains(target)) {
                addGlue(response, zone, target);
            }
        }
        if (sign) {
            // Secure delegation: a DS proves the child is signed, else the NSEC
            // at the delegation proves no DS exists (insecure delegation).
            List<Record> ds = zone.getRrset(delegation, Type.DS);
            if (ds != null && !ds.isEmpty()) {
                for (Record record : ds) {
                    response.addRecord(record, Section.AUTHORITY);
                }
                addRrsigs(response, zone.getRrsig(delegation, Type.DS), Section.AUTHORITY);
            }
            else {
                addSignedNsec(response, zone, delegation);
            }
        }
    }

    /** MX/SRV/NS answers get in-zone target addresses appended as additional data. */
    private static void addAdditionalData(@NonNull Message response, @NonNull DnsZoneSnapshot zone) {
        Set<Name> targets = new HashSet<>();
        for (Record record : response.getSection(Section.ANSWER)) {
            Name target = switch (record.getType()) {
                case Type.MX -> ((MXRecord) record).getTarget();
                case Type.SRV -> ((SRVRecord) record).getTarget();
                case Type.NS -> ((NSRecord) record).getTarget();
                default -> null;
            };
            if (target != null && zone.contains(target) && targets.add(target)) {
                addGlue(response, zone, target);
            }
        }
    }

    private static void addGlue(@NonNull Message response, @NonNull DnsZoneSnapshot zone, @NonNull Name target) {
        for (int type : new int[] {Type.A, Type.AAAA}) {
            List<Record> rrset = zone.getRrset(target, type);
            if (rrset != null) {
                for (Record record : rrset) {
                    response.addRecord(record, Section.ADDITIONAL);
                }
            }
        }
    }

    private static @NonNull Message baseResponse(@NonNull Message query) {
        Message response = new Message(query.getHeader().getID());
        response.getHeader().setFlag(Flags.QR);
        if (query.getHeader().getFlag(Flags.RD)) {
            response.getHeader().setFlag(Flags.RD);
        }
        Record question = query.getQuestion();
        if (question != null) {
            response.addRecord(question, Section.QUESTION);
        }
        return response;
    }

    private static @NonNull Message errorResponse(@NonNull Message query, int rcode) {
        Message response = baseResponse(query);
        response.getHeader().setRcode(rcode);
        finishEdns(query, response);
        return response;
    }

    private static boolean wantsDnssec(@NonNull Message query) {
        OPTRecord opt = query.getOPT();
        return opt != null && (opt.getFlags() & ExtendedFlags.DO) != 0;
    }

    private static void finishEdns(@NonNull Message query, @NonNull Message response) {
        OPTRecord opt = query.getOPT();
        if (opt != null) {
            // Echo the DO bit so a validating resolver sees DNSSEC was honored.
            int flags = (opt.getFlags() & ExtendedFlags.DO) != 0 ? ExtendedFlags.DO : 0;
            response.addRecord(new OPTRecord(EDNS_PAYLOAD, 0, 0, flags), Section.ADDITIONAL);
        }
    }

    private static int udpPayloadLimit(@NonNull Message query) {
        OPTRecord opt = query.getOPT();
        if (opt == null) {
            return PLAIN_UDP_PAYLOAD;
        }
        int advertised = opt.getPayloadSize();
        return Math.max(PLAIN_UDP_PAYLOAD, Math.min(advertised, EDNS_PAYLOAD));
    }

    /** Best-effort FORMERR for an unparseable datagram; null when even the id is unreadable. */
    private static byte @Nullable [] formErrFor(byte @NonNull [] wire) {
        if (wire.length < 12) {
            return null;
        }
        if ((wire[2] & 0x80) != 0) {
            return null;
        }
        int id = ((wire[0] & 0xff) << 8) | (wire[1] & 0xff);
        Message response = new Message(id);
        response.getHeader().setFlag(Flags.QR);
        response.getHeader().setRcode(Rcode.FORMERR);
        return response.toWire(PLAIN_UDP_PAYLOAD);
    }
}
