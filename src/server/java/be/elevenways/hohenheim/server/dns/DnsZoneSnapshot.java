package be.elevenways.hohenheim.server.dns;

import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.xbill.DNS.Name;
import org.xbill.DNS.Record;
import org.xbill.DNS.SOARecord;
import org.xbill.DNS.Type;

import java.util.List;
import java.util.Map;

/**
 * Immutable compiled view of one zone; empty node maps mark empty non-terminals
 * so NODATA and NXDOMAIN stay distinguishable.
 */
public final class DnsZoneSnapshot {

    private final int zoneId;
    private final String originString;
    private final Name origin;
    private final long serial;
    private final SOARecord soa;
    private final SOARecord negativeSoa;
    private final Map<Name, Map<Integer, List<Record>>> nodes;

    DnsZoneSnapshot(int zoneId,
                    @NonNull String originString,
                    @NonNull Name origin,
                    long serial,
                    @NonNull SOARecord soa,
                    @NonNull SOARecord negativeSoa,
                    @NonNull Map<Name, Map<Integer, List<Record>>> nodes) {
        this.zoneId = zoneId;
        this.originString = originString;
        this.origin = origin;
        this.serial = serial;
        this.soa = soa;
        this.negativeSoa = negativeSoa;
        this.nodes = nodes;
    }

    public int getZoneId() {
        return this.zoneId;
    }

    public @NonNull String getOriginString() {
        return this.originString;
    }

    public @NonNull Name getOrigin() {
        return this.origin;
    }

    public long getSerial() {
        return this.serial;
    }

    public @NonNull SOARecord getSoa() {
        return this.soa;
    }

    /** SOA copy whose TTL is the zone's negative TTL, for NXDOMAIN/NODATA authority data. */
    public @NonNull SOARecord getNegativeSoa() {
        return this.negativeSoa;
    }

    public boolean contains(@NonNull Name name) {
        return name.subdomain(this.origin);
    }

    /** @return the node's RRset map, or null when the name does not exist (empty map = empty non-terminal) */
    public @Nullable Map<Integer, List<Record>> getNode(@NonNull Name name) {
        return this.nodes.get(name);
    }

    public @Nullable List<Record> getRrset(@NonNull Name name, int type) {
        Map<Integer, List<Record>> node = this.nodes.get(name);
        return node != null ? node.get(type) : null;
    }

    /**
     * Every record in the zone EXCEPT the apex SOA, for AXFR bodies (the SOA
     * brackets the transfer separately). Order is unspecified, which AXFR allows.
     */
    public @NonNull List<Record> allRecordsExceptSoa() {
        List<Record> all = new java.util.ArrayList<>();
        for (Map<Integer, List<Record>> node : this.nodes.values()) {
            for (Map.Entry<Integer, List<Record>> rrset : node.entrySet()) {
                if (rrset.getKey() == Type.SOA) {
                    continue;
                }
                all.addAll(rrset.getValue());
            }
        }
        return all;
    }

    /**
     * @return the nearest delegation point at or above the name (strictly below
     *         the apex, carrying an NS RRset), or null when none applies
     */
    public @Nullable Name findDelegation(@NonNull Name qname) {
        Name current = qname;
        while (current.subdomain(this.origin) && !current.equals(this.origin)) {
            Map<Integer, List<Record>> node = this.nodes.get(current);
            if (node != null && node.containsKey(Type.NS)) {
                return current;
            }
            current = parent(current);
        }
        return null;
    }

    /** @return the longest existing ancestor of the name (the apex at worst) */
    public @NonNull Name closestEncloser(@NonNull Name qname) {
        Name current = parent(qname);
        while (!current.equals(this.origin)) {
            if (this.nodes.containsKey(current)) {
                return current;
            }
            current = parent(current);
        }
        return this.origin;
    }

    static @NonNull Name parent(@NonNull Name name) {
        return new Name(name, 1);
    }
}
