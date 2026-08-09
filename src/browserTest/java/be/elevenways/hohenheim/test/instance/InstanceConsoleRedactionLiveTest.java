package be.elevenways.hohenheim.test.instance;

import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.InstanceTemplateModel;
import be.elevenways.hohenheim.model.InstanceVariableModel;
import be.elevenways.hohenheim.server.ControllerScope;
import be.elevenways.hohenheim.server.docker.DockerClient;
import be.elevenways.hohenheim.server.instance.ConsoleRedaction;
import be.elevenways.hohenheim.server.instance.InstanceConsoles;
import be.elevenways.hohenheim.server.instance.InstanceService;
import be.elevenways.hohenheim.server.instance.InstanceVariables;
import be.elevenways.hohenheim.server.runtime.WorkloadNetworks;
import be.elevenways.hohenheim.server.security.WorkloadNetworkPolicy;
import be.elevenways.hohenheim.test.HohenheimTestRuntime;
import be.elevenways.hohenheim.test.host.HostFixtures;
import be.elevenways.hohenheim.test.live.LiveLane;
import be.elevenways.hohenheim.test.network.PrivateNetns;
import be.elevenways.zenit.common.orm.datasource.Datasources;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A REAL secret on a REAL workload, echoed to a REAL console, observed as the tenant's
 * WebSocket viewer observes it (the same {@code subscribe} the handler uses).
 *
 * The pre-fix behaviour this pins is not hypothetical: without redaction the value reaches
 * the viewer verbatim. Every assertion here reads STATE -- what a viewer received, what a
 * reconnecting viewer replays, what the one-shot logs read returns -- never a status code.
 */
class InstanceConsoleRedactionLiveTest {

    private static final Path SOCKET = Path.of(DockerClient.DEFAULT_SOCKET);

    /** Planted before the console opens: the value the redactor is seeded with. */
    private static final String DEPLOY_SECRET = "hh-deploy-secret-4a91c7f2";

    /** Planted while the console already streams: the live-registration lane. */
    private static final String LATE_SECRET = "hh-late-secret-8bd3e05a";

    private static SqliteDatasource datasource;
    private static PrivateNetns netns;

    @BeforeAll
    static void setUp() throws Exception {
        File db = File.createTempFile("hohenheim-console-redaction-test", ".db");
        db.delete();
        db.deleteOnExit();
        datasource = new SqliteDatasource("jdbc:sqlite:" + db.getAbsolutePath());
        new MigrationRunner(datasource).migrate().requireSuccess();
        Datasources.register(Datasources.DEFAULT, datasource);
        HohenheimTestRuntime.ensureBooted();
        if (PrivateNetns.available()) {
            netns = new PrivateNetns();
            WorkloadNetworkPolicy.overrideForTest(netns.enforcingPolicy());
        }
    }

    @AfterAll
    static void tearDown() {
        WorkloadNetworkPolicy.overrideForTest(null);
        if (netns != null) {
            netns.close();
            netns = null;
        }
    }

    private static void assumeLiveDaemon() {
        LiveLane.require(LiveLane.Need.DOCKER_SOCKET, Files.exists(SOCKET),
            "Docker socket not present");
        LiveLane.requireImage(new DockerClient(), "alpine:latest");
        LiveLane.require(LiveLane.Need.NETNS, netns != null,
            "no private netns: the instance tier refuses to deploy unprotected");
    }

