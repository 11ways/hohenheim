package be.elevenways.hohenheim.test.instance;

import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.PreviewDeploymentModel;
import be.elevenways.hohenheim.model.ReleaseOperationModel;
import be.elevenways.hohenheim.model.WebhookDeliveryModel;
import be.elevenways.hohenheim.server.application.ApplicationReleases;
import be.elevenways.hohenheim.server.cms.InstanceDeploymentsPage;
import be.elevenways.hohenheim.server.docker.ReleaseKind;
import be.elevenways.hohenheim.server.instance.ApplicationKind;
import be.elevenways.hohenheim.server.source.WebhookOutcome;
import be.elevenways.hohenheim.source.GitSourceSchema;
import be.elevenways.hohenheim.test.HardDeletes;
import be.elevenways.hohenheim.test.HohenheimTestBase;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.hohenheim.server.orm.GeneratedRows;
import be.elevenways.zenit.common.orm.model.Models;
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
 * An application's Deploys tab as the App-Deploys board draws it: the live release and the one kept for rollback
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

    private static Integer applicationId;
    private static final List<Integer> releaseIds = new ArrayList<>();
    private static final List<Integer> operationIds = new ArrayList<>();
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

        int live = release("live", LIVE_SHA, InstanceModel.ROLE_SERVING);
        int kept = release("kept", KEPT_SHA, InstanceModel.ROLE_RETIRED);
        int replaced = release("replaced", REPLACED_SHA, null);

        operation(ReleaseOperationModel.STATUS_SUCCEEDED, replaced, null, Instant.parse("2026-09-01T08:00:00Z"));
        operation(ReleaseOperationModel.STATUS_SUCCEEDED, kept, null, Instant.parse("2026-09-01T09:00:00Z"));
        operation(ReleaseOperationModel.STATUS_SUCCEEDED, live, null, Instant.parse("2026-09-01T10:00:00Z"));
        operation(ReleaseOperationModel.STATUS_FAILED, null, "Did not answer on port 3000 within 60 s",
            Instant.parse("2026-09-01T11:00:00Z"));

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

    private static void operation(String status, Integer candidate, String failure, Instant startedAt) {
        Row op = Models.get(ReleaseOperationModel.class).createEmptyRow();
        op.set(ReleaseOperationModel.KIND, ReleaseOperationModel.KIND_RELEASE);
        op.set(ReleaseOperationModel.FOR_MODEL, InstanceModel.MODEL_ID.toString());
        op.set(ReleaseOperationModel.FOR_ID, applicationId);
        op.set(ReleaseOperationModel.STATUS, status);
        op.set(ReleaseOperationModel.CANDIDATE_INSTANCE_ID, candidate);
        op.set(ReleaseOperationModel.FAILURE_REASON, failure);
        op.set(ReleaseOperationModel.STARTED_AT, startedAt);
        op.set(ReleaseOperationModel.DURATION_MS, 108_000);
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
        return "/admin/instances/" + applicationId + "/page/" + InstanceDeploymentsPage.SLUG;
    }

    @Test
    void theDeploysTabLeadsWithTheLiveReleaseAndTellsWhatEachPushDid() throws Exception {
        // 1. The tab renders for the operator and sits right after the overview in the record's strip.
        HttpResponse<String> page = adminGet(pageUrl());
        assertThat(page.statusCode()).as("step 1: the Deploys tab renders").isEqualTo(200);
        String body = page.body();
        int overview = body.indexOf("/page/overview\"");
        int deploys = body.indexOf("/page/" + InstanceDeploymentsPage.SLUG + "\"");
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
        assertThat(body).as("step 2: and it goes back to the kept release").contains("Roll back to bbbb2222");

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
            .as("step 5: deploy-on-push is on").contains("Every push to the branch deploys it.")
            .as("step 5: a push that deployed").contains("Started a deploy")
            .as("step 5: and one that was ignored, with the reason")
            .contains("Ignored: not the deployed branch and not a preview branch")
            .as("step 5: never the stored outcome token").doesNotContain(">ignored_branch<");
    }

    /** The history row carrying a release mark, up to the next row. */
    private static String markedRow(String body, String mark) {
        int start = body.indexOf("data-hh-release-mark=\"" + mark + "\"");
        assertThat(start).as("a history row is marked '%s'", mark).isGreaterThan(0);
        int end = body.indexOf("</pl-table-row>", start);
        return body.substring(start, end);
    }
}
