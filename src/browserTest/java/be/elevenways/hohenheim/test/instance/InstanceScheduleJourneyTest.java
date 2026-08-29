package be.elevenways.hohenheim.test.instance;

import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.server.schedule.InstancePowerAction;
import be.elevenways.hohenheim.test.HohenheimTestBase;
import be.elevenways.zenit.auth.model.UserModel;
import be.elevenways.zenit.auth.server.AuthModels;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.task.record.RecordScheduleModel;
import be.elevenways.zenit.common.task.record.RecordScheduleStepModel;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.http.HttpResponse;
import java.time.Instant;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The operator's schedule journey through the real panel forms: open the instance's
 * Schedules tab, add a schedule, give it a power step, see it listed with its next run,
 * delete it.
 *
 * Pinned defect (QA 2026-08-29, F8): "Add schedule" landed on a generic "New in
 * Schedules" form with a raw Record ID box, and Save answered "Saving failed" -- the
 * resource staged {@code model}/{@code run_as} on the coerced map, but the framework
 * writes FORM ENTRIES only, so the INSERT hit the NOT NULL {@code model} column. Every
 * earlier test wrote schedule rows directly and never took the form path.
 */
class InstanceScheduleJourneyTest extends HohenheimTestBase {

    private static final String PREFIX = "schedjourney-";

    private static Integer instanceId;
    private static Integer scheduleId;

    @BeforeAll
    static void seed() {
        Model instances = Models.get(InstanceModel.class);
        Row instance = instances.createEmptyRow();
        instance.set(InstanceModel.NAME, PREFIX + "target");
        instance.set(InstanceModel.KIND, "hohenheim:docker_container");
        instance.set(InstanceModel.SETTINGS, new LinkedHashMap<>(
            Map.of("image", "alpine", "tag", "latest", "command", "sleep 300")));
        instance.set(InstanceModel.STATUS, InstanceModel.STATUS_CREATED);
        instances.save(instance);
        instanceId = instance.get(InstanceModel.ID);
    }

    @AfterAll
    static void cleanUp() {
        if (scheduleId != null) {
            Model steps = Models.get(RecordScheduleStepModel.class);
            for (Row step : steps.find().where(RecordScheduleStepModel.SCHEDULE_ID.eq(scheduleId)).all()) {
                steps.delete(step.get(RecordScheduleStepModel.ID));
            }
            Models.get(RecordScheduleModel.class).delete(scheduleId);
        }
        if (instanceId != null) {
            Models.get(InstanceModel.class).delete(instanceId);
        }
    }

