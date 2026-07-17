package be.elevenways.hohenheim.server.dns;

import be.elevenways.hohenheim.model.CertificateModel;
import be.elevenways.hohenheim.model.DnsRecordModel;
import be.elevenways.hohenheim.server.tls.DnsTxtPublisher;
import be.elevenways.hohenheim.server.tls.DnsTxtRecord;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * First-party ACME DNS-01 publisher: writes the TXT value into a hosted zone
 * and swaps the serving snapshot, so wildcard certificates renew without
 * provider credentials or a shell hook. Only the exact published value is
 * removed on cleanup, so concurrent orders for the same name coexist.
 */
public final class InternalDnsTxtPublisher implements DnsTxtPublisher {

    /** TXT values live only for the duration of one ACME order; keep caches short. */
    private static final int ACME_TXT_TTL = 60;

    private final DnsZoneStore store;

    public InternalDnsTxtPublisher() {
        this(DnsZoneStore.INSTANCE);
    }

    public InternalDnsTxtPublisher(@NonNull DnsZoneStore store) {
        this.store = store;
    }

    @Override
    public @NonNull String id() {
        return CertificateModel.DNS_PUBLISHER_INTERNAL;
    }

    /** The snapshot swap is synchronous; the record serves before publish() returns. */
    @Override
    public boolean servesImmediately() {
        return true;
    }

    /** @return true when an enabled hosted zone contains the fqdn */
    public boolean canPublishFor(@NonNull String fqdn) {
        return this.store.findZoneFor(stripDot(fqdn)) != null;
    }

    /** @return true when at least one enabled zone is hosted */
    public boolean hasZones() {
        return !this.store.zones().isEmpty();
    }

    @Override
    public void publish(@NonNull DnsTxtRecord record) throws Exception {
        DnsZoneSnapshot zone = this.requireZone(record.name());
        String owner = this.relativeOwner(zone, record.name());

        DnsRecordModel model = Models.get(DnsRecordModel.class);
        Row row = model.createEmptyRow();
        row.set(DnsRecordModel.ZONE_ID, zone.getZoneId());
        row.set(DnsRecordModel.NAME, owner);
        row.set(DnsRecordModel.TYPE, DnsRecordModel.TYPE_TXT);
        row.set(DnsRecordModel.TTL, ACME_TXT_TTL);
        row.set(DnsRecordModel.VALUE, record.value());
        row.set(DnsRecordModel.ENABLED, true);
        row.set(DnsRecordModel.MANAGED_BY, DnsRecordModel.MANAGED_BY_ACME);
        model.save(row);

        this.store.bumpSerialAndReload(zone.getZoneId());
    }

    @Override
    public void cleanup(@NonNull DnsTxtRecord record) throws Exception {
        DnsZoneSnapshot zone = this.requireZone(record.name());
        String owner = this.relativeOwner(zone, record.name());

        Models.get(DnsRecordModel.class).find()
            .where(DnsRecordModel.ZONE_ID.eq(zone.getZoneId()))
            .and(DnsRecordModel.NAME.eq(owner))
            .and(DnsRecordModel.TYPE.eq(DnsRecordModel.TYPE_TXT))
            .and(DnsRecordModel.VALUE.eq(record.value()))
            .and(DnsRecordModel.MANAGED_BY.eq(DnsRecordModel.MANAGED_BY_ACME))
            .delete();

        this.store.bumpSerialAndReload(zone.getZoneId());
    }

    private @NonNull DnsZoneSnapshot requireZone(@NonNull String recordName) {
        String fqdn = stripDot(recordName);
        DnsZoneSnapshot zone = this.store.findZoneFor(fqdn);
        if (zone == null) {
            throw new IllegalStateException("No enabled hosted DNS zone contains " + fqdn);
        }
        return zone;
    }

    private @NonNull String relativeOwner(@NonNull DnsZoneSnapshot zone, @NonNull String recordName) {
        String owner = DnsNames.relative(zone.getOriginString(), stripDot(recordName));
        if (owner == null) {
            throw new IllegalStateException("Record name " + recordName
                + " left zone " + zone.getOriginString());
        }
        return owner;
    }

    private static @NonNull String stripDot(@Nullable String name) {
        String value = name != null ? name.trim().toLowerCase() : "";
        while (value.endsWith(".")) {
            value = value.substring(0, value.length() - 1);
        }
        return value;
    }
}
