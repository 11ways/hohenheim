package be.elevenways.hohenheim.server.cms;

import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.cms.common.access.AccessDecision;
import be.elevenways.zenit.cms.common.access.AccessFunction;
import be.elevenways.zenit.cms.common.resource.RecordScopedPage;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.orm.query.QueryBuilder;
import be.elevenways.zenit.common.orm.query.criteria.Criteria;
import be.elevenways.zenit.common.task.record.RecordScheduleModel;
import be.elevenways.zenit.common.task.record.RecordScheduleStepModel;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.util.LinkedHashSet;
import java.util.List;
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

    /**
     * The scope is derived from the parent's tri-state ({@link TenantScopes#INSTANCE_SCHEDULES}'
     * access half), never a hand-rolled isAdmin prefix: an unconstrained parent scope (the
     * walk's admin row, or a future instances-wide type-level row) answers ALL here too,
     * without enumerating.
     */
    @Override
    public @NonNull AccessFunction<Row> accessFunction() {
        return ctx -> {
            if (TenantScopes.INSTANCE_SCHEDULES.access().apply(ctx) == null) {
                return AccessDecision.allowAll();
            }
            Set<Integer> scheduleIds = visibleScheduleIds(TenantScopes.INSTANCE_SCHEDULES.criteria(ctx));
            return TenantScopes.decision(scheduleIds.isEmpty()
                ? Models.get(RecordScheduleStepModel.class).matchNone()
                : RecordScheduleStepModel.SCHEDULE_ID.in(scheduleIds));
        };
    }

    /**
     * The contributed pages only. Deliberately NOT frameworkSubpages(): the admin
     * activity/revision history stays off the delegated surface.
     */
    @Override
    public @NonNull List<RecordScopedPage<Row>> subpages() {
        return this.contributedSubpages();
    }

    /** The schedule ids the parent resource's own scope lets this context see. */
    private static @NonNull Set<Integer> visibleScheduleIds(@NonNull Criteria schedules) {
        QueryBuilder<Row> query = Models.get(RecordScheduleModel.class).find()
            .where(schedules);
        Set<Integer> ids = new LinkedHashSet<>();
        for (Row schedule : query.all()) {
            ids.add(schedule.get(RecordScheduleModel.ID));
        }
        return ids;
    }
}
