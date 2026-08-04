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
        Criteria instanceSchedules = RecordScheduleModel.MODEL.eq(InstanceModel.MODEL_ID.toString());
        if (HohenheimAccess.isAdmin(ctx)) {
            return AccessDecision.allow(QueryPredicate.of(instanceSchedules));
        }
        Set<Integer> managed = HohenheimAccess.instanceIdsWith(ctx, HohenheimAccess.MANAGE);
        if (managed.isEmpty()) {
            return AccessDecision.allow(QueryPredicate.of(
                Models.get(RecordScheduleModel.class).matchNone()));
        }
        Set<String> ids = new LinkedHashSet<>();
        for (Integer id : managed) {
            ids.add(String.valueOf(id));
        }
        return AccessDecision.allow(QueryPredicate.of(new CompositeCriteria(CompositeOperator.AND,
            instanceSchedules, RecordScheduleModel.RECORD_ID.in(ids))));
    }
}
