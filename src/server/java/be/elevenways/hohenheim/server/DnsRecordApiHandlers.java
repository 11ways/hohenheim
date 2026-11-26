package be.elevenways.hohenheim.server;

import be.elevenways.hohenheim.RawValues;
import be.elevenways.hohenheim.HohenheimEndpoints;
import be.elevenways.hohenheim.HohenheimMicrocopy;
import be.elevenways.hohenheim.server.api.ApiConduits;
import be.elevenways.hohenheim.dns.DnsApiErrorResponse;
import be.elevenways.hohenheim.dns.DnsRecordDeleteResponse;
import be.elevenways.hohenheim.dns.DnsRecordDto;
import be.elevenways.hohenheim.dns.DnsRecordListResponse;
import be.elevenways.hohenheim.dns.DnsRecordMutationResponse;
import be.elevenways.hohenheim.dns.DnsRecordSetResponse;
import be.elevenways.hohenheim.dns.DnsValidationErrorResponse;
import be.elevenways.hohenheim.model.DnsRecordModel;
import be.elevenways.hohenheim.model.DnsZoneModel;
import be.elevenways.hohenheim.server.cms.CmsSupport;
import be.elevenways.hohenheim.server.cms.DnsRecordEdits;
import be.elevenways.hohenheim.server.cms.DnsRecordSets;
import be.elevenways.hohenheim.server.cms.TenantScopes;
import be.elevenways.hohenheim.server.dns.DnsNames;
import be.elevenways.hohenheim.server.dns.DnsPeerApi;
import be.elevenways.hohenheim.server.dns.DnsZoneMoves;
import be.elevenways.hohenheim.server.dns.DnsZoneStore;
import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.zenit.common.Zenit;
import be.elevenways.zenit.common.conduit.Conduit;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.orm.query.SortOrder;
import be.elevenways.zenit.common.orm.query.criteria.Criteria;
import be.elevenways.zenit.common.result.ActionResult;
import be.elevenways.zenit.common.result.JsonResult;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.common.validation.Violation;
import be.elevenways.zenit.common.validation.Violations;
import be.elevenways.zenit.common.routing.Endpoint;
import be.elevenways.zenit.server.http.RateLimitPolicies;
import be.elevenways.zenit.server.http.RateLimitPolicyResolver;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * DNS records peer/automation API: the edit-forwarding channel other Hohenheim
 * instances use to edit zones THIS instance owns, and the record API of any key whose
 * owner may author records. API-key principals only; primary zones only.
 *
 * AIDEV-NOTE: there is no authorization rule in this class. An admin key (a peer, an
 * operator's automation) passes as an operator does; every other key's write is judged by
 * the {@code TenantWrites} record hooks on the model save and delete, the gate the /manage
 * surface rides (hostname authority or a per-record edit grant, the tenant type allow-list,
 * managed_by and zone frozen). What this class adds for such a key is READ scoping only:
 * {@link TenantScopes#reachesZone} and {@link TenantScopes#DNS_RECORDS}, so an unknown zone, an
 * unreached zone, a missing record and another owner's record all answer the one 404.
 */
final class DnsRecordApiHandlers {

    /**
     * Lifts {@link HohenheimEndpoints#DNS_RECORD_API_LIMIT} for an admin key, which is every DNS peer's: a peer
     * forwarding an operator's edits or an ACME challenge is never throttled, every other key spends its own budget.
     */
    private static final RateLimitPolicyResolver ADMIN_KEYS_UNLIMITED = (conduit, endpoint, declared) ->
        ApiConduits.isApiKey(conduit) && HohenheimAccess.isAdmin(AccessContext.of(conduit)) ? null : declared;

    private DnsRecordApiHandlers() {
    }

    /** The zone a route names, as the calling key reaches it. */
    record ApiZone(@NonNull AccessContext access, @NonNull Row zone) {

        int id() {
            return this.zone.get(DnsZoneModel.ID);
        }

        /** @return the records of this zone the key may read, null for every record */
        @Nullable Criteria readable() {
            return TenantScopes.DNS_RECORDS.criteria(this.access);
        }
    }

    static void init() {
        for (Endpoint<?> route : List.of(HohenheimEndpoints.API_DNS_RECORDS, HohenheimEndpoints.API_DNS_RECORD_CREATE,
                HohenheimEndpoints.API_DNS_RECORD_UPDATE, HohenheimEndpoints.API_DNS_RECORD_DELETE)) {
            RateLimitPolicies.registry().add(route.getId(), DnsRecordApiHandlers.class, ADMIN_KEYS_UNLIMITED);
        }
        DnsRecordModel model = Models.get(DnsRecordModel.class);

        HohenheimEndpoints.API_DNS_RECORDS.setHandler(conduit -> {
            ApiZone zone = apiPrimaryZone(conduit);
            if (zone == null) {
                return null;
            }
            var query = model.find().where(DnsRecordModel.ZONE_ID.eq(zone.id()));
            Criteria readable = zone.readable();
            if (readable != null) {
                query.where(readable);
            }
            List<DnsRecordDto> records = new ArrayList<>();
            for (Row record : query.orderBy(DnsRecordModel.NAME, SortOrder.ASC).all()) {
                records.add(DnsPeerApi.recordDto(record));
            }
            return new JsonResult<Object>(new DnsRecordListResponse(
                zone.zone().get(DnsZoneModel.ORIGIN), zone.zone().get(DnsZoneModel.SERIAL), records));
        });

        HohenheimEndpoints.API_DNS_RECORD_CREATE.setHandler(conduit -> {
            ApiZone zone = apiPrimaryZone(conduit);
            if (zone == null) {
                return null;
            }
            int zoneId = zone.id();
            Map<String, String> form = HandlerSupport.formMap(conduit);
            Map<String, Object> values = recordValues(form);
            values.put("zone_id", zoneId);
            Row row = model.createEmptyRow();
            try {
                String managedBy = declaredManagedBy(form);
                DnsRecordEdits.validate(values, null, model);
                row.set(DnsRecordModel.ZONE_ID, zoneId);
                if (managedBy != null) {
                    row.set(DnsRecordModel.MANAGED_BY, managedBy);
                }
                applyRecordValues(row, values);
                model.save(row);
            }
            catch (Violations violations) {
                return validationError(conduit, violations);
            }
            DnsZoneStore.INSTANCE.bumpSerialAndReload(zoneId);
            return new JsonResult<Object>(
                new DnsRecordMutationResponse(row.get(DnsRecordModel.ID)));
        });

        HohenheimEndpoints.API_DNS_RECORD_UPDATE.setHandler(conduit -> {
            ApiZone zone = apiPrimaryZone(conduit);
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
                applyRecordValues(record, values);
                model.save(record);
            }
            catch (Violations violations) {
                return validationError(conduit, violations);
            }
            DnsZoneStore.INSTANCE.bumpSerialAndReload(zone.id());
            return new JsonResult<Object>(
                new DnsRecordMutationResponse(record.get(DnsRecordModel.ID)));
        });

        HohenheimEndpoints.API_DNS_RECORD_DELETE.setHandler(conduit -> {
            ApiZone zone = apiPrimaryZone(conduit);
            if (zone == null) {
                return null;
            }
            Row record = apiRecord(conduit, zone, model);
            if (record == null) {
                return null;
            }
            try {
                model.delete(record);
            }
            catch (Violations violations) {
                return validationError(conduit, violations);
            }
            DnsZoneStore.INSTANCE.bumpSerialAndReload(zone.id());
            return new JsonResult<Object>(new DnsRecordDeleteResponse("deleted"));
        });

        // A peer's whole record set for its copy of a zone this instance owns: the forwarded dns_record_set.
        HohenheimEndpoints.API_DNS_RECORDS_SET.setHandler(conduit -> {
            ApiZone zone = apiPrimaryZone(conduit);
            if (zone == null) {
                return null;
            }
            Map<String, String> form = HandlerSupport.formMap(conduit);
            String server = form.get(DnsPeerApi.SET_SERVER);
            if (server == null || server.isBlank()) {
                return validationError(conduit, Violations.ofField(DnsPeerApi.SET_SERVER, server,
                    HohenheimMicrocopy.VIOLATIONS.of("dns_forward_server_required")));
            }
            Object records;
            try {
                records = Zenit.DRY.parseJsonTree(form.getOrDefault(DnsPeerApi.SET_RECORDS, ""));
            }
            catch (RuntimeException unreadable) {
                records = null;
            }
            try {
                return new JsonResult<Object>(new DnsRecordSetResponse(zone.zone().get(DnsZoneModel.ORIGIN),
                    DnsRecordSets.applyForwarded(zone.zone(), records, zone.access(), server.trim(),
                        form.get(DnsPeerApi.SET_PERSON))));
            }
            catch (Violations violations) {
                return validationError(conduit, violations);
            }
        });

        // A zone's whole state for the server holding its copy, and its hand-over to that server: the move.
        HohenheimEndpoints.API_DNS_ZONE_STATE.setHandler(conduit -> {
            ApiZone zone = reachedZone(conduit);
            return zone == null ? null : new JsonResult<Object>(DnsZoneMoves.state(zone.zone()));
        });
        HohenheimEndpoints.API_DNS_ZONE_HAND_OVER.setHandler(conduit -> {
            ApiZone zone = apiPrimaryZone(conduit);
            if (zone == null) {
                return null;
            }
            Map<String, String> form = HandlerSupport.formMap(conduit);
            String keyName = form.get(DnsPeerApi.HAND_OVER_KEY);
            Integer from = RawValues.parsedInt(form.get(DnsPeerApi.HAND_OVER_FROM));
            Integer serial = RawValues.parsedInt(form.get(DnsPeerApi.HAND_OVER_SERIAL));
            String server = form.get(DnsPeerApi.SET_SERVER);
            if (keyName == null || keyName.isBlank() || from == null || serial == null || server == null
                    || server.isBlank()) {
                conduit.setResponseStatus(422);
                return new JsonResult<Object>(new DnsApiErrorResponse("validation"));
            }
            try {
                return new JsonResult<Object>(DnsZoneMoves.handOver(zone.zone(), keyName.trim(), from, serial,
                    server.trim()));
            } catch (DnsZoneMoves.Refused refused) {
                conduit.setResponseStatus(409);
                return new JsonResult<Object>(new DnsValidationErrorResponse("hand_over_refused", null, null,
                    refused.getMessage()));
            }
        });
    }

    /**
     * The machine ownership a CREATE may claim, or null when it claims none.
     *
     * AIDEV-NOTE: the ONE caller that claims any is a peer forwarding an ACME DNS-01
     * challenge ({@code InternalDnsTxtPublisher}) -- the row it writes here IS
     * machine-owned, and without the stamp a zone-file import on this primary would
     * replace it as if an operator had typed it. It stays a CLOSED vocabulary and fails
     * closed: an unknown token is refused, never stored, so managed_by cannot become a
     * free-text field a caller invents meanings in.
     *
     * @throws Violations when the submitted value is outside {@link DnsRecordModel#MANAGED_BY_VALUES}
     */
    private static @org.checkerframework.checker.nullness.qual.Nullable String declaredManagedBy(
            Map<String, String> form) {
        String submitted = form.get(DnsRecordModel.MANAGED_BY.getName());
        if (submitted == null || submitted.isBlank()) {
            return null;
        }
        String value = submitted.trim();
        if (!DnsRecordModel.MANAGED_BY_VALUES.contains(value)) {
            throw Violations.ofField(DnsRecordModel.MANAGED_BY.getName(), value,
                HohenheimMicrocopy.VIOLATIONS.of("dns_managed_by_unknown"));
        }
        return value;
    }

    /**
     * Key + zone resolution shared by every DNS api handler; null = response already written.
     *
     * AIDEV-NOTE: the reach test runs BEFORE the role test, so a replica a key does not reach is the
     * same 404 as an unknown origin rather than a 409 naming it a replica.
     */
    private static @Nullable ApiZone apiPrimaryZone(@NonNull Conduit conduit) {
        ApiZone zone = reachedZone(conduit);
        if (zone == null) {
            return null;
        }
        if (DnsZoneModel.ROLE_SECONDARY.equals(DnsZoneModel.roleOf(zone.zone()))) {
            // Edits belong on the owning instance; a replica must never fork.
            conduit.setResponseStatus(409);
            new JsonResult<Object>(new DnsApiErrorResponse("not_primary"))
                .finalizeConduit(conduit);
            return null;
        }
        return zone;
    }

    /** @return the route's zone of either role as the calling key reaches it; null = the 404 is written */
    private static @Nullable ApiZone reachedZone(@NonNull Conduit conduit) {
        AccessContext access = ApiConduits.requireKey(conduit);
        if (access == null) {
            return null;
        }
        String rawOrigin = conduit.getParameter(HohenheimEndpoints.DNS_ORIGIN);
        String origin = DnsNames.normalizeOrigin(rawOrigin != null ? rawOrigin : "");
        Row zone = origin != null ? Models.get(DnsZoneModel.class).findByOrigin(origin) : null;
        if (zone == null || !TenantScopes.reachesZone(access, zone)) {
            conduit.notFound();
            return null;
        }
        return new ApiZone(access, zone);
    }

    /** The route's record inside the zone, as the key may read it; null = the 404 is written. */
    private static @Nullable Row apiRecord(@NonNull Conduit conduit, @NonNull ApiZone zone,
                                           @NonNull DnsRecordModel model) {
        Integer recordId = conduit.getParameter(HohenheimEndpoints.DNS_RECORD_ID);
        var query = model.find()
            .where(DnsRecordModel.ID.eq(recordId))
            .where(DnsRecordModel.ZONE_ID.eq(zone.id()));
        Criteria readable = zone.readable();
        if (readable != null) {
            query.where(readable);
        }
        Row record = query.first();
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
        for (String field : DnsPeerApi.RECORD_FIELDS) {
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
            row.set(DnsRecordModel.TTL, RawValues.parsedInt(values.get("ttl")));
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
            first.message().asString(conduit)));
    }
}
