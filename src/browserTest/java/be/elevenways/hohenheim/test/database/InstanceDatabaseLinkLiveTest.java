package be.elevenways.hohenheim.test.database;

import be.elevenways.hohenheim.model.DatabaseModel;
import be.elevenways.hohenheim.model.InstanceDatabaseModel;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.PortAllocationModel;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.ports.PortLedger;
import be.elevenways.hohenheim.server.ControllerScope;
import be.elevenways.hohenheim.server.database.DatabaseService;
import be.elevenways.hohenheim.server.database.ManagedDatabase;
import be.elevenways.hohenheim.server.docker.DockerClient;
import be.elevenways.hohenheim.server.docker.DockerReconciler;
import be.elevenways.hohenheim.server.docker.OwnerLabels;
import be.elevenways.hohenheim.server.docker.ServerService;
import be.elevenways.hohenheim.server.instance.InstanceService;
import be.elevenways.hohenheim.server.runtime.ContainerState;
import be.elevenways.hohenheim.server.runtime.Egress;
import be.elevenways.hohenheim.server.runtime.InstanceStatus;
import be.elevenways.hohenheim.server.runtime.LinkNetworkSupport;
import be.elevenways.hohenheim.server.runtime.WorkloadNetworks;
import be.elevenways.hohenheim.server.security.WorkloadNetworkPolicy;
import be.elevenways.hohenheim.test.HohenheimTestRuntime;
import be.elevenways.hohenheim.test.host.HostFixtures;
import be.elevenways.hohenheim.test.network.PrivateNetns;
import be.elevenways.zenit.common.orm.datasource.Datasources;
import be.elevenways.zenit.common.orm.datasource.Db;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.Models;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The INSTANCE half of database attachments, against a REAL daemon and REAL packets --
 * the twin of {@code SiteDatabaseLinkLiveTest}, which is the only place the site lane's
 * equivalent claims were ever actually executed. A workload container joins its attached
 * database's link network, a real redis client INSIDE it authenticates and queries over
 * the injected address, an unattached database stays unreachable, the reconciler calls
 * the live link network OWNED rather than orphaned debris, and DETACHING revokes the
 * reachability at the daemon THERE AND THEN -- no redeploy, no release switch.
 *
 * AIDEV-NOTE: every reachability claim is asserted on the REDIS SERVER'S OWN REPLY
 * ({@code +OK}, {@code +PONG}, {@code -NOAUTH}), never on an exit code: busybox nc and
 * redis-cli both exit 0 while the server is refusing, which has bitten this repo twice.
 * Every negative carries a positive anchor taken with the SAME probe from the SAME
 * container, so "unreachable because the container is broken" cannot pass as isolation.
 *
 * AIDEV-NOTE: the class owns its own sqlite control plane, so its controller identity --
 * and therefore every daemon resource NAME it mints -- is unique to this fork. Nothing
 * here reads a daemon-wide listing or count; four forks share one daemon.
 */
class InstanceDatabaseLinkLiveTest {

    private static final Path SOCKET = Path.of(DockerClient.DEFAULT_SOCKET);
    private static final String REDIS_IMAGE = "redis:7-alpine";
    private static final String PASSWORD_A = "idblink-pw-a";
    private static final String PASSWORD_B = "idblink-pw-b";

    private static SqliteDatasource datasource;
    private static PrivateNetns netns;

    /** Link networks the PRODUCTION lanes should have reclaimed and did not. */
    private static final List<String> leftovers = new ArrayList<>();

    @BeforeAll
    static void setUp() throws Exception {
        File db = File.createTempFile("hohenheim-instance-dblink-live", ".db");
        db.delete();
        db.deleteOnExit();
        datasource = new SqliteDatasource("jdbc:sqlite:" + db.getAbsolutePath());
        new MigrationRunner(datasource).migrate().requireSuccess();
        Datasources.register(Datasources.DEFAULT, datasource);
        HohenheimTestRuntime.ensureBooted();
        netns = PrivateNetns.installEnforcing();
    }

    @AfterAll
    static void tearDown() {
        PrivateNetns.uninstall(netns);
        netns = null;
        // Reported HERE and not in the journey's finally: this is a separate verdict, so
        // it can never replace the assertion that actually named the defect.
        assertThat(leftovers)
            .as("this class left no link network of its own behind (teardown removed them)")
            .isEmpty();
    }

