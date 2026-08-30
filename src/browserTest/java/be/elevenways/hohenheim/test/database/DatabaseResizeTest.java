package be.elevenways.hohenheim.test.database;

import be.elevenways.hohenheim.model.DatabaseModel;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.server.cms.DatabaseResource;
import be.elevenways.hohenheim.server.cms.ManageDatabaseResource;
import be.elevenways.hohenheim.server.database.DatabaseInstances;
import be.elevenways.hohenheim.server.host.HostPreflight;
import be.elevenways.hohenheim.server.orm.GeneratedRows;
import be.elevenways.hohenheim.test.HohenheimTestBase;
import be.elevenways.hohenheim.test.host.HostFixtures;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.common.validation.Violations;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * An operator can CORRECT a managed database's resource ceilings, and nothing else about
 * it, without deleting the record.
 *
 * The gap this pins, observed while migrating apps onto robbedoes on 2026-08-30: a
 * database booked its memory at create and no surface could ever change it. The detail
 * page offered only Delete, and the engine's own instance row is {@code generatedOnly()}
 * and refuses edits, pointing back at the database's surface. An oversized database on a
 * full host could therefore only be fixed by DELETING it -- which for a database holding
 * data is not a fix at all.
 *
 * The refusal half matters as much as the resize: a ceiling the host cannot afford is
 * refused ON THE FORM by the inline reservation, not discovered on a pool thread after
 * the record has already flipped to failed.
 */
class DatabaseResizeTest extends HohenheimTestBase {

    private static final String PREFIX = "dbresize-";

    private static Integer roomyHostId;
    private static Integer boundedHostId;
    private static Integer databaseId;
    private static Integer crampedDatabaseId;

    @BeforeAll
    static void seed() {
        roomyHostId = host(PREFIX + "roomy", 16L * 1024 * 1024 * 1024);
        // Big enough to carry the engine the fixture plants (postgres books its
        // 512 MB footprint on that write), far too small for the ceiling step 7 asks.
        boundedHostId = host(PREFIX + "bounded", 4L * 1024 * 1024 * 1024);
        databaseId = database(PREFIX + "db", roomyHostId);
        crampedDatabaseId = database(PREFIX + "cramped", boundedHostId);
    }

    @AfterAll
    static void cleanUp() {
        Model instances = Models.get(InstanceModel.class);
        GeneratedRows.sweeping("test", () -> {
            for (Row row : instances.find().where(InstanceModel.NAME.startsWith("db-" + PREFIX))
                    .all()) {
                instances.delete(row.get(InstanceModel.ID));
            }
        });
        Model databases = Models.get(DatabaseModel.class);
        for (Row row : databases.find().where(DatabaseModel.NAME.startsWith(PREFIX)).all()) {
            databases.delete(row.get(DatabaseModel.ID));
        }
        Model servers = Models.get(ServerModel.class);
        for (Row row : servers.find().where(ServerModel.NAME.startsWith(PREFIX)).all()) {
            servers.delete(row.get(ServerModel.ID));
        }
    }

