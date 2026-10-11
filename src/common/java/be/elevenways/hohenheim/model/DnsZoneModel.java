package be.elevenways.hohenheim.model;

import be.elevenways.hohenheim.HohenheimIds;
import be.elevenways.hohenheim.HohenheimMicrocopy;
import be.elevenways.hohenheim.dns.DelegationVerdict;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.common.edit.InputType;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.field.*;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.Schema;
import be.elevenways.zenit.common.orm.model.relation.BelongsTo;
import be.elevenways.zenit.common.ui.ColorHue;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.List;

/**
 * An authoritative DNS zone hosted by Hohenheim.
 * The origin is stored normalized: lowercase, no trailing dot.
 */
public class DnsZoneModel extends Model {

    public static final Identifier MODEL_ID = HohenheimIds.id("dns_zone");
    public static final Schema SCHEMA = new Schema();

    public static final IntegerField ID = SCHEMA.addField(IntegerField.builder().name("id").build());
    public static final StringField ORIGIN = SCHEMA.addField(StringField.builder().name("origin")
        .label(HohenheimMicrocopy.HOHENHEIM_FIELD.of("origin")).help(HohenheimMicrocopy.HELP.of("origin")).build());
    public static final StringField SOA_PRIMARY_NS = SCHEMA.addField(StringField.builder().name("soa_primary_ns")
        .label(HohenheimMicrocopy.HOHENHEIM_FIELD.of("soa_primary_ns"))
        .help(HohenheimMicrocopy.HELP.of("soa_primary_ns")).build());
    public static final StringField SOA_CONTACT = SCHEMA.addField(StringField.builder().name("soa_contact")
        .label(HohenheimMicrocopy.HOHENHEIM_FIELD.of("soa_contact")).help(HohenheimMicrocopy.HELP.of("soa_contact"))
        .build());
    public static final IntegerField SERIAL = SCHEMA.addField(IntegerField.builder().name("serial").defaultValue(1)
        .label(HohenheimMicrocopy.HOHENHEIM_FIELD.of("serial")).help(HohenheimMicrocopy.HELP.of("serial")).build());
    public static final IntegerField DEFAULT_TTL = SCHEMA.addField(IntegerField.builder().name("default_ttl")
            .defaultValue(3600)
        .suffix("s").label(HohenheimMicrocopy.HOHENHEIM_FIELD.of("default_ttl"))
        .help(HohenheimMicrocopy.HELP.of("default_ttl")).build());
    public static final IntegerField NEGATIVE_TTL = SCHEMA.addField(IntegerField.builder().name("negative_ttl")
            .defaultValue(300)
        .suffix("s").label(HohenheimMicrocopy.HOHENHEIM_FIELD.of("negative_ttl"))
        .help(HohenheimMicrocopy.HELP.of("negative_ttl")).build());
    public static final IntegerField SOA_REFRESH = SCHEMA.addField(IntegerField.builder().name("soa_refresh")
            .defaultValue(7200)
        .suffix("s").label(HohenheimMicrocopy.HOHENHEIM_FIELD.of("soa_refresh"))
        .help(HohenheimMicrocopy.HELP.of("soa_refresh")).build());
    public static final IntegerField SOA_RETRY = SCHEMA.addField(IntegerField.builder().name("soa_retry")
            .defaultValue(3600)
        .suffix("s").label(HohenheimMicrocopy.HOHENHEIM_FIELD.of("soa_retry"))
        .help(HohenheimMicrocopy.HELP.of("soa_retry")).build());
    public static final IntegerField SOA_EXPIRE = SCHEMA.addField(IntegerField.builder().name("soa_expire")
            .defaultValue(1209600)
        .suffix("s").label(HohenheimMicrocopy.HOHENHEIM_FIELD.of("soa_expire"))
        .help(HohenheimMicrocopy.HELP.of("soa_expire")).build());
    public static final BooleanField ENABLED = SCHEMA.addField(BooleanField.builder("enabled").defaultValue(true)
        .label(HohenheimMicrocopy.HOHENHEIM_FIELD.of("zone_enabled")).help(HohenheimMicrocopy.HELP.of("zone_enabled"))
        .build());

