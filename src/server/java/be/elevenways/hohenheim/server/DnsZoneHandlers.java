package be.elevenways.hohenheim.server;

import be.elevenways.hohenheim.HohenheimEndpoints;
import be.elevenways.hohenheim.model.DnsPeerModel;
import be.elevenways.hohenheim.model.DnsZoneModel;
import be.elevenways.hohenheim.server.cms.HohenheimFlash;
import be.elevenways.hohenheim.server.dns.DnsPeerApi;
import be.elevenways.hohenheim.server.dns.DnsZoneFiles;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.util.BlastString;
import be.elevenways.zenit.cms.common.page.CmsEndpoints;
import be.elevenways.zenit.cms.common.page.CmsRoutes;
import be.elevenways.zenit.common.data.DataItem;
import be.elevenways.zenit.common.data.DataPage;
import be.elevenways.zenit.common.orm.activity.ActivityLog;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.result.DryResult;
import be.elevenways.zenit.common.routing.BoundEndpoint;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * DNS zone administration: the zone-file paste import, the git provider browsing
 * pickers that ride the same tab family, and the remote-record edit forwarding.
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
                return HandlerSupport.redirect(CmsRoutes.list(HandlerSupport.ADMIN, "dns-zones"));
            }

            BoundEndpoint<Map<String, Object>> back = zoneSubpage(zoneId, "zonefile");
            Map<String, String> form = HandlerSupport.formMap(conduit);
            String text = form.getOrDefault("zone_text", "");
            if (text.isBlank()) {
                HohenheimFlash.error(conduit, zoneError("import_empty"));
                return HandlerSupport.redirect(back);
            }

            try {
                DnsZoneFiles.ImportResult result = DnsZoneFiles.importText(zone, text);
                ActivityLog.record(Models.get(DnsZoneModel.class), zoneId, "imported",
                    zone.get(DnsZoneModel.ORIGIN));
                if (result.skipped().isEmpty()) {
                    HohenheimFlash.success(conduit, zoneError("import_done")
                        .withArg("count", result.imported()));
                } else {
                    HohenheimFlash.warning(conduit, zoneError("import_partial")
                        .withArg("count", result.imported())
                        .withArg("skipped", String.join("; ", result.skipped())));
                }
                return HandlerSupport.redirect(back);
            }
            catch (Exception e) {
                HohenheimFlash.error(conduit, zoneError("import_failed")
                    .withArg("reason", String.valueOf(e.getMessage())));
                return HandlerSupport.redirect(back);
            }
        });

        // --- Git provider browsing: repository/branch selection for admin pickers
        //     and automation. Read-only against the provider, admin-gated, limited;
        //     answers are typed DataPages so pl-select's DataProviders consume them
        //     through Endpoint.call unchanged. ---
        HohenheimEndpoints.GIT_PROVIDER_REPOSITORIES.setHandler(conduit -> {
            Integer providerId = conduit.getParameter(HohenheimEndpoints.PROVIDER_ID);
            String text = trimmedQuery(conduit.getQueryParam("text"));
            try {
                List<DataItem> items = new ArrayList<>();
                for (var repo : be.elevenways.hohenheim.server.source.GitProviders
                        .clientFor(providerId).listRepositories()) {
                    if (!text.isEmpty()
                            && !BlastString.lower(repo.fullName()).contains(text)) {
                        continue;
                    }
                    items.add(new DataItem(repo.fullName(), repo.fullName(),
                        repo.defaultBranch(), null, null, null, null, Map.of()));
                }
                return new DryResult<>(new DataPage(items, 1, 1, items.size()));
            } catch (Exception e) {
                conduit.setResponseStatus(502);
                conduit.endWithContentType("text/plain", String.valueOf(e.getMessage()));
                return null;
            }
        });

        HohenheimEndpoints.GIT_PROVIDER_BRANCHES.setHandler(conduit -> {
            Integer providerId = conduit.getParameter(HohenheimEndpoints.PROVIDER_ID);
            String repository = conduit.getQueryParam("repository");
            if (repository == null || repository.isBlank()) {
                conduit.badRequest("repository required");
                return null;
            }
            String text = trimmedQuery(conduit.getQueryParam("text"));
            try {
                List<DataItem> items = new ArrayList<>();
                for (String branch : be.elevenways.hohenheim.server.source.GitProviders
                        .clientFor(providerId).listBranches(repository)) {
                    if (!text.isEmpty() && !BlastString.lower(branch).contains(text)) {
                        continue;
                    }
                    items.add(new DataItem(branch, branch, null, null, null, null, null, Map.of()));
                }
                return new DryResult<>(new DataPage(items, 1, 1, items.size()));
            } catch (Exception e) {
                conduit.setResponseStatus(502);
                conduit.endWithContentType("text/plain", String.valueOf(e.getMessage()));
                return null;
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
                return HandlerSupport.redirect(CmsRoutes.list(HandlerSupport.ADMIN, "dns-zones"));
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

    /** A zone-tab outcome message. */
    private static Microcopy zoneError(String key) {
        return Microcopy.of(key).withFilter("scope", "dns_zone");
    }

    private static @NonNull String trimmedQuery(@Nullable String value) {
        return value == null ? "" : BlastString.lower(value.trim());
    }

    /** The zone tab {@code slug}, as a target extra parameters can still be bound onto. */
    private static @NonNull BoundEndpoint<Map<String, Object>> zoneSubpage(@NonNull Integer zoneId,
                                                                          @NonNull String slug) {
        return CmsEndpoints.RECORD_SUBPAGE
            .with(CmsEndpoints.PANEL_PARAM, HandlerSupport.ADMIN)
            .with(CmsEndpoints.RESOURCE_PARAM, "dns-zones")
            .with(CmsEndpoints.RESOURCE_ID_PARAM, String.valueOf(zoneId))
            .with(CmsEndpoints.SUBPAGE_PARAM, slug);
    }
}
