package be.elevenways.hohenheim.server.cms;

import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.cms.common.access.AccessDecision;
import be.elevenways.zenit.cms.common.access.AccessFunction;
import be.elevenways.zenit.cms.common.access.QueryPredicate;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.query.criteria.Criteria;
import be.elevenways.zenit.common.security.AccessContext;
import org.checkerframework.checker.nullness.qual.NonNull;

/**
 * The /manage view over protected paths: scoped by the parent SITE's {@code manage}
 * grant, the ManageDomainResource shape. The base resource's write predicate already
 * asks the same question, so nothing narrows further here.
 */
public final class ManageProtectedPathResource extends ProtectedPathResource {

    @Override
    public @NonNull Identifier id() {
        return Identifier.of("hohenheim", "manage_protected_path");
    }

    /** Admins see every row; everyone else only the guarded paths of managed sites. */
    @Override
    public @NonNull AccessFunction<Row> accessFunction() {
        return ctx -> {
            Criteria scope = ManagePanel.protectedPathScope(ctx);
            return scope == null ? AccessDecision.allowAll()
                : AccessDecision.allow(QueryPredicate.of(scope));
        };
    }

    /** NAV-ONLY (zero managed sites hide the empty list); the route stays scoped. */
    @Override
    public boolean hasInScopeRecords(@NonNull AccessContext access) {
        return ManagePanel.hasManageScope(access);
    }
}
