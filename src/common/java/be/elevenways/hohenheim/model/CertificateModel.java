package be.elevenways.hohenheim.model;

import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.field.*;
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

    public static final IntegerField ID = SCHEMA.addField(IntegerField.builder().name("id").build());
    public static final StringField NICE_NAME = SCHEMA.addField(StringField.builder().name("nice_name").build());
    public static final StringField PROVIDER = SCHEMA.addField(StringField.builder().name("provider").build());
    public static final StringField CERTIFICATE_PEM = SCHEMA.addField(StringField.builder().name("certificate_pem").build());
    public static final StringField PRIVATE_KEY_PEM = SCHEMA.addField(StringField.builder().name("private_key_pem").build());
    public static final DateTimeField EXPIRES_ON = SCHEMA.addField(DateTimeField.builder().name("expires_on").build());
    public static final BooleanField AUTO_RENEW = SCHEMA.addField(BooleanField.builder("auto_renew").defaultValue(true).build());
    public static final StringField DNS_PROVIDER = SCHEMA.addField(StringField.builder().name("dns_provider").build());
    public static final StringField DNS_CREDENTIALS = SCHEMA.addField(StringField.builder().name("dns_credentials").build());
    public static final StringField ACME_SERVER = SCHEMA.addField(StringField.builder().name("acme_server").build());
    public static final StringField STATUS = SCHEMA.addField(StringField.builder().name("status").build());
    public static final DateTimeField ISSUED_ON = SCHEMA.addField(DateTimeField.builder().name("issued_on").build());
    public static final StringField RENEWAL_ERROR = SCHEMA.addField(StringField.builder().name("renewal_error").build());
    public static final IntegerField ERROR_COUNT = SCHEMA.addField(IntegerField.builder().name("error_count").build());
    public static final DateTimeField NEXT_ATTEMPT_AT = SCHEMA.addField(DateTimeField.builder().name("next_attempt_at").build());
    public static final StringField DOMAIN_NAMES_TEXT = SCHEMA.addField(StringField.builder().name("domain_names_text").build());

    /** Per-cert ACME account email override; null means the global account. */
    public static final StringField LETSENCRYPT_EMAIL = SCHEMA.addField(StringField.builder().name("letsencrypt_email").build());
    public static final SchemaField META = SCHEMA.addField(SchemaField.builder("meta").build());
    public static final DateTimeField CREATED_AT = SCHEMA.addField(DateTimeField.builder().name("created_at").build());
    public static final DateTimeField UPDATED_AT = SCHEMA.addField(DateTimeField.builder().name("updated_at").build());


    public List<Row> findExpiringSoon(int days) {
        Instant cutoff = Instant.now().plus(days, ChronoUnit.DAYS);
        return find()
            .where(AUTO_RENEW.eq(true))
            .and(EXPIRES_ON.lte(cutoff))
            .orderBy(EXPIRES_ON, SortOrder.ASC)
            .all();
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
