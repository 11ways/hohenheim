package be.elevenways.hohenheim.test.host;

import be.elevenways.hohenheim.test.live.LiveLane;
import be.elevenways.zenit.common.orm.datasource.Datasources;
import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.server.docker.ServerService;
import be.elevenways.hohenheim.server.host.HostAdmission;
import be.elevenways.hohenheim.server.host.HostKeys;
import be.elevenways.hohenheim.server.host.HostPreflight;
import be.elevenways.hohenheim.test.HohenheimTestRuntime;
import be.elevenways.zenit.common.orm.datasource.Db;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.validation.Violations;
import be.elevenways.zenit.server.orm.SqliteDatasource;
import be.elevenways.zenit.server.orm.migration.MigrationRunner;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Tag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * The remote-host mechanisms against a GENUINELY REMOTE machine (see
 * {@link LiveRemoteHost}): the trust ceremony ends in a real Docker daemon reached
 * over the pinned ssh lane, a pin that no longer matches what the real host offers
 * fails CLOSED with the typed {@code host_key_changed} class, and the full preflight
 * battery -- including the {@code sudo -n nft} lane no loopback fixture can exercise --
 * runs against that host's own kernel.
 *
 * WHAT IS REAL HERE: another machine, its sshd, its host key, its Docker daemon, its
 * kernel, its nftables. WHAT IS NOT: the reason a pin stops matching. This test pins a
 * DIFFERENT real ed25519 key rather than substituting the host's, because the ssh
 * client compares the pin to the offer and cannot tell those apart -- that equivalence
 * is the whole point -- and rotating a live host's identity to prove it would be
 * vandalism, not evidence.
 */
@Tag("slow") // live lane: needs a real daemon/host/image; runs via `zenit-dev test --all`
class LiveRemoteHostTest {

    private static final String HOST = "live-remote";

    private static SqliteDatasource datasource;
    private static LiveRemoteHost remote;
    private static Path sandbox;
    private static String previousDataPath;

    @BeforeAll
    static void setUp() throws Exception {
        remote = LiveRemoteHost.configured();
        LiveLane.require(LiveLane.Need.REMOTE_HOST, remote != null,
            "no live remote host enrolled at " + LiveRemoteHost.CONFIG);
        LiveLane.require(LiveLane.Need.SSH_TOOLS,
            Files.isExecutable(Path.of("/usr/bin/ssh"))
                && Files.isExecutable(Path.of("/usr/bin/ssh-keygen"))
                && Files.isExecutable(Path.of("/usr/bin/ssh-keyscan")),
            "no OpenSSH client tools");

        File db = File.createTempFile("hohenheim-live-remote-test", ".db");
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

        sandbox = Files.createTempDirectory("hohenheim-live-remote");
        previousDataPath = HohenheimSettings.VALUES.getValue(HohenheimSettings.Storage.DATA_PATH);
        HohenheimSettings.VALUES.setValue(HohenheimSettings.Storage.DATA_PATH,
            sandbox.resolve("data").toString());
    }

    @AfterAll
    static void tearDown() {
        if (previousDataPath != null) {
            HohenheimSettings.VALUES.setValue(HohenheimSettings.Storage.DATA_PATH, previousDataPath);
        }
        deleteTree(sandbox);
    }

