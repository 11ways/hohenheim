package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.cms.common.access.AccessDecision;
import be.elevenways.zenit.cms.common.access.AccessFunction;
import be.elevenways.zenit.cms.common.access.QueryPredicate;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.orm.query.QueryBuilder;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.common.task.record.RecordScheduleModel;
import be.elevenways.zenit.common.task.record.RecordScheduleStepModel;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * The /manage view over schedule steps, scoped through the PARENT schedule: a step is
 * visible exactly when its schedule is.
 *
 * AIDEV-NOTE: the base resource has no read scope at all (ALLOW_ALL), which is why this
 * class exists and why it may not simply be reused in the delegated panel. The scope is
 * resolved by ENUMERATING the visible schedule ids rather than by a correlated subquery
 * -- the ORM offers no EXISTS composition here, the set is one tenant's schedules, and
 * an empty set becomes matchNone rather than IN () (which some backends widen).
 */
public final class ManageInstanceScheduleStepResource extends InstanceScheduleStepResource {

    @Override
    public @NonNull Identifier id() {
        return Identifier.of("hohenheim", "manage_instance_schedule_step");
    }

    @Override
    public @NonNull AccessFunction<Row> accessFunction() {
        return ctx -> {
            if (HohenheimAccess.isAdmin(ctx)) {
                return AccessDecision.allowAll();
            }
            Set<Integer> scheduleIds = visibleScheduleIds(ctx);
            if (scheduleIds.isEmpty()) {
                return AccessDecision.allow(QueryPredicate.of(
                    Models.get(RecordScheduleStepModel.class).matchNone()));
            }
            return AccessDecision.allow(QueryPredicate.of(
                RecordScheduleStepModel.SCHEDULE_ID.in(scheduleIds)));
        };
    }

    /** The schedule ids the sibling resource's own scope lets this context see. */
    private static @NonNull Set<Integer> visibleScheduleIds(@NonNull AccessContext ctx) {
        QueryBuilder<Row> query = Models.get(RecordScheduleModel.class).find();
        var predicate = ManageInstanceScheduleResource.decide(ctx).predicate();
        if (predicate != null) {
            query.where(predicate.criteria());
        }
        Set<Integer> ids = new LinkedHashSet<>();
        for (Row schedule : query.all()) {
            ids.add(schedule.get(RecordScheduleModel.ID));
        }
        return ids;
    }
}
