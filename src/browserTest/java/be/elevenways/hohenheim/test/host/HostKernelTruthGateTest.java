package be.elevenways.hohenheim.test.host;

import be.elevenways.hohenheim.server.host.HostAdmission;
import be.elevenways.hohenheim.server.host.HostKeys;
import be.elevenways.hohenheim.server.host.HostPins;
import be.elevenways.hohenheim.server.host.HostPreflight;
import be.elevenways.hohenheim.server.host.HostProbe;
import be.elevenways.hohenheim.server.host.IncusPreflight;

import be.elevenways.hohenheim.instance.WorkloadIsolation;
import be.elevenways.hohenheim.model.HostTrustSlot;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.test.HohenheimTestRuntime;
import be.elevenways.hohenheim.test.TestDatabases;
import be.elevenways.zenit.common.orm.datasource.Datasources;
import be.elevenways.zenit.common.orm.datasource.Db;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.validation.Violations;
import be.elevenways.zenit.common.orm.datasource.sql.SqlDatasource;
import be.elevenways.zenit.server.orm.migration.MigrationRunner;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * Kernel-truth isolation verification as an ADMISSION REQUIREMENT: a host that accepts
 * tenant workloads must be able to prove its workload isolation in the kernel it runs
 * them on, while a host that accepts none stays fully usable without a lane.
 *
 * AIDEV-NOTE: this is the regression guard for a silent skip that cost three sessions.
 * {@code IncusKernelIsolation.runnerFor} returns null for an https daemon with no trusted
 * ssh lane, {@code available()} then answers false and every verification call returned
 * SILENTLY -- so a real isolation loss surfaced as a 1-in-7 "flake" with nothing watching.
 * The gate must therefore refuse BY NAME, and the evidence it reads must be a probe that
 * actually transacted on the kernel, never {@code available()}, which is a claim.
 *
 * No daemon is contacted anywhere in this class: every gate here is a decision over stored
 * record state, which is exactly the property that lets it run on the create path.
 */
class HostKernelTruthGateTest {

    private static final String SSH_KEY =
        "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";
    private static final String SERVER_PEM =
        "-----BEGIN CERTIFICATE-----\nMIIBdaemoncertificate\n-----END CERTIFICATE-----\n";

    /** No host here is DEDICATED, so the owner bucket is not what these steps are about. */
    private static final String BUCKET = "kernel-gate-owner";

    private static SqlDatasource datasource;

    @BeforeAll
    static void setUp() throws Exception {
        datasource = TestDatabases.freshDatasource();
        HohenheimTestRuntime.ensureBooted();
    }

