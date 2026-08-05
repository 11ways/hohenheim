package be.elevenways.hohenheim.test.host;

import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.server.docker.ServerService;
import be.elevenways.hohenheim.server.host.HostAdmission;
import be.elevenways.hohenheim.server.host.HostKeys;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The pin, proven against a REAL OpenSSH server whose host key really changes:
 * enrollment mints a per-host client key and pins what the host offers, an
 * unconfirmed pin cannot be admitted, a changed key FAILS the connection and
 * quarantines the host instead of silently re-accepting it, and recovery takes an
 * explicit re-pin.
 *
 * AIDEV-NOTE: the ssh CLIENT and the sshd here are the real binaries -- nothing about
 * host-key verification is stubbed, because a test that only walks our own branch
 * cannot tell whether OpenSSH actually enforces what we asked for. What IS simulated
 * is the reason the key changed: this fixture regenerates it, where an attacker would
 * have substituted it. The ssh client cannot tell those apart, which is the point.
 * No Docker daemon is involved: every connection here dies at authentication, which
 * is strictly AFTER host-key verification.
 */
class HostKeyPinningTest {

    private static SqliteDatasource datasource;
    private static Path sandbox;
    private static String previousDataPath;
    private static Sshd sshd;

    @BeforeAll
    static void setUp() throws Exception {
        assumeTrue(Sshd.available(),
            "no OpenSSH binaries: the real host-key lane cannot be exercised here");

        File db = File.createTempFile("hohenheim-host-key-test", ".db");
        db.delete();
        db.deleteOnExit();
        datasource = new SqliteDatasource("jdbc:sqlite:" + db.getAbsolutePath());
        new MigrationRunner(datasource).migrate().requireSuccess();
        HohenheimTestRuntime.ensureBooted();

        sandbox = Files.createTempDirectory("hohenheim-host-key");
        previousDataPath = HohenheimSettings.VALUES.getValue(HohenheimSettings.Storage.DATA_PATH);
        HohenheimSettings.VALUES.setValue(HohenheimSettings.Storage.DATA_PATH,
            sandbox.resolve("data").toString());
        sshd = Sshd.start(sandbox.resolve("sshd"));
    }

    @AfterAll
    static void tearDown() {
        if (sshd != null) {
            sshd.stop();
        }
        if (previousDataPath != null) {
            HohenheimSettings.VALUES.setValue(HohenheimSettings.Storage.DATA_PATH, previousDataPath);
        }
        deleteTree(sandbox);
    }

