package be.elevenways.hohenheim.test.instance;

import be.elevenways.hohenheim.model.InstanceLogModel;
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
import be.elevenways.hohenheim.server.task.CleanOldInstanceLogs;
import be.elevenways.hohenheim.test.HohenheimTestRuntime;
import be.elevenways.hohenheim.test.host.HostFixtures;
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
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Console history: a real workload's output written down, readable after the in-memory ring
 * is gone, stored ALREADY REDACTED, and swept by the retention rule.
 */
class InstanceLogRetentionLiveTest {

    private static final Path SOCKET = Path.of(DockerClient.DEFAULT_SOCKET);

    private static final String SECRET = "hh-stored-secret-77c1d0ea";

    private static SqliteDatasource datasource;
    private static PrivateNetns netns;

    @BeforeAll
    static void setUp() throws Exception {
        File db = File.createTempFile("hohenheim-instance-log-test", ".db");
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
        org.junit.jupiter.api.Assumptions.assumeTrue(Files.exists(SOCKET),
            "Docker socket not present");
        org.junit.jupiter.api.Assumptions.assumeTrue(alpinePresent(),
            "alpine:latest not present locally");
        org.junit.jupiter.api.Assumptions.assumeTrue(netns != null,
            "no private netns: the instance tier refuses to deploy unprotected");
    }

    @Test
    void consoleOutputIsStoredRedactedSurvivesTheRingAndIsSweptByRetention() {
        assumeLiveDaemon();
        DockerClient docker = new DockerClient();

        Db.run(datasource, () -> {
            HostFixtures.admitLocal();
            Row template = Models.get(InstanceTemplateModel.class).createEmptyRow();
            template.set(InstanceTemplateModel.NAME, "console-history");
            template.set(InstanceTemplateModel.KIND, "hohenheim:docker_container");
            template.set(InstanceTemplateModel.STOP_COMMAND, "exit");
            Models.get(InstanceTemplateModel.class).save(template);

            Row row = Models.get(InstanceModel.class).createEmptyRow();
            row.set(InstanceModel.NAME, "console-history");
            row.set(InstanceModel.KIND, "hohenheim:docker_container");
            row.set(InstanceModel.SETTINGS,
                new LinkedHashMap<>(Map.of("image", "alpine", "tag", "latest")));
            row.set(InstanceModel.TEMPLATE_ID, template.get(InstanceTemplateModel.ID));
            row.set(InstanceModel.CRASH_POLICY, InstanceModel.CRASH_NONE);
            Models.get(InstanceModel.class).save(row);
            int id = row.get(InstanceModel.ID);
            String handle = ControllerScope.handle(ControllerScope.KIND_INSTANCE, id);

            InstanceLogModel logs = Models.get(InstanceLogModel.class);
            InstanceService service = new InstanceService();
            try {
                // 1. Nothing is stored before an instance has ever run.
                assertThat(logs.findByInstanceId(id, 50))
                    .as("step 1: no console history exists yet").isEmpty();

                new InstanceVariables().setValue(id, null, "API_TOKEN",
                    InstanceVariableModel.KIND_SECRET, SECRET);
                service.deploy(id);

                // 2. The workload prints an ordinary line and a secret one; the episode is
                //    flushed to its row.
                InstanceConsoles.sendCommand(id, "echo history-line-abcdef");
                InstanceConsoles.sendCommand(id, "echo token=" + SECRET + " ok");
                assertThat(await(15_000, () -> {
                    InstanceConsoles.flushLogNow(id);
                    return storedText(logs, id).contains("history-line-abcdef");
                })).as("step 2: the episode's output was written down").isTrue();

                List<Row> stored = logs.findByInstanceId(id, 50);
                assertThat(stored).as("step 2: exactly ONE row per episode, upserted")
                    .hasSize(1);
                assertThat((String) stored.get(0).get(InstanceLogModel.HANDLE))
                    .as("step 2: attributed to the workload that produced it")
                    .isEqualTo(handle);
                assertThat((Integer) stored.get(0).get(InstanceLogModel.LINE_COUNT))
                    .as("step 2: with a line count").isGreaterThanOrEqualTo(1);

                // 3. THE STORAGE INVARIANT: a secret must never be at rest here. Redaction
                //    happens before the write, not on the read, so no reader can be wrong.
                assertThat(storedText(logs, id))
                    .as("step 3: the stored text holds no secret")
                    .doesNotContain(SECRET);
                assertThat(storedText(logs, id))
                    .as("step 3: it holds the redaction marker and the plain output")
                    .contains(ConsoleRedaction.PLACEHOLDER)
                    .contains("history-line-abcdef");

                // 4. HISTORY SURVIVES THE RING. Stop the workload: the session (and its
                //    in-memory ring) is gone, and the output is still readable.
                service.stop(id);
                assertThat(await(10_000, () -> InstanceConsoles.peek(id) == null))
                    .as("step 4: the live session is gone").isTrue();
                assertThat(storedText(logs, id))
                    .as("step 4: and the history is still there without any live session")
                    .contains("history-line-abcdef");

                // 5. A REDEPLOY is a new episode, not an appendix to the old one.
                service.deploy(id);
                InstanceConsoles.sendCommand(id, "echo second-episode-ghijkl");
                assertThat(await(15_000, () -> {
                    InstanceConsoles.flushLogNow(id);
                    return logs.findByInstanceId(id, 50).size() == 2;
                })).as("step 5: the second run got its own row").isTrue();

                // 6. RETENTION. Age the first episode past the window and sweep: the old
                //    row goes, the recent one stays. An unconditional sweeper would take
                //    both, which is why the second half of this assertion exists.
                List<Row> both = logs.findByInstanceId(id, 50);
                Integer oldest = both.get(both.size() - 1).get(InstanceLogModel.ID);
                logs.find().where(InstanceLogModel.ID.eq(oldest))
                    .assign(InstanceLogModel.CREATED_AT,
                        Instant.now().minus(40, ChronoUnit.DAYS))
                    .updateAll();
                CleanOldInstanceLogs.clean();
                List<Row> after = logs.findByInstanceId(id, 50);
                assertThat(after)
                    .as("step 6: retention removed the aged episode and kept the recent one")
                    .hasSize(1);
                assertThat((Integer) after.get(0).get(InstanceLogModel.ID))
                    .as("step 6: and it kept the RIGHT one").isNotEqualTo(oldest);
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

    private static String storedText(InstanceLogModel logs, int instanceId) {
        StringBuilder all = new StringBuilder();
        for (Row row : logs.findByInstanceId(instanceId, 50)) {
            String text = row.get(InstanceLogModel.LOG_TEXT);
            if (text != null) {
                all.append(text);
            }
        }
        return all.toString();
    }

    private static boolean await(long timeoutMs, Supplier<Boolean> condition) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (Boolean.TRUE.equals(condition.get())) {
                return true;
            }
            try {
                Thread.sleep(200);
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

    private static boolean alpinePresent() {
        try {
            for (Object image : new DockerClient().listImages()) {
                Object tags = ((Map<?, ?>) image).get("RepoTags");
                if (tags instanceof List<?> list && list.contains("alpine:latest")) {
                    return true;
                }
            }
            return false;
        } catch (IOException e) {
            return false;
        }
    }
}