    /**
     * The whole ceremony end to end against the real host, then the mismatch path and
     * the re-pin recovery.
     */
    @Test
    void theTrustCeremonyEndsAtARealRemoteDaemonAndAMismatchFailsClosed() {
        Db.run(datasource, () -> {
            ServerService servers = new ServerService();
            ServerModel model = Models.get(ServerModel.class);
            Row host = remote.enrol(HOST);
            try {
                // 1. An UNPINNED remote host is not connectable at all: no scan has
                //    happened, so there is nothing to verify against and the refusal is
                //    ours, typed, before any packet leaves the machine.
                ServerService.Summary unpinned = servers.probeAndStore(HOST);
                assertThat(unpinned.reachable())
                    .as("step 1: an unpinned real host is not reached").isFalse();
                assertThat(unpinned.errorKind())
                    .as("step 1: and the refusal is not_pinned, not a network guess")
                    .isEqualTo("not_pinned");

                // 2. The scan pins what the real host offers -- and the digest we compute
                //    equals the one an operator read ON THE HOST's own console.
                HostKeys.ScanResult scan = HostKeys.scanAndPin(host);
                assertThat(scan.outcome()).as("step 2: a first scan pins")
                    .isEqualTo(HostKeys.ScanOutcome.PINNED);
                assertThat(scan.fingerprint())
                    .as("step 2: and it is the out-of-band fingerprint of the real host")
                    .isEqualTo(remote.fingerprint());

                // 3. A SCANNED pin is UNVERIFIED: a scan proves what the wire said, not
                //    who said it, so admission is refused by name.
                Row pinned = model.findByName(HOST);
                assertThat((Boolean) pinned.get(ServerModel.HOST_KEY_VERIFIED))
                    .as("step 3: a scanned pin is unverified until a human compares it")
                    .isFalse();
                pinned.set(ServerModel.PREFLIGHT_OK, true);
                model.save(pinned);
                assertThat(catchThrowable(() -> HostAdmission.requireAdmittable(pinned)))
                    .as("step 3: admit refuses while the fingerprint is unconfirmed")
                    .isInstanceOfSatisfying(Violations.class, violations ->
                        assertThat(violations.all()).anySatisfy(violation ->
                            assertThat(violation.message().key())
                                .isEqualTo("host_key_unverified")));

                // 4. The operator confirms the digest they read out of band; only now may
                //    the host be admitted.
                HostKeys.confirm(pinned);
                HostAdmission.requireAdmittable(pinned);
                pinned.set(ServerModel.ADMISSION, ServerModel.ADMISSION_ADMITTED);
                model.save(pinned);

                // 5. A REAL Docker daemon on ANOTHER machine, reached over the pinned
                //    lane. The facts must be the REMOTE machine's, not this one's.
                ServerService.Summary reached = servers.probeAndStore(HOST);
                assertThat(reached.reachable())
                    .as("step 5: the remote daemon is reached over the pinned lane (%s)",
                        reached.errorDetail())
                    .isTrue();
                assertThat(reached.osType()).as("step 5: it is a Linux daemon")
                    .isEqualTo("linux");
                assertThat(reached.daemonVersion())
                    .as("step 5: with a real version string").isNotBlank();
                assertThat(reached.memoryBytes())
                    .as("step 5: and real host memory, not a zeroed unreachable guess")
                    .isGreaterThan(0L);
                assertThat(reached.operatingSystem())
                    .as("step 5: the OS is the REMOTE machine's, not this workstation's")
                    .isNotEqualTo(localOperatingSystem());
                Row seen = model.findByName(HOST);
                assertThat((Object) seen.get(ServerModel.LAST_SEEN_AT))
                    .as("step 5: and the contact is durably recorded").isNotNull();
                assertThat((String) seen.get(ServerModel.LAST_ERROR_KIND))
                    .as("step 5: with no stale error kind left behind").isNull();

                // 6. The pin now names a DIFFERENT real ed25519 key. The connection must
                //    fail CLOSED, and the failure must classify as host_key_changed --
                //    NOT as timeout, which is what a classifier reading the ssh argv's
                //    own ConnectTimeout=10 used to answer for every remote failure.
                String realPin = seen.get(ServerModel.HOST_KEY);
                String foreignPin = foreignHostKeyLine();
                assertThat(foreignPin).as("step 6: the decoy really is a different key")
                    .isNotEqualTo(realPin);
                seen.set(ServerModel.HOST_KEY, foreignPin);
                seen.set(ServerModel.HOST_KEY_FINGERPRINT, HostKeys.fingerprintOf(foreignPin));
                model.save(seen);

                ServerService.Summary changed = servers.probeAndStore(HOST);
                assertThat(changed.reachable())
                    .as("step 6: a pin that does not match the offer reaches nothing")
                    .isFalse();
                assertThat(changed.errorKind())
                    .as("step 6: and classifies as host_key_changed, not timeout: %s",
                        changed.errorDetail())
                    .isEqualTo("host_key_changed");
                assertThat(changed.errorDetail())
                    .as("step 6: because OpenSSH itself refused, on real evidence")
                    .contains("Host key verification failed");

                // 7. The failure QUARANTINES the host and re-pins NOTHING.
                Row quarantined = model.findByName(HOST);
                assertThat((String) quarantined.get(ServerModel.ADMISSION))
                    .as("step 7: an admitted host whose identity broke is blocked")
                    .isEqualTo(ServerModel.ADMISSION_BLOCKED);
                assertThat((Boolean) quarantined.get(ServerModel.PREFLIGHT_OK))
                    .as("step 7: its preflight verdict is dropped").isFalse();
                assertThat((String) quarantined.get(ServerModel.HOST_KEY))
                    .as("step 7: and the pin is untouched -- no silent self-healing")
                    .isEqualTo(foreignPin);

                // 8. Rescanning the REAL host does not heal it: the key it offers is
                //    recorded as EVIDENCE, the pin stays wrong.
                HostKeys.ScanResult rescan = HostKeys.scanAndPin(quarantined);
                assertThat(rescan.outcome()).as("step 8: a rescan reports MISMATCH")
                    .isEqualTo(HostKeys.ScanOutcome.MISMATCH);
                assertThat(rescan.fingerprint())
                    .as("step 8: naming the key the real host actually offers")
                    .isEqualTo(remote.fingerprint());
                Row afterRescan = model.findByName(HOST);
                assertThat((String) afterRescan.get(ServerModel.HOST_KEY))
                    .as("step 8: and STILL nothing re-pinned it").isEqualTo(foreignPin);
                assertThat((String) afterRescan.get(ServerModel.HOST_KEY_OFFERED))
                    .as("step 8: the offered key is kept for the re-pin ceremony")
                    .isEqualTo(realPin);

                // 9. Recovery is an explicit operator act that lands the host back at the
                //    BOTTOM of the ceremony.
                HostKeys.repin(afterRescan);
                Row repinned = model.findByName(HOST);
                assertThat((String) repinned.get(ServerModel.HOST_KEY_FINGERPRINT))
                    .as("step 9: the re-pin adopts the key the real host offered")
                    .isEqualTo(remote.fingerprint());
                assertThat((Boolean) repinned.get(ServerModel.HOST_KEY_VERIFIED))
                    .as("step 9: a re-pinned key is unconfirmed again").isFalse();
                assertThat(catchThrowable(() -> HostAdmission.requireAdmittable(repinned)))
                    .as("step 9: so it cannot be admitted without walking the ceremony")
                    .isInstanceOf(Violations.class);

                // 10. With the correct pin restored the real daemon is reachable again.
                ServerService.Summary recovered = servers.probeAndStore(HOST);
                assertThat(recovered.reachable())
                    .as("step 10: the re-pinned lane reaches the daemon again (%s)",
                        recovered.errorDetail())
                    .isTrue();
            } finally {
                servers.remove(HOST);
            }
        });
    }

