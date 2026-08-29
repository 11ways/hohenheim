package be.elevenways.hohenheim.server;

import be.elevenways.hohenheim.HohenheimEndpoints;
import be.elevenways.hohenheim.dns.DnsApiErrorResponse;
import be.elevenways.hohenheim.dns.DnsRecordDeleteResponse;
import be.elevenways.hohenheim.dns.DnsRecordDto;
import be.elevenways.hohenheim.dns.DnsRecordListResponse;
import be.elevenways.hohenheim.dns.DnsRecordMutationResponse;
import be.elevenways.hohenheim.dns.DnsValidationErrorResponse;
import be.elevenways.hohenheim.model.DnsRecordModel;
import be.elevenways.hohenheim.model.DnsZoneModel;
import be.elevenways.hohenheim.server.cms.DnsRecordEdits;
import be.elevenways.hohenheim.server.dns.DnsNames;
import be.elevenways.hohenheim.server.dns.DnsZoneStore;
import be.elevenways.zenit.auth.model.ApiKeyPrincipal;
import be.elevenways.zenit.common.conduit.Conduit;
import be.elevenways.zenit.common.conduit.ConduitAttributes;
import be.elevenways.zenit.common.orm.activity.ActivityLog;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.orm.query.SortOrder;
import be.elevenways.zenit.common.result.ActionResult;
import be.elevenways.zenit.common.result.JsonResult;
import be.elevenways.zenit.common.validation.Violation;
import be.elevenways.zenit.common.validation.Violations;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * DNS records peer/automation API: the edit-forwarding channel other Hohenheim
 * instances use to edit zones THIS instance owns (plus a plain automation API).
 * API-key principals only; primary zones only.
 */
final class DnsRecordApiHandlers {

    /**
     * The row columns the peer wire carries FLAT: a deliberate SUBSET of the model (no
     * zone_id, no managed_by, no generated_* -- those are the server's, not a caller's).
     */
    private static final List<String> COLUMN_FIELDS = List.of(
        DnsRecordModel.NAME.getName(), DnsRecordModel.TYPE.getName(),
        DnsRecordModel.VALUE.getName(), DnsRecordModel.TTL.getName(),
        DnsRecordModel.ENABLED.getName());

    /**
     * The record fields the wire carries, shared with the remote-record forwarding form.
     *
     * AIDEV-NOTE: the type-specific half is DERIVED from the model's per-type sub-schemas
     * ({@link DnsRecordModel#DATA_FIELD_NAMES}), never re-listed. Until 2026-08-17 this
     * spelled "priority", "weight", "port" here AND partitioned on the same three names
     * again in {@link #recordValues} -- so adding a field to the SRV schema needed two
     * edits in this file that nothing tied together, and forgetting either silently
     * dropped the field off the API instead of failing.
     */
    static final List<String> RECORD_FIELDS = recordFields();

    private static List<String> recordFields() {
        List<String> fields = new ArrayList<>(COLUMN_FIELDS);
        fields.addAll(DnsRecordModel.DATA_FIELD_NAMES);
        return List.copyOf(fields);
    }

    private DnsRecordApiHandlers() {
    }

