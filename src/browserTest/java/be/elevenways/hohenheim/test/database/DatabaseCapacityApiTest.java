package be.elevenways.hohenheim.test.database;

import be.elevenways.hohenheim.model.DatabaseEngineModel;
import be.elevenways.hohenheim.model.DatabaseModel;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.server.database.DatabaseService;
import be.elevenways.hohenheim.server.database.ManagedDatabase;
import be.elevenways.hohenheim.server.host.HostPreflight;
import be.elevenways.hohenheim.test.HohenheimTestBase;
import be.elevenways.hohenheim.test.host.HostFixtures;
import be.elevenways.zenit.auth.model.UserModel;
import be.elevenways.zenit.auth.server.ApiKeyService;
import be.elevenways.zenit.auth.server.AuthModels;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.Models;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What the managed-database API says about MEMORY and about a record still in flight --
 * the two facts an operator scripting a migration onto shared engines could not get
 * (2026-09-02: {@code hoh database list} printed an EMPTY memory column for a record on
 * the defaults, and there was no way to block until a background move finished, so the
 * lane was a {@code docker stats} read and a sleep loop).
 *
 * ONE journey, because the claim is that all three records are priced by the SAME
 * derivation the capacity hook books through: a declared ceiling, a defaulted one and a
 * shared one are three answers of one function, and asserting any one alone would prove
 * nothing about the others.
 *
 * AIDEV-NOTE: no daemon is touched. Every record here is priced from its settings and its
 * engine row, which is exactly the point -- the ceiling is a DECLARED number, not a
 * measurement, so it is knowable before anything runs.
 */
class DatabaseCapacityApiTest extends HohenheimTestBase {

    private static final String PREFIX = "dbcap-";

    private static String keyAdmin;
    private static String keyOutsider;

    private static Integer hostId;
    private static Integer engineId;
    private static Integer declaredId;
    private static Integer defaultedId;
    private static Integer sharedId;

    @BeforeAll
    static void seed() {
        hostId = host(PREFIX + "host");
        engineId = engine(PREFIX + "pg", hostId);
        declaredId = database(PREFIX + "declared", hostId, null, 256);
        defaultedId = database(PREFIX + "defaulted", hostId, null, null);
        sharedId = database(PREFIX + "shared", hostId, engineId, null);

        int adminId = AuthModels.users().find()
            .where(UserModel.EMAIL.eq("test@hohenheim.local")).first().get(UserModel.ID);
        keyAdmin = ApiKeyService.create(adminId, PREFIX + "admin", List.of("hohenheim.*"), null)
            .plaintext();

        // A key whose SCOPES claim everything but whose owner holds nothing: scopes narrow
        // authority, they never grant it, so the engine detail must still refuse it.
        int outsiderId = user(PREFIX + "outsider@surface.test", "Capacity API Outsider");
        keyOutsider = ApiKeyService.create(outsiderId, PREFIX + "outsider",
            List.of("hohenheim.*"), null).plaintext();
    }

    @AfterAll
    static void cleanUp() {
        Model databases = Models.get(DatabaseModel.class);
        for (Row row : databases.find().where(DatabaseModel.NAME.startsWith(PREFIX)).all()) {
            databases.delete(row.get(DatabaseModel.ID));
        }
        Model engines = Models.get(DatabaseEngineModel.class);
        for (Row row : engines.find().where(DatabaseEngineModel.NAME.startsWith(PREFIX)).all()) {
            engines.delete(row.get(DatabaseEngineModel.ID));
        }
        Model servers = Models.get(ServerModel.class);
        for (Row row : servers.find().where(ServerModel.NAME.startsWith(PREFIX)).all()) {
            servers.delete(row.get(ServerModel.ID));
        }
    }

