package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.model.DnsPeerModel;
import be.elevenways.hohenheim.model.DnsRecordModel;
import be.elevenways.hohenheim.model.DnsZoneModel;
import be.elevenways.hohenheim.server.dns.DnsPeerApi;
import be.elevenways.hohenheim.server.dns.DnsZoneSnapshot;
import be.elevenways.hohenheim.server.dns.DnsZoneStore;
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
    @Override public @NonNull Microcopy label() { return Microcopy.of("records").withFilter("scope", "dns_zone"); }
    @Override public @NonNull String slug() { return "records"; }
    @Override public @NonNull Icon icon() { return Icon.of("list-ul"); }

    @Override
    public @NonNull ActionResult<?> render(@NonNull Conduit conduit,
                                           @NonNull AccessContext accessContext,
                                           @NonNull Row zone) {
        if (DnsZoneModel.ROLE_SECONDARY.equals(DnsZoneModel.roleOf(zone))) {
            return renderRemote(conduit, zone);
        }
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

    /**
     * A SECONDARY zone's records live on its owning peer: the tab reads the
     * owner's live records through the peer API and forwards edits to it.
     * When the peer is unconfigured or unreachable, the replica snapshot is
     * shown read-only instead (DNS keeps serving; only editing needs the owner).
     */
    private @NonNull ActionResult<?> renderRemote(@NonNull Conduit conduit, @NonNull Row zone) {
        Integer zoneId = zone.get(DnsZoneModel.ID);
        String origin = zone.get(DnsZoneModel.ORIGIN);

        Integer peerId = zone.get(DnsZoneModel.PRIMARY_PEER_ID);
        Row peer = peerId != null ? Models.get(DnsPeerModel.class).findById(peerId) : null;
        DnsPeerApi api = DnsPeerApi.forPeer(peer);

        List<Map<String, Object>> records = new ArrayList<>();
        boolean editable = false;
        String notice = "";
        Map<String, Object> editRecord = null;
        String requestedRecord = conduit.getQueryParam("record");

        if (api != null) {
            try {
                for (Map<String, Object> remote : api.listRecords(origin)) {
                    Map<String, Object> entry = new HashMap<>();
                    String id = stringOf(remote.get("id"));
                    entry.put("id", id);
                    entry.put("name", stringOf(remote.get("name")));
                    entry.put("type", stringOf(remote.get("type")));
                    entry.put("ttl", stringOf(remote.get("ttl")));
                    entry.put("value", remoteDisplayValue(remote));
                    entry.put("enabled", Boolean.TRUE.equals(remote.get("enabled")));
                    entry.put("managed", remote.get("managed_by") != null);
                    records.add(entry);
                    if (id.equals(requestedRecord)) {
                        editRecord = rawFields(remote);
                    }
                }
                editable = true;
            }
            catch (RuntimeException e) {
                notice = Microcopy.of("peer_unreachable").withFilter("scope", "dns_remote")
                    .withArg("message", String.valueOf(e.getMessage()))
                    .resolve(conduit.getLocales(), conduit.getMessageResolver());
            }
        }
        else {
            notice = Microcopy.of("peer_not_configured").withFilter("scope", "dns_remote")
                .resolve(conduit.getLocales(), conduit.getMessageResolver());
        }

        if (!editable) {
            replicaRecords(origin, records);
        }
        if ("new".equals(requestedRecord) && editable) {
            editRecord = new HashMap<>(Map.of("id", "", "name", "", "type", "A", "ttl", "",
                "value", "", "priority", "", "weight", "", "port", "", "enabled", "true"));
        }

        Map<String, Object> vars = new HashMap<>();
        vars.put("title", origin + " - Records");
        vars.put("zoneId", zoneId);
        vars.put("origin", origin);
        vars.put("peerName", peer != null ? String.valueOf(peer.get(DnsPeerModel.NAME)) : "");
        vars.put("records", records);
        vars.put("editable", editable);
        vars.put("notice", notice);
        vars.put("editRecord", editRecord);
        vars.put("recordTypes", DnsRecordModel.ALL_TYPES);
        String error = conduit.getQueryParam("error");
        vars.put("error", error != null ? error : "");
        vars.put("saved", conduit.getQueryParam("saved") != null);
        vars.put("recordTabs", recordTabs(conduit));
        return new RenderTemplateResult(Identifier.of("hohenheim", "cms/dns-zone-remote-records"), vars);
    }

    /** Read-only listing from the replica snapshot when the owner cannot be reached. */
    private static void replicaRecords(@NonNull String origin, @NonNull List<Map<String, Object>> records) {
        DnsZoneSnapshot snapshot = DnsZoneStore.INSTANCE.getZone(origin);
        if (snapshot == null) {
            return;
        }
        for (org.xbill.DNS.Record record : snapshot.allRecordsExceptSoa()) {
            Map<String, Object> entry = new HashMap<>();
            entry.put("id", "");
            entry.put("name", record.getName().relativize(snapshot.getOrigin()).toString(true));
            entry.put("type", org.xbill.DNS.Type.string(record.getType()));
            entry.put("ttl", String.valueOf(record.getTTL()));
            entry.put("value", record.rdataToString());
            entry.put("enabled", true);
            entry.put("managed", false);
            records.add(entry);
        }
        records.sort(java.util.Comparator.comparing(entry -> String.valueOf(entry.get("name"))));
    }

    /** The raw remote fields for the edit form (everything as form-friendly strings). */
    private static @NonNull Map<String, Object> rawFields(@NonNull Map<String, Object> remote) {
        Map<String, Object> fields = new HashMap<>();
        fields.put("id", stringOf(remote.get("id")));
        fields.put("name", stringOf(remote.get("name")));
        fields.put("type", stringOf(remote.get("type")));
        fields.put("ttl", stringOf(remote.get("ttl")));
        fields.put("value", stringOf(remote.get("value")));
        fields.put("priority", stringOf(remote.get("priority")));
        fields.put("weight", stringOf(remote.get("weight")));
        fields.put("port", stringOf(remote.get("port")));
        fields.put("enabled", Boolean.TRUE.equals(remote.get("enabled")) ? "true" : "false");
        return fields;
    }

    private static @NonNull String remoteDisplayValue(@NonNull Map<String, Object> remote) {
        String value = stringOf(remote.get("value"));
        String type = stringOf(remote.get("type"));
        if (DnsRecordModel.TYPE_MX.equals(type) && remote.get("priority") != null) {
            return stringOf(remote.get("priority")) + " " + value;
        }
        if (DnsRecordModel.TYPE_SRV.equals(type)) {
            return zeroIfBlank(remote.get("priority")) + " " + zeroIfBlank(remote.get("weight"))
                + " " + zeroIfBlank(remote.get("port")) + " " + value;
        }
        return value;
    }

    private static @NonNull String stringOf(@Nullable Object value) {
        return value != null ? String.valueOf(value) : "";
    }

    private static @NonNull String zeroIfBlank(@Nullable Object value) {
        return value != null ? String.valueOf(value) : "0";
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
