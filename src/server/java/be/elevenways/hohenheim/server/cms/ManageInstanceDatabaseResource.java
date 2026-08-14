package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.model.InstanceDatabaseModel;
import be.elevenways.hohenheim.model.InstanceModel;
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
 * The /manage view over instance-database attachments: the same editor, narrowed to the
 * instances the principal manages. THE tenant path for the Pterodactyl shape -- allocate a
 * database, attach it to your own server, receive its credentials as environment variables.
 *
 * AIDEV-NOTE: the base resource's accessFunction does not scope by owner, which is correct
 * in an admin-gated panel and a cross-tenant leak in a delegated one (the
 * ManageInstanceDeviceResource lesson, third occurrence). Every WRITE is re-decided by
 * TenantWrites' two-sided rule, but a READ would have listed which databases every other
 * tenant's workloads are wired to.
 */
public final class ManageInstanceDatabaseResource extends InstanceDatabaseResource {

    @Override
    public @NonNull Identifier id() {
        return Identifier.of("hohenheim", "manage_instance_database");
    }

    @Override
    public @NonNull AccessFunction<Row> accessFunction() {
        return ManageInstanceDatabaseResource::decide;
    }

    /**
     * The walk's tri-state through {@code grantScope}, never a hand-rolled
     * isAdmin-plus-id-set prefix: an id set cannot express a whole-model row, and the
     * enumeration THROWS on one the moment instances grow a type-level permission.
     */
    static @NonNull AccessDecision decide(@NonNull AccessContext ctx) {
        Criteria scope = HohenheimAccess.grantScope(ctx, Models.get(InstanceDatabaseModel.class),
            InstanceModel.MODEL_ID, HohenheimAccess.VIEW, InstanceDatabaseModel.INSTANCE_ID::in);
        if (scope == null) {
            return AccessDecision.allow(QueryPredicate.of(
                InstanceDatabaseModel.INSTANCE_ID.isNotNull()));
        }
        return AccessDecision.allow(QueryPredicate.of(scope));
    }
}
