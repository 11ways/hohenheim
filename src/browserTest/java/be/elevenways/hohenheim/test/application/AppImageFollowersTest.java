package be.elevenways.hohenheim.test.application;

import be.elevenways.hohenheim.HohenheimActivityAction;
import be.elevenways.hohenheim.HohenheimCapabilities;
import be.elevenways.hohenheim.instance.InstanceOperations;
import be.elevenways.hohenheim.model.DeployTrigger;
import be.elevenways.hohenheim.model.HostModel;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.ReleaseOperationModel;
import be.elevenways.hohenheim.server.HandlerSupport;
import be.elevenways.hohenheim.server.application.AppImageFollowers;
import be.elevenways.hohenheim.server.application.ApplicationReleases;
import be.elevenways.hohenheim.server.application.ReleaseEngine;
import be.elevenways.hohenheim.server.instance.DockerContainerKind;
import be.elevenways.hohenheim.server.instance.InstanceMigrations;
import be.elevenways.hohenheim.server.instance.InstanceService;
import be.elevenways.hohenheim.server.instance.InstanceStatusReconciler;
import be.elevenways.hohenheim.test.ApiSupport;
import be.elevenways.hohenheim.test.HohenheimServedApp;
import be.elevenways.hohenheim.test.RecordedChanges;
import be.elevenways.hohenheim.test.TenantConduits;
import be.elevenways.hohenheim.test.docker.FakeDockerDaemon;
import be.elevenways.hohenheim.test.host.HostFixtures;
import be.elevenways.protoblast.common.time.Now;
import be.elevenways.zenit.auth.model.GrantSubjectType;
import be.elevenways.zenit.auth.server.RecordGrants;
import be.elevenways.zenit.common.orm.datasource.Db;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.datasource.sql.SqlDatasource;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.orm.query.SortOrder;
import be.elevenways.zenit.common.validation.Violations;
import be.elevenways.zenit.server.operation.OperationPipeline;
import be.elevenways.zenit.test.support.DatasourceProbe;
import be.elevenways.zenit.test.support.ServedApp;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * A Docker container that takes its image from an app, on the fake daemon: it runs what the app serves, follows every
 * switch and no other event, and the daemon never holds two containers of its record at once; the write guards, the
 * delete and move refusals and the doors of the sweep and boot.
 *
 * AIDEV-NOTE: the clock moves a second before each deploy (Now's offset, put back after), so "started before the
 * serving release was created" never rests on two writes landing in one millisecond.
 *
 * @author Jelle De Loecker
 * @since  0.10.0
 */
class AppImageFollowersTest {

    @RegisterExtension
    static final ServedApp APP = HohenheimServedApp.app();

    private static SqlDatasource datasource;
    private static FakeApps lane;
    private static FakeDockerDaemon daemon;
    private static Duration offset;

    @BeforeAll
    static void setUp() throws Exception {
        datasource = APP.database();
        lane = FakeApps.install(datasource);
        daemon = lane.daemon();
        daemon.installContainerKind();
        offset = Now.offset();
    }

    @AfterAll
    static void tearDown() {
        Now.setOffset(offset);
        lane.close();
    }

    /**
     * The worker of an app follows every switch of it, deploy and rollback alike, and nothing else: a failed command,
     * a failed probe, an unchanged converge, the app's own Stop and Start, an operator's Stop of the worker and an
     * errored worker on the served version each leave it alone; at no daemon call does its record own two containers.
     */
    @Test
    void aContainerTakingAnAppsImageFollowsEverySwitchJourney() {
        Db.run(datasource, () -> {
            InstanceService service = new InstanceService();
            int app = FakeApps.application("follow-app", "v1");
            int worker = follower("follow-worker", app);
            String handle = FakeDockerDaemon.handleOf(worker);

            // 1. The reference is accepted before the app's first deploy; a Start says why it cannot run yet.
            assertThat((Integer) row(worker).get(InstanceModel.IMAGE_APP_ID)).as("step 1: the reference is stored")
                .isEqualTo(app);
            assertThat(violationKeys(catchThrowable(() -> service.deploy(worker))))
                .as("step 1: a Start before the app was deployed is refused in its words")
                .isEqualTo("image_app_id=image_from_app_not_deployed ");

            // 2. The first deploy names no container (nobody started this one); its Start runs the serving image.
            tick();
            ApplicationReleases.converge(app, Map.of());
            settle();
            assertThat(latestStepLog(app)).as("step 2: a container nobody started is not named")
                .doesNotContain("containers restarting");
            assertThat(daemon.exists(handle)).as("step 2: and it was not started").isFalse();
            service.deploy(worker);
            assertThat(daemon.isRunning(handle)).as("step 2: its Start runs it").isTrue();
            assertThat(fingerprint(worker)).as("step 2: on the image the app serves").isEqualTo(servingImage(app));
            String first = servingImage(app);

            // 3. A deploy that changes only a variable makes a new release with the same image: restarted once.
            int creates = creates(worker);
            tick();
            Map<String, Object> withVariable = new LinkedHashMap<>(FakeApps.settingsFor("v1"));
            withVariable.put("environment_variables", Map.of("MODE", "two"));
            settings(app, withVariable);
            ApplicationReleases.converge(app, Map.of());
            settle();
            assertThat(latestStepLog(app)).as("step 3: the deploy names the container it restarts")
                .contains("containers restarting on this version: follow-worker");
            assertThat(creates(worker)).as("step 3: one create for the same image").isEqualTo(creates + 1);
            assertThat(fingerprint(worker)).as("step 3: still the same image").isEqualTo(first);
            assertThat(RecordedChanges.withFact(Models.get(InstanceModel.class), worker,
                HohenheimActivityAction.FOLLOWED_APP)).as("step 3: recorded on the container").hasSize(1);

            // 4. A deploy of a new image: halted and created once, on the new image.
            int stops = daemon.callCount("stop:" + handle);
            creates = creates(worker);
            tick();
            FakeApps.setSettings(app, "v2");
            ApplicationReleases.converge(app, Map.of());
            settle();
            String second = servingImage(app);
            assertThat(second).as("step 4: the app serves a new image").isNotEqualTo(first);
            assertThat(fingerprint(worker)).as("step 4: the container runs it").isEqualTo(second);
            assertThat(daemon.callCount("stop:" + handle)).as("step 4: one halt").isEqualTo(stops + 1);
            assertThat(creates(worker)).as("step 4: one create").isEqualTo(creates + 1);

            // 5. An unchanged converge and a Restart of the app make no release, so nothing touches the container.
            creates = creates(worker);
            ApplicationReleases.converge(app, Map.of());
            new InstanceService().restart(app, DeployTrigger.MANUAL);
            settle();
            assertThat(creates(worker)).as("step 5: no create without a switch").isEqualTo(creates);

            // 6. A rollback is a switch: the container runs the rolled-back image.
            tick();
            ReleaseEngine.rollback(app, DeployTrigger.MANUAL);
            settle();
            assertThat(servingImage(app)).as("step 6: the rollback serves the earlier image").isEqualTo(first);
            assertThat(fingerprint(worker)).as("step 6: and the container follows it").isEqualTo(first);

            // 7. A command before the deploy that fails switches nothing: the container is left alone.
            creates = creates(worker);
            tick();
            Map<String, Object> withCommand = new LinkedHashMap<>(FakeApps.settingsFor("v3"));
            withCommand.put("pre_deploy_command", "migrate --up");
            settings(app, withCommand);
            daemon.answerNextOneShot(1, "migration failed");
            String before = servingImage(app);
            // The engine refuses the candidate and the prior release keeps serving, which converge answers with.
            catchThrowable(() -> ApplicationReleases.converge(app, Map.of()));
            assertThat(servingImage(app)).as("step 7: the deploy switched nothing").isEqualTo(before);
            settle();
            assertThat(creates(worker)).as("step 7: no create").isEqualTo(creates);
            assertThat(fingerprint(worker)).as("step 7: still the served image").isEqualTo(servingImage(app));

            // 8. A candidate that fails its probe switches nothing either.
            tick();
            FakeApps.setSettings(app, "v4");
            daemon.answerWithForNextWorkload(503);
            catchThrowable(() -> ApplicationReleases.converge(app, Map.of()));
            assertThat(servingImage(app)).as("step 8: the unhealthy candidate switched nothing").isEqualTo(before);
            settle();
            assertThat(creates(worker)).as("step 8: no create").isEqualTo(creates);

            // 9. A throw AFTER the switch (the prior release vanished under the flip): the new release keeps serving
            //    and the container follows it, from the door's finally.
            int prior = ApplicationReleases.ownedServing(app).get(InstanceModel.ID);
            tick();
            FakeApps.setSettings(app, "v5");
            daemon.duringNextStart(() -> Models.get(InstanceModel.class).find()
                .where(InstanceModel.ID.eq(prior)).assign(InstanceModel.DELETED_AT, Now.instant()).updateAll());
            catchThrowable(() -> ApplicationReleases.converge(app, Map.of()));
            settle();
            assertThat((Integer) ApplicationReleases.ownedServing(app).get(InstanceModel.ID))
                .as("step 9: the new release serves").isNotEqualTo(prior);
            assertThat(fingerprint(worker)).as("step 9: and the container follows it").isEqualTo(servingImage(app));

            // 10. An operator's Stop wins over a later deploy; the next Start runs what serves then.
            service.stop(worker);
            creates = creates(worker);
            tick();
            FakeApps.setSettings(app, "v6");
            ApplicationReleases.converge(app, Map.of());
            settle();
            assertThat(daemon.isRunning(handle)).as("step 10: the stopped container stays stopped").isFalse();
            assertThat(creates(worker)).as("step 10: and is not created").isEqualTo(creates);
            service.deploy(worker);
            assertThat(fingerprint(worker)).as("step 10: its Start runs the new image").isEqualTo(servingImage(app));

            // 11. A Stop of the app leaves the container running, and its own Restart still works meanwhile.
            ApplicationReleases.stopFor(app);
            assertThat(daemon.isRunning(handle)).as("step 11: the app's Stop does not stop it").isTrue();
            service.restart(worker, DeployTrigger.MANUAL);
            assertThat(daemon.isRunning(handle)).as("step 11: its Restart runs it").isTrue();
            assertThat(fingerprint(worker)).as("step 11: on the image the stopped app still names")
                .isEqualTo(servingImage(app));
            service.deploy(app);
            settle();

            // 12. An errored container on the served image is left to its crash policy; a new image starts it once.
            Models.get(InstanceModel.class).find().where(InstanceModel.ID.eq(worker))
                .assign(InstanceModel.STATUS, InstanceModel.STATUS_ERROR).updateAll();
            creates = creates(worker);
            AppImageFollowers.converge(app);
            settle();
            assertThat(creates(worker)).as("step 12: no create on the same image").isEqualTo(creates);
            tick();
            FakeApps.setSettings(app, "v7");
            ApplicationReleases.converge(app, Map.of());
            settle();
            assertThat(creates(worker)).as("step 12: one create on a new image").isEqualTo(creates + 1);
            assertThat((String) row(worker).get(InstanceModel.STATUS)).as("step 12: and it runs")
                .isEqualTo(InstanceModel.STATUS_RUNNING);

            // 13. THE GUARANTEE, at the daemon: never two containers of the record at once.
            assertThat(daemon.peakContainersOf(worker)).as("step 13: at most one container ever").isEqualTo(1);

            service.destroy(worker);
            ApplicationReleases.destroyFor(app);
        });
    }

    /**
     * The reads of a switch stay flat as its containers grow, and an app without any pays one indexed read and queues
     * nothing.
     */
    @Test
    void aSwitchReadsTheSameNumberOfRowsWhateverItsContainers() {
        Db.run(datasource, () -> {
            int lone = FakeApps.application("follow-count-none", "v1");
            int one = FakeApps.application("follow-count-one", "v1");
            int three = FakeApps.application("follow-count-three", "v1");
            ApplicationReleases.converge(lone, Map.of());
            ApplicationReleases.converge(one, Map.of());
            ApplicationReleases.converge(three, Map.of());
            settle();
            follower("follow-count-a", one);
            for (String name : List.of("follow-count-b", "follow-count-c", "follow-count-d")) {
                follower(name, three);
            }

            // 1. Without a container: one read, counted warm as the framework's flat-count measure takes it (the
            //    first read of a fresh fork fills what every later one reuses, a cost no app adds).
            DatasourceProbe lonely = DatasourceProbe.on(datasource);
            assertThat(lonely.warmedRoundTrips(() -> AppImageFollowers.converge(lone)))
                .as("step 1: an app nobody follows costs one read: %s", lonely.executedStatements()).isEqualTo(1);

            // 2. One container or three: the same statements for the step line and the overview's version check,
            //    each counted warm as the framework's flat-count measure takes it: the first read of a fresh app
            //    fills what every later one reuses, a cost no container adds.
            DatasourceProbe probe = DatasourceProbe.on(datasource);
            probe.requireFlat(() -> AppImageFollowers.stepLine(one), () -> AppImageFollowers.stepLine(three),
                "step 2: the step line reads flat");
            probe.requireFlat(
                () -> AppImageFollowers.olderVersionAmong(one, AppImageFollowers.followersAmong(List.of(one)).get(one)),
                () -> AppImageFollowers.olderVersionAmong(three,
                    AppImageFollowers.followersAmong(List.of(three)).get(three)),
                "step 2: the overview's reads are flat");
        });
    }

    /**
     * A Restart of an ordinary container halts it and creates it again without writing stopped in between, so a
     * controller that dies between the two leaves a record claiming running, which the sweep restarts; the same for a
     * container following an app racing that app's deploy, never two containers at once.
     */
    @Test
    void aControllerDeathInsideARestartIsRestartedByTheSweepAndNeverRunsTwo() {
        Db.run(datasource, () -> {
            InstanceService service = new InstanceService();
            InstanceStatusReconciler reconciler = new InstanceStatusReconciler();

            // 1. An ordinary container: its Restart is stopped and recorded as before.
            int plain = container("restart-plain", Map.of("image", "fake/worker", "tag", "1"));
            service.deploy(plain);
            service.restart(plain, DeployTrigger.MANUAL);
            assertThat((String) row(plain).get(InstanceModel.STATUS)).as("step 1: a Restart settles running")
                .isEqualTo(InstanceModel.STATUS_RUNNING);
            assertThat(RecordedChanges.withFact(Models.get(InstanceModel.class), plain,
                HohenheimActivityAction.STOPPED)).as("step 1: its stop half is recorded").hasSize(1);

            // 2. The controller dies after the halt and before the create: the record still claims running.
            daemon.failNextCreate(new IllegalStateException("controller killed"));
            assertThat(catchThrowable(() -> service.restart(plain, DeployTrigger.MANUAL)))
                .as("step 2: the death interrupts the restart").isNotNull();
            assertThat((String) row(plain).get(InstanceModel.STATUS))
                .as("step 2: the record never passed through stopped").isEqualTo(InstanceModel.STATUS_RUNNING);
            assertThat(daemon.isRunning(FakeDockerDaemon.handleOf(plain))).as("step 2: nothing runs").isFalse();

            // 3. The sweep corrects it and its crash policy restarts it.
            reconciler.reconcile(plain);
            settle();
            assertThat(daemon.isRunning(FakeDockerDaemon.handleOf(plain))).as("step 3: the sweep restarted it")
                .isTrue();
            assertThat((String) row(plain).get(InstanceModel.STATUS)).as("step 3: running again")
                .isEqualTo(InstanceModel.STATUS_RUNNING);

            // 4. A container following an app crashes and its crash policy restarts it, then the app deploys: both
            //    restarts queue on the one claim, and it ends on the served image.
            int app = FakeApps.application("race-app", "v1");
            ApplicationReleases.converge(app, Map.of());
            int worker = follower("race-worker", app);
            service.deploy(worker);
            daemon.killWorkload(FakeDockerDaemon.handleOf(worker));
            reconciler.reconcile(worker);
            settle();
            tick();
            FakeApps.setSettings(app, "v2");
            ApplicationReleases.converge(app, Map.of());
            settle();
            assertThat(daemon.isRunning(FakeDockerDaemon.handleOf(worker))).as("step 4: it runs").isTrue();
            assertThat(fingerprint(worker)).as("step 4: on the served image").isEqualTo(servingImage(app));
            assertThat(daemon.peakContainersOf(worker)).as("step 4: never two at once").isEqualTo(1);

            // 5. The sweep is a door for a confirmed-running container that is behind, and never for an errored one.
            Models.get(InstanceModel.class).find().where(InstanceModel.ID.eq(worker))
                .assign(InstanceModel.IMAGE_FINGERPRINT, "sha256:older").updateAll();
            int creates = creates(worker);
            assertThat(reconciler.reconcile(worker).verdict()).as("step 5: the sweep confirms it running")
                .isEqualTo(InstanceStatusReconciler.Verdict.CONFIRMED);
            settle();
            assertThat(creates(worker)).as("step 5: and restarts it on its app's version").isEqualTo(creates + 1);
            Models.get(InstanceModel.class).find().where(InstanceModel.ID.eq(worker))
                .assign(InstanceModel.STATUS, InstanceModel.STATUS_ERROR)
                .assign(InstanceModel.IMAGE_FINGERPRINT, "sha256:older").updateAll();
            reconciler.sweep();
            settle();
            assertThat(creates(worker)).as("step 5: an errored one is never retried by the sweep")
                .isEqualTo(creates + 1);

            // 6. Boot converges every app with a container behind it and touches no other container.
            Models.get(InstanceModel.class).find().where(InstanceModel.ID.eq(worker))
                .assign(InstanceModel.STATUS, InstanceModel.STATUS_RUNNING)
                .assign(InstanceModel.IMAGE_FINGERPRINT, "sha256:older").updateAll();
            int plainCreates = creates(plain);
            AppImageFollowers.convergeAll();
            settle();
            assertThat(fingerprint(worker)).as("step 6: boot restarted the container behind").isEqualTo(
                servingImage(app));
            assertThat(creates(plain)).as("step 6: and no other").isEqualTo(plainCreates);
            assertThat(daemon.peakContainersOf(plain)).as("step 6: never two of the plain one").isEqualTo(1);

            service.destroy(worker);
            service.destroy(plain);
            ApplicationReleases.destroyFor(app);
        });
    }

    /**
     * Each write guard refuses in its own words on its own field; an owner change through the grants is caught at the
     * next create; an app's host is fixed while followed, and its Delete is drawn dead and refused in the same words;
     * a follower is never moved.
     */
    @Test
    void theReferenceIsJudgedAtTheWriteAtResolveAndOnTheAppsVerbs() {
        Db.run(datasource, () -> {
            InstanceService service = new InstanceService();
            int app = FakeApps.application("guard-app", "v1");
            ApplicationReleases.converge(app, Map.of());
            int other = container("guard-other", Map.of("image", "fake/worker", "tag", "1"));

            // 1. Only a Docker container, with no image of its own, naming a live app.
            assertThat(violationKeys(catchThrowable(() -> save("guard-kind", "hohenheim:application",
                FakeApps.settingsFor("v1"), app, HostModel.localHostId()))))
                .as("step 1: another kind is refused").contains("image_app_id=image_from_app_kind");
            assertThat(violationKeys(catchThrowable(() -> save("guard-image", DockerContainerKind.ID.toString(),
                Map.of("image", "fake/worker"), app, HostModel.localHostId()))))
                .as("step 1: an image of its own is refused").isEqualTo("settings.image=image_and_image_from_app ");
            assertThat(violationKeys(catchThrowable(() -> save("guard-tag", DockerContainerKind.ID.toString(),
                Map.of("tag", "1"), app, HostModel.localHostId()))))
                .as("step 1: so is a tag").isEqualTo("settings.image=image_and_image_from_app ");
            assertThat(violationKeys(catchThrowable(() -> save("guard-unknown", DockerContainerKind.ID.toString(),
                Map.of(), other, HostModel.localHostId()))))
                .as("step 1: a record that is no app is refused").isEqualTo("image_app_id=image_from_app_unknown ");

            // 2. Another server is refused at the write.
            int elsewhere = HostFixtures.admittedDockerHost("follow-elsewhere", 8192);
            assertThat(violationKeys(catchThrowable(() -> save("guard-server", DockerContainerKind.ID.toString(),
                Map.of(), app, elsewhere))))
                .as("step 2: an app on another host is refused")
                .isEqualTo("image_app_id=image_from_app_other_server ");

            // 3. Another owner is refused at the write, and an owner moved through the grants since is refused at
            //    the next create.
            int worker = follower("guard-worker", app);
            service.deploy(worker);
            UUID owner = ApiSupport.user("follow-owner@hohenheim.local", "Follow Owner");
            int theirs = FakeApps.application("guard-theirs", "v1");
            RecordGrants.grant(GrantSubjectType.USER, owner, InstanceModel.MODEL_ID, theirs,
                HohenheimCapabilities.MANAGE, true);
            assertThat(violationKeys(catchThrowable(() -> save("guard-owner", DockerContainerKind.ID.toString(),
                Map.of(), theirs, HostModel.localHostId()))))
                .as("step 3: an app of another owner is refused").isEqualTo("image_app_id=image_from_app_other_owner ");
            RecordGrants.grant(GrantSubjectType.USER, owner, InstanceModel.MODEL_ID, app,
                HohenheimCapabilities.MANAGE, true);
            assertThat(violationKeys(catchThrowable(() -> service.restart(worker, DeployTrigger.MANUAL))))
                .as("step 3: a drifted owner refuses the next create").isEqualTo(
                    "image_app_id=image_from_app_other_owner ");
            assertThat(daemon.isRunning(FakeDockerDaemon.handleOf(worker)))
                .as("step 3: and the refused restart left it running").isTrue();
            RecordGrants.revoke(GrantSubjectType.USER, owner, InstanceModel.MODEL_ID, app,
                HohenheimCapabilities.MANAGE);

            // 4. The app keeps its server while followed; the move is accepted once the reference is cleared.
            assertThat(violationKeys(catchThrowable(() -> {
                Row moved = row(app);
                moved.set(InstanceModel.HOST_ID, elsewhere);
                Models.get(InstanceModel.class).save(moved);
            }))).as("step 4: a followed app cannot change host").isEqualTo(
                "server_id=app_server_change_has_followers ");

            // 5. A followed app's Delete is drawn dead with its reason, and the service refuses it in the same words.
            Object reason = OperationPipeline.offer(InstanceOperations.DELETE, TenantConduits.operator(), row(app));
            assertThat(reason).as("step 5: the Delete is offered dead")
                .isInstanceOfSatisfying(OperationPipeline.Offer.Unavailable.class, dead ->
                    assertThat(dead.reason().key()).isEqualTo("app_delete_has_followers"));
            assertThat(violationKeys(catchThrowable(() -> service.destroy(app))))
                .as("step 5: the service refuses it by name").isEqualTo("form=app_delete_has_followers ");
            assertThat(row(app)).as("step 5: the app is kept").isNotNull();

            // 6. A follower is never moved: refused by name before any host is chosen.
            InstanceMigrations migrations = new InstanceMigrations(service, step -> { }, (serverId, bytes) -> { });
            assertThat(violationKeys(catchThrowable(() -> migrations.migrateTo(worker, elsewhere))))
                .as("step 6: a move is refused by name").isEqualTo("form=migrate_follows_app ");
            assertThat(violationKeys(catchThrowable(() -> migrations.migrate(worker))))
                .as("step 6: so is an evacuation's").isEqualTo("form=migrate_follows_app ");
            assertThat(daemon.isRunning(FakeDockerDaemon.handleOf(worker))).as("step 6: it keeps running").isTrue();

            // 7. Cleared, the container is an ordinary one and the app moves and is deleted as today.
            Row cleared = row(worker);
            cleared.set(InstanceModel.IMAGE_APP_ID, null);
            cleared.set(InstanceModel.SETTINGS, new LinkedHashMap<>(Map.of("image", "fake/worker", "tag", "1")));
            Models.get(InstanceModel.class).save(cleared);
            assertThat(OperationPipeline.offer(InstanceOperations.DELETE, TenantConduits.operator(), row(app)))
                .as("step 7: the Delete is offered again").isNotInstanceOf(OperationPipeline.Offer.Unavailable.class);
            service.destroy(worker);
            service.destroy(other);
            ApplicationReleases.destroyFor(app);
            ApplicationReleases.destroyFor(theirs);
        });
    }

    private static int follower(String name, int app) {
        Row row = Models.get(InstanceModel.class).createEmptyRow();
        row.set(InstanceModel.NAME, name);
        row.set(InstanceModel.KIND, DockerContainerKind.ID.toString());
        row.set(InstanceModel.HOST_ID, HostModel.localHostId());
        row.set(InstanceModel.SETTINGS, new LinkedHashMap<>(Map.of("command", "worker --queue",
            "memory_limit_mb", 256)));
        row.set(InstanceModel.CRASH_POLICY, InstanceModel.CRASH_RESTART);
        row.set(InstanceModel.IMAGE_APP_ID, app);
        Models.get(InstanceModel.class).save(row);
        return row.get(InstanceModel.ID);
    }

    private static int container(String name, Map<String, Object> settings) {
        Row row = Models.get(InstanceModel.class).createEmptyRow();
        row.set(InstanceModel.NAME, name);
        row.set(InstanceModel.KIND, DockerContainerKind.ID.toString());
        row.set(InstanceModel.HOST_ID, HostModel.localHostId());
        row.set(InstanceModel.SETTINGS, new LinkedHashMap<>(settings));
        row.set(InstanceModel.CRASH_POLICY, InstanceModel.CRASH_RESTART);
        Models.get(InstanceModel.class).save(row);
        return row.get(InstanceModel.ID);
    }

    private static void save(String name, String kind, Map<String, Object> settings, int app, int serverId) {
        Row row = Models.get(InstanceModel.class).createEmptyRow();
        row.set(InstanceModel.NAME, name);
        row.set(InstanceModel.KIND, kind);
        row.set(InstanceModel.HOST_ID, serverId);
        row.set(InstanceModel.SETTINGS, new LinkedHashMap<>(settings));
        row.set(InstanceModel.IMAGE_APP_ID, app);
        Models.get(InstanceModel.class).save(row);
    }

    private static void settings(int app, Map<String, Object> settings) {
        Row row = row(app);
        row.set(InstanceModel.SETTINGS, settings);
        Models.get(InstanceModel.class).save(row);
    }

    private static Row row(int id) {
        return Models.get(InstanceModel.class).findById(id);
    }

    private static String fingerprint(int id) {
        return row(id).get(InstanceModel.IMAGE_FINGERPRINT);
    }

    private static String servingImage(int app) {
        return String.valueOf(ApplicationReleases.storedSettings(ApplicationReleases.ownedServing(app)).get("image"));
    }

    private static int creates(int id) {
        return daemon.callCount("create:" + FakeDockerDaemon.handleOf(id));
    }

    private static String latestStepLog(int app) {
        Row op = Models.get(ReleaseOperationModel.class).find()
            .where(ReleaseOperationModel.FOR_MODEL.eq(InstanceModel.MODEL_ID.toString()))
            .where(ReleaseOperationModel.FOR_ID.eq(app))
            .orderBy(ReleaseOperationModel.ID, SortOrder.DESC)
            .first();
        return op == null ? "" : String.valueOf((Object) op.get(ReleaseOperationModel.STEP_LOG));
    }

    /**
     * Waits for every drain and queued restart, so the next verb on the app or its container finds no claim held;
     * never a poll.
     *
     * AIDEV-NOTE: the drains too: an operator's verb on the app (Start, Restart, Rollback, Delete) refuses while the
     * app's own drain holds its claim, and the background pool alone never covered a drain.
     */
    private static void settle() {
        assertThat(ReleaseEngine.awaitDrainsIdle(Duration.ofSeconds(30))).as("the drains settled").isTrue();
        assertThat(HandlerSupport.awaitBackgroundIdle(Duration.ofSeconds(30))).as("the background work settled")
            .isTrue();
    }

    /** Moves the clock a second on, so a release created next is later than every start before it. */
    private static void tick() {
        Now.setOffset(Now.offset().plusSeconds(1));
    }

    private static String violationKeys(Throwable thrown) {
        assertThat(thrown).isInstanceOf(Violations.class);
        StringBuilder keys = new StringBuilder();
        for (var violation : ((Violations) thrown).all()) {
            if (violation.fieldName() != null && !violation.fieldName().isEmpty()) {
                keys.append(violation.fieldName()).append('=');
            }
            keys.append(violation.message().key()).append(' ');
        }
        return keys.toString();
    }
}
