package be.elevenways.hohenheim.test.database;

import be.elevenways.hohenheim.model.DatabaseModel;
import be.elevenways.hohenheim.model.InstanceDatabaseModel;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.server.cms.DatabaseResource;
import be.elevenways.hohenheim.server.cms.InstanceDatabaseResource;
import be.elevenways.hohenheim.server.cms.InstanceDatabasesPage;
import be.elevenways.hohenheim.server.host.HostPreflight;
import be.elevenways.hohenheim.server.orm.GeneratedRows;
import be.elevenways.hohenheim.test.HohenheimTestBase;
import be.elevenways.hohenheim.test.host.HostFixtures;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.zenit.cms.common.action.ConfirmationSpec;
import be.elevenways.zenit.cms.common.page.CmsRoutes;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.common.validation.Violations;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * The operator-facing surface of a database attachment, end to end: the attachment is
 * titled by BOTH its sides, its delete dialog names them and the consequence, a database
 * a live workload holds is offered DEAD with the workloads and the page a detach happens
 * on, that page (the instance's Databases tab) EXISTS and lists the attachment, and the
 * attachment's edit form hydrates both relation pickers (F6 + F7, 2026-08-29).
 *
 * AIDEV-NOTE: the edit-form assertion is a BROWSER one on purpose. The QA pass saw the
 * Database combobox render empty while the Instance one kept its value, and nothing
 * below the rendered widget can see that: the server-side hydration, the DRY payload and
 * the client re-render each have their own way of losing the chosen item.
 */
class InstanceDatabaseSurfaceTest extends HohenheimTestBase {

    private static final String PREFIX = "dbsurface-";

    private static Integer hostId;
    private static Integer instanceId;
    private static Integer databaseId;
    private static Integer linkId;

    @BeforeAll
    static void seed() {
        hostId = host(PREFIX + "host");
        instanceId = instance(PREFIX + "web", hostId);
        databaseId = database(PREFIX + "db", hostId);
        Model links = Models.get(InstanceDatabaseModel.class);
        Row link = links.createEmptyRow();
        link.set(InstanceDatabaseModel.INSTANCE_ID, instanceId);
        link.set(InstanceDatabaseModel.DATABASE_ID, databaseId);
        link.set(InstanceDatabaseModel.ENV_PREFIX, "DB");
        links.save(link);
        linkId = link.get(InstanceDatabaseModel.ID);
    }

    @AfterAll
    static void cleanUp() {
        Models.get(InstanceDatabaseModel.class).find()
            .where(InstanceDatabaseModel.DATABASE_ID.eq(databaseId)).delete();
        Model instances = Models.get(InstanceModel.class);
        GeneratedRows.sweeping("test", () -> {
            for (Row row : instances.find().where(InstanceModel.NAME.startsWith(PREFIX)).all()) {
                instances.delete(row.get(InstanceModel.ID));
            }
            for (Row row : instances.find()
                    .where(InstanceModel.GENERATED_FOR_MODEL.eq(DatabaseModel.MODEL_ID.toString()))
                    .all()) {
                if (databaseId.equals(row.get(InstanceModel.GENERATED_FOR_ID))) {
                    instances.delete(row.get(InstanceModel.ID));
                }
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
    void anAttachmentIsNamedByBothSidesAndTheDatabaseDeleteIsDeadWithTheDetachPage()
            throws Exception {
        InstanceDatabaseResource attachments = new InstanceDatabaseResource();
        DatabaseResource databases = new DatabaseResource();
        Row link = Models.get(InstanceDatabaseModel.class).findById(linkId);
        Row database = Models.get(DatabaseModel.class).findById(databaseId);
        String tabUrl = CmsRoutes.subpage("admin", "instances", instanceId,
            InstanceDatabasesPage.SLUG).toUrl();

        // 1. The record is titled by both sides, never by its env prefix.
        assertThat(attachments.recordTitle(link))
            .as("step 1: the attachment title names the database and the instance")
            .contains(PREFIX + "db")
            .contains(PREFIX + "web")
            .doesNotStartWith("DB");

        // 2. Its delete dialog names both sides and the injected family it takes away.
        ConfirmationSpec confirmation = attachments.deleteConfirmationFor(link);
        Microcopy body = confirmation.body();
        assertThat(body.key()).as("step 2: the attachment-specific warning").isEqualTo("delete_confirm");
        assertThat(String.valueOf(body.args().get("database"))).isEqualTo(PREFIX + "db");
        assertThat(String.valueOf(body.args().get("instance"))).isEqualTo(PREFIX + "web");
        assertThat(String.valueOf(body.args().get("prefix"))).isEqualTo("DB");

        // 3. The database's delete is OFFERED BUT DEAD while the workload holds it,
        //    naming the workload and the page it is detached on -- the same facts the
        //    submit refuses with, so the dead button is never the gate.
        Microcopy reason = databases.deleteUnavailableReason(database, AccessContext.anonymous());
        assertThat(reason).as("step 3: the delete is dead with a reason").isNotNull();
        assertThat(reason.key()).isEqualTo("delete_in_use");
        String workloads = String.valueOf(reason.args().get("workloads"));
        assertThat(workloads)
            .as("step 3: the reason names the workload AND the detach page")
            .contains(PREFIX + "web")
            .contains(tabUrl);
        Throwable refused = catchThrowable(() ->
            databases.deleteRow(database, AccessContext.anonymous()));
        assertThat(refused).isInstanceOfSatisfying(Violations.class, violations ->
            assertThat(violations.all()).anySatisfy(violation -> {
                assertThat(violation.message().key()).isEqualTo("database_in_use");
                assertThat(String.valueOf(violation.message().args().get("workloads")))
                    .contains(tabUrl);
            }));

        // 4. That page EXISTS on the instance record and lists the attachment; the
        //    record band of the database renders the dead delete's reason.
        var tab = adminGet(tabUrl);
        assertThat(tab.statusCode()).as("step 4: the Databases tab renders").isEqualTo(200);
        assertThat(tab.body())
            .as("step 4: the tab lists the attached database")
            .contains(PREFIX + "db");
        var detail = adminGet(CmsRoutes.detail("admin", "databases", databaseId).toUrl());
        assertThat(detail.statusCode()).isEqualTo(200);
        assertThat(detail.body())
            .as("step 4: the database page carries the detach page in its dead-delete reason")
            .contains(tabUrl);

        // 5. THE BROWSER: the attachment's edit form hydrates BOTH relation pickers
        //    with their chosen titles (the Database one rendered empty in the QA pass).
        navigateToApp(CmsRoutes.detail("admin", "instance-databases", linkId).toUrl());
        waitForHydration();
        assertThat(page.locator("pl-select[name='instance_id'] .pl-select-value")
            .textContent().trim())
            .as("step 5: the Instance picker shows its chosen record")
            .isEqualTo(PREFIX + "web");
        assertThat(page.locator("pl-select[name='database_id'] .pl-select-value")
            .textContent().trim())
            .as("step 5: the Database picker shows its chosen record")
            .isEqualTo(PREFIX + "db");

        // 6. Detached, the database delete comes alive again.
        Models.get(InstanceDatabaseModel.class).delete(linkId);
        assertThat(databases.deleteUnavailableReason(database, AccessContext.anonymous()))
            .as("step 6: no workload holds it, so the delete is available")
            .isNull();
    }

    // -- fixtures -------------------------------------------------------------

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

    private static int database(String name, Integer serverId) {
        Model databases = Models.get(DatabaseModel.class);
        Row row = databases.createEmptyRow();
        row.set(DatabaseModel.NAME, name);
        row.set(DatabaseModel.ENGINE, "postgres");
        row.set(DatabaseModel.DB_NAME, "appdb");
        row.set(DatabaseModel.DB_USER, "appuser");
        row.set(DatabaseModel.DB_PASSWORD, "s3cr3t-surface-pw");
        row.set(DatabaseModel.SERVER_ID, serverId);
        row.set(DatabaseModel.STATUS, DatabaseModel.STATUS_ACTIVE);
        databases.save(row);
        Integer id = row.get(DatabaseModel.ID);
        EngineHandles.plant(id, name, "postgres", InstanceModel.STATUS_RUNNING);
        return id;
    }
}
