package be.elevenways.hohenheim.server;

import be.elevenways.hohenheim.HohenheimActivityAction;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.ReleaseOperationModel;
import be.elevenways.hohenheim.server.instance.ApplicationKind;
import be.elevenways.hohenheim.test.HardDeletes;
import be.elevenways.hohenheim.test.HohenheimTestBase;
import be.elevenways.hohenheim.test.TenantConduits;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.zenit.auth.model.UserModel;
import be.elevenways.zenit.auth.model.UserPrincipal;
import be.elevenways.zenit.common.flash.FlashLevel;
import be.elevenways.zenit.common.flash.FlashNotice;
import be.elevenways.zenit.common.orm.activity.ActivityLog;
import be.elevenways.zenit.common.orm.activity.ActivityModel;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.validation.Violations;
import org.junit.jupiter.api.Test;

import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The instance Deploys-tab verbs answer inside a bounded window: a refusal becomes a flash
 * in its own words instead of a bare 422 page, and work that outlives the window is reported
 * as running instead of holding the request for the whole health-gated probe.
 *
 * @author Jelle De Loecker
 * @since 0.1.0
 */
class DeployControlSettleTest extends HohenheimTestBase {

    private static final String NAME = "deploy-control-settle-app";

    /** A record id no instance holds, carrying only this test's attribution row. */
    private static final int SETTLE_RECORD = 987_654_321;

    @Test
    void aDeploysTabVerbAnswersInsideItsWindowOrSaysItIsRunning() throws Exception {
        // 1. A verb that finishes inside the window is DONE: not running, no failure.
        SiteControlHandlers.Settled done = SiteControlHandlers.settleWithin(
            Duration.ofSeconds(2), "test verb", () -> { });
        assertThat(done.running()).as("step 1: a quick verb is not reported running").isFalse();
        assertThat(done.failure()).as("step 1: and carries no failure").isNull();

        // 2. A refusal thrown inside the window comes back AS the refusal, so the handler
        //    can flash the domain's own sentence.
        SiteControlHandlers.Settled refused = SiteControlHandlers.settleWithin(
            Duration.ofSeconds(2), "test verb", () -> {
                throw Violations.ofForm(Microcopy.of("release_no_rollback_target")
                    .withFilter("scope", "violations"));
            });
        assertThat(refused.failure()).as("step 2: the refusal is handed back typed")
            .isInstanceOf(Violations.class);

        // 3. A verb that outlives the window is RUNNING: the request is answered, the work
        //    goes on. The latch keeps the background verb bounded to this test.
        CountDownLatch release = new CountDownLatch(1);
        try {
            SiteControlHandlers.Settled running = SiteControlHandlers.settleWithin(
                Duration.ofMillis(100), "test verb", () -> {
                    try {
                        release.await(10, TimeUnit.SECONDS);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    }
                });
            assertThat(running.running()).as("step 3: a slow verb is reported running").isTrue();
            assertThat(running.failure()).as("step 3: which is not a failure").isNull();
        } finally {
            release.countDown();
        }

        // 3b. The background verb writes its activity as whoever asked: the attribution rides the hop.
        Row admin = Models.get(UserModel.class).find().where(UserModel.EMAIL.eq("test@hohenheim.local")).first();
        int adminId = admin.get(UserModel.ID);
        TenantConduits.as(new UserPrincipal(adminId, "Test Admin"), () -> SiteControlHandlers.settleWithin(
            Duration.ofSeconds(5), "test verb",
            () -> ActivityLog.record(Models.get(InstanceModel.class), SETTLE_RECORD,
                HohenheimActivityAction.TESTED, null)));
        Row attributed = new ActivityModel(Models.get(InstanceModel.class).getResolvedDatasource()).find()
            .where(ActivityModel.RECORD_ID.eq(String.valueOf(SETTLE_RECORD)))
            .where(ActivityModel.ACTION.eq(HohenheimActivityAction.TESTED.id().toString())).first();
        assertThat(attributed).as("step 3b: the background verb recorded its activity").isNotNull();
        assertThat((String) attributed.get(ActivityModel.ACTOR))
            .as("step 3b: naming the caller who asked, not the system").isEqualTo(String.valueOf(adminId));

        // 4. Over HTTP: rolling back an application with no retained release used to escape
        //    the handler as a bare 422 page. It now redirects back to the tab and the
        //    engine's own refusal rides the session flash.
        int applicationId = application();
        try {
            long opsBefore = Models.get(ReleaseOperationModel.class).find()
                .where(ReleaseOperationModel.FOR_ID.eq(applicationId)).count();
            HttpResponse<String> rollback = httpPostForm("/instances/" + applicationId + "/rollback",
                "", sessionToken, csrfToken);
            assertThat(rollback.statusCode()).as("step 4: the refusal is a redirect, not a 422 page")
                .isIn(302, 303);
            assertThat(landingOf(rollback))
                .as("step 4: back to the application's Deployments tab")
                .endsWith("/instances/" + applicationId + "/page/deployments");
            FlashNotice flash = popFlash(rollback);
            assertThat(flash).as("step 4: the outcome rides the flash").isNotNull();
            assertThat(flash.toast()).as("step 4: as an error").isEqualTo(FlashLevel.ERROR.toast());
            assertThat(flash.message().key()).as("step 4: in the release engine's own words")
                .isEqualTo("release_no_rollback_target");
            assertThat(Models.get(ReleaseOperationModel.class).find()
                    .where(ReleaseOperationModel.FOR_ID.eq(applicationId)).count())
                .as("step 4: and nothing was minted for the refused rollback")
                .isEqualTo(opsBefore);
        } finally {
            HardDeletes.byId(Models.get(InstanceModel.class), applicationId);
        }
    }

    /** A release-managed application with no release history at all. */
    private static int application() {
        Row row = Models.get(InstanceModel.class).createEmptyRow();
        row.set(InstanceModel.NAME, NAME);
        row.set(InstanceModel.KIND, ApplicationKind.ID.toString());
        row.set(InstanceModel.SETTINGS, new LinkedHashMap<>(Map.of("image", "alpine", "tag", "latest")));
        row.set(InstanceModel.STATUS, InstanceModel.STATUS_CREATED);
        Models.get(InstanceModel.class).save(row);
        return row.get(InstanceModel.ID);
    }
}
