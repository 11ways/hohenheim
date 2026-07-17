package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.model.DnsPeerModel;
import be.elevenways.hohenheim.model.DnsZoneModel;
import be.elevenways.hohenheim.model.DnsZonePeerModel;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.cms.common.resource.RecordScopedPage;
import be.elevenways.zenit.common.conduit.Conduit;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.result.ActionResult;
import be.elevenways.zenit.common.result.RenderTemplateResult;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.common.ui.Icon;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Secondaries tab on a primary zone: the peers this zone is replicated to
 * (NOTIFY targets + AXFR-authorized keys). Only shown for primary zones; a
 * secondary zone's authority lives on its own primary.
 */
public final class DnsZoneSecondariesPage implements RecordScopedPage<Row> {

    @Override public @NonNull Identifier id() { return Identifier.of("hohenheim", "dns_zone_secondaries"); }
    @Override public @NonNull Microcopy label() { return Microcopy.of("secondaries").withFilter("scope", "dns_zone"); }
    @Override public @NonNull String slug() { return "secondaries"; }
    @Override public @NonNull Icon icon() { return Icon.of("handshake"); }

    @Override
    public boolean visibleFor(@NonNull Row zone) {
        return !DnsZoneModel.ROLE_SECONDARY.equals(DnsZoneModel.roleOf(zone));
    }

    @Override
    public @NonNull ActionResult<?> render(@NonNull Conduit conduit,
                                           @NonNull AccessContext accessContext,
                                           @NonNull Row zone) {
        Integer zoneId = zone.get(DnsZoneModel.ID);
        DnsPeerModel peerModel = Models.get(DnsPeerModel.class);

        List<Map<String, Object>> links = new ArrayList<>();
        for (Row link : Models.get(DnsZonePeerModel.class).findByZoneId(zoneId)) {
            Integer peerId = link.get(DnsZonePeerModel.PEER_ID);
            Row peer = peerId != null ? peerModel.findById(peerId) : null;
            Map<String, Object> entry = new HashMap<>();
            entry.put("peerName", peer != null ? peer.get(DnsPeerModel.NAME) : "(deleted peer)");
            entry.put("transferHost", peer != null ? peer.get(DnsPeerModel.TRANSFER_HOST) : "");
            entry.put("editUrl", "/admin/dns-zone-peers/" + link.get(DnsZonePeerModel.ID));
            links.add(entry);
        }

        Map<String, Object> vars = new HashMap<>();
        vars.put("title", zone.get(DnsZoneModel.ORIGIN) + " - Secondaries");
        vars.put("origin", zone.get(DnsZoneModel.ORIGIN));
        vars.put("zoneId", zoneId);
        vars.put("links", links);
        vars.put("recordTabs", recordTabs(conduit));

        return new RenderTemplateResult(Identifier.of("hohenheim", "cms/dns-zone-secondaries"), vars);
    }
}
