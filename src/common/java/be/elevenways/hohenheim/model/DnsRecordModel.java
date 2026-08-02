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
        .label(HohenheimFormCopy.label("record_zone")).help(HohenheimFormCopy.help("record_zone")).build());
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
        .suffix("s").label(HohenheimFormCopy.label("record_ttl")).help(HohenheimFormCopy.help("record_ttl")).build());
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

    // --- Dynamic DNS (dyndns2 update protocol; only A/AAAA records) ---
    public static final BooleanField DYNAMIC = SCHEMA.addField(BooleanField.builder("dynamic").defaultValue(false)
        .label(HohenheimFormCopy.label("record_dynamic")).help(HohenheimFormCopy.help("record_dynamic")).build());
    /**
     * The HTTP Basic password a dyndns client presents; a live bearer credential.
     * It is {@code secret()} (never echoed into the form, revisions, activity deltas
     * or diff rendering; a blank submit keeps the stored value) AND stored HASHED --
     * only the {@code sha256:} digest is at rest, so a DB read (backup, another
     * process, revision history) cannot recover a working token. {@code DynamicDnsService}
     * hashes the presented token and looks the record up by digest; the mint row
     * action discloses the plaintext ONCE in its toast. {@code DynamicDnsService.installTokenHashing}
     * normalizes any write, and {@code DyndnsTokenSeeder} hashes pre-existing tokens
     * in place (which keeps configured clients working, since they present the same plaintext).
     */
    public static final StringField DYNDNS_TOKEN = SCHEMA.addField(StringField.builder().name("dyndns_token")
        .label(HohenheimFormCopy.label("record_dyndns_token")).help(HohenheimFormCopy.help("record_dyndns_token"))
        .secret().build());
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
