package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.AttentionItem;
import be.elevenways.hohenheim.HohenheimCapabilities;
import be.elevenways.hohenheim.HohenheimMicrocopy;
import be.elevenways.hohenheim.HohenheimSlugs;
import be.elevenways.hohenheim.model.DatabaseEngineModel;
import be.elevenways.hohenheim.model.DatabaseModel;
import be.elevenways.hohenheim.model.InstanceDatabaseModel;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.server.application.ApplicationReleases;
import be.elevenways.hohenheim.server.database.DatabaseEngines;
import be.elevenways.hohenheim.server.database.DatabaseEnvInjection;
import be.elevenways.hohenheim.server.database.DatabaseInstances;
import be.elevenways.hohenheim.server.database.DatabaseWrites;
import be.elevenways.hohenheim.server.database.EngineHost;
import be.elevenways.hohenheim.server.database.ManagedDatabase;
import be.elevenways.hohenheim.server.docker.DockerClient;
import be.elevenways.hohenheim.server.instance.ConsoleRedaction;
import be.elevenways.hohenheim.server.instance.OwnedInstances;
import be.elevenways.hohenheim.server.runtime.ContainerState;
import be.elevenways.hohenheim.test.ApiSupport;
import be.elevenways.hohenheim.test.HardDeletes;
import be.elevenways.hohenheim.test.HohenheimHttpTestBase;
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
import be.elevenways.zenit.cms.common.action.CmsPlacementSurface;
import be.elevenways.zenit.cms.common.page.CmsEndpoints;
import be.elevenways.zenit.cms.common.page.CmsRoutes;
import be.elevenways.zenit.common.flash.FlashNotice;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.common.security.KnownValueRedactor;
import be.elevenways.zenit.common.security.Secret;
import be.elevenways.zenit.common.validation.Violations;
import be.elevenways.zenit.server.operation.OperationPipeline;
import be.elevenways.zenit.server.operation.OperationRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URLEncoder;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * A managed database's "Change password": the owner rotates a leaked password, the engine is handed the provisioning
 * re-credential command with the new one, the record stores it, the attached app asks for a deploy that injects it,
 * and no surface but the connection card's reveal ever shows it. A failure once the engine was asked leaves a trace a
 * retry clears, and two changes at once end with the engine and the record agreeing.
 *
 * AIDEV-NOTE: the engine is the hermetic {@link FakeDockerDaemon}: what this proves is that the product hands the
 * engine EXACTLY the provisioning command ({@code createLogicalCommand}) with the new password in its environment and
 * acts on the engine's answer. That the real engines then admit the new password and refuse the old one is proven
 * against a real daemon by SharedDatabaseEngineLiveTest step 3f (postgres and mysql); that the scripts keep the
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
            int revealed = occurrences(adminGet(overviewPath).body(), OLD_PASSWORD);
            assertThat(revealed).as("step 1 anchor: the connection card's reveal carries the password").isPositive();

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

            // 6. The pages: the password appears exactly where the connection card's reveal carried the old one (its
            //    masked input, its copy button and their page state) and nowhere more; the form masks it.
            String overview = adminGet(overviewPath).body();
            assertThat(overview).as("step 6 anchor: the connection card draws its password reveal")
                .containsPattern(Pattern.compile("<pl-input[^>]*data-widget-fact-secret[^>]*>"));
            assertThat(occurrences(overview, rotated)).as("step 6: the new password only where the reveal holds it")
                .isEqualTo(revealed);
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
                .contains("may now hold the new password").contains("retry the rotation")
                .as("step 9: redacted too").doesNotContain(unstored);
            assertThat(driftItems(PREFIX + "shop")).as("step 9: the dashboard raises it as a failed operation")
                .hasSize(1);

            // 10. A retry that succeeds stores the password and clears the trace in the same save.
            invoke(null, databases.findById(shopId), Map.of(DatabaseParts.NEW_PASSWORD.getName(), unstored));
            Row retried = databases.findById(shopId);
            assertThat((String) retried.get(DatabaseModel.DB_PASSWORD)).as("step 10: the retry stored it")
                .isEqualTo(unstored);
            assertThat((String) retried.get(DatabaseModel.FAILURE_REASON)).as("step 10: and cleared the drift")
                .isNull();
            assertThat(driftItems(PREFIX + "shop")).as("step 10: the dashboard item is gone").isEmpty();
            assertThat(this.daemon.execsOn(handle)).as("step 10: one more command ran").hasSize(4);

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
                assertThat(this.daemon.execsOn(handle)).as("step 11: and has not reached the engine").hasSize(5);
                releaseFirst.countDown();
                assertThat(rotatingFirst.await(Duration.ofSeconds(30)).failure()).as("step 11: the first succeeded")
                    .isNull();
                assertThat(rotatingSecond.await(Duration.ofSeconds(30)).failure()).as("step 11: then the second")
                    .isNull();
            }
            List<FakeDockerDaemon.Exec> serialized = this.daemon.execsOn(handle);
            assertThat(serialized).as("step 11: each ran once").hasSize(6);
            assertThat(serialized.get(4).env()).as("step 11: the first in the engine first")
                .contains(ManagedDatabase.Engine.LOGICAL_PASSWORD + "=" + first);
            assertThat(serialized.get(5).env()).as("step 11: the second after it")
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

    /** @return how often {@code needle} occurs in {@code text} */
    private static int occurrences(String text, String needle) {
        int count = 0;
        for (int at = text.indexOf(needle); at >= 0; at = text.indexOf(needle, at + needle.length())) {
            count++;
        }
        return count;
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
