package be.elevenways.hohenheim.test.instance;

import be.elevenways.hohenheim.HohenheimCapabilities;
import be.elevenways.hohenheim.HohenheimChannels;
import be.elevenways.hohenheim.HohenheimDeployFunctions;
import be.elevenways.hohenheim.HohenheimSlugs;
import be.elevenways.hohenheim.model.BuildOperationModel;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.InstanceVariableModel;
import be.elevenways.hohenheim.model.OperationStatus;
import be.elevenways.hohenheim.server.application.DeployLogHandler;
import be.elevenways.hohenheim.server.build.BuildLog;
import be.elevenways.hohenheim.server.build.BuildQuota;
import be.elevenways.hohenheim.server.build.BuildRequest;
import be.elevenways.hohenheim.server.build.LiveBuildLogs;
import be.elevenways.hohenheim.server.build.SandboxedBuilds;
import be.elevenways.hohenheim.server.docker.DockerClient;
import be.elevenways.hohenheim.server.instance.ApplicationKind;
import be.elevenways.hohenheim.server.instance.ConsoleRedaction;
import be.elevenways.hohenheim.server.instance.InstanceVariables;
import be.elevenways.hohenheim.server.security.WorkloadNetworkPolicy;
import be.elevenways.hohenheim.source.GitSourceSchema;
import be.elevenways.hohenheim.test.ApiSupport;
import be.elevenways.hohenheim.test.HardDeletes;
import be.elevenways.hohenheim.test.HohenheimTestBase;
import be.elevenways.hohenheim.test.docker.FakeDockerDaemon;
import be.elevenways.zenit.auth.model.GrantSubjectType;
import be.elevenways.zenit.auth.model.UserModel;
import be.elevenways.zenit.auth.model.UserPrincipal;
import be.elevenways.zenit.auth.server.RecordGrants;
import be.elevenways.zenit.common.channel.ChannelException;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.refusal.ZenitRefusalReason;
import be.elevenways.zenit.test.support.FakeChannelLink;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * The live deploy log: a viewer watching an application's deploy hears its build output as the build writes it,
 * redacted of the application's secret values and bounded by the build's log cap, reads the same text once the build
 * has ended, and a tenant without the application hears nothing of it.
 *
 * AIDEV-NOTE: daemon-free on purpose (the BuildStampAttributionTest shape). The sandbox seam runs right after the
 * build's durable running row and the live registration, so the test writes the build's output through the very log
 * SandboxedBuilds registered and then fails the build; the FAILED stamp stores that log as a real ending does.
 *
 * @author Jelle De Loecker
 * @since 0.10.0
 */
class DeployLogJourneyTest extends HohenheimTestBase {

    private static final String SECRET = "hh-deploy-log-secret-5c2e91ab";
    private static final int LOG_CAP = 1024;
    private static final Duration BOUND = Duration.ofSeconds(10);