    @Test
    void anInstanceReachesExactlyItsAttachedDatabasesAndADetachRevokesThatNow()
            throws Exception {
        assumeTrue(Files.exists(SOCKET), "Docker socket not present");
        DockerClient docker = new DockerClient();
        assumeTrue(imagePresent(docker, "alpine:latest"), "alpine:latest not present locally");
        assumeTrue(imagePresent(docker, REDIS_IMAGE), REDIS_IMAGE + " not present locally");
        assumeTrue(netns != null,
            "no private netns: the instance tier refuses to deploy unprotected");

        Db.run(datasource, () -> {
            try {
                journey(docker);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
    }

    private static void journey(DockerClient docker) throws IOException {
        HostFixtures.admitLocal();
        DatabaseService service = new DatabaseService();
        String dbA = "idblinka" + System.nanoTime();
        String dbB = "idblinkb" + System.nanoTime();
        Integer instanceId = null;
        String instanceHandle = null;
        try {
            service.create(dbA, ManagedDatabase.Engine.REDIS, REDIS_IMAGE,
                "unused", PASSWORD_A, "0", true, ServerService.LOCAL);
            service.create(dbB, ManagedDatabase.Engine.REDIS, REDIS_IMAGE,
                "unused", PASSWORD_B, "0", true, ServerService.LOCAL);
            int idA = databaseId(dbA);
            int idB = databaseId(dbB);
            String engineA = EngineHandles.of(dbA);
            String engineB = EngineHandles.of(dbB);

            instanceId = workloadRecord("idblink-workload");
            // Effectively-final twin: the nullable local above is what the finally reads,
            // this is what the probes and lambdas below close over.
            final int workload = instanceId;
            instanceHandle = ControllerScope.handle(ControllerScope.KIND_INSTANCE, workload);
            int linkA = attach(workload, idA, "DB");

            // 1. Deploy WITH database A attached: the pre-start hook mints the link
            //    network and joins both members before the workload ever runs.
            InstanceStatus deployed = new InstanceService().deploy(workload);
            assertThat(deployed.state())
                .as("step 1: the workload runs").isEqualTo(ContainerState.RUNNING);
            String netA = WorkloadNetworks.networkName(linkHandle(workload, idA));
            assertThat(ioQuiet(() -> docker.findNetworkByName(netA)))
                .as("step 1: the (instance, database A) pair has its link network")
                .isNotNull();

            // 2. The injected environment is container-network shaped: the engine's
            //    container hostname and its own port, never a published loopback one.
            Map<String, String> env = containerEnv(docker, instanceHandle);
            assertThat(env).as("step 2: DB_HOST is the engine container hostname")
                .containsEntry("DB_HOST", engineA);
            assertThat(env).as("step 2: DB_PORT is redis's own port")
                .containsEntry("DB_PORT", "6379");
            assertThat(env).as("step 2: DATABASE_URL carries the same address")
                .containsEntry("DATABASE_URL", "redis://:" + PASSWORD_A + "@" + engineA + ":6379");

            // 3. A REAL client INSIDE the workload authenticates and round-trips a
            //    value over the injected address. The server's own replies are the
            //    evidence; redis answers errors with exit code 0.
            String transcript = redisFrom(docker, instanceHandle,
                "$DB_HOST", "$DB_PORT", PASSWORD_A);
            assertThat(transcript)
                .as("step 3: AUTH and SET succeeded against the injected address")
                .contains("+OK");
            assertThat(transcript)
                .as("step 3: the query round-tripped the stored value back")
                .contains("forty-two");
            assertThat(transcript)
                .as("step 3: no auth failure hid behind a zero exit")
                .doesNotContain("NOAUTH").doesNotContain("-ERR");

            // 4. Isolation: the UNATTACHED database B is not reachable. The anchor is
            //    taken FIRST with the identical probe against the ATTACHED database,
            //    so a broken probe cannot masquerade as isolation.
            String addressA = addressOn(docker, engineA, netA);
            String addressB = anyAddress(docker, engineB);
            assertThat(connects(docker, instanceHandle, addressA))
                .as("step 4 anchor: the probe reaches the ATTACHED database A")
                .isTrue();
            assertThat(connects(docker, instanceHandle, addressB))
                .as("step 4: the workload cannot reach the UNATTACHED database B (%s)",
                    addressB)
                .isFalse();

            // 5. THE RECONCILER. A live link network of a healthy attachment must be
            //    OWNED: the bucket the operator's remove-orphan action reads is
            //    ORPHANED, so a mis-bucketed live network is a delete button pointed
            //    at a running attachment.
            DockerReconciler.Finding findingA = classifyNetwork(docker, netA);
            assertThat(findingA.bucket())
                .as("step 5: the live link network is OWNED, not orphan debris")
                .isEqualTo(DockerReconciler.Bucket.OWNED);
            assertThat(findingA.owner())
                .as("step 5: and it is attributed to its ATTACHMENT row")
                .isNotNull()
                .satisfies(owner -> {
                    assertThat(owner.model()).isEqualTo(InstanceDatabaseModel.MODEL_ID);
                    assertThat(owner.id()).isEqualTo(String.valueOf(linkA));
                });

            // 6. A SECOND attachment on its own family, one redeploy later: both
            //    databases answer real queries over their own link networks.
            int idbLinkB = attach(workload, idB, "CACHE");
            new InstanceService().deploy(workload);
            String netB = WorkloadNetworks.networkName(linkHandle(workload, idB));
            assertThat(ioQuiet(() -> docker.findNetworkByName(netB)))
                .as("step 6: the second pair has its own link network").isNotNull();
            assertThat(redisFrom(docker, instanceHandle, "$CACHE_HOST", "$CACHE_PORT",
                    PASSWORD_B))
                .as("step 6: a real query works against the SECOND database too")
                .contains("+OK").contains("forty-two");
            assertThat(classifyNetwork(docker, netB).bucket())
                .as("step 6: the second link network is OWNED too")
                .isEqualTo(DockerReconciler.Bucket.OWNED);
            // Addresses are re-read: a deploy replaces the container and every
            // membership with it.
            String freshA = addressOn(docker, engineA,
                WorkloadNetworks.networkName(linkHandle(workload, idA)));
            String freshB = addressOn(docker, engineB, netB);

            // 7. THE DETACH, with NO redeploy: dropping the attachment row is the
            //    whole operator gesture, so it has to be the whole revocation.
            Models.get(InstanceDatabaseModel.class).find()
                .where(InstanceDatabaseModel.ID.eq(linkA)).delete();
            assertThat(ioQuiet(() -> docker.findNetworkByName(
                    WorkloadNetworks.networkName(linkHandle(workload, idA)))))
                .as("step 7: the detached pair's link network is gone from the daemon")
                .isNull();
            assertThat(connects(docker, instanceHandle, freshA))
                .as("step 7: the still-running workload can no longer reach database A")
                .isFalse();
            assertThat(connects(docker, instanceHandle, freshB))
                .as("step 7 anchor: it still reaches the database it is STILL attached"
                    + " to, so step 7 is a revocation and not a dead container")
                .isTrue();
            assertThat(redisAnswers(docker, engineA, PASSWORD_A))
                .as("step 7: database A itself is untouched by the detach")
                .isTrue();

            // 8. The disconnect moved the workload's own ephemeral published port;
            //    the ledger must have followed it, or the control plane now points
            //    operators and proxies at a port nobody is listening on.
            InstanceStatus after = new InstanceService().liveStatus(workload);
            assertThat(after.publishedPort())
                .as("step 8: the workload still publishes a port").isNotNull();
            assertThat(heldPorts(workload))
                .as("step 8: the ledger claim tracks the port the daemon publishes NOW")
                .containsExactly(after.publishedPort());

            // 9. A LEAKED link network -- what an earlier failed sweep leaves behind --
            //    is reclaimed by destroy even though no attachment row names it. The
            //    detach above removed the last row, so this is exactly the
            //    "no rows, nothing to do" shortcut that used to skip the sweep.
            Models.get(InstanceDatabaseModel.class).find()
                .where(InstanceDatabaseModel.ID.eq(idbLinkB)).delete();
            assertThat(linksOf(workload))
                .as("step 9: the instance holds no attachment row at all now").isEmpty();
            String leaked = linkHandle(workload, idB);
            InstanceService.Resolved resolved = new InstanceService().resolve(workload);
            assertThat(resolved.runtime()).isInstanceOf(LinkNetworkSupport.class);
            ((LinkNetworkSupport) resolved.runtime()).ensureLinkNetwork(leaked,
                OwnerLabels.of(InstanceDatabaseModel.MODEL_ID, idbLinkB), Egress.NONE);
            assertThat(ioQuiet(() -> docker.findNetworkByName(
                    WorkloadNetworks.networkName(leaked))))
                .as("step 9: the leak is really there before destroy runs").isNotNull();
            new InstanceService().destroy(workload);
            assertThat(ioQuiet(() -> docker.findNetworkByName(
                    WorkloadNetworks.networkName(leaked))))
                .as("step 9: destroy reclaimed the leaked link network")
                .isNull();
        } finally {
            cleanUp(docker, service, instanceId, instanceHandle, dbA, dbB);
        }
    }

    // -- helpers ----------------------------------------------------------------

    /** Mirrors InstanceDatabaseNetworks.linkHandle (deliberately re-spelled: the naming
     *  scheme is a daemon-side contract the sweep and the reconciler both rely on). */
    private static String linkHandle(int instanceId, int databaseId) {
        return ControllerScope.handle(ControllerScope.KIND_INSTANCE_DBLINK,
            instanceId + "-" + databaseId);
    }

    private static int workloadRecord(String name) {
        Model instances = Models.get(InstanceModel.class);
        Row row = instances.createEmptyRow();
        Map<String, Object> settings = new LinkedHashMap<>();
        settings.put("image", "alpine");
        settings.put("tag", "latest");
        settings.put("command", "sleep 600");
        settings.put("container_port", 8080);
        row.set(InstanceModel.NAME, name);
        row.set(InstanceModel.KIND, "hohenheim:docker_container");
        row.set(InstanceModel.SERVER_ID, ServerModel.localServerId());
        row.set(InstanceModel.SETTINGS, settings);
        instances.save(row);
        return row.get(InstanceModel.ID);
    }

    private static int attach(int instanceId, int databaseId, String prefix) {
        Model links = Models.get(InstanceDatabaseModel.class);
        Row row = links.createEmptyRow();
        row.set(InstanceDatabaseModel.INSTANCE_ID, instanceId);
        row.set(InstanceDatabaseModel.DATABASE_ID, databaseId);
        row.set(InstanceDatabaseModel.ENV_PREFIX, prefix);
        links.save(row);
        return row.get(InstanceDatabaseModel.ID);
    }

    private static List<Row> linksOf(int instanceId) {
        return Models.get(InstanceDatabaseModel.class).findByInstanceId(instanceId);
    }

    private static int databaseId(String name) {
        Row row = Models.get(DatabaseModel.class).findByName(name);
        assertThat(row).as("database record '%s' exists", name).isNotNull();
        return row.get(DatabaseModel.ID);
    }

    /** The ports the ledger says this instance HOLDS (parked releases excluded). */
    private static List<Integer> heldPorts(int instanceId) {
        return PortLedger.claimsOf(InstanceModel.MODEL_ID, instanceId).stream()
            .filter(claim -> !PortLedger.isReleasing(claim))
            .map(claim -> (Integer) claim.get(PortAllocationModel.PORT))
            .toList();
    }

    /** One network classified by the PRODUCTION resolver over the REAL records. */
    private static DockerReconciler.Finding classifyNetwork(DockerClient docker, String name)
            throws IOException {
        Object labels = docker.inspectNetwork(name).get("Labels");
        return DockerReconciler.classify(DockerReconciler.KIND_NETWORK, name,
            labels instanceof Map<?, ?> map ? map : null,
            new DockerReconciler.ModelRecords());
    }

    private static Map<String, String> containerEnv(DockerClient docker, String handle)
            throws IOException {
        Map<String, Object> inspect = docker.inspectContainer(handle);
        Map<String, String> env = new LinkedHashMap<>();
        if (inspect.get("Config") instanceof Map<?, ?> config
                && config.get("Env") instanceof List<?> entries) {
            for (Object entry : entries) {
                String text = String.valueOf(entry);
                int eq = text.indexOf('=');
                if (eq > 0) {
                    env.put(text.substring(0, eq), text.substring(eq + 1));
                }
            }
        }
        return env;
    }

    /**
     * A REAL redis conversation (AUTH, SET, GET) from INSIDE the workload container
     * against shell-expanded expressions, so "$DB_HOST" proves the INJECTED variable is
     * what connects rather than an address the test worked out for itself.
     */
    private static String redisFrom(DockerClient docker, String handle, String hostExpr,
                                    String portExpr, String password) throws IOException {
        String script = "printf 'AUTH " + password + "\\r\\nSET hohenheim_probe forty-two\\r\\n"
            + "GET hohenheim_probe\\r\\nQUIT\\r\\n' | /bin/busybox nc -w 5 "
            + hostExpr + " " + portExpr;
        DockerClient.ExecResult result = docker.exec(handle,
            List.of("/bin/sh", "-c", script), List.of());
        return result.stdout() + result.stderr();
    }

    /**
     * TCP reachability from inside a container, asserted on the SERVER'S OWN REPLY: a
     * redis on the other end answers PING with {@code +PONG} or, unauthenticated, with
     * {@code -NOAUTH Authentication required.} -- nothing else produces either string,
     * while busybox nc's exit code says 0 for a connect that never happened.
     */
    private static boolean connects(DockerClient docker, String handle, String address)
            throws IOException {
        DockerClient.ExecResult result = docker.exec(handle, List.of("/bin/sh", "-c",
            "printf 'PING\\r\\nQUIT\\r\\n' | /bin/busybox nc -w 3 " + address + " 6379"),
            List.of());
        String reply = result.stdout() + result.stderr();
        return reply.contains("PONG") || reply.contains("NOAUTH");
    }

    /** Whether the engine container itself still answers an authenticated PING. */
    private static boolean redisAnswers(DockerClient docker, String handle, String password) {
        try {
            DockerClient.ExecResult result = docker.exec(handle,
                List.of("redis-cli", "ping"), List.of("REDISCLI_AUTH=" + password));
            return result.stdout().contains("PONG");
        } catch (IOException e) {
            return false;
        }
    }

    private static String addressOn(DockerClient docker, String handle, String network)
            throws IOException {
        Object settings = docker.inspectContainer(handle).get("NetworkSettings");
        Object networks = settings instanceof Map<?, ?> map ? map.get("Networks") : null;
        if (networks instanceof Map<?, ?> map && map.get(network) instanceof Map<?, ?> endpoint
                && endpoint.get("IPAddress") instanceof String ip && !ip.isBlank()) {
            return ip;
        }
        throw new IllegalStateException(handle + " has no address on " + network);
    }

    private static String anyAddress(DockerClient docker, String handle) throws IOException {
        Object settings = docker.inspectContainer(handle).get("NetworkSettings");
        Object networks = settings instanceof Map<?, ?> map ? map.get("Networks") : null;
        if (networks instanceof Map<?, ?> map) {
            for (Object endpoint : map.values()) {
                if (endpoint instanceof Map<?, ?> e
                        && e.get("IPAddress") instanceof String ip && !ip.isBlank()) {
                    return ip;
                }
            }
        }
        throw new IllegalStateException(handle + " has no address");
    }

    /**
     * Leave the daemon as this class found it, scoped to the handles it minted -- and
     * RECORD what production failed to reclaim, for {@link #tearDown} to answer for.
     *
     * AIDEV-NOTE: the leftover check must NOT assert here. This runs in a finally, where
     * a throw REPLACES whatever the journey was failing on, so asserting here turned
     * every real defect into "teardown found a network" and hid the step that named it.
     */
    private static void cleanUp(DockerClient docker, DatabaseService service,
                                Integer instanceId, String instanceHandle,
                                String... databases) {
        if (instanceId != null) {
            try {
                new InstanceService().destroy(instanceId);
            } catch (RuntimeException ignored) {
                // already destroyed by the journey; the assertions are the outcome
            }
        }
        if (instanceHandle != null) {
            try {
                docker.removeContainer(instanceHandle, true);
            } catch (IOException ignored) {
                // already gone
            }
            try {
                docker.removeNetwork(WorkloadNetworks.networkName(instanceHandle));
            } catch (IOException ignored) {
                // already gone
            }
        }
        for (String name : databases) {
            try {
                service.destroy(name, true);
            } catch (Exception ignored) {
                // best effort
            }
        }
        if (instanceId != null) {
            String prefix = ControllerScope.kindPrefix(ControllerScope.KIND_INSTANCE_DBLINK)
                + instanceId + "-";
            for (String network : networksUnder(docker, prefix)) {
                leftovers.add(network);
                try {
                    docker.removeNetwork(network);
                } catch (IOException ignored) {
                    // the daemon keeps it; the AfterAll assertion is what reports it
                }
            }
        }
    }

    private static List<String> networksUnder(DockerClient docker, String prefix) {
        return ioQuiet(() -> docker.listNetworks()).stream()
            .filter(entry -> entry instanceof Map<?, ?> map
                && map.get("Name") instanceof String name && name.startsWith(prefix))
            .map(entry -> String.valueOf(((Map<?, ?>) entry).get("Name")))
            .toList();
    }

    private static boolean imagePresent(DockerClient docker, String tag) throws IOException {
        for (Object image : docker.listImages()) {
            Object repoTags = ((Map<?, ?>) image).get("RepoTags");
            if (repoTags instanceof List<?> tags && tags.contains(tag)) {
                return true;
            }
        }
        return false;
    }

    private interface IoCall<T> {
        T get() throws IOException;
    }

    private static <T> T ioQuiet(IoCall<T> call) {
        try {
            return call.get();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
