package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.model.DnsRecordModel;
import be.elevenways.hohenheim.model.DnsZoneModel;
import be.elevenways.hohenheim.server.dns.DnsNames;
import be.elevenways.hohenheim.server.dns.DnsRecordCodec;
import be.elevenways.hohenheim.server.dns.DnsValueException;
import be.elevenways.hohenheim.server.dns.DnsZoneStore;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.cms.common.panel.NavGroup;
import be.elevenways.zenit.cms.common.resource.RowResource;
import be.elevenways.zenit.cms.common.schema.ColumnSpec;
import be.elevenways.zenit.cms.common.schema.FilterSpec;
import be.elevenways.zenit.cms.common.schema.TableSpec;
import be.elevenways.zenit.common.conduit.Conduit;
import be.elevenways.zenit.common.edit.FieldFormEntryRegistry;
import be.elevenways.zenit.common.edit.FieldLabels;
import be.elevenways.zenit.common.edit.FormSpec;
import be.elevenways.zenit.common.edit.RelationPick;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.common.ui.Icon;
import be.elevenways.zenit.common.validation.Violations;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.xbill.DNS.Name;
import org.xbill.DNS.TextParseException;

import java.util.List;
import java.util.Map;

/**
 * Individual zone records. Hidden from the sidebar -- reached through a
 * zone's Records tab. A row that persists is a row the codec can serve,
 * so validation converts through {@link DnsRecordCodec}.
 */
public final class DnsRecordResource extends RowResource {

    private final FormSpec formSpec = FormSpec.builder()
        .add(RelationPick.of(DnsRecordModel.ZONE_ID, DnsZoneModel.MODEL_ID).build())
        .add(DnsRecordModel.NAME)
        .add(FieldFormEntryRegistry.INSTANCE.deriveEntry(DnsRecordModel.TYPE))
        .add(DnsRecordModel.VALUE)
        .add(DnsRecordModel.TTL)
        .add(DnsRecordModel.PRIORITY)
        .add(DnsRecordModel.WEIGHT)
        .add(DnsRecordModel.PORT)
        .add(DnsRecordModel.ENABLED)
        .build();

    private final TableSpec<Row> tableSpec = TableSpec.<Row>builder()
        .column(ColumnSpec.fromField(DnsRecordModel.NAME).filterable().build())
        .column(ColumnSpec.fromField(DnsRecordModel.TYPE).filterable().build())
        .column(ColumnSpec.fromField(DnsRecordModel.VALUE).filterable().build())
        .column(ColumnSpec.fromField(DnsRecordModel.ENABLED).filterable().build())
        .column(ColumnSpec.fromField(DnsRecordModel.ZONE_ID)
            .relation(RelationPick.of(DnsRecordModel.ZONE_ID, DnsZoneModel.MODEL_ID).build()).build())
        .filter(FilterSpec.forField(DnsRecordModel.NAME, FilterSpec.Kind.TEXT)
            .label(FieldLabels.labelFor(DnsRecordModel.NAME)).build())
        .filter(FilterSpec.forField(DnsRecordModel.TYPE, FilterSpec.Kind.SELECT)
            .label(FieldLabels.labelFor(DnsRecordModel.TYPE)).build())
        .filter(FilterSpec.forField(DnsRecordModel.ENABLED, FilterSpec.Kind.BOOLEAN)
            .label(FieldLabels.labelFor(DnsRecordModel.ENABLED)).build())
        .build();

    @Override public @NonNull Identifier id() { return Identifier.of("hohenheim", "dns_record"); }
    @Override public @NonNull Microcopy label() { return Microcopy.of("hohenheim.dns_record.plural"); }
    @Override public @NonNull String slug() { return "dns-records"; }
    @Override public @NonNull Model model() { return Models.get(DnsRecordModel.class); }
    @Override public @NonNull FormSpec formSpec() { return this.formSpec; }
    @Override public @NonNull TableSpec<Row> tableSpec() { return this.tableSpec; }
    @Override public @NonNull NavGroup navGroup() { return HohenheimPanel.INFRA_GROUP; }
    @Override public int navOrder() { return 31; }
    @Override public @NonNull Icon icon() { return Icon.of("list-ul"); }
    @Override public boolean showInNav() { return false; }

    /** The zone's Records tab links here with ?zone_id= so the pick is preselected. */
    @Override
    public @NonNull Map<String, Object> createValues(@NonNull Conduit conduit) {
        String zoneId = conduit.getQueryParam("zone_id");
        if (zoneId != null && !zoneId.isEmpty()) {
            try {
                return Map.of("zone_id", Integer.parseInt(zoneId));
            } catch (NumberFormatException ignored) {
                // Malformed prefill: render the bare form.
            }
        }
        return Map.of();
    }

    @Override
    public @NonNull Object persistRow(@NonNull Map<String, Object> coerced,
                                      @NonNull AccessContext accessContext) {
        Map<String, Object> values = CmsSupport.mutable(coerced);
        int zoneId = validate(values, null, this.model());
        Object id = super.persistRow(values, accessContext);
        DnsZoneStore.INSTANCE.bumpSerialAndReload(zoneId);
        return id;
    }

    @Override
    public void updateRow(@NonNull Row existing, @NonNull Map<String, Object> coerced,
                          @NonNull AccessContext accessContext) {
        Map<String, Object> values = CmsSupport.mutable(coerced);
        int zoneId = validate(values, existing, this.model());
        super.updateRow(existing, values, accessContext);
        Integer previousZone = existing.get(DnsRecordModel.ZONE_ID);
        if (previousZone != null && previousZone != zoneId) {
            DnsZoneStore.INSTANCE.bumpSerialAndReload(previousZone);
        }
        DnsZoneStore.INSTANCE.bumpSerialAndReload(zoneId);
    }

    @Override
    public void deleteRow(@NonNull Row existing, @NonNull AccessContext accessContext) {
        Integer zoneId = existing.get(DnsRecordModel.ZONE_ID);
        super.deleteRow(existing, accessContext);
        if (zoneId != null) {
            DnsZoneStore.INSTANCE.bumpSerialAndReload(zoneId);
        }
    }

    /** @return the validated zone id */
    private static int validate(@NonNull Map<String, Object> coerced, @Nullable Row existing,
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

    /** A CNAME owner can hold nothing else, and nothing else can join a CNAME owner. */
    private static void checkCnameExclusivity(@NonNull Model model, int zoneId, @NonNull String owner,
                                              @NonNull String type, @Nullable Integer selfId) {
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

    private static @Nullable Integer intOrNull(@Nullable Object value) {
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