    /**
     * The whole trust ceremony against a live sshd: pin, refuse admission until an
     * operator confirms, connect for real, then change the host key underneath it.
     */
    @Test
    void aChangedHostKeyFailsClosedQuarantinesAndNeedsAnExplicitRepin() {
        Db.run(datasource, () -> {
            ServerService servers = new ServerService();
            servers.add("edge-pinned", "nobody@127.0.0.1:" + sshd.port());
            ServerModel model = Models.get(ServerModel.class);
            Row host = model.findByName("edge-pinned");

            // 1. Enrollment mints the host's OWN client key; the private half is stored
            //    encrypted and the public half is what an operator installs remotely.
            assertThat(HostKeys.ensureIdentity(host))
                .as("step 1: enrollment generates a per-host client key").isTrue();
            assertThat((String) host.get(ServerModel.IDENTITY_PUBLIC_KEY))
                .as("step 1: and the public half is an ed25519 line to install")
                .startsWith("ssh-ed25519 ");
            assertThat((String) host.get(ServerModel.IDENTITY_PRIVATE_KEY))
                .as("step 1: with a private half held for this host only")
                .contains("OPENSSH PRIVATE KEY");

            // 2. An UNPINNED remote host is not connectable at all: fail closed, typed.
            ServerService.Summary unpinned = servers.probeAndStore("edge-pinned");
            assertThat(unpinned.reachable()).as("step 2: an unpinned host is not reached").isFalse();
            assertThat(unpinned.errorKind())
                .as("step 2: and the refusal is our own, typed, not an ssh guess")
                .isEqualTo("not_pinned");

            // 3. Scanning pins what the host offers -- and the fingerprint we compute
            //    matches what ssh-keygen itself prints for that key.
            HostKeys.ScanResult first = HostKeys.scanAndPin(host);
            assertThat(first.outcome()).as("step 3: a first scan pins")
                .isEqualTo(HostKeys.ScanOutcome.PINNED);
            assertThat(first.fingerprint())
                .as("step 3: and it is the real fingerprint of the running sshd's key")
                .isEqualTo(sshd.hostKeyFingerprint());
            Row pinned = model.findByName("edge-pinned");
            assertThat((Boolean) pinned.get(ServerModel.HOST_KEY_VERIFIED))
                .as("step 3: a scanned pin is UNVERIFIED until a human compares it")
                .isFalse();

            // 4. An unconfirmed pin cannot be admitted, even with a passing preflight.
            pinned.set(ServerModel.PREFLIGHT_OK, true);
            pinned.set(ServerModel.POSTURE, ServerModel.POSTURE_SHARED_CONTAINER);
            model.save(pinned);
            assertThat(catchThrowable(() -> HostAdmission.requireAdmittable(pinned)))
                .as("step 4: admit refuses while the fingerprint is unconfirmed")
                .isInstanceOfSatisfying(Violations.class, violations ->
                    assertThat(violations.all()).anySatisfy(violation ->
                        assertThat(violation.message().key()).isEqualTo("host_key_unverified")));

            // 5. The operator confirms out of band; only then may the host be admitted.
            HostKeys.confirm(pinned);
            HostAdmission.requireAdmittable(pinned);
            pinned.set(ServerModel.ADMISSION, ServerModel.ADMISSION_ADMITTED);
            model.save(pinned);

            // 6. A REAL connection over the pinned lane: host-key verification passes and
            //    the run dies at authentication instead -- proof the pin is being used and
            //    accepted by the actual ssh client, not merely stored.
            ServerService.Summary connected = servers.probeAndStore("edge-pinned");
            assertThat(connected.errorKind())
                .as("step 6: the pinned key verifies; the failure is authentication")
                .isEqualTo("ssh_auth");
            assertThat(connected.errorDetail())
                .as("step 6: and it really was the ssh client refusing the login")
                .containsIgnoringCase("permission denied");

            // 7. The host's key CHANGES underneath us. The connection must fail closed.
            String pinnedKey = pinned.get(ServerModel.HOST_KEY);
            sshd.regenerateHostKey();
            ServerService.Summary changed = servers.probeAndStore("edge-pinned");
            assertThat(changed.errorKind())
                .as("step 7: a changed host key is the typed host_key_changed failure")
                .isEqualTo("host_key_changed");
            assertThat(changed.errorDetail())
                .as("step 7: and it is OpenSSH itself refusing, not a guess of ours")
                .contains("Host key verification failed");

            // 8. The failure QUARANTINES: an admitted host stops receiving workloads and
            //    the pin is untouched -- nothing re-accepted the new key.
            Row quarantined = model.findByName("edge-pinned");
            assertThat((String) quarantined.get(ServerModel.ADMISSION))
                .as("step 8: an admitted host whose identity broke is blocked")
                .isEqualTo(ServerModel.ADMISSION_BLOCKED);
            assertThat((Boolean) quarantined.get(ServerModel.PREFLIGHT_OK))
                .as("step 8: and its preflight verdict is dropped").isFalse();
            assertThat((String) quarantined.get(ServerModel.HOST_KEY))
                .as("step 8: the pin is UNCHANGED -- no silent self-healing")
                .isEqualTo(pinnedKey);

            // 9. Rescanning does not heal it either: the new key is recorded as EVIDENCE.
            HostKeys.ScanResult rescan = HostKeys.scanAndPin(quarantined);
            assertThat(rescan.outcome()).as("step 9: a rescan reports MISMATCH")
                .isEqualTo(HostKeys.ScanOutcome.MISMATCH);
            assertThat(rescan.fingerprint())
                .as("step 9: naming the key the host now offers")
                .isEqualTo(sshd.hostKeyFingerprint());
            Row afterRescan = model.findByName("edge-pinned");
            assertThat((String) afterRescan.get(ServerModel.HOST_KEY))
                .as("step 9: and STILL nothing re-pinned it").isEqualTo(pinnedKey);
            assertThat((String) afterRescan.get(ServerModel.HOST_KEY_OFFERED))
                .as("step 9: the offered key is kept as evidence for the re-pin ceremony")
                .isNotNull();

            // 10. Recovery is an explicit operator act, and it lands the host back at the
            //     BOTTOM of the ceremony: unconfirmed, unpreflighted, unadmitted.
            HostKeys.repin(afterRescan);
            Row repinned = model.findByName("edge-pinned");
            assertThat((String) repinned.get(ServerModel.HOST_KEY_FINGERPRINT))
                .as("step 10: the re-pin adopts the offered key")
                .isEqualTo(sshd.hostKeyFingerprint());
            assertThat((Boolean) repinned.get(ServerModel.HOST_KEY_VERIFIED))
                .as("step 10: a re-pinned key is unconfirmed again").isFalse();
            assertThat((Boolean) repinned.get(ServerModel.PREFLIGHT_OK))
                .as("step 10: and unpreflighted again").isFalse();
            assertThat(catchThrowable(() -> HostAdmission.requireAdmittable(repinned)))
                .as("step 10: so it cannot be admitted without walking the ceremony again")
                .isInstanceOf(Violations.class);

            // 11. With the new pin in place the real ssh lane works again.
            ServerService.Summary reconnected = servers.probeAndStore("edge-pinned");
            assertThat(reconnected.errorKind())
                .as("step 11: the re-pinned key verifies and the run reaches auth again: %s",
                    reconnected.errorDetail())
                .isEqualTo("ssh_auth");

            servers.remove("edge-pinned");
        });
    }

