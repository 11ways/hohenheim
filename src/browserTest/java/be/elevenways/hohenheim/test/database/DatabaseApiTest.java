package be.elevenways.hohenheim.test.database;

import be.elevenways.hohenheim.model.DatabaseEngineModel;
import be.elevenways.hohenheim.model.DatabaseModel;
import be.elevenways.hohenheim.model.InstanceDatabaseModel;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.hohenheim.server.host.HostPreflight;
import be.elevenways.hohenheim.server.orm.GeneratedRows;
import be.elevenways.hohenheim.test.HohenheimTestBase;
import be.elevenways.hohenheim.test.host.HostFixtures;
import be.elevenways.zenit.auth.CapabilityScopes;
import be.elevenways.zenit.auth.model.GrantSubjectType;
import be.elevenways.zenit.auth.model.UserModel;
import be.elevenways.zenit.auth.server.ApiKeyService;
import be.elevenways.zenit.auth.server.AuthModels;
import be.elevenways.zenit.auth.server.GrantService;
import be.elevenways.zenit.auth.server.RecordGrants;
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
 * The managed-database automation surface, which did not exist before 2026-09-02: a
 * teardown and the move onto a shared engine were browser-only, so an operator scripting
 * a migration had to drive the panel by hand.
 *
 * ONE journey through the whole lane, because the claim being pinned is that the API and
 * the panel are the SAME doors -- the list is the /manage scope with the /manage columns
 * for a delegate, the move is offered where the row action is and refused by the row
 * action's own declaration, and the delete is dead exactly while the panel's Delete button
 * is dead. Asserting any one of those alone would prove nothing about the others.
 *
 * AIDEV-NOTE: no daemon is touched anywhere here, which is what keeps this out of the
 * live lane. The record is SHARED and its engine owns no instance, so the teardown's
 * {@code dropLogical} observes ABSENT and drops nothing -- exactly as a dedicated record
 * whose engine was never provisioned destroys as "observed absent".
 */
class DatabaseApiTest extends HohenheimTestBase {

    private static final String PREFIX = "dbapi-";

    /** The permission the /manage panel gate demands before a record grant can be walked. */
    private static final String MANAGE_ACCESS = "hohenheim.manage.access";

    private static String keyAdmin;
    private static String keyTenant;

    private static Integer hostId;
    private static Integer engineId;
    private static Integer databaseId;
    private static Integer instanceId;
    private static Integer linkId;

    @BeforeAll
    static void seed() {
        hostId = host(PREFIX + "host");
        engineId = engine(PREFIX + "pg", hostId);
        databaseId = sharedDatabase(PREFIX + "db", hostId, engineId);
        instanceId = instance(PREFIX + "web", hostId);
        Model links = Models.get(InstanceDatabaseModel.class);
        Row link = links.createEmptyRow();
        link.set(InstanceDatabaseModel.INSTANCE_ID, instanceId);
        link.set(InstanceDatabaseModel.DATABASE_ID, databaseId);
        link.set(InstanceDatabaseModel.ENV_PREFIX, "DB");
        links.save(link);
        linkId = link.get(InstanceDatabaseModel.ID);

        int adminId = AuthModels.users().find()
            .where(UserModel.EMAIL.eq("test@hohenheim.local")).first().get(UserModel.ID);
        keyAdmin = ApiKeyService.create(adminId, PREFIX + "admin", List.of("hohenheim.*"), null)
            .plaintext();

        // The delegate: the /manage door plus view AND destroy on this one record, so the
        // 403s below can only be about the ADMIN-only verbs and never about a missing grant.
        int tenantId = user(PREFIX + "tenant@surface.test", "Database API Tenant");
        GrantService.createDirectGrant(GrantSubjectType.USER, tenantId, MANAGE_ACCESS, true);
        RecordGrants.grant(GrantSubjectType.USER, tenantId, DatabaseModel.MODEL_ID, databaseId,
            HohenheimAccess.VIEW, true);
        RecordGrants.grant(GrantSubjectType.USER, tenantId, DatabaseModel.MODEL_ID, databaseId,
            HohenheimAccess.DESTROY, true);
        keyTenant = ApiKeyService.create(tenantId, PREFIX + "tenant",
            List.of(MANAGE_ACCESS,
                CapabilityScopes.format(DatabaseModel.MODEL_ID, HohenheimAccess.VIEW),
                CapabilityScopes.format(DatabaseModel.MODEL_ID, HohenheimAccess.DESTROY)),
            null).plaintext();
    }

