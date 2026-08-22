package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.cms.common.access.AccessDecision;
import be.elevenways.zenit.cms.common.access.AccessFunction;
import be.elevenways.zenit.cms.common.access.QueryPredicate;
import be.elevenways.zenit.cms.common.action.HeaderAction;
import be.elevenways.zenit.cms.common.action.RowAction;
import be.elevenways.zenit.cms.common.resource.ListChrome;
import be.elevenways.zenit.cms.common.resource.RecordScopedPage;
import be.elevenways.zenit.cms.common.resource.RecordSubpageRegistry;
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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

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

    /**
     * Does NOT inherit the admin list's views and rule builder: a tenant sees the handful
     * of sites they hold a grant on, through two columns.
     */
    @Override public @NonNull ListChrome listChrome() { return ListChrome.MINIMAL; }

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

    /**
     * The delegated surface owns NOTHING outside its three form fields, so it
     * inherits none of SiteResource's declaration. Deliberately explicit: a
     * silently inherited slug/status ownership would widen exactly the surface
     * this class exists to narrow.
     */
    @Override
    public @NonNull Set<String> restorableFieldsOutsideForm() {
        return Set.of();
    }

    /**
     * Apply only the explicit delegated form values; never run the admin source/type
     * normalizers.
     *
     * AIDEV-NOTE: all three reads take the STORED value when the write does not carry the
     * key. The inline cell lane hands updateRow a map holding EXACTLY ONE entry, and this
     * body set all three columns unconditionally: a description edit refused with
     * "name_required", and an enabled site whose name was corrected was set enabled=null
     * on the way through.
     */
    @Override
    public void updateRow(@NonNull Row existing, @NonNull Map<String, Object> coerced,
                          @NonNull AccessContext accessContext) {
        String name = CmsSupport.textOf(coerced, existing, SiteModel.NAME);
        if (name.isEmpty()) {
            throw Violations.ofField(SiteModel.NAME.getName(), name, CmsSupport.violationText("name_required"));
        }
        // This override deliberately skips the admin normalizers. The enable
        // invariant is NOT one of them and is NOT re-checked here: a delegated tenant
        // flipping this checkbox goes live through model.save below, which the
        // write-pipeline enable invariant (SiteResource.installEnableInvariant)
        // funnels through exactly like every other writer.
        existing.set(SiteModel.NAME, name);
        existing.set(SiteModel.ENABLED,
            (Boolean) CmsSupport.valueOf(coerced, existing, SiteModel.ENABLED));
        existing.set(SiteModel.DESCRIPTION,
            (String) CmsSupport.valueOf(coerced, existing, SiteModel.DESCRIPTION));
        this.model().save(existing);
    }

    /** Operate stays (toggle); the record-creating clone action does not. */
    @Override
    public @NonNull List<RowAction<Row>> rowActions() {
        return List.of(this.toggleAction());
    }

    /** NAV-ONLY (zero granted sites hide the empty list); the route itself stays scoped by accessFunction. */
    @Override
    public boolean hasInScopeRecords(@NonNull AccessContext access) {
        return ManagePanel.hasManageScope(access);
    }

    @Override
    public @NonNull List<RecordScopedPage<Row>> subpages() {
        // The operator subpages plus the CONTRIBUTED ones (the generic "access"
        // matrix): a manage holder must be able to delegate from /manage, and
        // each contributed page still gates itself per record via visibleFor.
        // Deliberately NOT frameworkSubpages(): the admin activity/revision
        // history pages stay off the delegated surface.
        List<RecordScopedPage<Row>> pages = new ArrayList<>(
            List.of(new SiteDomainsPage()));
        pages.addAll(RecordSubpageRegistry.INSTANCE.contributionsFor(this.model().getModelId()));
        return pages;
    }

    /**
     * No inherited header actions: the operator resource links sibling ADMIN lists a
     * tenant may not reach, and a 403 button is worse than no button.
     */
    @Override public @NonNull List<HeaderAction> headerActions() { return List.of(); }

}