    static void init() {
        DnsRecordModel model = Models.get(DnsRecordModel.class);

        HohenheimEndpoints.API_DNS_RECORDS.setHandler(conduit -> {
            Row zone = apiPrimaryZone(conduit);
            if (zone == null) {
                return null;
            }
            List<DnsRecordDto> records = new ArrayList<>();
            for (Row record : model.find()
                    .where(DnsRecordModel.ZONE_ID.eq(zone.get(DnsZoneModel.ID)))
                    .orderBy(DnsRecordModel.NAME, SortOrder.ASC)
                    .all()) {
                records.add(recordDto(record));
            }
            return new JsonResult<Object>(new DnsRecordListResponse(
                zone.get(DnsZoneModel.ORIGIN), zone.get(DnsZoneModel.SERIAL), records));
        });

        HohenheimEndpoints.API_DNS_RECORD_CREATE.setHandler(conduit -> {
            Row zone = apiPrimaryZone(conduit);
            if (zone == null) {
                return null;
            }
            int zoneId = zone.get(DnsZoneModel.ID);
            Map<String, Object> values = recordValues(HandlerSupport.formMap(conduit));
            values.put("zone_id", zoneId);
            try {
                DnsRecordEdits.validate(values, null, model);
            }
            catch (Violations violations) {
                return validationError(conduit, violations);
            }
            Row row = model.createEmptyRow();
            row.set(DnsRecordModel.ZONE_ID, zoneId);
            applyRecordValues(row, values);
            model.save(row);
            ActivityLog.record(model, row.get(DnsRecordModel.ID), "created", recordDetail(row));
            DnsZoneStore.INSTANCE.bumpSerialAndReload(zoneId);
            return new JsonResult<Object>(
                new DnsRecordMutationResponse(row.get(DnsRecordModel.ID)));
        });

        HohenheimEndpoints.API_DNS_RECORD_UPDATE.setHandler(conduit -> {
            Row zone = apiPrimaryZone(conduit);
            if (zone == null) {
                return null;
            }
            Row record = apiRecord(conduit, zone, model);
            if (record == null) {
                return null;
            }
            // Only submitted fields change; absent keys keep their stored value.
            Map<String, Object> values = recordValues(HandlerSupport.formMap(conduit));
            try {
                DnsRecordEdits.validate(values, record, model);
            }
            catch (Violations violations) {
                return validationError(conduit, violations);
            }
            applyRecordValues(record, values);
            model.save(record);
            ActivityLog.record(model, record.get(DnsRecordModel.ID), "updated",
                recordDetail(record));
            DnsZoneStore.INSTANCE.bumpSerialAndReload(zone.get(DnsZoneModel.ID));
            return new JsonResult<Object>(
                new DnsRecordMutationResponse(record.get(DnsRecordModel.ID)));
        });

        HohenheimEndpoints.API_DNS_RECORD_DELETE.setHandler(conduit -> {
            Row zone = apiPrimaryZone(conduit);
            if (zone == null) {
                return null;
            }
            Row record = apiRecord(conduit, zone, model);
            if (record == null) {
                return null;
            }
            Integer recordId = record.get(DnsRecordModel.ID);
            String detail = recordDetail(record);
            model.delete(record);
            ActivityLog.record(model, recordId, "deleted", detail);
            DnsZoneStore.INSTANCE.bumpSerialAndReload(zone.get(DnsZoneModel.ID));
            return new JsonResult<Object>(new DnsRecordDeleteResponse("deleted"));
        });
    }

    /** Activity detail naming the record an operator would recognise it by. */
    private static String recordDetail(Row record) {
        return record.get(DnsRecordModel.TYPE) + " " + record.get(DnsRecordModel.NAME);
    }

    private static DnsRecordDto recordDto(Row record) {
        // The peer wire stays FLAT (an external API contract); internally the
        // extras live in the type-schema'd data column.
        return new DnsRecordDto(
            record.get(DnsRecordModel.ID),
            record.get(DnsRecordModel.NAME),
            record.get(DnsRecordModel.TYPE),
            record.get(DnsRecordModel.TTL),
            record.get(DnsRecordModel.VALUE),
            DnsRecordModel.priorityOf(record),
            DnsRecordModel.weightOf(record),
            DnsRecordModel.portOf(record),
            Boolean.TRUE.equals(record.get(DnsRecordModel.ENABLED)),
            record.get(DnsRecordModel.MANAGED_BY));
    }

