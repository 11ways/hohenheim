package be.elevenways.hohenheim.test.host;

import be.elevenways.hohenheim.server.host.HostAdmission;
import be.elevenways.hohenheim.server.host.HostKeys;
import be.elevenways.hohenheim.server.host.HostPins;
import be.elevenways.hohenheim.server.host.HostPreflight;
import be.elevenways.hohenheim.server.host.HostProbe;
import be.elevenways.hohenheim.server.host.IncusPreflight;

import be.elevenways.hohenheim.model.HostTrustSlot;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.test.HohenheimTestRuntime;
import be.elevenways.zenit.common.orm.datasource.Datasources;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * A trust verdict may only be cleared by a trust ACT: no probe outcome, however
 * successful, lifts a quarantine, and both markers keep answering for the paths that
 * write them.
 *
 * AIDEV-NOTE: the defect this pins. {@code last_error_kind} was BOTH the last transient
 * probe outcome and the sticky quarantine token, so a host quarantined because its TLS
 * fingerprint or ssh host key CONTRADICTED its pin -- a security verdict -- had that
 * verdict erased by the next unrelated probe that happened to reach the daemon. The
 * earlier wave removed one automatic trigger (the hourly Docker sweep restamping every
 * Incus host UNREACHABLE) and left the shape open; this closes it.
 */
class HostQuarantineStickinessTest {

    private static final String SSH_KEY =
        "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";
    private static final String OTHER_SSH_KEY =
        "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB";

    private static SqliteDatasource datasource;

    @BeforeAll
    static void setUp() throws Exception {
        File db = File.createTempFile("hohenheim-quarantine-stickiness-test", ".db");
        db.delete();
        db.deleteOnExit();
        datasource = new SqliteDatasource("jdbc:sqlite:" + db.getAbsolutePath());
        new MigrationRunner(datasource).migrate().requireSuccess();
        Datasources.register(Datasources.DEFAULT, datasource);
        HohenheimTestRuntime.ensureBooted();
    }

