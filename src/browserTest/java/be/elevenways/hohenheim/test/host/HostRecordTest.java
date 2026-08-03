package be.elevenways.hohenheim.test.host;

import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.model.StackModel;
import be.elevenways.hohenheim.server.docker.DockerClient;
import be.elevenways.hohenheim.server.docker.ServerService;
import be.elevenways.hohenheim.server.host.HostAdmission;
import be.elevenways.hohenheim.server.instance.InstanceService;
import be.elevenways.hohenheim.test.HohenheimTestRuntime;
import be.elevenways.zenit.common.orm.datasource.Db;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.validation.Violations;
import be.elevenways.zenit.server.orm.SqliteDatasource;
import be.elevenways.zenit.server.orm.migration.MigrationRunner;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * The grown host record as a journey, daemon-free: admission gates placement,
 * admit needs a passing preflight, removal refuses while owned resources remain,
 * and a probe failure is TYPED and STORED instead of swallowed into zeroed
 * capacity.
 */
class HostRecordTest {

    private static SqliteDatasource datasource;

    @BeforeAll
    static void setUp() throws Exception {
        File db = File.createTempFile("hohenheim-host-record-test", ".db");
        db.delete();
        db.deleteOnExit();
        datasource = new SqliteDatasource("jdbc:sqlite:" + db.getAbsolutePath());
        new MigrationRunner(datasource).migrate().requireSuccess();
        HohenheimTestRuntime.ensureBooted();
    }

    private static int instanceRecord(String name, Integer serverId) {
        Row row = Models.get(InstanceModel.class).createEmptyRow();
        row.set(InstanceModel.NAME, name);
        row.set(InstanceModel.KIND, "hohenheim:docker_container");
        row.set(InstanceModel.SETTINGS, Map.of("image", "alpine", "command", "sleep 60"));
        if (serverId != null) {
            row.set(InstanceModel.SERVER_ID, serverId);
        }
        Models.get(InstanceModel.class).save(row);
        return row.get(InstanceModel.ID);
    }

    /** Admission and posture gate instance placement BEFORE any daemon contact. */
    @Test
    void admissionAndPostureGateInstancePlacement() {
        Db.run(datasource, () -> {
            int localId = ServerModel.localServerId();
            Row local = Models.get(ServerModel.class).findById(localId);
            local.set(ServerModel.ADMISSION, ServerModel.ADMISSION_BLOCKED);
            local.set(ServerModel.POSTURE, ServerModel.POSTURE_TRUSTED_ONLY);
            Models.get(ServerModel.class).save(local);
            int id = instanceRecord("gate-victim", null);
            InstanceService service = new InstanceService();

            // 1. A host with no successful probe is never silently trusted: default
            //    admission is blocked and the deploy is refused BY NAME.
            assertThat(catchThrowable(() -> service.deploy(id)))
                .as("step 1: a blocked host refuses placement")
                .isInstanceOfSatisfying(Violations.class, violations ->
                    assertThat(violations.all()).anySatisfy(violation ->
                        assertThat(violation.message().key()).isEqualTo("host_not_admitted")));
            assertThat((String) Models.get(InstanceModel.class).findById(id)
                    .get(InstanceModel.STATUS))
                .as("step 1: and the record is untouched -- nothing pretended to deploy")
                .isEqualTo(InstanceModel.STATUS_CREATED);

            // 2. Admitted but trusted-only: the POSTURE refuses a tenant instance.
            local.set(ServerModel.ADMISSION, ServerModel.ADMISSION_ADMITTED);
            Models.get(ServerModel.class).save(local);
            assertThat(catchThrowable(() -> service.deploy(id)))
                .as("step 2: a trusted-only posture refuses tenant instances")
                .isInstanceOfSatisfying(Violations.class, violations ->
                    assertThat(violations.all()).anySatisfy(violation ->
                        assertThat(violation.message().key()).isEqualTo("host_posture_refuses")));

            // 3. Cordoning an admitted host refuses NEW placement again.
            local.set(ServerModel.POSTURE, ServerModel.POSTURE_SHARED_CONTAINER);
            local.set(ServerModel.ADMISSION, ServerModel.ADMISSION_CORDONED);
            Models.get(ServerModel.class).save(local);
            assertThat(catchThrowable(() -> service.deploy(id)))
                .as("step 3: a cordoned host refuses new placement")
                .isInstanceOfSatisfying(Violations.class, violations ->
                    assertThat(violations.all()).anySatisfy(violation ->
                        assertThat(violation.message().key()).isEqualTo("host_not_admitted")));

            // 4. The admit transition itself is gated on a PASSING preflight.
            local.set(ServerModel.PREFLIGHT_OK, false);
            Models.get(ServerModel.class).save(local);
            assertThat(catchThrowable(() -> HostAdmission.requireAdmittable(local)))
                .as("step 4: admit refuses without a passing preflight")
                .isInstanceOfSatisfying(Violations.class, violations ->
                    assertThat(violations.all()).anySatisfy(violation ->
                        assertThat(violation.message().key()).isEqualTo("admit_needs_preflight")));
            local.set(ServerModel.PREFLIGHT_OK, true);
            HostAdmission.requireAdmittable(local);   // step 4b: and passes with one

            // Restore the local host for other tests in this class.
            local.set(ServerModel.ADMISSION, ServerModel.ADMISSION_BLOCKED);
            Models.get(ServerModel.class).save(local);
        });
    }

