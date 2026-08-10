package be.elevenways.hohenheim.server.dns;

import be.elevenways.hohenheim.model.DnsRecordModel;
import be.elevenways.hohenheim.model.DnsZoneModel;
import be.elevenways.protoblast.common.Blast;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.xbill.DNS.DClass;
import org.xbill.DNS.Name;
import org.xbill.DNS.Record;
import org.xbill.DNS.SOARecord;
import org.xbill.DNS.TextParseException;
import org.xbill.DNS.Type;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.IntConsumer;

/**
 * Builds immutable zone snapshots and swaps the serving view atomically. Primary
 * zones are rebuilt from the database; secondary zones are supplied as compiled
 * snapshots by the transfer layer. The serving view is the merge of both, so a
 * primary edit or an incoming AXFR each just rebuild their half.
 */
public final class DnsZoneStore {

    public static final DnsZoneStore INSTANCE = new DnsZoneStore();

    private volatile Map<String, DnsZoneSnapshot> primaryByOrigin = Map.of();
    private final Map<String, DnsZoneSnapshot> secondaryByOrigin = new ConcurrentHashMap<>();
    private volatile Map<String, DnsZoneSnapshot> serving = Map.of();
    private volatile @Nullable IntConsumer onZoneChanged;

    private DnsZoneStore() {}

    /** A detached store (not the serving singleton) for tests that stand up a second nameserver. */
    public static @NonNull DnsZoneStore createDetached() {
        return new DnsZoneStore();
    }

    /** For tests: install a primary snapshot directly, bypassing the database. */
    public synchronized void injectPrimarySnapshot(@NonNull DnsZoneSnapshot snapshot) {
        Map<String, DnsZoneSnapshot> next = new HashMap<>(this.primaryByOrigin);
        next.put(snapshot.getOriginString(), snapshot);
        this.primaryByOrigin = Map.copyOf(next);
        rebuildServing();
    }

    /**
     * Registers a callback fired (with the zone id) after a primary zone's serial
     * bumps, so the transfer layer can NOTIFY that zone's secondaries.
     */
    public void setOnZoneChanged(@Nullable IntConsumer onZoneChanged) {
        this.onZoneChanged = onZoneChanged;
    }

    /**
     * Rebuilds every enabled PRIMARY zone from the database and re-merges the
     * serving view. Also prunes secondary snapshots whose zone row was deleted,
     * disabled, or flipped to primary, so those stop being answered immediately.
     */
    public synchronized void reload() {
        Map<String, DnsZoneSnapshot> rebuilt = new HashMap<>();
        java.util.Set<String> activeSecondaryOrigins = new java.util.HashSet<>();
        DnsZoneModel zoneModel = Models.get(DnsZoneModel.class);
        DnsRecordModel recordModel = Models.get(DnsRecordModel.class);

        for (Row zone : zoneModel.findEnabled()) {
            if (DnsZoneModel.ROLE_SECONDARY.equals(DnsZoneModel.roleOf(zone))) {
                String origin = zone.get(DnsZoneModel.ORIGIN);
                if (origin != null) {
                    activeSecondaryOrigins.add(origin);
                }
                continue; // secondary content is supplied by the transfer layer
            }
            try {
                DnsZoneSnapshot snapshot = buildSnapshot(zone, recordModel);
                rebuilt.put(snapshot.getOriginString(), snapshot);
            }
            catch (Exception e) {
                Blast.log("DNS: skipping zone", zone.get(DnsZoneModel.ORIGIN), "-", e.getMessage());
            }
        }

        this.primaryByOrigin = Map.copyOf(rebuilt);
        this.secondaryByOrigin.keySet().retainAll(activeSecondaryOrigins);
        rebuildServing();
    }

    /** Atomically bumps the zone's SOA serial, rebuilds, and notifies its secondaries. */
    public void bumpSerialAndReload(int zoneId) {
        Models.get(DnsZoneModel.class).find()
            .where(DnsZoneModel.ID.eq(zoneId))
            .increment(DnsZoneModel.SERIAL)
            .updateAll();
        this.reload();
        IntConsumer hook = this.onZoneChanged;
        if (hook != null) {
            hook.accept(zoneId);
        }
    }

    /** Installs (or replaces) a secondary zone's compiled snapshot in the serving view. */
    public synchronized void putSecondarySnapshot(@NonNull DnsZoneSnapshot snapshot) {
        this.secondaryByOrigin.put(snapshot.getOriginString(), snapshot);
        rebuildServing();
    }

    /** Removes a secondary zone from the serving view (expired or deleted). */
    public synchronized void removeSecondarySnapshot(@NonNull String origin) {
        if (this.secondaryByOrigin.remove(origin) != null) {
            rebuildServing();
        }
    }