    @Test
    void tenantAcceptingHostsMustProveKernelTruthWhileOperatorOnlyHostsNeedNoLane() {
        Db.run(datasource, () -> {
            ServerModel model = Models.get(ServerModel.class);
            Row host = model.createEmptyRow();
            host.set(ServerModel.NAME, "gate-incus");
            host.set(ServerModel.RUNTIME, ServerModel.RUNTIME_INCUS);
            host.set(ServerModel.INCUS_URL, "https://192.0.2.31:8443");
            host.set(ServerModel.MODE, ServerModel.MODE_LOCAL);
            host.set(ServerModel.POSTURE, ServerModel.POSTURE_TRUSTED_ONLY);
            model.save(host);
            HostPins.apply(model.findByName("gate-incus"), HostTrustSlot.INCUS_TLS,
                SERVER_PEM, "sha256:daemon");
            HostPins.confirm(model.findByName("gate-incus"), HostTrustSlot.INCUS_TLS);
            markPreflightPassed("gate-incus", null);

            // 1. POSITIVE ANCHOR, and the recorded backup-target decision: an operator-only
            //    host with NO lane at all still admits. Verification is required to accept
            //    tenants, never to be trusted or to hold data.
            Row operatorOnly = model.findByName("gate-incus");
            assertThat(ServerModel.acceptsTenantWorkloads(operatorOnly))
                .as("step 1: a trusted-only posture accepts no tenant workloads").isFalse();
            HostAdmission.requireAdmittable(operatorOnly);
            HostAdmission.requireKernelTruth(operatorOnly);

            // 2. Widening the posture to accept tenants closes the gate IMMEDIATELY, by
            //    name, with no re-probe needed: the record now claims something it cannot
            //    back up. The refusal names the host and says what to do about it.
            Row widened = model.findByName("gate-incus");
            widened.set(ServerModel.POSTURE, ServerModel.POSTURE_VM_ISOLATED);
            model.save(widened);
            Row tenanted = model.findByName("gate-incus");
            assertThat(catchThrowable(() -> HostAdmission.requireAdmittable(tenanted)))
                .as("step 2: admitting a tenant-accepting host with no kernel lane is refused")
                .isInstanceOfSatisfying(Violations.class, violations ->
                    assertThat(violations.all()).anySatisfy(violation ->
                        assertThat(violation.message().key())
                            .isEqualTo("host_kernel_lane_missing")));

            // 3. The SAME refusal on the placement path, and it is the LIVE gate: this host
            //    is stored as ADMITTED (an operator admitted it before the requirement
            //    existed) and placement still refuses, while the admission column is left
            //    alone -- silently cordoning a running host is not this gate's call.
            Row admitted = model.findByName("gate-incus");
            admitted.set(ServerModel.ADMISSION, ServerModel.ADMISSION_ADMITTED);
            model.save(admitted);
            int serverId = model.findByName("gate-incus").get(ServerModel.ID);
            assertThat(catchThrowable(() -> HostAdmission.requireInstancePlacement(serverId,
                WorkloadIsolation.VIRTUAL_MACHINE, BUCKET)))
                .as("step 3: an already-admitted host that cannot verify takes no NEW tenant")
                .isInstanceOfSatisfying(Violations.class, violations ->
                    assertThat(violations.all()).anySatisfy(violation ->
                        assertThat(violation.message().key())
                            .isEqualTo("host_kernel_lane_missing")));
            assertThat((String) model.findByName("gate-incus").get(ServerModel.ADMISSION))
                .as("step 3: and the stored admission decision is NOT rewritten behind the"
                    + " operator's back")
                .isEqualTo(ServerModel.ADMISSION_ADMITTED);

            // 4. A trusted lane is still only a CLAIM. Pinned and confirmed ssh, so the
            //    verifier could be built -- and the gate keeps refusing, because the last
            //    preflight never proved the kernel was reachable through it.
            Row targeted = model.findByName("gate-incus");
            targeted.set(ServerModel.SSH_TARGET, "root@192.0.2.31");
            model.save(targeted);
            HostPins.apply(model.findByName("gate-incus"), HostTrustSlot.SSH, SSH_KEY,
                HostKeys.fingerprintOf(SSH_KEY));
            HostPins.confirm(model.findByName("gate-incus"), HostTrustSlot.SSH);
            markPreflightPassed("gate-incus", null);
            assertThat(catchThrowable(() ->
                    HostAdmission.requireInstancePlacement(serverId,
                WorkloadIsolation.VIRTUAL_MACHINE, BUCKET)))
                .as("step 4: a declared lane without kernel EVIDENCE is not verification")
                .isInstanceOfSatisfying(Violations.class, violations ->
                    assertThat(violations.all()).anySatisfy(violation ->
                        assertThat(violation.message().key())
                            .isEqualTo("host_kernel_lane_unproven")));

            // 5. A preflight whose kernel_isolation_lane check actually transacted on the
            //    kernel opens both gates. POSITIVE ANCHOR for the whole mechanism: without
            //    this the refusals above could be refusing everything.
            markPreflightPassed("gate-incus", HostPreflight.STATUS_PASS);
            Row proven = model.findByName("gate-incus");
            HostAdmission.requireAdmittable(proven);
            HostAdmission.requireInstancePlacement(serverId,
                WorkloadIsolation.VIRTUAL_MACHINE, BUCKET);
            assertThat(HostPreflight.storedCheckStatus(proven, IncusPreflight.KERNEL_LANE_CHECK))
                .as("step 5: the evidence the gate read is the STORED probe verdict")
                .isEqualTo(HostPreflight.STATUS_PASS);

            // 6. A FAILING stored probe is not evidence either: the lane is still declared
            //    and trusted, so only the probe verdict distinguishes these two states.
            markPreflightPassed("gate-incus", HostPreflight.STATUS_FAIL);
            assertThat(catchThrowable(() -> HostAdmission.requireInstancePlacement(serverId,
                WorkloadIsolation.VIRTUAL_MACHINE, BUCKET)))
                .as("step 6: a lane that FAILED its probe verifies nothing")
                .isInstanceOfSatisfying(Violations.class, violations ->
                    assertThat(violations.all()).anySatisfy(violation ->
                        assertThat(violation.message().key())
                            .isEqualTo("host_kernel_lane_unproven")));

            // 7. Narrowing the posture back is the operator's other honest way out: an
            //    operator-only host needs no verification, so admit works again even with
            //    the failing probe still stored.
            Row narrowed = model.findByName("gate-incus");
            narrowed.set(ServerModel.POSTURE, ServerModel.POSTURE_TRUSTED_ONLY);
            model.save(narrowed);
            HostAdmission.requireAdmittable(model.findByName("gate-incus"));
            assertThat(catchThrowable(() -> HostAdmission.requireInstancePlacement(serverId,
                WorkloadIsolation.VIRTUAL_MACHINE, BUCKET)))
                .as("step 7: and placement refuses for the POSTURE now, not the lane")
                .isInstanceOfSatisfying(Violations.class, violations ->
                    assertThat(violations.all()).anySatisfy(violation ->
                        assertThat(violation.message().key())
                            .isEqualTo("host_posture_refuses")));
        });
    }

