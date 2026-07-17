package be.elevenways.hohenheim.model;

import be.elevenways.hohenheim.HohenheimFormCopy;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.field.*;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.Schema;

import java.util.List;

/**
 * An authoritative DNS zone hosted by Hohenheim.
 * The origin is stored normalized: lowercase, no trailing dot.
 */
public class DnsZoneModel extends Model {

    public static final Identifier MODEL_ID = Identifier.of("hohenheim", "dns_zone");
    public static final Schema SCHEMA = new Schema();

    public static final IntegerField ID = SCHEMA.addField(IntegerField.builder().name("id").build());
    public static final StringField ORIGIN = SCHEMA.addField(StringField.builder().name("origin")
        .label(HohenheimFormCopy.label("origin")).help(HohenheimFormCopy.help("origin")).build());
    public static final StringField SOA_PRIMARY_NS = SCHEMA.addField(StringField.builder().name("soa_primary_ns")
        .label(HohenheimFormCopy.label("soa_primary_ns")).help(HohenheimFormCopy.help("soa_primary_ns")).build());
    public static final StringField SOA_CONTACT = SCHEMA.addField(StringField.builder().name("soa_contact")
        .label(HohenheimFormCopy.label("soa_contact")).help(HohenheimFormCopy.help("soa_contact")).build());
    public static final IntegerField SERIAL = SCHEMA.addField(IntegerField.builder().name("serial").defaultValue(1)
        .label(HohenheimFormCopy.label("serial")).build());
    public static final IntegerField DEFAULT_TTL = SCHEMA.addField(IntegerField.builder().name("default_ttl").defaultValue(3600)
        .label(HohenheimFormCopy.label("default_ttl")).help(HohenheimFormCopy.help("default_ttl")).build());
    public static final IntegerField NEGATIVE_TTL = SCHEMA.addField(IntegerField.builder().name("negative_ttl").defaultValue(300)
        .label(HohenheimFormCopy.label("negative_ttl")).help(HohenheimFormCopy.help("negative_ttl")).build());
    public static final IntegerField SOA_REFRESH = SCHEMA.addField(IntegerField.builder().name("soa_refresh").defaultValue(7200)
        .label(HohenheimFormCopy.label("soa_refresh")).help(HohenheimFormCopy.help("soa_refresh")).build());
    public static final IntegerField SOA_RETRY = SCHEMA.addField(IntegerField.builder().name("soa_retry").defaultValue(3600)
        .label(HohenheimFormCopy.label("soa_retry")).help(HohenheimFormCopy.help("soa_retry")).build());
    public static final IntegerField SOA_EXPIRE = SCHEMA.addField(IntegerField.builder().name("soa_expire").defaultValue(1209600)
        .label(HohenheimFormCopy.label("soa_expire")).help(HohenheimFormCopy.help("soa_expire")).build());
    public static final BooleanField ENABLED = SCHEMA.addField(BooleanField.builder("enabled").defaultValue(true)
        .label(HohenheimFormCopy.label("zone_enabled")).help(HohenheimFormCopy.help("zone_enabled")).build());
    public static final DateTimeField CREATED_AT = SCHEMA.addField(DateTimeField.builder().name("created_at").build());
    public static final DateTimeField UPDATED_AT = SCHEMA.addField(DateTimeField.builder().name("updated_at").build());

    public List<Row> findEnabled() {
        return find().where(ENABLED.eq(true)).all();
    }

    public Row findByOrigin(String origin) {
        return find().where(ORIGIN.eq(origin)).first();
    }

    @Override
    public Identifier getModelId() { return MODEL_ID; }

    @Override
    public Field<?, ?> getPrimaryKeyField() { return ID; }

    @Override
    public String getModelName() { return "DnsZone"; }

    @Override
    public String getTableName() { return "dns_zones"; }

    @Override
    public Schema getSchema() { return SCHEMA; }
}
