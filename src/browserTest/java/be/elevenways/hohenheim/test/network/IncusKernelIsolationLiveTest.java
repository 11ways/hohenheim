package be.elevenways.hohenheim.test.network;

import be.elevenways.zenit.common.orm.datasource.Datasources;
import be.elevenways.hohenheim.server.ControllerScope;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.server.incus.IncusKernelIsolation;
import be.elevenways.hohenheim.server.incus.IncusNetworkPolicy;
import be.elevenways.hohenheim.server.host.HostAdmission;
import be.elevenways.hohenheim.server.host.HostKeys;
import be.elevenways.hohenheim.server.host.HostPreflight;
import be.elevenways.hohenheim.server.instance.InstanceService;
import be.elevenways.hohenheim.server.task.VerifyIncusIsolation;
import be.elevenways.hohenheim.test.HohenheimTestRuntime;
import be.elevenways.hohenheim.test.host.LiveIncusHost;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The KERNEL-truth half of Incus isolation, proven against the real daemon host: the
 * daemon's configuration and the host's nftables are made to DISAGREE, and the product
 * is required to notice, repair, and -- when the repair cannot take -- stop the workload.
 *
 * AIDEV-NOTE: the divergence is produced the way upstream produces it, by removing the
 * instance's bridge-filter chains while it runs. That is exactly what incus v7.3.0's
 * failed NIC teardown leaves behind (the detach error returns before removeFilters, so
 * the surviving chains name a dead tap and the restarted workload's new tap matches
 * nothing) and what was observed live on daystrom on 2026-08-05: config read back fully
 * isolated, {@code table bridge incus} empty, and the VM pinging its peer AND the host.
 * The assertions here are the CONSEQUENCE (a real connection between two tenants), never
 * an API return value -- an API that answers correctly while the kernel leaks is the
 * whole defect.
 *
 * AIDEV-NOTE: the nft lane is the PRODUCTION one and no test seam is installed here. The
 * host record carries its own pinned ssh admin lane (M074 gave ssh trust its own columns,
 * so the daemon's TLS material no longer occupies them), the fixture performs only the
 * acts an operator performs -- install the product-minted public key in authorized_keys,
 * compare the scanned fingerprint against what the host reports for itself -- and every
 * kernel read below travels {@code HostKeys.sshArgv} -> {@code NftRunner.forServer}.
 * The earlier wave could only prove the MECHANISM through {@code overrideRunnerForTest};
 * this proves the DEPLOYMENT. The fixture's own ssh helpers stay the INJECTION lane (they
 * break the kernel), never the verification lane.
 */
class IncusKernelIsolationLiveTest {

    private static final String HOST = "live-incus-kernel";
    private static final String IMAGE = "alpine/3.22";

    private static SqliteDatasource datasource;
    private static LiveIncusHost remote;
    private static String enrolledFingerprint;

    @BeforeAll
    static void setUp() throws Exception {
        remote = LiveIncusHost.configured();
        assumeTrue(remote != null, "no live incus host enrolled at " + LiveIncusHost.CONFIG);

        File db = File.createTempFile("hohenheim-incus-kernel-live", ".db");
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

        // The DAEMON half only: this class walks the lane-less state on purpose, so it
        // enrols the trust relationship the daemon needs and stops there. Admission comes
        // after the kernel-truth lane, in the journey below, through the real gate.
        Db.run(datasource, () -> enrolledFingerprint =
            remote.enrollDaemonTrustThroughProduct(HOST, "hohenheim-live-kernel"));
    }

    /**
     * Give the host back exactly what it lent us: the enrolled client certificate and the
     * authorized_keys line. Both are working CREDENTIALS on a real machine, so the
     * outcome is PRINTED rather than swallowed -- a cleanup that silently fails leaves
     * root access behind and reports success, which is the shape this whole wave hunts.
     */
    @AfterAll
    static void tearDown() {
        if (remote == null) {
            return;
        }
        System.out.println("=== cleanup: authorized_keys -> "
            + remote.releaseAuthorizedKeys());
        if (enrolledFingerprint != null) {
            try {
                remote.removeTrustEntry(enrolledFingerprint);
                System.out.println("=== cleanup: trust entry " + enrolledFingerprint
                    + " removed");
            } catch (IOException failed) {
                System.out.println("=== cleanup: trust entry " + enrolledFingerprint
                    + " NOT removed: " + failed.getMessage());
            }
        }
    }

