package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.AttentionItem;
import be.elevenways.hohenheim.HohenheimCapabilities;
import be.elevenways.hohenheim.HohenheimMicrocopy;
import be.elevenways.hohenheim.HohenheimSlugs;
import be.elevenways.hohenheim.StoredSecret;
import be.elevenways.hohenheim.instance.InstanceOperations;
import be.elevenways.hohenheim.model.DatabaseEngineModel;
import be.elevenways.hohenheim.model.DatabaseModel;
import be.elevenways.hohenheim.model.InstanceDatabaseModel;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.InstanceVariableModel;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.ports.PortLedger;
import be.elevenways.hohenheim.server.application.ApplicationReleases;
import be.elevenways.hohenheim.server.database.DatabaseEngines;
import be.elevenways.hohenheim.server.database.DatabaseEnvInjection;
import be.elevenways.hohenheim.server.database.DatabaseInstances;
import be.elevenways.hohenheim.server.database.DatabaseService;
import be.elevenways.hohenheim.server.database.DatabaseWrites;
import be.elevenways.hohenheim.server.database.EngineHost;
import be.elevenways.hohenheim.server.database.ManagedDatabase;
import be.elevenways.hohenheim.server.docker.DockerClient;
import be.elevenways.hohenheim.server.instance.ConsoleRedaction;
import be.elevenways.hohenheim.server.instance.InstanceService;
import be.elevenways.hohenheim.server.instance.InstanceVariables;
import be.elevenways.hohenheim.server.instance.InstanceSnapshots;
import be.elevenways.hohenheim.server.instance.OwnedInstances;
import be.elevenways.hohenheim.server.runtime.ContainerState;
import be.elevenways.hohenheim.test.ApiSupport;
import be.elevenways.hohenheim.test.HardDeletes;
import be.elevenways.hohenheim.test.HohenheimHttpTestBase;
import be.elevenways.hohenheim.test.InstanceRowCleanup;
import be.elevenways.hohenheim.test.RecordedChanges;
import be.elevenways.hohenheim.test.TenantConduits;
import be.elevenways.hohenheim.test.TestAccessContexts;
import be.elevenways.hohenheim.test.database.EngineHandles;
import be.elevenways.hohenheim.test.docker.FakeDockerDaemon;
import be.elevenways.hohenheim.test.host.HostFixtures;
import be.elevenways.protoblast.common.time.Now;
import be.elevenways.protoblast.testsupport.thread.Racers;
import be.elevenways.protoblast.testsupport.time.Poll;
import be.elevenways.zenit.auth.model.GrantSubjectType;
import be.elevenways.zenit.auth.model.UserPrincipal;
import be.elevenways.zenit.auth.server.RecordGrants;
import be.elevenways.zenit.cms.common.resource.RecordHealth;
import be.elevenways.zenit.cms.common.action.CmsPlacementSurface;
import be.elevenways.zenit.cms.common.page.CmsEndpoints;
import be.elevenways.zenit.cms.common.page.CmsRoutes;
import be.elevenways.zenit.common.flash.FlashNotice;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.orm.datasource.context.SaveToDatasource;
import be.elevenways.zenit.common.orm.model.GlobalModelHooks;
import be.elevenways.zenit.common.orm.model.ModelHook;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.common.security.KnownValueRedactor;
import be.elevenways.zenit.common.security.Secret;
import be.elevenways.zenit.common.validation.Violations;
import be.elevenways.zenit.server.operation.OperationPipeline;
import be.elevenways.zenit.server.operation.OperationRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URLEncoder;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * A managed database's "Change password": the owner rotates a leaked password, the engine is handed the provisioning
 * re-credential command with the new one, the record stores it, the attached app asks for a deploy that injects it,
 * and no page ever carries it: the connection card's reveal fetches it per press. A failure once the engine was asked
 * leaves a trace a retry clears and a pending password the next use of the credential (a retry, a dump) adopts when
 * the engine admits it, and two changes at once end with the engine and the record agreeing. A dedicated
 * database rotates its own engine's credential, and a plain workload attached to it asks for the restart.
 *
 * AIDEV-NOTE: the engine is the hermetic {@link FakeDockerDaemon}: what this proves is that the product hands the
 * engine EXACTLY the credential command ({@code createLogicalCommand}, {@code rotateOwnCommand}) with the passwords in
 * its environment and acts on the engine's answer. That the real engines then admit the new password and refuse the
 * old one is proven against a real daemon by SharedDatabaseEngineLiveTest step 3f (shared postgres and mysql) and
 * DedicatedDatabasePasswordLiveTest (dedicated, all four engines, across a restart); that the scripts keep every
 * password out of every client's argv once the shell expands it, by SharedDatabaseEngineTest.
 *
 * @author Jelle De Loecker
 * @since  0.10.0
 */
class DatabasePasswordChangeJourneyTest extends HohenheimHttpTestBase {

    private static final String PREFIX = "dbpw-";
    private static final String ROOT_PASSWORD = "root-secret-password-1";
    private static final String OLD_PASSWORD = "old-secret-password-1";

    private FakeDockerDaemon daemon;

    @BeforeEach
    void installDaemon() {
        this.daemon = new FakeDockerDaemon();
        DockerClient.overrideLocalTransportForTest(() -> this.daemon);
    }

    @AfterEach
    void restoreDaemon() {
        DockerClient.overrideLocalTransportForTest(null);
        this.daemon.close();
    }

