package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.model.AccessListModel;
import be.elevenways.hohenheim.model.AccessRuleModel;
import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.cms.common.access.AccessDecision;
import be.elevenways.zenit.cms.common.access.AccessFunction;
import be.elevenways.zenit.cms.common.access.QueryPredicate;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.orm.query.criteria.Criteria;
import be.elevenways.zenit.common.security.AccessContext;
import org.checkerframework.checker.nullness.qual.NonNull;

/**
 * The /manage view over access rules: scoped by the parent LIST's {@code manage} grant --
 * a rule row answers to its list exactly like a domain row answers to its site, and
 * deliberately holds no grant surface of its own.
 */
public final class ManageAccessRuleResource extends AccessRuleResource {

    @Override
    public @NonNull Identifier id() {
        return Identifier.of("hohenheim", "manage_access_rule");
    }

    /** Admins see every rule; everyone else only the rows of lists they manage. */
    @Override
    public @NonNull AccessFunction<Row> accessFunction() {
        return ctx -> {
            Criteria scope = HohenheimAccess.grantScope(ctx, Models.get(AccessRuleModel.class),
                AccessListModel.MODEL_ID, HohenheimAccess.MANAGE,
                AccessRuleModel.ACCESS_LIST_ID::in);
            return scope == null ? AccessDecision.allowAll()
                : AccessDecision.allow(QueryPredicate.of(scope));
        };
    }

    /** Writing a rule demands {@code manage} on the list it belongs to. */
    @Override
    public boolean writableBy(@NonNull Row record, @NonNull AccessContext accessContext) {
        return HohenheimAccess.reachesRecord(accessContext, AccessListModel.MODEL_ID,
            record.get(AccessRuleModel.ACCESS_LIST_ID), HohenheimAccess.MANAGE);
    }
}