    private void rebuildServing() {
        Map<String, DnsZoneSnapshot> merged = new HashMap<>(this.primaryByOrigin);
        merged.putAll(this.secondaryByOrigin);
        this.serving = Map.copyOf(merged);
    }

    public @NonNull Collection<DnsZoneSnapshot> zones() {
        return this.serving.values();
    }

    public @Nullable DnsZoneSnapshot getZone(@NonNull String origin) {
        return this.serving.get(origin);
    }

    /** @return the most specific enabled zone containing the name, or null */
    public @Nullable DnsZoneSnapshot findZoneFor(@NonNull Name qname) {
        DnsZoneSnapshot best = null;
        for (DnsZoneSnapshot zone : this.serving.values()) {
            if (!qname.subdomain(zone.getOrigin())) {
                continue;
            }
            if (best == null || zone.getOrigin().labels() > best.getOrigin().labels()) {
                best = zone;
            }
        }
        return best;
    }

    /** @return the most specific enabled zone containing the fqdn (no trailing dot needed), or null */
    public @Nullable DnsZoneSnapshot findZoneFor(@NonNull String fqdn) {
        String best = null;
        for (DnsZoneSnapshot zone : this.serving.values()) {
            String origin = zone.getOriginString();
            if (DnsNames.zoneContains(origin, fqdn) && (best == null || origin.length() > best.length())) {
                best = origin;
            }
        }
        return best != null ? this.serving.get(best) : null;
    }

    private static @NonNull DnsZoneSnapshot buildSnapshot(@NonNull Row zone, @NonNull DnsRecordModel recordModel)
            throws Exception {

        int zoneId = zone.get(DnsZoneModel.ID);
        String originString = zone.get(DnsZoneModel.ORIGIN);
        Name origin = Name.fromString(originString + ".");

        int serial = valueOr(zone.get(DnsZoneModel.SERIAL), 1);
        int defaultTtl = valueOr(zone.get(DnsZoneModel.DEFAULT_TTL), 3600);
        int negativeTtl = valueOr(zone.get(DnsZoneModel.NEGATIVE_TTL), 300);
        int refresh = valueOr(zone.get(DnsZoneModel.SOA_REFRESH), 7200);
        int retry = valueOr(zone.get(DnsZoneModel.SOA_RETRY), 3600);
        int expire = valueOr(zone.get(DnsZoneModel.SOA_EXPIRE), 1209600);

        Name primaryNs = absoluteName(zone.get(DnsZoneModel.SOA_PRIMARY_NS), origin);
        Name contact = contactName(zone.get(DnsZoneModel.SOA_CONTACT), originString);

        SOARecord soa = new SOARecord(origin, DClass.IN, defaultTtl,
            primaryNs, contact, serial, refresh, retry, expire, negativeTtl);

        Map<Name, Map<Integer, List<Record>>> nodes = new HashMap<>();
        addRecord(nodes, origin, soa, origin);

        for (Row row : recordModel.findEnabledByZoneId(zoneId)) {
            try {
                String owner = row.get(DnsRecordModel.NAME);
                String type = row.get(DnsRecordModel.TYPE);
                long ttl = DnsRecordCodec.resolveTtl(row.get(DnsRecordModel.TTL), defaultTtl);
                Record record = DnsRecordCodec.toRecord(origin, owner, type, ttl,
                    valueOr(row.get(DnsRecordModel.VALUE), ""),
                    row.get(DnsRecordModel.PRIORITY),
                    row.get(DnsRecordModel.WEIGHT),
                    row.get(DnsRecordModel.PORT));
                addRecord(nodes, record.getName(), record, origin);
            }
            catch (Exception e) {
                // AIDEV-NOTE: deliberately Exception, not just DnsValueException, as
                // defense-in-depth: this loop turns a per-record failure into a one-row
                // skip, but an uncaught throwable escaping it aborts buildSnapshot, which
                // reload() answers by SKIPPING THE WHOLE ZONE -- one bad row would take
                // every other name dark. The codec today wraps its TextParseExceptions into
                // DnsValueException (DnsRecordCodec.ownerName), so no non-DnsValueException
                // is reachable through it right now; this widening guards the day a codec
                // path or a dnsjava record constructor throws an unchecked one. One bad row
                // loses one row, never a zone. (Zone-level parse failures -- the SOA NS and
                // contact -- stay outside this loop and legitimately skip the zone.)
                Blast.log("DNS: skipping record", row.get(DnsRecordModel.ID), "in zone", originString,
                    "-", e.getMessage());
            }
        }

        // DNSSEC: sign in place before freezing (primary zones only; a secondary
        // serves its primary's already-signed records verbatim).
        if (Boolean.TRUE.equals(zone.get(DnsZoneModel.DNSSEC_ENABLED))) {
            DnsSecKeys keys = DnsSecMaterial.ensure(zone);
            if (keys != null) {
                DnsSecSigner.sign(origin, defaultTtl, negativeTtl, nodes, keys, java.time.Instant.now());
            }
        }

        return freeze(zoneId, originString, origin, serial, soa, negativeTtl, nodes);
    }

