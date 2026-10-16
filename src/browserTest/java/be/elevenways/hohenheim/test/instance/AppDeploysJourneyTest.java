package be.elevenways.hohenheim.test.instance;

import be.elevenways.hohenheim.model.OperationStatus;
import be.elevenways.hohenheim.HohenheimSlugs;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.PreviewDeploymentModel;
import be.elevenways.hohenheim.model.BuildOperationModel;
import be.elevenways.hohenheim.model.ReleaseOperationModel;
import be.elevenways.hohenheim.model.WebhookDeliveryModel;
import be.elevenways.hohenheim.server.application.ApplicationReleases;
import be.elevenways.hohenheim.server.docker.ReleaseKind;
import be.elevenways.hohenheim.server.instance.ApplicationKind;
import be.elevenways.hohenheim.server.source.WebhookOutcome;
import be.elevenways.hohenheim.source.GitSourceSchema;
import be.elevenways.hohenheim.test.HardDeletes;
import be.elevenways.hohenheim.test.HohenheimTestBase;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.hohenheim.server.orm.GeneratedRows;
import be.elevenways.zenit.common.orm.model.Models;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.assertions.PlaywrightAssertions;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * An application's Deploys tab: the live release and the one kept for rollback
 * first, the history marked by where each release stands now, the previews with their own actions, and what the last
 * pushes did in words.
 *
 * @author Jelle De Loecker
 * @since 0.1.0
 */
class AppDeploysJourneyTest extends HohenheimTestBase {

    private static final String PREFIX = "appdeploys-";
    private static final String LIVE_SHA = "aaaa1111bbbbcccc";
    private static final String KEPT_SHA = "bbbb2222ccccdddd";
    private static final String REPLACED_SHA = "cccc3333ddddeeee";
    private static final String LIVE_IMAGE = "sha256:appdeploys-live-image";
    private static final String LIVE_BUILD_OUTPUT = "appdeploys live build output: Successfully built";
    private static final String LIVE_RELEASE_STEPS = "appdeploys live release steps: health ok";

    private static Integer applicationId;
    private static Integer keptId;
    private static Integer liveId;
    private static final List<Integer> releaseIds = new ArrayList<>();
    private static final List<Integer> operationIds = new ArrayList<>();
    private static final List<Integer> buildIds = new ArrayList<>();
    private static final List<Integer> previewIds = new ArrayList<>();
    private static final List<Integer> deliveryIds = new ArrayList<>();

