package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.model.DnsRecordModel;
import be.elevenways.hohenheim.model.DnsZoneModel;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.cms.common.resource.RecordScopedPage;
import be.elevenways.zenit.common.conduit.Conduit;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.orm.query.SortOrder;
import be.elevenways.zenit.common.result.ActionResult;
import be.elevenways.zenit.common.result.RenderTemplateResult;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.common.ui.Icon;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Records tab on a DNS zone, linking into the (nav-hidden) record resource
 * forms.
 */
public final class DnsZoneRecordsPage implements RecordScopedPage<Row> {

    @Override public @NonNull Identifier id() { return Identifier.of("hohenheim", "dns_zone_records"); }
    @Override public @NonNull Microcopy label() { return Microcopy.of("hohenheim.dns_zone.records"); }
    @Override public @NonNull String slug() { return "records"; }
    @Override public @NonNull Icon icon() { return Icon.of("list-ul"); }

    @Override
    public @NonNull ActionResult<?> render(@NonNull Conduit conduit,
                                           @NonNull AccessContext accessContext,
                                           @NonNull Row zone) {
        Integer zoneId = zone.get(DnsZoneModel.ID);
        String origin = zone.get(DnsZoneModel.ORIGIN);

        List<Map<String, Object>> records = new ArrayList<>();
        for (Row record : Models.get(DnsRecordModel.class).find()
                .where(DnsRecordModel.ZONE_ID.eq(zoneId))
                .orderBy(DnsRecordModel.NAME, SortOrder.ASC)
                .all()) {
            Map<String, Object> entry = new HashMap<>();
            entry.put("id", record.get(DnsRecordModel.ID));
            entry.put("name", record.get(DnsRecordModel.NAME));
            entry.put("type", record.get(DnsRecordModel.TYPE));
            entry.put("ttl", record.get(DnsRecordModel.TTL) != null
                ? String.valueOf(record.get(DnsRecordModel.TTL)) : "");
            entry.put("value", displayValue(record));
            entry.put("enabled", Boolean.TRUE.equals(record.get(DnsRecordModel.ENABLED)));
            entry.put("managed", record.get(DnsRecordModel.MANAGED_BY) != null);
            entry.put("editUrl", "/admin/dns-records/" + record.get(DnsRecordModel.ID));
            records.add(entry);
        }

        Map<String, Object> vars = new HashMap<>();
        vars.put("title", origin + " - Records");
        vars.put("zoneId", zoneId);
        vars.put("origin", origin);
        vars.put("records", records);
        vars.put("recordTabs", recordTabs(conduit));
        return new RenderTemplateResult(Identifier.of("hohenheim", "cms/dns-zone-records"), vars);
    }

    /** MX/SRV rows fold priority/weight/port into the display so the list reads like a zone file. */
    private static @NonNull String displayValue(@NonNull Row record) {
        String value = record.get(DnsRecordModel.VALUE);
        if (value == null) {
            value = "";
        }
        String type = record.get(DnsRecordModel.TYPE);
        Integer priority = record.get(DnsRecordModel.PRIORITY);
        if (DnsRecordModel.TYPE_MX.equals(type) && priority != null) {
            return priority + " " + value;
        }
        if (DnsRecordModel.TYPE_SRV.equals(type)) {
            Integer weight = record.get(DnsRecordModel.WEIGHT);
            Integer port = record.get(DnsRecordModel.PORT);
            return nullToZero(priority) + " " + nullToZero(weight) + " " + nullToZero(port) + " " + value;
        }
        return value;
    }

    private static int nullToZero(@Nullable Integer value) {
        return value != null ? value : 0;
    }
}
