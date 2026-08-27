package be.elevenways.hohenheim.model;

import be.elevenways.hohenheim.HohenheimFormCopy;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.common.edit.EditView;
import be.elevenways.zenit.common.edit.InputType;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.field.*;
import be.elevenways.zenit.common.orm.field.attributes.FieldAttributes;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.Schema;
import be.elevenways.zenit.common.orm.query.SortOrder;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

public class CertificateModel extends Model {

    public static final Identifier MODEL_ID = Identifier.of("hohenheim", "certificate");
    public static final Schema SCHEMA = new Schema();

    /** {@link #STATUS} value for an active certificate. */
    public static final String STATUS_ACTIVE = "active";

    /** {@link #STATUS} value for a certificate whose last issuance or renewal failed. */
    public static final String STATUS_ERROR = "error";

    /** {@link #STATUS} value for a certificate order that has not completed yet. */
    public static final String STATUS_PENDING = "pending";

    /** {@link #PROVIDER} value for the internal ACME account key row (excluded from listings). */
    public static final String PROVIDER_ACME_ACCOUNT = "acme_account";

    /** {@link #PROVIDER} value for ACME-issued certificates. */
    public static final String PROVIDER_LETSENCRYPT = "letsencrypt";

    /** {@link #PROVIDER} value for user-uploaded certificates (never auto-managed). */
    public static final String PROVIDER_CUSTOM = "custom";

    public static final String CHALLENGE_HTTP = "http";
    public static final String CHALLENGE_DNS = "dns";
    public static final String DNS_PUBLISHER_MANUAL = "manual";
    public static final String DNS_PUBLISHER_INTERNAL = "internal";

    /**
     * {@link #DNS_PUBLISHER} value for the operator-owned executable hook.
     *
     * AIDEV-NOTE: THE declaring home of the id, which {@code CommandDnsTxtPublisher.ID}
     * reads back -- the publisher lives in the server source set and this column is common,
     * so the string cannot come the other way. The 2026-08-19 enum conversion declared only
     * manual and internal and NARROWED a live vocabulary: the request form has offered this
     * value since it shipped, so rows already hold it, and the select lost its option while
     * a resubmit and a filter rule started refusing the stored value outright.
     */
    public static final String DNS_PUBLISHER_COMMAND = "command";


    public static final IntegerField ID = SCHEMA.addField(IntegerField.builder().name("id").build());
    public static final StringField NICE_NAME = SCHEMA.addField(StringField.builder().name("nice_name")
        .label(HohenheimFormCopy.label("cert_nice_name"))
        .help(HohenheimFormCopy.help("cert_nice_name")).build());
    public static final EnumField PROVIDER = SCHEMA.addField(EnumField.builder("provider")
        .value(PROVIDER_LETSENCRYPT, v -> v.displayName("Let's Encrypt")
            .label(providerLabel(PROVIDER_LETSENCRYPT)).icon("lock").color("green"))
        .value(PROVIDER_CUSTOM, v -> v.displayName("Custom")
            .label(providerLabel(PROVIDER_CUSTOM)).icon("file-import").color("blue"))
        .value(PROVIDER_ACME_ACCOUNT, v -> v.displayName("ACME account")
            .label(providerLabel(PROVIDER_ACME_ACCOUNT)).color("gray"))
        .build());

    /** The translation token for a certificate provider; the key IS the stored value. */
    private static Microcopy providerLabel(String provider) {
        return Microcopy.of(provider).withFilter("scope", "cert_provider");
    }
    public static final TextField CERTIFICATE_PEM = SCHEMA.addField(TextField.builder("certificate_pem")
        .label(HohenheimFormCopy.label("cert_certificate_pem"))
        .help(HohenheimFormCopy.help("cert_certificate_pem")).build());
    public static final TextField PRIVATE_KEY_PEM = SCHEMA.addField(TextField.builder("private_key_pem")
        .secret().encrypted().inputHint(InputType.MULTILINE)
        .label(HohenheimFormCopy.label("cert_private_key_pem"))
        .help(HohenheimFormCopy.help("cert_private_key_pem")).build());
    public static final DateTimeField EXPIRES_ON = SCHEMA.addField(DateTimeField.builder().name("expires_on").build());
    public static final BooleanField AUTO_RENEW = SCHEMA.addField(BooleanField.builder("auto_renew").defaultValue(true)
        .visibleIn(EditView.EDIT)
        .label(HohenheimFormCopy.label("cert_auto_renew"))
        .help(HohenheimFormCopy.help("cert_auto_renew")).build());
    public static final EnumField STATUS = SCHEMA.addField(EnumField.builder("status")
        .value(STATUS_ACTIVE, v -> v.displayName("Active")
            .label(statusLabel(STATUS_ACTIVE)).icon("circle-check").color("success"))
        .value(STATUS_PENDING, v -> v.displayName("Pending")
            .label(statusLabel(STATUS_PENDING)).icon("clock").color("warning"))
        .value(STATUS_ERROR, v -> v.displayName("Error")
            .label(statusLabel(STATUS_ERROR)).icon("triangle-exclamation").color("destructive"))
        .build());

