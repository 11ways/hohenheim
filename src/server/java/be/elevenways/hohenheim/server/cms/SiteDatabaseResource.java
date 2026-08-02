package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.model.DatabaseModel;
import be.elevenways.hohenheim.model.SiteDatabaseModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.server.database.DatabaseEnvInjection;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.server.sitetype.SiteTypes;
import be.elevenways.hohenheim.sitetype.SiteTypeInfo;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.cms.common.panel.NavGroup;
import be.elevenways.zenit.cms.common.resource.ResourceParent;
import be.elevenways.zenit.cms.common.resource.RowResource;
import be.elevenways.zenit.cms.common.schema.ColumnSpec;
import be.elevenways.zenit.cms.common.schema.TableSpec;
import be.elevenways.zenit.common.conduit.Conduit;
import be.elevenways.zenit.common.edit.FormSpec;
import be.elevenways.zenit.common.edit.RelationPick;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.common.validation.Violations;
import be.elevenways.zenit.common.ui.Icon;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.Map;
import java.util.Locale;

/**
 * Site-database attachments: the join records that drive connection-variable
 * injection into a site's processes. Hidden from the sidebar -- reached
 * through a site's Databases tab.
 */
public final class SiteDatabaseResource extends RowResource {

    /** env prefixes become variable names: a letter, then letters/digits/underscores. */
    private static final String PREFIX_PATTERN = "[A-Za-z][A-Za-z0-9_]*";

    private final FormSpec formSpec = FormSpec.builder()
        .add(RelationPick.of(SiteDatabaseModel.SITE_ID, SiteModel.MODEL_ID).build())
        .add(RelationPick.of(SiteDatabaseModel.DATABASE_ID, DatabaseModel.MODEL_ID).build())
        .add(SiteDatabaseModel.ENV_PREFIX)
        .build();

    private final TableSpec<Row> tableSpec = TableSpec.<Row>builder()
        .column(ColumnSpec.fromField(SiteDatabaseModel.SITE_ID)
            .relation(RelationPick.of(SiteDatabaseModel.SITE_ID, SiteModel.MODEL_ID).build()).build())
        .column(ColumnSpec.fromField(SiteDatabaseModel.DATABASE_ID)
            .relation(RelationPick.of(SiteDatabaseModel.DATABASE_ID, DatabaseModel.MODEL_ID).build()).build())
        .column(ColumnSpec.fromField(SiteDatabaseModel.ENV_PREFIX).build())
        .build();

    @Override public @NonNull Identifier id() { return Identifier.of("hohenheim", "site_database"); }
    @Override public @NonNull Microcopy label() { return Microcopy.of("plural").withFilter("scope", "site_database"); }
    @Override public @NonNull String slug() { return "site-databases"; }
    @Override public @NonNull Model model() { return Models.get(SiteDatabaseModel.class); }
    @Override public @NonNull FormSpec formSpec() { return this.formSpec; }
    @Override public @NonNull TableSpec<Row> tableSpec() { return this.tableSpec; }
    @Override public @NonNull NavGroup navGroup() { return HohenheimPanel.PROXY_GROUP; }
    @Override public int navOrder() { return 25; }
    @Override public @NonNull Icon icon() { return Icon.of("database"); }
    @Override public boolean showInNav() { return false; }

    @Override
    public @org.checkerframework.checker.nullness.qual.Nullable ResourceParent<Row> parent() {
        return ResourceParent.<Row>of("sites", row -> row.get(SiteDatabaseModel.SITE_ID)).tab("databases");
    }

    /** The site's Databases tab links here with ?site_id= so the pick is preselected. */
    @Override
    public @NonNull Map<String, Object> createValues(@NonNull Conduit conduit) {
        String siteId = conduit.getQueryParam("site_id");
        if (siteId != null && !siteId.isEmpty()) {
            try {
                return Map.of("site_id", Integer.parseInt(siteId),
                    "env_prefix", SiteDatabaseModel.DEFAULT_PREFIX);
            } catch (NumberFormatException ignored) {
                // Malformed prefill: render the bare form.
            }
        }
        return Map.of("env_prefix", SiteDatabaseModel.DEFAULT_PREFIX);
    }

    @Override
    public @NonNull Object persistRow(@NonNull Map<String, Object> coerced,
                                      @NonNull AccessContext accessContext) {
        validate(coerced, null);
        return super.persistRow(coerced, accessContext);
    }

