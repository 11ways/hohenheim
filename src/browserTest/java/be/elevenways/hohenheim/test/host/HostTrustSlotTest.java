package be.elevenways.hohenheim.test.host;

import be.elevenways.hohenheim.model.HostTrustSlot;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.server.host.HostAdmission;
import be.elevenways.hohenheim.server.host.HostKeys;
import be.elevenways.hohenheim.server.host.HostPins;
import be.elevenways.hohenheim.server.incus.IncusKernelIsolation;
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
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * The two INDEPENDENT trust relationships of one host record, with no daemon anywhere:
 * an Incus host's TLS pin and its ssh admin lane live in separate columns, neither can be
 * written into the other's slot, and each answers its own gates on its own terms.
 *
 * AIDEV-NOTE: this is the regression guard for the overloaded column. {@code host_key}
 * used to hold an ssh known_hosts line OR an Incus certificate PEM depending on runtime,
 * which cost a special case in the backup gate (a PEM handed to the known_hosts writer
 * materialises a pin ssh can never match) and made an Incus host's kernel-truth
 * verification impossible, because the lane it needed had nowhere to live.
 */
class HostTrustSlotTest {

    /** A syntactically real known_hosts line; nothing here ever connects. */
    private static final String SSH_KEY =
        "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";
    private static final String OTHER_SSH_KEY =
        "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB";
    private static final String SERVER_PEM =
        "-----BEGIN CERTIFICATE-----\nMIIBdaemoncertificate\n-----END CERTIFICATE-----\n";
    private static final String CLIENT_PEM =
        "-----BEGIN CERTIFICATE-----\nMIIBclientcertificate\n-----END CERTIFICATE-----\n";

    private static SqliteDatasource datasource;

    @BeforeAll
    static void setUp() throws Exception {
        File db = File.createTempFile("hohenheim-host-trust-slot-test", ".db");
        db.delete();
        db.deleteOnExit();
        datasource = new SqliteDatasource("jdbc:sqlite:" + db.getAbsolutePath());
        new MigrationRunner(datasource).migrate().requireSuccess();
        HohenheimTestRuntime.ensureBooted();
    }

