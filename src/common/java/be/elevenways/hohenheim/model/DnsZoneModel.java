package be.elevenways.hohenheim.model;

import be.elevenways.hohenheim.HohenheimFormCopy;
import be.elevenways.protoblast.common.i18n.Microcopy;
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
        .suffix("s").label(HohenheimFormCopy.label("default_ttl")).help(HohenheimFormCopy.help("default_ttl")).build());
    public static final IntegerField NEGATIVE_TTL = SCHEMA.addField(IntegerField.builder().name("negative_ttl").defaultValue(300)
        .suffix("s").label(HohenheimFormCopy.label("negative_ttl")).help(HohenheimFormCopy.help("negative_ttl")).build());
    public static final IntegerField SOA_REFRESH = SCHEMA.addField(IntegerField.builder().name("soa_refresh").defaultValue(7200)
        .suffix("s").label(HohenheimFormCopy.label("soa_refresh")).help(HohenheimFormCopy.help("soa_refresh")).build());
    public static final IntegerField SOA_RETRY = SCHEMA.addField(IntegerField.builder().name("soa_retry").defaultValue(3600)
        .suffix("s").label(HohenheimFormCopy.label("soa_retry")).help(HohenheimFormCopy.help("soa_retry")).build());
    public static final IntegerField SOA_EXPIRE = SCHEMA.addField(IntegerField.builder().name("soa_expire").defaultValue(1209600)
        .suffix("s").label(HohenheimFormCopy.label("soa_expire")).help(HohenheimFormCopy.help("soa_expire")).build());
    public static final BooleanField ENABLED = SCHEMA.addField(BooleanField.builder("enabled").defaultValue(true)
        .label(HohenheimFormCopy.label("zone_enabled")).help(HohenheimFormCopy.help("zone_enabled")).build());

    /** {@link #ROLE} value for a zone owned and edited on this instance. */
    public static final String ROLE_PRIMARY = "primary";
    /** {@link #ROLE} value for a zone replicated from a peer via AXFR. */
    public static final String ROLE_SECONDARY = "secondary";

    public static final EnumField ROLE = SCHEMA.addField(EnumField.builder("role")
        .value(ROLE_PRIMARY, v -> v.displayName("Primary")
            .label(Microcopy.of("role_primary").withFilter("scope", "dns_role"))
            .icon("star").color("blue"))
        .value(ROLE_SECONDARY, v -> v.displayName("Secondary")
            .label(Microcopy.of("role_secondary").withFilter("scope", "dns_role"))
            .icon("copy").color("gray"))
        .label(HohenheimFormCopy.label("zone_role")).help(HohenheimFormCopy.help("zone_role")).build());
    public static final IntegerField PRIMARY_PEER_ID = SCHEMA.addField(
        IntegerField.builder().name("primary_peer_id")
            .label(HohenheimFormCopy.label("primary_peer"))
            .help(HohenheimFormCopy.help("primary_peer")).build());
    public static final StringField TRANSFER_STATUS = SCHEMA.addField(
        StringField.builder().name("transfer_status").build());
    public static final StringField TRANSFER_MESSAGE = SCHEMA.addField(
        StringField.builder().name("transfer_message").build());
    public static final DateTimeField LAST_CHECKED_AT = SCHEMA.addField(
        DateTimeField.builder().name("last_checked_at").build());
    public static final DateTimeField LAST_TRANSFER_AT = SCHEMA.addField(
        DateTimeField.builder().name("last_transfer_at").build());
    public static final StringField REPLICA_RECORDS = SCHEMA.addField(
        StringField.builder().name("replica_records").build());

    // --- DNSSEC (online signing; one Combined Signing Key per zone) ---
    public static final BooleanField DNSSEC_ENABLED = SCHEMA.addField(
        BooleanField.builder("dnssec_enabled").defaultValue(false)
        .label(HohenheimFormCopy.label("dnssec_enabled")).help(HohenheimFormCopy.help("dnssec_enabled")).build());
    public static final IntegerField DNSSEC_ALGORITHM = SCHEMA.addField(
        IntegerField.builder().name("dnssec_algorithm").defaultValue(13).build());
    /** Base64 PKCS#8 private key; secret so it never leaves the server in exports or forms. */
    public static final StringField DNSSEC_PRIVATE_KEY = SCHEMA.addField(
        StringField.builder().name("dnssec_private_key").secret().encrypted().build());
    /** Base64 X.509 SubjectPublicKeyInfo of the signing key. */
    public static final StringField DNSSEC_PUBLIC_KEY = SCHEMA.addField(
        StringField.builder().name("dnssec_public_key").build());
    public static final IntegerField DNSSEC_KEY_TAG = SCHEMA.addField(
        IntegerField.builder().name("dnssec_key_tag").build());

    public static final DateTimeField CREATED_AT = SCHEMA.addField(DateTimeField.builder().name("created_at").build());
    public static final DateTimeField UPDATED_AT = SCHEMA.addField(DateTimeField.builder().name("updated_at").build());

    public List<Row> findEnabled() {
        return find().where(ENABLED.eq(true)).all();
    }

    /** @return the zone's role, defaulting to primary for rows predating federation */
    public static String roleOf(Row zone) {
        String role = zone.get(ROLE);
        return ROLE_SECONDARY.equals(role) ? ROLE_SECONDARY : ROLE_PRIMARY;
    }

    /** Enabled secondary zones only: a disabled secondary is neither replicated nor served. */
    public List<Row> findSecondaries() {
        return find().where(ROLE.eq(ROLE_SECONDARY)).and(ENABLED.eq(true)).all();
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
