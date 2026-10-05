package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.HohenheimIds;
import be.elevenways.hohenheim.HohenheimSlugs;
import be.elevenways.hohenheim.HohenheimParams;
import be.elevenways.hohenheim.HohenheimTemplateIds;
import be.elevenways.hohenheim.model.DnsPeerModel;
import be.elevenways.hohenheim.model.DnsZoneModel;
import be.elevenways.hohenheim.model.DnsZonePeerModel;
import be.elevenways.hohenheim.server.dns.DnsSecondaryFreshness;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.protoblast.common.time.RelativeTimeWording;
import be.elevenways.zenit.cms.common.page.CmsEndpoints;
import be.elevenways.zenit.cms.common.page.CmsRoutes;
import be.elevenways.zenit.cms.common.resource.RecordTab;
import be.elevenways.zenit.cms.common.panel.PanelRequest;
import be.elevenways.zenit.common.conduit.Conduit;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.result.ActionResult;
import be.elevenways.zenit.common.result.RenderTemplateResult;
import be.elevenways.zenit.common.routing.RouteTarget;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.common.ui.BadgeVariant;
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
public final class DnsZoneSecondariesPage implements RecordTab.Rendered<Row> {

    @Override public @NonNull Identifier id() { return HohenheimIds.id("dns_zone_secondaries"); }
    @Override public @NonNull Microcopy label() { return Microcopy.of("secondaries").withFilter("scope", "dns_zone"); }
    @Override public @NonNull String slug() { return "secondaries"; }
    @Override public @NonNull Icon icon() { return Icon.of("handshake"); }

    @Override
    public boolean visibleFor(@NonNull Row zone, @NonNull AccessContext access) {
        return !DnsZoneModel.ROLE_SECONDARY.equals(DnsZoneModel.roleOf(zone));
    }

    @Override
    public @NonNull ActionResult<?> render(@NonNull PanelRequest request, @NonNull Row zone) {
        return render(request.conduit(), request.access(), zone);
    }

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
            entry.put("editTarget", CmsRoutes.detail(HohenheimSlugs.ADMIN, DnsZonePeerParts.SLUG,
                link.get(DnsZonePeerModel.ID)));
            // Freshness as probed from this primary: what the peer served, when, and
            // whether that lag has outlived the stale window.
            Integer served = link.get(DnsZonePeerModel.SERVED_SERIAL);
            String probeError = link.get(DnsZonePeerModel.PROBE_ERROR);
            entry.put("servedSerial", served != null ? String.valueOf(served) : "");
            entry.put("probeError", probeError != null ? probeError : "");
            Instant probedAt = link.get(DnsZonePeerModel.PROBED_AT);
            entry.put("probedAtIso", probedAt != null ? probedAt.toString() : "");
            // And what THIS primary last did for the peer.
            Integer axfrSerial = link.get(DnsZonePeerModel.LAST_AXFR_SERIAL);
            Instant axfrAt = link.get(DnsZonePeerModel.LAST_AXFR_AT);
            Instant notifyAt = link.get(DnsZonePeerModel.LAST_NOTIFY_AT);
            String notifyOutcome = link.get(DnsZonePeerModel.LAST_NOTIFY_OUTCOME);
            entry.put("axfrSerial", axfrSerial != null ? String.valueOf(axfrSerial) : "");
            entry.put("axfrAtIso", axfrAt != null ? axfrAt.toString() : "");
            entry.put("notifyAtIso", notifyAt != null ? notifyAt.toString() : "");
            entry.put("notifyOutcome", notifyOutcome != null ? notifyOutcome : "");
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
            .with(CmsEndpoints.PANEL_PARAM, HohenheimSlugs.ADMIN)
            .with(CmsEndpoints.RESOURCE_PARAM, DnsZonePeerParts.SLUG)
            .with(HohenheimParams.ZONE_ID_PREFILL, zoneId));
        vars.put("head", recordHead(conduit));
        vars.put("timeWording", RelativeTimeWording.resolve(
            conduit.getLocales(), conduit.getMessageResolver()));

        return new RenderTemplateResult(HohenheimTemplateIds.DNS_ZONE_SECONDARIES, vars);
    }

    /** The freshness pill a link row projects, as probed from this primary. */
    enum Freshness {
        UNPROBED("unprobed", BadgeVariant.SECONDARY),
        CURRENT("current", BadgeVariant.SUCCESS),
        BEHIND("behind", BadgeVariant.WARNING),
        STALE("stale", BadgeVariant.DESTRUCTIVE);

        private final String token;
        private final BadgeVariant variant;

        Freshness(String token, BadgeVariant variant) {
            this.token = token;
            this.variant = variant;
        }

        @NonNull Microcopy label() {
            return Microcopy.of(this.token).withFilter("scope", "dns_freshness");
        }

        @NonNull BadgeVariant variant() {
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
