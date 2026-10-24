package be.elevenways.hohenheim.server;

import be.elevenways.hohenheim.HohenheimActivityAction;
import be.elevenways.hohenheim.HohenheimMicrocopy;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.test.HohenheimTestBase;
import be.elevenways.hohenheim.test.TenantConduits;
import be.elevenways.zenit.auth.model.UserModel;
import be.elevenways.zenit.auth.model.UserPrincipal;
import be.elevenways.hohenheim.test.RecordedChanges;
import be.elevenways.zenit.common.orm.history.Change;
import be.elevenways.zenit.common.orm.history.History;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.validation.Violations;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The instance Deploys-tab deploy verb answers inside a bounded window: a refusal comes back typed for a flash in its
 * own words instead of a bare 422 page, and work that outlives the window is reported as running instead of holding
 * the request for the whole health-gated probe. The tab's rollback is the placed rollback operation
 * (ReleasesTabJourneyTest proves its refusal).
 *
 * @author Jelle De Loecker
 * @since 0.1.0
 */
class DeployControlSettleTest extends HohenheimTestBase {

    /** A record id no instance holds, carrying only this test's attribution change. */
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
                throw Violations.ofForm(HohenheimMicrocopy.VIOLATIONS.of("release_no_rollback_target"));
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

        // 3b. The background verb writes its history as whoever asked: the attribution rides the hop.
        Row admin = Models.get(UserModel.class).find().where(UserModel.EMAIL.eq("test@hohenheim.local")).first();
        UUID adminId = admin.get(UserModel.ID);
        TenantConduits.as(new UserPrincipal(adminId, "Test Admin"), () -> SiteControlHandlers.settleWithin(
            Duration.ofSeconds(5), "test verb",
            () -> History.event(Models.get(InstanceModel.class), SETTLE_RECORD,
                Change.fact(HohenheimActivityAction.ROLLED_BACK))));
        List<RecordedChanges.Recorded> attributed = RecordedChanges.withFact(Models.get(InstanceModel.class),
            SETTLE_RECORD, HohenheimActivityAction.ROLLED_BACK);
        assertThat(attributed).as("step 3b: the background verb recorded its history").hasSize(1);
        assertThat(attributed.get(0).actor())
            .as("step 3b: naming the caller who asked, not the system").isEqualTo(String.valueOf(adminId));
    }
}