    /**
     * The full preflight battery against the real host, where {@code sudo -n nft} can
     * pass for the first time. Kernel truth is proven REMOTE by a pids cap only this
     * test would choose: the value has to come back out of the remote container's own
     * cgroup file, and the daemon/kernel facts have to be the other machine's.
     */
    @Test
    void thePreflightBatteryReadsTheRemoteHostsOwnKernelAndNftables() {
        Db.run(datasource, () -> {
            ServerService servers = new ServerService();
            Row host = remote.enrol(HOST);
            Integer originalPids = HohenheimSettings.VALUES.getValue(
                HohenheimSettings.Security.CONTAINER_PIDS_LIMIT);
            try {
                HostKeys.scanAndPin(host);
                Row pinned = Models.get(ServerModel.class).findByName(HOST);
                HostKeys.confirm(pinned);
                HohenheimSettings.VALUES.setValue(
                    HohenheimSettings.Security.CONTAINER_PIDS_LIMIT, 149);

                HostPreflight.Report report = HostPreflight.runAndStore(HOST);

                // 1. The daemon half really ran against the OTHER machine.
                assertThat(report.check("daemon").status())
                    .as("step 1: the remote daemon answered: %s",
                        report.check("daemon").detail())
                    .isEqualTo(HostPreflight.STATUS_PASS);
                assertThat(String.valueOf(report.facts().get("os")))
                    .as("step 1: and the OS fact is the REMOTE machine's")
                    .isNotEqualTo(localOperatingSystem());
                assertThat(String.valueOf(report.facts().get("kernel_version")))
                    .as("step 1: as is the kernel version")
                    .isNotEqualTo(System.getProperty("os.version"));
                assertThat(report.check("api_version").status())
                    .as("step 1: the daemon API is inside the supported window: %s",
                        report.check("api_version").detail())
                    .isEqualTo(HostPreflight.STATUS_PASS);

                // 2. Kernel truth read INSIDE a probe container on the remote host: the
                //    cap the check reports must be the one the remote cgroup file holds,
                //    which is only true if the probe read the kernel over there.
                assertThat(report.check("cgroup_pids_controller").status())
                    .as("step 2: the pids controller is delegated on the remote host: %s",
                        report.check("cgroup_pids_controller").detail())
                    .isEqualTo(HostPreflight.STATUS_PASS);
                assertThat(report.check("pids_limit_enforced").detail())
                    .as("step 2: and the remote cgroup really enforces the cap we set")
                    .contains("'149'");
                assertThat(report.check("pids_limit_enforced").status())
                    .as("step 2: so the check passes on evidence, not on config")
                    .isEqualTo(HostPreflight.STATUS_PASS);
                assertThat(report.check("seccomp").status())
                    .as("step 2: seccomp filters on the remote pid 1: %s",
                        report.check("seccomp").detail())
                    .isEqualTo(HostPreflight.STATUS_PASS);
                assertThat(report.check("no_new_privs").status())
                    .as("step 2: no_new_privs is set on the remote pid 1: %s",
                        report.check("no_new_privs").detail())
                    .isEqualTo(HostPreflight.STATUS_PASS);

                // 3. THE lane no loopback fixture reaches: a real nft transaction on the
                //    remote host's own netns, through ssh + sudo -n.
                assertThat(report.check("nftables").status())
                    .as("step 3: nft applied and read back on the remote host: %s",
                        report.check("nftables").detail())
                    .isEqualTo(HostPreflight.STATUS_PASS);
                assertThat(report.check("nftables").detail())
                    .as("step 3: and it was read back from the kernel, not assumed")
                    .contains("read back from the kernel");

                // 4. A network really was allocated and removed over there.
                assertThat(report.check("network_headroom").failed())
                    .as("step 4: a probe network was created and removed remotely: %s",
                        report.check("network_headroom").detail())
                    .isFalse();

                // 5. The verdict passes and is STORED, so the admit gate can read it.
                assertThat(report.passed())
                    .as("step 5: every required check passed: %s", failedChecks(report))
                    .isTrue();
                Row stored = Models.get(ServerModel.class).findByName(HOST);
                assertThat((Boolean) stored.get(ServerModel.PREFLIGHT_OK))
                    .as("step 5: and the stored verdict says so").isTrue();
                assertThat((Object) stored.get(ServerModel.PROBED_AT))
                    .as("step 5: with a probe timestamp").isNotNull();
                assertThat(stored.get(ServerModel.CAPABILITIES))
                    .as("step 5: and the remote facts on the record")
                    .isInstanceOfSatisfying(Map.class, map ->
                        assertThat((Map<?, ?>) map.get("checks")).isNotEmpty());

                // 6. Admission is now legitimately reachable for this real host.
                HostAdmission.requireAdmittable(stored);
            } finally {
                HohenheimSettings.VALUES.setValue(HohenheimSettings.Security.CONTAINER_PIDS_LIMIT,
                    originalPids != null ? originalPids : 512);
                servers.remove(HOST);
            }
        });
    }