    /** Removal refuses while stacks or live instances still reference the host. */
    @Test
    void removalRefusesWhileOwnedResourcesRemain() {
        Db.run(datasource, () -> {
            ServerService servers = new ServerService();
            servers.add("edge-owned", "deploy@edge-owned.example");
            int serverId = ServerModel.canonicalServerId("edge-owned");

            // 1. A live instance on the host blocks removal, with counts in the refusal.
            int instanceId = instanceRecord("holds-the-host", serverId);
            assertThat(catchThrowable(() -> servers.remove("edge-owned")))
                .as("step 1: removal refuses while a live instance references the host")
                .isInstanceOfSatisfying(Violations.class, violations ->
                    assertThat(violations.all()).anySatisfy(violation ->
                        assertThat(violation.message().key()).isEqualTo("server_in_use")));
            assertThat(Models.get(ServerModel.class).findByName("edge-owned"))
                .as("step 1: the server row survives the refused delete").isNotNull();

            // 2. A stack on the host blocks removal too, even with no instances left.
            Row instance = Models.get(InstanceModel.class).findById(instanceId);
            instance.set(InstanceModel.DELETED_AT, Instant.now());
            Models.get(InstanceModel.class).save(instance);
            Row stack = Models.get(StackModel.class).createEmptyRow();
            stack.set(StackModel.NAME, "edge-stack");
            stack.set(StackModel.SERVER_ID, serverId);
            Models.get(StackModel.class).save(stack);
            assertThat(catchThrowable(() -> servers.remove("edge-owned")))
                .as("step 2: removal refuses while a stack references the host")
                .isInstanceOf(Violations.class);

            // 3. With every owner gone (the instance already soft-deleted), removal works.
            Models.get(StackModel.class).delete(stack.get(StackModel.ID));
            servers.remove("edge-owned");
            assertThat(Models.get(ServerModel.class).findByName("edge-owned"))
                .as("step 3: an unowned host removes cleanly").isNull();
        });
    }

    /** A probe failure is a TYPED, STORED outcome -- and the list never probes live. */
    @Test
    void probeFailuresAreTypedAndStoredAndTheListNeverProbes() {
        Db.run(datasource, () -> {
            ServerService servers = new ServerService();
            servers.add("edge-dark", "nobody@edge-dark.hohenheim-test.invalid");

            // 1. The live probe fails with a NAMED class, not a silent null.
            ServerService.Summary summary = servers.probeAndStore("edge-dark");
            assertThat(summary.reachable())
                .as("step 1: the invalid host is unreachable").isFalse();
            assertThat(summary.errorKind())
                .as("step 1: and the failure is typed, never a zeroed shrug")
                .isIn("dns", "unreachable", "timeout");

            // 2. The typed outcome is STORED on the record: columns, not memory.
            Row stored = Models.get(ServerModel.class).findByName("edge-dark");
            assertThat((String) stored.get(ServerModel.LAST_ERROR_KIND))
                .as("step 2: last_error_kind persisted").isEqualTo(summary.errorKind());
            assertThat((String) stored.get(ServerModel.LAST_ERROR))
                .as("step 2: the human detail persisted too").isNotBlank();
            assertThat((Object) stored.get(ServerModel.LAST_SEEN_AT))
                .as("step 2: a host never reached has no last_seen_at").isNull();

            // 3. The stored-state list reads columns WITHOUT constructing a single
            //    docker client -- the old per-render serial probe is gone.
            long constructions = DockerClient.constructionCount();
            var states = servers.storedStates();
            assertThat(DockerClient.constructionCount())
                .as("step 3: storedStates() constructs no DockerClient")
                .isEqualTo(constructions);
            assertThat(states)
                .as("step 3: and the dark host's stored state carries its typed failure")
                .anySatisfy(state -> {
                    assertThat(state.name()).isEqualTo("edge-dark");
                    assertThat(state.lastErrorKind()).isEqualTo(summary.errorKind());
                    assertThat(state.admission()).isEqualTo(ServerModel.ADMISSION_BLOCKED);
                });

            servers.remove("edge-dark");
        });
    }
}