    @BeforeAll
    static void seed() throws Exception {
        Map<String, Object> settings = new LinkedHashMap<>();
        settings.put("image", "alpine");
        settings.put(GitSourceSchema.REPOSITORY_URL, "https://git.example.test/acme/staging-api.git");
        settings.put(GitSourceSchema.BRANCH, "main");
        settings.put(GitSourceSchema.AUTO_DEPLOY, true);
        settings.put(GitSourceSchema.PREVIEWS_ENABLED, true);
        Row app = Models.get(InstanceModel.class).createEmptyRow();
        app.set(InstanceModel.NAME, PREFIX + "app");
        app.set(InstanceModel.KIND, ApplicationKind.ID.toString());
        app.set(InstanceModel.SETTINGS, settings);
        app.set(InstanceModel.STATUS, InstanceModel.STATUS_CREATED);
        Models.get(InstanceModel.class).save(app);
        applicationId = app.get(InstanceModel.ID);

        // Created in release order, as the engine creates them: its ids are the releases' own sequence.
        int replaced = release("replaced", REPLACED_SHA, null);
        int kept = release("kept", KEPT_SHA, InstanceModel.ROLE_RETIRED);
        keptId = kept;
        int live = release("live", LIVE_SHA, InstanceModel.ROLE_SERVING);
        liveId = live;

        operation(ReleaseOperationModel.LIFECYCLE.stored(OperationStatus.SUCCEEDED), replaced, null, Instant
            .parse("2026-09-01T08:00:00Z"), null);
        operation(ReleaseOperationModel.LIFECYCLE.stored(OperationStatus.SUCCEEDED), kept, null, Instant
            .parse("2026-09-01T09:00:00Z"), null);
        operation(ReleaseOperationModel.LIFECYCLE.stored(OperationStatus.SUCCEEDED), live, null, Instant
            .parse("2026-09-01T10:00:00Z"), LIVE_IMAGE);
        // The build that produced the live release's image: a succeeded build is no row of its own.
        Row built = Models.get(BuildOperationModel.class).createEmptyRow();
        built.set(BuildOperationModel.FOR_MODEL, InstanceModel.MODEL_ID.toString());
        built.set(BuildOperationModel.FOR_ID, applicationId);
        built.set(BuildOperationModel.STATUS, BuildOperationModel.LIFECYCLE.stored(OperationStatus.SUCCEEDED));
        built.set(BuildOperationModel.SOURCE_REF, LIVE_SHA);
        built.set(BuildOperationModel.IMAGE_ID, LIVE_IMAGE);
        built.set(BuildOperationModel.LOG, LIVE_BUILD_OUTPUT);
        built.set(BuildOperationModel.STARTED_AT, Instant.parse("2026-09-01T09:58:00Z"));
        Models.get(BuildOperationModel.class).save(built);
        buildIds.add(built.get(BuildOperationModel.ID));
        operation(ReleaseOperationModel.LIFECYCLE.stored(OperationStatus.FAILED), null,
            "Did not answer on port 3000 within 60 s",
            Instant.parse("2026-09-01T11:00:00Z"), null);
        // A deploy whose build was refused never became a release.
        Row build = Models.get(BuildOperationModel.class).createEmptyRow();
        build.set(BuildOperationModel.FOR_MODEL, InstanceModel.MODEL_ID.toString());
        build.set(BuildOperationModel.FOR_ID, applicationId);
        build.set(BuildOperationModel.STATUS, BuildOperationModel.LIFECYCLE.stored(OperationStatus.FAILED));
        build.set(BuildOperationModel.SOURCE_REF, "main");
        build.set(BuildOperationModel.FAILURE_REASON, "The builder refused the image: no Dockerfile at the root");
        build.set(BuildOperationModel.STARTED_AT, Instant.parse("2026-09-01T12:00:00Z"));
        Models.get(BuildOperationModel.class).save(build);
        buildIds.add(build.get(BuildOperationModel.ID));

        preview("feature/checkout", 42, PreviewDeploymentModel.STATUS_RUNNING);
        preview("old/branch", null, PreviewDeploymentModel.STATUS_DESTROYED);

        delivery("push", WebhookOutcome.DEPLOY_QUEUED);
        delivery("push", WebhookOutcome.IGNORED_BRANCH);
    }

    @AfterAll
    static void cleanUp() throws Exception {
        for (Integer id : deliveryIds) {
            Models.get(WebhookDeliveryModel.class).delete(id);
        }
        for (Integer id : previewIds) {
            HardDeletes.byId(Models.get(PreviewDeploymentModel.class), id);
        }
        for (Integer id : operationIds) {
            Models.get(ReleaseOperationModel.class).delete(id);
        }
        for (Integer id : buildIds) {
            Models.get(BuildOperationModel.class).delete(id);
        }
        if (applicationId != null) {
            GeneratedRows.as(new GeneratedRows.Attribution(ApplicationReleases.SOURCE,
                InstanceModel.MODEL_ID.toString(), applicationId), () -> {
                for (Integer id : releaseIds) {
                    HardDeletes.byId(Models.get(InstanceModel.class), id);
                }
            });
            HardDeletes.byId(Models.get(InstanceModel.class), applicationId);
        }
    }

    private static int release(String name, String sha, String role) throws Exception {
        int[] created = new int[1];
        GeneratedRows.as(new GeneratedRows.Attribution(ApplicationReleases.SOURCE,
            InstanceModel.MODEL_ID.toString(), applicationId), () -> {
            Row row = Models.get(InstanceModel.class).createEmptyRow();
            row.set(InstanceModel.NAME, PREFIX + name);
            row.set(InstanceModel.KIND, ReleaseKind.ID.toString());
            row.set(InstanceModel.SETTINGS, new LinkedHashMap<>(Map.of("image", "alpine", "commit_sha", sha)));
            row.set(InstanceModel.RUNTIME_ROLE, role);
            Models.get(InstanceModel.class).save(row);
            created[0] = row.get(InstanceModel.ID);
        });
        releaseIds.add(created[0]);
        return created[0];
    }