    @Test
    void onlyARepinLiftsAQuarantineAndNoProbeOutcomeEverDoes() {
        Db.run(datasource, () -> {
            ServerModel model = Models.get(ServerModel.class);
            Row host = model.createEmptyRow();
            host.set(ServerModel.NAME, "sticky-host");
            host.set(ServerModel.RUNTIME, ServerModel.RUNTIME_DOCKER);
            host.set(ServerModel.MODE, ServerModel.MODE_SSH);
            host.set(ServerModel.SSH_TARGET, "root@192.0.2.44");
            host.set(ServerModel.ADMISSION, ServerModel.ADMISSION_ADMITTED);
            host.set(ServerModel.PREFLIGHT_OK, true);
            model.save(host);
            HostPins.apply(model.findByName("sticky-host"), HostTrustSlot.SSH, SSH_KEY,
                HostKeys.fingerprintOf(SSH_KEY));
            HostPins.confirm(model.findByName("sticky-host"), HostTrustSlot.SSH);

            // 1. POSITIVE ANCHOR: a confirmed, unquarantined host passes the trust gates
            //    and a successful probe leaves it that way.
            HostProbe.recordSuccess("sticky-host");
            Row healthy = model.findByName("sticky-host");
            assertThat(HostPins.isQuarantined(healthy, HostTrustSlot.SSH))
                .as("step 1: a confirmed host with no contradiction is not quarantined")
                .isFalse();
            HostAdmission.requireBackupDestination(healthy);
            HostAdmission.requireVerifiedIdentity(healthy);

            // 2. A REFUSED CONNECTION whose identity contradicted the pin: the typed verdict
            //    is stamped in its own column, the host is downgraded, and no material was
            //    re-pinned or even offered -- this is the path that writes NO offer.
            HostProbe.recordFailure("sticky-host", HostProbe.Outcome.failure(
                HostProbe.FailureKind.HOST_KEY_CHANGED,
                "remote host identification has changed"));
            Row quarantined = model.findByName("sticky-host");
            Instant stampedAt = quarantined.get(ServerModel.QUARANTINED_AT);
            assertThat(stampedAt)
                .as("step 2: the quarantine verdict has a column of its own").isNotNull();
            assertThat((String) quarantined.get(ServerModel.QUARANTINE_REASON))
                .as("step 2: with the reason kept beside it")
                .contains("identification has changed");
            assertThat(HostTrustSlot.SSH.offeredOf(quarantined))
                .as("step 2: and nothing was offered -- nobody rescanned").isEmpty();
            assertThat(HostPins.isQuarantined(quarantined, HostTrustSlot.SSH))
                .as("step 2: the host is quarantined").isTrue();
            assertThat((String) quarantined.get(ServerModel.ADMISSION))
                .as("step 2: an admitted host is downgraded")
                .isEqualTo(ServerModel.ADMISSION_BLOCKED);

            // 3. THE DEFECT. A later probe REACHES the daemon. That clears the transient
            //    error, and nothing else: reaching a daemon says nothing about WHICH
            //    machine answered, which is the exact question the pin decides.
            HostProbe.recordSuccess("sticky-host");
            Row afterSuccess = model.findByName("sticky-host");
            assertThat((String) afterSuccess.get(ServerModel.LAST_ERROR_KIND))
                .as("step 3: the TRANSIENT probe verdict is cleared, as it should be")
                .isNull();
            assertThat(HostPins.isQuarantined(afterSuccess, HostTrustSlot.SSH))
                .as("step 3: but a successful probe does NOT clear a trust verdict")
                .isTrue();
            assertThat((Instant) afterSuccess.get(ServerModel.QUARANTINED_AT))
                .as("step 3: the stamp is untouched").isEqualTo(stampedAt);
            assertThat(catchThrowable(() ->
                    HostAdmission.requireVerifiedIdentity(afterSuccess)))
                .as("step 3: and every trust gate still refuses BY NAME")
                .isInstanceOfSatisfying(Violations.class, violations ->
                    assertThat(violations.all()).anySatisfy(violation ->
                        assertThat(violation.message().key()).isEqualTo("host_quarantined")));
            assertThat(catchThrowable(() ->
                    HostAdmission.requireBackupDestination(afterSuccess)))
                .as("step 3: including the backup destination gate")
                .isInstanceOfSatisfying(Violations.class, violations ->
                    assertThat(violations.all()).anySatisfy(violation ->
                        assertThat(violation.message().key()).isEqualTo("host_quarantined")));

            // 4. A WEAKER failure does not clear it either -- a key-changed host that is
            //    then merely powered off used to lose its verdict on the next sweep.
            HostProbe.recordFailure("sticky-host", HostProbe.Outcome.failure(
                HostProbe.FailureKind.UNREACHABLE, "connection refused"));
            Row afterWeaker = model.findByName("sticky-host");
            assertThat((String) afterWeaker.get(ServerModel.LAST_ERROR_KIND))
                .as("step 4: the transient verdict is now the weaker one")
                .isEqualTo(HostProbe.FailureKind.UNREACHABLE.token);
            assertThat(HostPins.isQuarantined(afterWeaker, HostTrustSlot.SSH))
                .as("step 4: and the quarantine outlives it").isTrue();
            assertThat((String) afterWeaker.get(ServerModel.QUARANTINE_REASON))
                .as("step 4: the quarantine reason survives last_error being overwritten")
                .contains("identification has changed");

            // 5. THE TRUST ACT. A repin adopts what the machine now presents -- but there
            //    is nothing to adopt on this path, so the ceremony refuses by name: an
            //    operator must rescan first, exactly as the copy tells them.
            assertThat(catchThrowable(() -> HostPins.repin(model.findByName("sticky-host"),
                    HostTrustSlot.SSH, HostKeys::fingerprintOf)))
                .as("step 5: a quarantine with no offer cannot be repinned blindly")
                .isInstanceOfSatisfying(Violations.class, violations ->
                    assertThat(violations.all()).anySatisfy(violation ->
                        assertThat(violation.message().key()).isEqualTo("host_key_no_offer")));

            // 6. The OTHER path writes the other marker: a rescan that disagrees stores the
            //    offer in the slot AND stamps the same record-wide verdict. Two markers,
            //    two writers, one question.
            HostPins.apply(model.findByName("sticky-host"), HostTrustSlot.SSH,
                OTHER_SSH_KEY, HostKeys.fingerprintOf(OTHER_SSH_KEY));
            Row rescanned = model.findByName("sticky-host");
            assertThat(HostTrustSlot.SSH.offeredOf(rescanned))
                .as("step 6: the disagreeing rescan stored its evidence in the slot")
                .isEqualTo(OTHER_SSH_KEY);
            assertThat((String) rescanned.get(ServerModel.HOST_KEY))
                .as("step 6: and re-pinned NOTHING").isEqualTo(SSH_KEY);
            assertThat(HostPins.isQuarantined(rescanned, HostTrustSlot.SSH))
                .as("step 6: still quarantined, now by both markers").isTrue();

            // 7. The repin clears BOTH markers, and only the repin ever does. The host
            //    lands at the bottom of the ceremony: unconfirmed, unpreflighted.
            HostPins.repin(rescanned, HostTrustSlot.SSH, HostKeys::fingerprintOf);
            Row repinned = model.findByName("sticky-host");
            assertThat((Instant) repinned.get(ServerModel.QUARANTINED_AT))
                .as("step 7: the verdict is lifted by the trust act").isNull();
            assertThat((String) repinned.get(ServerModel.QUARANTINE_REASON))
                .as("step 7: reason and all").isNull();
            assertThat(HostTrustSlot.SSH.offeredOf(repinned))
                .as("step 7: and the offer marker is consumed").isEmpty();
            assertThat(HostPins.isQuarantined(repinned, HostTrustSlot.SSH))
                .as("step 7: so the host is out of quarantine").isFalse();
            assertThat(catchThrowable(() -> HostAdmission.requireBackupDestination(repinned)))
                .as("step 7: but not yet trusted -- the new pin is unconfirmed")
                .isInstanceOfSatisfying(Violations.class, violations ->
                    assertThat(violations.all()).anySatisfy(violation ->
                        assertThat(violation.message().key()).isEqualTo("host_key_unverified")));

            // 8. POSITIVE ANCHOR at the end of the ceremony: confirming the adopted key
            //    restores the host, so step 3's refusal was about the quarantine and not
            //    about refusing everything.
            HostPins.confirm(model.findByName("sticky-host"), HostTrustSlot.SSH);
            Row restored = model.findByName("sticky-host");
            HostAdmission.requireBackupDestination(restored);
            HostAdmission.requireVerifiedIdentity(restored);
            assertThat((String) restored.get(ServerModel.HOST_KEY))
                .as("step 8: the pin is the key the machine offered").isEqualTo(OTHER_SSH_KEY);
        });
    }
}