    @Test
    void anIncusHostCarriesTlsAndSshTrustSideBySideWithoutEitherDisplacingTheOther() {
        Db.run(datasource, () -> {
            ServerModel model = Models.get(ServerModel.class);
            Row host = model.createEmptyRow();
            host.set(ServerModel.NAME, "slot-incus");
            host.set(ServerModel.RUNTIME, ServerModel.RUNTIME_INCUS);
            host.set(ServerModel.INCUS_URL, "https://192.0.2.9:8443");
            host.set(ServerModel.MODE, ServerModel.MODE_LOCAL);
            model.save(host);

            // 1. The daemon's TLS trust, complete and confirmed. The ssh slot is still
            //    EMPTY -- which before the split was impossible to express, because the
            //    certificate occupied the only columns there were.
            HostPins.apply(host, HostTrustSlot.INCUS_TLS, SERVER_PEM, "sha256:daemon");
            Row tlsPinned = model.findByName("slot-incus");
            tlsPinned.set(HostTrustSlot.INCUS_TLS.clientPublic(), CLIENT_PEM);
            tlsPinned.set(HostTrustSlot.INCUS_TLS.clientPrivate(), "-----BEGIN PRIVATE KEY-----");
            model.save(tlsPinned);
            HostPins.confirm(model.findByName("slot-incus"), HostTrustSlot.INCUS_TLS);

            Row tlsOnly = model.findByName("slot-incus");
            assertThat(HostTrustSlot.INCUS_TLS.isConfirmed(tlsOnly))
                .as("step 1: the daemon lane is pinned and confirmed").isTrue();
            assertThat((String) tlsOnly.get(ServerModel.HOST_KEY))
                .as("step 1: and NOTHING landed in the ssh slot").isNull();
            HostAdmission.requireVerifiedIdentity(tlsOnly);
            assertThat(catchThrowable(() -> HostKeys.sshArgv(tlsOnly, List.of("true"))))
                .as("step 1: an ssh argv is refused: a certificate is not a host key")
                .isInstanceOfSatisfying(HostKeys.HostTrustException.class, refusal ->
                    assertThat(refusal.kind.token).isEqualTo("not_pinned"));

            // 2. No ssh target = no admin lane, so kernel truth is UNAVAILABLE and the
            //    host is honestly unverifiable rather than assumed safe.
            assertThat(ServerModel.hasSshLane(tlsOnly))
                .as("step 2: no ssh target means no lane").isFalse();
            assertThat(IncusKernelIsolation.forServer(tlsOnly).available())
                .as("step 2: so kernel truth cannot be read").isFalse();
            assertThat(catchThrowable(() -> HostAdmission.requireBackupDestination(tlsOnly)))
                .as("step 2: and it is not a backup destination either")
                .isInstanceOfSatisfying(Violations.class, violations ->
                    assertThat(violations.all()).anySatisfy(violation ->
                        assertThat(violation.message().key()).isEqualTo("host_not_ssh")));

            // 3. A DECLARED target is not yet a TRUSTED lane: unpinned, then pinned but
            //    unconfirmed, both stay unavailable. Nothing is verifiable until a human
            //    compared a fingerprint.
            Row targeted = model.findByName("slot-incus");
            targeted.set(ServerModel.SSH_TARGET, "root@192.0.2.9");
            model.save(targeted);
            assertThat(IncusKernelIsolation.forServer(model.findByName("slot-incus")).available())
                .as("step 3: a target without a pin is not a lane").isFalse();

            HostPins.apply(model.findByName("slot-incus"), HostTrustSlot.SSH, SSH_KEY,
                HostKeys.fingerprintOf(SSH_KEY));
            Row sshPinned = model.findByName("slot-incus");
            sshPinned.set(HostTrustSlot.SSH.clientPrivate(), "-----BEGIN OPENSSH PRIVATE KEY-----");
            sshPinned.set(HostTrustSlot.SSH.clientPublic(), SSH_KEY);
            model.save(sshPinned);
            assertThat(IncusKernelIsolation.forServer(model.findByName("slot-incus")).available())
                .as("step 3: an UNCONFIRMED pin is not a lane either").isFalse();

            // 4. Confirmed: the lane opens, the argv carries the ssh key line, and the
            //    daemon's certificate is exactly where it was.
            HostPins.confirm(model.findByName("slot-incus"), HostTrustSlot.SSH);
            Row both = model.findByName("slot-incus");
            assertThat(IncusKernelIsolation.forServer(both).available())
                .as("step 4: kernel truth is readable through the product's own lane")
                .isTrue();
            assertThat(String.join(" ", HostKeys.sshArgv(both, List.of("nft"))))
                .as("step 4: and the argv is the pinned one, aimed at the ssh target")
                .contains("StrictHostKeyChecking=yes")
                .contains("HostKeyAlias=hohenheim-host-" + both.get(ServerModel.ID))
                .contains("root@192.0.2.9");
            assertThat((String) both.get(ServerModel.INCUS_SERVER_CERT))
                .as("step 4: pinning the ssh lane displaced no TLS material")
                .isEqualTo(SERVER_PEM);
            assertThat((Boolean) both.get(ServerModel.INCUS_SERVER_CERT_VERIFIED))
                .as("step 4: nor its confirmation").isTrue();
            assertThat((String) both.get(ServerModel.HOST_KEY))
                .as("step 4: and the ssh slot holds a key line, never a PEM")
                .doesNotContain("CERTIFICATE");
            HostAdmission.requireBackupDestination(both);

            // 5. A changed ssh host key closes the admin lane and the backup gate. The
            //    EVIDENCE is per-slot -- the offer lands in the ssh slot and re-pins
            //    nothing, the TLS material is untouched -- but the VERDICT "this machine
            //    contradicted its identity" is deliberately record-wide: both lanes
            //    address the same machine, so a contradiction on either closes both until
            //    an operator re-pins. Fail-closed on the strongest boundary we have.
            HostKeys.ScanResult mismatch = HostPins.apply(both, HostTrustSlot.SSH,
                OTHER_SSH_KEY, HostKeys.fingerprintOf(OTHER_SSH_KEY));
            assertThat(mismatch.outcome())
                .as("step 5: a different offer is a MISMATCH, and re-pins nothing")
                .isEqualTo(HostKeys.ScanOutcome.MISMATCH);
            Row quarantined = model.findByName("slot-incus");
            assertThat((String) quarantined.get(ServerModel.HOST_KEY))
                .as("step 5: the pin still names the original key").isEqualTo(SSH_KEY);
            assertThat(quarantined.get(HostTrustSlot.SSH.offered()))
                .as("step 5: the offered key is evidence in the SSH slot")
                .isEqualTo(OTHER_SSH_KEY);
            assertThat(quarantined.get(HostTrustSlot.INCUS_TLS.offered()))
                .as("step 5: and no evidence was written into the TLS slot")
                .isNull();
            assertThat((String) quarantined.get(ServerModel.INCUS_SERVER_CERT))
                .as("step 5: whose certificate is byte-for-byte what it was")
                .isEqualTo(SERVER_PEM);
            assertThat(HostPins.isQuarantined(quarantined, HostTrustSlot.SSH))
                .as("step 5: the ssh slot is quarantined").isTrue();
            assertThat(catchThrowable(() ->
                    HostAdmission.requireVerifiedIdentity(quarantined)))
                .as("step 5: and the daemon transport is closed too, on purpose")
                .isInstanceOfSatisfying(Violations.class, violations ->
                    assertThat(violations.all()).anySatisfy(violation ->
                        assertThat(violation.message().key()).isEqualTo("host_quarantined")));
            assertThat(IncusKernelIsolation.forServer(quarantined).available())
                .as("step 5: an untrusted lane leaves the host unverifiable again")
                .isFalse();
            assertThat(catchThrowable(() ->
                    HostAdmission.requireBackupDestination(quarantined)))
                .as("step 5: and refuses to hold backups")
                .isInstanceOfSatisfying(Violations.class, violations ->
                    assertThat(violations.all()).anySatisfy(violation ->
                        assertThat(violation.message().key()).isEqualTo("host_quarantined")));

            // 6. The repin ceremony is per slot too: adopting the offered ssh key drops
            //    the ssh confirmation and touches no TLS column.
            HostPins.repin(quarantined, HostTrustSlot.SSH, HostKeys::fingerprintOf);
            Row repinned = model.findByName("slot-incus");
            assertThat((String) repinned.get(ServerModel.HOST_KEY))
                .as("step 6: the ssh slot adopted the offered key").isEqualTo(OTHER_SSH_KEY);
            assertThat((Boolean) repinned.get(ServerModel.HOST_KEY_VERIFIED))
                .as("step 6: unconfirmed again, at the bottom of the ceremony").isFalse();
            assertThat((Boolean) repinned.get(ServerModel.INCUS_SERVER_CERT_VERIFIED))
                .as("step 6: while the daemon lane stays confirmed").isTrue();
            assertThat((String) repinned.get(ServerModel.INCUS_SERVER_CERT))
                .as("step 6: with its certificate intact").isEqualTo(SERVER_PEM);
        });
    }
}
