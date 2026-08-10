package be.elevenways.hohenheim.model;

import be.elevenways.hohenheim.HohenheimFormCopy;
import be.elevenways.hohenheim.net.Hostnames;
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

    /**
     * {@link #MATCH_TYPE} value for a regex hostname match. Matching is case-INSENSITIVE
     * (request hostnames are pre-folded to lowercase; see HostnameRegex); the pattern
     * SOURCE keeps its case in storage, and RouteClaims folds the claim KEY.
     */
    public static final String MATCH_REGEX = "regex";

    /**
     * THE stored/canonical hostname form, shared by the beforeValidate hook, the
     * route-identity check and the dispatcher: trimmed, lowercased and stripped of the FQDN
     * root dot, except for regex sources (lowercasing pattern TEXT would flip class escapes
     * like {@code \S} into {@code \s}, and a trailing {@code .} is a metacharacter there
     * rather than the root label -- case-insensitive matching and the case-folded claim key
     * live in HostnameRegex and RouteClaims respectively).
     *
     * AIDEV-NOTE: the root-dot fold is load-bearing, not cosmetic. Without it
     * {@code victim.test.} and {@code victim.test} spelled two claim keys that
     * {@link be.elevenways.hohenheim.server.proxy.HostnamePatterns} could not intersect
     * either (a trailing empty label matches nothing), so both the conflict scan and the
     * release quarantine let a second tenant claim the dotted spelling of a name the first
     * one serves -- and TLS then answered it with the FIRST tenant's certificate, because
     * SNI cannot carry the dot at all. Fold here and the two spellings are one claim
     * everywhere at once.
     */
    public static @Nullable String canonicalHostname(@Nullable String hostname, @Nullable String matchType) {
        if (hostname == null) {
            return null;
        }
        String trimmed = hostname.trim();
        return MATCH_REGEX.equals(matchType)
            ? trimmed : Hostnames.stripTrailingDots(BlastString.lower(trimmed));
    }

    /**
     * THE routing tier a row lands in, which is NOT simply its {@link #MATCH_TYPE} column:
     * a hostname carrying glob characters routes as a wildcard whatever the column says.
     *
     * AIDEV-NOTE: every consumer that asks "what tier is this row" must come here --
     * the dispatcher's route-table build, the write-time overlap scan, the certificate
     * coverage walk and the tenant refusal. A policy that reasons about the COLUMN while a
     * consumer derives the same fact from the CONTENT is the bug shape this repo keeps
     * paying for: {@code TenantWrites} refused {@code match_type=wildcard} and happily
     * stored {@code hostname=*.victim.test, match_type=exact}, which the dispatcher then
     * routed as a wildcard and {@code HostnameAuthority} read as authority over every
     * sibling name under the victim's domain.
     *
     * @return one of the {@code MATCH_*} constants
     */
    public static @NonNull String effectiveMatchType(@Nullable String hostname,
                                                     @Nullable String matchType) {
        if (MATCH_REGEX.equals(matchType)) {
            return MATCH_REGEX;
        }
        if (MATCH_WILDCARD.equals(matchType) || Hostnames.hasGlobCharacters(hostname)) {
            return MATCH_WILDCARD;
        }
        return MATCH_EXACT;
    }

    /**
     * Refuse a hostname whose SPELLING is not a name of the tier it will route in.
     *
     * AIDEV-NOTE: judged on the EFFECTIVE tier, so a glob-shaped hostname is held to the
     * glob grammar even when its column says exact -- refusing it as an invalid exact name
     * would be the column-versus-content split all over again. Absence is deliberately not
     * a syntax question: an empty hostname is answered by SiteDomainResource's
     * {@code hostname_required}, which is the refusal an operator can act on.
     *
     * @throws Violations anchored on the hostname field
     */
    public static void validateHostnameSyntax(@Nullable String hostname, @Nullable String matchType) {
        if (hostname == null || hostname.isBlank()) {
            return;
        }
        String tier = effectiveMatchType(hostname, matchType);
        boolean valid = switch (tier) {
            // A regex source is not a name, so the only thing decidable about it here is
            // that it can occupy one field of a route tuple. A pattern that does not
            // compile routes nothing (SiteDispatcher drops it).
            case MATCH_REGEX -> Hostnames.isSingleLine(hostname);
            case MATCH_WILDCARD -> Hostnames.isValidGlob(hostname);
            default -> Hostnames.isValidLabelSequence(hostname);
        };
        if (!valid) {
            throw violation(HOSTNAME.getName(), hostname, "hostname_invalid");
        }
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
            String matchType = (String) effective(row, MATCH_TYPE);
            if (hostname != null) {
                String canonical = canonicalHostname(hostname, matchType);
                row.set(HOSTNAME, canonical);
                validateHostnameSyntax(canonical, matchType);
                // AIDEV-NOTE: the COLUMN is normalized to the tier the row actually routes
                // in, so the two can never disagree in storage at all. Validating the tier
                // without writing it back would leave a glob-under-exact row storable by an
                // operator, and a whole set of consumers read this column RAW to decide
                // real things -- CertificateRequestPage and ManagePanel gate certificate
                // orders on `MATCH_EXACT.equals(column)`, SiteDeploymentsPage queries on it,
                // TlsPassthroughRoutes builds SNI routes from it. Each of those would have
                // to re-derive the tier, which is exactly the duplication that produced the
                // takeover. One write here makes every raw reader correct by construction.
                row.set(MATCH_TYPE, effectiveMatchType(canonical, matchType));
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