    @Test
    void everyRecordReportsTheCeilingItRunsUnderAndHowItGotIt() throws Exception {
        Model databases = Models.get(DatabaseModel.class);
        int postgresDefault = ManagedDatabase.Engine.POSTGRES.footprintMb(false);
        int sharedCeiling = 1024;

        // 1. The list prices all three records. memory_limit_mb alone answered only the
        //    first of them -- the other two columns were empty, which read as "no limit".
        HttpResponse<String> list = keyGet(keyAdmin, "/api/v1/databases");
        assertThat(list.statusCode()).as("step 1: the list answers: " + list.body())
            .isEqualTo(200);
        assertThat(list.body()).as("step 1: a DECLARED ceiling is its own number")
            .contains("\"id\":" + declaredId)
            .contains("\"effective_memory_mb\":256")
            .contains("\"memory_source\":\"" + DatabaseService.MEMORY_SOURCE_DECLARED + "\"");
        assertThat(list.body()).as("step 1: a record on the defaults is priced at the "
            + "engine footprint the booking used, never left blank")
            .contains("\"effective_memory_mb\":" + postgresDefault)
            .contains("\"memory_source\":\"" + DatabaseService.MEMORY_SOURCE_DEFAULT + "\"");
        assertThat(list.body()).as("step 1: and a SHARED record inherits its engine's ceiling")
            .contains("\"effective_memory_mb\":" + sharedCeiling)
            .contains("\"memory_source\":\"" + DatabaseService.MEMORY_SOURCE_ENGINE + "\"");

        // 2. The same numbers through the single-record lane, which is what a watcher polls.
        HttpResponse<String> one = keyGet(keyAdmin, "/api/v1/databases/" + defaultedId);
        assertThat(one.statusCode()).as("step 2: one record answers: " + one.body())
            .isEqualTo(200);
        assertThat(one.body()).as("step 2: with the same ceiling the list quoted")
            .contains("\"effective_memory_mb\":" + postgresDefault)
            .contains("\"name\":\"" + PREFIX + "defaulted\"");
        assertThat(one.body()).as("step 2: and never the stored credentials")
            .doesNotContain("db_password").doesNotContain(PREFIX + "secret-pw");

        // 3. THE WATCHER'S FACT. A record in flight is `pending`, a settled one `ok`, a
        //    failed one `failed` -- read off the status vocabulary's own declaration, so
        //    the CLI's wait loop carries no list of status names.
        Row row = databases.findById(defaultedId);
        row.set(DatabaseModel.STATUS, DatabaseModel.STATUS_PROVISIONING);
        databases.save(row);
        assertThat(keyGet(keyAdmin, "/api/v1/databases/" + defaultedId).body())
            .as("step 3: a provisioning record is pending")
            .contains("\"outcome\":\"" + DatabaseModel.OUTCOME_PENDING + "\"");

        row = databases.findById(defaultedId);
        row.set(DatabaseModel.STATUS, DatabaseModel.STATUS_FAILED);
        row.set(DatabaseModel.FAILURE_REASON, "engine refused the restore");
        databases.save(row);
        HttpResponse<String> failed = keyGet(keyAdmin, "/api/v1/databases/" + defaultedId);
        assertThat(failed.body()).as("step 3: a failed record is failed, WITH the reason")
            .contains("\"outcome\":\"" + DatabaseModel.OUTCOME_FAILED + "\"")
            .contains("engine refused the restore");

        row = databases.findById(defaultedId);
        row.set(DatabaseModel.STATUS, DatabaseModel.STATUS_ACTIVE);
        databases.save(row);
        assertThat(keyGet(keyAdmin, "/api/v1/databases/" + defaultedId).body())
            .as("step 3: and a settled one is ok")
            .contains("\"outcome\":\"" + DatabaseModel.OUTCOME_OK + "\"");

        // 4. The drift gate on that classification: a fifth status must be a DECISION, not
        //    a value that silently reads as "still working".
        for (String status : DatabaseModel.STATUS.getValues().keySet()) {
            assertThat(DatabaseModel.declaresOutcome(status))
                .as("step 4: status '" + status + "' declares no outcome -- add it to "
                    + "DatabaseModel's outcome table")
                .isTrue();
        }

        // 5. The engine detail: the row, the logical databases living on it with their own
        //    ceilings, and the logical USER -- the neighbour list an operator needs before
        //    moving one more record onto it.
        HttpResponse<String> engine = keyGet(keyAdmin, "/api/v1/engines/" + engineId);
        assertThat(engine.statusCode()).as("step 5: the engine answers: " + engine.body())
            .isEqualTo(200);
        assertThat(engine.body()).as("step 5: naming itself and its database")
            .contains("\"name\":\"" + PREFIX + "pg\"")
            .contains("\"logical_databases\":")
            .contains("\"id\":" + sharedId)
            .contains(PREFIX + "shared")
            .contains("\"db_user\":\"appuser\"")
            .contains("\"effective_memory_mb\":" + sharedCeiling);
        assertThat(engine.body()).as("step 5: and never the engine superuser credentials")
            .doesNotContain("root_password").doesNotContain(PREFIX + "root-pw");

        // 6. Its door is the engine LIST's door -- operator-only -- and it is no existence
        //    oracle: an id that never existed answers the uniform 404.
        assertThat(keyGet(keyOutsider, "/api/v1/engines/" + engineId).statusCode())
            .as("step 6: a non-operator key cannot read an engine, whatever its scopes")
            .isEqualTo(403);
        assertThat(keyGet(keyAdmin, "/api/v1/engines/999999").statusCode())
            .as("step 6: and an unknown engine is 404").isEqualTo(404);
    }

