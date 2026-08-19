package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.model.GitProviderModel;
import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.auth.model.GrantSubjectType;
import be.elevenways.zenit.auth.server.RecordGrants;
import be.elevenways.zenit.cms.common.access.AccessDecision;
import be.elevenways.zenit.cms.common.access.AccessFunction;
import be.elevenways.zenit.cms.common.access.QueryPredicate;
import be.elevenways.zenit.cms.common.resource.ResourceFieldBinding;
import be.elevenways.zenit.cms.common.schema.ColumnSpec;
import be.elevenways.zenit.cms.common.schema.TableSpec;
import be.elevenways.zenit.common.edit.FieldFormEntryRegistry;
import be.elevenways.zenit.common.edit.FormSpec;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.orm.query.criteria.Criteria;
import be.elevenways.zenit.common.security.AccessContext;
import org.checkerframework.checker.nullness.qual.NonNull;

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
 *   form means absent from coercion, which is the enforcement, not merely the rendering.
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
        return ctx -> {
            Criteria scope = HohenheimAccess.grantScope(ctx, Models.get(GitProviderModel.class),
                GitProviderModel.MODEL_ID, HohenheimAccess.MANAGE, GitProviderModel.ID::in);
            return scope == null ? AccessDecision.allowAll()
                : AccessDecision.allow(QueryPredicate.of(scope));
        };
    }

    /**
     * Create, then hand the creator {@code manage} on what it just registered -- the
     * grant IS the ownership, exactly as it is for a tenant's instance or database. An
     * operator create plants nothing: an empty subject set IS operator ownership.
     */
    @Override
    public @NonNull Object persistRow(@NonNull Map<String, Object> coerced,
                                      @NonNull AccessContext accessContext) {
        Object key = super.persistRow(coerced, accessContext);
        int providerId = Integer.parseInt(String.valueOf(key));
        for (String subject : HohenheimAccess.creationOwnerSubjects(accessContext)) {
            int separator = subject.indexOf(':');
            RecordGrants.grant(GrantSubjectType.fromKey(subject.substring(0, separator)),
                Integer.parseInt(subject.substring(separator + 1)),
                GitProviderModel.MODEL_ID, providerId, HohenheimAccess.MANAGE, true);
        }
        // The request memo caches "which records does this principal hold X on", and the
        // grant above just changed the answer. zenit-cms verifies the created row against
        // the caller's own scope predicate before committing, so a stale memo would make a
        // legitimate create refuse ITSELF with out_of_scope.
        HohenheimAccess.forgetGrantedRecordIds(accessContext);
        return key;
    }

    /** NAV-ONLY (zero granted providers hide the empty list); the route stays scoped. */
    @Override
    public boolean hasInScopeRecords(@NonNull AccessContext access) {
        return HohenheimAccess.reachesAny(access, GitProviderModel.MODEL_ID,
            HohenheimAccess.MANAGE);
    }
}
