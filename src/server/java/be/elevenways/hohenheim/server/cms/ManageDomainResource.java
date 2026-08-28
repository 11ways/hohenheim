package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.model.SiteDomainModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.cms.common.access.AccessDecision;
import be.elevenways.zenit.cms.common.access.AccessFunction;
import be.elevenways.zenit.cms.common.access.QueryPredicate;
import be.elevenways.zenit.cms.common.resource.RecordScopedPage;
import be.elevenways.zenit.common.edit.FormSpec;
import be.elevenways.zenit.common.edit.RelationPick;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.query.criteria.Criteria;
import be.elevenways.zenit.common.security.AccessContext;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.util.List;

/**
 * The /manage view over domains belonging to granted sites: a tenant binds and unbinds
 * hostnames on the sites they manage, and nothing else.
 */
public final class ManageDomainResource extends SiteDomainResource {

    /**
     * The delegated form. This is a UX affordance ONLY: the fields it omits are frozen by
     * {@code TenantWrites} on the SiteDomainModel write pipeline, which every writer passes
     * and this form does not. Never treat the omission as the gate.
     */
    private final FormSpec manageFormSpec = FormSpec.builder()
        .add(RelationPick.of(SiteDomainModel.SITE_ID, SiteModel.MODEL_ID).build())
        .add(SiteDomainModel.HOSTNAME)
        .add(SiteDomainModel.FORCE_SSL)
        .add(SiteDomainModel.HSTS_ENABLED)
        .add(SiteDomainModel.HSTS_SUBDOMAINS)
        .add(SiteDomainModel.EXCLUDE_FROM_LETSENCRYPT)
        .build();

    @Override public @NonNull Identifier id() { return Identifier.of("hohenheim", "manage_site_domain"); }
    @Override public @NonNull FormSpec formSpec() { return this.manageFormSpec; }

    /**
     * Admins see every domain of a live site; everyone else only those of their granted
     * sites -- the soft-deleted-site scope is the base resource's and is never dropped by
     * narrowing it further.
     */
    @Override
    public @NonNull AccessFunction<Row> accessFunction() {
        return ctx -> {
            Criteria scope = ManagePanel.domainScope(ctx);
            return AccessDecision.allow(QueryPredicate.of(scope == null
                ? liveSiteScope()
                : Criteria.and(liveSiteScope(), scope)));
        };
    }

    /**
     * The delegated panel keeps the admin activity/revision history off its surface, exactly
     * like {@link ManageSiteResource}; site_domain contributes no registry subpages either
     * (it deliberately declares no record capabilities, so zenit-auth attaches no access
     * matrix to it).
     */
    @Override
    public @NonNull List<RecordScopedPage<Row>> subpages() {
        return List.of();
    }

    /** NAV-ONLY (zero granted sites hide the empty list); the route itself stays scoped by accessFunction. */
    @Override
    public boolean hasInScopeRecords(@NonNull AccessContext access) {
        return ManagePanel.hasManageScope(access);
    }
}
