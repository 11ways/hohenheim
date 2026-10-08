package be.elevenways.hohenheim.test.database;

import be.elevenways.hohenheim.HohenheimSlugs;
import be.elevenways.hohenheim.model.DatabaseEngineModel;
import be.elevenways.hohenheim.model.DatabaseModel;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.server.cms.WithheldFailure;
import be.elevenways.hohenheim.server.cms.DatabaseParts;
import be.elevenways.hohenheim.server.database.DatabaseInstances;
import be.elevenways.hohenheim.server.host.HostPreflight;
import be.elevenways.hohenheim.server.orm.GeneratedRows;
import be.elevenways.hohenheim.test.HohenheimTestBase;
import be.elevenways.hohenheim.test.ApiSupport;
import be.elevenways.hohenheim.test.TenantConduits;
import be.elevenways.zenit.auth.model.UserModel;
import be.elevenways.zenit.auth.model.UserPrincipal;
import be.elevenways.zenit.cms.common.resource.PanelResource;
import be.elevenways.zenit.cms.server.panel.PartsWrites;
import be.elevenways.zenit.common.conduit.Conduit;
import be.elevenways.zenit.common.conduit.ConduitAttributes;
import be.elevenways.zenit.common.security.ExecutionIdentity;
import be.elevenways.hohenheim.test.InstanceRowCleanup;
import be.elevenways.hohenheim.test.QueryConduits;
import be.elevenways.hohenheim.test.host.HostFixtures;
import be.elevenways.protoblast.common.time.Now;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.routing.RouteScope;
import be.elevenways.zenit.common.security.AccessContext;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A write that does not CARRY a resource ceiling leaves that ceiling alone, on a dedicated
 * database and on a shared engine alike.
 *
 * The defect this pins: both resize lanes read an absent {@code memory_limit_mb} off the
 * partial coerced map as null, which is "no ceiling" -- so a one-field write (the inline
 * cell lane, a reduced spec) uncapped the container AND recreated it, dropping every live
 * connection for a change nobody asked for.
 */
class DatabaseLimitPartialWriteTest extends HohenheimTestBase {

    private static final String PREFIX = "dblimitpw-";

    private static Integer hostId;
    private static Integer databaseId;
    private static Integer engineId;

    @BeforeAll
    static void seed() {
        hostId = host(PREFIX + "host", 16L * 1024 * 1024 * 1024);
        databaseId = database(PREFIX + "db", hostId);
        engineId = engine(PREFIX + "engine");
    }

    @AfterAll
    static void cleanUp() {
        Model instances = Models.get(InstanceModel.class);
        GeneratedRows.sweeping("test", () -> {
            for (Row row : instances.find().where(InstanceModel.NAME.startsWith("db-" + PREFIX)).all()) {
                InstanceRowCleanup.delete(row.get(InstanceModel.ID));
            }
        });
        Model databases = Models.get(DatabaseModel.class);
        for (Row row : databases.find().where(DatabaseModel.NAME.startsWith(PREFIX)).all()) {
            databases.delete(row.get(DatabaseModel.ID));
        }
        Models.get(DatabaseEngineModel.class).find()
            .where(DatabaseEngineModel.NAME.startsWith(PREFIX)).delete();
        Model servers = Models.get(ServerModel.class);
        for (Row row : servers.find().where(ServerModel.NAME.startsWith(PREFIX)).all()) {
            servers.delete(row.get(ServerModel.ID));
        }
    }

