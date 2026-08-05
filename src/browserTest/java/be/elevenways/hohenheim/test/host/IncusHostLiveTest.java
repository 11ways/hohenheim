package be.elevenways.hohenheim.test.host;

import be.elevenways.hohenheim.model.HostTrustSlot;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.server.docker.ServerService;
import be.elevenways.hohenheim.server.host.HostAdmission;
import be.elevenways.hohenheim.server.host.HostKeys;
import be.elevenways.hohenheim.server.host.HostPreflight;
import be.elevenways.hohenheim.server.incus.IncusTrust;
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
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The Incus trust ceremony against a GENUINELY REMOTE daemon (see
 * {@link LiveIncusHost}): the certificate pin, the operator confirmation, the
 * trust-token enrollment of a PRODUCT-minted client identity, the preflight battery
 * over the pinned+identified lane -- and the fail-closed mismatch path with its
 * explicit repin recovery, sharing the exact quarantine machinery of the ssh lane.
 *
 * WHAT IS REAL HERE: another machine, its Incus daemon, its TLS certificate, its
 * trust store. WHAT IS NOT: the reason a pin stops matching -- a freshly minted decoy
 * certificate stands in for a swapped server, because the TLS client compares pin to
 * offer and cannot tell those apart (that equivalence is the point).
 */
class IncusHostLiveTest {

    private static final String HOST = "live-incus";

    private static SqliteDatasource datasource;
    private static LiveIncusHost remote;

    @BeforeAll
    static void setUp() throws Exception {
        remote = LiveIncusHost.configured();
        assumeTrue(remote != null, "no live incus host enrolled at " + LiveIncusHost.CONFIG);

        File db = File.createTempFile("hohenheim-incus-live-test", ".db");
        db.delete();
        db.deleteOnExit();
        datasource = new SqliteDatasource("jdbc:sqlite:" + db.getAbsolutePath());
        new MigrationRunner(datasource).migrate().requireSuccess();
        HohenheimTestRuntime.ensureBooted();
    }

    @AfterAll
    static void tearDown() {
        // nothing global: every test removes its own trust entry and host record
    }

