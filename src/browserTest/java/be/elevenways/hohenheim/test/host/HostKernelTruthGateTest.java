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
            assertThat(catchThrowable(() -> HostAdmission.requireInstancePlacement(serverId)))
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
                    HostAdmission.requireInstancePlacement(serverId)))
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
            HostAdmission.requireInstancePlacement(serverId);
            assertThat(HostPreflight.storedCheckStatus(proven, IncusPreflight.KERNEL_LANE_CHECK))
                .as("step 5: the evidence the gate read is the STORED probe verdict")
                .isEqualTo(HostPreflight.STATUS_PASS);

            // 6. A FAILING stored probe is not evidence either: the lane is still declared
            //    and trusted, so only the probe verdict distinguishes these two states.
            markPreflightPassed("gate-incus", HostPreflight.STATUS_FAIL);
            assertThat(catchThrowable(() -> HostAdmission.requireInstancePlacement(serverId)))
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
            assertThat(catchThrowable(() -> HostAdmission.requireInstancePlacement(serverId)))
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