    // -- plumbing -------------------------------------------------------------

    private static String failedChecks(HostPreflight.Report report) {
        List<String> failed = new ArrayList<>();
        for (HostPreflight.Check check : report.checks()) {
            if (check.failed()) {
                failed.add(check.name() + "=" + check.detail());
            }
        }
        return failed.toString();
    }

    /** This machine's OperatingSystem string, so "the remote" can be proven remote. */
    private static String localOperatingSystem() {
        try {
            Object os = new be.elevenways.hohenheim.server.docker.DockerClient()
                .info().get("OperatingSystem");
            return os != null ? String.valueOf(os) : "";
        } catch (Exception noLocalDaemon) {
            return "";
        }
    }

    /** A freshly minted, genuinely valid ed25519 public key that is NOT the host's. */
    private static String foreignHostKeyLine() {
        Path directory = null;
        try {
            directory = Files.createTempDirectory("hohenheim-foreign-key");
            Path key = directory.resolve("decoy");
            Process process = new ProcessBuilder("/usr/bin/ssh-keygen", "-q", "-t", "ed25519",
                "-N", "", "-C", "decoy", "-f", key.toString())
                .redirectErrorStream(true).start();
            if (!process.waitFor(30, TimeUnit.SECONDS) || process.exitValue() != 0) {
                throw new IOException("ssh-keygen did not produce a decoy key");
            }
            String[] parts = Files.readString(directory.resolve("decoy.pub"),
                StandardCharsets.UTF_8).trim().split("\\s+");
            return parts[0] + " " + parts[1];
        } catch (IOException e) {
            throw new IllegalStateException("could not mint a decoy host key", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted minting a decoy host key", e);
        } finally {
            deleteTree(directory);
        }
    }

    private static void deleteTree(Path root) {
        if (root == null || !Files.exists(root)) {
            return;
        }
        try (var walk = Files.walk(root)) {
            List<Path> paths = new ArrayList<>(walk.toList());
            for (int index = paths.size() - 1; index >= 0; index--) {
                Files.deleteIfExists(paths.get(index));
            }
        } catch (IOException ignored) {
            // A leftover temp directory is not worth failing a green run over.
        }
    }
}
