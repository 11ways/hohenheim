package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.HohenheimEndpoints;
import be.elevenways.hohenheim.model.DatabaseModel;
import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.hohenheim.server.database.TenantDatabases;
import be.elevenways.protoblast.common.http.Uri;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.cms.common.access.AccessDecision;
import be.elevenways.zenit.cms.common.access.AccessFunction;
import be.elevenways.zenit.cms.common.access.QueryPredicate;
import be.elevenways.zenit.cms.common.action.RowAction;
import be.elevenways.zenit.cms.common.resource.RecordScopedPage;
import be.elevenways.zenit.cms.common.resource.ResourceFieldBinding;
import be.elevenways.zenit.cms.common.schema.ColumnSpec;
import be.elevenways.zenit.cms.common.schema.TableSpec;
import be.elevenways.zenit.common.conduit.Conduit;
import be.elevenways.zenit.common.edit.FieldFormEntryRegistry;
import be.elevenways.zenit.common.edit.FormSpec;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.query.criteria.Criteria;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.common.security.Permission;
import be.elevenways.zenit.common.ui.Icon;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.List;
import java.util.Map;

/**
 * The /manage view over managed databases: the tenant sees exactly the databases they
 * hold {@code view} on (which every other verb implies), and nothing else exists as far
 * as this surface is concerned.
 *
 * What is DROPPED relative to the admin resource, and why:
 *
 * - The CREATE form is name + engine, and nothing else. The image, the host, the resource
 *   caps and every credential are derived by {@link TenantDatabases#allocate} -- rendering
 *   an input the funnel discards would be a control that lies about what it does.
 * - No CREDENTIALS on the record form. A {@code .secret()} field renders MASKED by the
 *   framework's own FormSecrets pipeline, so the form could never be the place the
 *   password is read; the Credentials tab is, and it answers to its own capability.
 * - No RESTORE tab. It uploads arbitrary SQL executed as the engine superuser and renders
 *   the credentials besides; there is no delegated verb for it (see
 *   HohenheimAccess.declareGrantableModels) and so no surface here.
 * - No force-destroy row action: the recorded escape hatch for an unreachable host is an
 *   operator decision about the operator's own machine.
 */
public final class ManageDatabaseResource extends DatabaseResource {

    private final FormSpec manageFormSpec = FormSpec.builder()
        .add(DatabaseModel.NAME)
        .add(FieldFormEntryRegistry.INSTANCE.deriveEntry(DatabaseModel.ENGINE))
        .build();

    private final TableSpec<Row> manageTableSpec = TableSpec.<Row>builder()
        .column(ColumnSpec.fromField(DatabaseModel.NAME).build())
        .column(ColumnSpec.fromField(DatabaseModel.ENGINE).build())
        .column(ColumnSpec.fromField(DatabaseModel.DB_NAME).copyable().build())
        .column(ColumnSpec.fromField(DatabaseModel.STATUS).filterable().build())
        .build();

    @Override public @NonNull Identifier id() { return Identifier.of("hohenheim", "manage_database"); }
    @Override public @NonNull FormSpec formSpec() { return this.manageFormSpec; }
    @Override public @NonNull TableSpec<Row> tableSpec() { return this.manageTableSpec; }

    /**
     * None. The admin resource pins {@code status} read-only, and a binding must name a
     * path this resource's own formSpec offers -- this one offers name and engine, both
     * ordinary. (The framework refuses a binding for an absent path rather than ignoring
     * it, which is how the inherited one surfaced.)
     */
    @Override
    public @NonNull List<ResourceFieldBinding> fieldBindings() {
        return List.of();
    }

    /** The allocation form starts blank: everything else about the record is derived. */
    @Override
    public @NonNull Map<String, Object> createValues(@NonNull Conduit conduit) {
        return formSpec().defaultValues();
    }

    /**
     * Hide AND enforce: without the type-level allocation permission the create link is
     * absent and the submit answers 403. {@link TenantDatabases#allocate} asks the same
     * question again, because the API and any later caller reach the funnel, not this.
     */
    @Override
    public @Nullable Permission createPermission() {
        return TenantDatabases.DATABASES_CREATE;
    }

    /**
     * Admins see every database; everyone else only the ones the walk confirms
     * {@code view} on. This is what makes an unowned id read as MISSING (zenit-cms 404s
     * an out-of-scope load) rather than forbidden.
     */
    @Override
    public @NonNull AccessFunction<Row> accessFunction() {
        return ctx -> {
            Criteria scope = HohenheimAccess.databaseScope(ctx, HohenheimAccess.VIEW);
            return scope == null ? AccessDecision.allowAll()
                : AccessDecision.allow(QueryPredicate.of(scope));
        };
    }

    /**
     * THE tenant allocation funnel, in one call: the namespaced name, the credentials,
     * the placement, the creator's manage grant and the quota charge.
     */
    @Override
    public @NonNull Object persistRow(@NonNull Map<String, Object> coerced,
                                      @NonNull AccessContext accessContext) {
        return rowKey(TenantDatabases.allocate(accessContext,
            coerced.get(DatabaseModel.NAME.getName()),
            coerced.get(DatabaseModel.ENGINE.getName())));
    }

    /** Backup only, and only for a holder of the capability the download itself demands. */
    @Override
    public @NonNull List<RowAction<Row>> rowActions() {
        return List.of(RowAction.Url.<Row>builder(Identifier.of("hohenheim", "manage_backup_database"))
            .label(Microcopy.of("backup").withFilter("scope", "database"))
            .icon(Icon.of("download"))
            .visibleFor((row, ctx) -> HohenheimAccess.hasDatabaseCapability(ctx,
                row.get(DatabaseModel.ID), HohenheimAccess.BACKUPS))
            .url(row -> new Uri(HohenheimEndpoints.DATABASES_BACKUP
                .with(HohenheimEndpoints.DATABASE_NAME, row.get(DatabaseModel.NAME)).toUrl()))
            .build());
    }

    @Override
    public @NonNull List<RecordScopedPage<Row>> subpages() {
        return List.of(new ManageDatabaseCredentialsPage());
    }

    /**
     * NAV-ONLY (zero granted databases hide the empty list); the route stays scoped.
     * reachesAny, because an id set cannot express every-record authority.
     */
    @Override
    public boolean hasInScopeRecords(@NonNull AccessContext access) {
        return HohenheimAccess.reachesAny(access, DatabaseModel.MODEL_ID, HohenheimAccess.VIEW);
    }
}
