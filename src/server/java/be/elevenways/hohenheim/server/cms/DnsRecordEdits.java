package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.model.DnsRecordModel;
import be.elevenways.hohenheim.model.DnsZoneModel;
import be.elevenways.hohenheim.server.dns.DnsNames;
import be.elevenways.hohenheim.server.dns.DnsRecordCodec;
import be.elevenways.hohenheim.server.dns.DnsValueException;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.validation.Violations;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.xbill.DNS.Name;
import org.xbill.DNS.TextParseException;

import java.util.List;
import java.util.Map;

/**
 * The one validation pipeline for zone-record edits, shared by the CMS
 * resource and the peer/automation API: a record that passes is a record the
 * codec can serve.
 *
 * @throws Violations with scope=violations microcopy on any refusal
 */
public final class DnsRecordEdits {

    private DnsRecordEdits() {}

    /** @return the validated zone id; normalizes {@code name} in place */
    public static int validate(@NonNull Map<String, Object> coerced, @Nullable Row existing,
                        @NonNull Model model) {
        Object zoneValue = coerced.containsKey("zone_id") ? coerced.get("zone_id")
            : existing != null ? existing.get(DnsRecordModel.ZONE_ID) : null;
        if (!(zoneValue instanceof Integer zoneId)) {
            throw Violations.ofField("zone_id", zoneValue, CmsSupport.violationText("dns_zone_required"));
        }
        Row zone = Models.get(DnsZoneModel.class).find().where(DnsZoneModel.ID.eq(zoneId)).first();
        if (zone == null) {
            throw Violations.ofField("zone_id", zoneId, CmsSupport.violationText("dns_zone_required"));
        }

        Object nameValue = coerced.containsKey("name") ? coerced.get("name")
            : existing != null ? existing.get(DnsRecordModel.NAME) : "";
        String owner = DnsNames.normalizeOwner(nameValue != null ? String.valueOf(nameValue) : "");
        if (owner == null) {
            throw Violations.ofField("name", nameValue, CmsSupport.violationText("dns_name_format"));
        }
        coerced.put("name", owner);

        Object typeValue = coerced.containsKey("type") ? coerced.get("type")
            : existing != null ? existing.get(DnsRecordModel.TYPE) : null;
        String type = typeValue != null ? String.valueOf(typeValue) : "";

        Object valueValue = coerced.containsKey("value") ? coerced.get("value")
            : existing != null ? existing.get(DnsRecordModel.VALUE) : "";
        String value = valueValue != null ? String.valueOf(valueValue) : "";

        Integer ttl = intOrNull(coerced.containsKey("ttl") ? coerced.get("ttl")
            : existing != null ? existing.get(DnsRecordModel.TTL) : null);
        Integer priority = intOrNull(coerced.containsKey("priority") ? coerced.get("priority")
            : existing != null ? existing.get(DnsRecordModel.PRIORITY) : null);
        Integer weight = intOrNull(coerced.containsKey("weight") ? coerced.get("weight")
            : existing != null ? existing.get(DnsRecordModel.WEIGHT) : null);
        Integer port = intOrNull(coerced.containsKey("port") ? coerced.get("port")
            : existing != null ? existing.get(DnsRecordModel.PORT) : null);

        String origin = zone.get(DnsZoneModel.ORIGIN);
        Integer zoneTtl = zone.get(DnsZoneModel.DEFAULT_TTL);
        try {
            Name originName = Name.fromString(origin + ".");
            long effectiveTtl = DnsRecordCodec.resolveTtl(ttl, zoneTtl != null ? zoneTtl : 3600);
            DnsRecordCodec.toRecord(originName, owner, type, effectiveTtl, value, priority, weight, port);
        }
        catch (TextParseException e) {
            throw Violations.ofField("name", owner, CmsSupport.violationText("dns_name_format"));
        }
        catch (DnsValueException e) {
            throw Violations.ofField(e.getField(), coerced.get(e.getField()),
                CmsSupport.violationText(e.getMicrocopyKey()));
        }

        Integer selfId = existing != null ? existing.get(DnsRecordModel.ID) : null;
        checkCnameExclusivity(model, zoneId, owner, type, selfId);
        checkDuplicate(model, zoneId, owner, type, value, selfId);

        return zoneId;
    }

    /**
     * A CNAME owner can hold nothing else, and nothing else can join a CNAME owner.
     *
     * AIDEV-NOTE: the apex refusal must NOT rely on the sibling scan below -- the apex SOA
     * (and DNSSEC apparatus) are SYNTHESIZED in DnsZoneStore.buildSnapshot and are not
     * rows, so a CNAME at "@" has no stored sibling to conflict with and used to pass
     * straight through while producing a zone whose apex answers both SOA and CNAME.
     */
    private static void checkCnameExclusivity(@NonNull Model model, int zoneId, @NonNull String owner,
                                              @NonNull String type, @Nullable Integer selfId) {
        if (DnsRecordModel.TYPE_CNAME.equals(type) && DnsNames.APEX.equals(owner)) {
            throw Violations.ofField("type", type, CmsSupport.violationText("dns_cname_exclusive"));
        }
        List<Row> siblings = model.find()
            .where(DnsRecordModel.ZONE_ID.eq(zoneId))
            .where(DnsRecordModel.NAME.eq(owner))
            .all();
        for (Row sibling : siblings) {
            if (selfId != null && selfId.equals(sibling.get(DnsRecordModel.ID))) {
                continue;
            }
            String siblingType = sibling.get(DnsRecordModel.TYPE);
            boolean conflict = DnsRecordModel.TYPE_CNAME.equals(type)
                || DnsRecordModel.TYPE_CNAME.equals(siblingType);
            if (conflict) {
                throw Violations.ofField("type", type, CmsSupport.violationText("dns_cname_exclusive"));
            }
        }
    }

    private static void checkDuplicate(@NonNull Model model, int zoneId, @NonNull String owner,
                                       @NonNull String type, @NonNull String value,
                                       @Nullable Integer selfId) {
        Row duplicate = model.find()
            .where(DnsRecordModel.ZONE_ID.eq(zoneId))
            .where(DnsRecordModel.NAME.eq(owner))
            .where(DnsRecordModel.TYPE.eq(type))
            .where(DnsRecordModel.VALUE.eq(value))
            .first();
        if (duplicate != null && (selfId == null || !selfId.equals(duplicate.get(DnsRecordModel.ID)))) {
            throw Violations.ofField("value", value, CmsSupport.violationText("dns_record_duplicate"));
        }
    }

    public static @Nullable Integer intOrNull(@Nullable Object value) {
        if (value instanceof Integer number) {
            return number;
        }
        if (value instanceof String text && !text.isBlank()) {
            try {
                return Integer.parseInt(text.trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }
}