    private static void operation(String status, Integer candidate, String failure, Instant startedAt,
                                  String imageId) {
        Row op = Models.get(ReleaseOperationModel.class).createEmptyRow();
        op.set(ReleaseOperationModel.KIND, ReleaseOperationModel.KIND_RELEASE);
        op.set(ReleaseOperationModel.FOR_MODEL, InstanceModel.MODEL_ID.toString());
        op.set(ReleaseOperationModel.FOR_ID, applicationId);
        op.set(ReleaseOperationModel.STATUS, status);
        op.set(ReleaseOperationModel.CANDIDATE_INSTANCE_ID, candidate);
        op.set(ReleaseOperationModel.FAILURE_REASON, failure);
        op.set(ReleaseOperationModel.STARTED_AT, startedAt);
        op.set(ReleaseOperationModel.DURATION_MS, 108_000);
        op.set(ReleaseOperationModel.IMAGE_ID, imageId);
        if (imageId != null) {
            op.set(ReleaseOperationModel.STEP_LOG, LIVE_RELEASE_STEPS);
        }
        Models.get(ReleaseOperationModel.class).save(op);
        operationIds.add(op.get(ReleaseOperationModel.ID));
    }

    private static void preview(String ref, Integer pullRequest, String status) {
        Row row = Models.get(PreviewDeploymentModel.class).createEmptyRow();
        row.set(PreviewDeploymentModel.APPLICATION_ID, applicationId);
        row.set(PreviewDeploymentModel.REF, ref);
        row.set(PreviewDeploymentModel.PR_NUMBER, pullRequest);
        row.set(PreviewDeploymentModel.HOSTNAME, ref.replace('/', '-') + ".preview.test");
        row.set(PreviewDeploymentModel.STATUS, status);
        Models.get(PreviewDeploymentModel.class).save(row);
        previewIds.add(row.get(PreviewDeploymentModel.ID));
    }

    private static void delivery(String event, WebhookOutcome outcome) {
        Row row = Models.get(WebhookDeliveryModel.class).createEmptyRow();
        row.set(WebhookDeliveryModel.INSTANCE_ID, applicationId);
        row.set(WebhookDeliveryModel.DELIVERY_KEY, PREFIX + deliveryIds.size() + "-" + outcome.token());
        row.set(WebhookDeliveryModel.EVENT, event);
        row.set(WebhookDeliveryModel.ACTION, outcome.token());
        row.set(WebhookDeliveryModel.RECEIVED_AT, Instant.parse("2026-09-01T12:00:00Z"));
        Models.get(WebhookDeliveryModel.class).save(row);
        deliveryIds.add(row.get(WebhookDeliveryModel.ID));
    }

    private static String pageUrl() {
        return "/admin/instances/" + applicationId + "/page/" + HohenheimSlugs.Tab.DEPLOYMENTS;
    }

    /** Point a stored release at another commit, as a redeploy of one commit leaves its kept release. */
    private static void commitOf(int releaseId, String sha) throws Exception {
        GeneratedRows.as(new GeneratedRows.Attribution(ApplicationReleases.SOURCE,
            InstanceModel.MODEL_ID.toString(), applicationId), () -> {
            Row row = Models.get(InstanceModel.class).findById(releaseId);
            row.set(InstanceModel.SETTINGS, new LinkedHashMap<>(Map.of("image", "alpine", "commit_sha", sha)));
            Models.get(InstanceModel.class).save(row);
        });
    }

    /** Give a stored release another runtime role, as a rollback swaps the live and the kept one. */
    private static void roleOf(int releaseId, String role) throws Exception {
        GeneratedRows.as(new GeneratedRows.Attribution(ApplicationReleases.SOURCE,
            InstanceModel.MODEL_ID.toString(), applicationId), () -> {
            Row row = Models.get(InstanceModel.class).findById(releaseId);
            row.set(InstanceModel.RUNTIME_ROLE, role);
            Models.get(InstanceModel.class).save(row);
        });
    }

