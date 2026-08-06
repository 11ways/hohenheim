package be.elevenways.hohenheim.test.docker;

import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.hohenheim.model.DatabaseModel;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.model.SiteDatabaseModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.server.HohenheimDatabase;
import be.elevenways.hohenheim.server.database.DatabaseService;
import be.elevenways.hohenheim.server.database.ManagedDatabase;
import be.elevenways.hohenheim.server.docker.DockerClient;
import be.elevenways.hohenheim.server.docker.DockerSiteRequestHandler;
import be.elevenways.hohenheim.server.docker.SiteInstances;
import be.elevenways.hohenheim.server.runtime.WorkloadNetworks;
import be.elevenways.hohenheim.server.security.WorkloadNetworkPolicy;
import be.elevenways.hohenheim.test.LiveIdOffsets;
import be.elevenways.hohenheim.test.ProxyTestSupport;
import be.elevenways.hohenheim.test.network.PrivateNetns;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.orm.query.SortOrder;
import be.elevenways.zenit.common.validation.Violations;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The Docker-site half of database attachments, against a REAL daemon and REAL
 * packets: a site release container joins its attached database's link network, the
 * injected environment carries the container-network address, and a real client
 * INSIDE the site container authenticates and runs a query over it -- while an
 * unattached database on the same host stays unreachable (with a positive anchor
 * proving the probe). Detaching removes the reachability AND the link network;
 * attaching a second database extends it; a cross-host attachment refuses by name.
 *
 * AIDEV-NOTE: the site container image is the TestImages busybox responder (health
 * gate needs an HTTP 200 on 8080); the redis "client" is busybox nc speaking real
 * RESP with AUTH/SET/GET, asserted on the SERVER'S replies, never on an exit code.
 */
class SiteDatabaseLinkLiveTest {

    private static final Path SOCKET = Path.of(DockerClient.DEFAULT_SOCKET);
    private static final String REDIS_IMAGE = "redis:7-alpine";

    /** Fabricated site ids, unique to this class (one class runs in one fork). */
    private static final int SITE_ID = 957_101;
    private static final int CROSS_HOST_SITE_ID = 957_102;

    private static boolean booted;
    private static PrivateNetns netns;
    private static Integer savedProbeTimeout;
    private static Integer savedProbeInterval;
    private static Integer savedDrain;

    @BeforeAll
    static void boot() throws Exception {
        if (!booted) {
            booted = true;
            ProxyTestSupport.bootRuntime();
            LiveIdOffsets.apply(HohenheimDatabase.datasource());
        }
        if (PrivateNetns.available()) {
            netns = new PrivateNetns();
            WorkloadNetworkPolicy.overrideForTest(netns.enforcingPolicy());
        }
        savedProbeTimeout = HohenheimSettings.VALUES.getValue(
            HohenheimSettings.Releases.PROBE_TIMEOUT_SECONDS);
        savedProbeInterval = HohenheimSettings.VALUES.getValue(
            HohenheimSettings.Releases.PROBE_INTERVAL_MS);
        savedDrain = HohenheimSettings.VALUES.getValue(HohenheimSettings.Releases.DRAIN_SECONDS);
        HohenheimSettings.VALUES.setValue(HohenheimSettings.Releases.PROBE_TIMEOUT_SECONDS, 6);
        HohenheimSettings.VALUES.setValue(HohenheimSettings.Releases.PROBE_INTERVAL_MS, 100);
        HohenheimSettings.VALUES.setValue(HohenheimSettings.Releases.DRAIN_SECONDS, 2);
    }

    @AfterAll
    static void restore() {
        WorkloadNetworkPolicy.overrideForTest(null);
        if (netns != null) {
            netns.close();
            netns = null;
        }
        HohenheimSettings.VALUES.setValue(
            HohenheimSettings.Releases.PROBE_TIMEOUT_SECONDS, savedProbeTimeout);
        HohenheimSettings.VALUES.setValue(
            HohenheimSettings.Releases.PROBE_INTERVAL_MS, savedProbeInterval);
        HohenheimSettings.VALUES.setValue(HohenheimSettings.Releases.DRAIN_SECONDS, savedDrain);
    }

