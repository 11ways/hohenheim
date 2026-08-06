package be.elevenways.hohenheim.test.network;

import be.elevenways.zenit.common.orm.datasource.Datasources;
import be.elevenways.hohenheim.server.ControllerScope;
import be.elevenways.hohenheim.model.DatabaseModel;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.model.SiteDatabaseModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.model.StackModel;
import be.elevenways.hohenheim.model.StackServiceModel;
import be.elevenways.hohenheim.server.database.ManagedDatabase;
import be.elevenways.hohenheim.server.docker.DockerClient;
import be.elevenways.hohenheim.server.docker.OwnerLabels;
import be.elevenways.hohenheim.server.docker.SiteInstances;
import be.elevenways.hohenheim.server.instance.InstanceService;
import be.elevenways.hohenheim.server.orm.GeneratedRows;
import be.elevenways.hohenheim.server.runtime.DockerInstanceRuntime;
import be.elevenways.hohenheim.server.runtime.Egress;
import be.elevenways.hohenheim.server.runtime.WorkloadNetworks;
import be.elevenways.hohenheim.server.security.NftRunner;
import be.elevenways.hohenheim.server.security.TenantNetworkRanges;
import be.elevenways.hohenheim.server.security.WorkloadNetworkPolicy;
import be.elevenways.hohenheim.server.stack.StackDeployer;
import be.elevenways.hohenheim.server.stack.StackRuntime;
import be.elevenways.hohenheim.server.stack.StackSpec;
import be.elevenways.hohenheim.server.task.VerifyWorkloadIsolation;
import be.elevenways.hohenheim.test.HohenheimTestRuntime;
import be.elevenways.hohenheim.test.host.HostFixtures;
import be.elevenways.zenit.common.orm.datasource.Db;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.server.orm.SqliteDatasource;
import be.elevenways.zenit.server.orm.crypto.EncryptionKeyring;
import be.elevenways.zenit.server.orm.crypto.FieldEncryption;
import be.elevenways.zenit.server.orm.migration.MigrationRunner;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The reboot-window sweep against a REAL daemon and a REAL kernel: every Docker tier's
 * policied network (stack, instance, managed database, site-database link) loses its
 * chains the way a host reboot loses them -- the whole nftables table deleted while
 * containers and networks stay -- and {@link VerifyWorkloadIsolation#sweep()} must read
 * that out of the kernel, repair it, and hold the deny against real packets again.
 *
 * AIDEV-NOTE: the divergence is produced by deleting {@code table inet hohenheim_net}
 * inside the netns fixture, which is EXACTLY the state a rebooted host presents
 * (measured on daystrom 2026-08-06: networks and unless-stopped containers back,
 * {@code nft list table inet hohenheim_net} answering "No such file or directory").
 * What this class does NOT prove is the boot scheduling of the task itself -- that is
 * the framework's bootAndCron contract, exercised by zenit's own task tests.
 */
class VerifyWorkloadIsolationTest {

    private static final Path SOCKET = Path.of(DockerClient.DEFAULT_SOCKET);
    private static final String TEST_IMAGE = "alpine:latest";

    /** The cloud metadata address: instance credentials for the whole host. */
    private static final String METADATA = "169.254.169.254";

    /** A public address: the stack tier's DECLARED-open egress must keep working. */
    private static final String PUBLIC = "203.0.113.5";

    private static final int PEER_PORT = 80;

    private static SqliteDatasource datasource;
    private static PrivateNetns netns;
    private static DockerClient docker;

    @BeforeAll
    static void setUp() throws Exception {
        FieldEncryption.installKeyring(EncryptionKeyring.loadOrCreate(
            Files.createTempDirectory("hh-verify-iso").resolve("keys.dry")));
        File db = File.createTempFile("hohenheim-verify-isolation-test", ".db");
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
        netns = PrivateNetns.installEnforcing();
        docker = new DockerClient();
    }

    @AfterAll
    static void tearDown() {
        PrivateNetns.uninstall(netns);
        netns = null;
    }

    private void requireFixture() throws IOException {
        assumeTrue(Files.exists(SOCKET), "Docker socket not present");
        assumeTrue(netns != null,
            "no private netns available: the sweep cannot be proven against a real kernel");
        assumeTrue(imagePresent(docker, TEST_IMAGE), TEST_IMAGE + " not present locally");
    }

    @Test
    void aRebootLosesEveryTiersChainsAndOneSweepRestoresThemAll() throws IOException {
        requireFixture();
        String suffix = Long.toHexString(System.nanoTime());
        String stackName = "hhvrfy-" + suffix;
        StackRuntime stacks = new StackRuntime(docker, datasource);
        int[] cleanupInstance = new int[] {-1};
        int[] cleanupStack = new int[] {-1};
        List<String> scratchNetworks = new ArrayList<>();
        try {
            Db.run(datasource, () -> {
                HostFixtures.admitLocal();

                // 1. FOUR TIERS ON ONE HOST. A stack through the product lane; an
                //    instance through the product lane (then stamped as a site's
                //    SERVING release, which is what the link inventory keys on); a
                //    managed database's network + record; a site-database link
                //    network + attachment row.
                int stackId = stackRecords(stackName);
                cleanupStack[0] = stackId;
                io(() -> stacks.deploy(stackId, "verify-isolation test"));

                int instanceId = instanceRecord("vrfy-site-release");
                cleanupInstance[0] = instanceId;
                new InstanceService().deploy(instanceId);
                String instanceHandle = ControllerScope.handle(ControllerScope.KIND_INSTANCE, instanceId);
                int fakeSiteId = 800000 + instanceId;
                // The attribution columns are guarded: only the system scope may stamp
                // a row as site-generated, so the fixture goes through that same lane.
                try {
                    GeneratedRows.as(new GeneratedRows.Attribution(SiteInstances.SOURCE,
                            SiteModel.MODEL_ID.toString(), fakeSiteId), () -> {
                        Row instance = Models.get(InstanceModel.class).findById(instanceId);
                        instance.set(InstanceModel.RUNTIME_ROLE, InstanceModel.ROLE_SERVING);
                        Models.get(InstanceModel.class).save(instance);
                    });
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }

                int databaseId = databaseRecord("vrfy-db-" + suffix);
                String databaseHandle = ManagedDatabase.containerHandle("vrfy-db-" + suffix);
                io(() -> WorkloadNetworks.ensure(docker, netns.enforcingPolicy(),
                    databaseHandle, OwnerLabels.of(DatabaseModel.MODEL_ID, databaseId),
                    Egress.NONE));
                scratchNetworks.add(WorkloadNetworks.networkName(databaseHandle));

                Row link = Models.get(SiteDatabaseModel.class).createEmptyRow();
                link.set(SiteDatabaseModel.SITE_ID, fakeSiteId);
                link.set(SiteDatabaseModel.DATABASE_ID, databaseId);
                link.set(SiteDatabaseModel.ENV_PREFIX, "DB");
                Models.get(SiteDatabaseModel.class).save(link);
                String linkHandle = ControllerScope.handle(ControllerScope.KIND_DBLINK, fakeSiteId) + "-" + databaseId;
                DockerInstanceRuntime runtime = new DockerInstanceRuntime(
                    docker, netns.enforcingPolicy());
                io(() -> runtime.ensureLinkNetwork(linkHandle,
                    OwnerLabels.of(SiteDatabaseModel.MODEL_ID,
                        link.get(SiteDatabaseModel.ID)), Egress.NONE));
                scratchNetworks.add(WorkloadNetworks.networkName(linkHandle));

                String stackNetwork = StackDeployer.networkName(stackName);
                String instanceNetwork = WorkloadNetworks.networkName(instanceHandle);
                String databaseNetwork = WorkloadNetworks.networkName(databaseHandle);
                String linkNetwork = WorkloadNetworks.networkName(linkHandle);
                Map<String, String> subnets = new LinkedHashMap<>();
                for (String network : List.of(stackNetwork, instanceNetwork,
                        databaseNetwork, linkNetwork)) {
                    subnets.put(network, subnetOf(io(() -> docker.inspectNetwork(network))));
                }

                // 2. All four tiers' chains are in the kernel after their deploys.
                String before = netns.inHost("nft", "list", "ruleset").stdout();
                for (Map.Entry<String, String> entry : subnets.entrySet()) {
                    assertThat(before)
                        .as("step 2: the kernel denies the metadata range for %s before"
                            + " the reboot", entry.getKey())
                        .contains("ip saddr " + entry.getValue() + " ip daddr "
                            + TenantNetworkRanges.METADATA_V4 + " drop");
                }

                // 3. THE REBOOT EFFECT, exactly as measured on daystrom: kernel state
                //    gone, Docker state (networks, containers) untouched.
                io(() -> netns.setup("nft", "delete", "table", "inet", WorkloadNetworkPolicy.table()));
                NftRunner.Result gone = netns.inHost("nft", "list", "table", "inet",
                    WorkloadNetworkPolicy.table());
                assertThat(gone.ok())
                    .as("step 3: the policy table is gone, the daystrom post-reboot state")
                    .isFalse();
                assertThat(gone.failureText())
                    .as("step 3: gone as in ABSENT, not unreadable")
                    .contains("No such file or directory");

                // 4. ONE sweep. Every tier's workload is repaired, none contained,
                //    no errors, on a verifiable host.
                List<VerifyWorkloadIsolation.HostOutcome> outcomes =
                    VerifyWorkloadIsolation.sweep();
                assertThat(outcomes)
                    .as("step 4: exactly the local host was swept").hasSize(1);
                VerifyWorkloadIsolation.HostOutcome outcome = outcomes.get(0);
                assertThat(outcome.verifiable())
                    .as("step 4: the host's kernel is readable").isTrue();
                assertThat(outcome.errors())
                    .as("step 4: no workload errored").isEmpty();
                assertThat(outcome.contained())
                    .as("step 4: nothing needed containment").isEmpty();
                assertThat(outcome.repaired())
                    .as("step 4: every tier's workload was repaired")
                    .contains("stack '" + stackName + "'", instanceHandle, databaseHandle,
                        "link " + linkHandle);

                // 5. KERNEL truth after the repair: the full deny vocabulary is back
                //    for every network's REAL subnet, egress-NONE drops included.
                String after = netns.inHost("nft", "list", "ruleset").stdout();
                System.out.println("VERIFY-ISOLATION kernel ruleset after repair:\n" + after);
                for (Map.Entry<String, String> entry : subnets.entrySet()) {
                    for (String denied : TenantNetworkRanges.DENIED_V4) {
                        assertThat(after)
                            .as("step 5: the kernel denies %s for %s again", denied,
                                entry.getKey())
                            .contains("ip saddr " + entry.getValue() + " ip daddr "
                                + denied + " drop");
                    }
                }
                for (String noneEgress : List.of(databaseNetwork, linkNetwork)) {
                    assertThat(after)
                        .as("step 5: the egress-NONE final drop is back for %s", noneEgress)
                        .contains("ip saddr " + subnets.get(noneEgress) + " drop");
                }

                // 6. A second sweep finds everything enforced: the repair is idempotent
                //    and the sweep never oscillates.
                VerifyWorkloadIsolation.HostOutcome second = VerifyWorkloadIsolation.sweep().get(0);
                assertThat(second.repaired())
                    .as("step 6: nothing left to repair on the second tick").isEmpty();
                assertThat(second.enforced())
                    .as("step 6: every workload reads back enforced")
                    .contains("stack '" + stackName + "'", instanceHandle, databaseHandle,
                        "link " + linkHandle);

                // 7. REAL packets through the repaired chains, on the stack subnet: the
                //    metadata service is BLOCKED again and the DECLARED-open egress
                //    still works (the positive anchor proving the deny is a deny, not a
                //    broken route).
                String stackSubnet = subnets.get(stackNetwork);
                String gateway = gatewayOf(io(() -> docker.inspectNetwork(stackNetwork)));
                io(() -> {
                    long service = netns.nested();
                    long peer = netns.nested();
                    wire(netns, service, peer, gateway, addressIn(stackSubnet, 200));
                    assertThat(netns.probe(service, METADATA, PEER_PORT))
                        .as("step 7: after the repair the metadata service is BLOCKED"
                            + " for a stack-subnet source")
                        .isEqualTo("BLOCKED");
                    assertThat(netns.probe(service, PUBLIC, PEER_PORT))
                        .as("step 7: while the declared-open egress still REACHES out")
                        .isEqualTo("REACHABLE");
                    return null;
                });
            });
        } finally {
            cleanupAll(stacks, cleanupStack[0], cleanupInstance[0], scratchNetworks);
        }
    }

    /**
     * The three repair-failure conditions, in one journey: enforcement off = report and
     * touch nothing; kernel unreadable = report and touch nothing; observed diverged
     * and re-apply refused = the stack is STOPPED at the daemon and on the record.
     */
    @Test
    void repairFailureContainsTheStackWhileUnverifiableHostsAreOnlyReported()
            throws IOException {
        requireFixture();
        String stackName = "hhcntn-" + Long.toHexString(System.nanoTime());
        StackRuntime stacks = new StackRuntime(docker, datasource);
        int[] cleanupStack = new int[] {-1};
        try {
            Db.run(datasource, () -> {
                int stackId = stackRecords(stackName);
                cleanupStack[0] = stackId;
                io(() -> stacks.deploy(stackId, "containment test"));
                String container = StackDeployer.containerName(spec(stackId), "app");

                // 1. Divergence: the stack's chains are gone (the reboot state).
                io(() -> netns.setup("nft", "delete", "table", "inet", WorkloadNetworkPolicy.table()));

                // 2. Enforcement OFF: the sweep reports the host unverifiable ON THE
                //    RECORD and stops nothing -- the pre-enforcement decision.
                WorkloadNetworkPolicy.overrideForTest(new WorkloadNetworkPolicy(
                    (args, stdin) -> {
                        throw new AssertionError("nft must never run with enforcement off");
                    }, () -> false));
                VerifyWorkloadIsolation.HostOutcome off = VerifyWorkloadIsolation.sweep().get(0);
                assertThat(off.verifiable())
                    .as("step 2: enforcement off reads as unverifiable").isFalse();
                assertThat(off.errors())
                    .as("step 2: and says so on the record")
                    .anySatisfy(error -> assertThat(error)
                        .contains("security.nftables_enabled"));
                assertThat(off.contained())
                    .as("step 2: nothing was contained").isEmpty();
                assertThat(isRunning(docker, container))
                    .as("step 2: the pre-enforcement stack keeps running").isTrue();

                // 3. Kernel UNREADABLE: reported per workload as UNCONFIRMED, nothing
                //    contained -- refusing to answer is not evidence of a leak.
                WorkloadNetworkPolicy.overrideForTest(new WorkloadNetworkPolicy(
                    (args, stdin) -> new NftRunner.Result(2, "", "injected read failure"),
                    () -> true));
                VerifyWorkloadIsolation.HostOutcome unreadable =
                    VerifyWorkloadIsolation.sweep().get(0);
                assertThat(unreadable.errors())
                    .as("step 3: the unreadable kernel is reported UNCONFIRMED by name")
                    .anySatisfy(error -> assertThat(error)
                        .contains("stack '" + stackName + "'")
                        .contains("UNCONFIRMED")
                        .contains("injected read failure"));
                assertThat(unreadable.contained())
                    .as("step 3: nothing was contained").isEmpty();
                assertThat(isRunning(docker, container))
                    .as("step 3: the stack keeps running").isTrue();

                // 4. Diverged AND unrepairable: reads answer truthfully (the chains are
                //    gone), every write is refused. The sweep must STOP the stack.
                NftRunner real = netns.nftRunner();
                WorkloadNetworkPolicy.overrideForTest(new WorkloadNetworkPolicy(
                    (args, stdin) -> "list".equals(args.get(0))
                        ? real.run(args, stdin)
                        : new NftRunner.Result(1, "", "injected write failure"),
                    () -> true));
                VerifyWorkloadIsolation.HostOutcome contained =
                    VerifyWorkloadIsolation.sweep().get(0);
                assertThat(contained.errors())
                    .as("step 4: the refused repair is on the record")
                    .anySatisfy(error -> assertThat(error)
                        .contains("injected write failure"));
                assertThat(contained.contained())
                    .as("step 4: the stack was contained by name")
                    .contains("stopped stack '" + stackName + "'");
                assertThat(isRunning(docker, container))
                    .as("step 4: the stack's container is genuinely STOPPED at the daemon")
                    .isFalse();
                assertThat((String) Models.get(StackModel.class).findById(stackId)
                    .get(StackModel.STATUS))
                    .as("step 4: and the record says stopped, not active")
                    .isEqualTo(StackModel.STATUS_STOPPED);
            });
        } finally {
            WorkloadNetworkPolicy.overrideForTest(netns != null
                ? netns.enforcingPolicy() : null);
            cleanupAll(stacks, cleanupStack[0], -1, List.of());
        }
    }

    // -- fixtures ---------------------------------------------------------------

    private static int stackRecords(String stackName) {
        StackModel stacks = Models.get(StackModel.class);
        Row stack = stacks.createEmptyRow();
        stack.set(StackModel.NAME, stackName);
        stack.set(StackModel.ENABLED, true);
        stack.set(StackModel.SERVER_ID, ServerModel.localServerId());
        stacks.save(stack);
        StackServiceModel services = Models.get(StackServiceModel.class);
        Row service = services.createEmptyRow();
        service.set(StackServiceModel.STACK_ID, stack.get(StackModel.ID));
        service.set(StackServiceModel.NAME, "app");
        service.set(StackServiceModel.ENABLED, true);
        service.set(StackServiceModel.IMAGE, TEST_IMAGE);
        service.set(StackServiceModel.COMMAND, List.of("sleep", "600"));
        service.set(StackServiceModel.RESTART_POLICY, "unless-stopped");
        services.save(service);
        return stack.get(StackModel.ID);
    }

    private static StackSpec spec(int stackId) {
        return StackSpec.fromRecordsUnordered(Models.get(StackModel.class).findById(stackId));
    }

    private static int instanceRecord(String name) {
        Map<String, Object> settings = new LinkedHashMap<>();
        settings.put("image", "alpine");
        settings.put("tag", "latest");
        settings.put("command", "sleep 600");
        Row row = Models.get(InstanceModel.class).createEmptyRow();
        row.set(InstanceModel.NAME, name + "-" + System.nanoTime());
        row.set(InstanceModel.KIND, "hohenheim:docker_container");
        row.set(InstanceModel.SETTINGS, settings);
        Models.get(InstanceModel.class).save(row);
        return row.get(InstanceModel.ID);
    }

    private static int databaseRecord(String name) {
        Row row = Models.get(DatabaseModel.class).createEmptyRow();
        row.set(DatabaseModel.NAME, name);
        row.set(DatabaseModel.ENGINE, "redis");
        row.set(DatabaseModel.DB_USER, "app");
        row.set(DatabaseModel.DB_PASSWORD, "pw");
        row.set(DatabaseModel.DB_NAME, "app");
        row.set(DatabaseModel.STATUS, DatabaseModel.STATUS_ACTIVE);
        row.set(DatabaseModel.SERVER_ID, ServerModel.localServerId());
        Models.get(DatabaseModel.class).save(row);
        return row.get(DatabaseModel.ID);
    }

    private void cleanupAll(StackRuntime stacks, int stackId, int instanceId,
                            List<String> scratchNetworks) {
        if (stackId > 0) {
            try {
                stacks.destroy(stackId, true);
            } catch (Exception ignored) {
                // best effort
            }
        }
        if (instanceId > 0) {
            try {
                Db.run(datasource, () -> new InstanceService().destroy(instanceId));
            } catch (Exception ignored) {
                // best effort
            }
        }
        for (String network : scratchNetworks) {
            try {
                docker.removeNetwork(network);
            } catch (Exception ignored) {
                // best effort
            }
        }
    }

    // -- wiring (the StackNetworkIsolationTest topology, reduced to service + peer) --

    private static void wire(PrivateNetns netns, long service, long peer, String gateway,
                             String serviceAddress) throws IOException {
        netns.setup("sysctl", "-w", "net.ipv4.ip_forward=1");
        netns.setup("ip", "link", "set", "lo", "up");
        netns.setup("ip", "link", "add", "vt0", "type", "veth", "peer", "name", "vt1");
        netns.setup("ip", "link", "add", "vp0", "type", "veth", "peer", "name", "vp1");
        netns.setup("ip", "link", "set", "vt1", "netns", String.valueOf(service));
        netns.setup("ip", "link", "set", "vp1", "netns", String.valueOf(peer));
        netns.setup("ip", "addr", "add", gateway + "/32", "dev", "vt0");
        netns.setup("ip", "link", "set", "vt0", "up");
        netns.setup("ip", "route", "add", serviceAddress + "/32", "dev", "vt0");
        netns.setup("ip", "addr", "add", "169.254.169.1/24", "dev", "vp0");
        netns.setup("ip", "addr", "add", "203.0.113.1/24", "dev", "vp0");
        netns.setup("ip", "link", "set", "vp0", "up");
        netns.setupNested(service, "ip", "link", "set", "lo", "up");
        netns.setupNested(service, "ip", "addr", "add", serviceAddress + "/32", "dev", "vt1");
        netns.setupNested(service, "ip", "link", "set", "vt1", "up");
        netns.setupNested(service, "ip", "route", "add", gateway + "/32", "dev", "vt1");
        netns.setupNested(service, "ip", "route", "add", "default", "via", gateway);
        netns.setupNested(peer, "ip", "link", "set", "lo", "up");
        netns.setupNested(peer, "ip", "addr", "add", METADATA + "/24", "dev", "vp1");
        netns.setupNested(peer, "ip", "addr", "add", PUBLIC + "/24", "dev", "vp1");
        netns.setupNested(peer, "ip", "link", "set", "vp1", "up");
        netns.setupNested(peer, "ip", "route", "add", "default", "via", "169.254.169.1");
        netns.listen(peer, PEER_PORT);
    }

    // -- helpers ---------------------------------------------------------------

    private static boolean isRunning(DockerClient docker, String container) {
        try {
            Object state = docker.inspectContainer(container).get("State");
            return state instanceof Map<?, ?> map && Boolean.TRUE.equals(map.get("Running"));
        } catch (IOException absent) {
            return false;
        }
    }

    private static String subnetOf(Map<String, Object> network) {
        return ipamValue(network, "Subnet");
    }

    private static String gatewayOf(Map<String, Object> network) {
        return ipamValue(network, "Gateway");
    }

    private static String ipamValue(Map<String, Object> network, String field) {
        Object configs = network.get("IPAM") instanceof Map<?, ?> ipam
            ? ipam.get("Config") : null;
        if (configs instanceof List<?> list) {
            for (Object entry : list) {
                if (entry instanceof Map<?, ?> config
                        && config.get("Subnet") instanceof String subnet
                        && !subnet.contains(":")
                        && config.get(field) instanceof String value && !value.isBlank()) {
                    return value;
                }
            }
        }
        throw new IllegalStateException("network has no IPv4 " + field + ": " + network);
    }

    /** The host address {@code offset} above the subnet base. */
    private static String addressIn(String subnet, int offset) {
        String[] parts = subnet.split("/")[0].split("\\.");
        long base = 0;
        for (String part : parts) {
            base = (base << 8) | Integer.parseInt(part);
        }
        long address = base + offset;
        return ((address >> 24) & 0xff) + "." + ((address >> 16) & 0xff) + "."
            + ((address >> 8) & 0xff) + "." + (address & 0xff);
    }

    private static boolean imagePresent(DockerClient docker, String image) throws IOException {
        for (Object entry : docker.listImages()) {
            if (entry instanceof Map<?, ?> map && map.get("RepoTags") instanceof List<?> tags
                    && tags.contains(image)) {
                return true;
            }
        }
        return false;
    }

    /** Checked-exception plumbing: Db.run takes a Runnable, the daemon throws IOException. */
    private interface IoCall<T> {
        T get() throws IOException;
    }

    private interface IoVoid {
        void run() throws IOException;
    }

    private static <T> T io(IoCall<T> call) {
        try {
            return call.get();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static void io(IoVoid call) {
        try {
            call.run();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