    @Test
    void aWriteThatCarriesNoCeilingNeverUncapsOrRecreatesTheContainer() {
        PanelResource<Row> databases = DatabaseParts.admin();
        Model model = Models.get(DatabaseModel.class);
        Object engineSettingsBefore = engineSettings(databaseId);

        // 1. A write carrying NOTHING (every entry stripped, a reduced spec) is a no-op: the
        //    stored 1024 MB / 1.5 CPU ceiling stays and no recreate is scheduled.
        PartsWrites.updateRow(databases, model.findById(databaseId), Map.of(), AccessContext.anonymous());
        Row afterEmpty = model.findById(databaseId);
        assertThat((Integer) afterEmpty.get(DatabaseModel.MEMORY_LIMIT_MB))
            .as("step 1: an empty write keeps the memory ceiling").isEqualTo(1024);
        assertThat((String) afterEmpty.get(DatabaseModel.STATUS))
            .as("step 1: and never recreates the engine").isEqualTo(DatabaseModel.STATUS_ACTIVE);

        // 2. A write carrying ONLY the unchanged cpu ceiling (the inline cell lane's one entry)
        //    is still a no-op. Before the fix the absent memory read as null != 1024, which
        //    booked an uncapped engine and recreated it.
        PartsWrites.updateRow(databases, model.findById(databaseId), Map.of("cpu_limit", 1.5),
            AccessContext.anonymous());
        Row afterCpu = model.findById(databaseId);
        assertThat((Integer) afterCpu.get(DatabaseModel.MEMORY_LIMIT_MB))
            .as("step 2: a cpu-only write keeps the memory ceiling").isEqualTo(1024);
        assertThat((String) afterCpu.get(DatabaseModel.STATUS))
            .as("step 2: and schedules no recreate").isEqualTo(DatabaseModel.STATUS_ACTIVE);
        assertThat(engineSettings(databaseId))
            .as("step 2: the engine instance is untouched").isEqualTo(engineSettingsBefore);

        // 3. A write carrying ONLY a new memory ceiling resizes memory and keeps the cpu one.
        PartsWrites.updateRow(databases, model.findById(databaseId), Map.of("memory_limit_mb", 2048),
            AccessContext.anonymous());
        Row resized = model.findById(databaseId);
        assertThat((Integer) resized.get(DatabaseModel.MEMORY_LIMIT_MB))
            .as("step 3: the carried ceiling lands").isEqualTo(2048);
        assertThat((Double) resized.get(DatabaseModel.CPU_LIMIT))
            .as("step 3: the ceiling the write did not carry is kept").isEqualTo(1.5);

        // 4. A shared engine follows the same lane: an empty write and an unchanged
        //    one-entry write leave its ceilings and status alone.
        PanelResource<Row> engines = DatabaseParts.engines();
        Model engineModel = Models.get(DatabaseEngineModel.class);
        PartsWrites.updateRow(engines, engineModel.findById(engineId), Map.of(), AccessContext.anonymous());
        PartsWrites.updateRow(engines, engineModel.findById(engineId), Map.of("cpu_limit", 2.0),
            AccessContext.anonymous());
        Row engine = engineModel.findById(engineId);
        assertThat((Integer) engine.get(DatabaseEngineModel.MEMORY_LIMIT_MB))
            .as("step 4: the engine keeps its memory ceiling").isEqualTo(3072);
        assertThat((String) engine.get(DatabaseEngineModel.STATUS))
            .as("step 4: and is never recreated for it").isEqualTo(DatabaseModel.STATUS_ACTIVE);
    }

