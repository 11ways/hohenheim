package be.elevenways.hohenheim.test.instance;

import be.elevenways.hohenheim.HohenheimSlugs;
import be.elevenways.hohenheim.test.PanelEntryViews;
import be.elevenways.hohenheim.instance.InstanceOperations;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.HohenheimCapabilities;
import be.elevenways.hohenheim.test.ApiSupport;
import be.elevenways.hohenheim.test.HardDeletes;
import be.elevenways.hohenheim.test.HohenheimTestBase;
import be.elevenways.hohenheim.test.TenantConduits;
import be.elevenways.zenit.auth.model.GrantSubjectType;
import be.elevenways.zenit.auth.model.UserPrincipal;
import be.elevenways.zenit.auth.server.RecordGrants;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.common.task.record.RecordScheduleModel;
import be.elevenways.zenit.common.task.record.RecordScheduleStepModel;
import be.elevenways.zenit.common.validation.Violations;
import be.elevenways.zenit.cms.common.resource.PanelResource;
import be.elevenways.zenit.cms.server.panel.PartsWrites;
import be.elevenways.zenit.cms.server.panel.ResourceVerbs;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Shaping a schedule's chain demands CONFIG on the schedule's instance, on create AND edit, and a
 * step never moves to another schedule.
 *
 * The defect pinned here: step create checked only the chosen ACTION's capability, so a delegate
 * holding just {@code power} on an instance could add power steps to a chain they may not shape;
 * the create affordance was offered to them; and an edit could
 * repoint a step's schedule_id at ANOTHER schedule, which nothing checked for CONFIG.
 */
class ScheduleStepAuthorityTest extends HohenheimTestBase {

    private static final String PREFIX = "stepauth-";

    private static Integer powerOnlyId;
    private static Integer ownerId;
    private static Integer instanceId;
    private static Integer otherInstanceId;
    private static Integer scheduleId;
    private static Integer otherScheduleId;
    private static final List<Integer> stepIds = new ArrayList<>();

    @BeforeAll
    static void seed() {
        powerOnlyId = ApiSupport.user(PREFIX + "power@surface.test", "Power Only");
        ownerId = ApiSupport.user(PREFIX + "owner@surface.test", "Chain Owner");
        instanceId = instance(PREFIX + "target");
        otherInstanceId = instance(PREFIX + "other");

        RecordGrants.grant(GrantSubjectType.USER, powerOnlyId, InstanceModel.MODEL_ID, instanceId,
            HohenheimCapabilities.POWER, true);
        RecordGrants.grant(GrantSubjectType.USER, powerOnlyId, InstanceModel.MODEL_ID, instanceId,
            HohenheimCapabilities.VIEW, true);
        RecordGrants.grant(GrantSubjectType.USER, ownerId, InstanceModel.MODEL_ID, instanceId,
            HohenheimCapabilities.MANAGE, true);
        RecordGrants.grant(GrantSubjectType.USER, ownerId, InstanceModel.MODEL_ID, otherInstanceId,
            HohenheimCapabilities.MANAGE, true);

        scheduleId = schedule(instanceId, PREFIX + "nightly");
        otherScheduleId = schedule(otherInstanceId, PREFIX + "other-nightly");
    }

    @AfterAll
    static void cleanUp() {
        for (Integer stepId : stepIds) {
            Models.get(RecordScheduleStepModel.class).delete(stepId);
        }
        for (Integer id : new Integer[] {scheduleId, otherScheduleId}) {
            if (id != null) {
                Models.get(RecordScheduleModel.class).delete(id);
            }
        }
        for (Integer id : new Integer[] {instanceId, otherInstanceId}) {
            if (id != null) {
                HardDeletes.byId(Models.get(InstanceModel.class), id);
            }
        }
    }

    private static int instance(String name) {
        Model instances = Models.get(InstanceModel.class);
        Row instance = instances.createEmptyRow();
        instance.set(InstanceModel.NAME, name);
        instance.set(InstanceModel.KIND, "hohenheim:docker_container");
        instance.set(InstanceModel.SETTINGS, new LinkedHashMap<>(
            Map.of("image", "alpine", "tag", "latest", "command", "sleep 300")));
        instance.set(InstanceModel.STATUS, InstanceModel.STATUS_CREATED);
        instances.save(instance);
        return instance.get(InstanceModel.ID);
    }