    @Test
    void dockerSiteReachesExactlyItsAttachedDatabases() throws Exception {
        assumeTrue(Files.exists(SOCKET), "Docker socket not present");
        DockerClient docker = new DockerClient();
        assumeTrue(imagePresent(docker, "alpine:latest"), "alpine:latest not present locally");
        assumeTrue(imagePresent(docker, REDIS_IMAGE), REDIS_IMAGE + " not present locally");
        assumeTrue(netns != null,
            "no private netns: PRIVATE workloads refuse to deploy without enforcement");

        DatabaseService service = new DatabaseService();
        String dbA = "sitelink-a-" + System.nanoTime();
        String dbB = "sitelink-b-" + System.nanoTime();
        String handleA = ManagedDatabase.containerHandle(dbA);
        String handleB = ManagedDatabase.containerHandle(dbB);
        String repo = "hohenheim-dblink-img-" + System.nanoTime();
        String digest = TestImages.loadHttpServer(docker, repo + ":latest", "dblink-site");
        Integer linkAId = null;

        try {
            service.create(dbA, ManagedDatabase.Engine.REDIS, REDIS_IMAGE,
                "unused", "linkpw", "0", true, ServerModel.MODE_LOCAL);
            service.create(dbB, ManagedDatabase.Engine.REDIS, REDIS_IMAGE,
                "unused", "decoypw", "0", true, ServerModel.MODE_LOCAL);
            Integer idA = databaseId(dbA);
            Integer idB = databaseId(dbB);

            // 1. Attach database A and deploy the site: the release deploys healthy.
            linkAId = link(SITE_ID, idA, "DB");
            DockerSiteRequestHandler first =
                new DockerSiteRequestHandler(SITE_ID, "dblink-site", settingsFor(repo));
            assertThat(first.getUpstream())
                .as("step 1: the site deploys and serves with a database attached")
                .isNotNull();
            String siteHandle = "hohenheim-instance-" + first.getInstanceId();

            // 2. The injected environment is container-network shaped: the database's
            //    container hostname and the engine's own port, never 127.0.0.1.
            Map<String, String> env = containerEnv(docker, siteHandle);
            assertThat(env).as("step 2: DB_HOST is the database container hostname")
                .containsEntry("DB_HOST", handleA);
            assertThat(env).as("step 2: DB_PORT is redis's own port, not a published one")
                .containsEntry("DB_PORT", "6379");
            assertThat(env).as("step 2: DATABASE_URL carries the same address")
                .containsEntry("DATABASE_URL", "redis://:linkpw@" + handleA + ":6379");
            assertThat(env.entrySet().stream()
                .filter(e -> e.getKey().startsWith("DB") || e.getKey().equals("DATABASE_URL")))
                .as("step 2: no injected variable is 127.0.0.1-shaped")
                .noneMatch(e -> e.getValue().contains("127.0.0.1"));

            // 3. A REAL client inside the site container authenticates and runs a
            //    query over the injected address: the server's own replies are the
            //    evidence (never an exit code -- redis answers errors with exit 0).
            String transcript = redisFromSite(docker, siteHandle,
                "$DB_HOST", "$DB_PORT", "linkpw");
            assertThat(transcript)
                .as("step 3: AUTH and SET succeeded against the injected address")
                .contains("+OK");
            assertThat(transcript)
                .as("step 3: the query round-tripped the stored value back")
                .contains("forty-two");
            assertThat(transcript)
                .as("step 3: no auth failure hid behind a zero exit")
                .doesNotContain("NOAUTH").doesNotContain("-ERR");

            // 4. Isolation kept: the UNATTACHED database B is not reachable from the
            //    site container. Positive anchor first: the very same TCP probe DOES
            //    reach the attached database A over the link network, so a false
            //    negative cannot hide behind a broken probe.
            String linkNetA = WorkloadNetworks.networkName(linkHandle(SITE_ID, idA));
            String addressA = addressOn(docker, handleA, linkNetA);
            assertThat(connects(docker, siteHandle, addressA, 6379))
                .as("step 4 anchor: the probe reaches the ATTACHED database")
                .isTrue();
            String addressB = anyAddress(docker, handleB);
            assertThat(connects(docker, siteHandle, addressB, 6379))
                .as("step 4: the site cannot open a TCP connection to the UNATTACHED"
                    + " database B (" + addressB + ":6379)")
                .isFalse();

            // 5. DETACH: the attachment row is the authority. Removing it changes the
            //    source fingerprint, the next convergence releases a container without
            //    the join or the variables, and the drain sweep removes the network.
            Models.get(SiteDatabaseModel.class).find()
                .where(SiteDatabaseModel.ID.eq(linkAId)).delete();
            linkAId = null;
            DockerSiteRequestHandler detached =
                new DockerSiteRequestHandler(SITE_ID, "dblink-site", settingsFor(repo));
            assertThat(detached.getUpstream())
                .as("step 5: the site releases cleanly after the detach").isNotNull();
            assertThat(detached.getInstanceId())
                .as("step 5: the detach really produced a NEW release")
                .isNotEqualTo(first.getInstanceId());
            String detachedHandle = "hohenheim-instance-" + detached.getInstanceId();
            assertThat(containerEnv(docker, detachedHandle))
                .as("step 5: the new release carries no database variables")
                .doesNotContainKey("DB_HOST").doesNotContainKey("DATABASE_URL");
            assertThat(connects(docker, detachedHandle, addressA, 6379))
                .as("step 5: the new release cannot reach the detached database")
                .isFalse();
            await("step 5: the drain sweep removes the stale link network", 20_000,
                () -> ioQuiet(() -> docker.findNetworkByName(linkNetA)) == null);
            assertThat(redisAnswers(docker, handleA, "linkpw"))
                .as("step 5: the database itself is untouched by the detach")
                .isTrue();

            // 6. ATTACH TWO: re-attach A and additionally B; one release later the
            //    site reaches BOTH families over their own link networks.
            linkAId = link(SITE_ID, idA, "DB");
            Integer linkBId = link(SITE_ID, idB, "CACHE");
            DockerSiteRequestHandler both =
                new DockerSiteRequestHandler(SITE_ID, "dblink-site", settingsFor(repo));
            assertThat(both.getUpstream())
                .as("step 6: the site releases with two attachments").isNotNull();
            String bothHandle = "hohenheim-instance-" + both.getInstanceId();
            Map<String, String> bothEnv = containerEnv(docker, bothHandle);
            assertThat(bothEnv)
                .as("step 6: both families are injected")
                .containsEntry("DB_HOST", handleA)
                .containsEntry("CACHE_HOST", handleB)
                .containsEntry("CACHE_PORT", "6379");
            String second = redisFromSite(docker, bothHandle,
                "$CACHE_HOST", "$CACHE_PORT", "decoypw");
            assertThat(second)
                .as("step 6: a real query works against the SECOND database too")
                .contains("+OK").contains("forty-two");
            assertThat(ioQuiet(() -> docker.findNetworkByName(
                    WorkloadNetworks.networkName(linkHandle(SITE_ID, idB)))))
                .as("step 6: the second pair has its own link network").isNotNull();
            Models.get(SiteDatabaseModel.class).find()
                .where(SiteDatabaseModel.ID.eq(linkBId)).delete();

            // 7. CROSS-HOST: a database on another server refuses the deploy BY NAME
            //    instead of injecting an address that cannot work.
            Row phantom = Models.get(ServerModel.class).createEmptyRow();
            phantom.set(ServerModel.NAME, "dblink-phantom-" + System.nanoTime());
            phantom.set(ServerModel.MODE, ServerModel.MODE_SSH);
            phantom.set(ServerModel.SSH_TARGET, "nobody@phantom.invalid");
            Models.get(ServerModel.class).save(phantom);
            Row rowB = Models.get(DatabaseModel.class).find()
                .where(DatabaseModel.ID.eq(idB)).first();
            rowB.set(DatabaseModel.SERVER_ID, phantom.get(ServerModel.ID));
            Models.get(DatabaseModel.class).save(rowB);
            link(CROSS_HOST_SITE_ID, idB, "DB");
            // The refusal identity is asserted on the converger itself (the request
            // handler would swallow it into a DOWN site).
            assertThatThrownBy(() -> SiteInstances.ensureRunning(
                    CROSS_HOST_SITE_ID, "dblink-cross", settingsFor(repo)))
                .as("step 7: a cross-host attachment refuses the deploy by name")
                .isInstanceOf(Violations.class)
                .hasMessageContaining("Cross-host site-to-database links are not supported")
                .hasMessageContaining(dbB);
            Row crossServing = servingOf(CROSS_HOST_SITE_ID);
            assertThat(crossServing)
                .as("step 7: the refused release left a visible record").isNotNull();
            assertThat((String) crossServing.get(InstanceModel.STATUS))
                .as("step 7: stamped as an ERROR, never a silent success")
                .isEqualTo(InstanceModel.STATUS_ERROR);
            assertThat(isRunning(docker,
                    "hohenheim-instance-" + crossServing.get(InstanceModel.ID)))
                .as("step 7: and no workload runs at the daemon for it")
                .isFalse();
            rowB.set(DatabaseModel.SERVER_ID, ServerModel.localServerId());
            Models.get(DatabaseModel.class).save(rowB);
        } finally {
            destroyQuietly(SITE_ID);
            destroyQuietly(CROSS_HOST_SITE_ID);
            Models.get(SiteDatabaseModel.class).find()
                .where(SiteDatabaseModel.SITE_ID.eq(SITE_ID)).delete();
            Models.get(SiteDatabaseModel.class).find()
                .where(SiteDatabaseModel.SITE_ID.eq(CROSS_HOST_SITE_ID)).delete();
            destroyDatabaseQuietly(service, dbA);
            destroyDatabaseQuietly(service, dbB);
            removeImageQuietly(docker, repo + ":latest");
            removeImageQuietly(docker, digest);
        }
    }

