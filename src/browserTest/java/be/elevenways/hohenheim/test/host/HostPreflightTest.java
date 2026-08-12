package be.elevenways.hohenheim.test.host;

import be.elevenways.hohenheim.test.live.LiveLane;
import be.elevenways.zenit.common.orm.datasource.Datasources;
import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.server.docker.DockerClient;
import be.elevenways.hohenheim.server.host.HostPreflight;
import be.elevenways.hohenheim.test.HohenheimTestRuntime;
import be.elevenways.hohenheim.test.network.PrivateNetns;
import be.elevenways.zenit.common.orm.datasource.Db;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.server.orm.SqliteDatasource;
import be.elevenways.zenit.server.orm.migration.MigrationRunner;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Tag;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The preflight battery against the REAL local daemon, with the nft half pointed at
 * real nftables in a private network namespace (the WorkloadNetworkPolicy testing
 * convention -- this machine has no passwordless host nft, and a skipped check would
 * be a green check).
 */
@Tag("slow") // live lane: needs a real daemon/host/image; runs via `zenit-dev test --all`
class HostPreflightTest {

    private static final Path SOCKET = Path.of(DockerClient.DEFAULT_SOCKET);

    private static SqliteDatasource datasource;

    @BeforeAll
    static void setUp() throws Exception {
        File db = File.createTempFile("hohenheim-host-preflight-test", ".db");
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

    /**
     * Every kernel-truth check passes on this machine, and the pids check reads the
     * REAL cgroup value: a deliberately distinct configured cap must appear verbatim
     * in the probe detail, or the check is reading config back to itself.
     */
    @Test
    void preflightReadsKernelTruthFromAProbeContainer() throws Exception {
        LiveLane.require(LiveLane.Need.DOCKER_SOCKET, Files.exists(SOCKET),
            "Docker socket not present");
        DockerClient docker = new DockerClient();
        LiveLane.requireImage(docker, "alpine:latest");
        LiveLane.require(LiveLane.Need.NETNS, PrivateNetns.available(),
            "no private netns for the nft half");

        Integer originalPids = HohenheimSettings.VALUES.getValue(
            HohenheimSettings.Security.CONTAINER_PIDS_LIMIT);
        try (PrivateNetns netns = new PrivateNetns()) {
            // 1. A deliberately unusual pids cap, so the kernel read is provably real.
            HohenheimSettings.VALUES.setValue(
                HohenheimSettings.Security.CONTAINER_PIDS_LIMIT, 137);

            HostPreflight.Report report = HostPreflight.run(docker,
                (args, stdin) -> netns.nftRunner().run(args, stdin));

            // 2. Daemon + API window.
            assertThat(report.check("daemon"))
                .as("step 2: the daemon check exists").isNotNull();
            assertThat(report.check("daemon").status())
                .as("step 2: the daemon is reachable").isEqualTo(HostPreflight.STATUS_PASS);
            assertThat(report.check("api_version").status())
                .as("step 2: the API version is inside the supported window")
                .isEqualTo(HostPreflight.STATUS_PASS);

            // 3. Kernel truth from inside the probe container.
            assertThat(report.check("cgroup_pids_controller").status())
                .as("step 3: the pids controller is delegated (cgroup v2)")
                .isEqualTo(HostPreflight.STATUS_PASS);
            assertThat(report.check("pids_limit_enforced").status())
                .as("step 3: the pids cap is really enforced by the kernel")
                .isEqualTo(HostPreflight.STATUS_PASS);
            assertThat(report.check("pids_limit_enforced").detail())
                .as("step 3: and the detail carries the REAL cgroup value, proving the"
                    + " probe read the kernel and not the config")
                .contains("'137'");
            assertThat(report.check("seccomp").status())
                .as("step 3: seccomp is filtering, not unconfined")
                .isEqualTo(HostPreflight.STATUS_PASS);
            assertThat(report.check("no_new_privs").status())
                .as("step 3: no_new_privs is set")
                .isEqualTo(HostPreflight.STATUS_PASS);

            // 4. The nft half went through REAL nftables (the netns) and read back.
            assertThat(report.check("nftables").status())
                .as("step 4: a real nft transaction applied and read back")
                .isEqualTo(HostPreflight.STATUS_PASS);

            // 5. One more network is allocatable, proven by doing it.
            assertThat(report.check("network_headroom").failed())
                .as("step 5: the probe network was created and removed").isFalse();

            // 6. The advisory checks are KERNEL reads too, and their details must prove
            //    it. Both used to come off the daemon's Info.SecurityOptions, which is a
            //    claim about configuration; the detail now has to carry the literal
            //    /proc value, which no config-reading implementation could produce.
            assertThat(report.check("userns_remap"))
                .as("step 6: userns posture is recorded").isNotNull();
            assertThat(report.check("userns_remap").detail())
                .withFailMessage("step 6: the userns detail carries no uid_map, so the"
                    + " check is not reading the container's kernel state: '%s'",
                    report.check("userns_remap").detail())
                .contains("uid_map reads '");
            assertThat(report.check("userns_remap").detail())
                .withFailMessage("step 6: this machine's daemon has no userns-remap, so"
                    + " the probe must read the IDENTITY map back: '%s'",
                    report.check("userns_remap").detail())
                .contains(HostPreflight.IDENTITY_UID_MAP);
            assertThat(report.check("userns_remap").required())
                .as("step 6: userns is advisory -- refusing every ordinary Docker host"
                    + " over a posture nobody ships is an availability decision")
                .isFalse();
            assertThat(report.check("lsm"))
                .as("step 6: LSM posture is recorded").isNotNull();
            assertThat(report.check("lsm").detail())
                .withFailMessage("step 6: the LSM detail names neither a confinement label"
                    + " nor its absence, so nothing was read from pid 1: '%s'",
                    report.check("lsm").detail())
                .containsAnyOf("pid 1 runs under LSM confinement '", "pid 1 label: '");
            assertThat(report.check("lsm").required())
                .as("step 6: LSM presence is a distribution fact, so it stays advisory")
                .isFalse();

            // 7. The verdict follows the required checks only.
            assertThat(report.passed())
                .as("step 7: every required check passed, so the verdict is a pass")
                .isTrue();
        } finally {
            HohenheimSettings.VALUES.setValue(HohenheimSettings.Security.CONTAINER_PIDS_LIMIT,
                originalPids != null ? originalPids : 512);
        }
    }

    /**
     * runAndStore against the PRODUCTION nft seam: on this machine sudo nft has no
     * password-less grant, so the nftables check FAILS -- and that failure must be
     * stored honestly (preflight_ok false), because an unverifiable host is exactly
     * the host that may not be admitted.
     */
    @Test
    void theStoredReportIsHonestAboutAnUnverifiableHost() throws Exception {
        LiveLane.require(LiveLane.Need.DOCKER_SOCKET, Files.exists(SOCKET),
            "Docker socket not present");
        LiveLane.requireImage(new DockerClient(), "alpine:latest");

        Db.run(datasource, () -> {
            ServerModel.localServerId();
            HostPreflight.Report report = HostPreflight.runAndStore("local");

            Row local = Models.get(ServerModel.class).findByName("local");
            assertThat((Object) local.get(ServerModel.PROBED_AT))
                .as("step 1: probed_at is stamped").isNotNull();
            assertThat((Boolean) local.get(ServerModel.PREFLIGHT_OK))
                .as("step 1: preflight_ok stores the real verdict")
                .isEqualTo(report.passed());
            Object capabilities = local.get(ServerModel.CAPABILITIES);
            assertThat(capabilities)
                .as("step 2: the capabilities JSON holds the check map")
                .isInstanceOfSatisfying(Map.class, map ->
                    assertThat((Map<?, ?>) map.get("checks")).isNotEmpty());
            assertThat((Object) local.get(ServerModel.LAST_SEEN_AT))
                .as("step 2: the daemon contact updated last_seen_at").isNotNull();

            // 3. The nftables outcome is recorded VERBATIM. On a host with passwordless
            //    nft it passes; on this machine it fails -- either way the verdict must
            //    agree with the stored check, never report green past a failed check.
            HostPreflight.Check nft = report.check("nftables");
            assertThat(nft).as("step 3: the nftables check is always recorded").isNotNull();
            if (nft.failed()) {
                assertThat(report.passed())
                    .as("step 3: a failed required nft check fails the whole preflight")
                    .isFalse();
                assertThat((Boolean) local.get(ServerModel.PREFLIGHT_OK))
                    .as("step 3: and the stored verdict says so")
                    .isFalse();
            }
        });
    }
}