    @Test
    void aDockerHostIsGatedByItsOwnRequiredNftablesProbeAndNotByThisOne() {
        Db.run(datasource, () -> {
            ServerModel model = Models.get(ServerModel.class);
            Row host = model.createEmptyRow();
            host.set(ServerModel.NAME, "gate-docker");
            host.set(ServerModel.RUNTIME, ServerModel.RUNTIME_DOCKER);
            host.set(ServerModel.MODE, ServerModel.MODE_LOCAL);
            host.set(ServerModel.POSTURE, ServerModel.POSTURE_SHARED_CONTAINER);
            model.save(host);
            HostFixtures.acknowledgePosture(host);
            markPreflightPassed("gate-docker", null);

            // 1. No kernel_isolation_lane check exists on a Docker report at all, and the
            //    gate passes: that battery's own REQUIRED nftables probe already proves the
            //    same kernel access, and a second gate over the same evidence is theater.
            Row docker = model.findByName("gate-docker");
            assertThat(ServerModel.acceptsTenantWorkloads(docker))
                .as("step 1: this host does accept tenant workloads").isTrue();
            assertThat(HostPreflight.storedCheckStatus(docker, IncusPreflight.KERNEL_LANE_CHECK))
                .as("step 1: and stores no incus kernel-lane verdict").isNull();
            HostAdmission.requireKernelTruth(docker);
            HostAdmission.requireAdmittable(docker);
        });
    }

