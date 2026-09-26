package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.AttentionItem;
import be.elevenways.hohenheim.AttentionSeverity;
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

import java.util.List;

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
                CmsRoutes.list(ADMIN, SettingsPage.DEFAULT_SLUG)));
        }
        for (DnsZoneSnapshot zone : DnsZoneStore.INSTANCE.zones()) {
            if (zone.getRrset(zone.getOrigin(), Type.NS) == null) {
                items.add(item(AttentionSeverity.WARNING, "sitemap",
                    copy("dns_zone_no_ns", "attention_title", "origin", zone.getOriginString()),
                    copy("dns_zone_no_ns", "attention_detail"),
                    CmsRoutes.subpage(ADMIN, DnsZoneResource.SLUG, zone.getZoneId(),
                        DnsZoneRecordsPage.SLUG)));
            }
        }
        staleDnsSecondaries(items);
        brokenDnsDelegations(items);
    }

    /**
     * A linked secondary that has served an old serial, or nothing, for longer than the
     * stale window -- read off the link rows the probe task writes, never probed here.
     */
    public static void staleDnsSecondaries(List<AttentionItem> items) {
        DnsZoneModel zones = Models.get(DnsZoneModel.class);
        DnsPeerModel peers = Models.get(DnsPeerModel.class);
        for (Row link : Models.get(DnsZonePeerModel.class).find().all()) {
            if (!DnsSecondaryFreshness.isStale(link)) {
                continue;
            }
            Integer zoneId = link.get(DnsZonePeerModel.ZONE_ID);
            Integer peerId = link.get(DnsZonePeerModel.PEER_ID);
            Row zone = zoneId != null ? zones.findById(zoneId) : null;
            Row peer = peerId != null ? peers.findById(peerId) : null;
            if (zone == null || !Boolean.TRUE.equals(zone.get(DnsZoneModel.ENABLED))) {
                continue;
            }
            String error = link.get(DnsZonePeerModel.PROBE_ERROR);
            Integer served = link.get(DnsZonePeerModel.SERVED_SERIAL);
            Microcopy detail = error != null
                ? literal(error)
                : copy("dns_secondary_stale", "attention_detail",
                    "served", served != null ? served : 0,
                    "serial", zone.get(DnsZoneModel.SERIAL) != null ? zone.get(DnsZoneModel.SERIAL) : 0);
            items.add(item(AttentionSeverity.WARNING, "handshake",
                copy("dns_secondary_stale", "attention_title",
                    "peer", peer != null ? String.valueOf(peer.get(DnsPeerModel.NAME)) : "#" + peerId,
                    "origin", String.valueOf(zone.get(DnsZoneModel.ORIGIN))),
                detail,
                CmsRoutes.subpage(ADMIN, DnsZoneResource.SLUG, zoneId, "secondaries")));
        }
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
                CmsRoutes.detail(ADMIN, DnsZoneResource.SLUG, zone.get(DnsZoneModel.ID))));
        }
    }
}