    @Test
    void kernelDivergenceIsSeenRepairedAndOtherwiseStopsTheWorkload() {
        Db.run(datasource, () -> {
            InstanceService service = new InstanceService();
            ServerModel model = Models.get(ServerModel.class);

            // 1. WITHOUT an ssh admin lane this remote daemon's kernel is unreadable, and
            //    the product says so instead of guessing. This is the state every https
            //    Incus host was in before the trust columns were split.
            Row laneless = model.findByName(HOST);
            assertThat(ServerModel.hasSshLane(laneless))
                .as("step 1: the enrolled host starts with no ssh admin lane")
                .isFalse();
            assertThat(IncusKernelIsolation.forServer(laneless).available())
                .as("step 1: so kernel truth is UNAVAILABLE, never assumed fine")
                .isFalse();
            assertThatInspectRefuses(IncusKernelIsolation.forServer(laneless),
                ControllerScope.handle(ControllerScope.KIND_INSTANCE, 0));
            // 1b. And that is now a PLACEMENT REFUSAL, not merely a missing diagnostic:
            //     a real https daemon with a tenant-accepting posture and no lane cannot
            //     pass preflight or be admitted, by name.
            assertThat(ServerModel.acceptsTenantWorkloads(laneless))
                .as("step 1b: the enrolled host accepts tenant workloads")
                .isTrue();
            HostPreflight.Report lanelessReport = HostPreflight.runAndStore(HOST);
            assertThat(lanelessReport.check("kernel_isolation_lane"))
                .as("step 1b: the kernel-lane probe ran").isNotNull();
            assertThat(lanelessReport.check("kernel_isolation_lane").required())
                .as("step 1b: and is REQUIRED for a tenant-accepting host").isTrue();
            assertThat(lanelessReport.check("kernel_isolation_lane").status())
                .as("step 1b: it FAILS with no lane to read the kernel through")
                .isEqualTo(HostPreflight.STATUS_FAIL);
            assertThat(lanelessReport.passed())
                .as("step 1b: so the whole preflight verdict is false")
                .isFalse();
            assertThat(catchThrowable(() ->
                    HostAdmission.requireAdmittable(model.findByName(HOST))))
                .as("step 1b: and admitting it is refused BY NAME")
                .isInstanceOfSatisfying(Violations.class, violations ->
                    assertThat(violations.all()).anySatisfy(violation ->
                        assertThat(violation.message().key())
                            .isEqualTo("host_kernel_lane_missing")));

            // 2. The operator enrolls the second trust relationship, walking the SAME
            //    ceremony the docker hosts walk (scan, out-of-band compare, confirm) --
            //    and it lands in its OWN columns, next to the daemon certificate.
            String sshFingerprint = remote.enrollSshLaneThroughProduct(HOST);
            Row host = model.findByName(HOST);
            assertThat((String) host.get(ServerModel.HOST_KEY_FINGERPRINT))
                .as("step 2: the ssh pin is the host's own ssh host key")
                .isEqualTo(sshFingerprint);
            assertThat((String) host.get(ServerModel.INCUS_SERVER_CERT_FINGERPRINT))
                .as("step 2: and the daemon certificate is untouched beside it")
                .isEqualTo(remote.fingerprint());
            assertThat((String) host.get(ServerModel.HOST_KEY))
                .as("step 2: the ssh slot holds a known_hosts line, never a PEM")
                .startsWith("ssh-").doesNotContain("BEGIN CERTIFICATE");
            assertThat((String) host.get(ServerModel.INCUS_SERVER_CERT))
                .as("step 2: and the tls slot holds the PEM")
                .contains("BEGIN CERTIFICATE");

            // 3. THE PRODUCTION PATH: no test seam is installed, so this verifier reaches
            //    daystrom's kernel over HostKeys.sshArgv with the pin it just confirmed.
            IncusKernelIsolation kernel = IncusKernelIsolation.forServer(host);
            assertThat(kernel.available())
                .as("step 3: kernel truth is now readable through the product's own lane")
                .isTrue();

            // 4. An UNCONFIRMED lane is not a lane: refusing to answer stays the verdict
            //    until a human compared the fingerprint.
            host.set(ServerModel.HOST_KEY_VERIFIED, false);
            model.save(host);
            assertThat(IncusKernelIsolation.forServer(model.findByName(HOST)).available())
                .as("step 4: an unconfirmed ssh pin leaves the host unverifiable")
                .isFalse();
            HostKeys.confirm(model.findByName(HOST));

            // 4b. With the lane confirmed the kernel-lane probe PROVES itself against
            //     daystrom's real nftables over ssh, and only then does the host admit --
            //     the whole point of the requirement, walked end to end.
            HostPreflight.Report provenReport = HostPreflight.runAndStore(HOST);
            assertThat(provenReport.check("kernel_isolation_lane").status())
                .as("step 4b: the probe transacted on the daemon host's own nftables: "
                    + provenReport.check("kernel_isolation_lane").detail())
                .isEqualTo(HostPreflight.STATUS_PASS);
            assertThat(provenReport.passed())
                .as("step 4b: so the preflight verdict is green")
                .isTrue();
            Row admittable = model.findByName(HOST);
            HostAdmission.requireAdmittable(admittable);
            admittable.set(ServerModel.ADMISSION, ServerModel.ADMISSION_ADMITTED);
            model.save(admittable);
            HostAdmission.requireKernelTruth(model.findByName(HOST));

            int idA = instanceRecord("kernel-tenant-a", 8011);
            int idB = instanceRecord("kernel-tenant-b", 8022);
            String handleA = ControllerScope.handle(ControllerScope.KIND_INSTANCE, idA);
            String handleB = ControllerScope.handle(ControllerScope.KIND_INSTANCE, idB);

            try {
                service.deploy(idA);
                service.deploy(idB);
                String peer = addressOf(handleB);

                // 5. As deployed: the kernel carries the isolation for A's LIVE tap, and
                //    the boundary really holds -- A cannot reach B, but the internet is
                //    up (an ADDRESS LITERAL, so a working v6 cannot fake a v4 pass).
                evidence("step 5: AS DEPLOYED (read through the product's ssh lane)");
                assertThat(kernel.inspect(handleA).missing())
                    .as("step 5: the as-deployed kernel is missing nothing")
                    .isEmpty();
                assertThat(canReach(handleA, peer))
                    .as("step 5: tenant A cannot reach tenant B (" + peer + ")")
                    .isFalse();
                assertThat(canReach(handleA, "1.1.1.1"))
                    .as("step 5: A still reaches the internet, so its NIC is alive")
                    .isTrue();

                // 6. THE DIVERGENCE, produced as the daemon produces it: A's chains are
                //    gone from the kernel while every daemon-side fact stays perfect.
                dropChains(handleA);
                assertThat(nicAclOf(handleA))
                    .as("step 6: the daemon still reports the NIC as fully isolated")
                    .isEqualTo(IncusNetworkPolicy.aclName());
                assertThat(aclShow())
                    .as("step 6: and the ACL still reads back with every tenant reject")
                    .contains("10.0.0.0/8").contains("169.254.0.0/16").contains("fc00::/7");
                evidence("step 6: DIVERGED (config still says isolated)");
                assertThat(canReach(handleA, peer))
                    .as("step 6: THE LEAK -- A now reaches B while config says isolated")
                    .isTrue();

                // 7. The kernel verifier is the ONLY layer that sees it.
                IncusKernelIsolation.Divergence diverged = kernel.inspect(handleA);
                assertThat(diverged.enforced())
                    .as("step 7: kernel truth reports the divergence config cannot see")
                    .isFalse();
                assertThat(diverged.describe())
                    .as("step 7: the refusal names the workload and the missing ranges")
                    .contains(handleA).contains("10.0.0.0/8");

                // 8. The SWEEP is what runs in production, and it repairs through the
                //    daemon's own re-apply lever; the boundary is measurably back.
                assertThat(sweepOutcome())
                    .as("step 8: the production sweep repairs the diverged workload")
                    .satisfies(outcome -> {
                        assertThat(outcome.verifiable())
                            .as("step 8: over a lane it can actually read").isTrue();
                        assertThat(outcome.repaired())
                            .as("step 8: naming the workload it repaired")
                            .contains(handleA);
                        assertThat(outcome.stopped())
                            .as("step 8: and stopping nothing, because the repair took")
                            .isEmpty();
                        assertThat(outcome.errors())
                            .as("step 8: with no error at all -- a repair that reloaded"
                                + " every workload on the daemon would fail here the"
                                + " moment any neighbour's NIC was mid transition")
                            .isEmpty();
                    });
                evidence("step 8: REPAIRED by the production sweep");
                assertThat(kernel.inspect(handleA).missing())
                    .as("step 8: the kernel carries the isolation again")
                    .isEmpty();
                assertThat(canReach(handleA, peer))
                    .as("step 8: and A can no longer reach B")
                    .isFalse();
                assertThat(canReach(handleA, "1.1.1.1"))
                    .as("step 8: the repair did not sever the NIC")
                    .isTrue();

                // 9. UNREPAIRABLE: with the ACL no longer referenced by A's NIC, the
                //    re-apply lever cannot reach it. enforce() must REFUSE rather than
                //    report success, naming the workload.
                dropAcl(handleA);
                dropChains(handleA);
                assertThat(canReach(handleA, peer))
                    .as("step 9: A is unisolated again")
                    .isTrue();
                assertThatEnforceRefuses(kernel, handleA);

                // 10. The declared consequence, through the SWEEP: an unisolated,
                //     unrepairable workload does not stay reachable.
                assertThat(sweepOutcome())
                    .as("step 10: the sweep stops what it cannot re-isolate")
                    .satisfies(outcome -> {
                        assertThat(outcome.stopped())
                            .as("step 10: naming the stopped workload")
                            .contains(handleA);
                        assertThat(outcome.errors())
                            .as("step 10: with the refusal recorded, not swallowed")
                            .anyMatch(error -> error.contains("REFUSED to leave"));
                        // THE cross-tenant property. A repair lever that walks every NIC
                        // referencing the shared ACL fails on a NEIGHBOUR's transitioning
                        // veth, and the refusal it records then blames a workload that is
                        // not ours -- which, since an unrepairable workload is STOPPED,
                        // means one tenant's churn can stop another tenant's instance.
                        // Measured on daystrom: 21 of 103 shared-ACL bumps failed that
                        // way under neighbour churn, 0 of 116 per-instance toggles did.
                        assertThat(blamedHandles(outcome.errors()))
                            .as("step 10: and every workload the refusals name is OURS;"
                                + " a repair must never depend on a neighbour's NIC")
                            .isSubsetOf(handleA, handleB);
                    });
                assertThat(runningState(handleA))
                    .as("step 10: the workload is stopped at the DAEMON, not just in a log")
                    .isNotEqualTo("Running");
                assertThat(Models.get(InstanceModel.class).findById(idA)
                        .get(InstanceModel.STATUS))
                    .as("step 10: and the record records the stop")
                    .isEqualTo(InstanceModel.STATUS_STOPPED);
            } catch (IOException e) {
                throw new RuntimeException(e);
            } finally {
                remote.forceDelete(handleA);
                remote.forceDelete(handleB);
                // The shared isolation ACL is deliberately NOT deleted: it is durable
                // daemon infrastructure, and under parallel forks a per-class delete
                // races another class's no-write-then-verify deploy (ensureIsolationAcl
                // reads it as present-and-exact, writes nothing, then finds it GONE at
                // the read-back and refuses that tenant's install). Observed live
                // 2026-08-06 under the eight-class run.
            }
        });
    }