    /** The translation token for a certificate status; the key IS the stored value. */
    private static Microcopy statusLabel(String status) {
        return Microcopy.of(status).withFilter("scope", "cert_status");
    }
    public static final DateTimeField ISSUED_ON = SCHEMA.addField(DateTimeField.builder().name("issued_on").build());
    public static final StringField RENEWAL_ERROR = SCHEMA.addField(StringField.builder().name("renewal_error")
        .visibleIn(EditView.EDIT)
        .attribute(FieldAttributes.GROUP, "renewal")
        .label(HohenheimFormCopy.label("cert_renewal_error")).build());
    public static final IntegerField ERROR_COUNT = SCHEMA.addField(IntegerField.builder().name("error_count")
        .visibleIn(EditView.EDIT)
        .attribute(FieldAttributes.GROUP, "renewal")
        .label(HohenheimFormCopy.label("cert_error_count")).build());
    public static final DateTimeField NEXT_ATTEMPT_AT = SCHEMA.addField(DateTimeField.builder().name("next_attempt_at")
        .visibleIn(EditView.EDIT)
        .attribute(FieldAttributes.GROUP, "renewal")
        .label(HohenheimFormCopy.label("cert_next_attempt_at")).build());
    public static final StringField DOMAIN_NAMES_TEXT = SCHEMA.addField(StringField.builder().name("domain_names_text")
        .label(HohenheimFormCopy.label("cert_domain_names")).build());

    /** Per-cert ACME account email override; null means the global account. */
    public static final StringField LETSENCRYPT_EMAIL = SCHEMA.addField(StringField.builder().name("letsencrypt_email").build());
    public static final EnumField CHALLENGE_TYPE = SCHEMA.addField(EnumField.builder("challenge_type")
        .value(CHALLENGE_HTTP, value -> value.displayName("HTTP-01")
            .label(challengeLabel(CHALLENGE_HTTP)).icon("globe").color("blue"))
        .value(CHALLENGE_DNS, value -> value.displayName("DNS-01")
            .label(challengeLabel(CHALLENGE_DNS)).icon("at").color("violet"))
        .label(HohenheimFormCopy.label("cert_challenge_type"))
        .help(HohenheimFormCopy.help("cert_challenge_type"))
        .visibleIn(EditView.EDIT)
        .build());

    /** The translation token for an ACME challenge type; the key IS the stored value. */
    private static Microcopy challengeLabel(String challenge) {
        return Microcopy.of(challenge).withFilter("scope", "cert_challenge");
    }

    public static final EnumField DNS_PUBLISHER = SCHEMA.addField(
        EnumField.builder("dns_publisher")
            .value(DNS_PUBLISHER_MANUAL, v -> v.displayName("Manual")
                .label(Microcopy.of("manual").withFilter("scope", "dns_publisher"))
                .icon("pen").color("gray"))
            .value(DNS_PUBLISHER_INTERNAL, v -> v.displayName("Internal")
                .label(Microcopy.of("internal").withFilter("scope", "dns_publisher"))
                .icon("server").color("green"))
            .value(DNS_PUBLISHER_COMMAND, v -> v.displayName("Command hook")
                .label(Microcopy.of("command").withFilter("scope", "dns_publisher"))
                .icon("terminal").color("blue"))
            .visibleIn(EditView.EDIT)
            .label(HohenheimFormCopy.label("cert_dns_publisher"))
            .help(HohenheimFormCopy.help("cert_dns_publisher")).build());

    /**
     * The user whose authority this certificate was issued under, or null for operator and
     * unattended orders.
     *
     * AIDEV-NOTE: renewal re-runs CertificateAuthority against THIS subject rather than
     * trusting the fact that issuance once succeeded. Without it a certificate ordered by a
     * tenant kept renewing forever after the manage grant that authorized it was revoked.
     * Never a permission SNAPSHOT -- a stored decision goes stale silently; a stored subject
     * is re-decided every sweep.
     */
    public static final IntegerField REQUESTED_BY_USER_ID = SCHEMA.addField(
        IntegerField.builder().name("requested_by_user_id").build());

    /** Dedup stamp for the expiring-soon alert; a renewal moves expires_on forward, re-arming it. */
    public static final DateTimeField EXPIRY_NOTIFIED_AT = SCHEMA.addField(DateTimeField.builder().name("expiry_notified_at").build());
    public static final DateTimeField CREATED_AT = SCHEMA.addField(DateTimeField.builder().name("created_at").build());
    public static final DateTimeField UPDATED_AT = SCHEMA.addField(DateTimeField.builder().name("updated_at").build());


    /** Real certificates (never the ACME account row) expiring on or before the cutoff. */
    public List<Row> findExpiringSoon(Instant cutoff) {
        return find()
            .where(PROVIDER.ne(PROVIDER_ACME_ACCOUNT))
            .and(EXPIRES_ON.lte(cutoff))
            .orderBy(EXPIRES_ON, SortOrder.ASC)
            .all();
    }

    static {
        // The operator-given name first; a certificate requested straight from a domain
        // carries none, and then the names it covers ARE its identity.
        SCHEMA.setDisplayFields(NICE_NAME, DOMAIN_NAMES_TEXT);
    }

    @Override
    public Identifier getModelId() { return MODEL_ID; }

    @Override
    public Field<?, ?> getPrimaryKeyField() { return ID; }

    @Override
    public String getModelName() { return "Certificate"; }

    @Override
    public String getTableName() { return "certificates"; }

    @Override
    public Schema getSchema() { return SCHEMA; }
}