    @Test
    void aSecretEchoedByTheWorkloadNeverReachesAViewer() {
        assumeLiveDaemon();
        DockerClient docker = new DockerClient();

        Db.run(datasource, () -> {
            HostFixtures.admitLocal();
            Row template = Models.get(InstanceTemplateModel.class).createEmptyRow();
            template.set(InstanceTemplateModel.NAME, "console-redaction");
            template.set(InstanceTemplateModel.KIND, "hohenheim:docker_container");
            // A stop command alone is enough to make the hub open a console session at
            // deploy; no readiness line, so nothing races the assertions below.
            template.set(InstanceTemplateModel.STOP_COMMAND, "exit");
            Models.get(InstanceTemplateModel.class).save(template);

            Row row = Models.get(InstanceModel.class).createEmptyRow();
            row.set(InstanceModel.NAME, "console-redaction");
            row.set(InstanceModel.KIND, "hohenheim:docker_container");
            row.set(InstanceModel.SETTINGS,
                new LinkedHashMap<>(Map.of("image", "alpine", "tag", "latest")));
            row.set(InstanceModel.TEMPLATE_ID, template.get(InstanceTemplateModel.ID));
            row.set(InstanceModel.CRASH_POLICY, InstanceModel.CRASH_NONE);
            Models.get(InstanceModel.class).save(row);
            int id = row.get(InstanceModel.ID);
            String handle = ControllerScope.handle(ControllerScope.KIND_INSTANCE, id);

            InstanceService service = new InstanceService();
            try {
                // 1. Plant a real secret on the instance BEFORE anything runs: an encrypted
                //    instance_variables row, the same carrier a template's secret variable
                //    or a generated forwarding key lands in.
                new InstanceVariables().setValue(id, null, "API_TOKEN",
                    InstanceVariableModel.KIND_SECRET, DEPLOY_SECRET);
                assertThat(new InstanceVariables().valuesFor(id))
                    .as("step 1: the control plane really holds this value for this instance")
                    .containsEntry("API_TOKEN", DEPLOY_SECRET);

                service.deploy(id);
                assertThat(InstanceConsoles.peek(id))
                    .as("step 1: and the deploy opened the console session").isNotNull();

                // 2. Attach a viewer exactly as the tenant's WebSocket handler does.
                StringBuilder viewer = new StringBuilder();
                InstanceConsoles.subscribe(id, chunk -> {
                    synchronized (viewer) {
                        viewer.append(chunk);
                    }
                });

                // 3. THE LEAK, staged for real: the workload echoes the secret to stdout,
                //    the way a deploy script printing an env var or a crash dump would.
                InstanceConsoles.sendCommand(id, "echo token=" + DEPLOY_SECRET + " ready");
                assertThat(await(15_000, () -> seen(viewer).contains("token=")))
                    .as("step 3: the echoed line reached the viewer").isTrue();
                assertThat(seen(viewer))
                    .as("step 3: and the SECRET ITSELF did not")
                    .doesNotContain(DEPLOY_SECRET);
                assertThat(seen(viewer))
                    .as("step 3: it arrived redacted, with its surroundings intact")
                    .contains("token=" + ConsoleRedaction.PLACEHOLDER + " ready");

                // 4. POSITIVE ANCHOR: ordinary output is not touched at all. A redactor
                //    that mangles legitimate text is as broken as one that leaks.
                InstanceConsoles.sendCommand(id, "echo plain-output-unchanged-1234");
                assertThat(await(15_000,
                        () -> seen(viewer).contains("plain-output-unchanged-1234")))
                    .as("step 4: unrelated output arrives byte for byte").isTrue();

                // 5. A value declared secret while the console is ALREADY streaming is
                //    redacted from that moment -- the write funnel tells the live session.
                new InstanceVariables().setValue(id, null, "LATE_TOKEN",
                    InstanceVariableModel.KIND_SECRET, LATE_SECRET);
                InstanceConsoles.sendCommand(id, "echo late=" + LATE_SECRET + " done");
                assertThat(await(15_000, () -> seen(viewer).contains("late=")))
                    .as("step 5: the late line reached the viewer").isTrue();
                assertThat(seen(viewer))
                    .as("step 5: and the value written mid-session was redacted too")
                    .doesNotContain(LATE_SECRET);

                // 6. A RECONNECTING viewer replays the ring -- which was written redacted,
                //    so reconnecting cannot be the way back to the plaintext.
                StringBuilder reconnected = new StringBuilder();
                InstanceConsoles.subscribe(id, chunk -> {
                    synchronized (reconnected) {
                        reconnected.append(chunk);
                    }
                });
                assertThat(seen(reconnected))
                    .as("step 6: the replayed ring holds the redacted text")
                    .contains(ConsoleRedaction.PLACEHOLDER)
                    .doesNotContain(DEPLOY_SECRET)
                    .doesNotContain(LATE_SECRET);

                // 7. The one-shot automation read is the same door, not a wider one: the
                //    daemon's own captured log goes through redaction before it is returned.
                String tail = InstanceConsoles.tail(id, 200);
                assertThat(tail)
                    .as("step 7: the logs API returns redacted output")
                    .doesNotContain(DEPLOY_SECRET)
                    .doesNotContain(LATE_SECRET);
                assertThat(tail)
                    .as("step 7: and still returns the surrounding output")
                    .contains("plain-output-unchanged-1234");
            } finally {
                try {
                    service.destroy(id);
                } catch (RuntimeException ignored) {
                    // best-effort; the docker cleanup below is the authority
                }
                cleanup(docker, handle);
            }
        });
    }

    private static String seen(StringBuilder buffer) {
        synchronized (buffer) {
            return buffer.toString();
        }
    }

    private static boolean await(long timeoutMs, Supplier<Boolean> condition) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (Boolean.TRUE.equals(condition.get())) {
                return true;
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return Boolean.TRUE.equals(condition.get());
    }

    private static void cleanup(DockerClient docker, String handle) {
        try {
            docker.removeContainer(handle, true);
        } catch (IOException ignored) {
            // already gone
        }
        try {
            docker.removeNetwork(WorkloadNetworks.networkName(handle));
        } catch (IOException ignored) {
            // already gone
        }
    }
}