    /**
     * Print the daemon host's bridge-filter table at a named moment. This is the EVIDENCE
     * the whole mechanism turns on -- the table the verifier reads and the daemon's own
     * config cannot see -- so a failing run in CI carries it instead of a bare boolean.
     */
    private static void evidence(String label) {
        System.out.println("=== " + label + " ===");
        System.out.println(hostCmd("nft", "list", "table", "bridge", "incus"));
    }

    /** Every instance handle named anywhere in a sweep's recorded errors. */
    private static Set<String> blamedHandles(List<String> errors) {
        Set<String> handles = new TreeSet<>();
        for (String error : errors) {
            Matcher matcher = Pattern.compile(Pattern.quote(
                ControllerScope.kindPrefix(ControllerScope.KIND_INSTANCE)) + "\\d+").matcher(error);
            while (matcher.find()) {
                handles.add(matcher.group());
            }
        }
        return handles;
    }

    /** This host's outcome from the PRODUCTION sweep the scheduled task runs. */
    private static VerifyIncusIsolation.HostOutcome sweepOutcome() {
        for (VerifyIncusIsolation.HostOutcome outcome : VerifyIncusIsolation.sweep()) {
            if (HOST.equals(outcome.server())) {
                return outcome;
            }
        }
        throw new AssertionError("the sweep did not visit " + HOST + "; it only reports on"
            + " hosts carrying RUNNING instances");
    }

