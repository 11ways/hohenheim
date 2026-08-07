package be.elevenways.hohenheim.test.host;

import be.elevenways.zenit.common.orm.datasource.Datasources;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.server.docker.ServerService;
import be.elevenways.hohenheim.server.host.HostAdmission;
import be.elevenways.hohenheim.server.host.HostPreflight;
import be.elevenways.hohenheim.server.host.IncusPreflight;
import be.elevenways.hohenheim.server.incus.IncusEndpoint;
import be.elevenways.hohenheim.server.instance.InstancePlacement;
import be.elevenways.hohenheim.server.instance.InstanceService;
import be.elevenways.hohenheim.server.instance.InstanceSnapshots;
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
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * The runtime-as-data declaration on host records, with no daemon anywhere: which
 * hosts require a pinned identity, which client construction refuses which host,
 * where placement may land each runtime, and the resolve-time kind/host mismatch
 * refusal -- the seams that keep a Docker client from ever being aimed at an Incus
 * daemon or vice versa.
 */
class HostRuntimeTest {

    private static SqliteDatasource datasource;

    @BeforeAll
    static void setUp() throws Exception {
        File db = File.createTempFile("hohenheim-host-runtime-test", ".db");
        db.delete();
        db.deleteOnExit();
        datasource = new SqliteDatasource("jdbc:sqlite:" + db.getAbsolutePath());
        new MigrationRunner(datasource).migrate().requireSuccess();
        // ONE database per test class: the controller identity (and therefore every
        // daemon resource name) resolves through the CURRENT datasource, and a Db scope
        // is thread-local -- so a second, unregistered database would hand any
        // thread-hopping work a different controller's token than the records came from.
        Datasources.register(Datasources.DEFAULT, datasource);
        HohenheimTestRuntime.ensureBooted();
    }

    private static Row incusHost(String name, String url) {
        ServerModel model = Models.get(ServerModel.class);
        Row row = model.findByName(name);
        if (row == null) {
            row = model.createEmptyRow();
            row.set(ServerModel.NAME, name);
        }
        row.set(ServerModel.RUNTIME, ServerModel.RUNTIME_INCUS);
        row.set(ServerModel.INCUS_URL, url);
        row.set(ServerModel.MODE, ServerModel.MODE_LOCAL);
        model.save(row);
        return model.findByName(name);
    }