    /**
     * Compiles a secondary zone snapshot directly from transferred records; the
     * apex SOA in the list supplies the zone's authority data and serial.
     *
     * @throws IllegalArgumentException when the record set has no apex SOA
     */
    public static @NonNull DnsZoneSnapshot snapshotFromTransfer(int zoneId,
                                                                @NonNull String originString,
                                                                @NonNull List<Record> records) throws Exception {
        Name origin = Name.fromString(originString + ".");
        SOARecord soa = null;
        for (Record record : records) {
            if (record.getType() == Type.SOA && record.getName().equals(origin)) {
                soa = (SOARecord) record;
                break;
            }
        }
        if (soa == null) {
            throw new IllegalArgumentException("Transferred zone " + originString + " has no apex SOA");
        }

        Map<Name, Map<Integer, List<Record>>> nodes = new HashMap<>();
        addRecord(nodes, origin, soa, origin);
        for (Record record : records) {
            if (record.getType() == Type.SOA) {
                continue; // the apex SOA is added once above; AXFR brackets with two SOAs
            }
            if (!record.getName().subdomain(origin)) {
                continue; // out-of-zone glue: ignore
            }
            addRecord(nodes, record.getName(), record, origin);
        }

        return freeze(zoneId, originString, origin, soa.getSerial(), soa, (int) soa.getMinimum(), nodes);
    }

    private static @NonNull DnsZoneSnapshot freeze(int zoneId, @NonNull String originString, @NonNull Name origin,
                                                   long serial, @NonNull SOARecord soa, int negativeTtl,
                                                   @NonNull Map<Name, Map<Integer, List<Record>>> nodes) {
        SOARecord negativeSoa = new SOARecord(origin, DClass.IN, negativeTtl,
            soa.getHost(), soa.getAdmin(), soa.getSerial(), soa.getRefresh(), soa.getRetry(),
            soa.getExpire(), soa.getMinimum());

        Map<Name, Map<Integer, List<Record>>> frozen = new HashMap<>();
        for (Map.Entry<Name, Map<Integer, List<Record>>> node : nodes.entrySet()) {
            Map<Integer, List<Record>> types = new HashMap<>();
            for (Map.Entry<Integer, List<Record>> rrset : node.getValue().entrySet()) {
                types.put(rrset.getKey(), List.copyOf(rrset.getValue()));
            }
            frozen.put(node.getKey(), Map.copyOf(types));
        }
        return new DnsZoneSnapshot(zoneId, originString, origin, serial, soa, negativeSoa, Map.copyOf(frozen));
    }

    /** Registers the record and materializes empty non-terminal nodes up to the apex. */
    private static void addRecord(@NonNull Map<Name, Map<Integer, List<Record>>> nodes,
                                  @NonNull Name owner,
                                  @NonNull Record record,
                                  @NonNull Name origin) {
        nodes.computeIfAbsent(owner, k -> new HashMap<>())
            .computeIfAbsent(record.getType(), k -> new ArrayList<>())
            .add(record);

        Name current = DnsZoneSnapshot.parent(owner);
        while (current.labels() > origin.labels() && current.subdomain(origin)) {
            nodes.computeIfAbsent(current, k -> new HashMap<>());
            current = DnsZoneSnapshot.parent(current);
        }
    }

    private static @NonNull Name absoluteName(@Nullable String value, @NonNull Name origin) throws TextParseException {
        if (value == null || value.isBlank()) {
            return new Name("ns1", origin);
        }
        String name = value.trim().toLowerCase(Locale.ROOT);
        while (name.endsWith(".")) {
            name = name.substring(0, name.length() - 1);
        }
        return Name.fromString(name + ".");
    }

    /** Turns an email contact into SOA RNAME form (dots in the local part escaped). */
    private static @NonNull Name contactName(@Nullable String value, @NonNull String origin) throws TextParseException {
        String contact = value != null ? value.trim().toLowerCase(Locale.ROOT) : "";
        if (contact.isEmpty()) {
            contact = "hostmaster@" + origin;
        }
        int at = contact.indexOf('@');
        if (at > 0) {
            String local = contact.substring(0, at).replace(".", "\\.");
            contact = local + "." + contact.substring(at + 1);
        }
        while (contact.endsWith(".")) {
            contact = contact.substring(0, contact.length() - 1);
        }
        return Name.fromString(contact + ".");
    }

    private static int valueOr(@Nullable Integer value, int fallback) {
        return value != null ? value : fallback;
    }

    private static @NonNull String valueOr(@Nullable String value, @NonNull String fallback) {
        return value != null ? value : fallback;
    }
}
