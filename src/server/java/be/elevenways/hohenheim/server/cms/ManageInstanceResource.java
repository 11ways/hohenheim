package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.cms.common.access.AccessDecision;
import be.elevenways.zenit.cms.common.access.AccessFunction;
import be.elevenways.zenit.cms.common.access.QueryPredicate;
import be.elevenways.zenit.cms.common.action.RowAction;
import be.elevenways.zenit.cms.common.resource.ListChrome;
import be.elevenways.zenit.cms.common.resource.RecordScopedPage;
import be.elevenways.zenit.cms.common.resource.RecordSubpageRegistry;
import be.elevenways.zenit.cms.common.resource.RelatedPage;
import be.elevenways.zenit.cms.common.resource.ResourceFieldBinding;
import be.elevenways.zenit.cms.common.schema.ColumnSpec;
import be.elevenways.zenit.cms.common.schema.TableSpec;
import be.elevenways.zenit.common.edit.FieldAccess;
import be.elevenways.zenit.common.edit.FieldFormEntryRegistry;
import be.elevenways.zenit.common.edit.FormSpec;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.query.criteria.CompositeCriteria;
import be.elevenways.zenit.common.orm.query.criteria.CompositeOperator;
import be.elevenways.zenit.common.orm.query.criteria.Criteria;
import be.elevenways.zenit.common.security.AccessContext;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

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
        .column(ColumnSpec.fromField(InstanceModel.NAME).subtext("kind").build())
        .column(ColumnSpec.fromField(InstanceModel.KIND).hidden().build())
        .column(ColumnSpec.fromField(InstanceModel.STATUS).filterable().subtext("install_state").build())
        .column(ColumnSpec.fromField(InstanceModel.INSTALL_STATE).hidden().build())
        .build();

    @Override public @NonNull Identifier id() { return Identifier.of("hohenheim", "manage_instance"); }
    @Override public @NonNull FormSpec formSpec() { return this.manageFormSpec; }
    @Override public @NonNull TableSpec<Row> tableSpec() { return this.manageTableSpec; }

    /**
     * Does NOT inherit the admin fleet list's views and rule builder: a tenant sees the
     * instances they hold a grant on, through two visible columns.
     */
    @Override public @NonNull ListChrome listChrome() { return ListChrome.MINIMAL; }

    @Override
    public @NonNull List<ResourceFieldBinding> fieldBindings() {
        return List.of(
            ResourceFieldBinding.of(InstanceModel.NAME.getName(), FieldAccess.ALWAYS_EDITABLE),
            ResourceFieldBinding.of(InstanceModel.CRASH_POLICY.getName(), FieldAccess.ALWAYS_EDITABLE));
    }

    /**
     * Admins see every live instance; everyone else only the ones the walk confirms
     * {@code view} on (a manage/console/power/config/destroy grant implies it). This is
     * what makes an unowned id read as MISSING (zenit-cms 404s an out-of-scope load)
     * rather than forbidden.
     */
    @Override
    public @NonNull AccessFunction<Row> accessFunction() {
        return ctx -> {
            // Generated (product-tier-owned) instances stay off the delegated surface
            // too: their one UI is the owning record's own page.
            Criteria base = new CompositeCriteria(CompositeOperator.AND,
                InstanceModel.DELETED_AT.isNull(), InstanceModel.GENERATED_BY.isNull());
            Criteria scope = HohenheimAccess.instanceScope(ctx, HohenheimAccess.VIEW);
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
     * Power, the two artifact actions and the in-place app update, each carrying the
     * capability gate it declared on the base resource -- inherited verbatim, so
     * /manage and /admin can never drift on what an action requires.
     */
    @Override
    public @NonNull List<RowAction<Row>> rowActions() {
        return List.of(this.deployAction(), this.stopAction(),
            this.snapshotAction(), this.backupAction(), this.appUpdateAction());
    }

    @Override
    public @NonNull List<RecordScopedPage<Row>> subpages() {
        List<RecordScopedPage<Row>> pages = new ArrayList<>(List.of(
            new InstanceOverviewPage(this),
            new InstanceConsolePage(), new InstanceFramebufferPage(),
            new InstanceProvisioningPage(),
            new InstanceDeploymentsPage(),
            new InstanceFilesPage(), new InstanceStatsPage(),
            // Offered on the TENANT panel too, unlike the exec tab beside it in the
            // operator resource: exec is ADMIN-sensitivity with deliberately no /manage
            // surface, while `shell` is a delegable tenant verb bounded to a workload that
            // runs as its own non-root uid -- a tenant who cannot reach it here has "your
            // own box" and no way into it. It hides AND 404s itself without the capability.
            new InstanceShellPage(),
            // The DELEGATED artifact resources: ManageInstanceBackupResource declares no
            // row actions, which is how restore-to-new stays operator-only here too.
            new InstanceSnapshotsPage(new ManageInstanceSnapshotResource()),
            new InstanceBackupsPage(new ManageInstanceBackupResource()),
            new InstanceSchedulesPage(), new InstanceDevicesPage(),
            new InstanceVolumesPage()));
        pages.addAll(RecordSubpageRegistry.INSTANCE.contributionsFor(this.model().getModelId()));
        return pages;
    }

    /**
     * The delegated front door is the same overview -- and the URL is now DERIVED, so
     * this declaration is byte-identical to the operator resource's and cannot drift
     * to the wrong panel the way a hand-spelled {@code CmsRoutes.subpage("manage", ...)}
     * could.
     */
    @Override
    public @Nullable String landingSubpage() {
        return InstanceOverviewPage.SLUG;
    }

    /**
     * NAV-ONLY (zero granted instances hide the empty list); the route stays scoped.
     *
     * AIDEV-NOTE: reachesAny, not "ids.isEmpty()" -- the walk's whole-model rows (the admin
     * bypass here) cover records that carry no grant, so an id set answers "nothing" for a
     * subject who reaches everything. The admin disjunct is gone with it: the walk answers
     * that row itself, without a query.
     */
    @Override
    public boolean hasInScopeRecords(@NonNull AccessContext access) {
        return HohenheimAccess.reachesAny(access, InstanceModel.MODEL_ID, HohenheimAccess.VIEW);
    }

    /**
     * No inherited related pages: the operator resource names sibling peers of the ADMIN
     * panel, which this panel does not register at all -- inheriting one would fail
     * ManagePanel's own registration walk, and a 403 link is worse than no link.
     */
    @Override public @NonNull List<RelatedPage> relatedPages() { return List.of(); }

}
