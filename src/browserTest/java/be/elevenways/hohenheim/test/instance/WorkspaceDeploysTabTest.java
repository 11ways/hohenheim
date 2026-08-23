package be.elevenways.hohenheim.test.instance;

import be.elevenways.hohenheim.model.BuildOperationModel;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.RuntimeImageModel;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.server.cms.InstanceDeploymentsPage;
import be.elevenways.hohenheim.test.HohenheimTestBase;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Deploys tab on a WORKSPACE: what the last deploy did, and what it printed.
 *
 * AIDEV-NOTE: the pre-fix defect, from the workspace-journey audit --
 * {@code InstanceDeploymentsPage.visibleFor} admitted release-managed kinds only, so a
 * workspace had no surface at all showing its checkout, its build, or the build log
 * WorkspaceBuilds captures. The counterfactual half matters as much: a workspace that
 * names no repository deploys a bare container and must still get NO tab, because a
 * history page over a record with no history is a dead end wearing a label.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class WorkspaceDeploysTabTest extends HohenheimTestBase {

    private static final String KIND = "hohenheim:workspace";

    private static Integer bareWorkspaceId;
    private static Integer sourcedWorkspaceId;

    @BeforeAll
    static void seed() {
        int imageId = runtimeImage();
        bareWorkspaceId = workspace("deploys-tab-bare", imageId, Map.of());
        sourcedWorkspaceId = workspace("deploys-tab-sourced", imageId, Map.of(
            "repository_url", "https://git.example.test/team/app.git",
            "branch", "main"));

        operation(sourcedWorkspaceId,
            BuildOperationModel.STATUS_SUCCEEDED,
            "cafebabecafebabecafebabecafebabecafebabe",
            null,
            "[hohenheim] deploying main\n"
                + "Cloning into '/home/site/app'...\n"
                + "added 214 packages in 9s\n");
        operation(sourcedWorkspaceId, BuildOperationModel.STATUS_FAILED, "main",
            "The workspace build command failed: npm ERR! missing script: build",
            "[hohenheim] deploying main\nnpm ERR! missing script: build\n");
    }

    /**
     * The tab exists for a source-declared workspace and carries its history, its log and
     * its deploy control -- and does NOT exist for a workspace with no source.
     */
    @Test
    @Order(1)
    void aSourcedWorkspaceGetsTheDeploysTabAndABareOneDoesNot() throws Exception {

        // 1. THE ABSENCE, pre-fix: the slug answered 404 for every workspace.
        HttpResponse<String> page = adminGet(url(sourcedWorkspaceId));
        assertThat(page.statusCode())
            .withFailMessage("step 1: a workspace that names a repository has no Deploys"
                + " tab (HTTP %s)", page.statusCode())
            .isEqualTo(200);

        // 2. Its operations render, newest first, with the stored status and commit.
        assertThat(page.body())
            .as("step 2: the succeeded deploy's commit is shown")
            .contains("cafebabe")
            .as("step 2: and the failed one's reason, so a failure explains itself")
            .contains("missing script: build");

        // 3. The captured log rides pl-log-view -- the follow-tail pane that names its own
        //    scroll region -- and never a hand-rolled <pre>.
        assertThat(page.body())
            .withFailMessage("step 3: the build log is not rendered through pl-log-view")
            .contains("<pl-log-view");
        assertThat(page.body())
            .as("step 3: carrying the text BuildLog captured")
            .contains("added 214 packages");

        // 4. The Trigger column is the release lane's vocabulary and a workspace build
        //    records none, so the column is absent rather than a row of blanks.
        assertThat(page.body())
            .withFailMessage("step 4: the workspace lane renders a Trigger column it can"
                + " never fill")
            .doesNotContain(">Trigger<");

        // 5. Rollback is not offered: a workspace's state IS its home volume, so there is
        //    no previous artifact to point back at. Asserted on the rendered CONTROL and
        //    not on the route: the page's declared vars ride the hydration payload, so the
        //    rollback endpoint's URL is in the body either way.
        assertThat(page.body())
            .withFailMessage("step 5: the workspace lane offers a rollback that could only"
                + " refuse")
            .doesNotContain("Roll back");

        // 5b. Positive anchor for step 5: the DEPLOY control IS rendered, so the absence
        //     above is the rollback offer and not an empty card.
        assertThat(page.body())
            .withFailMessage("step 5: the workspace lane offers no Deploy control at all")
            .contains("Deploy now");

        // 6. FALSIFIED: the identical kind with no repository gets no tab at all, so
        //    step 1 is the source declaration and not "workspaces got a tab".
        assertThat(adminGet(url(bareWorkspaceId)).statusCode())
            .withFailMessage("step 6: a workspace with no repository is given a deploy"
                + " history page with nothing to show")
            .isEqualTo(404);
        assertThat(adminGet("/admin/instances/" + bareWorkspaceId).body())
            .withFailMessage("step 6: and the tab strip still offers the slug")
            .doesNotContain("/page/" + InstanceDeploymentsPage.SLUG);
    }

    /** The tab is offered from the record's own tab strip, not only reachable by URL. */
    @Test
    @Order(2)
    void theTabIsOfferedOnTheRecordItself() throws Exception {
        assertThat(adminGet("/admin/instances/" + sourcedWorkspaceId).body())
            .withFailMessage("the Deploys tab is reachable only by typing its URL")
            .contains("/page/" + InstanceDeploymentsPage.SLUG);
    }

    // -- fixtures ---------------------------------------------------------------

    private static String url(int instanceId) {
        return "/admin/instances/" + instanceId + "/page/" + InstanceDeploymentsPage.SLUG;
    }

    private static int runtimeImage() {
        RuntimeImageModel model = Models.get(RuntimeImageModel.class);
        Row row = model.find().where(RuntimeImageModel.NAME.eq("deploys-tab-image")).first();
        if (row == null) {
            row = model.createEmptyRow();
            row.set(RuntimeImageModel.NAME, "deploys-tab-image");
        }
        row.set(RuntimeImageModel.DOCKER_IMAGE, "hohenheim/node-22:1");
        row.set(RuntimeImageModel.DEFAULT_COMMAND, "npm start");
        row.set(RuntimeImageModel.ENABLED, true);
        model.save(row);
        return row.get(RuntimeImageModel.ID);
    }

    private static int workspace(String name, int imageId, Map<String, Object> settings) {
        InstanceModel model = Models.get(InstanceModel.class);
        Row row = model.createEmptyRow();
        row.set(InstanceModel.NAME, name);
        row.set(InstanceModel.KIND, KIND);
        row.set(InstanceModel.SETTINGS, new LinkedHashMap<>(settings));
        row.set(InstanceModel.STATUS, InstanceModel.STATUS_STOPPED);
        row.set(InstanceModel.SERVER_ID, ServerModel.localServerId());
        row.set(InstanceModel.RUNTIME_IMAGE_ID, imageId);
        model.save(row);
        return row.get(InstanceModel.ID);
    }

    /** One recorded workspace deploy, in the shape WorkspaceBuilds writes. */
    private static void operation(int instanceId, String status, String sourceRef,
                                  String failureReason, String log) {
        BuildOperationModel model = Models.get(BuildOperationModel.class);
        Row row = model.createEmptyRow();
        row.set(BuildOperationModel.BUILDER_KIND, BuildOperationModel.KIND_WORKSPACE);
        row.set(BuildOperationModel.FOR_MODEL, InstanceModel.MODEL_ID.toString());
        row.set(BuildOperationModel.FOR_ID, instanceId);
        row.set(BuildOperationModel.STATUS, status);
        row.set(BuildOperationModel.SOURCE_REF, sourceRef);
        row.set(BuildOperationModel.FAILURE_REASON, failureReason);
        row.set(BuildOperationModel.LOG, log);
        row.set(BuildOperationModel.STARTED_AT, Instant.parse("2026-08-22T09:00:00Z"));
        row.set(BuildOperationModel.FINISHED_AT, Instant.parse("2026-08-22T09:00:12Z"));
        row.set(BuildOperationModel.DURATION_MS, 12_000);
        model.save(row);
    }
}
