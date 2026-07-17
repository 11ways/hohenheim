package be.elevenways.hohenheim.server.dns;

import be.elevenways.hohenheim.model.DnsPeerModel;
import be.elevenways.hohenheim.model.DnsZonePeerModel;
import be.elevenways.protoblast.common.Blast;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.xbill.DNS.Flags;
import org.xbill.DNS.Message;
import org.xbill.DNS.Name;
import org.xbill.DNS.Rcode;
import org.xbill.DNS.Record;
import org.xbill.DNS.Section;
import org.xbill.DNS.TSIG;
import org.xbill.DNS.TSIGRecord;

import java.util.ArrayList;
import java.util.List;

/**
 * Primary-side AXFR: streams a zone to a TSIG-authenticated secondary. The key
 * resolver decides, per (zone, requested key name), which TSIG may pull the
 * zone; the default resolves it from the zone's linked peers. Everything the
 * resolver rejects is REFUSED.
 */
public final class AxfrResponder {

    /**
     * Per-message caps: count AND estimated rdata bytes, so a TXT-heavy zone
     * (DKIM keys etc.) can never overflow the 64 KiB TCP message ceiling,
     * which dnsjava would silently truncate and the secondary would reject.
     */
    private static final int RECORDS_PER_MESSAGE = 100;
    private static final int BYTES_PER_MESSAGE = 32_768;

    /** Resolves the TSIG key authorized to transfer a zone, or null to refuse. */
    @FunctionalInterface
    public interface KeyResolver {
        @Nullable TSIG resolve(int zoneId, @NonNull Name requestedKeyName);
    }

    private final DnsZoneStore store;
    private final KeyResolver keyResolver;

    public AxfrResponder(@NonNull DnsZoneStore store) {
        this(store, AxfrResponder::resolveFromLinkedPeers);
    }

    public AxfrResponder(@NonNull DnsZoneStore store, @NonNull KeyResolver keyResolver) {
        this.store = store;
        this.keyResolver = keyResolver;
    }

    /**
     * @return the length-prefix-ready wire messages of the AXFR stream, or null
     *         when the transfer is refused (the caller sends a single REFUSED)
     */
    public @Nullable List<byte[]> respond(@NonNull Message query, byte @NonNull [] rawQuery) {
        Record question = query.getQuestion();
        if (question == null) {
            return null;
        }
        Name qname = question.getName();
        DnsZoneSnapshot zone = this.store.getZone(qname.toString(true).toLowerCase());
        if (zone == null || !qname.equals(zone.getOrigin())) {
            return null; // not an apex we host: REFUSED
        }

        TSIGRecord queryTsig = query.getTSIG();
        if (queryTsig == null) {
            Blast.log("DNS: AXFR of", zone.getOriginString(), "refused (no TSIG)");
            return null;
        }
        // AIDEV-NOTE: RFC 8945 wants NOTAUTH+BADKEY/BADSIG here; we send a plain
        // REFUSED, which dnsjava and NSD/Knot secondaries handle as a failed
        // transfer just the same. Revisit only if a picky secondary appears.
        TSIG key = this.keyResolver.resolve(zone.getZoneId(), queryTsig.getName());
        if (key == null || key.verify(query, rawQuery, null) != Rcode.NOERROR) {
            Blast.log("DNS: AXFR of", zone.getOriginString(), "refused (unauthorized)");
            return null;
        }

        return buildStream(query, zone, key, queryTsig);
    }

    private static @NonNull List<byte[]> buildStream(@NonNull Message query,
                                                     @NonNull DnsZoneSnapshot zone,
                                                     @NonNull TSIG signer,
                                                     @NonNull TSIGRecord queryTsig) {
        List<Record> sequence = new ArrayList<>();
        sequence.add(zone.getSoa());
        sequence.addAll(zone.allRecordsExceptSoa());
        sequence.add(zone.getSoa());

        // StreamGenerator signs the first, last and periodic intermediate messages,
        // chained off the request's TSIG record.
        TSIG.StreamGenerator generator = new TSIG.StreamGenerator(signer, queryTsig);

        List<byte[]> messages = new ArrayList<>();
        int id = query.getHeader().getID();
        boolean first = true;
        int index = 0;
        while (index < sequence.size()) {
            Message message = new Message(id);
            message.getHeader().setFlag(Flags.QR);
            message.getHeader().setFlag(Flags.AA);
            if (first) {
                message.addRecord(query.getQuestion(), Section.QUESTION);
            }
            int count = 0;
            int bytes = 0;
            while (index < sequence.size() && count < RECORDS_PER_MESSAGE) {
                Record record = sequence.get(index);
                int recordBytes = record.toWire(Section.ANSWER).length;
                if (count > 0 && bytes + recordBytes > BYTES_PER_MESSAGE) {
                    break; // flush; an oversized single record still goes out alone
                }
                message.addRecord(record, Section.ANSWER);
                bytes += recordBytes;
                count++;
                index++;
            }
            generator.generate(message);
            messages.add(message.toWire(Message.MAXLENGTH));
            first = false;
        }
        return messages;
    }

    /** Default authorization: a peer linked to the zone whose TSIG key name matches. */
    private static @Nullable TSIG resolveFromLinkedPeers(int zoneId, @NonNull Name requestedKeyName) {
        DnsPeerModel peerModel = Models.get(DnsPeerModel.class);
        for (Row link : Models.get(DnsZonePeerModel.class).findByZoneId(zoneId)) {
            Integer peerId = link.get(DnsZonePeerModel.PEER_ID);
            if (peerId == null) {
                continue;
            }
            Row peer = peerModel.findById(peerId);
            if (peer == null || !Boolean.TRUE.equals(peer.get(DnsPeerModel.ENABLED))) {
                continue;
            }
            String peerKeyName = peer.get(DnsPeerModel.TSIG_KEY_NAME);
            if (peerKeyName != null && DnsTsig.canonicalKeyName(peerKeyName).equals(requestedKeyName)) {
                return DnsTsig.forPeer(peer);
            }
        }
        return null;
    }
}
