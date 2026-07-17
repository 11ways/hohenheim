package be.elevenways.hohenheim.server.dns;

import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.xbill.DNS.CNAMERecord;
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

        Name delegation = zone.findDelegation(qname);
        if (delegation != null) {
            addReferral(response, zone, delegation);
            finishEdns(query, response);
            return response;
        }

        response.getHeader().setFlag(Flags.AA);
        resolve(zone, qname, qtype, response, 0, new HashSet<>());
        addAdditionalData(response, zone);
        finishEdns(query, response);
        return response;
    }

    private void resolve(@NonNull DnsZoneSnapshot zone,
                         @NonNull Name qname,
                         int qtype,
                         @NonNull Message response,
                         int depth,
                         @NonNull Set<Name> visited) {

        if (depth > MAX_CNAME_DEPTH || !visited.add(qname)) {
            return;
        }

        Map<Integer, List<Record>> node = zone.getNode(qname);

        if (node == null) {
            Name closestEncloser = zone.closestEncloser(qname);
            Map<Integer, List<Record>> wildcard = wildcardNode(zone, closestEncloser);
            if (wildcard == null) {
                response.getHeader().setRcode(Rcode.NXDOMAIN);
                addNegativeAuthority(response, zone);
                return;
            }
            answerFromNode(zone, wildcard, qname, qtype, response, depth, visited, true);
            return;
        }

        answerFromNode(zone, node, qname, qtype, response, depth, visited, false);
    }

    private void answerFromNode(@NonNull DnsZoneSnapshot zone,
                                @NonNull Map<Integer, List<Record>> node,
                                @NonNull Name qname,
                                int qtype,
                                @NonNull Message response,
                                int depth,
                                @NonNull Set<Name> visited,
                                boolean synthesized) {

        List<Record> cnames = node.get(Type.CNAME);
        if (cnames != null && !cnames.isEmpty() && qtype != Type.CNAME && qtype != Type.ANY) {
            CNAMERecord cname = (CNAMERecord) cnames.get(0);
            addAnswer(response, cname, qname, synthesized);
            Name target = cname.getTarget();
            if (zone.contains(target)) {
                if (zone.findDelegation(target) == null) {
                    this.resolve(zone, target, qtype, response, depth + 1, visited);
                }
            }
            return;
        }

        if (qtype == Type.ANY) {
            boolean any = false;
            for (List<Record> rrset : node.values()) {
                for (Record record : rrset) {
                    addAnswer(response, record, qname, synthesized);
                    any = true;
                }
            }
            if (!any) {
                addNegativeAuthority(response, zone);
            }
            return;
        }

        List<Record> rrset = node.get(qtype);
        if (rrset == null || rrset.isEmpty()) {
            addNegativeAuthority(response, zone);
            return;
        }

        for (Record record : rrset) {
            addAnswer(response, record, qname, synthesized);
        }
    }

    private static @Nullable Map<Integer, List<Record>> wildcardNode(@NonNull DnsZoneSnapshot zone,
                                                                     @NonNull Name closestEncloser) {
        try {
            Name wildcard = Name.fromString("*", closestEncloser);
            return zone.getNode(wildcard);
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

    private static void addNegativeAuthority(@NonNull Message response, @NonNull DnsZoneSnapshot zone) {
        response.addRecord(zone.getNegativeSoa(), Section.AUTHORITY);
    }

    private static void addReferral(@NonNull Message response,
                                    @NonNull DnsZoneSnapshot zone,
                                    @NonNull Name delegation) {
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

    private static void finishEdns(@NonNull Message query, @NonNull Message response) {
        if (query.getOPT() != null) {
            response.addRecord(new OPTRecord(EDNS_PAYLOAD, 0, 0), Section.ADDITIONAL);
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