    @Test
    void theTrustCeremonyEnrollmentPreflightAndMismatchAllRunAgainstTheRealDaemon() {
        Db.run(datasource, () -> {
            ServerService servers = new ServerService();
            ServerModel model = Models.get(ServerModel.class);
            Row host = remote.enrol(HOST);
            String enrolledFingerprint = null;
            try {
                // 1. The Docker lane REFUSES this host by name: its declared runtime is
                //    data the dispatch reads, not a mode branch.
                assertThat(catchThrowable(() -> servers.clientFor(HOST)))
                    .as("step 1: a DockerClient for an incus host is a named refusal")
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("incus");

                // 2. Unpinned = not connectable at all, typed as not_pinned before any
                //    TLS trust decision could exist.
                ServerService.Summary unpinned = servers.probeAndStore(HOST);
                assertThat(unpinned.reachable())
                    .as("step 2: an unpinned incus host is not reached").isFalse();
                assertThat(unpinned.errorKind())
                    .as("step 2: and the refusal is not_pinned").isEqualTo("not_pinned");

                // 3. The PRODUCT mints the client identity (no operator key here: the
                //    trust token is how it lands on the daemon later).
                assertThat(IncusTrust.ensureIdentity(host))
                    .as("step 3: a fresh record mints its client certificate").isTrue();
                Row withIdentity = model.findByName(HOST);
                String clientCert = withIdentity.get(HostTrustSlot.INCUS_TLS.clientPublic());
                assertThat(clientCert)
                    .as("step 3: the identity is a PEM certificate, ready to install")
                    .contains("BEGIN CERTIFICATE");
                enrolledFingerprint = IncusTrust.fingerprintOf(clientCert);

                // 4. The scan pins what the daemon offers, and the digest equals the one
                //    read out of band ON the host (incus info's own fingerprint).
                HostKeys.ScanResult scan = IncusTrust.scanAndPin(withIdentity);
                assertThat(scan.outcome()).as("step 4: a first scan pins")
                    .isEqualTo(HostKeys.ScanOutcome.PINNED);
                assertThat(scan.fingerprint())
                    .as("step 4: and it is the host's own certificate_fingerprint")
                    .isEqualTo(remote.fingerprint());

                // 5. A scanned pin is UNVERIFIED and blocks admission by name.
                Row pinned = model.findByName(HOST);
                assertThat((Boolean) pinned.get(ServerModel.INCUS_SERVER_CERT_VERIFIED))
                    .as("step 5: scanned = unverified").isFalse();
                pinned.set(ServerModel.PREFLIGHT_OK, true);
                model.save(pinned);
                assertThat(catchThrowable(() -> HostAdmission.requireAdmittable(pinned)))
                    .as("step 5: admit refuses an unconfirmed certificate")
                    .isInstanceOfSatisfying(Violations.class, violations ->
                        assertThat(violations.all()).anySatisfy(violation ->
                            assertThat(violation.message().key())
                                .isEqualTo("host_key_unverified")));
                IncusTrust.confirm(pinned);

                // 6. BEFORE enrollment the daemon answers but does not trust us, and the
                //    preflight says exactly that: reachability alone must never pass the
                //    trusted check (GET /1.0 answers any TLS client).
                HostPreflight.Report untrusted = HostPreflight.runAndStore(HOST);
                assertThat(untrusted.check("daemon").status())
                    .as("step 6: the daemon IS reachable over the pinned lane: %s",
                        untrusted.check("daemon").detail())
                    .isEqualTo(HostPreflight.STATUS_PASS);
                assertThat(untrusted.check("trusted").status())
                    .as("step 6: but the client is untrusted until enrolled")
                    .isEqualTo(HostPreflight.STATUS_FAIL);
                assertThat(untrusted.passed())
                    .as("step 6: so the verdict fails").isFalse();

                // 7. Trust-token enrollment: the operator mints a ONE-USE token on the
                //    host, the product enrolls its own certificate with it and PROVES
                //    the daemon now answers trusted.
                String token;
                try {
                    token = remote.mintTrustToken("hohenheim-live-test");
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
                IncusTrust.enrollWithToken(model.findByName(HOST), token);

                // 8. The full battery over the now-trusted lane: version facts recorded
                //    (the rolling-release evidence), driver/storage/network verified.
                HostPreflight.Report report = HostPreflight.runAndStore(HOST);
                assertThat(report.check("trusted").status())
                    .as("step 8: the enrolled identity is trusted: %s",
                        report.check("trusted").detail())
                    .isEqualTo(HostPreflight.STATUS_PASS);
                assertThat(String.valueOf(report.facts().get("incus_version")))
                    .as("step 8: the OBSERVED incus version is recorded").isNotBlank();
                assertThat(String.valueOf(report.facts().get("kernel_version")))
                    .as("step 8: the kernel fact is the REMOTE machine's")
                    .isNotEqualTo(System.getProperty("os.version"));
                assertThat(report.check("driver_lxc").status())
                    .as("step 8: lxc driver present: %s", report.check("driver_lxc").detail())
                    .isEqualTo(HostPreflight.STATUS_PASS);
                assertThat(report.check("storage_pool").status())
                    .as("step 8: a created storage pool exists: %s",
                        report.check("storage_pool").detail())
                    .isEqualTo(HostPreflight.STATUS_PASS);
                assertThat(report.check("managed_network").status())
                    .as("step 8: a managed bridge exists: %s",
                        report.check("managed_network").detail())
                    .isEqualTo(HostPreflight.STATUS_PASS);
                assertThat(report.passed()).as("step 8: the battery passes").isTrue();

                // 9. Admission is now legitimately reachable, and the live probe reads
                //    the REMOTE machine's facts over the pinned+identified lane.
                Row ready = model.findByName(HOST);
                HostAdmission.requireAdmittable(ready);
                ready.set(ServerModel.ADMISSION, ServerModel.ADMISSION_ADMITTED);
                model.save(ready);
                ServerService.Summary reached = servers.probeAndStore(HOST);
                assertThat(reached.reachable())
                    .as("step 9: the daemon is reached (%s)", reached.errorDetail()).isTrue();
                assertThat(reached.daemonVersion())
                    .as("step 9: with a real version string").isNotBlank();
                assertThat(reached.memoryBytes())
                    .as("step 9: and real host memory").isGreaterThan(0L);

                // 10. The pin now names a DIFFERENT certificate: fail CLOSED, classified
                //     host_key_changed, quarantined, nothing re-pinned.
                Row seen = model.findByName(HOST);
                String realPin = seen.get(ServerModel.INCUS_SERVER_CERT);
                String decoy = decoyCertificatePem();
                assertThat(decoy).as("step 10: the decoy really is a different certificate")
                    .isNotEqualTo(realPin);
                seen.set(ServerModel.INCUS_SERVER_CERT, decoy);
                seen.set(ServerModel.INCUS_SERVER_CERT_FINGERPRINT,
                    IncusTrust.fingerprintOf(decoy));
                model.save(seen);
                ServerService.Summary changed = servers.probeAndStore(HOST);
                assertThat(changed.reachable())
                    .as("step 10: a pin that does not match the offer reaches nothing")
                    .isFalse();
                assertThat(changed.errorKind())
                    .as("step 10: and classifies as host_key_changed: %s",
                        changed.errorDetail())
                    .isEqualTo("host_key_changed");
                Row quarantined = model.findByName(HOST);
                assertThat((String) quarantined.get(ServerModel.ADMISSION))
                    .as("step 10: the admitted host is quarantined to blocked")
                    .isEqualTo(ServerModel.ADMISSION_BLOCKED);
                assertThat((Boolean) quarantined.get(ServerModel.PREFLIGHT_OK))
                    .as("step 10: its preflight verdict is dropped").isFalse();

                // 11. A rescan records the REAL certificate as EVIDENCE and heals nothing.
                HostKeys.ScanResult rescan = IncusTrust.scanAndPin(quarantined);
                assertThat(rescan.outcome()).as("step 11: rescan reports MISMATCH")
                    .isEqualTo(HostKeys.ScanOutcome.MISMATCH);
                assertThat(rescan.fingerprint())
                    .as("step 11: naming the certificate the real daemon offers")
                    .isEqualTo(remote.fingerprint());
                Row afterRescan = model.findByName(HOST);
                assertThat((String) afterRescan.get(ServerModel.INCUS_SERVER_CERT))
                    .as("step 11: and STILL nothing re-pinned it").isEqualTo(decoy);

                // 12. Recovery is the explicit repin, landing back at the bottom of the
                //     ceremony, after which the daemon is reachable again.
                IncusTrust.repin(afterRescan);
                Row repinned = model.findByName(HOST);
                assertThat((String) repinned.get(ServerModel.INCUS_SERVER_CERT_FINGERPRINT))
                    .as("step 12: the repin adopts the real certificate")
                    .isEqualTo(remote.fingerprint());
                assertThat((Boolean) repinned.get(ServerModel.INCUS_SERVER_CERT_VERIFIED))
                    .as("step 12: unconfirmed again").isFalse();
                ServerService.Summary recovered = servers.probeAndStore(HOST);
                assertThat(recovered.reachable())
                    .as("step 12: the re-pinned lane reaches the daemon again (%s)",
                        recovered.errorDetail())
                    .isTrue();
            } finally {
                if (enrolledFingerprint != null) {
                    try {
                        remote.removeTrustEntry(enrolledFingerprint);
                    } catch (IOException cleanup) {
                        // an unenrolled run has nothing to remove
                    }
                }
                servers.remove(HOST);
            }
        });
    }

    /** A freshly minted, genuinely valid certificate that is NOT the daemon's. */
    private static String decoyCertificatePem() {
        Path directory = null;
        try {
            directory = Files.createTempDirectory("hohenheim-decoy-cert");
            Path key = directory.resolve("decoy.key");
            Path cert = directory.resolve("decoy.crt");
            Process process = new ProcessBuilder("openssl", "req", "-x509", "-newkey", "ec",
                "-pkeyopt", "ec_paramgen_curve:prime256v1", "-sha384", "-days", "1",
                "-nodes", "-subj", "/CN=decoy",
                "-keyout", key.toString(), "-out", cert.toString())
                .redirectErrorStream(true).start();
            if (!process.waitFor(30, TimeUnit.SECONDS) || process.exitValue() != 0) {
                throw new IOException("openssl did not produce a decoy certificate");
            }
            return Files.readString(cert, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("could not mint a decoy certificate", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted minting a decoy certificate", e);
        } finally {
            deleteTree(directory);
        }
    }

    private static void deleteTree(Path root) {
        if (root == null || !Files.exists(root)) {
            return;
        }
        try (var walk = Files.walk(root)) {
            List<Path> paths = walk.toList();
            for (int index = paths.size() - 1; index >= 0; index--) {
                Files.deleteIfExists(paths.get(index));
            }
        } catch (IOException ignored) {
            // a leftover temp directory is not worth failing a green run over
        }
    }
}
