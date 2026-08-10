package be.elevenways.hohenheim.server.dns;

import be.elevenways.hohenheim.model.DnsRecordModel;
import be.elevenways.hohenheim.model.DnsZoneModel;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.orm.query.SortOrder;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.xbill.DNS.AAAARecord;
import org.xbill.DNS.ARecord;
import org.xbill.DNS.CAARecord;
import org.xbill.DNS.CNAMERecord;
import org.xbill.DNS.DClass;
import org.xbill.DNS.MXRecord;
import org.xbill.DNS.Master;
import org.xbill.DNS.NSRecord;
import org.xbill.DNS.Name;
import org.xbill.DNS.Record;
import org.xbill.DNS.SRVRecord;
import org.xbill.DNS.TXTRecord;
import org.xbill.DNS.TextParseException;
import org.xbill.DNS.Type;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Standard zone-file text for a hosted zone. Import REPLACES all
 * operator-managed rows (ACME-managed rows survive); $INCLUDE is disabled.
 */
public final class DnsZoneFiles {

    /** @param imported rows written @param skipped human-readable notes about ignored lines */
    public record ImportResult(int imported, List<String> skipped) {}

    private DnsZoneFiles() {}

    /** @return the zone as master-file text; disabled and unconvertible rows appear as comments */
    public static @NonNull String export(@NonNull Row zone) {
        StringBuilder text = new StringBuilder();
        String origin = zone.get(DnsZoneModel.ORIGIN);
        Integer defaultTtl = zone.get(DnsZoneModel.DEFAULT_TTL);
        int zoneTtl = defaultTtl != null ? defaultTtl : 3600;

        text.append("$ORIGIN ").append(origin).append(".\n");
        text.append("$TTL ").append(zoneTtl).append("\n");

        Name originName;
        try {
            originName = Name.fromString(origin + ".");
        }
        catch (TextParseException e) {
            return "; invalid zone origin: " + origin + "\n";
        }

        Integer zoneId = zone.get(DnsZoneModel.ID);
        DnsZoneSnapshot snapshot = DnsZoneStore.INSTANCE.getZone(origin);
        if (snapshot != null && zoneId != null && snapshot.getZoneId() == zoneId) {
            text.append(snapshot.getSoa()).append("\n");
        }

        List<Row> rows = Models.get(DnsRecordModel.class).find()
            .where(DnsRecordModel.ZONE_ID.eq(zoneId))
            .orderBy(DnsRecordModel.NAME, SortOrder.ASC)
            .all();

        for (Row row : rows) {
            String line = rowToLine(originName, zoneTtl, row);
            boolean enabled = Boolean.TRUE.equals(row.get(DnsRecordModel.ENABLED));
            if (!enabled) {
                text.append("; disabled: ");
            }
            text.append(line).append("\n");
        }

        return text.toString();
    }

