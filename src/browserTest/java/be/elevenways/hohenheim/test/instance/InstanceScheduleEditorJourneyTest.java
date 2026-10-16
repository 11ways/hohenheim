package be.elevenways.hohenheim.test.instance;

import be.elevenways.hohenheim.instance.InstanceOperations;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.test.HardDeletes;
import be.elevenways.hohenheim.test.HohenheimTestBase;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.task.record.RecordScheduleModel;
import be.elevenways.zenit.common.task.record.RecordScheduleStepModel;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * An instance schedule's cron edits as a schedule picker on its record page: a backup schedule moves from every night
 * to Sunday night through the presets, a power schedule keeps and edits its custom expression, an expression the
 * scheduler cannot run is refused on the field, and a new schedule saved untouched stores the default it shows.
 *
 * @author Jelle De Loecker
 * @since  0.10.0
 */
class InstanceScheduleEditorJourneyTest extends HohenheimTestBase {

    private static final String PREFIX = "cron-editor-";
    private static final String INPUT = "zf-cron-input";
    private static final String PRESET = INPUT + " [data-zf-cron-preset]";
    private static final String READING = INPUT + " [data-zf-cron-reading]";
    private static final String CUSTOM = INPUT + " [data-zf-cron-custom] input";

    private static Integer instanceId;
    private static Integer backupId;
    private static Integer powerId;
    private static Integer createdId;

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
        backupId = schedule("nightly backup", "0 3 * * *", InstanceOperations.BACKUP.id().toString());
        powerId = schedule("office hours", "*/30 8-18 * * 1-5", InstanceOperations.STOP.id().toString());
    }

    @AfterAll
    static void cleanUp() {
        Model steps = Models.get(RecordScheduleStepModel.class);
        for (Integer id : new Integer[] {backupId, powerId, createdId}) {
            if (id == null) {
                continue;
            }
            for (Row step : steps.find().where(RecordScheduleStepModel.SCHEDULE_ID.eq(id)).all()) {
                steps.delete(step.get(RecordScheduleStepModel.ID));
            }
            Models.get(RecordScheduleModel.class).delete(id);
        }
        if (instanceId != null) {
            HardDeletes.byId(Models.get(InstanceModel.class), instanceId);
        }
    }

    @Test
    void aBackupAndAPowerScheduleEditTheirCronThroughThePicker() {
        // 1. The backup schedule's nightly cron opens on "Every day", read in words with its next run.
        navigateToApp("/admin/instance-schedules/" + backupId);
        waitForHydration();
        assertThat(page.locator(PRESET + " .pl-select-field").innerText()).as("step 1: it opens on every day")
            .contains("Every day");
        assertThat(page.locator(READING).innerText()).as("step 1: read in words with its next run")
            .contains("Every day at 03:00").contains("Next run:");

        // 2. Weekly on Sunday: the reading follows the pick before anything is saved, and the save stores the cron
        //    and arms its next run on a Sunday at 03:00 in the schedule's zone.
        choose(PRESET, "weekly");
        choose(INPUT + " [data-zf-cron-weekday]", "7");
        page.waitForCondition(() -> page.locator(READING).innerText().contains("Every Sunday at 03:00"));
        saveForm();
        Row backup = Models.get(RecordScheduleModel.class).findById(backupId);
        assertThat(backup.get(RecordScheduleModel.CRON)).as("step 2: the weekly cron is stored")
            .isEqualTo("0 3 * * 0");
        ZonedDateTime next = ((Instant) backup.get(RecordScheduleModel.NEXT_FIRE_AT))
            .atZone(ZoneId.of("Europe/Brussels"));
        assertThat(next.getDayOfWeek()).as("step 2: its next run is a Sunday").isEqualTo(DayOfWeek.SUNDAY);
        assertThat(next.getHour()).as("step 2: at 03:00 in the schedule's zone").isEqualTo(3);

        // 3. The power schedule's expression no preset writes opens on Custom, as stored, and saves its edit.
        navigateToApp("/admin/instance-schedules/" + powerId);
        waitForHydration();
        assertThat(page.locator(PRESET + " .pl-select-field").innerText()).as("step 3: it opens on custom")
            .contains("Custom (cron)");
        assertThat(page.locator(CUSTOM).inputValue()).as("step 3: as stored").isEqualTo("*/30 8-18 * * 1-5");
        page.locator(CUSTOM).fill("0 7 * * 1-5");
        page.locator(CUSTOM).blur();
        waitForReactiveIdle();
        saveForm();
        assertThat(Models.get(RecordScheduleModel.class).findById(powerId).get(RecordScheduleModel.CRON))
            .as("step 3: the edited custom cron is stored").isEqualTo("0 7 * * 1-5");

        // 4. An expression the scheduler cannot run is refused on the field and the stored one stays.
        navigateToApp("/admin/instance-schedules/" + powerId);
        waitForHydration();
        page.locator(CUSTOM).fill("61 * * * *");
        page.locator(CUSTOM).blur();
        waitForReactiveIdle();
        page.click(".cms-form-actions pl-button[type='submit']");
        page.waitForSelector("text=This is not a cron expression a schedule can run");
        assertThat(Models.get(RecordScheduleModel.class).findById(powerId).get(RecordScheduleModel.CRON))
            .as("step 4: the stored cron stays").isEqualTo("0 7 * * 1-5");

        // 5. A new schedule saved without touching the picker stores the "Every day at 02:00" the picker shows.
        navigateToApp("/admin/instance-schedules/new?record_id=" + instanceId);
        waitForHydration();
        assertThat(page.locator(READING).innerText()).as("step 5: the new picker reads its default")
            .contains("Every day at 02:00");
        type("input[name='name']", PREFIX + "untouched");
        page.evaluate("document.querySelector('form.cms-form-layout').requestSubmit()");
        page.waitForURL(url -> url.contains("/page/steps"));
        Row created = Models.get(RecordScheduleModel.class).find()
            .where(RecordScheduleModel.NAME.eq(PREFIX + "untouched")).first();
        assertThat(created).as("step 5: the schedule is created, not refused").isNotNull();
        createdId = created.get(RecordScheduleModel.ID);
        assertThat(created.get(RecordScheduleModel.CRON)).as("step 5: the shown default is stored")
            .isEqualTo("0 2 * * *");
    }

    private static int schedule(String name, String cron, String action) {
        Model schedules = Models.get(RecordScheduleModel.class);
        Row schedule = schedules.createEmptyRow();
        schedule.set(RecordScheduleModel.MODEL, InstanceModel.MODEL_ID.toString());
        schedule.set(RecordScheduleModel.RECORD_ID, String.valueOf(instanceId));
        schedule.set(RecordScheduleModel.NAME, PREFIX + name);
        schedule.set(RecordScheduleModel.CRON, cron);
        schedule.set(RecordScheduleModel.TIMEZONE, "Europe/Brussels");
        // Off, so no sweep fires the chain against the fixture instance while the journey edits it.
        schedule.set(RecordScheduleModel.ENABLED, false);
        schedules.save(schedule);
        int id = schedule.get(RecordScheduleModel.ID);
        Model steps = Models.get(RecordScheduleStepModel.class);
        Row step = steps.createEmptyRow();
        step.set(RecordScheduleStepModel.SCHEDULE_ID, id);
        step.set(RecordScheduleStepModel.POSITION, 1);
        step.set(RecordScheduleStepModel.ACTION, action);
        step.set(RecordScheduleStepModel.RETRY_LIMIT, 3);
        steps.save(step);
        return id;
    }

    private void choose(String select, String value) {
        click(select + " .pl-select-field");
        String option = "he-bottom [data-open] [role='option'][data-value='" + value + "']";
        waitForSelector(option);
        click(option);
        waitForReactiveIdle();
    }

    private void saveForm() {
        page.click(".cms-form-actions pl-button[type='submit']");
        page.waitForSelector("text=have been saved");
    }
}
