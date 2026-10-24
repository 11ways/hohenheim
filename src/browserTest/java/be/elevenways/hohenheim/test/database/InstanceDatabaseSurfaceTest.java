package be.elevenways.hohenheim.test.database;

import java.util.Objects;
import be.elevenways.hohenheim.test.TenantConduits;
import be.elevenways.hohenheim.test.PanelEntryViews;
import be.elevenways.hohenheim.HohenheimSlugs;
import be.elevenways.zenit.cms.common.panel.PanelRequest;
import be.elevenways.zenit.cms.common.panel.PanelRegistry;
import be.elevenways.zenit.cms.common.panel.Panel;
import be.elevenways.hohenheim.server.cms.CmsSupport;
import be.elevenways.hohenheim.model.DatabaseModel;
import be.elevenways.hohenheim.model.InstanceDatabaseModel;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.server.cms.DatabaseParts;
import be.elevenways.hohenheim.server.cms.InstanceBackupsPage;
import be.elevenways.hohenheim.instance.InstanceAttachmentOperations;
import be.elevenways.hohenheim.instance.InstanceOperations;
import be.elevenways.zenit.cms.common.page.CmsEndpoints;
import be.elevenways.zenit.cms.test.support.PanelResourceCalls;
import be.elevenways.zenit.common.orm.datasource.sql.SqlDatasource;
import be.elevenways.zenit.test.support.DatasourceProbe;
import java.net.http.HttpResponse;
import be.elevenways.hohenheim.server.database.DatabaseEnvInjection;
import be.elevenways.hohenheim.server.database.ManagedDatabase;
import be.elevenways.hohenheim.server.host.HostPreflight;
import be.elevenways.hohenheim.server.instance.InstanceService;
import be.elevenways.hohenheim.server.orm.GeneratedRows;
import be.elevenways.hohenheim.server.runtime.ContainerState;
import be.elevenways.hohenheim.test.HardDeletes;
import be.elevenways.hohenheim.test.HohenheimTestBase;
import be.elevenways.hohenheim.test.host.HostFixtures;
import be.elevenways.protoblast.common.i18n.LocaleChain;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.zenit.common.Zenit;
import be.elevenways.protoblast.common.time.Now;
import be.elevenways.zenit.cms.common.action.ConfirmationSpec;
import be.elevenways.zenit.cms.common.page.CmsRoutes;
import be.elevenways.zenit.cms.server.page.ResourceWrites;
import be.elevenways.zenit.server.operation.OperationPipeline;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.common.validation.Violations;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * The operator-facing surface of a database attachment, end to end: the attachment is
 * titled by BOTH its sides, its delete dialog names them and the consequence, a database
 * a live workload holds is offered DEAD naming the workloads (names only: a reason is a
 * sentence, never a path), the instance's Databases tab EXISTS and lists the attachment, and the
 * attachment's edit form hydrates both relation pickers (F6 + F7, 2026-08-29), and a
 * workload whose database is not ready yet cannot be deployed at all.
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

    /** The second pair, used by the deploy-readiness journey only. */
    private static Integer waitingInstanceId;
    private static Integer waitingDatabaseId;

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
        if (waitingDatabaseId != null) {
            Models.get(InstanceDatabaseModel.class).find()
                .where(InstanceDatabaseModel.DATABASE_ID.eq(waitingDatabaseId)).delete();
        }
        Model instances = Models.get(InstanceModel.class);
        GeneratedRows.sweeping("test", () -> {
            for (Row row : instances.find().withTrashed().where(InstanceModel.NAME.startsWith(PREFIX)).all()) {
                HardDeletes.byId(instances, row.get(InstanceModel.ID));
            }
            for (Row row : instances.find().withTrashed()
                    .where(InstanceModel.GENERATED_FOR_MODEL.eq(DatabaseModel.MODEL_ID.toString()))
                    .all()) {
                Object generatedFor = row.get(InstanceModel.GENERATED_FOR_ID);
                if (databaseId.equals(generatedFor)
                        || (waitingDatabaseId != null && waitingDatabaseId.equals(generatedFor))) {
                    HardDeletes.byId(instances, row.get(InstanceModel.ID));
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
        var attachments = PanelEntryViews.of(HohenheimSlugs.ADMIN, HohenheimSlugs.INSTANCE_DATABASES);
        Panel admin = Objects.requireNonNull(PanelRegistry.getBySlug(HohenheimSlugs.ADMIN), "the admin panel");
        Row link = Models.get(InstanceDatabaseModel.class).findById(linkId);
        Row database = Models.get(DatabaseModel.class).findById(databaseId);
        String tabUrl = CmsRoutes.subpage("admin", "instances", instanceId,
            HohenheimSlugs.Tab.DATABASES).toUrl();

        // 1. The record is titled by both sides, never by its env prefix.
        assertThat(Objects.requireNonNull(attachments.recordTitle(link))
                .resolve(LocaleChain.ofTags("en"), Zenit.getMessageResolver()))
            .as("step 1: the attachment title names the database and the instance")
            .contains(PREFIX + "db")
            .contains(PREFIX + "web")
            .doesNotStartWith("DB");

        // 2. Its delete dialog names both sides and the injected family it takes away.
        ConfirmationSpec confirmation = CmsSupport.rowEntry(admin, HohenheimSlugs.INSTANCE_DATABASES)
            .deleteConfirmation()
            .forRow(link, new PanelRequest(admin, TenantConduits.stubFor(null), AccessContext.anonymous(), null));
        Microcopy body = confirmation.body();
        assertThat(body.key()).as("step 2: the attachment-specific warning").isEqualTo("delete_confirm");
        assertThat(String.valueOf(body.args().get("database"))).isEqualTo(PREFIX + "db");
        assertThat(String.valueOf(body.args().get("instance"))).isEqualTo(PREFIX + "web");
        assertThat(String.valueOf(body.args().get("prefix"))).isEqualTo("DB");

        // 3. The database's delete is OFFERED BUT DEAD while the workload holds it,
        //    naming the workload by name, never a path in the sentence -- the same facts
        //    the submit refuses with, so the dead button is never the gate.
        AccessContext operator = TenantConduits.operator();
        Microcopy reason = deleteUnavailable(database, operator);
        assertThat(reason).as("step 3: the delete is dead with a reason").isNotNull();
        assertThat(reason.key()).isEqualTo("delete_in_use");
        String workloads = String.valueOf(reason.args().get("workloads"));
        assertThat(workloads)
            .as("step 3: the reason names the workload and carries no path")
            .contains(PREFIX + "web")
            .doesNotContain(tabUrl);
        Throwable refused = catchThrowable(() -> ResourceWrites.delete(admin,
            DatabaseParts.admin(), database, operator));
        assertThat(refused).isInstanceOfSatisfying(Violations.class, violations ->
            assertThat(violations.all()).anySatisfy(violation -> {
                assertThat(violation.message().key()).isEqualTo("delete_in_use");
                assertThat(String.valueOf(violation.message().args().get("workloads")))
                    .contains(PREFIX + "web");
            }));

        // 4. The Attached databases tab is the attachment list narrowed to the instance: the database by name,
        //    opening the database itself, its engine by its label, what it does as a badge, and the attach offered
        //    under this instance; the record band of the database renders the dead delete's reason.
        var tab = adminGet(tabUrl);
        assertThat(tab.statusCode()).as("step 4: the Databases tab renders").isEqualTo(200);
        assertThat(tab.body())
            .as("step 4: the tab lists the attached database")
            .contains(PREFIX + "db")
            .as("step 4: the name opens the database, never the attachment form")
            .contains("/admin/databases/" + databaseId)
            .as("step 4: the engine by its label, never the raw token")
            .contains("PostgreSQL")
            .as("step 4: what the database does is a state badge")
            .contains("hh-state-line")
            .as("step 4: the row detaches through the attachment's own delete operation")
            .contains(InstanceAttachmentOperations.DELETE_DATABASE_LINK.id().getPath())
            .as("step 4: its attach opens the attachment create form under this instance")
            .contains("/admin/instance-databases/new?parent=" + instanceId)
            .as("step 4: and its lead sentence stands in the shared record heading")
            .contains("data-cms-tab-lead")
            .contains("Each attached database injects its connection variables");
        var detail = adminGet(CmsRoutes.detail("admin", "databases", databaseId).toUrl());
        assertThat(detail.statusCode()).isEqualTo(200);
        assertThat(detail.body())
            .as("step 4: the database page names the holding workload in its dead-delete reason")
            .contains(PREFIX + "web");

        // 5. The attach form says what it does to which app, and its picker offers only what is not attached yet:
        //    the attached database is refused on submit by the picker's own narrowing.
        String createUrl = "/admin/instance-databases/new?parent=" + instanceId;
        assertThat(adminGet(createUrl).body())
            .as("step 5: the page names the app it attaches to")
            .contains("Attach a database to " + PREFIX + "web")
            .as("step 5: and its submit says Attach").contains("data-cms-form-save");
        HttpResponse<String> again = httpPostForm(createUrl, "database_id=" + databaseId + "&env_prefix=AGAIN&"
            + PanelResourceCalls.createEnvelope(), sessionToken, csrfToken);
        assertThat(again.statusCode()).as("step 5: THE ASSERTION -- an attached database is no offer")
            .isNotIn(302, 303);
        assertThat(Models.get(InstanceDatabaseModel.class).findByInstanceId(instanceId))
            .as("step 5: nothing was attached twice").hasSize(1);

        // 6. Another database attaches, written under the instance the form was opened under whatever is posted, and
        //    lands back on the instance's Attached databases tab.
        int otherInstance = instance(PREFIX + "other", hostId);
        int second = database(PREFIX + "db2", hostId);
        HttpResponse<String> attached = httpPostForm(createUrl, "instance_id=" + otherInstance + "&database_id="
            + second + "&env_prefix=SECOND&" + PanelResourceCalls.createEnvelope(), sessionToken, csrfToken);
        assertThat(attached.statusCode()).as("step 6: the attach is written").isIn(302, 303);
        assertThat(attached.headers().firstValue("location").orElse(""))
            .as("step 6: and lands on the Attached databases tab").contains(tabUrl);
        Row secondLink = Models.get(InstanceDatabaseModel.class).find()
            .where(InstanceDatabaseModel.DATABASE_ID.eq(second)).first();
        assertThat(secondLink).isNotNull();
        assertThat((Object) secondLink.get(InstanceDatabaseModel.INSTANCE_ID))
            .as("step 6: THE ASSERTION -- under the parent, never the posted instance").isEqualTo(instanceId);

        // 7. The Backups tab names each attached database with its last backup, read in one pass: the attachments
        //    and the databases are one read each however many are attached.
        assertThat(adminGet(CmsRoutes.subpage("admin", "instances", instanceId, HohenheimSlugs.Tab.BACKUPS)
                .toUrl()).body())
            .as("step 7: the Backups tab names the attached databases")
            .contains("data-hh-attached-database-backups").contains(PREFIX + "db2")
            .contains("/admin/databases/" + second);
        PanelRequest operatorRequest = new PanelRequest(admin, TenantConduits.stubFor(null),
            TenantConduits.operator(), null);
        DatasourceProbe probe = DatasourceProbe.on((SqlDatasource) Models.get(InstanceDatabaseModel.class)
            .getResolvedDatasource());
        probe.reset();
        assertThat(InstanceBackupsPage.attachedDatabases(operatorRequest, instanceId)).hasSize(2);
        int linksForTwo = probe.queriesAgainst(InstanceDatabaseModel.MODEL_ID);
        int databasesForTwo = probe.queriesAgainst(DatabaseModel.MODEL_ID);
        // The added database is a temporary one: a persistent database's last-backup verdict asks the engine serving
        // it once per record (DatabaseInstances.owned, outside this read), which would hide what this read costs.
        int third = database(PREFIX + "db3", hostId);
        Row thirdDatabase = Models.get(DatabaseModel.class).findById(third);
        thirdDatabase.set(DatabaseModel.EPHEMERAL, true);
        Models.get(DatabaseModel.class).save(thirdDatabase);
        Row thirdLink = Models.get(InstanceDatabaseModel.class).createEmptyRow();
        thirdLink.set(InstanceDatabaseModel.INSTANCE_ID, instanceId);
        thirdLink.set(InstanceDatabaseModel.DATABASE_ID, third);
        thirdLink.set(InstanceDatabaseModel.ENV_PREFIX, "THIRD");
        Models.get(InstanceDatabaseModel.class).save(thirdLink);
        probe.reset();
        assertThat(InstanceBackupsPage.attachedDatabases(operatorRequest, instanceId)).hasSize(3);
        assertThat(probe.queriesAgainst(InstanceDatabaseModel.MODEL_ID))
            .as("step 7: the attachments cost the same however many there are").isEqualTo(linksForTwo);
        assertThat(probe.queriesAgainst(DatabaseModel.MODEL_ID))
            .as("step 7: and so are their databases").isEqualTo(databasesForTwo);

        // 8. THE BROWSER: the attachment's edit form names both sides read-only: an attachment is never re-pointed.
        navigateToApp(CmsRoutes.detail("admin", "instance-databases", linkId).toUrl());
        waitForHydration();
        assertThat(page.locator("pl-field[data-path='instance_id'] .zf-field-readonly").textContent().trim())
            .as("step 8: the Instance is named, frozen to the parent")
            .isEqualTo(PREFIX + "web");
        assertThat(page.locator("pl-field[data-path='database_id'] .zf-field-readonly").textContent().trim())
            .as("step 8: the Database is named, frozen after the attach")
            .isEqualTo(PREFIX + "db");
        assertThat(page.locator("pl-select[name='database_id']").count())
            .as("step 8: no picker to re-point it with").isZero();

        // 9. Detached through the row's Detach (the attachment's own delete), the database delete comes alive again.
        Models.get(InstanceDatabaseModel.class).find().where(InstanceDatabaseModel.ID.in(
            List.of(secondLink.get(InstanceDatabaseModel.ID), thirdLink.get(InstanceDatabaseModel.ID)))).delete();
        HttpResponse<String> detach = httpPostForm(CmsRoutes.invoke(HohenheimSlugs.ADMIN,
                HohenheimSlugs.INSTANCE_DATABASES, InstanceAttachmentOperations.DELETE_DATABASE_LINK.id())
            .with(CmsEndpoints.SUBJECT_PARAM, String.valueOf(linkId)).toUrl(), confirmed(""), sessionToken,
            csrfToken);
        assertThat(detach.statusCode()).as("step 9: the detach answers").isIn(302, 303);
        assertThat(Models.get(InstanceDatabaseModel.class).findById(linkId))
            .as("step 9: the attachment is gone").isNull();
        assertThat(deleteUnavailable(database, operator))
            .as("step 9: no workload holds it, so the delete is available")
            .isNull();
    }

    /**
     * A workload whose attached database is not ready yet cannot be deployed: the Deploy
     * action is offered DEAD with the database and its state, and the POST refuses with
     * the same named reason -- through the state machine provisioning -> failed -> active.
     *
     * AIDEV-NOTE: pins the silent success the WordPress template lane made reachable in
     * one click. Injection FAIL-SOFTS, so a deploy that got through dropped the whole
     * {@code WORDPRESS_DB_*} family, the workload booted without credentials and looked
     * healthy, and the only trace was an attention-panel item.
     */
    @Test
    void deployIsRefusedWhileAnAttachedDatabaseIsNotActive() {
        waitingInstanceId = instance(PREFIX + "wp", hostId);
        waitingDatabaseId = database(PREFIX + "wpdb", hostId, DatabaseModel.STATUS_PROVISIONING);
        Model links = Models.get(InstanceDatabaseModel.class);
        Row link = links.createEmptyRow();
        link.set(InstanceDatabaseModel.INSTANCE_ID, waitingInstanceId);
        link.set(InstanceDatabaseModel.DATABASE_ID, waitingDatabaseId);
        link.set(InstanceDatabaseModel.ENV_PREFIX, "WORDPRESS_DB");
        links.save(link);
        AccessContext viewer = TenantConduits.operator();
        Model databases = Models.get(DatabaseModel.class);

        // 1. Still provisioning: the placed deploy is DEAD before any run, naming the database and its state.
        Microcopy provisioning = deployRefusal(viewer);
        assertThat(provisioning)
            .as("step 1: deploy is offered dead while the database provisions")
            .isNotNull();
        assertThat(provisioning.key()).isEqualTo("database_not_ready");
        assertThat(String.valueOf(provisioning.args().get("name"))).isEqualTo(PREFIX + "wpdb");
        assertThat(String.valueOf(provisioning.args().get("state")))
            .as("step 1: the reason names the state the operator is waiting on")
            .isEqualTo(DatabaseModel.STATUS_PROVISIONING);

        // 2. The dead button is never the gate: the deploy itself refuses, with the same
        //    key, before any daemon work.
        Throwable refused = catchThrowable(() -> new InstanceService().deploy(waitingInstanceId));
        assertThat(violationKeys(refused))
            .as("step 2: the POST refuses with the same named reason")
            .contains("database_not_ready");

        // 3. A FAILED database refuses with its FAILURE REASON instead of its state --
        //    the operator needs the sentence that says why waiting will not help.
        Row database = databases.findById(waitingDatabaseId);
        database.set(DatabaseModel.STATUS, DatabaseModel.STATUS_FAILED);
        database.set(DatabaseModel.FAILURE_REASON, "image pull refused: no such tag");
        databases.save(database);
        Microcopy failed = deployRefusal(viewer);
        assertThat(failed).as("step 3: a failed database is still a dead deploy").isNotNull();
        assertThat(failed.key()).isEqualTo("database_not_ready");
        assertThat(String.valueOf(failed.args().get("reason")))
            .as("step 3: naming why it failed")
            .contains("no such tag");
        assertThat(failed.describe())
            .as("step 3: and the rendered sentence carries it too")
            .contains("no such tag");
        assertThat(violationKeys(catchThrowable(
            () -> new InstanceService().deploy(waitingInstanceId))))
            .as("step 3: the POST refuses a failed database as well")
            .contains("database_not_ready");

        // 4. Active: the gate opens and the injected family is there -- the two halves
        //    the refusal exists to keep together. (The deploy itself is not driven here:
        //    everything past this gate is daemon work the live lane owns.)
        database.set(DatabaseModel.STATUS, DatabaseModel.STATUS_ACTIVE);
        database.set(DatabaseModel.FAILURE_REASON, null);
        databases.save(database);
        assertThat(deployRefusal(viewer))
            .as("step 4: an active database refuses nothing")
            .isNull();
        Map<String, String> env = DatabaseEnvInjection.envForInstance(waitingInstanceId,
            row -> new ManagedDatabase.LiveStatus(ContainerState.RUNNING, 5544));
        assertThat(env)
            .as("step 4: and the workload receives the family the refusal protected")
            .containsEntry("WORDPRESS_DB_NAME", "appdb")
            .containsEntry("WORDPRESS_DB_USER", "appuser")
            .containsEntry("WORDPRESS_DB_PORT", "5432");
    }

    // -- fixtures -------------------------------------------------------------

    /** Why the placed deploy (a background run) refuses the waiting instance before any run starts, or null. */
    private static @Nullable Microcopy deployRefusal(AccessContext viewer) {
        Row instance = Models.get(InstanceModel.class).findById(waitingInstanceId);
        return OperationPipeline.offer(InstanceOperations.DEPLOY, viewer, instance)
            instanceof OperationPipeline.Offer.Unavailable unavailable ? unavailable.reason() : null;
    }

    private static String violationKeys(Throwable thrown) {
        assertThat(thrown).as("a refusal was raised at all").isInstanceOf(Violations.class);
        StringBuilder keys = new StringBuilder();
        for (var violation : ((Violations) thrown).all()) {
            keys.append(violation.message().key()).append(' ');
        }
        return keys.toString();
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
            Map.of("mem_total", 16L * 1024 * 1024 * 1024), true, Now.instant(), null));
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
        return database(name, serverId, DatabaseModel.STATUS_ACTIVE);
    }

    private static int database(String name, Integer serverId, String status) {
        Model databases = Models.get(DatabaseModel.class);
        Row row = databases.createEmptyRow();
        row.set(DatabaseModel.NAME, name);
        row.set(DatabaseModel.ENGINE, "postgres");
        row.set(DatabaseModel.DB_NAME, "appdb");
        row.set(DatabaseModel.DB_USER, "appuser");
        row.set(DatabaseModel.DB_PASSWORD, "s3cr3t-surface-pw");
        row.set(DatabaseModel.SERVER_ID, serverId);
        row.set(DatabaseModel.STATUS, status);
        databases.save(row);
        Integer id = row.get(DatabaseModel.ID);
        EngineHandles.plant(id, name, "postgres", InstanceModel.STATUS_RUNNING);
        return id;
    }

    /** @return the words the database delete is offered dead with, null when it is live */
    private static Microcopy deleteUnavailable(Row database, AccessContext access) {
        OperationPipeline.Offer offer = OperationPipeline.offer(DatabaseParts.DELETE, access, database);
        return offer instanceof OperationPipeline.Offer.Unavailable dead ? dead.reason() : null;
    }
}