    /** Gate + zone resolution shared by every DNS api handler; null = response already written. */
    private static @org.checkerframework.checker.nullness.qual.Nullable Row apiPrimaryZone(Conduit conduit) {
        // AIDEV-NOTE: this method authenticates the KIND of principal (api key) and the
        // zone's primacy -- it does NOT scope which zones a key may edit. The only
        // authorization is the endpoints' requiresPermission("hohenheim.admin.access")
        // (HohenheimEndpoints API_DNS_*), which is therefore LOAD-BEARING: relax it, or
        // mint admin-permissioned keys for a narrower purpose, and every such key can
        // edit EVERY primary zone. API keys carry no per-zone scope today; adding one
        // means checking it here, not only on the endpoint.
        if (!(conduit.getAttribute(ConduitAttributes.PRINCIPAL) instanceof ApiKeyPrincipal)) {
            conduit.forbidden();
            return null;
        }
        String rawOrigin = conduit.getParameter(HohenheimEndpoints.DNS_ORIGIN);
        String origin = DnsNames.normalizeOrigin(rawOrigin != null ? rawOrigin : "");
        Row zone = origin != null ? Models.get(DnsZoneModel.class).findByOrigin(origin) : null;
        if (zone == null) {
            conduit.notFound();
            return null;
        }
        if (DnsZoneModel.ROLE_SECONDARY.equals(DnsZoneModel.roleOf(zone))) {
            // Edits belong on the owning instance; a replica must never fork.
            conduit.setResponseStatus(409);
            new JsonResult<Object>(new DnsApiErrorResponse("not_primary"))
                .finalizeConduit(conduit);
            return null;
        }
        return zone;
    }

    private static @org.checkerframework.checker.nullness.qual.Nullable Row apiRecord(
            Conduit conduit, Row zone, DnsRecordModel model) {
        Integer recordId = conduit.getParameter(HohenheimEndpoints.DNS_RECORD_ID);
        Row record = model.find()
            .where(DnsRecordModel.ID.eq(recordId))
            .where(DnsRecordModel.ZONE_ID.eq(zone.get(DnsZoneModel.ID)))
            .first();
        if (record == null) {
            conduit.notFound();
            return null;
        }
        return record;
    }

    /**
     * Submitted record fields as a validation map; absent keys fall back to the existing
     * row. The wire's flat priority/weight/port fold into the data sub-map the model
     * stores ({@code DnsRecordEdits.validate} normalizes it to the type's shape).
     */
    private static Map<String, Object> recordValues(Map<String, String> form) {
        Map<String, Object> values = new LinkedHashMap<>();
        Map<String, Object> data = new LinkedHashMap<>();
        for (String field : RECORD_FIELDS) {
            if (!form.containsKey(field)) {
                continue;
            }
            if (DnsRecordModel.DATA_FIELD_NAMES.contains(field)) {
                data.put(field, form.get(field));
            }
            else {
                values.put(field, form.get(field));
            }
        }
        if (!data.isEmpty()) {
            values.put("data", data);
        }
        return values;
    }

    private static void applyRecordValues(Row row, Map<String, Object> values) {
        if (values.containsKey("name")) {
            row.set(DnsRecordModel.NAME, String.valueOf(values.get("name")));
        }
        if (values.containsKey("type")) {
            row.set(DnsRecordModel.TYPE, String.valueOf(values.get("type")));
        }
        if (values.containsKey("value")) {
            row.set(DnsRecordModel.VALUE, String.valueOf(values.get("value")));
        }
        if (values.containsKey("ttl")) {
            row.set(DnsRecordModel.TTL, DnsRecordEdits.intOrNull(values.get("ttl")));
        }
        // validate() always leaves the normalized per-type data map behind.
        row.set(DnsRecordModel.DATA, values.get("data"));
        if (values.containsKey("enabled")) {
            row.set(DnsRecordModel.ENABLED, Boolean.parseBoolean(String.valueOf(values.get("enabled"))));
        }
        else if (row.get(DnsRecordModel.ENABLED) == null) {
            row.set(DnsRecordModel.ENABLED, true);
        }
    }

    private static ActionResult<Object> validationError(Conduit conduit, Violations violations) {
        Violation first = violations.all().isEmpty() ? null : violations.all().get(0);
        conduit.setResponseStatus(422);
        if (first == null) {
            return new JsonResult<Object>(new DnsApiErrorResponse("validation"));
        }
        return new JsonResult<Object>(new DnsValidationErrorResponse(
            "validation",
            first.fieldName(),
            first.message().key(),
            first.message().resolve(conduit.getLocales(), conduit.getMessageResolver())));
    }
}
