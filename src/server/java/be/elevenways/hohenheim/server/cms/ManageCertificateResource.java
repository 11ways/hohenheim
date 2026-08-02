package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.model.CertificateModel;
import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.cms.common.access.AccessDecision;
import be.elevenways.zenit.cms.common.access.AccessFunction;
import be.elevenways.zenit.cms.common.access.QueryPredicate;
import be.elevenways.zenit.cms.common.action.HeaderAction;
import be.elevenways.zenit.cms.common.action.RowAction;
import be.elevenways.zenit.cms.common.resource.RecordScopedPage;
import be.elevenways.zenit.cms.common.resource.RecordSubpageRegistry;
import be.elevenways.zenit.cms.common.resource.ResourceFieldBinding;
import be.elevenways.zenit.cms.common.schema.ColumnSpec;
import be.elevenways.zenit.cms.common.schema.SortSpec;
import be.elevenways.zenit.cms.common.schema.TableSpec;
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
 * The /manage view over certificates: STATUS, never key material.
 *
 * The vocabulary this surface serves is {@code view} only. Key export and certificate upload
 * are never delegable and never owner-implied (docs/instance-tier-plan.md, Phase 2 parallel
 * gate): hohenheim terminates TLS itself so a tenant has no need for the private key, and
 * exporting it would make revocation meaningless. So this resource carries NO PEM entry in
 * either spec, no download row action, no create and no update -- the omissions are the
 * point, not an oversight to be "completed" later.
 */
public final class ManageCertificateResource extends CertificateResource {

    private final FormSpec manageFormSpec = FormSpec.builder()
        .add(CertificateModel.NICE_NAME)
        .add(CertificateModel.DOMAIN_NAMES_TEXT)
        .add(CertificateModel.STATUS)
        .add(CertificateModel.EXPIRES_ON)
        .add(CertificateModel.RENEWAL_ERROR)
        .build();

    private final TableSpec<Row> manageTableSpec = TableSpec.<Row>builder()
        .column(ColumnSpec.fromField(CertificateModel.NICE_NAME).build())
        .column(ColumnSpec.fromField(CertificateModel.DOMAIN_NAMES_TEXT).build())
        .column(ColumnSpec.fromField(CertificateModel.STATUS).build())
        .column(ColumnSpec.fromField(CertificateModel.EXPIRES_ON).build())
        .column(ColumnSpec.fromField(CertificateModel.RENEWAL_ERROR).build())
        .defaultSort(SortSpec.desc(CertificateModel.EXPIRES_ON.getName()))
        .build();

    @Override public @NonNull Identifier id() { return Identifier.of("hohenheim", "manage_certificate"); }
    @Override public @NonNull FormSpec formSpec() { return this.manageFormSpec; }
    @Override public @NonNull TableSpec<Row> tableSpec() { return this.manageTableSpec; }
    @Override public boolean creatable() { return false; }
    @Override public boolean updatable() { return false; }
    @Override public boolean deletable() { return false; }

    /** Every entry is a reader's view; nothing on this surface is authored. */
    @Override
    public @NonNull List<ResourceFieldBinding> fieldBindings() {
        List<ResourceFieldBinding> bindings = new ArrayList<>();
        for (var entry : this.manageFormSpec.entries()) {
            bindings.add(ResourceFieldBinding.of(entry.name(), FieldAccess.alwaysReadonly()));
        }
        return bindings;
    }

    /** No download: the private key is the one thing this surface must never hand over. */
    @Override public @NonNull List<RowAction<Row>> rowActions() { return List.of(); }

    /** No upload link: an uploaded certificate is unverified authority over a name. */
    @Override public @NonNull List<HeaderAction> headerActions() { return List.of(); }

    /** Admins see every certificate; everyone else only the walk-reachable ones. */
    @Override
    public @NonNull AccessFunction<Row> accessFunction() {
        return ctx -> {
            Criteria notTheAccountRow = new CompositeCriteria(CompositeOperator.OR,
                CertificateModel.PROVIDER.isNull(),
                CertificateModel.PROVIDER.ne(CertificateModel.PROVIDER_ACME_ACCOUNT));
            Criteria scope = ManagePanel.certificateScope(ctx);
            return scope == null
                ? AccessDecision.allow(QueryPredicate.of(notTheAccountRow))
                : AccessDecision.allow(QueryPredicate.of(
                    new CompositeCriteria(CompositeOperator.AND, notTheAccountRow, scope)));
        };
    }

    @Override
    public @NonNull List<RecordScopedPage<Row>> subpages() {
        return new ArrayList<>(
            RecordSubpageRegistry.INSTANCE.contributionsFor(this.model().getModelId()));
    }

    /** NAV-ONLY; the route itself stays scoped by accessFunction. */
    @Override
    public boolean hasInScopeRecords(@NonNull AccessContext access) {
        return HohenheimAccess.isAdmin(access)
            || access.principalId() != null
            || !HohenheimAccess.grantedRecordIds(access, CertificateModel.MODEL_ID,
                HohenheimAccess.VIEW).isEmpty();
    }
}
