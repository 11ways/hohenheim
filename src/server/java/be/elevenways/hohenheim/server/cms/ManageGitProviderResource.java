package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.model.GitProviderModel;
import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.zenit.cms.common.access.AccessFunction;
import be.elevenways.zenit.cms.common.resource.ResourceFieldBinding;
import be.elevenways.zenit.cms.common.schema.ColumnSpec;
import be.elevenways.zenit.cms.common.schema.TableSpec;
import be.elevenways.zenit.common.edit.FieldFormEntryRegistry;
import be.elevenways.zenit.common.edit.FormSpec;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.common.validation.Violations;
import be.elevenways.zenit.cms.common.resource.RecordScopedPage;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.List;
import java.util.Map;

/**
 * The /manage view over git providers: a tenant registers its OWN forge installation and
 * uses it on its own sites, seeing only the providers it holds {@code manage} on.
 *
 * What is DROPPED relative to the admin resource, and why:
 *
 * - SHARED is absent from the form and the table. It is the OPERATOR's declaration that
 *   this installation's credential may be used by every tenant, so offering it here would
 *   let a tenant publish its own credential to the whole installation. Absent from the
 *   form keeps it out of coercion, and {@link #refuseSharedChange} refuses any wider map
 *   this resource is handed -- the omission is no longer the whole gate.
 * - A failed connection test answers ONE generic message ({@link #connectionFailure}):
 *   the tenant chose the URL, so the client's own error text would be a port-scan oracle.
 * - The list is scoped to MANAGED rows only, not to the picker's shared-plus-managed
 *   scope: a tenant may USE an operator's shared provider, and must never be able to open
 *   its record, retype its base URL or delete it.
 */
public final class ManageGitProviderResource extends GitProviderResource {

    private final FormSpec manageFormSpec = FormSpec.builder()
        .add(GitProviderModel.NAME)
        .add(FieldFormEntryRegistry.INSTANCE.deriveEntry(GitProviderModel.KIND))
        .add(GitProviderModel.BASE_URL)
        .add(FieldFormEntryRegistry.INSTANCE.deriveEntry(GitProviderModel.SETTINGS))
        .add(GitProviderModel.ACCESS_TOKEN)
        .add(GitProviderModel.APP_PRIVATE_KEY_PEM)
        .build();

    private final TableSpec<Row> manageTableSpec = TableSpec.<Row>builder()
        .column(ColumnSpec.fromField(GitProviderModel.NAME).build())
        .column(ColumnSpec.fromField(GitProviderModel.KIND).filterable().build())
        .column(ColumnSpec.fromField(GitProviderModel.BASE_URL).copyable().build())
        .build();

    @Override public @NonNull Identifier id() { return Identifier.of("hohenheim", "manage_git_provider"); }
    @Override public @NonNull FormSpec formSpec() { return this.manageFormSpec; }
    @Override public @NonNull TableSpec<Row> tableSpec() { return this.manageTableSpec; }

    /** None: this surface offers no field the admin resource pins. */
    @Override
    public @NonNull List<ResourceFieldBinding> fieldBindings() {
        return List.of();
    }

    /**
     * Admins see every provider; everyone else only the ones the walk confirms
     * {@code manage} on. This is what makes an unowned id read as MISSING (zenit-cms 404s
     * an out-of-scope load) rather than forbidden.
     */
    @Override
    public @NonNull AccessFunction<Row> accessFunction() {
        return TenantScopes.MANAGED_GIT_PROVIDERS.accessFunction();
    }

    /**
     * Create, then hand the creator {@code manage} on what it just registered -- the
     * grant IS the ownership, exactly as it is for a tenant's instance or database. An
     * operator create plants nothing: an empty subject set IS operator ownership.
     */
    @Override
    public @NonNull Object persistRow(@NonNull Map<String, Object> coerced,
                                      @NonNull AccessContext accessContext) {
        refuseSharedChange(coerced, null);
        Object key = super.persistRow(coerced, accessContext);
        // THE planting loop, which also drops the scope memo the grant made stale --
        // zenit-cms verifies the created row against the caller's own scope predicate
        // before committing, so a stale memo makes a legitimate create refuse ITSELF.
        HohenheimAccess.grantCreatorManage(GitProviderModel.MODEL_ID,
            Integer.parseInt(String.valueOf(key)), accessContext);
        return key;
    }

    @Override
    public void updateRow(@NonNull Row existing, @NonNull Map<String, Object> coerced,
                          @NonNull AccessContext accessContext) {
        refuseSharedChange(coerced, existing);
        super.updateRow(existing, coerced, accessContext);
    }

    /**
     * Refuse any write through this surface that would move SHARED off its stored (or, on a
     * create, default) value.
     *
     * AIDEV-NOTE: the form omits SHARED, and coercion only carries form entries -- but that
     * made the omission the WHOLE gate, so any lane handing this resource a wider map (a
     * programmatic ResourceWrites call, a future quick-add) published a tenant credential
     * installation-wide. This is the resource's own EARLY refusal; the gate every writer
     * answers to is the model-level freeze in TenantWrites (checkGitProviderWrite), beside
     * the identical AccessListModel.SHARED freeze.
     *
     * @throws Violations anchored on the shared field
     */
    static void refuseSharedChange(@NonNull Map<String, Object> coerced, @Nullable Row existing) {
        String name = GitProviderModel.SHARED.getName();
        if (!coerced.containsKey(name)) {
            return;
        }
        Object baseline = existing != null ? existing.get(GitProviderModel.SHARED)
            : GitProviderModel.SHARED.getDefaultValue();
        if (Boolean.TRUE.equals(coerced.get(name)) != Boolean.TRUE.equals(baseline)) {
            throw Violations.ofField(name, coerced.get(name),
                CmsSupport.violationText("tenant_field_frozen"));
        }
    }

    /**
     * ONE generic message for every failed test on the tenant surface.
     *
     * AIDEV-NOTE: the tenant chose the URL this probe connects to, so echoing the client's
     * own failure ("connection refused", "timed out", a TLS or DNS error) would turn the
     * button into a port scanner of whatever the server can reach, with the toast as its
     * oracle. The operator surface keeps the detailed reason; the real reason is logged.
     */
    @Override
    protected @NonNull Microcopy connectionFailure(@NonNull Exception failure) {
        return Microcopy.of("test_failed_generic").withFilter("scope", "git_provider");
    }

    /** NAV-ONLY (zero granted providers hide the empty list); the route stays scoped. */
    @Override
    public boolean hasInScopeRecords(@NonNull AccessContext access) {
        return HohenheimAccess.reachesAny(access, GitProviderModel.MODEL_ID,
            HohenheimAccess.MANAGE);
    }

    /**
     * The contributed pages only (the generic access matrix, which gates itself per record).
     * Deliberately NOT frameworkSubpages(): the admin activity/revision history stays off the
     * delegated surface, and dropping the page here also 404s its routes.
     */
    @Override
    public @NonNull List<RecordScopedPage<Row>> subpages() {
        return this.contributedSubpages();
    }
}
