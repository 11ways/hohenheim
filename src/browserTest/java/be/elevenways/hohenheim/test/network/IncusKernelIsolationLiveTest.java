package be.elevenways.hohenheim.test.network;

import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.server.incus.IncusKernelIsolation;
import be.elevenways.hohenheim.server.incus.IncusNetworkPolicy;
import be.elevenways.hohenheim.server.instance.InstanceService;
import be.elevenways.hohenheim.server.security.NftRunner;
import be.elevenways.hohenheim.test.HohenheimTestRuntime;
import be.elevenways.hohenheim.test.LiveIdOffsets;
import be.elevenways.hohenheim.test.host.LiveIncusHost;
import be.elevenways.zenit.common.orm.datasource.Db;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
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

import static org.assertj.core.api.Assertions.assertThat;
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
 * AIDEV-NOTE: the nft lane is injected through {@code overrideRunnerForTest} because an
 * https-enrolled Incus host has no PRODUCT shell lane (its ssh columns carry the
 * daemon's TLS material), so without the override every kernel-truth assertion would
 * SKIP -- and a skipped test is a green test. The override carries the PRODUCTION
 * verifier onto the real host's real nftables; nothing about the decision is faked.
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
        LiveIdOffsets.apply(datasource);
        HohenheimTestRuntime.ensureBooted();

        Db.run(datasource, () -> enrolledFingerprint =
            remote.enrollThroughProduct(HOST, "hohenheim-live-kernel"));
        IncusKernelIsolation.overrideRunnerForTest(hostNftRunner());
    }

    @AfterAll
    static void tearDown() {
        IncusKernelIsolation.overrideRunnerForTest(null);
        if (remote != null && enrolledFingerprint != null) {
            try {
                remote.removeTrustEntry(enrolledFingerprint);
            } catch (IOException ignored) {
                // nothing enrolled, nothing to remove
            }
        }
    }

    @Test
    void kernelDivergenceIsSeenRepairedAndOtherwiseStopsTheWorkload() {
        Db.run(datasource, () -> {
            InstanceService service = new InstanceService();
            Row host = Models.get(ServerModel.class).findByName(HOST);
            IncusKernelIsolation kernel = IncusKernelIsolation.forServer(host);

            int idA = instanceRecord("kernel-tenant-a", 8011);
            int idB = instanceRecord("kernel-tenant-b", 8022);
            String handleA = "hohenheim-instance-" + idA;
            String handleB = "hohenheim-instance-" + idB;

            try {
                service.deploy(idA);
                service.deploy(idB);
                String peer = addressOf(handleB);

                // 1. As deployed: the kernel carries the isolation for A's LIVE tap, and
                //    the boundary really holds -- A cannot reach B, but the internet is
                //    up (an ADDRESS LITERAL, so a working v6 cannot fake a v4 pass).
                assertThat(kernel.available())
                    .as("step 1: the test lane reaches the daemon host's nftables")
                    .isTrue();
                assertThat(kernel.inspect(handleA).missing())
                    .as("step 1: the as-deployed kernel is missing nothing")
                    .isEmpty();
                assertThat(canReach(handleA, peer))
                    .as("step 1: tenant A cannot reach tenant B (" + peer + ")")
                    .isFalse();
                assertThat(canReach(handleA, "1.1.1.1"))
                    .as("step 1: A still reaches the internet, so its NIC is alive")
                    .isTrue();

                // 2. THE DIVERGENCE, produced as the daemon produces it: A's chains are
                //    gone from the kernel while every daemon-side fact stays perfect.
                dropChains(handleA);
                assertThat(nicAclOf(handleA))
                    .as("step 2: the daemon still reports the NIC as fully isolated")
                    .isEqualTo(IncusNetworkPolicy.ACL_NAME);
                assertThat(aclShow())
                    .as("step 2: and the ACL still reads back with every tenant reject")
                    .contains("10.0.0.0/8").contains("169.254.0.0/16").contains("fc00::/7");
                assertThat(canReach(handleA, peer))
                    .as("step 2: THE LEAK -- A now reaches B while config says isolated")
                    .isTrue();

                // 3. The kernel verifier is the ONLY layer that sees it.
                IncusKernelIsolation.Divergence diverged = kernel.inspect(handleA);
                assertThat(diverged.enforced())
                    .as("step 3: kernel truth reports the divergence config cannot see")
                    .isFalse();
                assertThat(diverged.describe())
                    .as("step 3: the refusal names the workload and the missing ranges")
                    .contains(handleA).contains("10.0.0.0/8");

                // 4. enforce() repairs through the daemon's own re-apply lever, and the
                //    boundary is measurably back.
                kernel.enforce(handleA);
                assertThat(kernel.inspect(handleA).missing())
                    .as("step 4: the kernel carries the isolation again")
                    .isEmpty();
                assertThat(canReach(handleA, peer))
                    .as("step 4: and A can no longer reach B")
                    .isFalse();
                assertThat(canReach(handleA, "1.1.1.1"))
                    .as("step 4: the repair did not sever the NIC")
                    .isTrue();

                // 5. UNREPAIRABLE: with the ACL no longer referenced by A's NIC, the
                //    re-apply lever cannot reach it. enforce() must REFUSE rather than
                //    report success, naming the workload.
                dropAcl(handleA);
                dropChains(handleA);
                assertThat(canReach(handleA, peer))
                    .as("step 5: A is unisolated again")
                    .isTrue();
                assertThatEnforceRefuses(kernel, handleA);

                // 6. The declared consequence: an unisolated, unrepairable workload does
                //    not stay reachable. The service stop is what the sweep calls.
                service.stop(idA);
                assertThat(runningState(handleA))
                    .as("step 6: the workload is stopped at the DAEMON, not just in a log")
                    .isNotEqualTo("Running");
                assertThat(Models.get(InstanceModel.class).findById(idA)
                        .get(InstanceModel.STATUS))
                    .as("step 6: and the record records the stop")
                    .isEqualTo(InstanceModel.STATUS_STOPPED);
            } catch (IOException e) {
                throw new RuntimeException(e);
            } finally {
                remote.forceDelete(handleA);
                remote.forceDelete(handleB);
                try {
                    hostCmd("incus", "network", "acl", "delete", IncusNetworkPolicy.ACL_NAME);
                } catch (RuntimeException stillReferencedOrGone) {
                    // best-effort sweep; a referenced ACL clears once both instances are gone
                }
            }
        });
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

    /** The nft lane onto the DAEMON'S host, over the operator ssh target of the fixture. */
    private static NftRunner hostNftRunner() {
        return (args, stdin) -> {
            List<String> argv = new java.util.ArrayList<>(List.of("nft"));
            argv.addAll(args);
            try {
                return new NftRunner.Result(0, remote.hostCommand(
                    argv.toArray(new String[0])), "");
            } catch (IOException e) {
                return new NftRunner.Result(1, "", String.valueOf(e.getMessage()));
            }
        };
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
        return hostCmd("incus", "network", "acl", "show", IncusNetworkPolicy.ACL_NAME);
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
