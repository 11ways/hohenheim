package be.elevenways.hohenheim.test.instance;

import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.InstanceVariableModel;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.hohenheim.test.ApiSupport;
import be.elevenways.hohenheim.test.ApiWire;
import be.elevenways.hohenheim.test.ApiWire.Caller;
import be.elevenways.hohenheim.test.ApiWire.PerRun;
import be.elevenways.hohenheim.test.HohenheimTestBase;
import be.elevenways.zenit.auth.CapabilityScopes;
import be.elevenways.zenit.auth.model.GrantSubjectType;
import be.elevenways.zenit.auth.server.ApiKeyService;
import be.elevenways.zenit.auth.server.RecordGrants;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static be.elevenways.hohenheim.test.ApiSupport.form;

/**
 * The instance routes of {@code /api/v1} -- reads, power, console, snapshot, backup, create, delete, logs,
 * variables and devices: every route's success reply and its main refusal, compared byte for byte to the
 * java-rewrite capture through {@link ApiWire}.
 *
 * AIDEV-NOTE: daemon-free. The workloads run on the backup lane's fake native kind and the device lane's
 * {@link FakeDeviceDaemon}, on the private database {@link BackupLaneFixture} installs, so every id is the same on
 * every run. The one per-run value is the created instance's {@code created_at}, which the create stamps from the
 * clock.
 *
 * @author Jelle De Loecker
 * @since  0.9.0
 */
class ApiWireInstancesTest extends HohenheimTestBase {

    private static final Instant T0 = Instant.parse("2026-01-02T03:04:05Z");

    private static Caller admin;
    private static Caller tenant;
    private static Caller viewer;
    private static Caller tenantSession;

    private static int instanceId;
    private static int foreignId;
    private static int devicesId;
    private static int disposableId;
    private static int dockerHostId;

    @BeforeAll
    static void seed() throws Exception {
        BackupLaneFixture fixture = BackupLaneFixture.install();
        FakeDeviceDaemon.Kind.register();

        int operatorId = ApiWire.operatorId();
        int tenantId = ApiSupport.user("wire-instances-tenant@surface.test", "Wire Instances Tenant");
        int viewerId = ApiSupport.user("wire-instances-viewer@surface.test", "Wire Instances Viewer");
        dockerHostId = sshDockerHost("wire-docker-host");

        instanceId = instance("wire-instance", FakeNativeDaemons.FakeNativeKind.ID.toString(), fixture.hostId);
        Row row = Models.get(InstanceModel.class).findById(instanceId);
        row.set(InstanceModel.BACKUP_TARGET_ID, fixture.targetId);
        Models.get(InstanceModel.class).save(row);
        foreignId = instance("wire-foreign", FakeNativeDaemons.FakeNativeKind.ID.toString(), fixture.hostId);
        devicesId = instance("wire-devices", FakeDeviceDaemon.Kind.ID.toString(), fixture.hostId);
        FakeDeviceDaemon.DAEMON.put(FakeDeviceDaemon.handleOf(devicesId), new FakeDeviceDaemon.Workload());
        disposableId = instance("wire-disposable", FakeNativeDaemons.FakeNativeKind.ID.toString(), fixture.hostId);

        for (String capability : List.of(HohenheimAccess.MANAGE, HohenheimAccess.SNAPSHOTS, HohenheimAccess.BACKUPS)) {
            RecordGrants.grant(GrantSubjectType.USER, tenantId, InstanceModel.MODEL_ID, instanceId, capability, true);
        }
        RecordGrants.grant(GrantSubjectType.USER, tenantId, InstanceModel.MODEL_ID, devicesId,
            HohenheimAccess.MANAGE, true);
        RecordGrants.grant(GrantSubjectType.USER, viewerId, InstanceModel.MODEL_ID, instanceId,
            HohenheimAccess.VIEW, true);
        Row seeded = Models.get(InstanceVariableModel.class).createEmptyRow();
        seeded.set(InstanceVariableModel.INSTANCE_ID, instanceId);
        seeded.set(InstanceVariableModel.KEY, "WIRE_SEEDED");
        seeded.set(InstanceVariableModel.KIND, InstanceVariableModel.KIND_PLAIN);
        seeded.set(InstanceVariableModel.PLAIN_VALUE, "seeded-value");
        Models.get(InstanceVariableModel.class).save(seeded);
        FakeNativeDaemons.TAILS.put(FakeNativeDaemons.handleOf(instanceId), "wire log line 1\nwire log line 2\n");

        admin = new Caller.Key(ApiKeyService.create(operatorId, "wire-instances-admin", List.of("hohenheim.*"), null)
            .plaintext());
        tenant = new Caller.Key(ApiKeyService.create(tenantId, "wire-instances-tenant", List.of(
            CapabilityScopes.format(InstanceModel.MODEL_ID, HohenheimAccess.MANAGE),
            CapabilityScopes.format(InstanceModel.MODEL_ID, HohenheimAccess.SNAPSHOTS),
            CapabilityScopes.format(InstanceModel.MODEL_ID, HohenheimAccess.BACKUPS)), null).plaintext());
        // The viewer's key carries every scope the tenant's does: its owner's VIEW grant is what refuses it.
        viewer = new Caller.Key(ApiKeyService.create(viewerId, "wire-instances-viewer", List.of(
            CapabilityScopes.format(InstanceModel.MODEL_ID, HohenheimAccess.MANAGE),
            CapabilityScopes.format(InstanceModel.MODEL_ID, HohenheimAccess.SNAPSHOTS),
            CapabilityScopes.format(InstanceModel.MODEL_ID, HohenheimAccess.BACKUPS)), null).plaintext());
        tenantSession = new Caller.Session(sessionCookieHeader(sessionFor(tenantId).token()));
    }

