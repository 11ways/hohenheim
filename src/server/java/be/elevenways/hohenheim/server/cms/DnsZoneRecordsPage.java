package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.dns.DnsRecordDto;
import be.elevenways.hohenheim.dns.DnsRecordFormView;
import be.elevenways.hohenheim.dns.DnsRecordView;
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
import java.util.Comparator;
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

        List<DnsRecordView> records = new ArrayList<>();
        for (Row record : Models.get(DnsRecordModel.class).find()
                .where(DnsRecordModel.ZONE_ID.eq(zoneId))
                .orderBy(DnsRecordModel.NAME, SortOrder.ASC)
                .all()) {
            Integer recordId = record.get(DnsRecordModel.ID);
            records.add(new DnsRecordView(
                text(recordId),
                text(record.get(DnsRecordModel.NAME)),
                text(record.get(DnsRecordModel.TYPE)),
                text(record.get(DnsRecordModel.TTL)),
                displayValue(record),
                Boolean.TRUE.equals(record.get(DnsRecordModel.ENABLED)),
                record.get(DnsRecordModel.MANAGED_BY) != null,
                "/admin/dns-records/" + recordId));
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

        List<DnsRecordView> records = new ArrayList<>();
        boolean editable = false;
        String notice = "";
        DnsRecordFormView editRecord = null;
        String requestedRecord = conduit.getQueryParam("record");

        if (api != null) {
            try {
                for (DnsRecordDto remote : api.listRecords(origin)) {
                    String id = text(remote.id());
                    records.add(new DnsRecordView(
                        id,
                        text(remote.name()),
                        text(remote.type()),
                        text(remote.ttl()),
                        remoteDisplayValue(remote),
                        remote.enabled(),
                        remote.managed_by() != null,
                        null));
                    if (id.equals(requestedRecord)) {
                        editRecord = formView(remote);
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
            editRecord = DnsRecordFormView.empty();
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
    private static void replicaRecords(@NonNull String origin, @NonNull List<DnsRecordView> records) {
        DnsZoneSnapshot snapshot = DnsZoneStore.INSTANCE.getZone(origin);
        if (snapshot == null) {
            return;
        }
        for (org.xbill.DNS.Record record : snapshot.allRecordsExceptSoa()) {
            records.add(new DnsRecordView(
                "",
                record.getName().relativize(snapshot.getOrigin()).toString(true),
                org.xbill.DNS.Type.string(record.getType()),
                String.valueOf(record.getTTL()),
                record.rdataToString(),
                true,
                false,
                null));
        }
        records.sort(Comparator.comparing(DnsRecordView::name));
    }

    /** @return remote fields encoded as strings for HTML form controls */
    private static @NonNull DnsRecordFormView formView(@NonNull DnsRecordDto remote) {
        return new DnsRecordFormView(
            text(remote.id()),
            text(remote.name()),
            text(remote.type()),
            text(remote.ttl()),
            text(remote.value()),
            text(remote.priority()),
            text(remote.weight()),
            text(remote.port()),
            remote.enabled() ? "true" : "false");
    }

    private static @NonNull String remoteDisplayValue(@NonNull DnsRecordDto remote) {
        String value = text(remote.value());
        if (DnsRecordModel.TYPE_MX.equals(remote.type()) && remote.priority() != null) {
            return remote.priority() + " " + value;
        }
        if (DnsRecordModel.TYPE_SRV.equals(remote.type())) {
            return zeroIfNull(remote.priority()) + " " + zeroIfNull(remote.weight())
                + " " + zeroIfNull(remote.port()) + " " + value;
        }
        return value;
    }

    private static @NonNull String text(@Nullable String value) {
        return value != null ? String.valueOf(value) : "";
    }

    private static @NonNull String text(@Nullable Integer value) {
        return value != null ? String.valueOf(value) : "";
    }

    private static int zeroIfNull(@Nullable Integer value) {
        return value != null ? value : 0;
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
            return zeroIfNull(priority) + " " + zeroIfNull(weight) + " " + zeroIfNull(port) + " " + value;
        }
        return value;
    }
}
