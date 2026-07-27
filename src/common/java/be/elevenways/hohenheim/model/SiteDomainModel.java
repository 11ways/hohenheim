package be.elevenways.hohenheim.model;

import be.elevenways.hohenheim.HohenheimFormCopy;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.field.*;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.Schema;
import java.util.List;
import java.util.Locale;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.zenit.common.orm.model.Models;
import org.checkerframework.checker.nullness.qual.Nullable;
import be.elevenways.zenit.common.validation.Violations;

public class SiteDomainModel extends Model {

    public static final Identifier MODEL_ID = Identifier.of("hohenheim", "site_domain");
    public static final Schema SCHEMA = new Schema();

    /** {@link #MATCH_TYPE} value for an exact hostname match. */
    public static final String MATCH_EXACT = "exact";

    /** {@link #MATCH_TYPE} value for a glob-style wildcard hostname match. */
    public static final String MATCH_WILDCARD = "wildcard";

    /** {@link #MATCH_TYPE} value for a regex hostname match (hostname stays case-sensitive). */
    public static final String MATCH_REGEX = "regex";

    /**
     * THE stored/canonical hostname form, shared by the beforeValidate hook, the
     * route-identity check and the dispatcher: trimmed, lowercased except for regex
     * sources (patterns are case-sensitive).
     */
    public static @Nullable String canonicalHostname(@Nullable String hostname, @Nullable String matchType) {
        if (hostname == null) {
            return null;
        }
        String trimmed = hostname.trim();
        return MATCH_REGEX.equals(matchType) ? trimmed : trimmed.toLowerCase(Locale.ROOT);
    }

    public static final IntegerField ID = SCHEMA.addField(IntegerField.builder().name("id").build());
    public static final IntegerField SITE_ID = SCHEMA.addField(IntegerField.builder().name("site_id").build());
    public static final StringField HOSTNAME = SCHEMA.addField(StringField.builder().name("hostname")
        .label(HohenheimFormCopy.label("hostname"))
        .help(HohenheimFormCopy.help("hostname"))
        .placeholder("example.com")
        .build());
    public static final StringField MATCH_TYPE = SCHEMA.addField(StringField.builder().name("match_type")
        .defaultValue(MATCH_EXACT)
        .label(HohenheimFormCopy.label("match_type"))
        .help(HohenheimFormCopy.help("match_type"))
        .build());
    public static final StringField LISTEN_ON = SCHEMA.addField(StringField.builder().name("listen_on")
        .label(HohenheimFormCopy.label("listen_on"))
        .help(HohenheimFormCopy.help("listen_on"))
        .build());
    public static final StringField PATH = SCHEMA.addField(StringField.builder().name("path")
        .label(HohenheimFormCopy.label("path_prefix"))
        .help(HohenheimFormCopy.help("path_prefix"))
        .placeholder("/")
        .build());
    public static final BooleanField STRIP_PATH = SCHEMA.addField(BooleanField.builder("strip_path")
        .defaultValue(false)
        .label(HohenheimFormCopy.label("strip_path"))
        .help(HohenheimFormCopy.help("strip_path"))
        .build());
    public static final BooleanField FORCE_SSL = SCHEMA.addField(BooleanField.builder("force_ssl")
        .defaultValue(true)
        .label(HohenheimFormCopy.label("force_ssl"))
        .help(HohenheimFormCopy.help("force_ssl"))
        .build());
    public static final IntegerField CERTIFICATE_ID = SCHEMA.addField(IntegerField.builder().name("certificate_id")
        .label(HohenheimFormCopy.label("certificate"))
        .help(HohenheimFormCopy.help("certificate"))
        .build());
    public static final BooleanField HSTS_ENABLED = SCHEMA.addField(BooleanField.builder("hsts_enabled")
        .defaultValue(false)
        .label(HohenheimFormCopy.label("hsts_enabled"))
        .help(HohenheimFormCopy.help("hsts_enabled"))
        .build());
    public static final BooleanField HSTS_SUBDOMAINS = SCHEMA.addField(BooleanField.builder("hsts_subdomains")
        .defaultValue(false)
        .label(HohenheimFormCopy.label("hsts_subdomains"))
        .help(HohenheimFormCopy.help("hsts_subdomains"))
        .build());
    // Ordered header-name -> value maps (empty value = delete the header on forward).
    public static final StringMapField CUSTOM_HEADERS = SCHEMA.addField(StringMapField.builder("custom_headers")
        .label(HohenheimFormCopy.label("custom_headers"))
        .help(HohenheimFormCopy.help("custom_headers"))
        .build());
    public static final StringMapField RESPONSE_HEADERS = SCHEMA.addField(StringMapField.builder("response_headers")
        .label(HohenheimFormCopy.label("response_headers"))
        .help(HohenheimFormCopy.help("response_headers"))
        .build());
    public static final BooleanField EXCLUDE_FROM_LETSENCRYPT = SCHEMA.addField(BooleanField.builder("exclude_from_letsencrypt")
        .defaultValue(false)
        .label(HohenheimFormCopy.label("exclude_from_letsencrypt"))
        .help(HohenheimFormCopy.help("exclude_from_letsencrypt"))
        .build());
    public static final DateTimeField CREATED_AT = SCHEMA.addField(DateTimeField.builder().name("created_at").build());
    public static final DateTimeField UPDATED_AT = SCHEMA.addField(DateTimeField.builder().name("updated_at").build());