    @Test
    void aDeployLogStreamsRedactedBoundedAndOnlyToItsViewersJourney() throws Exception {
        Row app = Models.get(InstanceModel.class).createEmptyRow();
        app.set(InstanceModel.NAME, "deploy-log-app");
        app.set(InstanceModel.KIND, ApplicationKind.ID.toString());
        Map<String, Object> settings = new LinkedHashMap<>();
        settings.put("image", "alpine");
        settings.put(GitSourceSchema.REPOSITORY_URL, "https://git.example.test/acme/deploy-log.git");
        settings.put(GitSourceSchema.BRANCH, "main");
        app.set(InstanceModel.SETTINGS, settings);
        app.set(InstanceModel.STATUS, InstanceModel.STATUS_CREATED);
        Models.get(InstanceModel.class).save(app);
        int appId = app.get(InstanceModel.ID);
        new InstanceVariables().setValue(appId, null, "API_TOKEN", InstanceVariableModel.KIND_SECRET, SECRET);

        UUID ownerId = ApiSupport.user("deploy-log-owner@hohenheim.local", "Deploy Log Owner");
        UUID strangerId = ApiSupport.user("deploy-log-stranger@hohenheim.local", "Deploy Log Stranger");
        RecordGrants.grant(GrantSubjectType.USER, ownerId, InstanceModel.MODEL_ID, appId,
            HohenheimCapabilities.VIEW, true);
        FakeChannelLink<Object, Object> ownerLink = link("deploy-log-owner")
            .principal(new UserPrincipal(ownerId, "Deploy Log Owner"));
        FakeDockerDaemon daemon = new FakeDockerDaemon();
        try {
            // 1. A tenant without the application is refused, exactly as for an application that does not exist.
            FakeChannelLink<Object, Object> strangerLink = link("deploy-log-stranger")
                .principal(new UserPrincipal(strangerId, "Deploy Log Stranger"));
            for (int asked : new int[]{appId, Integer.MAX_VALUE}) {
                Throwable denied = catchThrowable(() -> new DeployLogHandler(strangerLink).onOpen(asked));
                assertThat(ChannelException.reasonOf(denied))
                    .as("step 1: instance " + asked + " is refused to the stranger as not permitted")
                    .isEqualTo(ZenitRefusalReason.PERMISSION_DENIED);
            }

            // 2. The owner's link opens before any build: its build log opens as an empty snapshot.
            DeployLogHandler watching = new DeployLogHandler(ownerLink);
            watching.onOpen(appId);
            assertThat(ownerLink.awaitSubmitted(snapshot(""), 1, BOUND))
                .as("step 2: the owner hears an empty build snapshot before any build ran").hasSize(1);

            // 3-5 run inside the build, between its running row and its ending.
            new SandboxedBuilds(new DockerClient(daemon), noNetworkPolicy(), () -> {
                BuildLog log = LiveBuildLogs.of(InstanceModel.MODEL_ID, appId);
                assertThat(log).as("step 3: the running build's log is live for its application").isNotNull();

                // 3. A line the build writes reaches the watching owner while the build still runs.
                log.append("compiling module one\n");
                await(ownerLink, appended("compiling module one"));
                String page = get(pageUrl(appId));
                assertThat(page)
                    .as("step 3: the Deploys tab opens the live log on the running deploy")
                    .contains("data-hh-deploy-live").contains("<hh-deploy-log")
                    .as("step 3: seeded with what the build wrote so far").contains("compiling module one");

                // 4. The application's secret, echoed by the build and split across two chunks the way a daemon
                //    hands output over, streams as the placeholder: no frame carries any part of it whole.
                log.append("export API_TOKEN=" + SECRET.substring(0, 12));
                log.append(SECRET.substring(12) + "\n");
                await(ownerLink, appended(ConsoleRedaction.PLACEHOLDER));

                // 5. Output past the cap is cut: the viewer hears the truncation marker once and nothing after it.
                log.append("x".repeat(LOG_CAP * 3));
                log.append("after the cap\n");
                await(ownerLink, appended("build log truncated"));
                throw new IllegalStateException("build stopped by the test");
            }).run(request(appId));
        } catch (IllegalStateException stopped) {
            assertThat(stopped).as("the build's own failure").hasMessage("build stopped by the test");
        } finally {
            daemon.close();
        }

        try {
            // 6. The finished log is stored, and it is exactly what the live viewer heard: no gap, no double.
            Row build = Models.get(BuildOperationModel.class)
                .findForOwner(InstanceModel.MODEL_ID.toString(), appId, 1).get(0);
            assertThat((String) build.get(BuildOperationModel.STATUS))
                .as("step 6: the build ended").isEqualTo(BuildOperationModel.LIFECYCLE.stored(OperationStatus.FAILED));
            String stored = build.get(BuildOperationModel.LOG);
            assertThat(stored)
                .as("step 6: the stored log holds the build's lines").contains("compiling module one")
                .as("step 6: and never the secret").doesNotContain(SECRET)
                .as("step 6: nor anything past the cap").doesNotContain("after the cap");
            assertThat(stored.length())
                .as("step 6: the stored log is bounded by the cap and its one marker line")
                .isLessThanOrEqualTo(LOG_CAP + 64);
            assertThat(stored.split("build log truncated", -1))
                .as("step 6: the truncation marker appears exactly once").hasSize(2);

            // 6b. The owner, attached while the build ran, hears it end: the final text replaces what it holds, and
            //     every build frame it heard, joined, never spells the secret across a chunk boundary.
            assertThat(ownerLink.awaitSubmitted(snapshot(stored), 1, BOUND))
                .as("step 6: the watching owner hears the build's end as its stored final text").hasSize(1);
            StringBuilder heard = new StringBuilder();
            for (Object frame : ownerLink.submitted()) {
                if (build(frame)) {
                    heard.append(((Map<?, ?>) frame).get(HohenheimDeployFunctions.TEXT_KEY));
                }
            }
            assertThat(heard.toString()).as("step 6: no build frame, nor any two joined, carried the secret")
                .doesNotContain(SECRET);

            // 7. A viewer opening after the build ended reads the finished log as its snapshot; no frame ever
            //    carried the secret.
            FakeChannelLink<Object, Object> laterLink = link("deploy-log-later")
                .principal(new UserPrincipal(ownerId, "Deploy Log Owner"));
            new DeployLogHandler(laterLink).onOpen(appId);
            assertThat(laterLink.awaitSubmitted(snapshot(stored), 1, BOUND))
                .as("step 7: the finished log is readable afterwards, whole").hasSize(1);
            assertThat(laterLink.submitted()).as("step 7: no frame carries the secret")
                .noneMatch(frame -> String.valueOf(frame).contains(SECRET));

            // 8. The finished log rides the Deploys tab's own row for an operator; a stranger has no such page.
            assertThat(get(pageUrl(appId))).as("step 8: the operator reads the finished build log on its row")
                .contains("compiling module one").doesNotContain(SECRET).doesNotContain("data-hh-deploy-live");
            assertThat(httpGet("/manage/instances/" + appId + "/page/" + HohenheimSlugs.Tab.DEPLOYMENTS,
                    sessionFor(strangerId).token()).body())
                .as("step 8: the stranger's own panel never shows the log").doesNotContain("compiling module one");
        } finally {
            for (Row variable : Models.get(InstanceVariableModel.class).findByInstanceId(appId)) {
                Models.get(InstanceVariableModel.class).delete(variable.get(InstanceVariableModel.ID));
            }
            for (Row build : Models.get(BuildOperationModel.class)
                    .findForOwner(InstanceModel.MODEL_ID.toString(), appId, 50)) {
                Models.get(BuildOperationModel.class).delete(build.get(BuildOperationModel.ID));
            }
            HardDeletes.byId(Models.get(InstanceModel.class), appId);
            Models.get(UserModel.class).delete(ownerId);
            Models.get(UserModel.class).delete(strangerId);
        }
    }