    @AfterAll
    static void cleanUp() {
        if (databaseId != null) {
            Models.get(InstanceDatabaseModel.class).find()
                .where(InstanceDatabaseModel.DATABASE_ID.eq(databaseId)).delete();
        }
        Model instances = Models.get(InstanceModel.class);
        GeneratedRows.sweeping("test", () -> {
            for (Row row : instances.find().where(InstanceModel.NAME.startsWith(PREFIX)).all()) {
                instances.delete(row.get(InstanceModel.ID));
            }
        });
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
    void theDatabaseTierIsDrivableWithoutABrowserBehindThePanelsOwnGates() throws Exception {
        Model databases = Models.get(DatabaseModel.class);

        // 1. The list an operator sees: the record with its placement, the engine row it
        //    lives on, its host, and the workload count -- what the admin table renders.
        HttpResponse<String> list = keyGet(keyAdmin, "/api/v1/databases");
        assertThat(list.statusCode()).as("step 1: the list answers: " + list.body())
            .isEqualTo(200);
        assertThat(list.body()).as("step 1: it carries the created record and its placement")
            .contains(PREFIX + "db")
            .contains("\"placement\":\"" + DatabaseModel.PLACEMENT_SHARED + "\"")
            .contains("\"engine_id\":" + engineId)
            .contains("\"server\":\"" + PREFIX + "host\"")
            .contains("\"attached\":1");
        assertThat(list.body()).as("step 1: and never the stored credentials")
            .doesNotContain("db_password").doesNotContain(PREFIX + "secret-pw");

        // 2. A DELEGATE sees its own record with the /manage columns only. The engine a
        //    shared record lives on is another tenant's neighbour list, which is exactly
        //    why the /manage table drops it -- the API must not be the wider door.
        HttpResponse<String> tenantList = keyGet(keyTenant, "/api/v1/databases");
        assertThat(tenantList.statusCode()).as("step 2: the delegate may list: "
            + tenantList.body()).isEqualTo(200);
        assertThat(tenantList.body()).as("step 2: its own record is there")
            .contains(PREFIX + "db")
            .contains("\"placement\":\"" + DatabaseModel.PLACEMENT_SHARED + "\"");
        assertThat(tenantList.body())
            .as("step 2: without the operator columns the /manage table drops")
            .doesNotContain("engine_id").doesNotContain("\"server\"")
            .doesNotContain("memory_limit_mb");

        // 3. ... and the two ADMIN-ONLY verbs are shut for it, because only the admin
        //    panel offers them at all (ManageDatabaseResource drops the move row action,
        //    and there is no delegated engine resource).
        assertThat(keyPost(keyTenant, "/api/v1/databases/" + databaseId + "/move-shared", "")
                .statusCode())
            .as("step 3: a delegate cannot move a record between placements").isEqualTo(403);
        assertThat(keyGet(keyTenant, "/api/v1/engines").statusCode())
            .as("step 3: nor enumerate the shared engines").isEqualTo(403);

        // 4. The engine list, for the operator: the row, its image, its host and how many
        //    databases live on it.
        HttpResponse<String> engines = keyGet(keyAdmin, "/api/v1/engines");
        assertThat(engines.statusCode()).as("step 4: the engine list answers: "
            + engines.body()).isEqualTo(200);
        assertThat(engines.body()).as("step 4: it names the engine and its database count")
            .contains(PREFIX + "pg")
            .contains("\"databases\":1")
            .contains("\"server\":\"" + PREFIX + "host\"");
        assertThat(engines.body()).as("step 4: and never the engine superuser credentials")
            .doesNotContain("root_password").doesNotContain(PREFIX + "root-pw");

        // 5. THE MOVE REFUSAL, by name and synchronously: this record already lives on a
        //    shared engine, which is the first thing DatabaseService.moveRefusal says --
        //    the very declaration the row action's visibility reads. A background lane
        //    that refused instead would be invisible to a script.
        HttpResponse<String> move = keyPost(keyAdmin,
            "/api/v1/databases/" + databaseId + "/move-shared", "");
        assertThat(move.statusCode()).as("step 5: an ineligible move is a typed refusal: "
            + move.body()).isEqualTo(422);
        assertThat(move.body()).as("step 5: naming which of the four reasons it is")
            .contains("database_already_shared");
        assertThat((String) databases.findById(databaseId).get(DatabaseModel.STATUS))
            .as("step 5: and nothing was queued -- the record never went provisioning")
            .isEqualTo(DatabaseModel.STATUS_ACTIVE);

        // 6. THE DELETE, dead while a workload holds the credentials: the reason the
        //    panel's Delete button renders, answered to a POST, so the dead button is
        //    never the gate.
        HttpResponse<String> held = keyPost(keyAdmin,
            "/api/v1/databases/" + databaseId + "/delete", "");
        assertThat(held.statusCode()).as("step 6: refused while attached: " + held.body())
            .isEqualTo(422);
        assertThat(held.body()).as("step 6: with the panel's own in-use reason, naming the workload")
            .contains("delete_in_use").contains(PREFIX + "web");
        assertThat(databases.findById(databaseId))
            .as("step 6: and the record survives a refused delete").isNotNull();

        // 7. Detached, the same call succeeds and the record is gone for real.
        Models.get(InstanceDatabaseModel.class).find()
            .where(InstanceDatabaseModel.ID.eq(linkId)).delete();
        HttpResponse<String> deleted = keyPost(keyAdmin,
            "/api/v1/databases/" + databaseId + "/delete", "");
        assertThat(deleted.statusCode()).as("step 7: the detached record deletes: "
            + deleted.body()).isEqualTo(200);
        assertThat(deleted.body()).contains("\"status\":\"deleted\"").contains(PREFIX + "db");
        assertThat(databases.findById(databaseId))
            .as("step 7: the row is gone, not merely reported gone").isNull();

        // 8. And the surface is no existence oracle: a deleted id and an id that never
        //    existed answer the same 404, exactly as the instance lane does.
        assertThat(keyPost(keyAdmin, "/api/v1/databases/" + databaseId + "/delete", "")
                .statusCode())
            .as("step 8: a deleted record is 404").isEqualTo(404);
        assertThat(keyPost(keyAdmin, "/api/v1/databases/999999/move-shared", "").statusCode())
            .as("step 8: and so is an id that never existed").isEqualTo(404);
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

    /** A shared engine ROW with no owned instance: the tier exists, no container does. */
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

    private static int sharedDatabase(String name, Integer serverId, Integer engineId) {
        Model databases = Models.get(DatabaseModel.class);
        Row row = databases.createEmptyRow();
        row.set(DatabaseModel.NAME, name);
        row.set(DatabaseModel.ENGINE, DatabaseModel.ENGINE_POSTGRES);
        row.set(DatabaseModel.DB_NAME, "appdb");
        row.set(DatabaseModel.DB_USER, "appuser");
        row.set(DatabaseModel.DB_PASSWORD, PREFIX + "secret-pw");
        row.set(DatabaseModel.SERVER_ID, serverId);
        row.set(DatabaseModel.PLACEMENT, DatabaseModel.PLACEMENT_SHARED);
        row.set(DatabaseModel.ENGINE_ID, engineId);
        row.set(DatabaseModel.STATUS, DatabaseModel.STATUS_ACTIVE);
        databases.save(row);
        return row.get(DatabaseModel.ID);
    }

    private static int instance(String name, Integer serverId) {
        Model instances = Models.get(InstanceModel.class);
        Row row = instances.createEmptyRow();
        row.set(InstanceModel.NAME, name);
        row.set(InstanceModel.KIND, "hohenheim:docker_container");
        row.set(InstanceModel.SERVER_ID, serverId);
        row.set(InstanceModel.SETTINGS, Map.of("image", "alpine", "tag", "latest"));
        row.set(InstanceModel.STATUS, InstanceModel.STATUS_STOPPED);
        instances.save(row);
        return row.get(InstanceModel.ID);
    }
}
