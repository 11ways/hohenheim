package be.elevenways.hohenheim.model;

import be.elevenways.hohenheim.HohenheimFormCopy;
import be.elevenways.hohenheim.sitetype.SiteTypeRegistry;
import be.elevenways.hohenheim.source.GitSourceSchema;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.common.orm.behaviour.RevisionableBehaviour;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.datasource.context.RemoveFromDatasource;
import be.elevenways.zenit.common.orm.field.*;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.orm.model.Schema;
import be.elevenways.zenit.common.orm.query.QueryBuilder;
import be.elevenways.zenit.common.orm.query.QueryContext;
import be.elevenways.zenit.common.orm.query.SortOrder;
import be.elevenways.zenit.common.orm.query.criteria.Criteria;

import java.util.ArrayList;
import java.util.List;
import be.elevenways.zenit.common.validation.Violations;
import org.checkerframework.checker.nullness.qual.NonNull;

public class SiteModel extends Model {

    public static final Identifier MODEL_ID = Identifier.of("hohenheim", "site");
    public static final Schema SCHEMA = new Schema();

    /** {@link #SOURCE} value for git-provisioned sites. */
    public static final String SOURCE_GIT = "git";

    /** {@link #STATUS} value for an active site. */
    public static final String STATUS_ACTIVE = "active";
    public static final String SITE_TYPE_TLS_PASSTHROUGH = "hohenheim:tls_passthrough";

    public static final IntegerField ID = SCHEMA.addField(IntegerField.builder().name("id").build());
    public static final StringField NAME = SCHEMA.addField(StringField.builder().name("name")
        .label(HohenheimFormCopy.label("name"))
        .build());
    public static final StringField SLUG = SCHEMA.addField(StringField.builder().name("slug").build());

    // RegistryEnumField: values come from SiteTypeRegistry at runtime
    public static final EnumField SITE_TYPE = SCHEMA.addField(
        RegistryEnumField.builder("site_type")
            .registry(SiteTypeRegistry.REGISTRY)
            .label(HohenheimFormCopy.label("site_type"))
            .help(HohenheimFormCopy.help("site_type"))
            .build());

    public static final BooleanField ENABLED = SCHEMA.addField(BooleanField.builder("enabled")
        .defaultValue(true)
        .label(HohenheimFormCopy.label("enabled"))
        .help(HohenheimFormCopy.help("enabled"))
        .build());

    // Polymorphic settings: schema resolved dynamically from site_type
    public static final SchemaField SETTINGS = SCHEMA.addField(
        SchemaField.builder("settings")
            .schemaFrom("site_type")
            .label(HohenheimFormCopy.label("settings"))
            .build());

    // Source provisioning: null/"local" = local files, "git" = git-provisioned
    public static final EnumField SOURCE = SCHEMA.addField(
        EnumField.builder("source")
            .value("local", value -> value.displayName("Local files")
                .label(Microcopy.of("local_files").withFilter("scope", "site_source"))
                .icon("folder"))
            .value(SOURCE_GIT, value -> value.displayName("Git repository")
                .label(Microcopy.of("git_repository").withFilter("scope", "site_source"))
                .icon("code-branch")
                .schema(GitSourceSchema.SCHEMA))
            .defaultValue("local")
            .label(HohenheimFormCopy.label("source"))
            .help(HohenheimFormCopy.help("source"))
            .build());

    // Git-specific settings, only relevant when source == "git"
    public static final SchemaField SOURCE_SETTINGS = SCHEMA.addField(
        SchemaField.builder("source_settings")
            .schemaFrom(SOURCE)
            .label(HohenheimFormCopy.label("source_settings"))
            .build());