    /**
     * POSTURE ESCALATION: a preflight's verdict answers the posture that was in force
     * when it RAN, and the posture can move afterwards with nothing re-probing.
     *
     * The concrete hole this drives: {@code container_userns} and {@code container_seccomp}
     * are required exactly when the host accepts tenant workloads, that flag is frozen into
     * the stored report, and {@code preflight_ok} counts only the checks that were required
     * THEN. So a host probed as trusted_only banks two advisory FAILs and a true
     * {@code preflight_ok}, and a later flip to shared_container hands it tenant workloads
     * on a kernel already measured as giving them the host's own uid range and no seccomp
     * filter. The attack needs no daemon and no race -- it is one posture edit.
     */
    @Test
    void aPostureFlipRegatesTheChecksTheLastPreflightOnlyTreatedAsAdvisory() {
        Db.run(datasource, () -> {
            ServerModel model = Models.get(ServerModel.class);
            Row host = model.createEmptyRow();
            host.set(ServerModel.NAME, "gate-posture");
            host.set(ServerModel.RUNTIME, ServerModel.RUNTIME_INCUS);
            host.set(ServerModel.INCUS_URL, "https://192.0.2.32:8443");
            host.set(ServerModel.MODE, ServerModel.MODE_LOCAL);
            host.set(ServerModel.SSH_TARGET, "root@192.0.2.32");
            host.set(ServerModel.POSTURE, ServerModel.POSTURE_TRUSTED_ONLY);
            model.save(host);
            HostPins.apply(model.findByName("gate-posture"), HostTrustSlot.INCUS_TLS,
                SERVER_PEM, "sha256:daemon");
            HostPins.confirm(model.findByName("gate-posture"), HostTrustSlot.INCUS_TLS);
            HostPins.apply(model.findByName("gate-posture"), HostTrustSlot.SSH, SSH_KEY,
                HostKeys.fingerprintOf(SSH_KEY));
            HostPins.confirm(model.findByName("gate-posture"), HostTrustSlot.SSH);

            // 1. The probe as the Incus battery really writes it on a TRUSTED-ONLY host:
            //    required-ness follows acceptsTenantWorkloads, so all three posture-driven
            //    checks land non-required and two of them are honest FAILs. The report
            //    still PASSES, because passed() counts required failures only.
            storePostureReport("gate-posture", false,
                HostPreflight.STATUS_PASS, HostPreflight.STATUS_FAIL, HostPreflight.STATUS_FAIL);
            Row probed = model.findByName("gate-posture");
            assertThat((Boolean) probed.get(ServerModel.PREFLIGHT_OK))
                .as("step 1: the stored verdict really is a PASS -- that is the whole trap")
                .isTrue();
            assertThat(HostPreflight.storedCheckStatus(probed, IncusPreflight.USERNS_CHECK))
                .as("step 1: while the kernel says the workload keeps the host's uid range")
                .isEqualTo(HostPreflight.STATUS_FAIL);

            // 2. POSITIVE ANCHOR: under the posture it was probed for, this host is fine.
            //    Admission passes, so nothing below is refusing a broken fixture.
            HostAdmission.requireAdmittable(probed);
            probed.set(ServerModel.ADMISSION, ServerModel.ADMISSION_ADMITTED);
            model.save(probed);
            int serverId = model.findByName("gate-posture").get(ServerModel.ID);

            // 3. THE ESCALATION: one posture edit, nothing else. No re-probe runs -- the
            //    only caller of runAndStore is a manual row action -- so every stored
            //    verdict is still the trusted-only one.
            Row widened = model.findByName("gate-posture");
            widened.set(ServerModel.POSTURE, ServerModel.POSTURE_SHARED_CONTAINER);
            model.save(widened);
            HostFixtures.acknowledgePosture(model.findByName("gate-posture"));
            assertThat(HostPreflight.storedCheckStatus(model.findByName("gate-posture"),
                    IncusPreflight.KERNEL_LANE_CHECK))
                .as("step 3: the kernel LANE still passes, so the existing live gate is"
                    + " satisfied and cannot be what refuses below")
                .isEqualTo(HostPreflight.STATUS_PASS);

            // 4. Placement must refuse, naming the check whose required-ness the posture
            //    just changed. Before this gate existed, this call SUCCEEDED.
            assertThat(catchThrowable(() -> HostAdmission.requireInstancePlacement(serverId,
                WorkloadIsolation.SHARED_KERNEL, BUCKET)))
                .as("step 4: a check that only counted as advice under the old posture is"
                    + " re-read against the new one, and placement refuses by name")
                .isInstanceOfSatisfying(Violations.class, violations ->
                    assertThat(violations.all()).anySatisfy(violation ->
                        assertThat(violation.message().key())
                            .isEqualTo("host_preflight_check_now_required")));

            // 5. The admit transition answers the same way, so re-admitting is not the way
            //    around it either.
            assertThat(catchThrowable(() ->
                    HostAdmission.requireAdmittable(model.findByName("gate-posture"))))
                .as("step 5: and so does admit -- both gates read the re-derived verdict")
                .isInstanceOfSatisfying(Violations.class, violations ->
                    assertThat(violations.all()).anySatisfy(violation ->
                        assertThat(violation.message().key())
                            .isEqualTo("host_preflight_check_now_required")));

            // 6. STATE, not just the refusals: this is a LIVE gate, so it must not have
            //    rewritten the stored decision behind the operator's back. Silently
            //    cordoning an already-admitted production host is the availability call
            //    requireKernelTruth deliberately refuses to take, and this one inherits it.
            Row after = model.findByName("gate-posture");
            assertThat((String) after.get(ServerModel.ADMISSION))
                .as("step 6: the admission column is untouched")
                .isEqualTo(ServerModel.ADMISSION_ADMITTED);
            assertThat((Boolean) after.get(ServerModel.PREFLIGHT_OK))
                .as("step 6: and so is preflight_ok -- the verdict is re-derived, never"
                    + " re-written by a gate or by a second before-validate hook")
                .isTrue();

            // 7. Narrowing the posture back is one honest exit, and the re-derivation must
            //    answer in that direction too: NOTHING is re-probed here, only the posture
            //    is undone, and the host admits again exactly as it did in step 2.
            Row narrowed = model.findByName("gate-posture");
            narrowed.set(ServerModel.POSTURE, ServerModel.POSTURE_TRUSTED_ONLY);
            model.save(narrowed);
            HostAdmission.requireAdmittable(model.findByName("gate-posture"));
            assertThat(catchThrowable(() -> HostAdmission.requireInstancePlacement(serverId,
                WorkloadIsolation.SHARED_KERNEL, BUCKET)))
                .as("step 7: and placement then refuses for the POSTURE, one gate earlier")
                .isInstanceOfSatisfying(Violations.class, violations ->
                    assertThat(violations.all()).anySatisfy(violation ->
                        assertThat(violation.message().key())
                            .isEqualTo("host_posture_refuses")));

            // 8. POSITIVE ANCHOR for the whole mechanism: the OTHER honest exit is to fix
            //    the host and re-probe. With the posture wide again and a report whose
            //    kernel checks pass, both gates open -- so steps 4-5 were refusing this
            //    evidence and not refusing everything.
            Row rewidened = model.findByName("gate-posture");
            rewidened.set(ServerModel.POSTURE, ServerModel.POSTURE_SHARED_CONTAINER);
            model.save(rewidened);
            HostFixtures.acknowledgePosture(model.findByName("gate-posture"));
            storePostureReport("gate-posture", true,
                HostPreflight.STATUS_PASS, HostPreflight.STATUS_PASS, HostPreflight.STATUS_PASS);
            HostAdmission.requireInstancePlacement(serverId,
                WorkloadIsolation.SHARED_KERNEL, BUCKET);
            HostAdmission.requireAdmittable(model.findByName("gate-posture"));

            // 9. lsm is ADVISORY on BOTH tiers by decision -- the Incus battery calls the
            //    shared HostPreflight.lsmCheck unchanged, and daystrom's kernel carries no
            //    LSM at all. Only the userns/seccomp twins diverge between the tiers, so a
            //    failing lsm must NOT be swept up by the posture re-derivation.
            HostPreflight.store("gate-posture", new HostPreflight.Report(
                List.of(new HostPreflight.Check("lsm", HostPreflight.STATUS_FAIL, false,
                    "no LSM on this kernel")),
                Map.of(), true, Instant.now(), null));
            HostAdmission.requireInstancePlacement(serverId,
                WorkloadIsolation.SHARED_KERNEL, BUCKET);
        });
    }

