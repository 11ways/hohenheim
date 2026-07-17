package be.elevenways.hohenheim.model;

import be.elevenways.hohenheim.HohenheimFormCopy;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.field.*;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.Schema;

import java.util.List;

/**
 * One authoritative DNS resource record; rows sharing zone+name+type form one RRset.
 * Owner names are stored relative to the zone origin ("@" = apex, "*" = wildcard).
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

    public static final java.util.List<String> ALL_TYPES = java.util.List.of(
        TYPE_A, TYPE_AAAA, TYPE_CNAME, TYPE_NS, TYPE_MX, TYPE_TXT, TYPE_CAA, TYPE_SRV);

    public static final IntegerField ID = SCHEMA.addField(IntegerField.builder().name("id").build());
    public static final IntegerField ZONE_ID = SCHEMA.addField(IntegerField.builder().name("zone_id")
        .label(HohenheimFormCopy.label("record_zone")).build());
    public static final StringField NAME = SCHEMA.addField(StringField.builder().name("name")
        .label(HohenheimFormCopy.label("record_name")).help(HohenheimFormCopy.help("record_name")).build());
    public static final EnumField TYPE = SCHEMA.addField(EnumField.builder("type")
        .label(HohenheimFormCopy.label("record_type")).help(HohenheimFormCopy.help("record_type"))
        .value(TYPE_A, v -> v.displayName("A").icon("location-dot").color("blue"))
        .value(TYPE_AAAA, v -> v.displayName("AAAA").icon("location-dot").color("indigo"))
        .value(TYPE_CNAME, v -> v.displayName("CNAME").icon("link").color("purple"))
        .value(TYPE_NS, v -> v.displayName("NS").icon("server").color("orange"))
        .value(TYPE_MX, v -> v.displayName("MX").icon("envelope").color("green"))
        .value(TYPE_TXT, v -> v.displayName("TXT").icon("quote-left").color("gray"))
        .value(TYPE_CAA, v -> v.displayName("CAA").icon("certificate").color("teal"))
        .value(TYPE_SRV, v -> v.displayName("SRV").icon("network-wired").color("pink"))
        .build());
    public static final IntegerField TTL = SCHEMA.addField(IntegerField.builder().name("ttl")
        .label(HohenheimFormCopy.label("record_ttl")).help(HohenheimFormCopy.help("record_ttl")).build());
    public static final StringField VALUE = SCHEMA.addField(StringField.builder().name("value")
        .label(HohenheimFormCopy.label("record_value")).help(HohenheimFormCopy.help("record_value")).build());
    public static final IntegerField PRIORITY = SCHEMA.addField(IntegerField.builder().name("priority")
        .label(HohenheimFormCopy.label("record_priority")).help(HohenheimFormCopy.help("record_priority")).build());
    public static final IntegerField WEIGHT = SCHEMA.addField(IntegerField.builder().name("weight")
        .label(HohenheimFormCopy.label("record_weight")).help(HohenheimFormCopy.help("record_weight")).build());
    public static final IntegerField PORT = SCHEMA.addField(IntegerField.builder().name("port")
        .label(HohenheimFormCopy.label("record_port")).help(HohenheimFormCopy.help("record_port")).build());
    public static final BooleanField ENABLED = SCHEMA.addField(BooleanField.builder("enabled").defaultValue(true)
        .label(HohenheimFormCopy.label("record_enabled")).help(HohenheimFormCopy.help("record_enabled")).build());
    public static final StringField MANAGED_BY = SCHEMA.addField(StringField.builder().name("managed_by")
        .label(HohenheimFormCopy.label("managed_by")).build());
    public static final DateTimeField CREATED_AT = SCHEMA.addField(DateTimeField.builder().name("created_at").build());
    public static final DateTimeField UPDATED_AT = SCHEMA.addField(DateTimeField.builder().name("updated_at").build());

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
