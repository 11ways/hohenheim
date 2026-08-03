package be.elevenways.hohenheim.test.host;

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

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The preflight battery against the REAL local daemon, with the nft half pointed at
 * real nftables in a private network namespace (the WorkloadNetworkPolicy testing
 * convention -- this machine has no passwordless host nft, and a skipped check would
 * be a green check).
 */
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
        HohenheimTestRuntime.ensureBooted();
    }

    /**
     * Every kernel-truth check passes on this machine, and the pids check reads the
     * REAL cgroup value: a deliberately distinct configured cap must appear verbatim
     * in the probe detail, or the check is reading config back to itself.
     */
    @Test
    void preflightReadsKernelTruthFromAProbeContainer() throws Exception {
        assumeTrue(Files.exists(SOCKET), "Docker socket not present");
        DockerClient docker = new DockerClient();
        assumeTrue(imagePresent(docker), "alpine:latest not present locally");
        assumeTrue(PrivateNetns.available(), "no private netns for the nft half");

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

            // 6. Advisory checks are RECORDED (pass or warn), never absent and never
            //    silently green: this machine has no userns-remap, so warn is expected.
            assertThat(report.check("userns_remap"))
                .as("step 6: userns posture is recorded").isNotNull();
            assertThat(report.check("lsm"))
                .as("step 6: LSM posture is recorded").isNotNull();

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
        assumeTrue(Files.exists(SOCKET), "Docker socket not present");
        assumeTrue(imagePresent(new DockerClient()), "alpine:latest not present locally");

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

    private static boolean imagePresent(DockerClient docker) throws Exception {
        for (Object image : docker.listImages()) {
            Object repoTags = ((Map<?, ?>) image).get("RepoTags");
            if (repoTags instanceof java.util.List<?> tags && tags.contains("alpine:latest")) {
                return true;
            }
        }
        return false;
    }
}
