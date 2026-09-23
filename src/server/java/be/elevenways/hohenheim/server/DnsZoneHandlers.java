package be.elevenways.hohenheim.server;

import be.elevenways.hohenheim.HohenheimEndpoints;
import be.elevenways.hohenheim.HohenheimSlugs;
import be.elevenways.hohenheim.model.DnsPeerModel;
import be.elevenways.hohenheim.model.DnsZoneModel;
import be.elevenways.hohenheim.server.cms.HohenheimFlash;
import be.elevenways.hohenheim.server.dns.DnsPeerApi;
import be.elevenways.hohenheim.server.dns.DnsZoneFiles;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.zenit.cms.common.page.CmsEndpoints;
import be.elevenways.zenit.cms.common.page.CmsRoutes;
import be.elevenways.zenit.common.orm.activity.ActivityLog;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.routing.BoundEndpoint;
import be.elevenways.zenit.common.validation.Violations;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * DNS zone administration: the zone-file paste import and the remote-record edit
 * forwarding. Git provider browsing lives in {@link GitProviderHandlers}.
 */
final class DnsZoneHandlers {

    private DnsZoneHandlers() {
    }

    /** Zone-file import (the zone-file tab's paste form). */
    static void initZones() {
        HohenheimEndpoints.DNS_ZONE_IMPORT.setHandler(conduit -> {
            Integer zoneId = conduit.getParameter(HohenheimEndpoints.ZONE_ID);
            Row zone = Models.get(DnsZoneModel.class).find()
                .where(DnsZoneModel.ID.eq(zoneId)).first();
            if (zone == null) {
                return HandlerSupport.redirect(zoneList());
            }

            BoundEndpoint<Map<String, Object>> back = zoneSubpage(zoneId, "zonefile");
            Map<String, String> form = HandlerSupport.formMap(conduit);
            String text = form.getOrDefault("zone_text", "");
            if (text.isBlank()) {
                HohenheimFlash.error(conduit, zoneError("import_empty"));
                return HandlerSupport.redirect(back);
            }

            try {
                DnsZoneFiles.ImportResult result = DnsZoneFiles.importText(zone, text,
                    DnsZoneFiles.ApexNsPolicy.forKeepFlag(form.get("keep_ns")));
                ActivityLog.record(Models.get(DnsZoneModel.class), zoneId, "imported",
                    zone.get(DnsZoneModel.ORIGIN));
                String notes = String.join("; ", result.notes());
                if (!result.skipped().isEmpty()) {
                    HohenheimFlash.warning(conduit, zoneError("import_partial")
                        .withArg("count", result.imported())
                        .withArg("skipped", String.join("; ", result.skipped()))
                        .withArg("notes", notes));
                } else if (!notes.isEmpty()) {
                    HohenheimFlash.success(conduit, zoneError("import_done_notes")
                        .withArg("count", result.imported())
                        .withArg("notes", notes));
                } else {
                    HohenheimFlash.success(conduit, zoneError("import_done")
                        .withArg("count", result.imported()));
                }
                return HandlerSupport.redirect(back);
            }
            catch (Violations refused) {
                HohenheimFlash.error(conduit, HandlerSupport.violationMessage(refused));
                return HandlerSupport.redirect(back);
            }
            catch (Exception e) {
                HohenheimFlash.error(conduit, zoneError("import_failed")
                    .withArg("reason", String.valueOf(e.getMessage())));
                return HandlerSupport.redirect(back);
            }
        });
    }

    /**
     * Remote-record edit forwarding: the admin form POST on a SECONDARY zone's Records
     * tab, forwarded to the owning peer's API.
     */
    static void initRemoteRecords() {
        HohenheimEndpoints.DNS_REMOTE_RECORD.setHandler(conduit -> {
            Integer zoneId = conduit.getParameter(HohenheimEndpoints.ZONE_ID);
            Row zone = Models.get(DnsZoneModel.class).find()
                .where(DnsZoneModel.ID.eq(zoneId)).first();
            if (zone == null || !DnsZoneModel.ROLE_SECONDARY.equals(DnsZoneModel.roleOf(zone))) {
                return HandlerSupport.redirect(zoneList());
            }
            BoundEndpoint<Map<String, Object>> back = zoneSubpage(zoneId, "records");

            Integer peerId = zone.get(DnsZoneModel.PRIMARY_PEER_ID);
            Row peer = peerId != null ? Models.get(DnsPeerModel.class).findById(peerId) : null;
            DnsPeerApi api = DnsPeerApi.forPeer(peer);
            if (api == null) {
                HohenheimFlash.error(conduit,
                    Microcopy.of("peer_not_configured").withFilter("scope", "dns_remote"));
                return HandlerSupport.redirect(back);
            }

            Map<String, String> form = HandlerSupport.formMap(conduit);
            String origin = zone.get(DnsZoneModel.ORIGIN);
            String action = form.getOrDefault("action", "save");
            String recordId = form.getOrDefault("record_id", "").trim();
            Map<String, String> fields = new LinkedHashMap<>();
            for (String field : DnsRecordApiHandlers.RECORD_FIELDS) {
                if (form.containsKey(field)) {
                    fields.put(field, form.get(field));
                }
            }

            try {
                if ("delete".equals(action) && !recordId.isEmpty()) {
                    api.deleteRecord(origin, Integer.parseInt(recordId));
                }
                else if (!recordId.isEmpty()) {
                    api.updateRecord(origin, Integer.parseInt(recordId), fields);
                }
                else {
                    api.createRecord(origin, fields);
                }
            }
            catch (NumberFormatException e) {
                return HandlerSupport.redirect(back);
            }
            catch (DnsPeerApi.PeerApiException e) {
                // A validation refusal round-trips by microcopy key (same catalogs
                // on both instances); transport failures show the raw message.
                Microcopy message = e.getViolationKey() != null
                    ? Microcopy.of(e.getViolationKey()).withFilter("scope", "violations")
                    : Microcopy.of("peer_call_failed").withFilter("scope", "dns_remote")
                        .withArg("reason", String.valueOf(e.getMessage()));
                HohenheimFlash.error(conduit, message);
                return HandlerSupport.redirect(back);
            }

            HohenheimFlash.success(conduit,
                Microcopy.of("edit_saved").withFilter("scope", "dns_remote"));
            return HandlerSupport.redirect(back);
        });
    }

    /** The operator panel's zone list, where an unknown or unfit zone lands. */
    private static @NonNull BoundEndpoint<?> zoneList() {
        return CmsRoutes.list(HandlerSupport.ADMIN, HohenheimSlugs.DNS_ZONES);
    }

    /** A zone-tab outcome message. */
    private static Microcopy zoneError(String key) {
        return Microcopy.of(key).withFilter("scope", "dns_zone");
    }

    /** The zone tab {@code slug}, as a target extra parameters can still be bound onto. */
    private static @NonNull BoundEndpoint<Map<String, Object>> zoneSubpage(@NonNull Integer zoneId,
                                                                          @NonNull String slug) {
        return CmsEndpoints.RECORD_SUBPAGE
            .with(CmsEndpoints.PANEL_PARAM, HandlerSupport.ADMIN)
            .with(CmsEndpoints.RESOURCE_PARAM, HohenheimSlugs.DNS_ZONES)
            .with(CmsEndpoints.RESOURCE_ID_PARAM, String.valueOf(zoneId))
            .with(CmsEndpoints.SUBPAGE_PARAM, slug);
    }
}
