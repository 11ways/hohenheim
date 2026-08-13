package be.elevenways.hohenheim.model;

import be.elevenways.hohenheim.HohenheimFormCopy;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.field.*;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.Schema;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

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

    public static final Identifier MODEL_ID = Identifier.of("hohenheim", "dns_record");
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

    public static final List<String> ALL_TYPES = List.of(
        TYPE_A, TYPE_AAAA, TYPE_CNAME, TYPE_NS, TYPE_MX, TYPE_TXT, TYPE_CAA, TYPE_SRV);

    // --- Per-type sub-schemas (the ONLY home for type-specific fields) ---

    public static final Schema MX_DATA_SCHEMA = new Schema();
    public static final IntegerField MX_PRIORITY = MX_DATA_SCHEMA.addField(
        IntegerField.builder().name("priority")
            .label(HohenheimFormCopy.label("record_priority"))
            .help(HohenheimFormCopy.help("record_priority")).build());

    public static final Schema SRV_DATA_SCHEMA = new Schema();
    public static final IntegerField SRV_PRIORITY = SRV_DATA_SCHEMA.addField(
        IntegerField.builder().name("priority")
            .label(HohenheimFormCopy.label("record_priority"))
            .help(HohenheimFormCopy.help("record_priority")).build());
    public static final IntegerField SRV_WEIGHT = SRV_DATA_SCHEMA.addField(
        IntegerField.builder().name("weight")
            .label(HohenheimFormCopy.label("record_weight"))
            .help(HohenheimFormCopy.help("record_weight")).build());
    public static final IntegerField SRV_PORT = SRV_DATA_SCHEMA.addField(
        IntegerField.builder().name("port")
            .label(HohenheimFormCopy.label("record_port"))
            .help(HohenheimFormCopy.help("record_port")).build());

    public static final IntegerField ID = SCHEMA.addField(IntegerField.builder().name("id").build());
    public static final IntegerField ZONE_ID = SCHEMA.addField(IntegerField.builder().name("zone_id")
        .label(HohenheimFormCopy.label("record_zone")).help(HohenheimFormCopy.help("record_zone")).build());
    public static final StringField NAME = SCHEMA.addField(StringField.builder().name("name")
        .label(HohenheimFormCopy.label("record_name")).help(HohenheimFormCopy.help("record_name")).build());
    public static final EnumField TYPE = SCHEMA.addField(EnumField.builder("type")
        .label(HohenheimFormCopy.label("record_type")).help(HohenheimFormCopy.help("record_type"))
        .value(TYPE_A, v -> v.displayName("A").icon("location-dot").color("blue"))
        .value(TYPE_AAAA, v -> v.displayName("AAAA").icon("location-dot").color("indigo"))
        .value(TYPE_CNAME, v -> v.displayName("CNAME").icon("link").color("purple"))
        .value(TYPE_NS, v -> v.displayName("NS").icon("server").color("orange"))
        .value(TYPE_MX, v -> v.displayName("MX").icon("envelope").color("green").schema(MX_DATA_SCHEMA))
        .value(TYPE_TXT, v -> v.displayName("TXT").icon("quote-left").color("gray"))
        .value(TYPE_CAA, v -> v.displayName("CAA").icon("certificate").color("teal"))
        .value(TYPE_SRV, v -> v.displayName("SRV").icon("network-wired").color("pink").schema(SRV_DATA_SCHEMA))
        .build());
    public static final IntegerField TTL = SCHEMA.addField(IntegerField.builder().name("ttl")
        .suffix("s").label(HohenheimFormCopy.label("record_ttl")).help(HohenheimFormCopy.help("record_ttl")).build());
    public static final StringField VALUE = SCHEMA.addField(StringField.builder().name("value")
        .label(HohenheimFormCopy.label("record_value")).help(HohenheimFormCopy.help("record_value")).build());

    /** Type-specific RDATA extras, shaped by the sub-schema the record's TYPE declares. */
    public static final SchemaField DATA = SCHEMA.addField(SchemaField.builder("data")
        .schemaFrom("type")
        .label(HohenheimFormCopy.label("record_data")).build());

    public static final BooleanField ENABLED = SCHEMA.addField(BooleanField.builder("enabled").defaultValue(true)
        .label(HohenheimFormCopy.label("record_enabled")).help(HohenheimFormCopy.help("record_enabled")).build());
    public static final StringField MANAGED_BY = SCHEMA.addField(StringField.builder().name("managed_by")
        .label(HohenheimFormCopy.label("managed_by")).build());

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

    /** @return the integer under {@code key} when {@code data} is a map carrying one, else null */
    public static @Nullable Integer dataInt(@Nullable Object data, @NonNull String key) {
        if (!(data instanceof Map<?, ?> map)) {
            return null;
        }
        Object value = map.get(key);
        if (value instanceof Integer number) {
            return number;
        }
        if (value instanceof Number number) {
            return number.intValue();
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