    @Test
    void theDeploysTabLeadsWithTheLiveReleaseAndTellsWhatEachPushDid() throws Exception {
        // 1. The tab renders for the operator and sits right after the overview in the record's strip.
        HttpResponse<String> page = adminGet(pageUrl());
        assertThat(page.statusCode()).as("step 1: the Deploys tab renders").isEqualTo(200);
        String body = page.body();
        int overview = body.indexOf("/page/overview\"");
        int deploys = body.indexOf("/page/" + HohenheimSlugs.Tab.DEPLOYMENTS + "\"");
        int console = body.indexOf("/page/console\"");
        assertThat(overview).as("step 1: the strip links the overview").isGreaterThan(0);
        assertThat(deploys).as("step 1: Deploys follows the overview").isGreaterThan(overview);
        assertThat(console).as("step 1: and comes before the console").isGreaterThan(deploys);

        // 2. The live band names the serving commit and its branch, and offers the one-step way back to the kept one.
        assertThat(body)
            .as("step 2: the band names the live commit and branch").contains("Live: commit aaaa1111 from main")
            .as("step 2: and the release kept for rollback").contains("The previous release, bbbb2222, is kept");
        assertThat(body.split("data-hh-rollback", -1).length - 1)
            .as("step 2: exactly one rollback control is drawn").isEqualTo(1);
        assertThat(body).as("step 2: and it names the kept release it goes back to")
            .contains("Roll back to release bbbb2222");

        // 2b. A kept release that runs the live release's own commit is no way back: the band says so and offers no
        //     rollback, since going "back" would deploy the very commit that serves now.
        commitOf(keptId, LIVE_SHA);
        try {
            String same = adminGet(pageUrl()).body();
            assertThat(same).as("step 2b: no rollback control is drawn").doesNotContain("data-hh-rollback")
                .as("step 2b: and the band says why")
                .contains("The previous release runs the same commit, aaaa1111, so there is nothing earlier to go"
                    + " back to.")
                .doesNotContain("Roll back to");
        } finally {
            commitOf(keptId, KEPT_SHA);
        }

        // 2c. After a rollback the kept release is the NEWER one, by the releases' own order: the one step goes
        //     forward to it, in the button, the confirm dialog and the band.
        roleOf(liveId, InstanceModel.ROLE_RETIRED);
        roleOf(keptId, InstanceModel.ROLE_SERVING);
        try {
            String forward = adminGet(pageUrl()).body();
            assertThat(forward).as("step 2c: the band names the older commit as live")
                .contains("Live: commit bbbb2222 from main")
                .as("step 2c: the button goes forward to the kept release").contains("Go forward to release aaaa1111")
                .as("step 2c: the band says the newer release is kept")
                .contains("The newer release, aaaa1111, is kept so you can go forward to it in one step.")
                .doesNotContain("Roll back to release");
            // The confirm dialog is the shell's (use:CmsConfirm.destructive), drawn once the page hydrates.
            navigateToApp(pageUrl());
            waitForHydration();
            click("[data-hh-rollback]");
            assertIsVisible(".pl-alertdialog-modal[data-open]", "step 2c: the button asks first");
            // The dialog's words land after it opens: wait for them rather than reading the first frame.
            Locator dialog = this.page.locator(".pl-alertdialog-modal[data-open]");
            PlaywrightAssertions.assertThat(dialog)
                .containsText("Go forward to release aaaa1111? The live release stops serving.");
            assertThat(dialog.textContent()).as("step 2c: and the dialog goes forward too")
                .doesNotContain("Go back to release");
            click("[data-cms-confirm-cancel]");
        } finally {
            roleOf(keptId, InstanceModel.ROLE_RETIRED);
            roleOf(liveId, InstanceModel.ROLE_SERVING);
        }

        // 3. The history marks every succeeded release by where it stands now, read from the instances' roles.
        assertThat(markedRow(body, "live")).as("step 3: the live release row").contains("aaaa1111").contains(">Live<");
        assertThat(markedRow(body, "kept")).as("step 3: the kept release row")
            .contains("bbbb2222").contains("Kept for rollback");
        assertThat(markedRow(body, "replaced")).as("step 3: an older release reads as replaced")
            .contains("cccc3333").contains("Replaced");
        assertThat(body)
            .as("step 3: a failed release states its reason").contains("Did not answer on port 3000 within 60 s")
            .as("step 3: and its state in words, never the stored token").contains(">Failed<")
            .as("step 3: durations read in minutes and seconds").contains("1 min 48 s");

        // 3b. A deploy whose build was refused is listed too, newest first, with what refused it.
        int refused = body.indexOf("The builder refused the image: no Dockerfile at the root");
        assertThat(refused).as("step 3b: the refused build states its reason").isPositive();
        assertThat(refused).as("step 3b: above the older failed release")
            .isLessThan(body.indexOf("Did not answer on port 3000 within 60 s"));

        // 3c. A release row carries the output of the build that made its image beside its own release steps.
        int liveRow = body.indexOf("data-hh-release-mark=\"live\"");
        int keptRow = body.indexOf("data-hh-release-mark=\"kept\"");
        String liveDetail = body.substring(liveRow, keptRow);
        assertThat(liveDetail)
            .as("step 3c: the live release shows its build output").contains(LIVE_BUILD_OUTPUT)
            .as("step 3c: under the build log's name").contains("Build log")
            .as("step 3c: beside its own release steps").contains(LIVE_RELEASE_STEPS).contains("Release steps");

        // 4. The running preview is listed with its pull request; a torn-down one is not.
        assertThat(body)
            .as("step 4: the running preview is listed").contains("data-hh-preview=\"feature/checkout\"")
            .as("step 4: with its pull request").contains("Pull request #42")
            .as("step 4: a destroyed preview is gone").doesNotContain("old/branch")
            .as("step 4: a new preview opens the preview form for THIS application")
            .contains("/admin/previews/new?application=" + applicationId);

        // 5. Automatic deploys say where the code comes from and what each recent push did, in words.
        assertThat(body)
            .as("step 5: the source line").contains("https://git.example.test/acme/staging-api.git, branch main")
            .as("step 5: automatic deploys are on, without promising a push deploys at once")
            .contains("New commits on the branch deploy automatically.")
            .doesNotContain("Every push to the branch deploys it.")
            .as("step 5: a push that deployed").contains("Started a deploy")
            .as("step 5: and one that was ignored, with the reason")
            .contains("Ignored: not the deployed branch and not a preview branch")
            .as("step 5: never the stored outcome token").doesNotContain(">ignored_branch<");

        // 6. Nothing deploys, so the tab watches nothing; once a build runs, the open tab follows it without a reload.
        assertThat(body).as("step 6: an idle tab keeps no live watch").doesNotContain("data-hh-deploys-live");
        Row running = Models.get(BuildOperationModel.class).createEmptyRow();
        running.set(BuildOperationModel.FOR_MODEL, InstanceModel.MODEL_ID.toString());
        running.set(BuildOperationModel.FOR_ID, applicationId);
        running.set(BuildOperationModel.STATUS, BuildOperationModel.LIFECYCLE.stored(OperationStatus.RUNNING));
        running.set(BuildOperationModel.SOURCE_REF, "main");
        running.set(BuildOperationModel.STARTED_AT, Instant.parse("2026-09-02T12:00:00Z"));
        Models.get(BuildOperationModel.class).save(running);
        buildIds.add(running.get(BuildOperationModel.ID));
        String deploying = adminGet(pageUrl()).body();
        assertThat(deploying)
            .as("step 6: a running deploy re-renders the tab live").contains("data-hh-deploys-live")
            .as("step 6: watching the deploys feed").contains("instance_deploys_live");
    }

    /** The history row carrying a release mark, up to the next row. */
    private static String markedRow(String body, String mark) {
        int start = body.indexOf("data-hh-release-mark=\"" + mark + "\"");
        assertThat(start).as("a history row is marked '%s'", mark).isGreaterThan(0);
        int end = body.indexOf("</pl-table-row>", start);
        return body.substring(start, end);
    }
}
