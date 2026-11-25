package be.elevenways.hohenheim.test;

import be.elevenways.hohenheim.model.HostModel;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.server.docker.ContainerHardening;
import be.elevenways.hohenheim.server.docker.DockerClient;
import be.elevenways.hohenheim.server.mcp.HohenheimMcpModule;
import be.elevenways.hohenheim.test.application.FakeApps;
import be.elevenways.hohenheim.test.docker.FakeDockerDaemon;
import be.elevenways.hohenheim.test.host.HostFixtures;
import be.elevenways.zenit.ai.server.mcp.host.McpHostServer;
import be.elevenways.zenit.ai.server.mcp.host.McpHostServers;
import be.elevenways.zenit.ai.server.mcp.host.McpHttpSession;
import be.elevenways.zenit.auth.model.UserModel;
import be.elevenways.zenit.common.operation.OperationRunStatus;
import be.elevenways.zenit.common.orm.datasource.sql.SqlDatasource;
import be.elevenways.zenit.common.orm.model.Models;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * A Docker container app whose deploy fails, followed through the real MCP endpoint as an administrator's key: a
 * container create the hardening policy refuses, an image the daemon will not pull and a failure nobody planned for
 * each end a run that says why in words, never "did not finish because of an error" and never the failure's own
 * text; its logs say it has no container yet and which run failed; its history records the failure without the
 * policy's text.
 *
 * AIDEV-NOTE: the fake daemon's runtime face throws what the real driver throws, made by the real code: the
 * hardening policy's own refusal of a spec it forbids, and the real client's typed refusal of a pull the daemon
 * answers 404. What only a real host proves is which of a real daemon's answers reach those types.
 *
 * @author Jelle De Loecker
 * @since  0.1.0
 */
class DeployFailureReasonsTest extends HohenheimHttpTestBase {

    private static final String PREFIX = "mcp-failed-";

    /** The words of a run that failed with no reason kept, which none of these runs may read. */
    private static final String BARE_FAILURE = "did not finish because of an error";