    /** {@link #ROLE} value for a zone owned and edited on this instance. */
    public static final String ROLE_PRIMARY = "primary";
    /** {@link #ROLE} value for a zone replicated from a peer via AXFR. */
    public static final String ROLE_SECONDARY = "secondary";

    // AIDEV-NOTE: the label is the ROLE WORD only -- the parenthetical it used to carry
    // ("Primary (owned here)") made the list column 180px of nowrap text. The explanation
    // lives on the DECLARED description facet instead, so it can be drawn as help text or a
    // tooltip without every surface widening for it.
    public static final EnumField ROLE = SCHEMA.addField(EnumField.builder("role")
        .value(ROLE_PRIMARY, v -> v.displayName("Primary")
            .label(HohenheimMicrocopy.DNS_ROLE.of("role_primary"))
            .describe(HohenheimMicrocopy.HELP.of("role_primary"))
            .icon("star").color(ColorHue.BLUE))
        .value(ROLE_SECONDARY, v -> v.displayName("Secondary")
            .label(HohenheimMicrocopy.DNS_ROLE.of("role_secondary"))
            .describe(HohenheimMicrocopy.HELP.of("role_secondary"))
            .icon("copy").color(ColorHue.GRAY))
        .label(HohenheimMicrocopy.HOHENHEIM_FIELD.of("zone_role")).help(HohenheimMicrocopy.HELP.of("zone_role"))
        .build());
    public static final IntegerField PRIMARY_PEER_ID = SCHEMA.addField(
        IntegerField.builder().name("primary_peer_id")
            .label(HohenheimMicrocopy.HOHENHEIM_FIELD.of("primary_peer"))
            .help(HohenheimMicrocopy.HELP.of("primary_peer")).build());
    /** {@link #TRANSFER_STATUS} value: the last AXFR from the primary peer succeeded. */
    public static final String TRANSFER_OK = "ok";
    /** {@link #TRANSFER_STATUS} value: the last AXFR attempt failed; the replica still serves. */
    public static final String TRANSFER_ERROR = "error";
    /** {@link #TRANSFER_STATUS} value: the SOA expire window elapsed, so the replica stopped serving. */
    public static final String TRANSFER_EXPIRED = "expired";

    /**
     * Replication outcome of a SECONDARY zone; a primary zone never carries one.
     *
     * AIDEV-NOTE: this is the vocabulary's ONE declaring home -- {@code SecondaryZoneService}
     * writes these constants, it does not keep its own spelling of them.
     */
    public static final EnumField TRANSFER_STATUS = SCHEMA.addField(EnumField.builder("transfer_status")
        .value(TRANSFER_OK, v -> v.displayName("Transferred").icon("circle-check")
            .label(HohenheimMicrocopy.DNS_TRANSFER.of("transferred")).color(ColorHue.GREEN))
        .value(TRANSFER_ERROR, v -> v.displayName("Transfer failed").icon("triangle-exclamation")
            .label(HohenheimMicrocopy.DNS_TRANSFER.of("failed")).color(ColorHue.RED))
        .value(TRANSFER_EXPIRED, v -> v.displayName("Expired").icon("hourglass-end")
            .label(HohenheimMicrocopy.DNS_TRANSFER.of("expired")).color(ColorHue.ORANGE))
        .label(HohenheimMicrocopy.HOHENHEIM_FIELD.of("transfer_status"))
        .help(HohenheimMicrocopy.HELP.of("transfer_status")).build());
    public static final StringField TRANSFER_MESSAGE = SCHEMA.addField(
        StringField.builder().name("transfer_message")
            .label(HohenheimMicrocopy.HOHENHEIM_FIELD.of("transfer_message"))
            .help(HohenheimMicrocopy.HELP.of("transfer_message")).build());
    public static final DateTimeField LAST_CHECKED_AT = SCHEMA.addField(
        DateTimeField.builder().name("last_checked_at").build());
    public static final DateTimeField LAST_TRANSFER_AT = SCHEMA.addField(
        DateTimeField.builder().name("last_transfer_at")
            .label(HohenheimMicrocopy.HOHENHEIM_FIELD.of("last_transfer_at"))
            .help(HohenheimMicrocopy.HELP.of("last_transfer_at")).build());
    public static final StringField REPLICA_RECORDS = SCHEMA.addField(
        StringField.builder().name("replica_records").build());

