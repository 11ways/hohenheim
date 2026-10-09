package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.AttentionItem;
import be.elevenways.hohenheim.AttentionSeverity;
import be.elevenways.hohenheim.AttentionSubject;
import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.hohenheim.HohenheimSlugs;
import be.elevenways.hohenheim.dns.DelegationVerdict;
import be.elevenways.hohenheim.model.DnsPeerModel;
import be.elevenways.hohenheim.model.DnsZoneModel;
import be.elevenways.hohenheim.model.DnsZonePeerModel;
import be.elevenways.hohenheim.server.ServerMain;
import be.elevenways.hohenheim.server.dns.DnsSecondaryFreshness;
import be.elevenways.hohenheim.server.dns.DnsZoneSnapshot;
import be.elevenways.hohenheim.server.dns.DnsZoneStore;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.zenit.cms.common.page.CmsRoutes;
import be.elevenways.zenit.cms.server.page.SettingsPage;
import be.elevenways.zenit.common.Zenit;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import org.xbill.DNS.Type;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static be.elevenways.hohenheim.server.cms.AttentionItems.action;
import static be.elevenways.hohenheim.server.cms.AttentionItems.copy;
import static be.elevenways.hohenheim.server.cms.AttentionItems.item;
import static be.elevenways.hohenheim.server.cms.AttentionItems.literal;

/**
 * The DNS role's attention items: the listener, zones without NS, stale secondaries and broken delegations.
 *
 * Reads the in-memory zone store and the rows the probe tasks write; nothing is resolved per render.
 *
 * @author Jelle De Loecker
 * @since 0.1.0
 */
public final class DnsAttention {

    private static final String ADMIN = HohenheimSlugs.ADMIN;

    private DnsAttention() {
    }

    /** DNS listeners that failed to bind, and enabled zones a resolver cannot delegate to. */
    static void dnsIssues(List<AttentionItem> items) {
        Boolean enabled = Zenit.SETTINGS_VALUES.getValue(HohenheimSettings.Dns.ENABLED);
        var dnsServer = ServerMain.getDnsServer();
        if (Boolean.TRUE.equals(enabled) && (dnsServer == null || !dnsServer.isRunning())) {
            String reason = dnsServer != null ? dnsServer.getStartupError() : null;
            items.add(item(AttentionSeverity.ERROR, "sitemap",
                copy("dns_listener", "attention_title"),
                literal(reason),
                CmsRoutes.list(ADMIN, SettingsPage.DEFAULT_SLUG),
                action("act_open_settings")));
        }
        for (DnsZoneSnapshot zone : DnsZoneStore.INSTANCE.zones()) {
            if (zone.getRrset(zone.getOrigin(), Type.NS) == null) {
                items.add(item(AttentionSeverity.WARNING, "sitemap",
                    copy("dns_zone_no_ns", "attention_title", "origin", zone.getOriginString()),
                    copy("dns_zone_no_ns", "attention_detail"),
                    CmsRoutes.subpage(ADMIN, DnsZoneParts.SLUG, zone.getZoneId(),
                        DnsZoneRecordsPage.SLUG),
                    action("act_add_ns")));
            }
        }
        staleDnsSecondaries(items);
        brokenDnsDelegations(items);
    }

    /**
     * The zones whose linked secondaries have served an old serial, or nothing, for longer than the stale window: ONE
     * item per zone, its root, naming every stale secondary -- read off the link rows the probe task writes, never
     * probed here.
     *
     * AIDEV-NOTE: the headline and each secondary's words are {@link DnsSecondaryFreshness}'s, the same the
     * DNS_SECONDARY_STALE alert says, so the inbox and the dashboard name one lag alike; what the probe saw (no
     * authoritative answer from host:port) is the detail after the words. The item lasts as long as the lag: a
     * secondary that catches up clears its link's behind_since and the item with it (Starfleet's kuifje, 2026-10-07:
     * stale for 39 minutes, its alert still unread a day later).
     */
    public static void staleDnsSecondaries(List<AttentionItem> items) {
        DnsZoneModel zones = Models.get(DnsZoneModel.class);
        DnsPeerModel peers = Models.get(DnsPeerModel.class);
        Map<Integer, List<Row[]>> staleByZone = new LinkedHashMap<>();
        for (Row link : Models.get(DnsZonePeerModel.class).find().all()) {
            if (!DnsSecondaryFreshness.isStale(link)) {
                continue;
            }
            Integer zoneId = link.get(DnsZonePeerModel.ZONE_ID);
            Integer peerId = link.get(DnsZonePeerModel.PEER_ID);
            Row peer = peerId != null ? peers.findById(peerId) : null;
            if (zoneId != null && peer != null) {
                staleByZone.computeIfAbsent(zoneId, id -> new ArrayList<>()).add(new Row[] {link, peer});
            }
        }
        staleByZone.forEach((zoneId, stale) -> {
            Row zone = zones.findById(zoneId);
            if (zone == null || !Boolean.TRUE.equals(zone.get(DnsZoneModel.ENABLED))) {
                return;
            }
            Integer serial = zone.get(DnsZoneModel.SERIAL);
            List<String> names = new ArrayList<>();
            List<Microcopy> lags = new ArrayList<>();
            for (Row[] pair : stale) {
                names.add(String.valueOf((Object) pair[1].get(DnsPeerModel.NAME)));
                lags.add(DnsSecondaryFreshness.lagOf(pair[1], pair[0], serial != null ? serial : 0));
            }
            Microcopy detail = copy("dns_secondaries_lag", "attention_detail", "lags", lags);
            items.add(item(AttentionSeverity.WARNING, "handshake",
                DnsSecondaryFreshness.staleTitle(names, String.valueOf((Object) zone.get(DnsZoneModel.ORIGIN))),
                detail,
                CmsRoutes.subpage(ADMIN, DnsZoneParts.SLUG, zoneId, "secondaries"),
                action("act_open_secondaries"))
                .about(AttentionSubject.zone(zoneId), null));
        });
    }

    /**
     * A primary zone whose last delegation check ended in a verdict that carries a
     * severity; the verdict's own label is the detail.
     */
    public static void brokenDnsDelegations(List<AttentionItem> items) {
        for (Row zone : Models.get(DnsZoneModel.class).findEnabled()) {
            DelegationVerdict verdict = DelegationVerdict.forToken(zone.get(DnsZoneModel.DELEGATION_STATUS));
            if (verdict == null || verdict.severity() == null) {
                continue;
            }
            items.add(item(verdict.severity(), verdict.icon(),
                copy("dns_delegation_broken", "attention_title",
                    "origin", String.valueOf(zone.get(DnsZoneModel.ORIGIN))),
                verdict.label(),
                CmsRoutes.detail(ADMIN, DnsZoneParts.SLUG, zone.get(DnsZoneModel.ID)),
                action("act_open_zone")));
        }
    }
}
