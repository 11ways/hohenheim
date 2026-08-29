package be.elevenways.hohenheim.server.api;

import be.elevenways.hohenheim.HohenheimEndpoints;
import be.elevenways.hohenheim.model.DnsRecordModel;
import be.elevenways.hohenheim.model.DnsZoneModel;
import be.elevenways.hohenheim.server.cms.DnsZoneResource;
import be.elevenways.hohenheim.server.dns.DnsNames;
import be.elevenways.hohenheim.server.dns.DnsZoneFiles;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.zenit.cms.common.access.AccessRefusedException;
import be.elevenways.zenit.cms.server.page.ResourceWrites;
import be.elevenways.zenit.common.conduit.Conduit;
import be.elevenways.zenit.common.orm.activity.ActivityLog;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.orm.query.SortOrder;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.common.validation.Violations;
import be.elevenways.zenit.server.http.body.FormSubmissionRawValues;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The PaaS API's DNS zone lane: list, create a primary zone and import a zone file,
 * through the very pipeline the admin panel runs.
 *
 * AIDEV-NOTE: no model write here either (the {@link SiteApi} stance). A create rides
 * zenit-cms {@code ResourceWrites} over {@link DnsZoneResource}, so validation, the origin
 * canonicalization, the declared-nameserver seeding and the served-snapshot reload are the
 * form's; an import is {@link DnsZoneFiles#importText} exactly as the Zone-file tab posts
 * it, {@code keep_ns} included. Every verb demands the admin permission, because zones are
 * an operator surface: the tenant panel exposes records under a delegated zone, never zones.
 */
public final class DnsZoneApi {

    private static final DnsZoneResource ZONES = new DnsZoneResource();

    private DnsZoneApi() {
    }

    public static void init() {
        HohenheimEndpoints.API_V1_DNS_ZONES.setHandler(conduit -> {
            AccessContext ctx = SiteApi.requireAdminKey(conduit);
            if (ctx == null) {
                return null;
            }
            List<Map<String, Object>> zones = new ArrayList<>();
            for (Row zone : Models.get(DnsZoneModel.class).find()
                    .orderBy(DnsZoneModel.ORIGIN, SortOrder.ASC).all()) {
                zones.add(projection(zone));
            }
            return ApiConduits.json(Map.of("zones", zones));
        });

        HohenheimEndpoints.API_V1_DNS_ZONE_CREATE.setHandler(conduit -> {
            AccessContext ctx = SiteApi.requireAdminKey(conduit);
            if (ctx == null) {
                return null;
            }
            try {
                int zoneId = (Integer) ResourceWrites.create(ZONES,
                    FormSubmissionRawValues.fromConduit(conduit), ctx);
                Row created = Objects.requireNonNull(
                    Models.get(DnsZoneModel.class).findById(zoneId));
                ActivityLog.record(Models.get(DnsZoneModel.class), zoneId, "created",
                    created.get(DnsZoneModel.ORIGIN));
                return ApiConduits.json(projection(created));
            } catch (Violations refused) {
                return ApiConduits.refusal(conduit, refused);
            } catch (AccessRefusedException refused) {
                conduit.forbidden();
                return null;
            }
        });

        HohenheimEndpoints.API_V1_DNS_ZONE_IMPORT.setHandler(conduit -> {
            AccessContext ctx = SiteApi.requireAdminKey(conduit);
            if (ctx == null) {
                return null;
            }
            Row zone = zoneOf(conduit);
            if (zone == null) {
                conduit.notFound();
                return null;
            }
            int zoneId = zone.get(DnsZoneModel.ID);
            String text = ApiConduits.formValue(conduit, "zone_text");
            if (text.isBlank()) {
                return ApiConduits.refusal(conduit, Violations.ofField("zone_text", "",
                    Microcopy.of("import_empty").withFilter("scope", "dns_zone")));
            }
            try {
                DnsZoneFiles.ImportResult result = DnsZoneFiles.importText(zone, text,
                    DnsZoneFiles.ApexNsPolicy.forKeepFlag(ApiConduits.formValue(conduit, "keep_ns")));
                ActivityLog.record(Models.get(DnsZoneModel.class), zoneId, "imported",
                    zone.get(DnsZoneModel.ORIGIN));
                Row reloaded = Objects.requireNonNull(Models.get(DnsZoneModel.class).findById(zoneId));
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("id", zoneId);
                body.put("origin", reloaded.get(DnsZoneModel.ORIGIN));
                body.put("serial", reloaded.get(DnsZoneModel.SERIAL));
                body.put("imported", result.imported());
                body.put("skipped", result.skipped());
                body.put("notes", result.notes());
                body.put("nameservers", result.nameservers());
                return ApiConduits.json(body);
            } catch (Violations refused) {
                return ApiConduits.refusal(conduit, refused);
            } catch (IOException unparseable) {
                return ApiConduits.refusal(conduit, Violations.ofField("zone_text", "",
                    Microcopy.of("import_failed").withFilter("scope", "dns_zone")
                        .withArg("reason", String.valueOf(unparseable.getMessage()))));
            }
        });
    }

    private static @Nullable Row zoneOf(@NonNull Conduit conduit) {
        Integer zoneId = conduit.getParameter(HohenheimEndpoints.ZONE_ID);
        return zoneId == null ? null : Models.get(DnsZoneModel.class).findById(zoneId);
    }

    /** THE enumerated view of a zone row: the operator columns plus its served apex NS names. */
    static @NonNull Map<String, Object> projection(@NonNull Row zone) {
        int zoneId = zone.get(DnsZoneModel.ID);
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("id", zoneId);
        entry.put("origin", zone.get(DnsZoneModel.ORIGIN));
        entry.put("role", DnsZoneModel.roleOf(zone));
        entry.put("enabled", Boolean.TRUE.equals(zone.get(DnsZoneModel.ENABLED)));
        entry.put("serial", zone.get(DnsZoneModel.SERIAL));
        entry.put("soa_primary_ns", stringOrEmpty(zone.get(DnsZoneModel.SOA_PRIMARY_NS)));
        entry.put("soa_contact", stringOrEmpty(zone.get(DnsZoneModel.SOA_CONTACT)));
        entry.put("default_ttl", DnsZoneModel.defaultTtlOf(zone));
        entry.put("delegation_status", stringOrEmpty(zone.get(DnsZoneModel.DELEGATION_STATUS)));
        entry.put("nameservers", apexNameservers(zoneId));
        entry.put("record_count", Models.get(DnsRecordModel.class).find()
            .where(DnsRecordModel.ZONE_ID.eq(zoneId)).count());
        return entry;
    }

    /** The zone's enabled apex NS targets, in row order. */
    static @NonNull List<String> apexNameservers(int zoneId) {
        List<String> names = new ArrayList<>();
        for (Row row : Models.get(DnsRecordModel.class).find()
                .where(DnsRecordModel.ZONE_ID.eq(zoneId))
                .where(DnsRecordModel.NAME.eq(DnsNames.APEX))
                .where(DnsRecordModel.TYPE.eq(DnsRecordModel.TYPE_NS))
                .where(DnsRecordModel.ENABLED.eq(true))
                .orderBy(DnsRecordModel.ID, SortOrder.ASC).all()) {
            names.add(row.get(DnsRecordModel.VALUE));
        }
        return names;
    }

    private static @NonNull String stringOrEmpty(@Nullable Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}