    private static void assertThatInspectRefuses(IncusKernelIsolation kernel, String handle) {
        try {
            kernel.inspect(handle);
            throw new AssertionError("inspect() answered for a host whose kernel cannot be"
                + " read; an unreadable kernel must never be reported as a pass");
        } catch (IOException refused) {
            assertThat(refused.getMessage())
                .as("the refusal names the missing lane rather than guessing")
                .contains("REFUSED to report").contains("ssh admin lane");
        }
    }

    private static void assertThatEnforceRefuses(IncusKernelIsolation kernel, String handle) {
        try {
            kernel.enforce(handle);
            throw new AssertionError("step 5: enforce() reported success on a workload the"
                + " daemon cannot re-isolate; that is the silent-success shape this whole"
                + " mechanism exists to kill");
        } catch (IOException refused) {
            assertThat(refused.getMessage())
                .as("step 5: the refusal names the workload and says it may not keep running")
                .contains(handle).contains("REFUSED to leave");
        }
    }

    /** Remove one instance's bridge-filter chains: the failed-teardown outcome. */
    private static void dropChains(String handle) {
        for (String prefix : List.of("in", "fwd", "out")) {
            try {
                hostCmd("nft", "delete", "chain", "bridge", "incus",
                    prefix + "." + handle + "." + IncusNetworkPolicy.NIC);
            } catch (RuntimeException alreadyGone) {
                // the chain may not exist; the assertion below measures the outcome
            }
        }
    }

