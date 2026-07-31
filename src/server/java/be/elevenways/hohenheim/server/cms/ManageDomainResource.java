package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.model.SiteDomainModel;
import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.cms.common.access.AccessDecision;
import be.elevenways.zenit.cms.common.access.AccessFunction;
import be.elevenways.zenit.cms.common.access.QueryPredicate;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.query.criteria.Criteria;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.util.Set;

/**
 * The read-only /manage view over domains belonging to granted sites.
 */
public final class ManageDomainResource extends SiteDomainResource {

    @Override public @NonNull Identifier id() { return Identifier.of("hohenheim", "manage_site_domain"); }
    @Override public boolean creatable() { return false; }
    @Override public boolean updatable() { return false; }
    @Override public boolean deletable() { return false; }

    /** Admins see everything; everyone else only domains of their granted sites. */
    @Override
    public @NonNull AccessFunction<Row> accessFunction() {
        return ctx -> {
            if (HohenheimAccess.isAdmin(ctx)) {
                return AccessDecision.allowAll();
            }
            if (ctx.isAnonymous()) {
                return AccessDecision.allow(QueryPredicate.of(SiteDomainModel.ID.eq(-1)));
            }
            Set<Integer> ids = HohenheimAccess.managedSiteIds(ctx);
            Criteria scope = ids.isEmpty()
                ? SiteDomainModel.ID.eq(-1)
                : SiteDomainModel.SITE_ID.in(ids);
            return AccessDecision.allow(QueryPredicate.of(scope));
        };
    }
}