    // --- Delegation health of a PRIMARY zone (written by the delegation check) ---
    /** The worst finding of the last delegation check; the vocabulary lives on {@link DelegationVerdict}. */
    public static final EnumField DELEGATION_STATUS = SCHEMA.addField(
        DelegationVerdict.fieldBuilder("delegation_status")
            .label(HohenheimMicrocopy.HOHENHEIM_FIELD.of("delegation_status"))
            .help(HohenheimMicrocopy.HELP.of("delegation_status")).build());
    /**
     * One line per finding of the last check, diagnostic text like {@link #TRANSFER_MESSAGE}.
     *
     * AIDEV-NOTE: MULTILINE because the column IS several lines -- a single-line input
     * collapsed them into one run-on string, which is the opposite of what the help text
     * promises. The stored lines stay {@code token subject} (the alert body and the zone API
     * read them); the panel localizes the token at render time
     * ({@code DnsZoneResource.valuesFromRow}).
     */
    public static final StringField DELEGATION_DETAIL = SCHEMA.addField(
        StringField.builder().name("delegation_detail")
            .inputHint(InputType.MULTILINE)
            .label(HohenheimMicrocopy.HOHENHEIM_FIELD.of("delegation_detail"))
            .help(HohenheimMicrocopy.HELP.of("delegation_detail")).build());
    public static final DateTimeField DELEGATION_CHECKED_AT = SCHEMA.addField(
        DateTimeField.builder().name("delegation_checked_at")
            .label(HohenheimMicrocopy.HOHENHEIM_FIELD.of("delegation_checked_at"))
            .help(HohenheimMicrocopy.HELP.of("delegation_checked_at")).build());

    // --- DNSSEC (online signing; one Combined Signing Key per zone) ---
    public static final BooleanField DNSSEC_ENABLED = SCHEMA.addField(
        BooleanField.builder("dnssec_enabled").defaultValue(false)
        .label(HohenheimMicrocopy.HOHENHEIM_FIELD.of("dnssec_enabled"))
        .help(HohenheimMicrocopy.HELP.of("dnssec_enabled")).build());
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

    /** The peer a SECONDARY zone replicates from; its delete refuses while a secondary names it. */
    public static final BelongsTo<DnsPeerModel> PRIMARY_PEER = SCHEMA.addRelation(
        BelongsTo.to(DnsPeerModel.class)
            .name("primary_peer")
            .localKey(PRIMARY_PEER_ID)
            .remoteKey(DnsPeerModel.ID)
            .build());

    public List<Row> findEnabled() {
        return findAll(ENABLED, true);
    }

    /** @return the zone's role, defaulting to primary for rows predating federation */
    public static String roleOf(Row zone) {
        String role = zone.get(ROLE);
        return ROLE_SECONDARY.equals(role) ? ROLE_SECONDARY : ROLE_PRIMARY;
    }

    /**
     * THE zone-default TTL every record without an explicit one inherits, so the number is
     * spelled once instead of once per caller.
     *
     * AIDEV-NOTE: the fallback DERIVES from {@link #DEFAULT_TTL}'s declared default rather
     * than repeating 3600, so raising the declared default cannot leave a stale literal
     * serving a different TTL than the form promises.
     */
    public static int defaultTtlOf(Row zone) {
        Integer stored = zone != null ? zone.get(DEFAULT_TTL) : null;
        if (stored != null) {
            return stored;
        }
        Integer declared = DEFAULT_TTL.getDefaultValue();
        return declared != null ? declared : 3600;
    }

    /** Enabled secondary zones only: a disabled secondary is neither replicated nor served. */
    public List<Row> findSecondaries() {
        return find().where(ROLE.eq(ROLE_SECONDARY)).and(ENABLED.eq(true)).all();
    }

    /** The zone with this origin, which is the zone's identity; null when none. */
    public @Nullable Row findByOrigin(String origin) {
        return findFirst(ORIGIN, origin);
    }

    static {
        // The origin IS the zone: it is what an operator typed, what every breadcrumb and
        // picker must say, and the only value here that is not a timer or a diagnostic.
        SCHEMA.setDisplayFields(ORIGIN);
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
