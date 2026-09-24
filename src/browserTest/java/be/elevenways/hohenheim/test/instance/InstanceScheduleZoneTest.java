package be.elevenways.hohenheim.test.instance;

import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.server.cms.InstanceScheduleResource;
import be.elevenways.hohenheim.test.HardDeletes;
import be.elevenways.hohenheim.test.HohenheimTestBase;
import be.elevenways.hohenheim.test.TenantConduits;
import be.elevenways.protoblast.common.time.Now;
import be.elevenways.zenit.auth.model.UserModel;
import be.elevenways.zenit.auth.model.UserPrincipal;
import be.elevenways.zenit.auth.server.AuthModels;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.common.task.record.RecordScheduleModel;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TimeZone;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A schedule's FIRST fire is evaluated in the same zone as every later one.
 *
 * The defect pinned here: the schedule resource computed the first next_fire_at itself in
 * ZoneId.systemDefault() for a blank timezone, while the framework sweep (RecordSchedules.zoneOf)
 * reads a blank timezone as UTC. On any host not running in UTC, a "04:00" schedule's first run
 * landed at 04:00 host time and every later run at 04:00 UTC. The save now arms through
 * RecordSchedules.armCron, the framework's own arming path.
 *
 * AIDEV-NOTE: the JVM default zone is moved to Asia/Kathmandu (UTC+05:45) for the journey and
 * restored in finally: on a UTC host the old defect is invisible, so the test forces the
 * condition that exposed it instead of depending on where the suite happens to run.
 */
class InstanceScheduleZoneTest extends HohenheimTestBase {

    private static final String PREFIX = "schedzone-";

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
            Models.get(RecordScheduleModel.class).delete(scheduleId);
        }
        if (instanceId != null) {
            HardDeletes.byId(Models.get(InstanceModel.class), instanceId);
        }
    }

    private static AccessContext operator() {
        Row admin = AuthModels.users().find()
            .where(UserModel.EMAIL.eq("test@hohenheim.local")).first();
        return AccessContext.of(TenantConduits.stubFor(
            new UserPrincipal(admin.get(UserModel.ID), "Test Admin")));
    }

    private static Row stored() {
        return Models.get(RecordScheduleModel.class).findById(scheduleId);
    }

    @Test
    void theFirstFireAndEveryLaterFireShareOneZone() {
        TimeZone original = TimeZone.getDefault();
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Kathmandu"));
        try {
            InstanceScheduleResource resource = new InstanceScheduleResource();
            AccessContext operator = operator();

            // 1. A schedule saved with NO timezone arms its first fire at 04:00 UTC -- the
            //    zone the sweep evaluates every later fire in -- not at 04:00 host time.
            Map<String, Object> create = new LinkedHashMap<>();
            create.put(RecordScheduleModel.RECORD_ID.getName(), String.valueOf(instanceId));
            create.put(RecordScheduleModel.NAME.getName(), PREFIX + "nightly");
            create.put(RecordScheduleModel.CRON.getName(), "0 4 * * *");
            create.put(RecordScheduleModel.TIMEZONE.getName(), "");
            create.put(RecordScheduleModel.ENABLED.getName(), true);
            scheduleId = (Integer) resource.persistRow(Map.copyOf(create), operator);

            Instant first = stored().get(RecordScheduleModel.NEXT_FIRE_AT);
            assertThat(first).as("step 1: the create armed a first fire").isNotNull();
            ZonedDateTime firstUtc = first.atZone(ZoneOffset.UTC);
            assertThat(firstUtc.getHour() * 60 + firstUtc.getMinute())
                .as("step 1: a blank zone arms the first fire at 04:00 UTC, not 04:00 host time")
                .isEqualTo(4 * 60);
            assertThat(first).as("step 1: the first fire lies ahead").isAfter(Now.instant());

            // 2. Moving the zone re-arms in THAT zone.
            ZoneId brussels = ZoneId.of("Europe/Brussels");
            resource.updateRow(stored(),
                Map.of(RecordScheduleModel.TIMEZONE.getName(), brussels.getId()), operator);
            ZonedDateTime moved = ((Instant) stored().get(RecordScheduleModel.NEXT_FIRE_AT)).atZone(brussels);
            assertThat(moved.getHour() * 60 + moved.getMinute())
                .as("step 2: a declared zone arms 04:00 in that zone").isEqualTo(4 * 60);

            // 3. Re-enabling a schedule whose stored next fire went stale re-arms it, instead of
            //    leaving a past next_fire_at the sweep would read as "due now".
            Instant stale = Now.instant().minusSeconds(86_400);
            Row disabled = stored();
            disabled.set(RecordScheduleModel.ENABLED, false);
            disabled.set(RecordScheduleModel.NEXT_FIRE_AT, stale);
            Models.get(RecordScheduleModel.class).save(disabled);

            resource.updateRow(stored(),
                Map.of(RecordScheduleModel.ENABLED.getName(), true), operator);
            Instant rearmed = stored().get(RecordScheduleModel.NEXT_FIRE_AT);
            assertThat(rearmed)
                .as("step 3: enabling re-arms a stale next fire into the future")
                .isAfter(Now.instant());
        } finally {
            TimeZone.setDefault(original);
        }
    }
}