    private static int schedule(int targetId, String name) {
        Model schedules = Models.get(RecordScheduleModel.class);
        Row schedule = schedules.createEmptyRow();
        schedule.set(RecordScheduleModel.MODEL, InstanceModel.MODEL_ID.toString());
        schedule.set(RecordScheduleModel.RECORD_ID, String.valueOf(targetId));
        schedule.set(RecordScheduleModel.NAME, name);
        schedule.set(RecordScheduleModel.CRON, "0 4 * * *");
        schedule.set(RecordScheduleModel.ENABLED, false);
        schedules.save(schedule);
        return schedule.get(RecordScheduleModel.ID);
    }

    private static AccessContext contextOf(int userId, String name) {
        return AccessContext.of(TenantConduits.stubFor(new UserPrincipal(userId, name)));
    }

    private static Map<String, Object> stepValues(int forSchedule) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put(RecordScheduleStepModel.SCHEDULE_ID.getName(), forSchedule);
        values.put(RecordScheduleStepModel.POSITION.getName(), 1);
        values.put(RecordScheduleStepModel.ACTION.getName(), InstanceOperations.RESTART.id().toString());
        return Map.copyOf(values);
    }

    @Test
    void shapingTheChainDemandsConfigAndAStepStaysInItsSchedule() {
        PanelResource<Row> steps = PanelEntryViews.of(HohenheimSlugs.MANAGE, HohenheimSlugs.INSTANCE_SCHEDULE_STEPS);
        AccessContext powerOnly = contextOf(powerOnlyId, "Power Only");
        AccessContext owner = contextOf(ownerId, "Chain Owner");
        long stepsBefore = Models.get(RecordScheduleStepModel.class).find()
            .where(RecordScheduleStepModel.SCHEDULE_ID.eq(scheduleId)).count();

        // 1. The create AFFORDANCE: a power-only delegate on the schedule's Steps tab is not
        //    offered "add step" under that schedule; the chain's manager is.
        assertThat(ResourceVerbs.createUnderPermits(steps, scheduleId, powerOnly))
            .as("step 1: a delegate holding only the action's verb is not offered step create")
            .isFalse();
        assertThat(ResourceVerbs.createUnderPermits(steps, scheduleId, owner))
            .as("step 1: the manage holder (CONFIG implied) is").isTrue();

        // 2. The create GATE: a direct submit by the power-only delegate is refused, although
        //    they hold the power step's own capability, and nothing is written.
        assertThatThrownBy(() -> PartsWrites.persistRow(steps, stepValues(scheduleId), powerOnly))
            .as("step 2: a power-only delegate cannot add a step to the chain")
            .isInstanceOf(Violations.class);
        assertThat(Models.get(RecordScheduleStepModel.class).find()
                .where(RecordScheduleStepModel.SCHEDULE_ID.eq(scheduleId)).count())
            .as("step 2: the refused create wrote no step").isEqualTo(stepsBefore);

        // 3. The manage holder adds the step.
        Integer stepId = (Integer) PartsWrites.persistRow(steps, stepValues(scheduleId), owner);
        stepIds.add(stepId);
        assertThat(stepId).as("step 3: the manage holder's create succeeds").isNotNull();

        // 4. An edit may not MOVE the step to another schedule -- not even by someone who
        //    manages both instances.
        Row step = Models.get(RecordScheduleStepModel.class).findById(stepId);
        assertThatThrownBy(() -> PartsWrites.updateRow(steps, step,
                Map.of(RecordScheduleStepModel.SCHEDULE_ID.getName(), otherScheduleId), owner))
            .as("step 4: repointing a step at another schedule is refused")
            .isInstanceOf(Violations.class);
        assertThat((Integer) Models.get(RecordScheduleStepModel.class).findById(stepId)
                .get(RecordScheduleStepModel.SCHEDULE_ID))
            .as("step 4: the step still belongs to the schedule it was created in")
            .isEqualTo(scheduleId);

        // 5. An ordinary edit that leaves the schedule alone still works for the manage holder
        //    and is refused for the power-only delegate.
        PartsWrites.updateRow(steps, Models.get(RecordScheduleStepModel.class).findById(stepId),
            Map.of(RecordScheduleStepModel.POSITION.getName(), 2), owner);
        assertThat((Integer) Models.get(RecordScheduleStepModel.class).findById(stepId)
                .get(RecordScheduleStepModel.POSITION))
            .as("step 5: the manage holder's in-place edit lands").isEqualTo(2);
        assertThatThrownBy(() -> PartsWrites.updateRow(steps,
                Models.get(RecordScheduleStepModel.class).findById(stepId),
                Map.of(RecordScheduleStepModel.POSITION.getName(), 3), powerOnly))
            .as("step 5: a power-only delegate cannot edit the chain")
            .isInstanceOf(Violations.class);
    }
}
