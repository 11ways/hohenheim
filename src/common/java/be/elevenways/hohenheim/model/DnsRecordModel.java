package be.elevenways.hohenheim.model;

import be.elevenways.hohenheim.RawValues;
import be.elevenways.hohenheim.HohenheimIds;
import be.elevenways.hohenheim.HohenheimMicrocopy;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.field.*;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.Schema;
import be.elevenways.zenit.common.orm.model.relation.BelongsTo;
import be.elevenways.zenit.common.ui.ColorHue;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One authoritative DNS resource record; rows sharing zone+name+type form one RRset.
 * Owner names are stored relative to the zone origin ("@" = apex, "*" = wildcard).
 *
 * AIDEV-NOTE: which fields a record carries FOLLOWS FROM ITS TYPE, declared once on
 * the {@link #TYPE} enum values: MX and SRV attach a per-type sub-schema that lives in
 * the {@link #DATA} column ({@code schemaFrom}), every other type carries none. There
 * are deliberately NO flat priority/weight/port columns (M091 dropped them) -- a new
 * type-specific field goes into that type's sub-schema, never into a column every
 * other type must carry. Dynamic-DNS state lives in its own table
 * ({@link DnsDyndnsCredentialModel}), not on this row.
 */
public class DnsRecordModel extends Model {

    public static final Identifier MODEL_ID = HohenheimIds.id("dns_record");
    public static final Schema SCHEMA = new Schema();

    public static final String TYPE_A = "A";
    public static final String TYPE_AAAA = "AAAA";
    public static final String TYPE_CNAME = "CNAME";
    public static final String TYPE_NS = "NS";
    public static final String TYPE_MX = "MX";
    public static final String TYPE_TXT = "TXT";
    public static final String TYPE_CAA = "CAA";
    public static final String TYPE_SRV = "SRV";

    /** {@link #MANAGED_BY} value for records the ACME DNS-01 flow owns. */
    public static final String MANAGED_BY_ACME = "acme";

    /**
     * The closed set of {@link #MANAGED_BY} values a machine may claim; null (an operator
     * authored the row) is the only other legal state. THE declaring home: the peer API
     * refuses anything outside it, so a stranger token can never be written over the wire.
     */
    public static final List<String> MANAGED_BY_VALUES = List.of(MANAGED_BY_ACME);

    // --- Per-type sub-schemas (the ONLY home for type-specific fields) ---

    public static final Schema MX_DATA_SCHEMA = new Schema();
    public static final IntegerField MX_PRIORITY = MX_DATA_SCHEMA.addField(
        IntegerField.builder().name("priority")
            .label(HohenheimMicrocopy.HOHENHEIM_FIELD.of("record_priority"))
            .help(HohenheimMicrocopy.HELP.of("record_priority")).build());

    public static final Schema SRV_DATA_SCHEMA = new Schema();
    public static final IntegerField SRV_PRIORITY = SRV_DATA_SCHEMA.addField(
        IntegerField.builder().name("priority")
            .label(HohenheimMicrocopy.HOHENHEIM_FIELD.of("record_priority"))
            .help(HohenheimMicrocopy.HELP.of("record_priority")).build());
    public static final IntegerField SRV_WEIGHT = SRV_DATA_SCHEMA.addField(
        IntegerField.builder().name("weight")
            .label(HohenheimMicrocopy.HOHENHEIM_FIELD.of("record_weight"))
            .help(HohenheimMicrocopy.HELP.of("record_weight")).build());
    public static final IntegerField SRV_PORT = SRV_DATA_SCHEMA.addField(
        IntegerField.builder().name("port")
            .label(HohenheimMicrocopy.HOHENHEIM_FIELD.of("record_port"))
            .help(HohenheimMicrocopy.HELP.of("record_port")).build());

    public static final IntegerField ID = SCHEMA.addField(IntegerField.builder().name("id").build());
    public static final IntegerField ZONE_ID = SCHEMA.addField(IntegerField.builder().name("zone_id")
        .label(HohenheimMicrocopy.HOHENHEIM_FIELD.of("record_zone")).help(HohenheimMicrocopy.HELP.of("record_zone"))
        .build());
    /** The owning zone; a record dies with its zone (the delete cascade asks through this). */
    public static final BelongsTo<DnsZoneModel> ZONE = SCHEMA.addRelation(
        BelongsTo.to(DnsZoneModel.class)
            .name("zone")
            .localKey(ZONE_ID)
            .remoteKey(DnsZoneModel.ID)
            .build());
    public static final StringField NAME = SCHEMA.addField(StringField.builder().name("name")
        .label(HohenheimMicrocopy.HOHENHEIM_FIELD.of("record_name")).help(HohenheimMicrocopy.HELP.of("record_name"))
        .build());
    public static final EnumField TYPE = SCHEMA.addField(EnumField.builder("type")
        .label(HohenheimMicrocopy.HOHENHEIM_FIELD.of("record_type")).help(HohenheimMicrocopy.HELP.of("record_type"))
        .value(TYPE_A, v -> v.displayName("A").label(HohenheimMicrocopy.DNS_RECORD_TYPE.of("a")).icon("location-dot")
            .color(ColorHue.BLUE))
        .value(TYPE_AAAA, v -> v.displayName("AAAA").label(HohenheimMicrocopy.DNS_RECORD_TYPE.of("aaaa"))
            .icon("location-dot")
            .color(ColorHue.INDIGO))
        .value(TYPE_CNAME, v -> v.displayName("CNAME").label(HohenheimMicrocopy.DNS_RECORD_TYPE.of("cname"))
            .icon("link").color(ColorHue.PURPLE))
        .value(TYPE_NS, v -> v.displayName("NS").label(HohenheimMicrocopy.DNS_RECORD_TYPE.of("ns")).icon("server")
            .color(ColorHue.ORANGE))
        .value(TYPE_MX, v -> v.displayName("MX").label(HohenheimMicrocopy.DNS_RECORD_TYPE.of("mx")).icon("envelope")
            .color(ColorHue.GREEN)
            .schema(MX_DATA_SCHEMA))
        .value(TYPE_TXT, v -> v.displayName("TXT").label(HohenheimMicrocopy.DNS_RECORD_TYPE.of("txt"))
            .icon("quote-left").color(ColorHue.GRAY))
        .value(TYPE_CAA, v -> v.displayName("CAA").label(HohenheimMicrocopy.DNS_RECORD_TYPE.of("caa"))
            .icon("certificate").color(ColorHue.TEAL))
        .value(TYPE_SRV, v -> v.displayName("SRV").label(HohenheimMicrocopy.DNS_RECORD_TYPE.of("srv"))
            .icon("network-wired").color(ColorHue.PINK)
            .schema(SRV_DATA_SCHEMA))
        .build());
    /**
     * THE record-type vocabulary, DERIVED from {@link #TYPE}'s declared values rather than
     * re-listed beside them.
     *
     * AIDEV-NOTE: this was a hand-written {@code List.of(TYPE_A, ...)} above the enum until
     * 2026-08-17, i.e. the same eight names written twice, three lines apart, with nothing
     * that noticed when they disagreed. A new type is now ONE edit -- add the
     * {@code .value(...)} and everything reading this list follows. Declaration order is
     * preserved (EnumField keeps a LinkedHashMap), so display order is unchanged.
     */
    public static final List<String> ALL_TYPES = List.copyOf(TYPE.getValues().keySet());

    /**
     * Every field name any type's sub-schema declares (today: priority, weight, port),
     * derived from the schemas themselves -- what the peer wire has to carry FLAT.
     */
    public static final List<String> DATA_FIELD_NAMES = dataFieldNames();

    public static final IntegerField TTL = SCHEMA.addField(IntegerField.builder().name("ttl")
        .suffix("s").label(HohenheimMicrocopy.HOHENHEIM_FIELD.of("record_ttl"))
        .help(HohenheimMicrocopy.HELP.of("record_ttl")).build());
    public static final StringField VALUE = SCHEMA.addField(StringField.builder().name("value")
        .label(HohenheimMicrocopy.HOHENHEIM_FIELD.of("record_value")).help(HohenheimMicrocopy.HELP.of("record_value"))
        .build());

    /** Type-specific RDATA extras, shaped by the sub-schema the record's TYPE declares. */
    public static final SchemaField DATA = SCHEMA.addField(SchemaField.builder("data")
        .schemaFrom("type")
        .label(HohenheimMicrocopy.HOHENHEIM_FIELD.of("record_data")).build());

    public static final BooleanField ENABLED = SCHEMA.addField(BooleanField.builder("enabled").defaultValue(true)
        .label(HohenheimMicrocopy.HOHENHEIM_FIELD.of("record_enabled"))
        .help(HohenheimMicrocopy.HELP.of("record_enabled")).build());
    public static final StringField MANAGED_BY = SCHEMA.addField(StringField.builder().name("managed_by")
        .label(HohenheimMicrocopy.HOHENHEIM_FIELD.of("managed_by")).build());

    /**
     * Which system authored this row, or null when an operator did.
     *
     * AIDEV-NOTE: this is the ENFORCEMENT column, distinct from {@link #MANAGED_BY} on
     * purpose. managed_by carries zone-file-import semantics (DnsZoneFiles replaces only
     * rows where it is NULL) and is a bare opaque string nothing ever refused, so a
     * hand-written {@code managed_by = "acme"} was indistinguishable from a real one. These
     * four columns are DERIVED in the write pipeline by GeneratedDnsRecords and refused
     * outright when a caller supplies them.
     */
    public static final StringField GENERATED_BY = SCHEMA.addField(
        StringField.builder().name("generated_by").filterable(false).build());

    /** Model id of the record that authorized this row; the reclaim doctrine's anchor. */
    public static final StringField GENERATED_FOR_MODEL = SCHEMA.addField(
        StringField.builder().name("generated_for_model").filterable(false).build());

    /** Primary key of the declaring record inside {@link #GENERATED_FOR_MODEL}. */
    public static final IntegerField GENERATED_FOR_ID = SCHEMA.addField(
        IntegerField.builder().name("generated_for_id").build());

    public static final DateTimeField GENERATED_AT = SCHEMA.addField(
        DateTimeField.builder().name("generated_at").build());

    public static final DateTimeField CREATED_AT = SCHEMA.addField(DateTimeField.builder().name("created_at").build());
    public static final DateTimeField UPDATED_AT = SCHEMA.addField(DateTimeField.builder().name("updated_at").build());

    /** Union of every per-type sub-schema's field names, in type then declaration order. */
    private static List<String> dataFieldNames() {
        List<String> names = new ArrayList<>();
        for (EnumField.EnumValue value : TYPE.getValues().values()) {
            Schema schema = value.getSchema();
            if (schema == null) {
                continue;
            }
            for (String field : schema.getFields().keySet()) {
                if (!names.contains(field)) {
                    names.add(field);
                }
            }
        }
        return List.copyOf(names);
    }

    /** @return true when the type is an address record (the only types dynamic DNS applies to) */
    public static boolean isAddressType(@Nullable String type) {
        return TYPE_A.equals(type) || TYPE_AAAA.equals(type);
    }

    /** @return the MX/SRV priority carried in {@link #DATA}, or null */
    public static @Nullable Integer priorityOf(@NonNull Row row) {
        return dataInt(row.get(DATA), "priority");
    }

    /** @return the SRV weight carried in {@link #DATA}, or null */
    public static @Nullable Integer weightOf(@NonNull Row row) {
        return dataInt(row.get(DATA), "weight");
    }

    /** @return the SRV port carried in {@link #DATA}, or null */
    public static @Nullable Integer portOf(@NonNull Row row) {
        return dataInt(row.get(DATA), "port");
    }

    /**
     * The record's rdata as a resolver prints it: the extras the type's sub-schema
     * declares lead the value, so five MX rows pointing at Google are told apart in a
     * listing instead of reading as five identical lines.
     *
     * AIDEV-NOTE: THE one home of that presentation. The local zone list (DnsRecordResource
     * cell) and a secondary's remote listing (DnsZoneRecordsPage) both render through it --
     * the remote lane used to carry its own copy of the MX/SRV spelling. It is presentation
     * ONLY: the stored VALUE column keeps the bare target, which is what the codec, the
     * filters and the inline editor speak.
     *
     * @return the value with its type's extras ahead of it, never null
     */
    public static @NonNull String presentationValue(@Nullable String type, @Nullable String value,
                                                    @Nullable Integer priority,
                                                    @Nullable Integer weight,
                                                    @Nullable Integer port) {
        String target = value != null ? value : "";
        if (TYPE_MX.equals(type)) {
            return priority != null ? priority + " " + target : target;
        }
        if (TYPE_SRV.equals(type)) {
            // A missing SRV number is a zero to every resolver, so it prints as one.
            return zeroIfNull(priority) + " " + zeroIfNull(weight) + " " + zeroIfNull(port)
                + " " + target;
        }
        return target;
    }

    /** @return {@link #presentationValue} for a stored row */
    public static @NonNull String presentationValue(@NonNull Row row) {
        return presentationValue(row.get(TYPE), row.get(VALUE),
            priorityOf(row), weightOf(row), portOf(row));
    }

    private static int zeroIfNull(@Nullable Integer value) {
        return value != null ? value : 0;
    }

    /** @return the integer under {@code key} when {@code data} is a map carrying one, else null */
    public static @Nullable Integer dataInt(@Nullable Object data, @NonNull String key) {
        if (!(data instanceof Map<?, ?> map)) {
            return null;
        }
        return RawValues.parsedInt(map.get(key));
    }

    /**
     * Shape the {@link #DATA} value for a type: exactly the keys that type's sub-schema
     * declares, or null for types that carry none.
     */
    public static @Nullable Map<String, Object> dataFor(@Nullable String type,
                                                        @Nullable Integer priority,
                                                        @Nullable Integer weight,
                                                        @Nullable Integer port) {
        if (TYPE_MX.equals(type)) {
            Map<String, Object> data = new LinkedHashMap<>();
            if (priority != null) {
                data.put("priority", priority);
            }
            return data.isEmpty() ? null : data;
        }
        if (TYPE_SRV.equals(type)) {
            Map<String, Object> data = new LinkedHashMap<>();
            if (priority != null) {
                data.put("priority", priority);
            }
            if (weight != null) {
                data.put("weight", weight);
            }
            if (port != null) {
                data.put("port", port);
            }
            return data.isEmpty() ? null : data;
        }
        return null;
    }

    public List<Row> findByZoneId(int zoneId) {
        return find().where(ZONE_ID.eq(zoneId)).all();
    }

    public List<Row> findEnabledByZoneId(int zoneId) {
        return find().where(ZONE_ID.eq(zoneId)).and(ENABLED.eq(true)).all();
    }

    @Override
    public Identifier getModelId() { return MODEL_ID; }

    @Override
    public Field<?, ?> getPrimaryKeyField() { return ID; }

    @Override
    public String getModelName() { return "DnsRecord"; }

    @Override
    public String getTableName() { return "dns_records"; }

    @Override
    public Schema getSchema() { return SCHEMA; }
}