    public static final StringField DESCRIPTION = SCHEMA.addField(StringField.builder().name("description")
        .label(HohenheimFormCopy.label("description"))
        .build());
    public static final EnumField STATUS = SCHEMA.addField(EnumField.builder("status")
        .value(STATUS_ACTIVE, v -> v.displayName("Active").icon("circle-check").color("success"))
        .build());
    public static final IntegerField ACCESS_LIST_ID = SCHEMA.addField(IntegerField.builder().name("access_list_id")
        .label(HohenheimFormCopy.label("access_list"))
        .help(HohenheimFormCopy.help("access_list"))
        .build());
    public static final IntegerField AUTH_PROVIDER_ID = SCHEMA.addField(IntegerField.builder().name("auth_provider_id")
        .label(HohenheimFormCopy.label("auth_provider"))
        .help(HohenheimFormCopy.help("auth_provider"))
        .build());
    // Raw spamservice client key for env injection into this site's managed
    // processes. AIDEV-NOTE: deliberately a dedicated column, NOT a key inside
    // the polymorphic SETTINGS map -- the dynamic (schemaFrom) form entry
    // rewrites that map on every admin save and would silently drop the key.
    // Kept RAW because the value must be re-injected on every child spawn
    // (spamservice stores only the hash). Tradeoff: an attacker who can read
    // the sites table can impersonate this site's reporting client --
    // acceptable, since that table already holds site env secrets.
    public static final StringField SECURITY_REPORT_TOKEN = SCHEMA.addField(
        StringField.builder().name("security_report_token").secret().encrypted().build());

    public static final DateTimeField CREATED_AT = SCHEMA.addField(DateTimeField.builder().name("created_at").build());
    public static final DateTimeField UPDATED_AT = SCHEMA.addField(DateTimeField.builder().name("updated_at").build());
    public static final DateTimeField DELETED_AT = SCHEMA.addField(DateTimeField.builder().name("deleted_at").build());

    // Sites are the proxy's central config records: keep full snapshots so
    // admins can diff and restore them from the CMS history tab.
    public static final RevisionableBehaviour REVISIONABLE =
        SCHEMA.addBehaviour(RevisionableBehaviour.create(50));

    static {
        // AIDEV-NOTE: Sites are trashed by hand (SiteResource stamps deleted_at through
        // save(), there is no SoftDeleteBehaviour here), so nothing declares deleted_at
        // as lifecycle state for us. Without this the CMS revision-restore endpoint
        // would replay a snapshot taken while the site was live -- deleted_at = null
        // included -- and silently bring a deleted site, its routes and its
        // certificates back.
        SCHEMA.addLifecycleField(DELETED_AT);

        SCHEMA.addBeforeValidateHook(context -> {
            Row row = context.getRow();
            if (row == null || !SITE_TYPE_TLS_PASSTHROUGH.equals(effective(row, SITE_TYPE))) return;
            Object authProvider = effective(row, AUTH_PROVIDER_ID);
            if (authProvider != null) {
                throw violation("auth_provider_id", authProvider, "tls_passthrough_no_http_auth");
            }
            Object accessList = effective(row, ACCESS_LIST_ID);
            if (accessList != null) {
                throw violation("access_list_id", accessList, "tls_passthrough_no_access_list");
            }
            Object source = effective(row, SOURCE);
            if (source != null && !"local".equals(source)) {
                throw violation("source", source, "tls_passthrough_local_only");
            }
            Integer id = row.has(ID.getName()) ? row.get(ID) : null;
            if (id != null) {
                for (Row domain : Models.get(SiteDomainModel.class).findBySiteId(id)) {
                    SiteDomainModel.validateTlsPassthroughValues(domain);
                }
            }
        });
        // AIDEV-NOTE: a HARD site delete used to leave its site_domains rows behind holding
        // a non-null live_route_key. The conflict scan skips them (the site lookup returns
        // null, so they read as not-live) but the UNIQUE index on live_route_key still
        // enforces, so the next claimant got a DuplicateKeyException whose holder name
        // resolved to null: the operator was told the route is taken by "?" and the hostname
        // was permanently unclaimable with no nameable holder. Only tests hard-delete sites
        // today; that is the reason it stayed invisible, not a reason to leave it.
        SCHEMA.addBeforeRemoveHook(SiteModel::cascadeDomainRows);
        SCHEMA.addAfterSaveHook(context -> {
            Row site = context.getRow();
            if (site == null || !SITE_TYPE_TLS_PASSTHROUGH.equals(effective(site, SITE_TYPE))) return;
            Integer id = site.get(ID);
            if (id == null) return;
            SiteDomainModel domains = Models.get(SiteDomainModel.class);
            domains.find().where(SiteDomainModel.SITE_ID.eq(id))
                .assign(SiteDomainModel.FORCE_SSL, false)
                .assign(SiteDomainModel.EXCLUDE_FROM_LETSENCRYPT, true)
                .updateAll();
        });
    }