    @Test
    void anOperatorResizesADatabaseInPlaceAndAnUnaffordableCeilingIsRefusedOnTheForm() {
        DatabaseResource databases = new DatabaseResource();
        Model model = Models.get(DatabaseModel.class);

        // 1. The surface is open at all -- the counterfactual for the whole gap. Before
        //    this change updatable() was false and there was no edit form to submit.
        assertThat(databases.updatable())
            .as("step 1: the admin database form saves, for the resource ceilings")
            .isTrue();

        // 2. ... and it is open for the OPERATOR only. A tenant write to a stored
        //    database row is refused by the model funnel whatever it carries, so the
        //    /manage surface must not inherit an editor whose every Save is refused.
        assertThat(new ManageDatabaseResource().updatable())
            .as("step 2: the tenant surface stays closed")
            .isFalse();

        // 3. Saving the form UNCHANGED changes nothing and schedules no redeploy. A
        //    deploy is a container RECREATE, so treating Save as "recreate" would drop
        //    every live connection for nothing.
        Row database = model.findById(databaseId);
        Object settingsBefore = engineSettings(databaseId);
        databases.updateRow(database, coerced(null, null), AccessContext.anonymous());
        assertThat((String) model.findById(databaseId).get(DatabaseModel.STATUS))
            .as("step 3: an unchanged save leaves the record alone")
            .isEqualTo(DatabaseModel.STATUS_ACTIVE);
        assertThat(engineSettings(databaseId))
            .as("step 3: and never rewrites the engine instance")
            .isEqualTo(settingsBefore);

        // 4. A NEW ceiling lands on the record...
        databases.updateRow(model.findById(databaseId), coerced(2048, null),
            AccessContext.anonymous());
        Row resized = model.findById(databaseId);
        assertThat((Integer) resized.get(DatabaseModel.MEMORY_LIMIT_MB))
            .as("step 4: the record carries the corrected ceiling")
            .isEqualTo(2048);

        // 5. ... and on the ENGINE INSTANCE, which is what actually reaches the daemon.
        //    Asserting only the record would pass against a change that never leaves the
        //    database table, and the operator's container would keep its old cgroup cap.
        assertThat(engineSettingsMap(databaseId))
            .as("step 5: the engine instance carries it, so the recreate applies it")
            .containsEntry("memory_limit_mb", 2048);

        // 6. The record says a recreate is under way. (Not asserted as exactly
        //    "provisioning": the background lane owns the row from here and, with no
        //    daemon in this lane, flips it to failed on its own thread.)
        assertThat((String) resized.get(DatabaseModel.STATUS))
            .as("step 6: the record left active, because the engine is being recreated")
            .isIn(DatabaseModel.STATUS_PROVISIONING, DatabaseModel.STATUS_FAILED);

        // 7. THE REFUSAL: a ceiling the host cannot afford is refused HERE, on the form,
        //    by the same booking the create path makes -- naming the host, what was asked
        //    and what is free. Before the inline reservation the operator's only feedback
        //    would have been the record turning red minutes later.
        Row cramped = model.findById(crampedDatabaseId);
        Integer ceilingBefore = cramped.get(DatabaseModel.MEMORY_LIMIT_MB);
        Throwable refused = catchThrowable(() -> databases.updateRow(cramped,
            coerced(65536, null), AccessContext.anonymous()));
        assertThat(refused)
            .as("step 7: an unaffordable ceiling is refused on the form")
            .isInstanceOf(Violations.class);
        assertThat(violationKeys(refused)).contains("host_capacity_reached");

        // 8. And the refusal left the record exactly as it was: no half-applied resize.
        Row untouched = model.findById(crampedDatabaseId);
        assertThat((Integer) untouched.get(DatabaseModel.MEMORY_LIMIT_MB))
            .as("step 8: a refused resize changes nothing")
            .isEqualTo(ceilingBefore);
        assertThat((String) untouched.get(DatabaseModel.STATUS))
            .as("step 8: and never marks the record as provisioning")
            .isEqualTo(DatabaseModel.STATUS_ACTIVE);
    }

    private static Map<String, Object> coerced(Integer memoryMb, Double cpus) {
        Map<String, Object> values = new HashMap<>();
        values.put("memory_limit_mb", memoryMb);
        values.put("cpu_limit", cpus);
        return values;
    }

    /** The engine instance's stored settings, or null when it owns none. */
    private static Object engineSettings(int databaseId) {
        Row engine = DatabaseInstances.owned(databaseId);
        return engine == null ? null : String.valueOf(engine.get(InstanceModel.SETTINGS));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> engineSettingsMap(int databaseId) {
        Row engine = DatabaseInstances.owned(databaseId);
        assertThat(engine).as("the database owns an engine instance").isNotNull();
        return (Map<String, Object>) engine.get(InstanceModel.SETTINGS);
    }

    private static String violationKeys(Throwable thrown) {
        StringBuilder keys = new StringBuilder();
        for (var violation : ((Violations) thrown).all()) {
            keys.append(violation.message().key()).append(' ');
        }
        return keys.toString();
    }

    private static int host(String name, long memTotalBytes) {
        Model servers = Models.get(ServerModel.class);
        Row row = servers.createEmptyRow();
        row.set(ServerModel.NAME, name);
        row.set(ServerModel.RUNTIME, ServerModel.RUNTIME_DOCKER);
        row.set(ServerModel.MODE, ServerModel.MODE_LOCAL);
        row.set(ServerModel.POSTURE, ServerModel.POSTURE_SHARED_CONTAINER);
        row.set(ServerModel.ADMISSION, ServerModel.ADMISSION_ADMITTED);
        row.set(ServerModel.PREFLIGHT_OK, true);
        servers.save(row);
        HostFixtures.acknowledgePosture(row);
        HostPreflight.store(name, new HostPreflight.Report(
            List.of(new HostPreflight.Check("daemon", HostPreflight.STATUS_PASS, true, "ok")),
            Map.of("mem_total", memTotalBytes), true, Instant.now(), null));
        return row.get(ServerModel.ID);
    }

    private static int database(String name, Integer serverId) {
        Model databases = Models.get(DatabaseModel.class);
        Row row = databases.createEmptyRow();
        row.set(DatabaseModel.NAME, name);
        row.set(DatabaseModel.ENGINE, "postgres");
        row.set(DatabaseModel.DB_NAME, "appdb");
        row.set(DatabaseModel.DB_USER, "appuser");
        row.set(DatabaseModel.DB_PASSWORD, "s3cr3t-resize-pw");
        row.set(DatabaseModel.SERVER_ID, serverId);
        row.set(DatabaseModel.STATUS, DatabaseModel.STATUS_ACTIVE);
        databases.save(row);
        Integer id = row.get(DatabaseModel.ID);
        EngineHandles.plant(id, name, "postgres", InstanceModel.STATUS_RUNNING);
        return id;
    }
}