    /** Await one matching frame from inside the build's seam, which may not throw checked. */
    private static void await(FakeChannelLink<Object, Object> link, Predicate<Object> matching) {
        try {
            link.awaitSubmitted(matching, 1, BOUND);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted while awaiting a frame", interrupted);
        }
    }

    /** A socketless link on the deploy-log channel. */
    private static FakeChannelLink<Object, Object> link(String id) {
        return new FakeChannelLink<>(HohenheimChannels.DEPLOY_LOG, id);
    }

    /** An operator's read of a page; callable from inside the build's seam, which may not throw checked. */
    private String get(String path) {
        try {
            return adminGet(path).body();
        } catch (Exception unreachable) {
            throw new AssertionError("the page could not be read: " + path, unreachable);
        }
    }

    private static String pageUrl(int appId) {
        return "/admin/instances/" + appId + "/page/" + HohenheimSlugs.Tab.DEPLOYMENTS;
    }

    private static BuildRequest request(int appId) throws Exception {
        Path context = Files.createTempDirectory("hohenheim-deploy-log");
        return new BuildRequest(InstanceModel.MODEL_ID, appId, BuildOperationModel.KIND_DOCKERFILE, context,
            "Dockerfile", "hohenheim-deploy-log:latest", Map.of(), "main", null,
            new BuildQuota(1.0, 512, 1024L * 1024 * 1024, 60_000, 64, LOG_CAP, 256L * 1024 * 1024));
    }

    private static WorkloadNetworkPolicy noNetworkPolicy() {
        return new WorkloadNetworkPolicy((args, stdin) -> {
            throw new AssertionError("nft must never run for a build that ends before its sandbox");
        }, () -> true);
    }

    private static Predicate<Object> appended(String text) {
        return frame -> build(frame) && !snapshotFlag(frame)
            && String.valueOf(((Map<?, ?>) frame).get(HohenheimDeployFunctions.TEXT_KEY)).contains(text);
    }

    private static Predicate<Object> snapshot(String text) {
        return frame -> build(frame) && snapshotFlag(frame)
            && text.equals(((Map<?, ?>) frame).get(HohenheimDeployFunctions.TEXT_KEY));
    }

    private static boolean build(Object frame) {
        return frame instanceof Map<?, ?> map && HohenheimDeployFunctions.Source.BUILD.token()
            .equals(map.get(HohenheimDeployFunctions.SOURCE_KEY));
    }

    private static boolean snapshotFlag(Object frame) {
        return Boolean.TRUE.equals(((Map<?, ?>) frame).get(HohenheimDeployFunctions.SNAPSHOT_KEY));
    }
}