    /** Replaces every operator-managed record with the parsed file contents. */
    public static @NonNull ImportResult importText(@NonNull Row zone, @NonNull String text) throws IOException {
        String origin = zone.get(DnsZoneModel.ORIGIN);
        Integer defaultTtl = zone.get(DnsZoneModel.DEFAULT_TTL);
        long zoneTtl = defaultTtl != null ? defaultTtl : 3600;
        int zoneId = zone.get(DnsZoneModel.ID);

        Name originName;
        try {
            originName = Name.fromString(origin + ".");
        }
        catch (TextParseException e) {
            throw new IOException("Invalid zone origin: " + origin);
        }

        List<Row> parsed = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        DnsRecordModel model = Models.get(DnsRecordModel.class);

        try (Master master = new Master(
                new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)), originName, zoneTtl)) {
            master.disableIncludes();
            Record record;
            while ((record = master.nextRecord()) != null) {
                if (record.getType() == Type.SOA) {
                    // Zone metadata lives on the zone row; the serial is framework-managed.
                    continue;
                }
                Row row = recordToRow(model, zoneId, originName, record, skipped);
                if (row != null) {
                    parsed.add(row);
                }
            }
        }

        model.find()
            .where(DnsRecordModel.ZONE_ID.eq(zoneId))
            .where(DnsRecordModel.MANAGED_BY.isNull())
            .delete();

        for (Row row : parsed) {
            model.save(row);
        }

        DnsZoneStore.INSTANCE.bumpSerialAndReload(zoneId);
        return new ImportResult(parsed.size(), skipped);
    }

    private static @NonNull String rowToLine(@NonNull Name originName, int zoneTtl, @NonNull Row row) {
        String owner = row.get(DnsRecordModel.NAME);
        String type = row.get(DnsRecordModel.TYPE);
        String value = row.get(DnsRecordModel.VALUE);
        try {
            long ttl = DnsRecordCodec.resolveTtl(row.get(DnsRecordModel.TTL), zoneTtl);
            Record record = DnsRecordCodec.toRecord(originName,
                owner != null ? owner : DnsNames.APEX,
                type != null ? type : "",
                ttl,
                value != null ? value : "",
                DnsRecordModel.priorityOf(row),
                DnsRecordModel.weightOf(row),
                DnsRecordModel.portOf(row));
            return record.toString();
        }
        catch (DnsValueException e) {
            return "; invalid: " + owner + " " + type + " " + value + " (" + e.getMicrocopyKey() + ")";
        }
    }

    private static @Nullable Row recordToRow(@NonNull DnsRecordModel model, int zoneId,
                                             @NonNull Name originName, @NonNull Record record,
                                             @NonNull List<String> skipped) {
        String owner = relativeOwner(originName, record.getName());
        if (owner == null) {
            skipped.add(record.getName() + " (outside the zone)");
            return null;
        }

        String type;
        String value;
        Integer priority = null;
        Integer weight = null;
        Integer port = null;

        switch (record.getType()) {
            case Type.A -> {
                type = DnsRecordModel.TYPE_A;
                value = ((ARecord) record).getAddress().getHostAddress();
            }
            case Type.AAAA -> {
                type = DnsRecordModel.TYPE_AAAA;
                value = ((AAAARecord) record).getAddress().getHostAddress();
            }
            case Type.CNAME -> {
                type = DnsRecordModel.TYPE_CNAME;
                value = stripDot(((CNAMERecord) record).getTarget());
            }
            case Type.NS -> {
                type = DnsRecordModel.TYPE_NS;
                value = stripDot(((NSRecord) record).getTarget());
            }
            case Type.MX -> {
                type = DnsRecordModel.TYPE_MX;
                MXRecord mx = (MXRecord) record;
                value = stripDot(mx.getTarget());
                priority = mx.getPriority();
            }
            case Type.TXT -> {
                type = DnsRecordModel.TYPE_TXT;
                value = String.join("", ((TXTRecord) record).getStrings());
            }
            case Type.CAA -> {
                type = DnsRecordModel.TYPE_CAA;
                CAARecord caa = (CAARecord) record;
                value = caa.getFlags() + " " + caa.getTag() + " " + caa.getValue();
            }
            case Type.SRV -> {
                type = DnsRecordModel.TYPE_SRV;
                SRVRecord srv = (SRVRecord) record;
                value = stripDot(srv.getTarget());
                priority = srv.getPriority();
                weight = srv.getWeight();
                port = srv.getPort();
            }
            default -> {
                skipped.add(record.getName() + " " + Type.string(record.getType())
                    + " (unsupported type)");
                return null;
            }
        }

        if (record.getDClass() != DClass.IN) {
            skipped.add(record.getName() + " (unsupported class)");
            return null;
        }

        Row row = model.createEmptyRow();
        row.set(DnsRecordModel.ZONE_ID, zoneId);
        row.set(DnsRecordModel.NAME, owner);
        row.set(DnsRecordModel.TYPE, type);
        row.set(DnsRecordModel.TTL, (int) record.getTTL());
        row.set(DnsRecordModel.VALUE, value);
        row.set(DnsRecordModel.DATA, DnsRecordModel.dataFor(type, priority, weight, port));
        row.set(DnsRecordModel.ENABLED, true);
        return row;
    }

    private static @Nullable String relativeOwner(@NonNull Name origin, @NonNull Name owner) {
        if (!owner.subdomain(origin)) {
            return null;
        }
        if (owner.equals(origin)) {
            return DnsNames.APEX;
        }
        String relative = owner.relativize(origin).toString().toLowerCase(Locale.ROOT);
        return DnsNames.normalizeOwner(relative);
    }

    private static @NonNull String stripDot(@NonNull Name name) {
        String value = name.toString().toLowerCase(Locale.ROOT);
        while (value.endsWith(".")) {
            value = value.substring(0, value.length() - 1);
        }
        return value;
    }
}
