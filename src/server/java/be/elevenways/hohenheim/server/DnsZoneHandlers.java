package be.elevenways.hohenheim.server;

import be.elevenways.hohenheim.HohenheimActivityAction;
import be.elevenways.hohenheim.HohenheimEndpoints;
import be.elevenways.hohenheim.HohenheimSlugs;
import be.elevenways.hohenheim.model.DnsZoneModel;
import be.elevenways.hohenheim.server.api.ApiConduits;
import be.elevenways.hohenheim.server.cms.DnsRecordResource;
import be.elevenways.hohenheim.server.cms.HohenheimFlash;
import be.elevenways.hohenheim.server.dns.DnsZoneFiles;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.zenit.cms.common.access.AccessRefusedException;
import be.elevenways.zenit.cms.common.page.CmsEndpoints;
import be.elevenways.zenit.cms.common.page.CmsRoutes;
import be.elevenways.zenit.common.orm.activity.ActivityLog;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.cms.server.page.ResourceWrites;
import be.elevenways.zenit.common.routing.BoundEndpoint;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.common.validation.Violations;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.util.Map;

/**
 * DNS zone administration: the zone-file paste import. A secondary zone's remote-record edits are its Records
 * tab's own submit ({@code DnsZoneRecordsPage}); git provider browsing lives in {@link GitProviderHandlers}.
 */
final class DnsZoneHandlers {

    /** The record resource whose declared parent judges whether an import may write into a zone. */
    private static final DnsRecordResource RECORDS = new DnsRecordResource();

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
            try {
                RECORDS.requireImportable(ApiConduits.adminPanel(), zoneId, AccessContext.of(conduit));
            } catch (AccessRefusedException readOnly) {
                ResourceWrites.answer(conduit, readOnly);
                return null;
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
                ActivityLog.record(Models.get(DnsZoneModel.class), zoneId, HohenheimActivityAction.IMPORTED,
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