    @Test
    void theRuntimeDeclarationDrivesIdentityClientAndPlacementDecisions() {
        Db.run(datasource, () -> {
            ServerService servers = new ServerService();

            // 1. Which shapes carry a WIRE identity to pin: both remote transports do,
            //    neither local socket does.
            Row incusRemote = incusHost("rt-remote", "https://192.0.2.9:8443");
            Row incusLocal = incusHost("rt-local", "unix://");
            servers.ensureLocal();
            Row dockerLocal = Models.get(ServerModel.class).findByName("local");
            assertThat(ServerModel.requiresPinnedIdentity(incusRemote))
                .as("step 1: incus-over-https requires a pinned identity").isTrue();
            assertThat(ServerModel.requiresPinnedIdentity(incusLocal))
                .as("step 1: incus-over-unix does not").isFalse();
            assertThat(ServerModel.requiresPinnedIdentity(dockerLocal))
                .as("step 1: the local docker daemon does not").isFalse();

            // 2. The admission identity gate follows that declaration: an unpinned
            //    https incus host is refused BY NAME, the unix one passes.
            assertThat(catchThrowable(() -> HostAdmission.requireVerifiedIdentity(incusRemote)))
                .as("step 2: unverified https incus identity refuses admission")
                .isInstanceOfSatisfying(Violations.class, violations ->
                    assertThat(violations.all()).anySatisfy(violation ->
                        assertThat(violation.message().key())
                            .isEqualTo("host_key_unverified")));
            HostAdmission.requireVerifiedIdentity(incusLocal);

            // 3. A DockerClient for an incus host is a named refusal -- the dispatch
            //    reads the runtime column, it never falls through to the local socket.
            assertThatThrownBy(() -> servers.clientFor("rt-remote"))
                .as("step 3: no Docker daemon is addressed on an incus host")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("incus runtime");
            // ...and the mirror image: an IncusClient for a docker host refuses too.
            assertThatThrownBy(() -> servers.incusClientFor("local"))
                .as("step 3: no Incus daemon is addressed on a docker host")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("docker runtime");

            // 4. Placement is runtime-aware: an admitted incus host takes incus
            //    workloads and NEVER docker ones, and with no admitted docker host the
            //    docker runtime is a named placement refusal.
            Row admitted = incusHost("rt-local", "unix://");
            admitted.set(ServerModel.PREFLIGHT_OK, true);
            admitted.set(ServerModel.ADMISSION, ServerModel.ADMISSION_ADMITTED);
            admitted.set(ServerModel.POSTURE, ServerModel.POSTURE_SHARED_CONTAINER);
            Models.get(ServerModel.class).save(admitted);
            // The eligible set is HostAdmission's own, so this host must satisfy every
            // gate a deploy would: a PROVEN kernel-truth lane and a measured memory
            // budget, both stored through the preflight funnel. Placement offering a
            // host that the deploy then refuses is the defect that seam removed.
            HostPreflight.store("rt-local", new HostPreflight.Report(List.of(
                new HostPreflight.Check("daemon", HostPreflight.STATUS_PASS, true, "ok"),
                new HostPreflight.Check(IncusPreflight.KERNEL_LANE_CHECK,
                    HostPreflight.STATUS_PASS, true, "kernel lane proven")),
                Map.of("mem_total", 4L * 1024 * 1024 * 1024), true, Instant.now(), null));
            assertThat(InstancePlacement.chooseForOwner("",
                    InstancePlacement.Workload.forRuntime(ServerModel.RUNTIME_INCUS)))
                .as("step 4: the incus runtime lands on the admitted incus host")
                .isEqualTo((int) admitted.get(ServerModel.ID));
            assertThat(catchThrowable(() ->
                    InstancePlacement.chooseForOwner("",
                        InstancePlacement.Workload.forRuntime(ServerModel.RUNTIME_DOCKER))))
                .as("step 4: the docker runtime refuses rather than landing there")
                .isInstanceOfSatisfying(Violations.class, violations ->
                    assertThat(violations.all()).anySatisfy(violation ->
                        assertThat(violation.message().key())
                            .isEqualTo("no_placement_available")));

            // 5. Resolve refuses a kind/host runtime mismatch BEFORE any client exists:
            //    a docker_container record pointed at the incus host is a named
            //    violation, not a wrong-daemon client.
            Row instance = Models.get(InstanceModel.class).createEmptyRow();
            instance.set(InstanceModel.NAME, "mismatched");
            instance.set(InstanceModel.KIND, "hohenheim:docker_container");
            instance.set(InstanceModel.SETTINGS, Map.of("image", "alpine"));
            instance.set(InstanceModel.SERVER_ID, admitted.get(ServerModel.ID));
            Models.get(InstanceModel.class).save(instance);
            assertThat(catchThrowable(() -> new InstanceService()
                    .resolve(instance.get(InstanceModel.ID))))
                .as("step 5: kind/host runtime mismatch is a named refusal")
                .isInstanceOfSatisfying(Violations.class, violations ->
                    assertThat(violations.all()).anySatisfy(violation ->
                        assertThat(violation.message().key())
                            .isEqualTo("host_runtime_mismatch")));

            // 6. The incus kind now CARRIES the snapshot capability (the native lane),
            //    so the refusal moved past the capability gate to the next honest one:
            //    this unix:// daemon does not exist, and the distinct violation key is
            //    the evidence the native lane was entered rather than refused by name.
            //    The snapshots_unsupported funnel itself stays for a driver with
            //    NEITHER seam and keeps its named-refusal contract.
            Row incusInstance = Models.get(InstanceModel.class).createEmptyRow();
            incusInstance.set(InstanceModel.NAME, "snapshotless");
            incusInstance.set(InstanceModel.KIND, "hohenheim:incus_container");
            incusInstance.set(InstanceModel.SETTINGS, Map.of("image", "alpine/3.22"));
            incusInstance.set(InstanceModel.SERVER_ID, admitted.get(ServerModel.ID));
            Models.get(InstanceModel.class).save(incusInstance);
            assertThat(catchThrowable(() -> new InstanceSnapshots()
                    .create(incusInstance.get(InstanceModel.ID), null)))
                .as("step 6: the native lane is entered and refuses at the daemon gate")
                .isInstanceOfSatisfying(Violations.class, violations ->
                    assertThat(violations.all()).anySatisfy(violation ->
                        assertThat(violation.message().key())
                            .isEqualTo("instance_unreachable")));
        });
    }

    @Test
    void incusEndpointsParseTheDeclaredSpellingsAndRefuseTheRest() {
        // 1. Blank and unix:// fold to the default local socket.
        assertThat(IncusEndpoint.parse(null).socketPath())
            .as("step 1: null = the default socket")
            .isEqualTo("/var/lib/incus/unix.socket");
        assertThat(IncusEndpoint.parse("unix://").https())
            .as("step 1: unix:// is not https").isFalse();
        assertThat(IncusEndpoint.parse("unix:///run/incus.sock").socketPath())
            .as("step 1: an explicit socket path is honoured")
            .isEqualTo("/run/incus.sock");

        // 2. https spellings: default port, explicit port, IPv6 literal.
        IncusEndpoint plain = IncusEndpoint.parse("https://10.0.0.9");
        assertThat(plain.port()).as("step 2: the default API port is 8443").isEqualTo(8443);
        IncusEndpoint explicit = IncusEndpoint.parse("https://incus.example:9999");
        assertThat(explicit.host()).as("step 2: the host survives").isEqualTo("incus.example");
        assertThat(explicit.port()).as("step 2: an explicit port is honoured").isEqualTo(9999);
        IncusEndpoint ipv6 = IncusEndpoint.parse("https://[fd00::9]:8443");
        assertThat(ipv6.host()).as("step 2: an IPv6 literal loses its brackets")
            .isEqualTo("fd00::9");

        // 3. Everything else is refused before any connect call could see it.
        assertThatThrownBy(() -> IncusEndpoint.parse("http://10.0.0.9:8443"))
            .as("step 3: plaintext http is not a lane")
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> IncusEndpoint.parse("https://:8443"))
            .as("step 3: a hostless url is refused")
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> IncusEndpoint.parse("https://host:notaport"))
            .as("step 3: a garbage port is refused")
            .isInstanceOf(IllegalArgumentException.class);
    }
}
