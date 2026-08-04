package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.cms.common.access.AccessDecision;
import be.elevenways.zenit.cms.common.access.AccessFunction;
import be.elevenways.zenit.cms.common.access.QueryPredicate;
import be.elevenways.zenit.cms.common.action.RowAction;
import be.elevenways.zenit.cms.common.resource.RecordScopedPage;
import be.elevenways.zenit.cms.common.resource.RecordSubpageRegistry;
import be.elevenways.zenit.cms.common.resource.ResourceFieldBinding;
import be.elevenways.zenit.cms.common.schema.ColumnSpec;
import be.elevenways.zenit.cms.common.schema.TableSpec;
import be.elevenways.zenit.common.edit.FieldFormEntryRegistry;
import be.elevenways.zenit.common.edit.FieldAccess;
import be.elevenways.zenit.common.edit.FormSpec;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.query.criteria.CompositeCriteria;
import be.elevenways.zenit.common.orm.query.criteria.CompositeOperator;
import be.elevenways.zenit.common.orm.query.criteria.Criteria;
import be.elevenways.zenit.common.security.AccessContext;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.util.ArrayList;
import java.util.List;

/**
 * The /manage view over instances: the tenant sees exactly the instances they hold
 * {@code manage} on, and nothing else exists as far as this surface is concerned.
 *
 * What is DROPPED relative to the admin resource, and why:
 *
 * - The FORM is name + crash policy. Kind, settings (image, command, environment) and
 *   the host pick are execution and placement decisions; a tenant authoring any of them
 *   would be authoring what runs and where. The image half is refused by the write
 *   pipeline too (InstanceImagePolicy), so this omission is UX on top of a gate rather
 *   than instead of one -- but the host pick has no pipeline twin, which is exactly why
 *   InstancePlacement ignores a submitted host for non-admins.
 * - No CREATE from this form. Creation is create-from-an-APPROVED-TEMPLATE, which is
 *   the only image source the threat model allows a tenant, and it lives on the
 *   template page.
 * - No DELETE. Destroy releases quota, removes containers, tears down game-domain
 *   mappings and leaves orphan volumes for an operator to judge; it stays an operator
 *   act. A tenant stops.
 * - No install/reinstall row action: reinstall is data-destructive per template policy,
 *   and its policy surface is not delegated yet.
 * - Subpages are console, provisioning and schedules, plus the CONTRIBUTED access
 *   matrix (a manage holder may delegate its delegable capability). Deliberately NOT
 *   frameworkSubpages(): activity and revision history are admin history.
 */
public final class ManageInstanceResource extends InstanceResource {

    private final FormSpec manageFormSpec = FormSpec.builder()
        .add(InstanceModel.NAME)
        .add(FieldFormEntryRegistry.INSTANCE.deriveEntry(InstanceModel.CRASH_POLICY))
        .build();

    private final TableSpec<Row> manageTableSpec = TableSpec.<Row>builder()
        .column(ColumnSpec.fromField(InstanceModel.NAME).build())
        .column(ColumnSpec.fromField(InstanceModel.KIND).build())
        .column(ColumnSpec.fromField(InstanceModel.STATUS).filterable().build())
        .column(ColumnSpec.fromField(InstanceModel.INSTALL_STATE).build())
        .build();

    @Override public @NonNull Identifier id() { return Identifier.of("hohenheim", "manage_instance"); }
    @Override public @NonNull FormSpec formSpec() { return this.manageFormSpec; }
    @Override public @NonNull TableSpec<Row> tableSpec() { return this.manageTableSpec; }

    @Override
    public @NonNull List<ResourceFieldBinding> fieldBindings() {
        return List.of(
            ResourceFieldBinding.of(InstanceModel.NAME.getName(), FieldAccess.ALWAYS_EDITABLE),
            ResourceFieldBinding.of(InstanceModel.CRASH_POLICY.getName(), FieldAccess.ALWAYS_EDITABLE));
    }

    /**
     * Admins see every live instance; everyone else only the ones a walk-confirmed
     * {@code manage} grant covers. This is what makes an unowned id read as MISSING
     * (zenit-cms 404s an out-of-scope load) rather than forbidden.
     */
    @Override
    public @NonNull AccessFunction<Row> accessFunction() {
        return ctx -> {
            // Generated (product-tier-owned) instances stay off the delegated surface
            // too: their one UI is the owning record's own page.
            Criteria base = new CompositeCriteria(CompositeOperator.AND,
                InstanceModel.DELETED_AT.isNull(), InstanceModel.GENERATED_BY.isNull());
            Criteria scope = HohenheimAccess.instanceScope(ctx, HohenheimAccess.MANAGE);
            if (scope == null) {
                return AccessDecision.allow(QueryPredicate.of(base));
            }
            return AccessDecision.allow(QueryPredicate.of(
                new CompositeCriteria(CompositeOperator.AND, base, scope)));
        };
    }

    @Override public boolean creatable() { return false; }
    @Override public boolean deletable() { return false; }

    /**
     * Power plus the two artifact actions, each carrying the capability gate it declared
     * on the base resource -- inherited verbatim, so /manage and /admin can never drift
     * on what an action requires.
     */
    @Override
    public @NonNull List<RowAction<Row>> rowActions() {
        return List.of(this.deployAction(), this.stopAction(),
            this.snapshotAction(), this.backupAction());
    }

    @Override
    public @NonNull List<RecordScopedPage<Row>> subpages() {
        List<RecordScopedPage<Row>> pages = new ArrayList<>(List.of(
            new InstanceConsolePage(), new InstanceProvisioningPage(),
            new InstanceFilesPage(), new InstanceStatsPage(),
            new InstanceSchedulesPage()));
        pages.addAll(RecordSubpageRegistry.INSTANCE.contributionsFor(this.model().getModelId()));
        return pages;
    }

    /** NAV-ONLY (zero granted instances hide the empty list); the route stays scoped. */
    @Override
    public boolean hasInScopeRecords(@NonNull AccessContext access) {
        return HohenheimAccess.isAdmin(access)
            || !HohenheimAccess.instanceIdsWith(access, HohenheimAccess.MANAGE).isEmpty();
    }
}