    /** Detach the isolation ACL from the NIC so the re-apply lever cannot reach it. */
    private static void dropAcl(String handle) {
        hostCmd("incus", "config", "device", "unset", handle, IncusNetworkPolicy.NIC,
            "security.acls");
    }

    private static String nicAclOf(String handle) {
        return hostCmd("incus", "config", "device", "get", handle, IncusNetworkPolicy.NIC,
            "security.acls").trim();
    }

    private static String aclShow() {
        return hostCmd("incus", "network", "acl", "show", IncusNetworkPolicy.aclName());
    }

    private static String runningState(String handle) {
        return hostCmd("incus", "list", handle, "-c", "s", "--format", "csv").trim();
    }

    private static int instanceRecord(String name, int ownerRecordId) {
        Row host = Models.get(ServerModel.class).findByName(HOST);
        Map<String, Object> settings = new LinkedHashMap<>();
        settings.put("image", IMAGE);
        settings.put("memory_limit_mb", 128);
        Row row = Models.get(InstanceModel.class).createEmptyRow();
        row.set(InstanceModel.NAME, name + "-" + ownerRecordId);
        row.set(InstanceModel.KIND, "hohenheim:incus_container");
        row.set(InstanceModel.SETTINGS, settings);
        row.set(InstanceModel.SERVER_ID, host.get(ServerModel.ID));
        Models.get(InstanceModel.class).save(row);
        return row.get(InstanceModel.ID);
    }

    /**
     * The instance's global IPv4, polled: deploy() returns as soon as the workload runs
     * and the lease lands a moment later.
     */
    private static String addressOf(String handle) {
        String out = "";
        long deadline = System.nanoTime() + 30_000_000_000L;
        while (true) {
            out = remoteExec(handle,
                "ip -o -f inet addr show eth0 | awk '{print $4}' | cut -d/ -f1 | head -1");
            if (!out.isBlank() || System.nanoTime() >= deadline) {
                break;
            }
            sleep(500L);
        }
        assertThat(out).as("IPv4 address of " + handle).isNotBlank();
        return out.trim();
    }

    /**
     * Whether the workload can actually reach an address. A dropped destination times
     * out; a reachable one answers.
     */
    private static boolean canReach(String from, String address) {
        String out = remoteExec(from, "ping -c 2 -W 2 " + address
            + " >/dev/null 2>&1 && echo REACHED || echo BLOCKED");
        return out.contains("REACHED");
    }

    private static String remoteExec(String handle, String command) {
        try {
            return remote.exec(handle, command);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static String hostCmd(String... command) {
        try {
            return remote.hostCommand(command);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