    /** Fail-closed refusals and the target parsing the ssh argv depends on. */
    @Test
    void refusalsAreTypedAndTargetsParseIntoRealSshArguments() {
        Db.run(datasource, () -> {
            ServerService servers = new ServerService();
            servers.add("edge-nokey", "deploy@127.0.0.1:" + sshd.port());
            ServerModel model = Models.get(ServerModel.class);
            Row host = model.findByName("edge-nokey");

            // 1. No pin AND no identity: the pin is checked first and the refusal says so.
            assertThat(catchThrowable(() -> HostKeys.sshArgv(host, List.of("true"))))
                .as("step 1: an unpinned host cannot produce an ssh argv at all")
                .isInstanceOfSatisfying(HostKeys.HostTrustException.class, refusal ->
                    assertThat(refusal.kind.token).isEqualTo("not_pinned"));

            // 2. Pinned but with no client identity: still refused, with its OWN class --
            //    "we have no credential" and "we do not know this host" are not one state.
            HostKeys.scanAndPin(host);
            Row pinned = model.findByName("edge-nokey");
            assertThat(catchThrowable(() -> HostKeys.sshArgv(pinned, List.of("true"))))
                .as("step 2: a pinned host with no client key is refused separately")
                .isInstanceOfSatisfying(HostKeys.HostTrustException.class, refusal ->
                    assertThat(refusal.kind.token).isEqualTo("no_identity"));

            // 3. With both in hand the argv pins verification to OUR store and OUR key.
            HostKeys.ensureIdentity(pinned);
            List<String> argv = HostKeys.sshArgv(pinned, List.of("docker", "system", "dial-stdio"));
            String flat = String.join(" ", argv);
            assertThat(flat)
                .as("step 3: strict checking against Hohenheim's own known_hosts, own key")
                .contains("StrictHostKeyChecking=yes")
                .contains("GlobalKnownHostsFile=/dev/null")
                .contains("IdentitiesOnly=yes")
                .doesNotContain("accept-new");
            assertThat(flat)
                .as("step 3: and never the OS user's ambient known_hosts")
                .doesNotContain(System.getProperty("user.home") + "/.ssh");
            assertThat(argv)
                .as("step 3: the -- terminator still guards against an option-shaped target")
                .containsSequence("--", "deploy@127.0.0.1", "docker");

            // 4. A [user@]host:port target becomes a real -p argument; ssh takes no
            //    "host:port" destination, so the old spelling would have resolved a
            //    hostname that does not exist.
            assertThat(argv).as("step 4: the port rides -p, not the destination")
                .containsSequence("-p", String.valueOf(sshd.port()));
            HostKeys.Target ipv6 = HostKeys.parseTarget("ops@[2001:db8::1]:2222");
            assertThat(ipv6.destination()).as("step 4: a bracketed IPv6 target unwraps")
                .isEqualTo("ops@2001:db8::1");
            assertThat(ipv6.port()).as("step 4: with its port split off").isEqualTo(2222);
            assertThat(HostKeys.parseTarget("2001:db8::1").port())
                .as("step 4: while a bare IPv6 literal keeps every colon and takes no port")
                .isZero();

            servers.remove("edge-nokey");
        });
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
