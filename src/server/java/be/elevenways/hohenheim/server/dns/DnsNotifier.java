package be.elevenways.hohenheim.server.dns;

import be.elevenways.hohenheim.model.DnsPeerModel;
import be.elevenways.hohenheim.model.DnsZoneModel;
import be.elevenways.hohenheim.model.DnsZonePeerModel;
import be.elevenways.protoblast.common.Blast;
import be.elevenways.protoblast.common.thread.JobRunner;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.xbill.DNS.DClass;
import org.xbill.DNS.Flags;
import org.xbill.DNS.Message;
import org.xbill.DNS.Name;
import org.xbill.DNS.Opcode;
import org.xbill.DNS.Rcode;
import org.xbill.DNS.Record;
import org.xbill.DNS.TSIG;
import org.xbill.DNS.Type;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;

/**
 * Sends DNS NOTIFY to a primary zone's secondaries after its serial changes, so
 * they pull the update within seconds instead of waiting for their refresh
 * timer. Best-effort over UDP; a missed NOTIFY is covered by the refresh poll.
 */
public final class DnsNotifier {

    public static final DnsNotifier INSTANCE = new DnsNotifier();

    private final JobRunner jobs = JobRunner.create("dns-notify");

    /** Asynchronously NOTIFY every secondary linked to the zone. */
    public void notifyZonePeers(int zoneId) {
        jobs.fireAndForget(() -> notifyZonePeersBlocking(zoneId));
    }

    public void notifyZonePeersBlocking(int zoneId) {
        Row zone = Models.get(DnsZoneModel.class).findById(zoneId);
        if (zone == null || DnsZoneModel.ROLE_SECONDARY.equals(DnsZoneModel.roleOf(zone))) {
            return;
        }
        String originString = zone.get(DnsZoneModel.ORIGIN);
        Name origin;
        try {
            origin = Name.fromString(originString + ".");
        }
        catch (Exception e) {
            return;
        }

        DnsPeerModel peerModel = Models.get(DnsPeerModel.class);
        for (Row link : Models.get(DnsZonePeerModel.class).findByZoneId(zoneId)) {
            Integer peerId = link.get(DnsZonePeerModel.PEER_ID);
            Row peer = peerId != null ? peerModel.findById(peerId) : null;
            if (peer == null || !Boolean.TRUE.equals(peer.get(DnsPeerModel.ENABLED))) {
                continue;
            }
            String host = peer.get(DnsPeerModel.TRANSFER_HOST);
            if (host == null || host.isBlank()) {
                continue;
            }
            Integer port = peer.get(DnsPeerModel.TRANSFER_PORT);
            String outcome = sendNotify(host.trim(), port != null ? port : 53, origin,
                DnsTsig.forPeer(peer));
            Integer serial = zone.get(DnsZoneModel.SERIAL);
            DnsFederationTrace.notifySent(link, peer, originString,
                serial != null ? serial : 0, outcome);
        }
    }

    /** @return what came back: the ack's rcode, {@code timeout}, or the send error */
    private static @NonNull String sendNotify(@NonNull String host, int port, @NonNull Name origin,
                                              TSIG tsig) {
        try {
            Message notify = Message.newQuery(Record.newRecord(origin, Type.SOA, DClass.IN));
            notify.getHeader().setOpcode(Opcode.NOTIFY);
            notify.getHeader().setFlag(Flags.AA);
            if (tsig != null) {
                tsig.apply(notify, null);
            }
            byte[] wire = notify.toWire(Message.MAXLENGTH);

            try (DatagramSocket socket = new DatagramSocket()) {
                socket.setSoTimeout(5_000);
                socket.send(new DatagramPacket(wire, wire.length, new InetSocketAddress(host, port)));
                // Read the NOTIFY ack if it comes; a missed ack is fine (refresh covers it).
                try {
                    byte[] buffer = new byte[512];
                    DatagramPacket ack = new DatagramPacket(buffer, buffer.length);
                    socket.receive(ack);
                    byte[] data = new byte[ack.getLength()];
                    System.arraycopy(ack.getData(), 0, data, 0, ack.getLength());
                    return Rcode.string(new Message(data).getRcode()).toLowerCase(java.util.Locale.ROOT);
                }
                catch (java.net.SocketTimeoutException ignored) {
                    // Secondary may not ack promptly; the refresh poll is the backstop.
                    return "timeout";
                }
            }
        }
        catch (Exception e) {
            Blast.log("DNS: NOTIFY to", host + ":" + port, "for", origin.toString(true), "failed:", e.getMessage());
            return "error: " + e.getMessage();
        }
    }
}