    // -- helpers ----------------------------------------------------------------

    private static Map<String, Object> settingsFor(String repo) {
        Map<String, Object> settings = new LinkedHashMap<>();
        settings.put("image", repo);
        settings.put("tag", "latest");
        settings.put("container_port", 8080);
        return settings;
    }

    /** Mirrors SiteDatabaseNetworks.linkHandle (deliberately re-spelled: the naming
     *  scheme is a public daemon-side contract the sweep and reconciler rely on). */
    private static String linkHandle(int siteId, int databaseId) {
        return "hohenheim-dblink-" + siteId + "-" + databaseId;
    }

    private static Integer databaseId(String name) {
        Row row = Models.get(DatabaseModel.class).findByName(name);
        assertThat(row).as("database record '%s' exists", name).isNotNull();
        return row.get(DatabaseModel.ID);
    }

    private static Integer link(int siteId, Integer databaseId, String prefix) {
        SiteDatabaseModel links = Models.get(SiteDatabaseModel.class);
        Row row = links.createEmptyRow();
        row.set(SiteDatabaseModel.SITE_ID, siteId);
        row.set(SiteDatabaseModel.DATABASE_ID, databaseId);
        row.set(SiteDatabaseModel.ENV_PREFIX, prefix);
        links.save(row);
        return row.get(SiteDatabaseModel.ID);
    }