    // -- fixtures -------------------------------------------------------------

    private static int user(String email, String name) {
        Row row = AuthModels.users().createEmptyRow();
        row.set(UserModel.EMAIL, email);
        row.set(UserModel.DISPLAY_NAME, name);
        row.set(UserModel.ENABLED, true);
        row.set(UserModel.CREATED_AT, Instant.now());
        row.set(UserModel.UPDATED_AT, Instant.now());
        AuthModels.users().save(row);
        return row.get(UserModel.ID);
    }

    private static int host(String name) {
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
            Map.of("mem_total", 16L * 1024 * 1024 * 1024), true, Instant.now(), null));
        return row.get(ServerModel.ID);
    }

    /** A shared engine ROW declaring a 1024 MB ceiling; no container is ever provisioned. */
    private static int engine(String name, Integer serverId) {
        Model engines = Models.get(DatabaseEngineModel.class);
        Row row = engines.createEmptyRow();
        row.set(DatabaseEngineModel.NAME, name);
        row.set(DatabaseEngineModel.ENGINE, DatabaseModel.ENGINE_POSTGRES);
        row.set(DatabaseEngineModel.IMAGE, "postgres:16");
        row.set(DatabaseEngineModel.SERVER_ID, serverId);
        row.set(DatabaseEngineModel.ROOT_USER, "root");
        row.set(DatabaseEngineModel.ROOT_PASSWORD, PREFIX + "root-pw");
        row.set(DatabaseEngineModel.MEMORY_LIMIT_MB, 1024);
        row.set(DatabaseEngineModel.STATUS, DatabaseModel.STATUS_ACTIVE);
        engines.save(row);
        return row.get(DatabaseEngineModel.ID);
    }

    /** A record: shared when an engine is given, dedicated otherwise; limit null = defaults. */
    private static int database(String name, Integer serverId, Integer engineId, Integer limitMb) {
        Model databases = Models.get(DatabaseModel.class);
        Row row = databases.createEmptyRow();
        row.set(DatabaseModel.NAME, name);
        row.set(DatabaseModel.ENGINE, DatabaseModel.ENGINE_POSTGRES);
        row.set(DatabaseModel.DB_NAME, "appdb");
        row.set(DatabaseModel.DB_USER, "appuser");
        row.set(DatabaseModel.DB_PASSWORD, PREFIX + "secret-pw");
        row.set(DatabaseModel.SERVER_ID, serverId);
        row.set(DatabaseModel.PLACEMENT, engineId == null
            ? DatabaseModel.PLACEMENT_DEDICATED : DatabaseModel.PLACEMENT_SHARED);
        row.set(DatabaseModel.ENGINE_ID, engineId);
        row.set(DatabaseModel.MEMORY_LIMIT_MB, limitMb);
        row.set(DatabaseModel.STATUS, DatabaseModel.STATUS_ACTIVE);
        databases.save(row);
        return row.get(DatabaseModel.ID);
    }
}
