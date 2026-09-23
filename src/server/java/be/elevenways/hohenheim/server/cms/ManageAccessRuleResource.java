package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.model.AccessListModel;
import be.elevenways.hohenheim.model.AccessRuleModel;
import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.cms.common.access.AccessFunction;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.cms.common.resource.RecordScopedPage;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.util.List;

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
        return TenantScopes.ACCESS_RULES.accessFunction();
    }

    /** Writing a rule demands {@code manage} on the list it belongs to. */
    @Override
    public boolean writableBy(@NonNull Row record, @NonNull AccessContext accessContext) {
        return HohenheimAccess.reachesRecord(accessContext, AccessListModel.MODEL_ID,
            record.get(AccessRuleModel.ACCESS_LIST_ID), HohenheimAccess.MANAGE);
    }

    /**
     * The contributed pages only (the generic access matrix, which gates itself per record).
     * Deliberately NOT frameworkSubpages(): the admin activity/revision history stays off the
     * delegated surface, and dropping the page here also 404s its routes.
     */
    @Override
    public @NonNull List<RecordScopedPage<Row>> subpages() {
        return this.contributedSubpages();
    }
}