    static {
        // The hostname is the human title (breadcrumbs, relation pickers) instead of "SiteDomain #id".
        SCHEMA.setDisplayFields(HOSTNAME);
        SCHEMA.addBeforeValidateHook(context -> {
            Row row = context.getRow();
            if (row == null) return;
            String hostname = (String) effective(row, HOSTNAME);
            if (hostname != null) {
                row.set(HOSTNAME, canonicalHostname(hostname, (String) effective(row, MATCH_TYPE)));
            }
            Integer siteId = (Integer) effective(row, SITE_ID);
            Row site = siteId != null ? Models.get(SiteModel.class).findById(siteId) : null;
            if (site == null || !SiteModel.SITE_TYPE_TLS_PASSTHROUGH
                    .equals(site.get(SiteModel.SITE_TYPE))) return;
            row.set(FORCE_SSL, false);
            row.set(EXCLUDE_FROM_LETSENCRYPT, true);
            validateTlsPassthroughValues(row);
        });
    }

    /** Enforces the model-level invariants of an encrypted, pre-HTTP route. */
    public static void validateTlsPassthroughValues(Row row) {
        String path = (String) effective(row, PATH);
        if (path != null && !path.isBlank() && !"/".equals(path.trim())) {
            throw violation("path", path, "tls_passthrough_no_path");
        }
        if (Boolean.TRUE.equals(effective(row, STRIP_PATH))) {
            throw violation("strip_path", true, "tls_passthrough_no_http_options");
        }
        Object certificateId = effective(row, CERTIFICATE_ID);
        if (certificateId != null) {
            throw violation("certificate_id", certificateId, "tls_passthrough_backend_certificate");
        }
        if (Boolean.TRUE.equals(effective(row, HSTS_ENABLED))
                || Boolean.TRUE.equals(effective(row, HSTS_SUBDOMAINS))) {
            throw violation("hsts_enabled", effective(row, HSTS_ENABLED),
                "tls_passthrough_no_http_options");
        }
        if (hasValues(effective(row, CUSTOM_HEADERS)) || hasValues(effective(row, RESPONSE_HEADERS))) {
            throw violation("custom_headers", effective(row, CUSTOM_HEADERS),
                "tls_passthrough_no_http_options");
        }
    }

    private static Object effective(Row row, Field<?, ?> field) {
        if (row.has(field.getName())) return row.get(field.getName());
        if (!row.has(ID.getName())) return null;
        Row stored = Models.get(SiteDomainModel.class).findById(row.get(ID));
        return stored != null ? stored.get(field.getName()) : null;
    }

    private static boolean hasValues(Object value) {
        return value instanceof java.util.Map<?, ?> map && !map.isEmpty();
    }

    private static Violations violation(String field, Object value, String key) {
        return Violations.ofField(field, value,
            Microcopy.of(key).withFilter("scope", "violations"));
    }

    public List<Row> findBySiteId(int siteId) {
        return find()
            .where(SITE_ID.eq(siteId))
            .all();
    }

    public List<Row> findByHostname(String hostname) {
        return find()
            .where(HOSTNAME.eq(hostname))
            .all();
    }

    @Override
    public Identifier getModelId() { return MODEL_ID; }

    @Override
    public Field<?, ?> getPrimaryKeyField() { return ID; }

    @Override
    public String getModelName() { return "SiteDomain"; }

    @Override
    public String getTableName() { return "site_domains"; }

    @Override
    public Schema getSchema() { return SCHEMA; }
}
