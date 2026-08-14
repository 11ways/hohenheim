package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.cms.common.access.AccessDecision;
import be.elevenways.zenit.cms.common.access.AccessFunction;
import be.elevenways.zenit.cms.common.access.QueryPredicate;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.orm.query.criteria.CompositeCriteria;
import be.elevenways.zenit.common.orm.query.criteria.CompositeOperator;
import be.elevenways.zenit.common.orm.query.criteria.Criteria;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.common.task.record.RecordScheduleModel;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * The /manage view over instance schedules: the same editor as the admin one, narrowed
 * to the schedules of instances the principal manages.
 *
 * AIDEV-NOTE: the base resource's accessFunction scopes only by MODEL ("this surface
 * manages instance schedules"), which is correct in an admin-gated panel and a
 * cross-tenant list in a delegated one -- every write already demanded manage, but the
 * READ did not. This override is that missing half, and it is a criteria over the
 * record_id STRINGS because record schedules key their target polymorphically.
 */
public final class ManageInstanceScheduleResource extends InstanceScheduleResource {

    @Override
    public @NonNull Identifier id() {
        return Identifier.of("hohenheim", "manage_instance_schedule");
    }

    @Override
    public @NonNull AccessFunction<Row> accessFunction() {
        return ManageInstanceScheduleResource::decide;
    }

    /** Shared with the steps resource, which scopes through its parent schedule. */
    static @NonNull AccessDecision decide(@NonNull AccessContext ctx) {
        Criteria scope = scopeCriteria(ctx);
        if (scope == null) {
            return AccessDecision.allow(QueryPredicate.of(
                RecordScheduleModel.MODEL.eq(InstanceModel.MODEL_ID.toString())));
        }
        return AccessDecision.allow(QueryPredicate.of(scope));
    }

    /**
     * The walk's tri-state through {@code grantScope}, never a hand-rolled
     * isAdmin-plus-id-set prefix: an id set cannot express a whole-model row, and the
     * enumeration THROWS on one the moment instances grow a type-level permission.
     * Shared with {@link ManagePanel#recordScheduleScope}, the SAME policy on the
     * record-source face.
     *
     * @return null for an unconstrained scope (constrain to instance schedules yourself
     *         where the surface demands it), else a criteria over instance schedules
     */
    static @Nullable Criteria scopeCriteria(@NonNull AccessContext ctx) {
        Criteria scope = HohenheimAccess.grantScope(ctx, Models.get(RecordScheduleModel.class),
            InstanceModel.MODEL_ID, HohenheimAccess.VIEW,
            ManageInstanceScheduleResource::recordIdIn);
        if (scope == null) {
            return null;
        }
        return new CompositeCriteria(CompositeOperator.AND,
            RecordScheduleModel.MODEL.eq(InstanceModel.MODEL_ID.toString()), scope);
    }

    /** Record schedules key their target polymorphically, so the id set folds to strings. */
    private static @NonNull Criteria recordIdIn(@NonNull Set<Integer> instanceIds) {
        Set<String> ids = new LinkedHashSet<>();
        for (Integer id : instanceIds) {
            ids.add(String.valueOf(id));
        }
        return RecordScheduleModel.RECORD_ID.in(ids);
    }
}
