package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.model.AccessListModel;
import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.auth.model.GrantSubjectType;
import be.elevenways.zenit.auth.server.RecordGrants;
import be.elevenways.zenit.cms.common.resource.RecordSubpageRegistry;
import be.elevenways.zenit.cms.common.access.AccessDecision;
import be.elevenways.zenit.cms.common.access.AccessFunction;
import be.elevenways.zenit.cms.common.access.QueryPredicate;
import be.elevenways.zenit.cms.common.resource.RecordScopedPage;
import be.elevenways.zenit.cms.common.schema.ColumnSpec;
import be.elevenways.zenit.cms.common.schema.TableSpec;
import be.elevenways.zenit.common.edit.FormSpec;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.orm.query.criteria.Criteria;
import be.elevenways.zenit.common.security.AccessContext;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The /manage view over access lists: a tenant authors its OWN policies (a password on a
 * folder, an allow-list on a site) and attaches them to the sites it manages, seeing only
 * the lists it holds {@code manage} on.
 *
 * What is DROPPED relative to the admin resource, and why:
 *
 * - SHARED is absent from the form and the table: publishing a policy installation-wide
 *   is the operator's declaration, exactly like a shared git provider. Absent from the
 *   form means absent from coercion, and TenantWrites freezes the column on every writer.
 * - The list is scoped to MANAGED rows only, not to the picker's shared-plus-managed
 *   scope: a tenant may ATTACH an operator's shared list, and must never be able to open
 *   its record, read its rules or delete it.
 */
public final class ManageAccessListResource extends AccessListResource {

    private final FormSpec manageFormSpec = FormSpec.builder()
        .add(AccessListModel.NAME)
        .add(AccessListModel.SATISFY)
        .build();

    private final TableSpec<Row> manageTableSpec = TableSpec.<Row>builder()
        .column(ColumnSpec.fromField(AccessListModel.NAME).filterable().build())
        .column(ColumnSpec.fromField(AccessListModel.SATISFY).filterable().build())
        .build();

    @Override public @NonNull Identifier id() { return Identifier.of("hohenheim", "manage_access_list"); }
    @Override public @NonNull FormSpec formSpec() { return this.manageFormSpec; }
    @Override public @NonNull TableSpec<Row> tableSpec() { return this.manageTableSpec; }

    /**
     * Admins see every list; everyone else only the ones the walk confirms {@code manage}
     * on -- an unowned id reads as MISSING (zenit-cms 404s an out-of-scope load).
     */
    @Override
    public @NonNull AccessFunction<Row> accessFunction() {
        return ctx -> {
            Criteria scope = HohenheimAccess.grantScope(ctx, Models.get(AccessListModel.class),
                AccessListModel.MODEL_ID, HohenheimAccess.MANAGE, AccessListModel.ID::in);
            return scope == null ? AccessDecision.allowAll()
                : AccessDecision.allow(QueryPredicate.of(scope));
        };
    }

    /**
     * Create, then hand the creator {@code manage} on what it just authored -- the grant
     * IS the ownership, the ManageGitProviderResource shape verbatim. An operator create
     * plants nothing: an empty subject set IS operator ownership.
     */
    @Override
    public @NonNull Object persistRow(@NonNull Map<String, Object> coerced,
                                      @NonNull AccessContext accessContext) {
        Object key = super.persistRow(coerced, accessContext);
        int listId = Integer.parseInt(String.valueOf(key));
        for (String subject : HohenheimAccess.creationOwnerSubjects(accessContext)) {
            int separator = subject.indexOf(':');
            RecordGrants.grant(GrantSubjectType.fromKey(subject.substring(0, separator)),
                Integer.parseInt(subject.substring(separator + 1)),
                AccessListModel.MODEL_ID, listId, HohenheimAccess.MANAGE, true);
        }
        // The scope memo cached "which lists does this principal manage" before the grant
        // above changed the answer; without this the create refuses ITSELF out_of_scope.
        HohenheimAccess.forgetGrantedRecordIds(accessContext);
        return key;
    }

    /**
     * The Rules tab plus the CONTRIBUTED pages (the generic "access" matrix, so an owner
     * can delegate its list from /manage). Deliberately NOT frameworkSubpages(): the
     * admin activity/revision pages stay off the delegated surface.
     */
    @Override
    public @NonNull List<RecordScopedPage<Row>> subpages() {
        List<RecordScopedPage<Row>> pages = new ArrayList<>(List.of(new AccessListRulesPage()));
        pages.addAll(RecordSubpageRegistry.INSTANCE.contributionsFor(this.model().getModelId()));
        return pages;
    }
}
