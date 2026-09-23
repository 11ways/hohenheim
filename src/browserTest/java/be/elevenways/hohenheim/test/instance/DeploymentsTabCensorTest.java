package be.elevenways.hohenheim.test.instance;

import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.ReleaseOperationModel;
import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.hohenheim.server.cms.InstanceDeploymentsPage;
import be.elevenways.hohenheim.server.instance.ApplicationKind;
import be.elevenways.hohenheim.test.HohenheimTestBase;
import be.elevenways.protoblast.common.time.Now;
import be.elevenways.zenit.auth.model.GrantSubjectType;
import be.elevenways.zenit.auth.model.UserModel;
import be.elevenways.zenit.auth.server.AuthModels;
import be.elevenways.zenit.auth.server.RecordGrants;
import be.elevenways.zenit.common.orm.datasource.Row;
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
 * An application's Deploys tab: in-flight is the release model's own answer, and the delegated
 * panel never shows the daemon's own failure text.
 *
 * Two defects pinned here. The page kept its OWN in-flight list (pending/deploying/probing) and
 * missed the switching and draining states the release model and the engine include, so Deploy
 * was offered live while a release was mid-switch. And a release's failure reason and step log --
 * stamped with the daemon's and transport's own text (socket paths, host paths) -- rendered
 * verbatim on /manage.
 */
class DeploymentsTabCensorTest extends HohenheimTestBase {

    private static final String PREFIX = "deploycensor-";
    private static final String DAEMON_TEXT = "dial unix /var/run/docker.sock: connect: refused";
    private static final String STEP_TEXT = "WARNING: artifact prune failed: /srv/hohenheim/secret-path";

    private static Integer tenantId;
    private static TestSession tenant;
    private static Integer applicationId;
    private static final List<Integer> operationIds = new ArrayList<>();

    @BeforeAll
    static void seed() {
        Row user = AuthModels.users().createEmptyRow();
        user.set(UserModel.EMAIL, PREFIX + "tenant@surface.test");
        user.set(UserModel.DISPLAY_NAME, "Deploy Tenant");
        user.set(UserModel.ENABLED, true);
        user.set(UserModel.CREATED_AT, Now.instant());
        user.set(UserModel.UPDATED_AT, Now.instant());
        AuthModels.users().save(user);
        tenantId = user.get(UserModel.ID);
        tenant = sessionFor(tenantId);

        Row app = Models.get(InstanceModel.class).createEmptyRow();
        app.set(InstanceModel.NAME, PREFIX + "app");
        app.set(InstanceModel.KIND, ApplicationKind.ID.toString());
        app.set(InstanceModel.SETTINGS, new LinkedHashMap<>(Map.of("image", "alpine", "tag", "latest")));
        app.set(InstanceModel.STATUS, InstanceModel.STATUS_CREATED);
        Models.get(InstanceModel.class).save(app);
        applicationId = app.get(InstanceModel.ID);

        RecordGrants.grant(GrantSubjectType.USER, tenantId, InstanceModel.MODEL_ID, applicationId,
            HohenheimAccess.MANAGE, true);

        operation(ReleaseOperationModel.STATUS_FAILED, DAEMON_TEXT, STEP_TEXT + "\n",
            Instant.parse("2026-09-01T09:00:00Z"));
        operation(ReleaseOperationModel.STATUS_SWITCHING, null, "switching traffic\n",
            Instant.parse("2026-09-01T10:00:00Z"));
    }

    @AfterAll
    static void cleanUp() {
        for (Integer id : operationIds) {
            Models.get(ReleaseOperationModel.class).delete(id);
        }
        if (applicationId != null) {
            Models.get(InstanceModel.class).delete(applicationId);
        }
    }

    private static void operation(String status, String failure, String stepLog, Instant startedAt) {
        Row op = Models.get(ReleaseOperationModel.class).createEmptyRow();
        op.set(ReleaseOperationModel.KIND, ReleaseOperationModel.KIND_RELEASE);
        op.set(ReleaseOperationModel.FOR_MODEL, InstanceModel.MODEL_ID.toString());
        op.set(ReleaseOperationModel.FOR_ID, applicationId);
        op.set(ReleaseOperationModel.STATUS, status);
        op.set(ReleaseOperationModel.FAILURE_REASON, failure);
        op.set(ReleaseOperationModel.STEP_LOG, stepLog);
        op.set(ReleaseOperationModel.STARTED_AT, startedAt);
        Models.get(ReleaseOperationModel.class).save(op);
        operationIds.add(op.get(ReleaseOperationModel.ID));
    }

    private static String url(String panel) {
        return "/" + panel + "/instances/" + applicationId + "/page/" + InstanceDeploymentsPage.SLUG;
    }

    /** The markup of the pl-button whose face reads {@code label}, opening tag included. */
    private static String buttonLabelled(String body, String label) {
        int face = body.indexOf(">" + label + "<");
        assertThat(face).as("the page renders a '%s' control", label).isGreaterThan(0);
        int open = body.lastIndexOf("<pl-button", face);
        return body.substring(open, face);
    }

    @Test
    void inFlightIsTheModelsAnswerAndATenantNeverSeesDaemonText() throws Exception {
        // 1. The operator's page renders, and a release in SWITCHING counts as in flight: the
        //    deploy control is disabled. The page's own list had no switching state.
        HttpResponse<String> admin = adminGet(url("admin"));
        assertThat(admin.statusCode()).as("step 1: the operator's Deploys tab renders").isEqualTo(200);
        assertThat(buttonLabelled(admin.body(), "Deploy now"))
            .as("step 1: a switching release disables Deploy now")
            .contains("disabled");

        // 2. The operator reads the daemon's own reason and the engine's step log.
        assertThat(admin.body())
            .as("step 2: the operator sees the failure reason").contains(DAEMON_TEXT)
            .as("step 2: and the release step log").contains("secret-path");

        // 3. The tenant's page renders the same history...
        HttpResponse<String> manage = httpGet(url("manage"), tenant.token());
        assertThat(manage.statusCode()).as("step 3: the tenant's Deploys tab renders").isEqualTo(200);
        assertThat(buttonLabelled(manage.body(), "Deploy now"))
            .as("step 3: in flight on /manage too").contains("disabled");

        // 4. ...but never the daemon's own words: the failure is stated, its reason withheld.
        assertThat(manage.body())
            .as("step 4: the socket path never reaches the tenant").doesNotContain("docker.sock")
            .as("step 4: nor the engine's step log").doesNotContain("secret-path")
            .as("step 4: the failure is still stated, with where the reason lives")
            .contains("An administrator can read the reason");
    }
}
