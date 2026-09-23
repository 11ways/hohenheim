package be.elevenways.hohenheim.server;

import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.ReleaseOperationModel;
import be.elevenways.hohenheim.server.instance.ApplicationKind;
import be.elevenways.hohenheim.test.HohenheimTestBase;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.zenit.common.flash.FlashEncoding;
import be.elevenways.zenit.common.flash.FlashLevel;
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
            assertThat(rollback.headers().firstValue("Location").orElse(""))
                .as("step 4: back to the application's Deployments tab")
                .endsWith("/instances/" + applicationId + "/page/deployments");
            FlashEncoding.Decoded flash = popFlash();
            assertThat(flash).as("step 4: the outcome rides the flash").isNotNull();
            assertThat(flash.level()).as("step 4: as an error").isEqualTo(FlashLevel.ERROR);
            assertThat(flash.message().key()).as("step 4: in the release engine's own words")
                .isEqualTo("release_no_rollback_target");
            assertThat(Models.get(ReleaseOperationModel.class).find()
                    .where(ReleaseOperationModel.FOR_ID.eq(applicationId)).count())
                .as("step 4: and nothing was minted for the refused rollback")
                .isEqualTo(opsBefore);
        } finally {
            Models.get(InstanceModel.class).delete(applicationId);
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