    /**
     * The three posture-driven Incus checks exactly as {@code IncusPreflight} stores them:
     * one shared {@code required} flag derived from the posture at PROBE time, and a report
     * whose {@code passed} therefore ignores a non-required failure.
     */
    private static void storePostureReport(String name, boolean required,
                                           String lane, String userns, String seccomp) {
        List<HostPreflight.Check> checks = List.of(
            new HostPreflight.Check("daemon", HostPreflight.STATUS_PASS, true, "reachable"),
            new HostPreflight.Check(IncusPreflight.KERNEL_LANE_CHECK, lane, required,
                "probe verdict under test"),
            new HostPreflight.Check(IncusPreflight.USERNS_CHECK, userns, required,
                "probe verdict under test"),
            new HostPreflight.Check(IncusPreflight.SECCOMP_CHECK, seccomp, required,
                "probe verdict under test"));
        boolean passed = true;
        for (HostPreflight.Check check : checks) {
            if (check.required() && check.failed()) {
                passed = false;
            }
        }
        HostPreflight.store(name, new HostPreflight.Report(checks, Map.of(), passed,
            Instant.now(), null));
    }

    /**
     * Store a passing preflight report, optionally carrying a kernel-lane check verdict --
     * the same {@code capabilities.checks} shape {@link HostPreflight#store} writes.
     */
    private static void markPreflightPassed(String name, String kernelLaneStatus) {
        List<HostPreflight.Check> checks = new ArrayList<>();
        checks.add(new HostPreflight.Check("daemon", HostPreflight.STATUS_PASS, true,
            "reachable"));
        if (kernelLaneStatus != null) {
            checks.add(new HostPreflight.Check(IncusPreflight.KERNEL_LANE_CHECK,
                kernelLaneStatus, true, "probe verdict under test"));
        }
        Map<String, Object> facts = new LinkedHashMap<>();
        HostPreflight.store(name, new HostPreflight.Report(List.copyOf(checks), facts,
            true, Instant.now(), null));
    }
}