    @Test
    void anOwnerRotatesALeakedPasswordAndTheAppAsksForTheDeployThatInjectsIt() throws Exception {
        List<Runnable> cleanup = new ArrayList<>();
        HostFixtures.LocalHostState localBefore = HostFixtures.captureLocal();
        HostFixtures.makeLocalPlaceable(16L * 1024);
        HostFixtures.giveLocalVolumeQuota();
        Model databases = Models.get(DatabaseModel.class);
        Model instances = Models.get(InstanceModel.class);
        try {
            // 1. A shared postgres engine serving two tenants' databases, an application whose serving release was
            //    built from what is stored now, attached to tenant A's database.
            Row engine = row(cleanup, Models.get(DatabaseEngineModel.class), Map.of(
                DatabaseEngineModel.NAME.getName(), PREFIX + "engine", DatabaseEngineModel.ENGINE.getName(), "postgres",
                DatabaseEngineModel.ROOT_USER.getName(), "root",
                DatabaseEngineModel.ROOT_PASSWORD.getName(), ROOT_PASSWORD,
                DatabaseEngineModel.SERVER_ID.getName(), ServerModel.localServerId(),
                DatabaseEngineModel.STATUS.getName(), DatabaseModel.STATUS_ACTIVE));
            int engineId = engine.get(DatabaseEngineModel.ID);
            String handle = EngineHandles.plantEngine(engineId, PREFIX + "engine", "postgres",
                InstanceModel.STATUS_RUNNING);
            Row planted = DatabaseInstances.ownedBy(EngineHost.ofEngine(engine));
            cleanup.add(() -> OwnedInstances.inScopeUnchecked(DatabaseEngines.SOURCE, DatabaseEngineModel.MODEL_ID,
                engineId, () -> HardDeletes.row(instances, planted)));
            this.daemon.seedRunning(handle);
            Row shop = database(cleanup, engineId, "shop", OLD_PASSWORD);
            int shopId = shop.get(DatabaseModel.ID);
            Row other = database(cleanup, engineId, "other", "other-secret-password-1");

            UUID tenantA = ApiSupport.user(PREFIX + "a-" + UUID.randomUUID() + "@hohenheim.local", "Password Tenant A");
            UUID tenantB = ApiSupport.user(PREFIX + "b-" + UUID.randomUUID() + "@hohenheim.local", "Password Tenant B");
            RecordGrants.grant(GrantSubjectType.USER, tenantA, DatabaseModel.MODEL_ID, shopId,
                HohenheimCapabilities.MANAGE, true);
            RecordGrants.grant(GrantSubjectType.USER, tenantB, DatabaseModel.MODEL_ID, other.get(DatabaseModel.ID),
                HohenheimCapabilities.MANAGE, true);
            UserPrincipal ownerA = new UserPrincipal(tenantA, "Password Tenant A");
            UserPrincipal ownerB = new UserPrincipal(tenantB, "Password Tenant B");

            Row app = row(cleanup, instances, Map.of(InstanceModel.NAME.getName(), PREFIX + "app",
                InstanceModel.KIND.getName(), "hohenheim:application",
                InstanceModel.SETTINGS.getName(), new LinkedHashMap<>(Map.of("repository_url",
                    "https://git.example.test/acme/rotate.git")),
                InstanceModel.SERVER_ID.getName(), ServerModel.localServerId()));
            int appId = app.get(InstanceModel.ID);
            int[] release = new int[1];
            ApplicationReleases.inScopeUnchecked(appId, () -> {
                Row serving = instances.createEmptyRow();
                serving.set(InstanceModel.NAME, PREFIX + "app");
                serving.set(InstanceModel.KIND, "hohenheim:release");
                serving.set(InstanceModel.SETTINGS, new LinkedHashMap<>(Map.of("image", "alpine")));
                serving.set(InstanceModel.SERVER_ID, ServerModel.localServerId());
                serving.set(InstanceModel.RUNTIME_ROLE, InstanceModel.ROLE_SERVING);
                instances.save(serving);
                release[0] = serving.get(InstanceModel.ID);
            });
            cleanup.add(() -> ApplicationReleases.inScopeUnchecked(appId,
                () -> HardDeletes.byId(instances, release[0])));
            instances.find().where(InstanceModel.ID.eq(release[0]))
                .assign(InstanceModel.STATUS, InstanceModel.STATUS_RUNNING).bypassBehaviours().updateAll();
            row(cleanup, Models.get(InstanceDatabaseModel.class), Map.of(
                InstanceDatabaseModel.INSTANCE_ID.getName(), appId,
                InstanceDatabaseModel.DATABASE_ID.getName(), shopId,
                InstanceDatabaseModel.ENV_PREFIX.getName(), "DB"));
            // An hour back, so the rotation's own stamp moves the fingerprint whatever the clock's resolution.
            databases.find().where(DatabaseModel.ID.eq(shopId))
                .assign(DatabaseModel.UPDATED_AT, Now.instant().minus(Duration.ofHours(1)))
                .bypassBehaviours().updateAll();
            Row serving = instances.findById(release[0]);
            Map<String, Object> built = new LinkedHashMap<>(InstanceModel.settingsOf(serving));
            built.put("source_fingerprint", ApplicationReleases.configuredFingerprint(instances.findById(appId),
                serving));
            serving.set(InstanceModel.SETTINGS, built);
            ApplicationReleases.inScopeUnchecked(appId, () -> instances.save(serving));
            assertThat(ApplicationReleases.configPending(instances.findById(appId)))
                .as("step 1 anchor: the serving release was built from what is stored").isFalse();
            assertThat(this.daemon.execsOn(handle)).as("step 1 anchor: nothing ran in the engine yet").isEmpty();
            String overviewPath = "/admin/" + HohenheimSlugs.DATABASES + "/" + shopId + "/page/overview";
            assertThat(adminGet(overviewPath).body()).as("step 1 anchor: no page carries the password")
                .doesNotContain(OLD_PASSWORD);
            assertThat(revealedValue(revealSecret(StoredSecret.DATABASE_PASSWORD, shopId, sessionToken, csrfToken)))
                .as("step 1 anchor: the reveal answers it on request").isEqualTo(OLD_PASSWORD);

            // 2. Tenant A, who manages the database, rotates with nothing typed: a password is generated, the engine
            //    is handed the provisioning re-credential command with it, and the record stores what the engine took.
            invoke(ownerA, shop, Map.of());
            String rotated = databases.findById(shopId).get(DatabaseModel.DB_PASSWORD);
            assertThat(rotated).as("step 2: the record holds a new password").isNotEqualTo(OLD_PASSWORD)
                .as("step 2: generated like a create's (24 url-safe characters)").matches("[A-Za-z0-9_-]{24}");
            List<FakeDockerDaemon.Exec> execs = this.daemon.execsOn(handle);
            assertThat(execs).as("step 2: one command ran inside the engine").hasSize(1);
            assertThat(execs.get(0).command()).as("step 2: the provisioning re-credential command")
                .isEqualTo(ManagedDatabase.Engine.POSTGRES.createLogicalCommand("root", "shopdb", "shopuser"));
            assertThat(execs.get(0).env()).as("step 2: as root, with the NEW password in its environment")
                .isEqualTo(ManagedDatabase.Engine.POSTGRES.logicalEnv(ROOT_PASSWORD, rotated));
            assertThat(String.join(" ", execs.get(0).command())).as("step 2: never on the command line")
                .doesNotContain(rotated);

            // 3. The attached app asks for a deploy (its saved configuration is not live), and the environment that
            //    deploy injects carries the new password, raw and in the URL; nothing restarted it.
            assertThat(ApplicationReleases.configPending(instances.findById(appId)))
                .as("step 3: the app's serving release no longer matches what is stored").isTrue();
            assertThat(AppHealth.instances(false).read(instances.findById(appId), TenantConduits.operator())
                .headline().key()).as("step 3: its health asks for the deploy").isEqualTo("changes_not_live");
            Row stillServing = ApplicationReleases.ownedServing(appId);
            assertThat(stillServing).as("step 3: nothing redeployed or restarted the app").isNotNull();
            assertThat((Integer) stillServing.get(InstanceModel.ID)).as("step 3: the same release still serves")
                .isEqualTo(release[0]);
            assertThat((String) stillServing.get(InstanceModel.STATUS)).as("step 3: still running")
                .isEqualTo(InstanceModel.STATUS_RUNNING);
            Map<String, String> env = DatabaseEnvInjection.envForInstance(appId,
                database -> new ManagedDatabase.LiveStatus(ContainerState.RUNNING, 5432));
            assertThat(env.get("DB_PASSWORD")).as("step 3: the next deploy injects the new password")
                .isEqualTo(rotated);
            assertThat(env.get("DB_URL")).as("step 3: and its URL spells it encoded")
                .contains(":" + DatabaseEnvInjection.urlEncoded(rotated) + "@")
                .doesNotContain(OLD_PASSWORD);

            // 4. Redaction knows the new password: the database's own output and its attached app's.
            assertThat(ConsoleRedaction.secretsOfOwner(DatabaseModel.MODEL_ID, shopId).values())
                .as("step 4: the database's redaction holds the new password, raw and URL-encoded")
                .contains(rotated, DatabaseEnvInjection.urlEncoded(rotated)).doesNotContain(OLD_PASSWORD);
            assertThat(ConsoleRedaction.secretsOf(appId).values())
                .as("step 4: and so does the attached app's").contains(rotated);

            // 5. The activity entry names who changed it and never the value, old or new.
            RecordedChanges.Recorded change = RecordedChanges.lastUpdateOf(databases, shopId,
                DatabaseModel.DB_PASSWORD.getName());
            assertThat(change.actor()).as("step 5: the owner is the actor").isEqualTo(tenantA.toString());
            String recorded = change.delta() + " " + change.facts() + " " + change.change() + " " + change.action();
            assertThat(recorded).as("step 5: no password in the recorded change")
                .doesNotContain(rotated).doesNotContain(OLD_PASSWORD);

            // 6. The pages: neither password is in the overview (a link to the tab whose card reveals it per press),
            //    the reveal answers the new one, and the form masks it.
            String overview = adminGet(overviewPath).body();
            assertThat(overview).as("step 6 anchor: the overview points at the tab that reveals the password")
                .contains("/admin/" + HohenheimSlugs.DATABASES + "/" + shopId + "/page/" + HohenheimSlugs.Tab.RESTORE);
            assertThat(overview).as("step 6: the new password is in no page").doesNotContain(rotated);
            assertThat(revealedValue(revealSecret(StoredSecret.DATABASE_PASSWORD, shopId, sessionToken, csrfToken)))
                .as("step 6: the reveal answers the new password").isEqualTo(rotated);
            assertThat(overview).as("step 6: the old one is gone").doesNotContain(OLD_PASSWORD);
            assertThat(adminGet("/admin/" + HohenheimSlugs.DATABASES + "/" + shopId).body())
                .as("step 6: the form never draws it").doesNotContain(rotated);

            // 7. Tenant B, who manages only their own database, cannot rotate tenant A's: refused, nothing ran.
            Throwable foreign = catchThrowable(() -> invoke(ownerB, databases.findById(shopId), Map.of()));
            assertThat(foreign).as("step 7: another tenant's rotation is refused").isNotNull();
            assertThat((String) databases.findById(shopId).get(DatabaseModel.DB_PASSWORD))
                .as("step 7: the password is unchanged").isEqualTo(rotated);
            assertThat(this.daemon.execsOn(handle)).as("step 7: and nothing ran in the engine").hasSize(1);

            // 8. The operator types one: a value the create rule refuses (a character the scripts cannot spell, or
            //    shorter than every log can redact) is refused on its field and never echoed; a valid one is set as
            //    typed, and the answer names the apps, never the value.
            String invoke = CmsRoutes.invoke(HohenheimSlugs.ADMIN, HohenheimSlugs.DATABASES,
                DatabaseParts.CHANGE_PASSWORD.id()).with(CmsEndpoints.SUBJECT_PARAM, String.valueOf(shopId)).toUrl();
            String rule = ApiSupport.shippedText(HohenheimMicrocopy.VIOLATIONS.of("database_logical_password")
                .withArg("minimum", ManagedDatabase.Engine.LOGICAL_PASSWORD_MIN)
                .withArg("maximum", ManagedDatabase.Engine.LOGICAL_IDENTIFIER_MAX));
            String tooShort = "Sh0rt-" + "p".repeat(ManagedDatabase.Engine.LOGICAL_PASSWORD_MIN - 7);
            assertThat(ManagedDatabase.Engine.LOGICAL_PASSWORD_MIN)
                .as("step 8 anchor: the minimum is at least twelve and at least what every log redacts")
                .isGreaterThanOrEqualTo(12).isGreaterThanOrEqualTo(KnownValueRedactor.MIN_LENGTH);
            for (String refusedTyped : List.of("bad/slash-password", tooShort)) {
                HttpResponse<String> refused = adminPostForm(invoke, typedBody(refusedTyped));
                assertThat(refused.statusCode()).as("step 8: the create rule refuses '%s', redrawn", refusedTyped)
                    .isEqualTo(422);
                assertThat(refused.body()).as("step 8: naming the rule with its bounds").contains(rule)
                    .as("step 8: never echoing the typed value").doesNotContain(refusedTyped);
            }
            assertThat(this.daemon.execsOn(handle)).as("step 8: nothing ran for either").hasSize(1);

            String typed = "Typed.password-0002";
            HttpResponse<String> accepted = adminPostForm(invoke, typedBody(typed));
            assertThat(accepted.statusCode()).as("step 8: a valid one is accepted").isBetween(300, 399);
            assertThat((String) databases.findById(shopId).get(DatabaseModel.DB_PASSWORD))
                .as("step 8: and stored as typed").isEqualTo(typed);
            assertThat(this.daemon.execsOn(handle).get(1).env()).as("step 8: the engine was handed it")
                .contains(ManagedDatabase.Engine.LOGICAL_PASSWORD + "=" + typed);
            FlashNotice toast = popFlash(accepted);
            assertThat(toast).as("step 8: the answer is a toast").isNotNull();
            assertThat(toast.message().key()).as("step 8: saying it changed").isEqualTo("password_changed");
            assertThat(toastText(toast, "en")).as("step 8: naming the apps' next deploy")
                .contains("next deploy").doesNotContain(typed);
            assertThat(String.valueOf(toast.message().args().asMap())).as("step 8: no argument carries the value")
                .doesNotContain(typed);

            // 9. The engine fails the command AFTER its password statement (the script's later CREATE DATABASE is
            //    refused): the refusal says why with the new password redacted out of the engine's own words, the
            //    record keeps what it named, and because the engine may hold the new password now, the record
            //    carries that drift as an active record's failure reason the dashboard raises.
            String unstored = "Typed.engine-refused-0003";
            this.daemon.refuseNextExec(1, "DO\nERROR: permission denied to create database (role shopuser, password '"
                + unstored + "')");
            Throwable engineRefused = catchThrowable(() -> invoke(null, databases.findById(shopId),
                Map.of(DatabaseParts.NEW_PASSWORD.getName(), unstored)));
            Violations violations = violationsIn(engineRefused);
            assertThat(violations.all().get(0).message().key()).as("step 9: the refusal names the failed change")
                .isEqualTo("database_password_failed");
            String reason = String.valueOf(violations.all().get(0).message().args().asMap().get("reason"));
            assertThat(reason).as("step 9: in the engine's words").contains("permission denied to create database")
                .as("step 9: with the new password redacted").doesNotContain(unstored)
                .contains(KnownValueRedactor.PLACEHOLDER)
                .as("step 9: saying the engine may hold the new one").contains("may now hold the new password");
            Row drifted = databases.findById(shopId);
            assertThat((String) drifted.get(DatabaseModel.DB_PASSWORD))
                .as("step 9: the record keeps the password it named").isEqualTo(typed);
            assertThat((String) drifted.get(DatabaseModel.STATUS)).as("step 9: still active")
                .isEqualTo(DatabaseModel.STATUS_ACTIVE);
            assertThat((String) drifted.get(DatabaseModel.FAILURE_REASON)).as("step 9: the drift is stamped")
                .contains("may now hold the new password").contains("which the record keeps as pending")
                .as("step 9: redacted too").doesNotContain(unstored);
            assertThat((String) drifted.get(DatabaseModel.PENDING_PASSWORD))
                .as("step 9: the record keeps the new password as pending").isEqualTo(unstored);
            assertThat(driftItems(PREFIX + "shop")).as("step 9: the dashboard raises it as a failed operation")
                .hasSize(1);

            // 10. A retry first asks the engine which password the tenant user logs in with: the stored one (the fake
            //     engine admits it), so the record and the engine agree and nothing is pending. The change then
            //     stores the password and clears the trace in the same save.
            invoke(null, databases.findById(shopId), Map.of(DatabaseParts.NEW_PASSWORD.getName(), unstored));
            Row retried = databases.findById(shopId);
            assertThat((String) retried.get(DatabaseModel.DB_PASSWORD)).as("step 10: the retry stored it")
                .isEqualTo(unstored);
            assertThat((String) retried.get(DatabaseModel.FAILURE_REASON)).as("step 10: and cleared the drift")
                .isNull();
            assertThat((String) retried.get(DatabaseModel.PENDING_PASSWORD)).as("step 10: nothing is pending")
                .isNull();
            assertThat(driftItems(PREFIX + "shop")).as("step 10: the dashboard item is gone").isEmpty();
            List<FakeDockerDaemon.Exec> retriedExecs = this.daemon.execsOn(handle);
            assertThat(retriedExecs).as("step 10: the credential check, then the change").hasSize(5);
            assertThat(retriedExecs.get(3).command()).as("step 10: the tenant user's credential check")
                .isEqualTo(ManagedDatabase.Engine.POSTGRES.credentialCheckCommand("shopuser", "shopdb", true));
            assertThat(retriedExecs.get(3).env()).as("step 10: of the stored password")
                .isEqualTo(ManagedDatabase.Engine.POSTGRES.credentialCheckEnv(typed));

            // 11. Two changes at once: the second waits on the engine lock while the first sits in the engine, so the
            //     engine's last password and the record's are the same one.
            String first = "Concurrent.first-0011";
            String second = "Concurrent.second-0012";
            CountDownLatch firstInEngine = new CountDownLatch(1);
            CountDownLatch releaseFirst = new CountDownLatch(1);
            this.daemon.duringNextExec(() -> {
                firstInEngine.countDown();
                try {
                    releaseFirst.await(30, TimeUnit.SECONDS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            });
            try (Racers rotations = Racers.named("two password changes of one database")) {
                Racers.Running<Object> rotatingFirst = rotations.start("the first change", () -> {
                    DatabaseWrites.changePassword(databases.findById(shopId), Secret.of(first));
                    return null;
                });
                assertThat(firstInEngine.await(30, TimeUnit.SECONDS))
                    .as("step 11 anchor: the first change reached the engine").isTrue();
                Racers.Running<Object> rotatingSecond = rotations.start("the second change", () -> {
                    DatabaseWrites.changePassword(databases.findById(shopId), Secret.of(second));
                    return null;
                });
                Poll.until("step 11: the second change parked on the engine lock, or ended", Duration.ofSeconds(10),
                    () -> rotatingSecond.thread().getState() == Thread.State.BLOCKED || !rotatingSecond.isRunning());
                assertThat(rotatingSecond.thread().getState()).as("step 11: the second change waits on the lock")
                    .isEqualTo(Thread.State.BLOCKED);
                assertThat(this.daemon.execsOn(handle)).as("step 11: and has not reached the engine").hasSize(6);
                releaseFirst.countDown();
                assertThat(rotatingFirst.await(Duration.ofSeconds(30)).failure()).as("step 11: the first succeeded")
                    .isNull();
                assertThat(rotatingSecond.await(Duration.ofSeconds(30)).failure()).as("step 11: then the second")
                    .isNull();
            }
            List<FakeDockerDaemon.Exec> serialized = this.daemon.execsOn(handle);
            assertThat(serialized).as("step 11: each ran once").hasSize(7);
            assertThat(serialized.get(5).env()).as("step 11: the first in the engine first")
                .contains(ManagedDatabase.Engine.LOGICAL_PASSWORD + "=" + first);
            assertThat(serialized.get(6).env()).as("step 11: the second after it")
                .contains(ManagedDatabase.Engine.LOGICAL_PASSWORD + "=" + second);
            assertThat((String) databases.findById(shopId).get(DatabaseModel.DB_PASSWORD))
                .as("step 11: the record names the engine's last password").isEqualTo(second);
        } finally {
            for (int i = cleanup.size() - 1; i >= 0; i--) {
                cleanup.get(i).run();
            }
            localBefore.restore();
        }
    }

    /**
     * A DEDICATED database rotates its own engine's credential, every engine through its own script with both
     * passwords off the command line, its engine instance's stored environment follows so a restart (which recreates
     * the container) starts from the new password, and a plain workload attached to it, which no release fingerprint
     * covers, says it runs with the old password until a restart gives it the new one.
     */
    @Test
    void aDedicatedEngineTakesItsNewPasswordAndAPlainWorkloadAsksForTheRestartThatGivesItToIt(@TempDir Path dumps)
            throws Exception {
        List<Runnable> cleanup = new ArrayList<>();
        HostFixtures.LocalHostState localBefore = HostFixtures.captureLocal();
        HostFixtures.makeLocalPlaceable(16L * 1024);
        HostFixtures.giveLocalVolumeQuota();
        this.daemon.installContainerKind();
        Model databases = Models.get(DatabaseModel.class);
        Model instances = Models.get(InstanceModel.class);
        try {
            // 1. One dedicated database per engine, each served by its own running engine instance, and a plain
            //    container workload attached to the postgres one, deployed once: its attachment records the
            //    credential generation it was deployed with.
            Map<ManagedDatabase.Engine, Row> dedicated = new LinkedHashMap<>();
            Map<ManagedDatabase.Engine, String> handles = new LinkedHashMap<>();
            for (ManagedDatabase.Engine engine : ManagedDatabase.Engine.values()) {
                Row database = row(cleanup, databases, Map.of(DatabaseModel.NAME.getName(), PREFIX + "own-"
                        + engine.token(), DatabaseModel.ENGINE.getName(), engine.token(),
                    DatabaseModel.PLACEMENT.getName(), DatabaseModel.PLACEMENT_DEDICATED,
                    DatabaseModel.DB_NAME.getName(), "appdb", DatabaseModel.DB_USER.getName(), "appuser",
                    DatabaseModel.DB_PASSWORD.getName(), OLD_PASSWORD,
                    DatabaseModel.SERVER_ID.getName(), ServerModel.localServerId(),
                    DatabaseModel.STATUS.getName(), DatabaseModel.STATUS_ACTIVE));
                int databaseId = database.get(DatabaseModel.ID);
                String handle = EngineHandles.plant(databaseId, PREFIX + "own-" + engine.token(), engine.token(),
                    InstanceModel.STATUS_RUNNING);
                int engineInstance = DatabaseInstances.owned(databaseId).get(InstanceModel.ID);
                cleanup.add(() -> OwnedInstances.inScopeUnchecked(DatabaseInstances.SOURCE, DatabaseModel.MODEL_ID,
                    databaseId, () -> InstanceRowCleanup.delete(engineInstance)));
                this.daemon.seedRunning(handle);
                dedicated.put(engine, database);
                handles.put(engine, handle);
            }
            int postgresId = dedicated.get(ManagedDatabase.Engine.POSTGRES).get(DatabaseModel.ID);
            Row app = instances.createEmptyRow();
            app.set(InstanceModel.NAME, PREFIX + "plain");
            app.set(InstanceModel.KIND, "hohenheim:docker_container");
            app.set(InstanceModel.SERVER_ID, ServerModel.localServerId());
            app.set(InstanceModel.SETTINGS, new LinkedHashMap<>(Map.of("image", "fake/app", "tag", "v1",
                "container_port", 8080)));
            instances.save(app);
            int appId = app.get(InstanceModel.ID);
            cleanup.add(() -> {
                PortLedger.releaseOwnerFully(InstanceModel.MODEL_ID, appId);
                InstanceRowCleanup.delete(appId);
            });
            Row link = row(cleanup, Models.get(InstanceDatabaseModel.class), Map.of(
                InstanceDatabaseModel.INSTANCE_ID.getName(), appId,
                InstanceDatabaseModel.DATABASE_ID.getName(), postgresId,
                InstanceDatabaseModel.ENV_PREFIX.getName(), "DB"));
            InstanceService service = new InstanceService();
            service.deploy(appId);
            assertThat(this.daemon.isRunning(FakeDockerDaemon.handleOf(appId)))
                .as("step 1 anchor: the plain workload runs").isTrue();
            assertThat((Integer) Models.get(InstanceDatabaseModel.class).findById(link.get(InstanceDatabaseModel.ID))
                .get(InstanceDatabaseModel.DEPLOYED_GENERATION))
                .as("step 1: its attachment records the generation it was deployed with").isZero();
            assertThat(healthOf(appId).headline().key()).as("step 1: and nothing asks for a restart")
                .isNotEqualTo("credentials_not_live");
            for (ManagedDatabase.Engine engine : ManagedDatabase.Engine.values()) {
                assertThat(DatabaseParts.passwordUnavailable(List.of(dedicated.get(engine))).get(0))
                    .as("step 1 (%s): a serving dedicated database offers Change password", engine).isNull();
            }

            // 2. Each dedicated database rotates with nothing typed: ONE command ran in its own engine, its own
            //    rotation, the current password and the new one in its environment and neither on its command line.
            //    The record stores the new password one credential generation further, and the engine instance's
            //    stored environment is what a deploy would write from the record now: the next container create
            //    starts from the new password.
            for (ManagedDatabase.Engine engine : ManagedDatabase.Engine.values()) {
                int databaseId = dedicated.get(engine).get(DatabaseModel.ID);
                invoke(null, databases.findById(databaseId), Map.of());
                Row rotatedRow = databases.findById(databaseId);
                String rotated = rotatedRow.get(DatabaseModel.DB_PASSWORD);
                assertThat(rotated).as("step 2 (%s): the record holds a new generated password", engine)
                    .isNotEqualTo(OLD_PASSWORD).matches("[A-Za-z0-9_-]{24}");
                assertThat(DatabaseModel.credentialGenerationOf(rotatedRow))
                    .as("step 2 (%s): one credential generation further", engine).isEqualTo(1);
                List<FakeDockerDaemon.Exec> execs = this.daemon.execsOn(handles.get(engine));
                assertThat(execs).as("step 2 (%s): one command ran in its own engine", engine).hasSize(1);
                assertThat(execs.get(0).command()).as("step 2 (%s): the engine's own rotation", engine)
                    .isEqualTo(engine.rotateOwnCommand("appuser"));
                assertThat(execs.get(0).env()).as("step 2 (%s): the current and the new password ride the env", engine)
                    .isEqualTo(engine.rotateEnv(OLD_PASSWORD, rotated));
                assertThat(String.join(" ", execs.get(0).command()))
                    .as("step 2 (%s): neither is on the command line", engine)
                    .doesNotContain(rotated).doesNotContain(OLD_PASSWORD);
                int engineInstance = DatabaseInstances.owned(databaseId).get(InstanceModel.ID);
                assertThat(new InstanceVariables().valuesFor(engineInstance))
                    .as("step 2 (%s): the engine instance's stored environment names the new password", engine)
                    .isEqualTo(DatabaseInstances.engineEnvironment(EngineHost.dedicated(rotatedRow)))
                    .containsAllEntriesOf(engine.secretEnv("appuser", rotated));
            }

            // 3. The plain workload still runs with the old password: its health says so, naming the database, with
            //    Restart as the fix. Nothing restarted it.
            assertThat(this.daemon.isRunning(FakeDockerDaemon.handleOf(appId)))
                .as("step 3 anchor: the workload still runs").isTrue();
            var stale = healthOf(appId);
            assertThat(stale.headline().key()).as("step 3: it asks for the new password")
                .isEqualTo("credentials_not_live");
            assertThat(String.valueOf(stale.detail().args().asMap().get("database")))
                .as("step 3: naming the database").isEqualTo(PREFIX + "own-postgres");
            assertThat(stale.fixes()).as("step 3: Restart is the fix")
                .containsExactly(InstanceOperations.RESTART.id());

            // 4. The restart derives the new password into its environment and records the generation it was
            //    deployed with: the notice is gone.
            service.restart(appId);
            assertThat((Integer) Models.get(InstanceDatabaseModel.class).findById(link.get(InstanceDatabaseModel.ID))
                .get(InstanceDatabaseModel.DEPLOYED_GENERATION))
                .as("step 4: the attachment records the new generation").isEqualTo(1);
            assertThat(healthOf(appId).headline().key()).as("step 4: and the workload no longer asks")
                .isNotEqualTo("credentials_not_live");

            // 5. Storing a rotation fails after the engine took it: the engine instance's stored environment refuses
            //    its write. The record and that environment are ONE transaction, so neither changed, and the same
            //    command set the engine back to the password the record keeps: nothing drifted, so nothing is stamped,
            //    and the refusal says the change did not happen.
            Set<Integer> refusingStore = new HashSet<>();
            boolean[] refuseSetBack = {false};
            ModelHook<SaveToDatasource> readOnlyEnvironment = context -> {
                if (context.getModel() instanceof InstanceVariableModel && context.getRow() != null
                        && refusingStore.contains(context.getRow().get(InstanceVariableModel.INSTANCE_ID))) {
                    if (refuseSetBack[0]) {
                        // The rotation already ran: the next exec is the set-back.
                        this.daemon.refuseNextExec(2, "psql: error: connection to server failed");
                    }
                    throw new IllegalStateException("the variable store refused the write");
                }
            };
            ManagedDatabase.Engine postgres = ManagedDatabase.Engine.POSTGRES;
            String postgresKept = databases.findById(postgresId).get(DatabaseModel.DB_PASSWORD);
            int postgresEngine = DatabaseInstances.owned(postgresId).get(InstanceModel.ID);
            Map<String, String> postgresEnvironment = new InstanceVariables().valuesFor(postgresEngine);
            String stranded = "Typed.stranded-0006";
            int mysqlId = dedicated.get(ManagedDatabase.Engine.MYSQL).get(DatabaseModel.ID);
            String mysqlKept = databases.findById(mysqlId).get(DatabaseModel.DB_PASSWORD);
            int mysqlEngine = DatabaseInstances.owned(mysqlId).get(InstanceModel.ID);
            String dumpedWith = "Typed.dumped-0006b";
            GlobalModelHooks.addBeforeWriteHook(readOnlyEnvironment);
            try {
                ManagedDatabase.Engine redis = ManagedDatabase.Engine.REDIS;
                int redisId = dedicated.get(redis).get(DatabaseModel.ID);
                String redisKept = databases.findById(redisId).get(DatabaseModel.DB_PASSWORD);
                int redisEngine = DatabaseInstances.owned(redisId).get(InstanceModel.ID);
                Map<String, String> redisEnvironment = new InstanceVariables().valuesFor(redisEngine);
                String unstored = "Typed.unstored-0005";
                refusingStore.add(redisEngine);
                // Through the operation's write itself: the invoke route allows five changes a minute per caller.
                Violations undone = violationsIn(catchThrowable(() -> DatabaseWrites.changePassword(
                    databases.findById(redisId), Secret.of(unstored))));
                assertThat(undone.all().get(0).message().key()).as("step 5: the change is refused")
                    .isEqualTo("database_password_failed");
                assertThat(String.valueOf(undone.all().get(0).message().args().asMap().get("reason")))
                    .as("step 5: saying the engine was set back").contains("set back")
                    .contains("nothing changed").doesNotContain(unstored);
                Row kept = databases.findById(redisId);
                assertThat((String) kept.get(DatabaseModel.DB_PASSWORD)).as("step 5: the record keeps its password")
                    .isEqualTo(redisKept);
                assertThat(DatabaseModel.credentialGenerationOf(kept)).as("step 5: and its generation").isEqualTo(1);
                assertThat((String) kept.get(DatabaseModel.FAILURE_REASON)).as("step 5: nothing drifted").isNull();
                assertThat((String) kept.get(DatabaseModel.PENDING_PASSWORD))
                    .as("step 5: and no password is left pending").isNull();
                assertThat(new InstanceVariables().valuesFor(redisEngine))
                    .as("step 5: the stored environment is unchanged").isEqualTo(redisEnvironment);
                List<FakeDockerDaemon.Exec> redisExecs = this.daemon.execsOn(handles.get(redis));
                assertThat(redisExecs).as("step 5: the rotation, then the set-back").hasSize(3);
                assertThat(redisExecs.get(1).env()).as("step 5: the engine took the new password")
                    .isEqualTo(redis.rotateEnv(redisKept, unstored));
                assertThat(redisExecs.get(2).command()).as("step 5: the set-back is the same rotation")
                    .isEqualTo(redis.rotateOwnCommand("appuser"));
                assertThat(redisExecs.get(2).env()).as("step 5: from the new password back to the kept one")
                    .isEqualTo(redis.rotateEnv(unstored, redisKept));
                refusingStore.clear();

                // 6. The same failure where the set-back is refused too: the engine holds the new password while the
                //    record and the stored environment keep the old one, so the drift is stamped, saying a restart of
                //    the database is unsafe and that the record keeps the new password as pending, and the dashboard
                //    raises it. The pending password is a secret: every redaction of the record's output masks it,
                //    and no recorded change of the record carries it.
                refusingStore.add(postgresEngine);
                refuseSetBack[0] = true;
                Violations drifted = violationsIn(catchThrowable(() -> DatabaseWrites.changePassword(
                    databases.findById(postgresId), Secret.of(stranded))));
                assertThat(String.valueOf(drifted.all().get(0).message().args().asMap().get("reason")))
                    .as("step 6: the refusal says a restart is unsafe").contains("restarting the database is unsafe")
                    .doesNotContain(stranded);
                Row stamped = databases.findById(postgresId);
                assertThat((String) stamped.get(DatabaseModel.DB_PASSWORD)).as("step 6: the record keeps its password")
                    .isEqualTo(postgresKept);
                assertThat((String) stamped.get(DatabaseModel.PENDING_PASSWORD))
                    .as("step 6: and the new one as pending").isEqualTo(stranded);
                assertThat((String) stamped.get(DatabaseModel.STATUS)).as("step 6: still active")
                    .isEqualTo(DatabaseModel.STATUS_ACTIVE);
                assertThat((String) stamped.get(DatabaseModel.FAILURE_REASON)).as("step 6: the drift is stamped")
                    .contains("restarting the database is unsafe").contains("The record keeps it as pending")
                    .doesNotContain(stranded);
                assertThat(driftItems(PREFIX + "own-postgres")).as("step 6: the dashboard raises it").hasSize(1);
                assertThat(new InstanceVariables().valuesFor(postgresEngine))
                    .as("step 6: the stored environment is unchanged").isEqualTo(postgresEnvironment);
                List<FakeDockerDaemon.Exec> postgresExecs = this.daemon.execsOn(handles.get(postgres));
                assertThat(postgresExecs).as("step 6: the rotation, then the refused set-back").hasSize(3);
                assertThat(postgresExecs.get(2).env()).as("step 6: the set-back was asked for")
                    .isEqualTo(postgres.rotateEnv(stranded, postgresKept));
                assertThat(ConsoleRedaction.redactWhole("the engine said " + stranded,
                    ConsoleRedaction.secretsOfOwner(DatabaseModel.MODEL_ID, postgresId)))
                    .as("step 6: the record's redaction masks the pending password").doesNotContain(stranded)
                    .contains(KnownValueRedactor.PLACEHOLDER);
                RecordedChanges.Recorded pendingWrite = RecordedChanges.lastUpdateOf(databases, postgresId,
                    DatabaseModel.PENDING_PASSWORD.getName());
                assertThat(pendingWrite.delta() + " " + pendingWrite.facts() + " " + pendingWrite.change() + " "
                    + pendingWrite.action()).as("step 6: the recorded change that set it pending never carries it")
                    .doesNotContain(stranded);
                assertThat(String.valueOf(RecordedChanges.of(databases, postgresId)))
                    .as("step 6: nor does any other").doesNotContain(stranded);

                // 6b. The same drift on the mysql database, whose next dump will find it.
                refusingStore.add(mysqlEngine);
                Violations mysqlDrifted = violationsIn(catchThrowable(() -> DatabaseWrites.changePassword(
                    databases.findById(mysqlId), Secret.of(dumpedWith))));
                assertThat(String.valueOf(mysqlDrifted.all().get(0).message().args().asMap().get("reason")))
                    .as("step 6b: the mysql change drifted too").contains("restarting the database is unsafe");
                assertThat((String) databases.findById(mysqlId).get(DatabaseModel.PENDING_PASSWORD))
                    .as("step 6b: keeping its new password as pending").isEqualTo(dumpedWith);
                refusingStore.clear();
                refuseSetBack[0] = false;
            } finally {
                GlobalModelHooks.removeBeforeWriteHook(readOnlyEnvironment);
            }

            // 7. A retry finds the engine refusing the stored password and admitting the pending one: the record
            //    adopts it first (one generation further, the stored environment naming it, nothing pending, the
            //    drift cleared), and the change then rotates from the password the engine holds. The record, two
            //    generations further, and the stored environment name the retried password.
            String retried = "Typed.retried-0007";
            String postgresHandle = handles.get(postgres);
            int beforeRetry = this.daemon.execsOn(postgresHandle).size();
            this.daemon.refuseNextExec(2, "psql: error: password authentication failed for user \"appuser\"");
            DatabaseWrites.changePassword(databases.findById(postgresId), Secret.of(retried));
            List<FakeDockerDaemon.Exec> recovery = this.daemon.execsOn(postgresHandle);
            recovery = recovery.subList(beforeRetry, recovery.size());
            assertThat(recovery).as("step 7: two credential checks, then the rotation").hasSize(3);
            assertThat(recovery.get(0).command()).as("step 7: the engine's own credential check")
                .isEqualTo(postgres.credentialCheckCommand("appuser", "appdb", false));
            assertThat(recovery.get(0).env()).as("step 7: of the stored password first, refused")
                .isEqualTo(postgres.credentialCheckEnv(postgresKept));
            assertThat(recovery.get(1).env()).as("step 7: then of the pending one, admitted")
                .isEqualTo(postgres.credentialCheckEnv(stranded));
            assertThat(recovery.get(2).env()).as("step 7: the rotation authenticates with the adopted password")
                .isEqualTo(postgres.rotateEnv(stranded, retried));
            Row cleared = databases.findById(postgresId);
            assertThat((String) cleared.get(DatabaseModel.DB_PASSWORD)).as("step 7: the retry stored it")
                .isEqualTo(retried);
            assertThat(DatabaseModel.credentialGenerationOf(cleared))
                .as("step 7: one generation for the adoption, one for the retry").isEqualTo(3);
            assertThat((String) cleared.get(DatabaseModel.PENDING_PASSWORD)).as("step 7: nothing is pending").isNull();
            assertThat((String) cleared.get(DatabaseModel.FAILURE_REASON)).as("step 7: the drift is cleared").isNull();
            assertThat(driftItems(PREFIX + "own-postgres")).as("step 7: the dashboard item is gone").isEmpty();
            assertThat(new InstanceVariables().valuesFor(postgresEngine))
                .as("step 7: the stored environment names the retried password")
                .containsAllEntriesOf(ManagedDatabase.Engine.POSTGRES.secretEnv("appuser", retried));

            // 8. A dump of the drifted mysql database finds the engine refusing the stored password: the record adopts
            //    the pending one and the dump authenticates with it.
            ManagedDatabase.Engine mysql = ManagedDatabase.Engine.MYSQL;
            String mysqlHandle = handles.get(mysql);
            int beforeDump = this.daemon.execsOn(mysqlHandle).size();
            this.daemon.refuseNextExec(1, "ERROR 1045 (28000): Access denied for user 'appuser'@'127.0.0.1'");
            new DatabaseService().backupToFile(PREFIX + "own-mysql", dumps, "drift");
            List<FakeDockerDaemon.Exec> dumped = this.daemon.execsOn(mysqlHandle);
            dumped = dumped.subList(beforeDump, dumped.size());
            assertThat(dumped).as("step 8: two credential checks, then the dump").hasSize(3);
            assertThat(dumped.get(0).env()).as("step 8: the stored password was refused")
                .isEqualTo(mysql.credentialCheckEnv(mysqlKept));
            assertThat(dumped.get(1).env()).as("step 8: the pending one admitted")
                .isEqualTo(mysql.credentialCheckEnv(dumpedWith));
            assertThat(dumped.get(2).env()).as("step 8: the dump authenticated with the pending password")
                .containsExactly("MYSQL_PWD=" + dumpedWith);
            Row adopted = databases.findById(mysqlId);
            assertThat((String) adopted.get(DatabaseModel.DB_PASSWORD)).as("step 8: the record stores it")
                .isEqualTo(dumpedWith);
            assertThat((String) adopted.get(DatabaseModel.PENDING_PASSWORD)).as("step 8: nothing is pending").isNull();
            assertThat((String) adopted.get(DatabaseModel.FAILURE_REASON)).as("step 8: the drift is cleared").isNull();
            assertThat(new InstanceVariables().valuesFor(DatabaseInstances.owned(mysqlId).get(InstanceModel.ID)))
                .as("step 8: the stored environment names it")
                .containsAllEntriesOf(mysql.secretEnv("appuser", dumpedWith));

            // 9. No capture lane carries a dedicated engine's data, so no restore can put an older credential back
            //    under the record: a snapshot of the engine instance is refused before anything ran, its data volume
            //    being no declared volume.
            int execsBefore = this.daemon.execsOn(handles.get(ManagedDatabase.Engine.POSTGRES)).size();
            Violations noVolumes = violationsIn(catchThrowable(() -> new InstanceSnapshots().create(postgresEngine,
                null)));
            assertThat(noVolumes.all().get(0).message().key()).as("step 9: the snapshot is refused")
                .isEqualTo("snapshot_no_volumes");
            assertThat(this.daemon.execsOn(handles.get(ManagedDatabase.Engine.POSTGRES)))
                .as("step 9: nothing ran in the engine").hasSize(execsBefore);
            assertThat(this.daemon.isRunning(handles.get(ManagedDatabase.Engine.POSTGRES)))
                .as("step 9: which still runs").isTrue();

            // 10. Neither password admitted: a drifted change left a pending password, and a failure that is no
            //     credential stamp sits beside it. The engine refuses both the stored and the pending password, so a
            //     change is refused by name with nothing stored: no rotation was asked for (it would authenticate
            //     with the refused stored password), the pending password is still the one the engine may hold, and
            //     the record, its generation, its failure reason and the stored environment are unchanged.
            ManagedDatabase.Engine mongo = ManagedDatabase.Engine.MONGO;
            int mongoId = dedicated.get(mongo).get(DatabaseModel.ID);
            String mongoHandle = handles.get(mongo);
            String foreign = "The nightly dump failed: no space left on device";
            String mongoStranded = "Typed.stranded-0010";
            Row planted = databases.findById(mongoId);
            planted.set(DatabaseModel.PENDING_PASSWORD, mongoStranded);
            planted.set(DatabaseModel.FAILURE_REASON, foreign);
            databases.save(planted);
            Row mongoBefore = databases.findById(mongoId);
            String mongoKept = mongoBefore.get(DatabaseModel.DB_PASSWORD);
            int mongoGeneration = DatabaseModel.credentialGenerationOf(mongoBefore);
            int mongoEngine = DatabaseInstances.owned(mongoId).get(InstanceModel.ID);
            Map<String, String> mongoEnvironment = new InstanceVariables().valuesFor(mongoEngine);
            int beforeNeither = this.daemon.execsOn(mongoHandle).size();
            this.daemon.refuseNextExec(1, "MongoServerError: Authentication failed.");
            this.daemon.duringNextExec(() -> this.daemon.refuseNextExec(1, "MongoServerError: Authentication failed."));
            String refusedChange = "Typed.refused-0010";
            Violations neither = violationsIn(catchThrowable(() -> DatabaseWrites.changePassword(
                databases.findById(mongoId), Secret.of(refusedChange))));
            assertThat(String.valueOf(neither.all().get(0).message().args().asMap().get("reason")))
                .as("step 10: the change is refused by name")
                .contains("accepts neither the stored nor the pending password").contains("Manual recovery is needed")
                .doesNotContain(refusedChange).doesNotContain(mongoStranded).doesNotContain(mongoKept);
            List<FakeDockerDaemon.Exec> checks = this.daemon.execsOn(mongoHandle);
            checks = checks.subList(beforeNeither, checks.size());
            assertThat(checks).as("step 10: the two credential checks and no rotation").hasSize(2);
            assertThat(checks.get(0).command()).as("step 10: the engine's own credential check")
                .isEqualTo(mongo.credentialCheckCommand("appuser", "appdb", false));
            assertThat(checks.get(0).env()).as("step 10: of the stored password, refused")
                .isEqualTo(mongo.credentialCheckEnv(mongoKept));
            assertThat(checks.get(1).env()).as("step 10: then of the pending one, refused")
                .isEqualTo(mongo.credentialCheckEnv(mongoStranded));
            Row neitherRow = databases.findById(mongoId);
            assertThat((String) neitherRow.get(DatabaseModel.DB_PASSWORD)).as("step 10: the record keeps its password")
                .isEqualTo(mongoKept);
            assertThat((String) neitherRow.get(DatabaseModel.PENDING_PASSWORD))
                .as("step 10: and the pending one the engine may hold, not the refused change's")
                .isEqualTo(mongoStranded);
            assertThat(DatabaseModel.credentialGenerationOf(neitherRow)).as("step 10: and its generation")
                .isEqualTo(mongoGeneration);
            assertThat((String) neitherRow.get(DatabaseModel.FAILURE_REASON)).as("step 10: and its failure reason")
                .isEqualTo(foreign);
            assertThat(new InstanceVariables().valuesFor(mongoEngine))
                .as("step 10: the stored environment is unchanged").isEqualTo(mongoEnvironment);
            assertThat(String.valueOf(RecordedChanges.of(databases, mongoId)))
                .as("step 10: no recorded change of the record carries a password")
                .doesNotContain(mongoStranded).doesNotContain(refusedChange);

            // 11. The engine admits the stored password again: the change clears the pending password and rotates,
            //     and the failure beside it stays, since only a credential stamp is the change's to clear.
            String settledChange = "Typed.settled-0011";
            DatabaseWrites.changePassword(databases.findById(mongoId), Secret.of(settledChange));
            List<FakeDockerDaemon.Exec> settledExecs = this.daemon.execsOn(mongoHandle);
            settledExecs = settledExecs.subList(beforeNeither + 2, settledExecs.size());
            assertThat(settledExecs).as("step 11: the credential check, then the rotation").hasSize(2);
            assertThat(settledExecs.get(1).env()).as("step 11: the rotation authenticates with the stored password")
                .isEqualTo(mongo.rotateEnv(mongoKept, settledChange));
            Row settledRow = databases.findById(mongoId);
            assertThat((String) settledRow.get(DatabaseModel.DB_PASSWORD)).as("step 11: the change is stored")
                .isEqualTo(settledChange);
            assertThat((String) settledRow.get(DatabaseModel.PENDING_PASSWORD)).as("step 11: nothing is pending")
                .isNull();
            assertThat(DatabaseModel.credentialGenerationOf(settledRow)).as("step 11: one generation further")
                .isEqualTo(mongoGeneration + 1);
            assertThat((String) settledRow.get(DatabaseModel.FAILURE_REASON))
                .as("step 11: the failure that is no credential stamp stays").isEqualTo(foreign);
        } finally {
            for (int i = cleanup.size() - 1; i >= 0; i--) {
                cleanup.get(i).run();
            }
            FakeDockerDaemon.restore();
            localBefore.restore();
        }
    }

    /**
     * A dedicated redis engine whose stored environment names no password, which its start script refuses, is given
     * one by the boot repair before its next start: the record's own when it has one, else a generated one through the
     * rotation (a running engine) or stored with its environment (a stopped one); a repair that fails is raised on the
     * dashboard.
     */
    @Test
    void aRedisEngineStoredWithoutAPasswordIsGivenOneBeforeItsNextStart() {
        List<Runnable> cleanup = new ArrayList<>();
        this.daemon.installContainerKind();
        Model databases = Models.get(DatabaseModel.class);
        DatabaseService service = new DatabaseService();
        ManagedDatabase.Engine redis = ManagedDatabase.Engine.REDIS;
        Set<String> passwordKeys = redis.secretEnv("unused").keySet();
        try {
            // 1. The record has a password, its engine instance's stored environment none: the repair writes the
            //    environment from the record again; nothing ran in the engine and the record is unchanged. A second
            //    pass finds nothing to repair.
            PlantedRedis kept = plantedRedis(cleanup, "kept", OLD_PASSWORD, true);
            assertThat(new InstanceVariables().valuesFor(kept.instanceId()))
                .as("step 1 anchor: the stored environment names no password").doesNotContainKeys(
                    passwordKeys.toArray(String[]::new));
            assertThat(service.repairRedisPassword(kept.databaseId())).as("step 1: repaired").isTrue();
            Row keptRow = databases.findById(kept.databaseId());
            assertThat(new InstanceVariables().valuesFor(kept.instanceId()))
                .as("step 1: the stored environment is what a deploy writes from the record")
                .isEqualTo(DatabaseInstances.engineEnvironment(EngineHost.dedicated(keptRow)))
                .containsAllEntriesOf(redis.secretEnv(OLD_PASSWORD));
            assertThat((String) keptRow.get(DatabaseModel.DB_PASSWORD)).as("step 1: the record keeps its password")
                .isEqualTo(OLD_PASSWORD);
            assertThat(DatabaseModel.credentialGenerationOf(keptRow)).as("step 1: and its generation").isZero();
            assertThat(this.daemon.execsOn(kept.handle())).as("step 1: nothing ran in the engine").isEmpty();
            assertThat(service.repairRedisPassword(kept.databaseId())).as("step 1: a second pass repairs nothing")
                .isFalse();

            // 2. A running engine whose record names only blanks: a generated password through the rotation, which
            //    authenticates with nothing (a redis demanding no password takes it), stored on the record one
            //    generation further and in the stored environment.
            PlantedRedis blank = plantedRedis(cleanup, "blank", "   ", true);
            assertThat(service.repairRedisPassword(blank.databaseId())).as("step 2: repaired").isTrue();
            Row blankRow = databases.findById(blank.databaseId());
            String generated = blankRow.get(DatabaseModel.DB_PASSWORD);
            assertThat(generated).as("step 2: the record holds a generated password").matches("[A-Za-z0-9_-]{24}");
            assertThat(DatabaseModel.credentialGenerationOf(blankRow)).as("step 2: one generation further")
                .isEqualTo(1);
            List<FakeDockerDaemon.Exec> rotation = this.daemon.execsOn(blank.handle());
            assertThat(rotation).as("step 2: one command ran in the engine").hasSize(1);
            assertThat(rotation.get(0).command()).as("step 2: its own rotation")
                .isEqualTo(redis.rotateOwnCommand("appuser"));
            assertThat(rotation.get(0).env()).as("step 2: authenticating with no password")
                .isEqualTo(redis.rotateEnv("   ", generated))
                .noneMatch(variable -> variable.startsWith("REDISCLI_AUTH="));
            assertThat(new InstanceVariables().valuesFor(blank.instanceId()))
                .as("step 2: the stored environment names it").containsAllEntriesOf(redis.secretEnv(generated));

            // 3. A stopped engine whose record has no password: nothing can run in it, and a stopped redis holds its
            //    password nowhere but its start configuration, so the generated one is stored with its environment.
            PlantedRedis stopped = plantedRedis(cleanup, "stopped", "", false);
            assertThat(service.repairRedisPassword(stopped.databaseId())).as("step 3: repaired").isTrue();
            Row stoppedRow = databases.findById(stopped.databaseId());
            String stored = stoppedRow.get(DatabaseModel.DB_PASSWORD);
            assertThat(stored).as("step 3: the record holds a generated password").matches("[A-Za-z0-9_-]{24}");
            assertThat(DatabaseModel.credentialGenerationOf(stoppedRow)).as("step 3: one generation further")
                .isEqualTo(1);
            assertThat(new InstanceVariables().valuesFor(stopped.instanceId()))
                .as("step 3: the stored environment names it").containsAllEntriesOf(redis.secretEnv(stored));
            assertThat(this.daemon.execsOn(stopped.handle())).as("step 3: nothing ran in the engine").isEmpty();

            // 4. The rotation refused: the repair says so on the record, which the dashboard raises, and the stored
            //    environment still names no password.
            PlantedRedis refused = plantedRedis(cleanup, "refused", "", true);
            this.daemon.refuseNextExec(1, "ERR unknown command 'CONFIG'");
            assertThat(service.repairRedisPassword(refused.databaseId())).as("step 4: not repaired").isFalse();
            assertThat((String) databases.findById(refused.databaseId()).get(DatabaseModel.FAILURE_REASON))
                .as("step 4: the record says why").contains("has no password in its stored environment")
                .contains("cannot start again");
            assertThat(driftItems(PREFIX + "refused")).as("step 4: the dashboard raises it").hasSize(1);
            assertThat(new InstanceVariables().valuesFor(refused.instanceId()))
                .as("step 4: the stored environment still names no password")
                .doesNotContainKeys(passwordKeys.toArray(String[]::new));
        } finally {
            for (int i = cleanup.size() - 1; i >= 0; i--) {
                cleanup.get(i).run();
            }
            FakeDockerDaemon.restore();
        }
    }

    /** A dedicated redis database and its planted engine instance. */
    private record PlantedRedis(int databaseId, int instanceId, String handle) {
    }

    /** @return a dedicated redis record named {@code <prefix><name>} with its engine instance, running or stopped */
    private PlantedRedis plantedRedis(List<Runnable> cleanup, String name, String password, boolean running) {
        Row database = row(cleanup, Models.get(DatabaseModel.class), Map.of(DatabaseModel.NAME.getName(), PREFIX + name,
            DatabaseModel.ENGINE.getName(), ManagedDatabase.Engine.REDIS.token(),
            DatabaseModel.PLACEMENT.getName(), DatabaseModel.PLACEMENT_DEDICATED,
            DatabaseModel.DB_NAME.getName(), "appdb", DatabaseModel.DB_USER.getName(), "appuser",
            DatabaseModel.DB_PASSWORD.getName(), password,
            DatabaseModel.SERVER_ID.getName(), ServerModel.localServerId(),
            DatabaseModel.STATUS.getName(), DatabaseModel.STATUS_ACTIVE));
        int databaseId = database.get(DatabaseModel.ID);
        String handle = EngineHandles.plant(databaseId, PREFIX + name, ManagedDatabase.Engine.REDIS.token(),
            running ? InstanceModel.STATUS_RUNNING : InstanceModel.STATUS_STOPPED);
        int instanceId = DatabaseInstances.owned(databaseId).get(InstanceModel.ID);
        cleanup.add(() -> OwnedInstances.inScopeUnchecked(DatabaseInstances.SOURCE, DatabaseModel.MODEL_ID,
            databaseId, () -> InstanceRowCleanup.delete(instanceId)));
        if (running) {
            this.daemon.seedRunning(handle);
        }
        return new PlantedRedis(databaseId, instanceId, handle);
    }

    /** @return an instance's health as the operator reads it on its overview */
    private static RecordHealth healthOf(int instanceId) {
        return AppHealth.instances(false).read(Models.get(InstanceModel.class).findById(instanceId),
            TenantConduits.operator());
    }

    /** @return the dashboard's "last operation failed" items about the database named {@code name} */
    private static List<AttentionItem> driftItems(String name) {
        List<AttentionItem> items = new ArrayList<>();
        DatabaseAttention.failedDatabases(items);
        List<AttentionItem> mine = new ArrayList<>();
        for (AttentionItem item : items) {
            if (item.detail() != null && "database_operation_failed".equals(item.detail().key())
                    && name.equals(String.valueOf(item.title().args().asMap().get("name")))) {
                mine.add(item);
            }
        }
        return mine;
    }

    /** Runs the change as {@code owner} (null: the operator) the way the invoke route runs it. */
    private static void invoke(UserPrincipal owner, Row database, Map<String, Object> form) {
        AccessContext caller = owner == null ? TenantConduits.operator() : TestAccessContexts.contextFor(owner);
        OperationPipeline.invoke(OperationRequest.of(DatabaseParts.CHANGE_PASSWORD, CmsPlacementSurface.ADMIN_ACTION)
            .caller(caller)
            .subjects(List.of(database))
            .form(form));
    }

    /** @return the url-encoded invoke body typing {@code password} */
    private static String typedBody(String password) {
        return DatabaseParts.NEW_PASSWORD.getName() + "=" + URLEncoder.encode(password, StandardCharsets.UTF_8) + "&"
            + ApiSupport.invokeTransport();
    }

    /** @return the Violations a refusal carries, itself or as a cause */
    private static Violations violationsIn(Throwable thrown) {
        for (Throwable cause = thrown; cause != null; cause = cause.getCause()) {
            if (cause instanceof Violations violations) {
                return violations;
            }
        }
        throw new AssertionError("no Violations in " + thrown, thrown);
    }

    /** A shared postgres database on the engine: {@code <name>db} owned by {@code <name>user}. */
    private static Row database(List<Runnable> cleanup, int engineId, String name, String password) {
        return row(cleanup, Models.get(DatabaseModel.class), Map.of(DatabaseModel.NAME.getName(), PREFIX + name,
            DatabaseModel.ENGINE.getName(), "postgres", DatabaseModel.PLACEMENT.getName(),
            DatabaseModel.PLACEMENT_SHARED, DatabaseModel.ENGINE_ID.getName(), engineId,
            DatabaseModel.DB_NAME.getName(), name + "db", DatabaseModel.DB_USER.getName(), name + "user",
            DatabaseModel.DB_PASSWORD.getName(), password,
            DatabaseModel.SERVER_ID.getName(), ServerModel.localServerId(),
            DatabaseModel.STATUS.getName(), DatabaseModel.STATUS_ACTIVE));
    }

    private static Row row(List<Runnable> cleanup, Model model, Map<String, Object> values) {
        Row row = model.createEmptyRow();
        values.forEach(row::set);
        model.save(row);
        cleanup.add(() -> HardDeletes.row(model, row));
        return row;
    }
}