    @Override
    public void updateRow(@NonNull Row existing, @NonNull Map<String, Object> coerced,
                          @NonNull AccessContext accessContext) {
        validate(coerced, existing);
        super.updateRow(existing, coerced, accessContext);
    }

    /**
     * Reachability is enforced HERE, at link time, so injection never emits credentials
     * the site's runtime cannot connect to: the site type must run host processes
     * (docker-site containers can't reach a 127.0.0.1-published port) and the database
     * must live on the local server (it publishes on its own host's loopback only).
     */
    private void validate(@NonNull Map<String, Object> coerced, @Nullable Row existing) {
        Integer siteId = intOf(coerced.get("site_id"),
            existing != null ? existing.get(SiteDatabaseModel.SITE_ID) : null);
        if (siteId == null) {
            throw Violations.ofField("site_id", null, CmsSupport.violationText("site_required"));
        }
        Row site = Models.get(SiteModel.class).find().where(SiteModel.ID.eq(siteId)).first();
        if (site == null) {
            throw Violations.ofField("site_id", siteId, CmsSupport.violationText("site_missing"));
        }
        String siteType = site.get(SiteModel.SITE_TYPE);
        SiteTypeInfo typeInfo = SiteTypes.getHandler(siteType);
        if (typeInfo == null || !typeInfo.supportsEnvInjection()) {
            throw Violations.ofField("site_id", siteId,
                CmsSupport.violationText("site_type_no_injection")
                    .withArg("type", typeInfo != null ? typeInfo.getDisplayName() : siteType));
        }

        Integer databaseId = intOf(coerced.get("database_id"),
            existing != null ? existing.get(SiteDatabaseModel.DATABASE_ID) : null);
        if (databaseId == null) {
            throw Violations.ofField("database_id", null, CmsSupport.violationText("database_required"));
        }
        Row database = Models.get(DatabaseModel.class).find()
            .where(DatabaseModel.ID.eq(databaseId)).first();
        if (database == null) {
            throw Violations.ofField("database_id", databaseId,
                CmsSupport.violationText("database_missing"));
        }
        Integer serverId = database.get(DatabaseModel.SERVER_ID);
        if (serverId != null && serverId != ServerModel.localServerId()) {
            throw Violations.ofField("database_id", databaseId,
                CmsSupport.violationText("database_remote")
                    .withArg("name", database.get(DatabaseModel.NAME))
                    .withArg("server", ServerModel.nameOf(serverId)));
        }

        String prefix = prefixOf(coerced, existing);
        if (!prefix.matches(PREFIX_PATTERN)) {
            throw Violations.ofField("env_prefix", prefix, CmsSupport.violationText("prefix_format"));
        }

        Integer existingId = existing != null ? existing.get(SiteDatabaseModel.ID) : null;
        for (Row link : Models.get(SiteDatabaseModel.class).findBySiteId(siteId)) {
            if (existingId != null && existingId.equals(link.get(SiteDatabaseModel.ID))) {
                continue;
            }
            if (databaseId.equals(link.get(SiteDatabaseModel.DATABASE_ID))) {
                throw Violations.ofField("database_id", databaseId,
                    CmsSupport.violationText("database_already_attached"));
            }
            String linkPrefix = DatabaseEnvInjection.normalizedPrefix(link.get(SiteDatabaseModel.ENV_PREFIX));
            if (linkPrefix.equalsIgnoreCase(prefix)) {
                throw Violations.ofField("env_prefix", prefix,
                    CmsSupport.violationText("prefix_taken").withArg("prefix", prefix.toUpperCase(Locale.ROOT)));
            }
        }
    }

    private static @NonNull String prefixOf(@NonNull Map<String, Object> coerced, @Nullable Row existing) {
        Object submitted = coerced.containsKey("env_prefix") ? coerced.get("env_prefix")
            : existing != null ? existing.get(SiteDatabaseModel.ENV_PREFIX) : null;
        String prefix = submitted != null ? String.valueOf(submitted).trim() : "";
        return prefix.isEmpty() ? SiteDatabaseModel.DEFAULT_PREFIX : prefix;
    }

    private static @Nullable Integer intOf(@Nullable Object submitted, @Nullable Integer fallback) {
        return submitted instanceof Integer i ? i : fallback;
    }
}
