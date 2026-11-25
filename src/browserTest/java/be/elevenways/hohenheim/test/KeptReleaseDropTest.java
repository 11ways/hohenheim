package be.elevenways.hohenheim.test;

import be.elevenways.hohenheim.instance.InstanceOperations;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.server.HandlerSupport;
import be.elevenways.hohenheim.server.application.ReleaseEngine;
import be.elevenways.hohenheim.server.instance.InstanceOperationHandlers;
import be.elevenways.hohenheim.server.mcp.HohenheimMcpModule;
import be.elevenways.hohenheim.model.HostModel;
import be.elevenways.hohenheim.test.application.FakeApps;
import be.elevenways.hohenheim.test.docker.FakeDockerDaemon;
import be.elevenways.hohenheim.test.host.HostFixtures;
import be.elevenways.zenit.ai.server.mcp.host.McpHostServer;
import be.elevenways.zenit.ai.server.mcp.host.McpHostServers;
import be.elevenways.zenit.ai.server.mcp.host.McpHttpSession;
import be.elevenways.zenit.auth.model.UserModel;
import be.elevenways.zenit.common.operation.OperationRunStatus;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.datasource.sql.SqlDatasource;
import be.elevenways.zenit.common.orm.model.Models;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The release an app keeps for roll back, through the real MCP endpoint as an administrator's key: dropped by
 * {@code roll_back drop=true}, which takes its container with it and leaves roll back refused in words until the next
 * deploy keeps one; and a kept release whose container was removed outside Hohenheim noticed by the hourly Docker
 * reconcile, which drops it, so roll back refuses in words instead of failing at its start.
 *
 * AIDEV-NOTE: the fake daemon removes a container the way an operator's {@code docker rm} does. Step 3 calls the
 * engine's drop the reconcile task makes for the hosts its sweep listed, not the task: the sweep would also reach every
 * host the shared test database holds. What only a real host proves is that the network and memory booking go too.
 *
 * @author Jelle De Loecker
 * @since  0.1.0
 */
class KeptReleaseDropTest extends HohenheimHttpTestBase {

    private static final String PREFIX = "mcp-kept-";

    /** The words a roll back is refused with while the app keeps no release. */
    private static final String NOTHING_KEPT = "keeps no earlier release";

    @Test
    void aKeptReleaseIsDroppedByToolAndAVanishedOneByTheReconcile() throws Exception {
        URI endpoint = URI.create(baseUrl() + "/zn/mcp/" + HohenheimMcpModule.SERVER_NAME);
        McpHostServer server = Objects.requireNonNull(McpHostServers.routed(HohenheimMcpModule.SERVER_NAME));
        HostFixtures.LocalHostState local = HostFixtures.captureLocal();
        try (FakeApps apps = FakeApps.install((SqlDatasource) Models.get(InstanceModel.class)
                .getResolvedDatasource())) {
            FakeDockerDaemon daemon = apps.daemon();
            McpHttpSession admin = HohenheimMcpTest.presetSession(server, endpoint,
                TenantConduits.operatorUser().get(UserModel.ID), "Test Admin");
            int appId = FakeApps.application(PREFIX + HohenheimMcpTest.letters(), "v1");
            String appCode = HohenheimMcpTest.code(InstanceModel.MODEL_ID, appId, TenantConduits.operator());

            // 1. Two deploys: the first release is kept for roll back, and the panel offers to drop it.
            deployed(admin, appCode, "step 1");
            FakeApps.setSettings(appId, "v2");
            deployed(admin, appCode, "step 1");
            Row kept = Objects.requireNonNull(ReleaseEngine.newestRetired(appId), "step 1: a release is kept");
            String keptHandle = FakeDockerDaemon.handleOf(kept.get(InstanceModel.ID));
            assertThat(daemon.exists(keptHandle)).as("step 1: its container stays").isTrue();
            assertThat(InstanceOperationHandlers.offered(InstanceOperations.DROP_KEPT_RELEASE,
                TenantConduits.operator(), Models.get(InstanceModel.class).findById(appId)))
                .as("step 1: the panel offers to drop it").isTrue();

            // 2. roll_back drop=true drops it with its container; roll_back then refuses in words, and so does a
            //    second drop.
            McpHttpSession.Answer dropped = admin.call("roll_back", Map.of("code", appCode, "drop", true));
            assertThat(dropped.error()).as("step 2: " + dropped.text()).isFalse();
            assertThat(ReleaseEngine.newestRetired(appId)).as("step 2: nothing is kept").isNull();
            assertThat(daemon.exists(keptHandle)).as("step 2: its container is gone").isFalse();
            McpHttpSession.Answer back = admin.call("roll_back", Map.of("code", appCode));
            assertThat(back.error()).as("step 2: " + back.text()).isTrue();
            assertThat(back.text()).as("step 2: refused in words").contains(NOTHING_KEPT);
            McpHttpSession.Answer again = admin.call("roll_back", Map.of("code", appCode, "drop", true));
            assertThat(again.error()).as("step 2: " + again.text()).isTrue();
            assertThat(again.text()).as("step 2: refused in words").contains(NOTHING_KEPT);

            // 3. The next deploy keeps one again; its container is removed outside Hohenheim, and the hourly Docker
            //    reconcile drops it, so roll back refuses in words.
            FakeApps.setSettings(appId, "v3");
            deployed(admin, appCode, "step 3");
            Row vanishing = Objects.requireNonNull(ReleaseEngine.newestRetired(appId), "step 3: one is kept again");
            daemon.removeOutside(FakeDockerDaemon.handleOf(vanishing.get(InstanceModel.ID)));
            assertThat(ReleaseEngine.dropVanishedKept(Set.of(HostModel.nameOf(HostModel.localHostId()))))
                .as("step 3: the reconcile's drop of what its sweep found gone").isPositive();
            assertThat(ReleaseEngine.newestRetired(appId)).as("step 3: the reconcile dropped it").isNull();
            McpHttpSession.Answer gone = admin.call("roll_back", Map.of("code", appCode));
            assertThat(gone.error()).as("step 3: " + gone.text()).isTrue();
            assertThat(gone.text()).as("step 3: refused in words").contains(NOTHING_KEPT);
        } finally {
            local.restore();
        }
    }

    /** Deploys the app by its tool and follows the run to a good end, then lets its drain settle. */
    private static void deployed(McpHttpSession admin, String appCode, String step) {
        McpHttpSession.Answer started = admin.call("deploy", Map.of("code", appCode));
        assertThat(started.error()).as(step + ": " + started.text()).isFalse();
        String run = HohenheimAppPartToolsTest.codeIn(started.text(), "RUN");
        assertThat(HohenheimMcpTest.followed(admin, run, step).text()).as(step + ": the deploy ends well")
            .contains("status: " + OperationRunStatus.SUCCEEDED.storageKey());
        assertThat(ReleaseEngine.awaitDrainsIdle(Duration.ofSeconds(20))).as(step + ": the drains settled").isTrue();
        assertThat(HandlerSupport.awaitBackgroundIdle(Duration.ofSeconds(20))).as(step + ": the background settled")
            .isTrue();
    }
}