    @Test
    void aFailedDeployOfAContainerSaysWhyInItsRunItsLogsAndItsHistory() throws Exception {
        URI endpoint = URI.create(baseUrl() + "/zn/mcp/" + HohenheimMcpModule.SERVER_NAME);
        McpHostServer server = Objects.requireNonNull(McpHostServers.routed(HohenheimMcpModule.SERVER_NAME));
        Deque<Runnable> cleanUp = new ArrayDeque<>();
        HostFixtures.LocalHostState local = HostFixtures.captureLocal();
        try (FakeApps apps = FakeApps.install((SqlDatasource) Models.get(InstanceModel.class)
                .getResolvedDatasource())) {
            FakeDockerDaemon daemon = apps.daemon();
            daemon.installContainerKind();
            daemon.keepConsoleLogs();
            HostFixtures.makeLocalPlaceable(16L * 1024);
            McpHttpSession admin = HohenheimMcpTest.presetSession(server, endpoint,
                TenantConduits.operatorUser().get(UserModel.ID), "Test Admin");
            int appId = HohenheimDataToolsTest.record(InstanceModel.class, cleanUp, row -> {
                row.set(InstanceModel.NAME, PREFIX + HohenheimMcpTest.letters());
                row.set(InstanceModel.KIND, "hohenheim:docker_container");
                row.set(InstanceModel.SETTINGS, new LinkedHashMap<>(Map.of("image", "does-not-exist", "tag",
                    "nope")));
                row.set(InstanceModel.STATUS, InstanceModel.STATUS_CREATED);
                row.set(InstanceModel.HOST_ID, HostModel.localHostId());
            });
            String appCode = HohenheimMcpTest.code(InstanceModel.MODEL_ID, appId, TenantConduits.operator());

            // 1. Never deployed: its logs say it has no container yet, not that they cannot be read.
            McpHttpSession.Answer none = admin.call("logs", Map.of("code", appCode));
            assertThat(none.error()).as("step 1: " + none.text()).isTrue();
            assertThat(none.text()).as("step 1: no container yet").contains("has no container yet")
                .doesNotContain("cannot be read right now");

            // 2. The hardening policy refuses the container create: the run says so in words, and so do its logs and
            //    its history; the policy's own text reaches none of them.
            Map<String, Object> escaping = new LinkedHashMap<>(Map.of("Image", "does-not-exist:nope",
                "HostConfig", new LinkedHashMap<>(Map.of("Privileged", true))));
            Throwable hardening = catchThrowable(
                () -> ContainerHardening.applyTo(escaping, ContainerHardening.SERVICE));
            assertThat(hardening).as("step 2: the real policy refuses it")
                .isInstanceOf(ContainerHardening.Refused.class);
            daemon.failNextCreate((Exception) hardening);
            String refusedRun = deployRun(admin, appCode, "step 2");
            String refused = HohenheimMcpTest.followed(admin, refusedRun, "step 2").text();
            assertThat(refused).as("step 2: the run says why")
                .contains("status: " + OperationRunStatus.REFUSED.storageKey())
                .contains("container safety rules").doesNotContain(BARE_FAILURE).doesNotContain("REFUSED to create")
                .doesNotContain("Privileged");
            assertThat((String) Models.get(InstanceModel.class).findById(appId).get(InstanceModel.STATUS))
                .as("step 2: the app reads error").isEqualTo(InstanceModel.STATUS_ERROR);
            McpHttpSession.Answer logs = admin.call("logs", Map.of("code", appCode));
            assertThat(logs.text()).as("step 2: its logs name the failed deploy's run")
                .contains("has no container yet").contains(refusedRun);
            McpHttpSession.Answer history = admin.call("history", Map.of("code", appCode));
            assertThat(history.error()).as("step 2: " + history.text()).isFalse();
            assertThat(history.text()).as("step 2: its history records the failed deploy, never the policy's text")
                .contains("to Error").doesNotContain("REFUSED to create").doesNotContain("Privileged");

            // 3. The daemon will not pull the image: the run says the image cannot be had, not what the daemon said.
            daemon.refusePull("does-not-exist:nope", "pull access denied for does-not-exist, repository does not"
                + " exist or may require 'docker login'");
            Throwable pull = catchThrowable(() -> new DockerClient(daemon).ensureImage("does-not-exist:nope", null));
            assertThat(pull).as("step 3: the real client's refusal")
                .isInstanceOf(DockerClient.ImageUnavailableException.class);
            daemon.failNextCreate((Exception) pull);
            String pullRun = deployRun(admin, appCode, "step 3");
            assertThat(HohenheimMcpTest.followed(admin, pullRun, "step 3").text()).as("step 3: the run says why")
                .contains("could not be found or pulled").doesNotContain(BARE_FAILURE)
                .doesNotContain("pull access denied").doesNotContain("HTTP 404");

            // 4. A failure nobody planned for: the run says it is unknown and names itself as where the log has it.
            daemon.failNextCreate(new IllegalStateException("token=hunter2 nobody planned for this"));
            String unknownRun = deployRun(admin, appCode, "step 4");
            assertThat(HohenheimMcpTest.followed(admin, unknownRun, "step 4").text()).as("step 4: the run says why")
                .contains("does not recognise the reason").contains("under " + unknownRun)
                .doesNotContain(BARE_FAILURE).doesNotContain("hunter2");
        } finally {
            while (!cleanUp.isEmpty()) cleanUp.pop().run();
            local.restore();
        }
    }

    /** @return the code of the run the deploy tool started on the app */
    private static String deployRun(McpHttpSession admin, String appCode, String step) {
        McpHttpSession.Answer started = admin.call("deploy", Map.of("code", appCode));
        assertThat(started.error()).as(step + ": " + started.text()).isFalse();
        return HohenheimAppPartToolsTest.codeIn(started.text(), "RUN");
    }
}