    @Test
    void addAPowerScheduleFromTheTabSeeItsNextRunAndDeleteIt() throws Exception {
        // 1. The tab offers "Add schedule" pointing at the scoped create form.
        HttpResponse<String> tab = adminGet("/admin/instances/" + instanceId + "/page/schedules");
        assertThat(tab.statusCode()).as("step 1: the Schedules tab renders").isEqualTo(200);
        assertThat(tab.body())
            .as("step 1: the add link carries the instance")
            .contains("add-schedule-link")
            .contains("record_id=" + instanceId);

        // 2. The create form is headed by the record label and shows the INSTANCE, picked
        //    by name, not a raw id box.
        HttpResponse<String> form = adminGet("/admin/instance-schedules/new?record_id=" + instanceId);
        assertThat(form.statusCode()).as("step 2: the create form renders").isEqualTo(200);
        assertThat(form.body())
            .as("step 2: the heading names the singular, the picker names the instance")
            .contains("New schedule")
            .doesNotContain("New in ")
            .contains(PREFIX + "target");

        // 3. Save through the real form: the row lands with the target model, the editor's
        //    authority and a REAL next fire (04:00 in the schedule's zone, not "now").
        HttpResponse<String> created = httpPostForm("/admin/instance-schedules/new",
            "record_id=" + instanceId + "&name=" + PREFIX + "nightly&cron=0+4+*+*+*"
                + "&timezone=Europe%2FBrussels&enabled=false&enabled=true",
            sessionToken, csrfToken);
        assertThat(created.statusCode()).as("step 3: the save is a redirect, not a rerender")
            .isEqualTo(302);
        Row schedule = Models.get(RecordScheduleModel.class).find()
            .where(RecordScheduleModel.NAME.eq(PREFIX + "nightly")).first();
        assertThat(schedule).as("step 3: the schedule row exists").isNotNull();
        scheduleId = schedule.get(RecordScheduleModel.ID);
        assertThat(created.headers().firstValue("Location").orElse(""))
            .as("step 3: a fresh schedule lands on its steps, where the next act is")
            .contains("/admin/instance-schedules/" + scheduleId + "/page/steps");
        assertThat(schedule.get(RecordScheduleModel.MODEL))
            .as("step 3: the target model is stamped").isEqualTo(InstanceModel.MODEL_ID.toString());
        assertThat(schedule.get(RecordScheduleModel.RECORD_ID))
            .as("step 3: the target record is the instance").isEqualTo(String.valueOf(instanceId));
        Row admin = AuthModels.users().find().where(UserModel.EMAIL.eq("test@hohenheim.local")).first();
        assertThat(schedule.get(RecordScheduleModel.RUN_AS))
            .as("step 3: the chain runs as the editor")
            .isEqualTo(((Integer) admin.get(UserModel.ID)).longValue());
        Instant nextFire = schedule.get(RecordScheduleModel.NEXT_FIRE_AT);
        assertThat(nextFire).as("step 3: the first fire is computed on save").isNotNull();
        assertThat(nextFire).as("step 3: and lies in the future, never 'due now'").isAfter(Instant.now());
        assertThat(nextFire.atZone(ZoneId.of("Europe/Brussels")).getHour())
            .as("step 3: at the cron's hour in the schedule's own zone").isEqualTo(4);

        // 4. Add a power step through its form: the action vocabulary is the registry's.
        HttpResponse<String> step = httpPostForm("/admin/instance-schedule-steps/new",
            "schedule_id=" + scheduleId + "&step_order=1&action=" + InstancePowerAction.ID
                + "&payload.operation=" + InstancePowerAction.OP_STOP
                + "&offset_seconds=0&failure_policy=abort&retry_limit=3",
            sessionToken, csrfToken);
        assertThat(step.statusCode()).as("step 4: the step saves").isEqualTo(302);
        Row stored = Models.get(RecordScheduleStepModel.class).find()
            .where(RecordScheduleStepModel.SCHEDULE_ID.eq(scheduleId)).first();
        assertThat(stored).as("step 4: the step row exists").isNotNull();
        assertThat(stored.get(RecordScheduleStepModel.ACTION))
            .as("step 4: it is the power action").isEqualTo(InstancePowerAction.ID.toString());

        // 5. The tab lists the schedule with its next run.
        HttpResponse<String> listed = adminGet("/admin/instances/" + instanceId + "/page/schedules");
        assertThat(listed.body())
            .as("step 5: the schedule is listed with its next-run copy")
            .contains(PREFIX + "nightly")
            .contains("data-next-run")
            .contains(nextFire.toString());

        // 6. Delete it from the record: the chain goes with it.
        HttpResponse<String> deleted = httpPostForm(
            "/admin/instance-schedules/" + scheduleId + "/delete", confirmed(""),
            sessionToken, csrfToken);
        assertThat(deleted.statusCode()).as("step 6: the delete redirects").isEqualTo(302);
        assertThat(Models.get(RecordScheduleModel.class).findById(scheduleId))
            .as("step 6: the schedule is gone").isNull();
        assertThat(Models.get(RecordScheduleStepModel.class).find()
                .where(RecordScheduleStepModel.SCHEDULE_ID.eq(scheduleId)).count())
            .as("step 6: and so is its step").isZero();
        scheduleId = null;
    }
}
