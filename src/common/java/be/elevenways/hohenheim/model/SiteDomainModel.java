package be.elevenways.hohenheim.model;

import be.elevenways.hohenheim.HohenheimFormCopy;
import be.elevenways.protoblast.common.util.BlastString;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.field.*;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.Schema;
import java.util.List;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.zenit.common.orm.model.Models;
import org.checkerframework.checker.nullness.qual.NonNull;
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
        return MATCH_REGEX.equals(matchType) ? trimmed : BlastString.lower(trimmed);
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
    /**
     * The canonical route tuple this row claims while its site is LIVE, or null when it
     * claims nothing (disabled site, soft-deleted site, or a row that lost a heal pass).
     *
     * AIDEV-NOTE: derived, never operator-editable, and backed by a UNIQUE index
     * (M045_SiteDomainRouteClaims). Concurrency is handled by the serialized write
     * transaction that this model's save() declares (see RouteClaims): the conflict scan
     * in SiteDomainResource runs inside the same transaction as the claim write, so it
     * cannot go stale, and it is what refuses OVERLAPPING listener sets whose keys
     * differ; the index refuses identical keys even for writers that dodge the
     * transaction. NULL means "no claim", so staged duplicates on disabled sites stay
     * legal and a soft-deleted site owns nothing. Backend note: this rides
     * NULLs-are-distinct unique-index semantics (SQLite -- hohenheim's only backend --
     * plus PostgreSQL/MySQL/Firebird); MongoDB would need a partial index for the same
     * shape.
     */
    public static final StringField LIVE_ROUTE_KEY = SCHEMA.addField(
        StringField.builder().name("live_route_key").filterable(false).build());

    /**
     * GeneratedRows attribution (the DnsRecordModel discipline): DERIVED inside the
     * system scope, refused when a caller supplies them, and the reclaim anchor -- a
     * preview's generated hostname row is removed by exact attribution only, so a
     * hand-authored row with the same hostname is never adopted or deleted.
     */
    public static final StringField GENERATED_BY = SCHEMA.addField(
        StringField.builder().name("generated_by").filterable(false).build());

    public static final StringField GENERATED_FOR_MODEL = SCHEMA.addField(
        StringField.builder().name("generated_for_model").filterable(false).build());

    public static final IntegerField GENERATED_FOR_ID = SCHEMA.addField(
        IntegerField.builder().name("generated_for_id").build());

    public static final DateTimeField GENERATED_AT = SCHEMA.addField(
        DateTimeField.builder().name("generated_at").build());

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

    /**
     * The value a partial write will END UP with: the staged value when the write carries
     * the field, else the stored one. Every invariant over a domain row must read through
     * this, because a CMS update stages only the changed columns.
     */
    public static Object effective(Row row, Field<?, ?> field) {
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

    /**
     * Every domain save is ONE write transaction: the route-conflict scan
     * (beforeValidate), the live-route claim stamp (beforeWrite) and the row write
     * commit or fail together.
     *
     * AIDEV-NOTE: this transaction IS the route invariant for overlapping listener
     * sets -- see RouteClaims. Without it the scan is a read-then-write with a window,
     * and Model.save only wraps a transaction for revisionable schemas, which this
     * model is not. Do not remove.
     */
    @Override
    public Row save(@NonNull Row row) {
        Row[] result = new Row[1];
        this.requireDatasource().withTransaction(tx -> result[0] = super.save(row));
        return result[0];
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
