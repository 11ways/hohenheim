package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.cms.common.access.AccessDecision;
import be.elevenways.zenit.cms.common.access.AccessFunction;
import be.elevenways.zenit.cms.common.access.QueryPredicate;
import be.elevenways.zenit.cms.common.action.RowAction;
import be.elevenways.zenit.cms.common.resource.RecordScopedPage;
import be.elevenways.zenit.cms.common.resource.ResourceFieldBinding;
import be.elevenways.zenit.cms.common.schema.ColumnSpec;
import be.elevenways.zenit.cms.common.schema.TableSpec;
import be.elevenways.zenit.common.edit.FieldAccess;
import be.elevenways.zenit.common.edit.FormSpec;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.query.criteria.CompositeCriteria;
import be.elevenways.zenit.common.orm.query.criteria.CompositeOperator;
import be.elevenways.zenit.common.orm.query.criteria.Criteria;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.common.validation.Violations;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.util.List;
import java.util.Map;

/**
 * The /manage view over sites: grant-scoped list/edit/operate, no create, no
 * delete, no clone, and only the operator-relevant subpages.
 */
public final class ManageSiteResource extends SiteResource {

    private final FormSpec manageFormSpec = FormSpec.builder()
        .add(SiteModel.NAME)
        .add(SiteModel.ENABLED)
        .add(SiteModel.DESCRIPTION)
        .build();

    private final TableSpec<Row> manageTableSpec = TableSpec.<Row>builder()
        .column(ColumnSpec.fromField(SiteModel.NAME).build())
        .column(ColumnSpec.fromField(SiteModel.ENABLED).build())
        .build();

    @Override public @NonNull Identifier id() { return Identifier.of("hohenheim", "manage_site"); }
    @Override public @NonNull FormSpec formSpec() { return this.manageFormSpec; }
    @Override public @NonNull TableSpec<Row> tableSpec() { return this.manageTableSpec; }

    /** Only non-execution metadata is editable in the delegated panel. */
    @Override
    public @NonNull List<ResourceFieldBinding> fieldBindings() {
        return List.of(
            ResourceFieldBinding.of(SiteModel.NAME.getName(), FieldAccess.ALWAYS_EDITABLE),
            ResourceFieldBinding.of(SiteModel.ENABLED.getName(), FieldAccess.ALWAYS_EDITABLE),
            ResourceFieldBinding.of(SiteModel.DESCRIPTION.getName(), FieldAccess.ALWAYS_EDITABLE));
    }

    /** Admins see every non-deleted site; everyone else only their granted ones. */
    @Override
    public @NonNull AccessFunction<Row> accessFunction() {
        return ctx -> {
            Criteria notDeleted = SiteModel.DELETED_AT.isNull();
            Criteria scope = ManagePanel.siteScope(ctx);
            if (scope == null) {
                return AccessDecision.allow(QueryPredicate.of(notDeleted));
            }
            return AccessDecision.allow(QueryPredicate.of(
                new CompositeCriteria(CompositeOperator.AND, notDeleted, scope)));
        };
    }

    @Override public boolean creatable() { return false; }
    @Override public boolean deletable() { return false; }

    /** Apply only the explicit delegated form values; never run the admin source/type normalizers. */
    @Override
    public void updateRow(@NonNull Row existing, @NonNull Map<String, Object> coerced,
                          @NonNull AccessContext accessContext) {
        String name = coerced.get(SiteModel.NAME.getName()) instanceof String value ? value.trim() : "";
        if (name.isEmpty()) {
            throw Violations.ofField(SiteModel.NAME.getName(), name, CmsSupport.violationText("name_required"));
        }
        existing.set(SiteModel.NAME, (String) coerced.get(SiteModel.NAME.getName()));
        existing.set(SiteModel.ENABLED, (Boolean) coerced.get(SiteModel.ENABLED.getName()));
        existing.set(SiteModel.DESCRIPTION, (String) coerced.get(SiteModel.DESCRIPTION.getName()));
        this.model().save(existing);
    }

    /** Operate stays (toggle); the record-creating clone action does not. */
    @Override
    public @NonNull List<RowAction<Row>> rowActions() {
        return List.of(this.toggleAction());
    }

    @Override
    public @NonNull List<RecordScopedPage<Row>> subpages() {
        return List.of(new SiteDomainsPage(), new SiteDeploymentsPage(), new SiteProcessesPage());
    }
}
