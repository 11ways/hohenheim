package be.elevenways.hohenheim.server.dns;

import be.elevenways.hohenheim.model.DnsPeerModel;
import be.elevenways.hohenheim.model.DnsZoneModel;
import be.elevenways.protoblast.common.Blast;
import be.elevenways.protoblast.common.thread.JobRunner;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.xbill.DNS.Master;
import org.xbill.DNS.Message;
import org.xbill.DNS.Name;
import org.xbill.DNS.Rcode;
import org.xbill.DNS.Record;
import org.xbill.DNS.SOARecord;
import org.xbill.DNS.TSIG;
import org.xbill.DNS.ZoneTransferIn;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Replicates secondary zones from their primary peer: an initial pull at boot,
 * a refresh/retry timer honoring the zone SOA, and out-of-band pulls when a
 * NOTIFY arrives. Content is served straight from the transferred records via
 * {@link DnsZoneStore#putSecondarySnapshot}; local record rows are never
 * touched, so a secondary zone is inherently read-only here.
 */
public final class SecondaryZoneService {

    private static final Duration TICK = Duration.ofSeconds(30);

    private final DnsZoneStore store;
    private final JobRunner jobs = JobRunner.create("dns-secondary");
    private final Map<Integer, ZoneState> state = new ConcurrentHashMap<>();
    /** Zones with a NOTIFY-triggered pull already queued (spoof/burst coalescing). */
    private final Set<Integer> pendingNotifyPulls = ConcurrentHashMap.newKeySet();

    private volatile boolean started;

    public SecondaryZoneService(@NonNull DnsZoneStore store) {
        this.store = store;
    }

    /** Per-zone timing kept out of the DB (recomputed at boot from the SOA). */
    private static final class ZoneState {
        volatile long nextAttemptEpochMs;
        volatile long lastSuccessEpochMs;
    }

    public synchronized void start() {
        if (started) {
            return;
        }
        started = true;
        // Serve the persisted replica immediately, then refresh from the primary.
        jobs.fireAndForget(() -> {
            restorePersistedReplicas();
            refreshAllDue();
        });
        jobs.scheduleRepeating(this::refreshAllDue, TICK.toMillis());
    }

    /**
     * Rebuilds serving snapshots from the replica text persisted at the last
     * successful transfer, so a restart while the primary is unreachable keeps
     * answering until the SOA expire window closes.
     */
    public void restorePersistedReplicas() {
        for (Row zone : Models.get(DnsZoneModel.class).findSecondaries()) {
            int zoneId = zone.get(DnsZoneModel.ID);
            String text = zone.get(DnsZoneModel.REPLICA_RECORDS);
            Instant lastTransfer = zone.get(DnsZoneModel.LAST_TRANSFER_AT);
            if (text == null || text.isBlank() || lastTransfer == null) {
                continue;
            }
            String originString = zone.get(DnsZoneModel.ORIGIN);
            ZoneState zs = state.computeIfAbsent(zoneId, k -> new ZoneState());
            zs.lastSuccessEpochMs = lastTransfer.toEpochMilli();

            int expire = valueOr(zone.get(DnsZoneModel.SOA_EXPIRE), 1209600);
            if (System.currentTimeMillis() - zs.lastSuccessEpochMs > expire * 1000L) {
                // The replica outlived its SOA expire while we were down: do not serve it.
                zone.set(DnsZoneModel.TRANSFER_STATUS, DnsZoneModel.TRANSFER_EXPIRED);
                Models.get(DnsZoneModel.class).save(zone);
                continue;
            }
            try {
                DnsZoneSnapshot snapshot = DnsZoneStore.snapshotFromTransfer(
                    zoneId, originString, parseReplica(originString, text));
                store.putSecondarySnapshot(snapshot);
                Blast.log("DNS: restored persisted replica of", originString,
                    "serial", snapshot.getSerial());
            }
            catch (Exception e) {
                Blast.log("DNS: could not restore replica of", originString, "-", e.getMessage());
            }
        }
    }

    public void stop() {
        jobs.shutdownNow();
    }

    /**
     * A NOTIFY landed: schedule a serial-checked pull for matching secondary
     * zones. When the zone's primary peer has a TSIG key, the NOTIFY must be
     * signed with it (RFC 1996 posture: ignore notifies from strangers); the
     * pull itself only transfers when the primary's serial actually advanced,
     * and concurrent notifies for the same zone coalesce into one queued pull.
     *
     * @return true when at least one pull was scheduled
     */
    public boolean onNotify(@NonNull Message query, byte @NonNull [] wire) {
        Record question = query.getQuestion();
        if (question == null) {
            return false;
        }
        String originString = question.getName().toString(true).toLowerCase(Locale.ROOT);
        boolean scheduled = false;
        for (Row zone : Models.get(DnsZoneModel.class).findSecondaries()) {
            if (!originString.equals(zone.get(DnsZoneModel.ORIGIN))) {
                continue;
            }
            int zoneId = zone.get(DnsZoneModel.ID);
            Row peer = peerFor(zone);
            TSIG key = peer != null ? DnsTsig.forPeer(peer) : null;
            if (key != null
                    && (query.getTSIG() == null || key.verify(query, wire, null) != Rcode.NOERROR)) {
                Blast.log("DNS: ignoring unauthenticated NOTIFY for", originString);
                continue;
            }
            if (pendingNotifyPulls.add(zoneId)) {
                jobs.fireAndForget(() -> {
                    try {
                        transfer(zoneId, false);
                    }
                    finally {
                        pendingNotifyPulls.remove(zoneId);
                    }
                });
            }
            scheduled = true;
        }
        return scheduled;
    }

    private void refreshAllDue() {
        long now = System.currentTimeMillis();
        for (Row zone : Models.get(DnsZoneModel.class).findSecondaries()) {
            int zoneId = zone.get(DnsZoneModel.ID);
            ZoneState zs = state.computeIfAbsent(zoneId, k -> new ZoneState());
            if (now >= zs.nextAttemptEpochMs) {
                transfer(zoneId, false);
            }
        }
    }

    /**
     * Pulls the zone if the primary's serial advanced (or {@code force}).
     * Updates the zone row and serving snapshot; applies refresh/retry/expire.
     */
    public synchronized boolean transfer(int zoneId, boolean force) {
        Row zone = Models.get(DnsZoneModel.class).findById(zoneId);
        if (zone == null || !DnsZoneModel.ROLE_SECONDARY.equals(DnsZoneModel.roleOf(zone))
                || !Boolean.TRUE.equals(zone.get(DnsZoneModel.ENABLED))) {
            return false;
        }
        ZoneState zs = state.computeIfAbsent(zoneId, k -> new ZoneState());
        String originString = zone.get(DnsZoneModel.ORIGIN);

        Row peer = peerFor(zone);
        if (peer == null) {
            markError(zone, zs, "no primary peer configured");
            return false;
        }
        String host = peer.get(DnsPeerModel.TRANSFER_HOST);
        if (host == null || host.isBlank()) {
            markError(zone, zs, "peer has no transfer host");
            return false;
        }
        int port = valueOr(peer.get(DnsPeerModel.TRANSFER_PORT), 53);
        TSIG tsig = DnsTsig.forPeer(peer);

        try {
            Name origin = Name.fromString(originString + ".");
            long localSerial = valueOr(zone.get(DnsZoneModel.SERIAL), 0);

            if (!force) {
                Long remoteSerial = DnsSoaProbe.serial(host.trim(), port, origin);
                stampChecked(zone);
                if (remoteSerial != null && serialNotNewer(remoteSerial, localSerial)
                        && DnsZoneModel.TRANSFER_OK.equals(zone.get(DnsZoneModel.TRANSFER_STATUS))) {
                    scheduleRefresh(zone, zs);
                    return true; // already current
                }
            }

            ZoneTransferIn xfr = ZoneTransferIn.newAXFR(origin, host.trim(), port, tsig);
            xfr.setTimeout(Duration.ofSeconds(30));
            xfr.run();
            List<Record> records = xfr.getAXFR();

            DnsZoneSnapshot snapshot = DnsZoneStore.snapshotFromTransfer(zoneId, originString, records);
            store.putSecondarySnapshot(snapshot);

            zs.lastSuccessEpochMs = System.currentTimeMillis();
            applySoa(zone, snapshot.getSoa());
            zone.set(DnsZoneModel.TRANSFER_STATUS, DnsZoneModel.TRANSFER_OK);
            zone.set(DnsZoneModel.TRANSFER_MESSAGE, null);
            zone.set(DnsZoneModel.LAST_TRANSFER_AT, Instant.now());
            zone.set(DnsZoneModel.LAST_CHECKED_AT, Instant.now());
            zone.set(DnsZoneModel.REPLICA_RECORDS, serializeReplica(records));
            Models.get(DnsZoneModel.class).save(zone);
            scheduleRefresh(zone, zs);

            Blast.log("DNS: transferred secondary zone", originString, "serial", snapshot.getSerial());
            Blast.slog("dns.secondary_transfer", Map.of(
                "zone", originString, "serial", snapshot.getSerial()));
            return true;
        }
        catch (Exception e) {
            expireOrRetry(zone, zs, e.getMessage());
            return false;
        }
    }

    private void expireOrRetry(@NonNull Row zone, @NonNull ZoneState zs, @Nullable String message) {
        int expire = valueOr(zone.get(DnsZoneModel.SOA_EXPIRE), 1209600);

        // The expire clock runs from the last SUCCESSFUL transfer, surviving
        // restarts via the persisted timestamp. A zone that never transferred
        // has nothing stale to stop serving, so it just retries as an error.
        long lastSuccess = zs.lastSuccessEpochMs;
        if (lastSuccess == 0) {
            Instant persisted = zone.get(DnsZoneModel.LAST_TRANSFER_AT);
            lastSuccess = persisted != null ? persisted.toEpochMilli() : 0;
        }

        if (lastSuccess != 0 && System.currentTimeMillis() - lastSuccess > expire * 1000L) {
            // Past the expire window: stop serving stale data (keep retrying).
            store.removeSecondarySnapshot(zone.get(DnsZoneModel.ORIGIN));
            zone.set(DnsZoneModel.TRANSFER_STATUS, DnsZoneModel.TRANSFER_EXPIRED);
            Blast.log("DNS: secondary zone", zone.get(DnsZoneModel.ORIGIN), "expired -", message);
        }
        else {
            zone.set(DnsZoneModel.TRANSFER_STATUS, DnsZoneModel.TRANSFER_ERROR);
            Blast.log("DNS: secondary transfer of", zone.get(DnsZoneModel.ORIGIN), "failed -", message);
        }
        zone.set(DnsZoneModel.TRANSFER_MESSAGE, truncate(message));
        zone.set(DnsZoneModel.LAST_CHECKED_AT, Instant.now());
        Models.get(DnsZoneModel.class).save(zone);

        int retry = valueOr(zone.get(DnsZoneModel.SOA_RETRY), 3600);
        zs.nextAttemptEpochMs = System.currentTimeMillis() + retry * 1000L;
    }

    private void markError(@NonNull Row zone, @NonNull ZoneState zs, @NonNull String message) {
        zone.set(DnsZoneModel.TRANSFER_STATUS, DnsZoneModel.TRANSFER_ERROR);
        zone.set(DnsZoneModel.TRANSFER_MESSAGE, truncate(message));
        zone.set(DnsZoneModel.LAST_CHECKED_AT, Instant.now());
        Models.get(DnsZoneModel.class).save(zone);
        zs.nextAttemptEpochMs = System.currentTimeMillis() + 300_000L;
    }

    private static void scheduleRefresh(@NonNull Row zone, @NonNull ZoneState zs) {
        int refresh = valueOr(zone.get(DnsZoneModel.SOA_REFRESH), 7200);
        zs.nextAttemptEpochMs = System.currentTimeMillis() + refresh * 1000L;
    }

    private static void stampChecked(@NonNull Row zone) {
        zone.set(DnsZoneModel.LAST_CHECKED_AT, Instant.now());
        Models.get(DnsZoneModel.class).save(zone);
    }

    private static void applySoa(@NonNull Row zone, @NonNull SOARecord soa) {
        zone.set(DnsZoneModel.SERIAL, (int) soa.getSerial());
        zone.set(DnsZoneModel.SOA_PRIMARY_NS, soa.getHost().toString(true));
        zone.set(DnsZoneModel.SOA_CONTACT, adminToEmail(soa.getAdmin()));
        zone.set(DnsZoneModel.SOA_REFRESH, (int) soa.getRefresh());
        zone.set(DnsZoneModel.SOA_RETRY, (int) soa.getRetry());
        zone.set(DnsZoneModel.SOA_EXPIRE, (int) soa.getExpire());
        zone.set(DnsZoneModel.NEGATIVE_TTL, (int) soa.getMinimum());
    }

    /** SOA RNAME back to an email (first unescaped dot becomes the @). */
    private static @NonNull String adminToEmail(@NonNull Name admin) {
        String rname = admin.toString(true);
        StringBuilder local = new StringBuilder();
        int i = 0;
        while (i < rname.length()) {
            char c = rname.charAt(i);
            if (c == '\\' && i + 1 < rname.length()) {
                local.append(rname.charAt(i + 1));
                i += 2;
                continue;
            }
            if (c == '.') {
                return local + "@" + rname.substring(i + 1);
            }
            local.append(c);
            i++;
        }
        return rname;
    }

    /** Master-file text of the transferred records (absolute names, so no $ORIGIN needed). */
    private static @NonNull String serializeReplica(@NonNull List<Record> records) {
        StringBuilder text = new StringBuilder(records.size() * 48);
        for (Record record : records) {
            text.append(record.toString()).append('\n');
        }
        return text.toString();
    }

    private static @NonNull List<Record> parseReplica(@NonNull String originString,
                                                      @NonNull String text) throws Exception {
        Name origin = Name.fromString(originString + ".");
        List<Record> records = new ArrayList<>();
        try (Master master = new Master(
                new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)), origin, 3600)) {
            master.disableIncludes();
            Record record;
            while ((record = master.nextRecord()) != null) {
                records.add(record);
            }
        }
        return records;
    }

    private static @Nullable Row peerFor(@NonNull Row zone) {
        Integer peerId = zone.get(DnsZoneModel.PRIMARY_PEER_ID);
        return peerId != null ? Models.get(DnsPeerModel.class).findById(peerId) : null;
    }

    /** RFC 1982 serial arithmetic: true when remote is not strictly newer than local. */
    private static boolean serialNotNewer(long remote, long local) {
        long diff = (remote - local) & 0xFFFFFFFFL;
        return diff == 0 || diff > 0x7FFFFFFFL;
    }

    private static @Nullable String truncate(@Nullable String message) {
        if (message == null) {
            return null;
        }
        return message.length() > 480 ? message.substring(0, 480) : message;
    }

    private static int valueOr(@Nullable Integer value, int fallback) {
        return value != null ? value : fallback;
    }
}
