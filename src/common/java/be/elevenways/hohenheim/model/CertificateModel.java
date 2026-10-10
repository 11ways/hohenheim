package be.elevenways.hohenheim.model;

import be.elevenways.hohenheim.HohenheimIds;
import be.elevenways.hohenheim.HohenheimMicrocopy;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.common.edit.EditView;
import be.elevenways.zenit.common.edit.InputType;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.field.*;
import be.elevenways.zenit.common.orm.field.attributes.FieldAttributes;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.Schema;
import be.elevenways.zenit.common.orm.query.SortOrder;
import be.elevenways.zenit.common.security.PrincipalField;
import be.elevenways.zenit.common.security.PrincipalKinds;
import be.elevenways.zenit.common.security.PrincipalRef;
import be.elevenways.zenit.common.ui.BadgeVariant;
import be.elevenways.zenit.common.ui.ColorHue;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

public class CertificateModel extends Model {

    public static final Identifier MODEL_ID = HohenheimIds.id("certificate");
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
        .label(HohenheimMicrocopy.HOHENHEIM_FIELD.of("cert_nice_name"))
        .help(HohenheimMicrocopy.HELP.of("cert_nice_name")).build());
    public static final EnumField PROVIDER = SCHEMA.addField(EnumField.builder("provider")
        .value(PROVIDER_LETSENCRYPT, v -> v.displayName("Let's Encrypt")
            .label(HohenheimMicrocopy.CERT_PROVIDER.of(PROVIDER_LETSENCRYPT)).icon("lock").color(ColorHue.GREEN))
        .value(PROVIDER_CUSTOM, v -> v.displayName("Uploaded")
            .label(HohenheimMicrocopy.CERT_PROVIDER.of(PROVIDER_CUSTOM)).icon("file-import").color(ColorHue.BLUE))
        .value(PROVIDER_ACME_ACCOUNT, v -> v.displayName("ACME account")
            .label(HohenheimMicrocopy.CERT_PROVIDER.of(PROVIDER_ACME_ACCOUNT)).color(ColorHue.GRAY))
        .label(HohenheimMicrocopy.HOHENHEIM_FIELD.of("provider")).build());
    public static final TextField CERTIFICATE_PEM = SCHEMA.addField(TextField.builder("certificate_pem")
        .label(HohenheimMicrocopy.HOHENHEIM_FIELD.of("cert_certificate_pem"))
        .help(HohenheimMicrocopy.HELP.of("cert_certificate_pem")).build());
    public static final TextField PRIVATE_KEY_PEM = SCHEMA.addField(TextField.builder("private_key_pem")
        .secret().encrypted().inputHint(InputType.MULTILINE)
        .label(HohenheimMicrocopy.HOHENHEIM_FIELD.of("cert_private_key_pem"))
        .help(HohenheimMicrocopy.HELP.of("cert_private_key_pem")).build());
    /** Absent until a certificate is issued; list, record and the admin overview say so in the same words. */
    public static final DateTimeField EXPIRES_ON = SCHEMA.addField(DateTimeField.builder().name("expires_on")
        .label(HohenheimMicrocopy.HOHENHEIM_FIELD.of("cert_expires_on"))
        .absent(HohenheimMicrocopy.CERTIFICATE.of("expiry_none")).build());
    public static final BooleanField AUTO_RENEW = SCHEMA.addField(BooleanField.builder("auto_renew").defaultValue(true)
        .visibleIn(EditView.EDIT, EditView.DETAIL)
        .label(HohenheimMicrocopy.HOHENHEIM_FIELD.of("cert_auto_renew"))
        .help(HohenheimMicrocopy.HELP.of("cert_auto_renew")).build());
    public static final EnumField STATUS = SCHEMA.addField(EnumField.builder("status")
        .value(STATUS_ACTIVE, v -> v.displayName("Active")
            .label(HohenheimMicrocopy.CERT_STATUS.of(STATUS_ACTIVE)).icon("circle-check").color(BadgeVariant.SUCCESS))
        .value(STATUS_PENDING, v -> v.displayName("Pending")
            .label(HohenheimMicrocopy.CERT_STATUS.of(STATUS_PENDING)).icon("clock").color(BadgeVariant.WARNING))
        .value(STATUS_ERROR, v -> v.displayName("Error")
            .label(HohenheimMicrocopy.CERT_STATUS.of(STATUS_ERROR)).icon("triangle-exclamation")
            .color(BadgeVariant.DESTRUCTIVE))
        .label(HohenheimMicrocopy.HOHENHEIM_FIELD.of("status"))
        .build());
    public static final DateTimeField ISSUED_ON = SCHEMA.addField(DateTimeField.builder().name("issued_on")
        .label(HohenheimMicrocopy.HOHENHEIM_FIELD.of("issued_on")).build());
    public static final StringField RENEWAL_ERROR = SCHEMA.addField(StringField.builder().name("renewal_error")
        .visibleIn(EditView.EDIT)
        .attribute(FieldAttributes.GROUP, "renewal")
        .label(HohenheimMicrocopy.HOHENHEIM_FIELD.of("cert_renewal_error")).build());
    public static final IntegerField ERROR_COUNT = SCHEMA.addField(IntegerField.builder().name("error_count")
        .visibleIn(EditView.EDIT, EditView.DETAIL)
        .attribute(FieldAttributes.GROUP, "renewal")
        .label(HohenheimMicrocopy.HOHENHEIM_FIELD.of("cert_error_count")).build());
    public static final DateTimeField NEXT_ATTEMPT_AT = SCHEMA.addField(DateTimeField.builder().name("next_attempt_at")
        .visibleIn(EditView.EDIT)
        .attribute(FieldAttributes.GROUP, "renewal")
        .label(HohenheimMicrocopy.HOHENHEIM_FIELD.of("cert_next_attempt_at")).build());
    public static final StringField DOMAIN_NAMES_TEXT = SCHEMA.addField(StringField.builder().name("domain_names_text")
        .label(HohenheimMicrocopy.HOHENHEIM_FIELD.of("cert_domain_names")).build());

    /** Per-cert ACME account email override; null means the global account. */
    public static final StringField LETSENCRYPT_EMAIL = SCHEMA.addField(StringField.builder().name("letsencrypt_email").build());
    /**
     * THE challenge-type vocabulary, declared once for the stored column and for the certificate request's input
     * (CertificateOperations), which must offer it in a create-like view the column hides.
     */
    public static EnumField.Builder challengeTypeField() {
        return EnumField.builder("challenge_type")
            .value(CHALLENGE_HTTP, value -> value.displayName("HTTP-01")
                .label(HohenheimMicrocopy.CERT_CHALLENGE.of(CHALLENGE_HTTP)).icon("globe").color(ColorHue.BLUE))
            .value(CHALLENGE_DNS, value -> value.displayName("DNS-01")
                .label(HohenheimMicrocopy.CERT_CHALLENGE.of(CHALLENGE_DNS)).icon("at").color(ColorHue.VIOLET));
    }

    /** THE DNS-01 publisher vocabulary, shared by the stored column and the certificate request's input. */
    public static EnumField.Builder dnsPublisherField() {
        return EnumField.builder("dns_publisher")
            .value(DNS_PUBLISHER_MANUAL, v -> v.displayName("Manual")
                .label(HohenheimMicrocopy.DNS_PUBLISHER.of("manual"))
                .icon("pen").color(ColorHue.GRAY))
            .value(DNS_PUBLISHER_INTERNAL, v -> v.displayName("Internal")
                .label(HohenheimMicrocopy.DNS_PUBLISHER.of("internal"))
                .icon("server").color(ColorHue.GREEN))
            .value(DNS_PUBLISHER_COMMAND, v -> v.displayName("Command hook")
                .label(HohenheimMicrocopy.DNS_PUBLISHER.of("command"))
                .icon("terminal").color(ColorHue.BLUE));
    }

    public static final EnumField CHALLENGE_TYPE = SCHEMA.addField(challengeTypeField()
        .label(HohenheimMicrocopy.HOHENHEIM_FIELD.of("cert_challenge_type"))
        .help(HohenheimMicrocopy.HELP.of("cert_challenge_type"))
        .visibleIn(EditView.EDIT)
        .build());

    public static final EnumField DNS_PUBLISHER = SCHEMA.addField(
        dnsPublisherField()
            .visibleIn(EditView.EDIT)
            .label(HohenheimMicrocopy.HOHENHEIM_FIELD.of("cert_dns_publisher"))
            .help(HohenheimMicrocopy.HELP.of("cert_dns_publisher")).build());

    /**
     * The id of the principal whose authority this certificate was issued under, beside
     * {@link #REQUESTED_BY_KIND}; null for unattended orders. Read the pair through
     * {@link #requesterOf}, never the id alone.
     *
     * AIDEV-NOTE: renewal re-runs CertificateAuthority against THIS subject rather than
     * trusting the fact that issuance once succeeded. Without it a certificate ordered by a
     * tenant kept renewing forever after the manage grant that authorized it was revoked.
     * Never a permission SNAPSHOT -- a stored decision goes stale silently; a stored subject
     * is re-decided every sweep.
     */
    public static final IntegerField REQUESTED_BY_USER_ID = SCHEMA.addField(
        IntegerField.builder().name("requested_by_user_id").build());

    /** The kind of {@link #REQUESTED_BY_USER_ID}'s principal; together they are the certificate's owner. */
    public static final StringField REQUESTED_BY_KIND = SCHEMA.addField(PrincipalKinds.kindField("requested_by_kind"));

    /** The requester pair: the certificate's owner, re-decided against every renewal sweep. */
    public static final PrincipalField REQUESTER = PrincipalField.of(REQUESTED_BY_KIND, REQUESTED_BY_USER_ID);

    /** Dedup stamp for the expiring-soon alert; a renewal moves expires_on forward, re-arming it. */
    public static final DateTimeField EXPIRY_NOTIFIED_AT = SCHEMA.addField(DateTimeField.builder().name("expiry_notified_at").build());
    public static final DateTimeField CREATED_AT = SCHEMA.addField(DateTimeField.builder().name("created_at")
        .label(HohenheimMicrocopy.HOHENHEIM_FIELD.of("created_at")).build());
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

    /** @return the stored requester, or null for an unattended order or a pair naming no known principal */
    public static @Nullable PrincipalRef requesterOf(@NonNull Row certificate) {
        return REQUESTER.read(certificate);
    }

    /** Stores {@code requester} in {@link #REQUESTER}, both columns null for an unattended order. */
    public static void setRequester(@NonNull Row certificate, @Nullable PrincipalRef requester) {
        REQUESTER.write(certificate, requester);
    }
}
