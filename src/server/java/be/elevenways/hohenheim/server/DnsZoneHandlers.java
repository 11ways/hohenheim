package be.elevenways.hohenheim.server;

import be.elevenways.hohenheim.HohenheimEndpoints;
import be.elevenways.hohenheim.HohenheimSlugs;
import be.elevenways.hohenheim.model.DnsZoneModel;
import be.elevenways.hohenheim.server.cms.DnsOperations;
import be.elevenways.hohenheim.server.cms.HohenheimFlash;
import be.elevenways.hohenheim.server.dns.DnsZoneFiles;
import be.elevenways.zenit.cms.common.action.CmsActionResult;
import be.elevenways.zenit.cms.common.page.CmsEndpoints;
import be.elevenways.zenit.cms.common.page.CmsRoutes;
import be.elevenways.zenit.common.flash.FlashLevel;
import be.elevenways.zenit.common.operation.ZenitPlacementSurface;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.refusal.DomainRefusal;
import be.elevenways.zenit.cms.server.page.ResourceWrites;
import be.elevenways.zenit.common.routing.BoundEndpoint;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.common.validation.Violations;
import be.elevenways.zenit.server.operation.OperationPipeline;
import be.elevenways.zenit.server.operation.OperationRequest;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * DNS zone administration: the zone-file paste import. A secondary zone's remote-record edits are its Records
 * tab's own submit ({@code DnsZoneRecordsPage}); git provider browsing lives in {@link GitProviderHandlers}.
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
            // The tab's form posts keep_ns as any non-blank value; the operation takes it as a switch.
            Map<String, Object> input = new LinkedHashMap<>();
            input.put(DnsOperations.ZONE_TEXT.getName(), form.getOrDefault(DnsOperations.ZONE_TEXT.getName(), ""));
            input.put(DnsOperations.KEEP_NS.getName(), DnsZoneFiles.ApexNsPolicy.forKeepFlag(
                form.get(DnsOperations.KEEP_NS.getName())) == DnsZoneFiles.ApexNsPolicy.KEEP_FILE);
            CmsActionResult result;
            try {
                result = OperationPipeline.invoke(OperationRequest.of(DnsOperations.IMPORT_ZONE,
                        ZenitPlacementSurface.HTTP_API)
                    .caller(AccessContext.of(conduit))
                    .subjects(List.of(zone))
                    .form(input)).value();
            } catch (DomainRefusal readOnly) {
                ResourceWrites.answer(conduit, readOnly);
                return null;
            } catch (Violations refused) {
                HohenheimFlash.error(conduit, HandlerSupport.violationMessage(refused));
                return HandlerSupport.redirect(back);
            }
            if (result instanceof CmsActionResult.Toast toast && toast.level() == FlashLevel.WARNING) {
                HohenheimFlash.warning(conduit, toast.message());
            } else if (result instanceof CmsActionResult.Toast toast) {
                HohenheimFlash.success(conduit, toast.message());
            }
            return HandlerSupport.redirect(back);
        });
    }

    /** The operator panel's zone list, where an unknown or unfit zone lands. */
    private static @NonNull BoundEndpoint<?> zoneList() {
        return CmsRoutes.list(HohenheimSlugs.ADMIN, HohenheimSlugs.DNS_ZONES);
    }

    /** The zone tab {@code slug}, as a target extra parameters can still be bound onto. */
    private static @NonNull BoundEndpoint<Map<String, Object>> zoneSubpage(@NonNull Integer zoneId,
                                                                          @NonNull String slug) {
        return CmsEndpoints.RECORD_SUBPAGE
            .with(CmsEndpoints.PANEL_PARAM, HohenheimSlugs.ADMIN)
            .with(CmsEndpoints.RESOURCE_PARAM, HohenheimSlugs.DNS_ZONES)
            .with(CmsEndpoints.RESOURCE_ID_PARAM, String.valueOf(zoneId))
            .with(CmsEndpoints.SUBPAGE_PARAM, slug);
    }
}
