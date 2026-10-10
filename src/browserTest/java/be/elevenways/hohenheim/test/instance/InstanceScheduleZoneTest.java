package be.elevenways.hohenheim.test.instance;

import be.elevenways.hohenheim.HohenheimSlugs;
import be.elevenways.hohenheim.test.PanelEntryViews;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.test.HardDeletes;
import be.elevenways.hohenheim.test.HohenheimTestBase;
import be.elevenways.hohenheim.test.TenantConduits;
import be.elevenways.protoblast.common.time.Now;
import be.elevenways.zenit.auth.model.UserModel;
import be.elevenways.zenit.auth.model.UserPrincipal;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.common.task.record.RecordScheduleModel;
import be.elevenways.zenit.cms.common.resource.PanelResource;
import be.elevenways.zenit.cms.server.panel.PartsWrites;
import be.elevenways.zenit.server.time.JvmZoneClock;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TimeZone;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A schedule's FIRST fire is evaluated in the same zone as every later one, and a blank zone is the office zone.
 *
 * The defect pinned here: the schedule resource computed the first next_fire_at itself in ZoneId.systemDefault()
 * for a blank timezone, while the framework sweep (RecordSchedules.zoneOf) reads a blank timezone as the
 * installation's office zone ({@code office.zone}, Europe/Brussels in Hohenheim's default.dry). On a host whose JVM
 * zone was not the office zone, a "04:00" schedule's first run landed at 04:00 host time and every later run at
 * 04:00 office time. The save now arms through RecordSchedules.armCron, the framework's own arming path.
 *
 * AIDEV-NOTE: the JVM default zone is moved to Asia/Kathmandu (UTC+05:45) for the journey and restored in finally:
 * on a host already running in the office zone the old defect is invisible, so the test forces the condition that
 * exposed it instead of depending on where the suite happens to run. The office zone is read through
 * JvmZoneClock.office(), never hard-coded, so the journey follows whatever default.dry declares.
 *
 * @author Jelle De Loecker
 * @since  0.1.0
 */
class InstanceScheduleZoneTest extends HohenheimTestBase {

    private static final String PREFIX = "schedzone-";

    /** UTC+05:45 all year, the forced host zone; step 1 proves the office zone differs from it. */
    private static final String HOST_ZONE = "Asia/Kathmandu";

    /** UTC-03:00 all year, a zone step 3 declares that is neither the host's nor the office's. */
    private static final String DECLARED_ZONE = "America/Sao_Paulo";

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
        Row admin = Models.get(UserModel.class).find()
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
        TimeZone.setDefault(TimeZone.getTimeZone(HOST_ZONE));
        try {
            PanelResource<Row> resource = PanelEntryViews.of(HohenheimSlugs.ADMIN, HohenheimSlugs.INSTANCE_SCHEDULES);
            AccessContext operator = operator();
            ZoneId office = JvmZoneClock.office();
            ZoneId declared = ZoneId.of(DECLARED_ZONE);

            // 1. The office zone differs from the host zone and from the zone step 3 declares, so each step can
            //    tell which zone armed the fire.
            assertThat(office).as("step 1: the office zone is not the host zone").isNotEqualTo(ZoneId.of(HOST_ZONE));
            assertThat(office).as("step 1: the office zone is not the declared zone").isNotEqualTo(declared);

            // 2. A schedule saved with NO timezone arms its first fire at 04:00 in the office zone -- the zone the
            //    sweep evaluates every later fire in -- not at 04:00 host time.
            Map<String, Object> create = new LinkedHashMap<>();
            create.put(RecordScheduleModel.RECORD_ID.getName(), String.valueOf(instanceId));
            create.put(RecordScheduleModel.NAME.getName(), PREFIX + "nightly");
            create.put(RecordScheduleModel.CRON.getName(), "0 4 * * *");
            create.put(RecordScheduleModel.TIMEZONE.getName(), "");
            create.put(RecordScheduleModel.ENABLED.getName(), true);
            scheduleId = (Integer) PartsWrites.persistRow(resource, Map.copyOf(create), operator);

            Instant first = stored().get(RecordScheduleModel.NEXT_FIRE_AT);
            assertThat(first).as("step 2: the create armed a first fire").isNotNull();
            ZonedDateTime firstInOffice = first.atZone(office);
            assertThat(firstInOffice.getHour() * 60 + firstInOffice.getMinute())
                .as("step 2: a blank zone arms the first fire at 04:00 in the office zone, not 04:00 host time")
                .isEqualTo(4 * 60);
            assertThat(first).as("step 2: the first fire lies ahead").isAfter(Now.instant());

            // 3. Moving the zone re-arms in THAT zone.
            PartsWrites.updateRow(resource, stored(),
                Map.of(RecordScheduleModel.TIMEZONE.getName(), declared.getId()), operator);
            ZonedDateTime moved = ((Instant) stored().get(RecordScheduleModel.NEXT_FIRE_AT)).atZone(declared);
            assertThat(moved.getHour() * 60 + moved.getMinute())
                .as("step 3: a declared zone arms 04:00 in that zone, not in the office zone").isEqualTo(4 * 60);

            // 4. Re-enabling a schedule whose stored next fire went stale re-arms it, instead of leaving a past
            //    next_fire_at the sweep would read as "due now".
            Instant stale = Now.instant().minusSeconds(86_400);
            Row disabled = stored();
            disabled.set(RecordScheduleModel.ENABLED, false);
            disabled.set(RecordScheduleModel.NEXT_FIRE_AT, stale);
            Models.get(RecordScheduleModel.class).save(disabled);

            PartsWrites.updateRow(resource, stored(),
                Map.of(RecordScheduleModel.ENABLED.getName(), true), operator);
            Instant rearmed = stored().get(RecordScheduleModel.NEXT_FIRE_AT);
            assertThat(rearmed)
                .as("step 4: enabling re-arms a stale next fire into the future")
                .isAfter(Now.instant());
        } finally {
            TimeZone.setDefault(original);
        }
    }
}
