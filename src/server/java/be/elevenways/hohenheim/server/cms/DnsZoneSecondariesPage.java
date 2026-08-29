package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.HohenheimParams;
import be.elevenways.hohenheim.model.DnsPeerModel;
import be.elevenways.hohenheim.model.DnsZoneModel;
import be.elevenways.hohenheim.model.DnsZonePeerModel;
import be.elevenways.hohenheim.server.dns.DnsSecondaryFreshness;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.protoblast.common.time.RelativeTimeWording;
import be.elevenways.zenit.cms.common.page.CmsEndpoints;
import be.elevenways.zenit.cms.common.page.CmsRoutes;
import be.elevenways.zenit.cms.common.resource.RecordScopedPage;
import be.elevenways.zenit.common.conduit.Conduit;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.result.ActionResult;
import be.elevenways.zenit.common.result.RenderTemplateResult;
import be.elevenways.zenit.common.routing.RouteTarget;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.common.ui.Icon;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.time.Instant;
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
            entry.put("editTarget", CmsRoutes.detail("admin", "dns-zone-peers",
                link.get(DnsZonePeerModel.ID)));
            // Freshness as probed from this primary: what the peer served, when, and
            // whether that lag has outlived the stale window.
            Integer served = link.get(DnsZonePeerModel.SERVED_SERIAL);
            String probeError = link.get(DnsZonePeerModel.PROBE_ERROR);
            entry.put("servedSerial", served != null ? String.valueOf(served) : "");
            entry.put("probeError", probeError != null ? probeError : "");
            Instant probedAt = link.get(DnsZonePeerModel.PROBED_AT);
            entry.put("probedAtIso", probedAt != null ? probedAt.toString() : "");
            Freshness freshness = freshnessOf(link);
            entry.put("freshnessLabel", freshness.label());
            entry.put("freshnessVariant", freshness.variant());
            links.add(entry);
        }

        Map<String, Object> vars = new HashMap<>();
        vars.put("title", CmsSupport.pageTitle(conduit, "dns_secondaries",
            zone.get(DnsZoneModel.ORIGIN)));
        vars.put("origin", zone.get(DnsZoneModel.ORIGIN));
        vars.put("zoneId", zoneId);
        vars.put("links", links);
        // Create form + prefill query parameter: composed off CmsEndpoints, since
        // CmsRoutes.create returns the RouteTarget interface (no with(...)).
        vars.put("attachPeerTarget", CmsEndpoints.CREATE_FORM
            .with(CmsEndpoints.PANEL_PARAM, "admin")
            .with(CmsEndpoints.RESOURCE_PARAM, "dns-zone-peers")
            .with(HohenheimParams.ZONE_ID_PREFILL, zoneId));
        vars.put("recordTabs", recordTabs(conduit));
        vars.put("timeWording", RelativeTimeWording.resolve(
            conduit.getLocales(), conduit.getMessageResolver()));

        return new RenderTemplateResult(Identifier.of("hohenheim", "cms/dns-zone-secondaries"), vars);
    }

    /** The freshness pill a link row projects, as probed from this primary. */
    enum Freshness {
        UNPROBED("unprobed", "secondary"),
        CURRENT("current", "green"),
        BEHIND("behind", "orange"),
        STALE("stale", "red");

        private final String token;
        private final String variant;

        Freshness(String token, String variant) {
            this.token = token;
            this.variant = variant;
        }

        @NonNull Microcopy label() {
            return Microcopy.of(this.token).withFilter("scope", "dns_freshness");
        }

        @NonNull String variant() {
            return this.variant;
        }
    }

    static @NonNull Freshness freshnessOf(@NonNull Row link) {
        if (link.get(DnsZonePeerModel.PROBED_AT) == null) {
            return Freshness.UNPROBED;
        }
        if (link.get(DnsZonePeerModel.BEHIND_SINCE) == null) {
            return Freshness.CURRENT;
        }
        return DnsSecondaryFreshness.isStale(link) ? Freshness.STALE : Freshness.BEHIND;
    }
}