    @AfterAll
    static void tearDown() throws Exception {
        FakeNativeDaemons.resetStreams();
        FakeDeviceDaemon.DAEMON.remove(FakeDeviceDaemon.handleOf(devicesId));
        BackupLaneFixture.uninstall();
        // Later classes of this JVM find the seeded shared database they expect.
        freshSeededDatabase();
    }

    @Test
    void theInstanceRoutesAnswerWhatJavaRewriteAnswered() {
        ApiWire wire = new ApiWire("instances", this::requestTo, HohenheimTestBase::sendRequestBytes);
        String own = "/api/v1/instances/" + instanceId;
        String foreign = "/api/v1/instances/" + foreignId;
        String devices = "/api/v1/instances/" + devicesId + "/devices";

        // 1. The reads: the inventory and one record, refused to a session and for a record the key cannot see.
        wire.get("list instances", tenant, "/api/v1/instances");
        wire.get("list instances as a browser session", tenantSession, "/api/v1/instances");
        wire.get("read an instance", tenant, own);
        wire.get("read an invisible instance", tenant, foreign);
        wire.get("read the console tail", tenant, own + "/logs?lines=50");
        wire.get("read an invisible instance's console tail", tenant, foreign + "/logs");

        // 2. Variables: list, set and delete, each with its refusal.
        wire.get("list variables", tenant, own + "/variables");
        wire.get("list an invisible instance's variables", tenant, foreign + "/variables");
        wire.post("set a variable", tenant, own + "/variables", form("key", "WIRE_PLAIN", "value", "plain-value"));
        wire.post("set a variable of an unknown kind", tenant, own + "/variables",
            form("key", "WIRE_ODD", "kind", "mystery", "value", "v"));
        wire.post("delete a variable", tenant, own + "/variables/delete", form("key", "WIRE_PLAIN"));
        wire.post("delete an absent variable", tenant, own + "/variables/delete", form("key", "WIRE_PLAIN"));

        // 3. Power, console, snapshot and backup: each verb succeeds for its holder and refuses its own way.
        wire.post("start", tenant, own + "/power", form("action", "start"));
        wire.post("an unknown power action", tenant, own + "/power", form("action", "selfdestruct"));
        wire.post("send a console command", tenant, own + "/command", form("command", "say hello"));
        wire.post("send an empty console command", tenant, own + "/command", form("command", ""));
        wire.post("take a snapshot", tenant, own + "/snapshot", form("note", "wire"));
        wire.post("take a snapshot with a view grant", viewer, own + "/snapshot", form("note", "wire"));
        wire.post("take a backup", tenant, own + "/backup", "");
        wire.post("take a backup with a view grant", viewer, own + "/backup", "");

        // 4. Devices: list, attach, resize and detach, each refused for a type or a device that is not there.
        wire.get("list devices", tenant, devices);
        wire.get("list an invisible instance's devices", tenant, foreign + "/devices");
        wire.post("attach a disk", tenant, devices, form("type", "disk", "name", "wire-data", "size_gb", "2"));
        wire.post("attach a device of an unknown type", tenant, devices, form("type", "floppy", "name", "wire-odd"));
        wire.post("resize a disk", tenant, devices + "/resize", form("name", "wire-data", "size_gb", "4"));
        wire.post("resize an absent disk", tenant, devices + "/resize", form("name", "wire-none", "size_gb", "4"));
        wire.post("detach a disk", tenant, devices + "/detach", form("name", "wire-data"));
        wire.post("detach an absent device", tenant, devices + "/detach", form("name", "wire-none"));

        // 5. Create through the admin form's pipeline, refused for an unknown template; delete, refused to a viewer.
        wire.post("create an instance", admin, "/api/v1/instances", form(
            "name", "wire-created", "kind", "hohenheim:application", "server_id", String.valueOf(dockerHostId),
            "settings.repository_url", "https://example.test/wire-created.git",
            "settings.branch", "main", "settings.container_port", "3000")).perRun(PerRun.CREATED_AT);
        wire.post("create from an unknown template", admin, "/api/v1/instances",
            form("name", "wire-templated", "template_id", "987654"));
        wire.post("delete an instance", admin, "/api/v1/instances/" + disposableId + "/delete", "");
        wire.post("delete an instance with a view grant", viewer, own + "/delete", "");

        // 6. Every reply is the java-rewrite one.
        wire.assertGolden();
    }

    // -- fixtures -------------------------------------------------------------------------------------------------

    private static int instance(String name, String kind, int serverId) {
        Row row = Models.get(InstanceModel.class).createEmptyRow();
        row.set(InstanceModel.NAME, name);
        row.set(InstanceModel.KIND, kind);
        row.set(InstanceModel.SETTINGS, Map.of("image", "fake/image"));
        row.set(InstanceModel.SERVER_ID, serverId);
        row.set(InstanceModel.CREATED_AT, T0);
        Models.get(InstanceModel.class).save(row);
        return row.get(InstanceModel.ID);
    }

    /** A Docker host the application kind's picker rules accept, reached over ssh nobody answers. */
    private static int sshDockerHost(String name) {
        Row row = Models.get(ServerModel.class).createEmptyRow();
        row.set(ServerModel.NAME, name);
        row.set(ServerModel.MODE, ServerModel.MODE_SSH);
        row.set(ServerModel.RUNTIME, ServerModel.RUNTIME_DOCKER);
        row.set(ServerModel.VOLUME_BACKEND, "btrfs");
        row.set(ServerModel.SSH_TARGET, "root@" + name + ".test");
        Models.get(ServerModel.class).save(row);
        return row.get(ServerModel.ID);
    }
}