    /** The container's configured environment, parsed from the daemon's inspect. */
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
     * Run a REAL redis conversation (AUTH, SET, GET) from INSIDE the site container
     * against the given host/port expressions (shell-expanded in the container, so
     * "$DB_HOST" proves the injected variable itself is what connects).
     */
    private static String redisFromSite(DockerClient docker, String siteHandle,
                                        String hostExpr, String portExpr, String password)
            throws IOException {
        String script = "printf 'AUTH " + password + "\\r\\nSET hohenheim_probe forty-two\\r\\n"
            + "GET hohenheim_probe\\r\\nQUIT\\r\\n' | /bin/busybox nc -w 5 " + hostExpr + " " + portExpr;
        DockerClient.ExecResult result = docker.exec(siteHandle,
            List.of("/bin/sh", "-c", script), List.of());
        return result.stdout() + result.stderr();
    }

    /** Whether the database container itself still answers an authenticated PING. */
    private static boolean redisAnswers(DockerClient docker, String handle, String password) {
        try {
            DockerClient.ExecResult result = docker.exec(handle,
                List.of("redis-cli", "ping"), List.of("REDISCLI_AUTH=" + password));
            return result.stdout().contains("PONG");
        } catch (IOException e) {
            return false;
        }
    }

    /** TCP connect probe from inside a container (the DatabaseNetworkIsolationTest shape). */
    private static boolean connects(DockerClient docker, String handle, String address, int port)
            throws IOException {
        return docker.exec(handle,
            List.of("/bin/sh", "-c", "/bin/busybox nc -w 2 " + address + " " + port + " </dev/null"),
            List.of()).exitCode() == 0;
    }

    /** A container's IP on one specific network. */
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

    /** Any address of a container (its own private network). */
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

    private static Row servingOf(int siteId) {
        return Models.get(InstanceModel.class).find()
            .where(InstanceModel.GENERATED_FOR_MODEL.eq(SiteModel.MODEL_ID.toString()))
            .where(InstanceModel.GENERATED_FOR_ID.eq(siteId))
            .where(InstanceModel.RUNTIME_ROLE.eq(InstanceModel.ROLE_SERVING))
            .where(InstanceModel.DELETED_AT.isNull())
            .orderBy(InstanceModel.ID, SortOrder.DESC)
            .first();
    }

    private static boolean isRunning(DockerClient docker, String handle) {
        try {
            Map<String, Object> inspect = docker.inspectContainer(handle);
            return inspect.get("State") instanceof Map<?, ?> state
                && Boolean.TRUE.equals(state.get("Running"));
        } catch (IOException gone) {
            return false;
        }
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

    private static void await(String what, long timeoutMs, BooleanSupplier condition)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(200);
        }
        assertThat(condition.getAsBoolean()).as(what).isTrue();
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

    private static void destroyQuietly(int siteId) {
        try {
            SiteInstances.destroyFor(siteId);
        } catch (RuntimeException ignored) {
            // teardown best effort; the assertions are the outcome
        }
    }

    private static void destroyDatabaseQuietly(DatabaseService service, String name) {
        try {
            service.destroy(name, true);
        } catch (Exception ignored) {
            // best effort
        }
    }

    private static void removeImageQuietly(DockerClient docker, String reference) {
        try {
            docker.removeImage(reference, true);
        } catch (IOException ignored) {
            // already gone or still referenced
        }
    }
}
