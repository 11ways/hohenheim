package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.model.DatabaseModel;
import be.elevenways.hohenheim.model.SiteDatabaseModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.server.database.DatabaseEnvInjection;
import be.elevenways.hohenheim.server.docker.ServerService;
import be.elevenways.hohenheim.server.sitetype.SiteTypes;
import be.elevenways.hohenheim.sitetype.SiteTypeInfo;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.cms.common.panel.NavGroup;
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
import be.elevenways.zenit.common.ui.Icon;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.Map;

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
        .column(ColumnSpec.fromField(SiteDatabaseModel.SITE_ID).build())
        .column(ColumnSpec.fromField(SiteDatabaseModel.DATABASE_ID).build())
        .column(ColumnSpec.fromField(SiteDatabaseModel.ENV_PREFIX).build())
        .build();

    @Override public @NonNull Identifier id() { return Identifier.of("hohenheim", "site_database"); }
    @Override public @NonNull Microcopy label() { return Microcopy.of("hohenheim.site_database.plural"); }
    @Override public @NonNull String slug() { return "site-databases"; }
    @Override public @NonNull Model model() { return Models.get(SiteDatabaseModel.class); }
    @Override public @NonNull FormSpec formSpec() { return this.formSpec; }
    @Override public @NonNull TableSpec<Row> tableSpec() { return this.tableSpec; }
    @Override public @NonNull NavGroup navGroup() { return HohenheimPanel.PROXY_GROUP; }
    @Override public int navOrder() { return 25; }
    @Override public @NonNull Icon icon() { return Icon.of("database"); }
    @Override public boolean showInNav() { return false; }

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
            throw new IllegalStateException("A site is required");
        }
        Row site = Models.get(SiteModel.class).find().where(SiteModel.ID.eq(siteId)).first();
        if (site == null) {
            throw new IllegalStateException("That site does not exist");
        }
        String siteType = site.get(SiteModel.SITE_TYPE);
        SiteTypeInfo typeInfo = SiteTypes.getHandler(siteType);
        if (typeInfo == null || !typeInfo.supportsEnvInjection()) {
            throw new IllegalStateException("Sites of type '"
                + (typeInfo != null ? typeInfo.getDisplayName() : siteType)
                + "' cannot receive database credentials: only host-process site types"
                + " (Node.js, Alchemy, Command) can reach a managed database");
        }

        Integer databaseId = intOf(coerced.get("database_id"),
            existing != null ? existing.get(SiteDatabaseModel.DATABASE_ID) : null);
        if (databaseId == null) {
            throw new IllegalStateException("A database is required");
        }
        Row database = Models.get(DatabaseModel.class).find()
            .where(DatabaseModel.ID.eq(databaseId)).first();
        if (database == null) {
            throw new IllegalStateException("That database does not exist");
        }
        String server = database.get(DatabaseModel.SERVER_NAME);
        if (server != null && !server.isBlank() && !ServerService.LOCAL.equals(server)) {
            throw new IllegalStateException("Database '" + database.get(DatabaseModel.NAME)
                + "' runs on server '" + server + "': its port is only published on that host's"
                + " loopback, so sites on this server cannot reach it");
        }

        String prefix = prefixOf(coerced, existing);
        if (!prefix.matches(PREFIX_PATTERN)) {
            throw new IllegalStateException(
                "Env prefix must start with a letter and contain only letters, digits, and underscores");
        }

        Integer existingId = existing != null ? existing.get(SiteDatabaseModel.ID) : null;
        for (Row link : Models.get(SiteDatabaseModel.class).findBySiteId(siteId)) {
            if (existingId != null && existingId.equals(link.get(SiteDatabaseModel.ID))) {
                continue;
            }
            if (databaseId.equals(link.get(SiteDatabaseModel.DATABASE_ID))) {
                throw new IllegalStateException("That database is already attached to this site");
            }
            String linkPrefix = DatabaseEnvInjection.normalizedPrefix(link.get(SiteDatabaseModel.ENV_PREFIX));
            if (linkPrefix.equalsIgnoreCase(prefix)) {
                throw new IllegalStateException("Env prefix '" + prefix.toUpperCase()
                    + "' is already used by another database on this site");
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