    @Test
    void aDaemonsOwnFailureTextReachesTheOperatorButNeverATenantJourney() {
        IOException failure = new IOException("dial unix /var/run/docker.sock: connection refused");
        UserPrincipal admin = adminPrincipal();
        UserPrincipal tenant = new UserPrincipal(ApiSupport.user(PREFIX + "tenant@hohenheim.local", "Limit Tenant"),
            "Limit Tenant");

        // 1. Declared system work (a task, the operator harness) is the operator lane.
        assertThat(ExecutionIdentity.supplyAsSystem("withheld-failure-test",
                () -> WithheldFailure.operatorDetail(failure)))
            .as("step 1: declared system work keeps the daemon's text").isEqualTo(failure.getMessage());

        // 2. Work with no identity at all is never the operator lane: "no request" discloses nothing.
        ExecutionIdentity.run(null, () -> assertThat(WithheldFailure.operatorDetail(failure))
            .as("step 2: no identity gets no daemon text").isNull());

        // 3. Under /admin the authenticated operator reads the reason.
        Conduit adminPanel = QueryConduits.request(HohenheimSlugs.ADMIN, Map.of());
        adminPanel.setAttribute(ConduitAttributes.PRINCIPAL, admin);
        assertThat(RouteScope.supply(adminPanel, () -> WithheldFailure.operatorDetail(failure)))
            .as("step 3: /admin shows the operator the daemon's text").isEqualTo(failure.getMessage());

        // 4. Under /manage the socket path never reaches the reader, the operator included: the surface decides.
        Conduit managePanel = QueryConduits.request(HohenheimSlugs.MANAGE, Map.of());
        managePanel.setAttribute(ConduitAttributes.PRINCIPAL, admin);
        assertThat(RouteScope.supply(managePanel, () -> WithheldFailure.operatorDetail(failure)))
            .as("step 4: /manage gets no daemon text at all").isNull();

        // 5. Off the panels (the API), a tenant's request is not the operator lane; an operator's is.
        String[] offPanel = new String[2];
        TenantConduits.as(tenant, () -> offPanel[0] = WithheldFailure.operatorDetail(failure));
        TenantConduits.as(admin, () -> offPanel[1] = WithheldFailure.operatorDetail(failure));
        assertThat(offPanel[0]).as("step 5: a tenant's API request gets no daemon text").isNull();
        assertThat(offPanel[1]).as("step 5: an operator's API request does").isEqualTo(failure.getMessage());
    }

    private static UserPrincipal adminPrincipal() {
        Row admin = Models.get(UserModel.class).find().where(UserModel.EMAIL.eq("test@hohenheim.local")).first();
        return new UserPrincipal(admin.get(UserModel.ID), "Test Admin");
    }

    private static Object engineSettings(int databaseId) {
        Row engine = DatabaseInstances.owned(databaseId);
        return engine == null ? null : String.valueOf(engine.get(InstanceModel.SETTINGS));
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
            Map.of("mem_total", memTotalBytes), true, Now.instant(), null));
        return row.get(ServerModel.ID);
    }

    private static int database(String name, Integer serverId) {
        Model databases = Models.get(DatabaseModel.class);
        Row row = databases.createEmptyRow();
        row.set(DatabaseModel.NAME, name);
        row.set(DatabaseModel.ENGINE, "postgres");
        row.set(DatabaseModel.DB_NAME, "appdb");
        row.set(DatabaseModel.DB_USER, "appuser");
        row.set(DatabaseModel.DB_PASSWORD, "s3cr3t-partial-pw");
        row.set(DatabaseModel.SERVER_ID, serverId);
        row.set(DatabaseModel.MEMORY_LIMIT_MB, 1024);
        row.set(DatabaseModel.CPU_LIMIT, 1.5);
        row.set(DatabaseModel.STATUS, DatabaseModel.STATUS_ACTIVE);
        databases.save(row);
        Integer id = row.get(DatabaseModel.ID);
        EngineHandles.plant(id, name, "postgres", InstanceModel.STATUS_RUNNING);
        return id;
    }

    private static int engine(String name) {
        Model engines = Models.get(DatabaseEngineModel.class);
        Row row = engines.createEmptyRow();
        row.set(DatabaseEngineModel.NAME, name);
        row.set(DatabaseEngineModel.ENGINE, "mysql");
        row.set(DatabaseEngineModel.ROOT_USER, "root");
        row.set(DatabaseEngineModel.ROOT_PASSWORD, "rootsecret");
        row.set(DatabaseEngineModel.SERVER_ID, ServerModel.localServerId());
        row.set(DatabaseEngineModel.MEMORY_LIMIT_MB, 3072);
        row.set(DatabaseEngineModel.CPU_LIMIT, 2.0);
        row.set(DatabaseEngineModel.STATUS, DatabaseModel.STATUS_ACTIVE);
        engines.save(row);
        return row.get(DatabaseEngineModel.ID);
    }
}