    /**
     * Remove the domain rows of every site a pending delete will remove.
     *
     * A remove hook fires ONCE for the whole delete with a criteria-only context whose row
     * is null, so the doomed ids are read back from the criteria here (the framework's
     * documented seam, mirroring PortLedger.captureDoomedOwners). Deleting the children
     * BEFORE the parents also keeps the claim release ordered: SiteDomainModel's own remove
     * hook still sees rows whose site exists.
     */
    private static void cascadeDomainRows(RemoveFromDatasource context) {
        Model sites = context.getModel();
        if (sites == null) {
            return;
        }
        QueryContext queryContext = context.getQueryContext();
        Criteria criteria = queryContext != null ? queryContext.getCriteria() : null;
        QueryBuilder<Row> builder = sites.find();
        if (criteria != null) {
            builder.where(criteria);
        }
        List<Integer> doomed = new ArrayList<>();
        for (Row site : builder.all()) {
            if (site.get(ID) instanceof Integer siteId) {
                doomed.add(siteId);
            }
        }
        if (!doomed.isEmpty()) {
            Models.get(SiteDomainModel.class).find()
                .where(SiteDomainModel.SITE_ID.in(doomed)).delete();
        }
    }

    private static Object effective(Row row, Field<?, ?> field) {
        if (row.has(field.getName())) return row.get(field.getName());
        if (!row.has(ID.getName())) return null;
        Row stored = Models.get(SiteModel.class).findById(row.get(ID));
        return stored != null ? stored.get(field.getName()) : null;
    }

    private static Violations violation(String field, Object value, String key) {
        return Violations.ofField(field, value,
            Microcopy.of(key).withFilter("scope", "violations"));
    }

    /**
     * Every site save is ONE write transaction by DECLARATION: the enable scan
     * (beforeValidate), the route-claim restamp (beforeWrite) and the row write commit
     * or fail together.
     *
     * AIDEV-NOTE: Model.save already wraps revisionable schemas in a transaction, but
     * the route invariant (see RouteClaims) must not ride that coincidence -- removing
     * REVISIONABLE would silently reopen the scan-then-claim window. The nested
     * transaction joins the outer one, so this costs nothing today.
     */
    @Override
    public Row save(@NonNull Row row) {
        Row[] result = new Row[1];
        this.requireDatasource().withTransaction(tx -> result[0] = super.save(row));
        return result[0];
    }

    public List<Row> findEnabled() {
        return find()
            .where(ENABLED.eq(true))
            .where(DELETED_AT.isNull())
            .orderBy(NAME, SortOrder.ASC)
            .all();
    }

    public List<Row> findActive() {
        return find()
            .where(DELETED_AT.isNull())
            .orderBy(CREATED_AT, SortOrder.DESC)
            .all();
    }

    @Override
    public Identifier getModelId() { return MODEL_ID; }

    @Override
    public Field<?, ?> getPrimaryKeyField() { return ID; }

    @Override
    public String getModelName() { return "Site"; }

    @Override
    public String getTableName() { return "sites"; }

    @Override
    public Schema getSchema() { return SCHEMA; }
}
