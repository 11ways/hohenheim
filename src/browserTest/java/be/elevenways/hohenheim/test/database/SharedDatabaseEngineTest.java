package be.elevenways.hohenheim.test.database;

import be.elevenways.hohenheim.model.DatabaseEngineModel;
import be.elevenways.hohenheim.model.DatabaseModel;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.server.database.DatabaseContainerKind;
import be.elevenways.hohenheim.server.database.DatabaseEngines;
import be.elevenways.hohenheim.server.database.DatabaseInstances;
import be.elevenways.hohenheim.server.database.DatabaseService;
import be.elevenways.hohenheim.server.database.EngineHost;
import be.elevenways.hohenheim.server.database.ManagedDatabase;
import be.elevenways.hohenheim.server.database.TenantDatabases;
import be.elevenways.hohenheim.server.docker.ResourceLimits;
import be.elevenways.hohenheim.server.docker.ServerService;
import be.elevenways.hohenheim.server.instance.ConsoleRedaction;
import be.elevenways.hohenheim.server.instance.InstanceCapacity;
import be.elevenways.hohenheim.test.HohenheimTestRuntime;
import be.elevenways.hohenheim.test.TestDatabases;
import be.elevenways.hohenheim.test.host.HostFixtures;
import be.elevenways.zenit.common.orm.datasource.Db;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.datasource.sql.SqlDatasource;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.security.KnownValueRedactor;
import be.elevenways.zenit.common.validation.Violations;
import be.elevenways.protoblast.server.process.ProcessOutcome;
import be.elevenways.protoblast.server.process.Subprocess;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * The ALLOCATION half of shared database engines, with no daemon anywhere: which
 * placement a new record gets, what an engine row and its owned instance look like when
 * the funnel creates one, every refusal that keeps a shared record and its engine from
 * disagreeing, and the shell commands the engine tier drives a logical database with.
 *
 * AIDEV-NOTE: the env-injection half of the shared shape lives in
 * {@code DatabaseEnvInjectionTest} beside every other injection claim rather than being
 * re-asserted here -- that is the declaring home of "what a workload is told", and a
 * second copy would be exactly the drift this repo keeps paying for.
 *
 * AIDEV-NOTE: no container is ever started. The reservation half of the funnel
 * ({@code DatabaseInstances.reserveEngineRow}) is deliberately transactional and
 * daemon-free, which is what lets an allocation be refused on the operator's own form,
 * and it is exactly the half this class exercises. The DAEMON half is
 * {@code SharedDatabaseEngineLiveTest}.
 */
class SharedDatabaseEngineTest {

    /**
     * The image {@code ManagedDatabase.Engine.MONGO} defaults to. Spelled here because the
     * enum's {@code defaultImage} field is package-private to the server package; the
     * value is asserted against what the funnel really wrote, so a drift shows up as a
     * failure here rather than as silence.
     */
    private static final String MONGO_DEFAULT_IMAGE = "mongo:7";

    /** The host the refusal journey allocates on, so it never shares an engine row with
     *  the allocation journey whatever order JUnit runs them in. */
    private static final String REFUSAL_HOST = "shared-engine-refusals";

    private static SqlDatasource datasource;

    @BeforeAll
    static void setUp() throws Exception {
        datasource = TestDatabases.freshDatasource();
        HohenheimTestRuntime.ensureBooted();
        // An operator's own decisions about their own machine: the engine instance row is
        // a real placement and a host nobody admitted or measured would refuse it for a
        // reason that has nothing to do with placement.
        Db.run(datasource, () -> {
            HostFixtures.makeLocalPlaceable(16384);
            sshServer(REFUSAL_HOST);
        });
    }

    private static DatabaseService service() {
        return new DatabaseService(datasource);
    }

    /**
     * THE default placement: shared wherever the engine has a real per-database namespace
     * AND the data is persistent; dedicated otherwise.
     */
    @Test
    void theDefaultPlacementFollowsTheEnginesNamespaceAndThePersistenceShape() {
        // 1. The three engines with per-database credentials default to SHARED, which is
        //    the whole point of the change: one engine process per host, not per record.
        for (ManagedDatabase.Engine engine : List.of(ManagedDatabase.Engine.MONGO,
                ManagedDatabase.Engine.MYSQL, ManagedDatabase.Engine.POSTGRES)) {
            assertThat(engine.supportsLogicalDatabases())
                .as("step 1 (%s): declares logical databases", engine).isTrue();
            assertThat(DatabaseService.defaultPlacement(engine, false))
                .as("step 1 (%s): a persistent record defaults to shared", engine)
                .isEqualTo(DatabaseModel.PLACEMENT_SHARED);
        }

        // 2. An EPHEMERAL record is always its own container: the tmpfs is a property of
        //    that container and nothing else can carry it.
        for (ManagedDatabase.Engine engine : ManagedDatabase.Engine.values()) {
            assertThat(DatabaseService.defaultPlacement(engine, true))
                .as("step 2 (%s): a tmpfs database is dedicated by definition", engine)
                .isEqualTo(DatabaseModel.PLACEMENT_DEDICATED);
        }

        // 3. Redis is the deliberate NO: its numbered databases share one password over
        //    one keyspace, which is the shared credential a managed database exists to
        //    avoid -- so it is dedicated in BOTH persistence shapes.
        assertThat(ManagedDatabase.Engine.REDIS.supportsLogicalDatabases())
            .as("step 3: redis declares no logical databases").isFalse();
        assertThat(DatabaseService.defaultPlacement(ManagedDatabase.Engine.REDIS, false))
            .as("step 3: so even a persistent redis is dedicated")
            .isEqualTo(DatabaseModel.PLACEMENT_DEDICATED);
    }

    /**
     * The allocation funnel: the FIRST shared record of a kind on a host mints the engine
     * row, its root credentials and its owned instance; the SECOND binds to the very same
     * engine without minting anything.
     */
    @Test
    void theFirstSharedRecordMintsTheHostsEngineAndTheSecondJoinsIt() {
        DatabaseService service = service();

        // 1. A mongo record created with the default placement lands SHARED and bound.
        Row first = service.insertRecord("shareda", ManagedDatabase.Engine.MONGO, null,
            "usera", "password-a-0001", "dba", false, ServerService.LOCAL_HOST_NAME, ResourceLimits.none(),
            DatabaseModel.STATUS_PROVISIONING);
        Db.run(datasource, () -> {
            Row stored = Models.get(DatabaseModel.class).findByName("shareda");
            assertThat((String) stored.get(DatabaseModel.PLACEMENT))
                .as("step 1: the default placement of a persistent mongo is shared")
                .isEqualTo(DatabaseModel.PLACEMENT_SHARED);
            assertThat((Integer) stored.get(DatabaseModel.ENGINE_ID))
                .as("step 1: and it is bound to an engine").isNotNull();

            // 2. THE ENGINE ROW: named for its kind and host, root credentials generated,
            //    provisioning until a daemon says otherwise.
            int engineId = stored.get(DatabaseModel.ENGINE_ID);
            Row engine = Models.get(DatabaseEngineModel.class).findById(engineId);
            assertThat(engine).as("step 2: the engine row exists").isNotNull();
            assertThat((String) engine.get(DatabaseEngineModel.NAME))
                .as("step 2: named <engine>-<host>, the auto-created spelling")
                .isEqualTo("mongo-local");
            assertThat((String) engine.get(DatabaseEngineModel.ENGINE)).isEqualTo("mongo");
            assertThat((String) engine.get(DatabaseEngineModel.ROOT_USER))
                .as("step 2: the controller's own superuser").isEqualTo("root");
            assertThat((String) engine.get(DatabaseEngineModel.ROOT_PASSWORD))
                .as("step 2: with a generated password, never the record's")
                .isNotBlank()
                .isNotEqualTo("password-a-0001");
            assertThat((String) engine.get(DatabaseEngineModel.STATUS))
                .as("step 2: nothing is running yet, so it is provisioning")
                .isEqualTo(DatabaseModel.STATUS_PROVISIONING);
            assertThat((Integer) engine.get(DatabaseEngineModel.SERVER_ID))
                .as("step 2: on the record's host").isEqualTo(ServerModel.localServerId());

            // 3. THE OWNED INSTANCE: the engine owns a database_container exactly like a
            //    dedicated record used to, flagged shared, on the engine's default image,
            //    and booked ONCE at the shared footprint.
            Row instance = DatabaseInstances.ownedBy(EngineHost.ofEngine(engine));
            assertThat(instance).as("step 3: the engine owns an instance").isNotNull();
            assertThat((String) instance.get(InstanceModel.KIND))
                .as("step 3: of the database-container kind")
                .isEqualTo(DatabaseContainerKind.ID.toString());
            @SuppressWarnings("unchecked")
            Map<String, Object> settings =
                (Map<String, Object>) instance.get(InstanceModel.SETTINGS);
            assertThat(settings).as("step 3: the settings declare the shared shape")
                .containsEntry("shared", true)
                .containsEntry("engine", "mongo")
                .containsEntry("image", MONGO_DEFAULT_IMAGE)
                .containsEntry("ephemeral", false);
            assertThat(String.valueOf(settings.get("data_volume")))
                .as("step 3: on a data volume keyed to the ENGINE, not to a database")
                .isEqualTo(EngineHost.ofEngine(engine).dataVolume())
                .contains("dbengine");
            assertThat(InstanceCapacity.footprintMbOf(instance))
                .as("step 3: booked once at the declared shared footprint")
                .isEqualTo(ManagedDatabase.Engine.MONGO.sharedFootprintMb())
                .isEqualTo(1024);
            assertThat(new DatabaseContainerKind().defaultFootprintMb(settings))
                .as("step 3: the kind reads the same number off the same settings")
                .isEqualTo(1024);
        });

        // 4. A SECOND mongo record on the same host binds to the SAME engine -- no second
        //    engine row and no second container. That is the entire saving.
        service.insertRecord("sharedb", ManagedDatabase.Engine.MONGO, null,
            "userb", "password-b-0001", "dbb", false, ServerService.LOCAL_HOST_NAME, ResourceLimits.none(),
            DatabaseModel.STATUS_PROVISIONING);
        Db.run(datasource, () -> {
            Integer engineA = Models.get(DatabaseModel.class).findByName("shareda")
                .get(DatabaseModel.ENGINE_ID);
            Integer engineB = Models.get(DatabaseModel.class).findByName("sharedb")
                .get(DatabaseModel.ENGINE_ID);
            assertThat(engineB).as("step 4: the second record joins the SAME engine")
                .isEqualTo(engineA);
            assertThat(Models.get(DatabaseEngineModel.class).find()
                    .where(DatabaseEngineModel.ENGINE.eq("mongo"))
                    .where(DatabaseEngineModel.SERVER_ID.eq(ServerModel.localServerId()))
                    .all())
                .as("step 4: the host has exactly one mongo engine").hasSize(1);
            assertThat(Models.get(InstanceModel.class).find()
                    .where(InstanceModel.GENERATED_FOR_MODEL
                        .eq(DatabaseEngineModel.MODEL_ID.toString()))
                    .where(InstanceModel.GENERATED_FOR_ID.eq(engineA))
                    .where(InstanceModel.DELETED_AT.isNull()).all())
                .as("step 4: and exactly one engine container serves both").hasSize(1);
            assertThat(DatabaseEngines.databasesOn(engineA))
                .as("step 4: the engine knows both of its logical databases").hasSize(2);

            // 4b. A THIRD record may not reuse a logical NAME the engine already holds
            //     (that is the other record's data), nor a logical USER (engine-global
            //     on MySQL and Postgres: the second create re-credentialed the first and
            //     one credential reached both databases, 2026-09-02 on robbedoes). Both
            //     are refused by name BEFORE any row is written.
            assertThat(refusalOf(() -> service.insertRecord("sharedc",
                    ManagedDatabase.Engine.MONGO, null, "userc", "password-c-0001", "dba", false,
                    ServerService.LOCAL_HOST_NAME, ResourceLimits.none(),
                    DatabaseModel.STATUS_PROVISIONING)))
                .as("step 4b: a taken logical database name is refused by name")
                .isEqualTo("database_logical_name_taken");
            assertThat(refusalOf(() -> service.insertRecord("sharedc",
                    ManagedDatabase.Engine.MONGO, null, "usera", "password-c-0001", "dbc", false,
                    ServerService.LOCAL_HOST_NAME, ResourceLimits.none(),
                    DatabaseModel.STATUS_PROVISIONING)))
                .as("step 4b: a taken logical user is refused by name")
                .isEqualTo("database_logical_user_taken");
            assertThat(Models.get(DatabaseModel.class).findByName("sharedc"))
                .as("step 4b: and neither refusal left a record behind").isNull();
            assertThat(DatabaseEngines.databasesOn(engineA))
                .as("step 4b: the engine still holds exactly its two").hasSize(2);

            // 5. A DEDICATED record beside them is unaffected: it owns nothing on the
            //    engine and the engine's own instance is still the only one.
            service.insertRecord("dedicatedc", ManagedDatabase.Engine.MONGO, null,
                "userc", "password-c-0001", "dbc", false, ServerService.LOCAL_HOST_NAME,
                ResourceLimits.of(256, null), DatabaseModel.STATUS_PROVISIONING,
                DatabaseModel.PLACEMENT_DEDICATED, null);
            Row dedicated = Models.get(DatabaseModel.class).findByName("dedicatedc");
            assertThat((String) dedicated.get(DatabaseModel.PLACEMENT))
                .as("step 5: an explicit dedicated placement is honoured")
                .isEqualTo(DatabaseModel.PLACEMENT_DEDICATED);
            assertThat((Integer) dedicated.get(DatabaseModel.ENGINE_ID))
                .as("step 5: and names no engine").isNull();
            assertThat((Integer) dedicated.get(DatabaseModel.MEMORY_LIMIT_MB))
                .as("step 5: a dedicated record keeps its own ceilings").isEqualTo(256);
            assertThat(DatabaseEngines.databasesOn(engineA))
                .as("step 5: the shared engine gained nothing from it").hasSize(2);
            assertThat(EngineHost.serving(dedicated).ownerModel())
                .as("step 5: and it is served by its own record, not by the engine")
                .isEqualTo(DatabaseModel.MODEL_ID);
            assertThat(EngineHost.serving(Models.get(DatabaseModel.class)
                    .findByName("shareda")).ownerModel())
                .as("step 5 anchor: while a shared record is served by the engine row")
                .isEqualTo(DatabaseEngineModel.MODEL_ID);

            // 6. A failure quoting the engine's root password or a hosted database's password is stored REDACTED:
            //    the engine's write redacts through its own and its databases' credentials, and a database's
            //    reason through its own and its engine's.
            Row engine = Models.get(DatabaseEngineModel.class).findById(engineA);
            String rootPassword = engine.get(DatabaseEngineModel.ROOT_PASSWORD);
            DatabaseEngines.setStatus(engineA, DatabaseModel.STATUS_FAILED,
                "psql: auth failed for root/" + rootPassword + " creating usera/password-a-0001");
            String stored = Models.get(DatabaseEngineModel.class).findById(engineA)
                .get(DatabaseEngineModel.FAILURE_REASON);
            assertThat(stored).as("step 6: the engine's stored reason keeps its words")
                .contains("psql: auth failed for root/" + KnownValueRedactor.PLACEHOLDER)
                .as("step 6: and loses both passwords").doesNotContain(rootPassword).doesNotContain("password-a-0001");
            int sharedaId = Models.get(DatabaseModel.class).findByName("shareda").get(DatabaseModel.ID);
            assertThat(ConsoleRedaction.redactedReason(DatabaseModel.MODEL_ID, sharedaId,
                    "restore refused for password-a-0001 as root " + rootPassword))
                .as("step 6: a database's reason loses its own password and its engine's")
                .isEqualTo("restore refused for [redacted] as root [redacted]");
            DatabaseEngines.setStatus(engineA, DatabaseModel.STATUS_PROVISIONING, null);
        });
    }

    /**
     * Every refusal that keeps a shared record and the engine it lives on from
     * disagreeing -- each one named, each one BEFORE anything is written.
     */
    @Test
    void everyWayASharedPlacementCanBeWrongIsRefusedByName() {
        DatabaseService service = service();

        // 1. Redis has no logical databases, so a shared placement is refused by name
        //    rather than silently downgraded to dedicated.
        assertThat(refusalOf(() -> service.insertRecord("refuseredis",
                ManagedDatabase.Engine.REDIS, null, "user", "password-0001", "db0", false,
                REFUSAL_HOST, ResourceLimits.none(),
                DatabaseModel.STATUS_PROVISIONING, DatabaseModel.PLACEMENT_SHARED, null)))
            .as("step 1: redis cannot host a logical database")
            .isEqualTo("database_placement_unsupported");

        // 2. A shared record declares no ceilings of its own: the ENGINE is what is
        //    booked and resized, so a per-record limit would describe nothing.
        assertThat(refusalOf(() -> service.insertRecord("refuselimits",
                ManagedDatabase.Engine.MONGO, null, "user", "password-0001", "dbl", false,
                REFUSAL_HOST, ResourceLimits.of(512, null),
                DatabaseModel.STATUS_PROVISIONING, DatabaseModel.PLACEMENT_SHARED, null)))
            .as("step 2: ceilings belong to the engine, not to a logical database")
            .isEqualTo("database_shared_limits");

        // 3. The three values the logical-database SCRIPTS interpolate are refused rather
        //    than escaped for three quoting grammars -- a quote in the password included.
        assertThat(refusalOf(() -> service.insertRecord("refusequote",
                ManagedDatabase.Engine.MONGO, null, "user", "pass'word", "dbq", false,
                REFUSAL_HOST, ResourceLimits.none(),
                DatabaseModel.STATUS_PROVISIONING, DatabaseModel.PLACEMENT_SHARED, null)))
            .as("step 3: a quote in the password cannot ride a shell script")
            .isEqualTo("database_logical_password");
        assertThat(refusalOf(() -> service.insertRecord("refusespace",
                ManagedDatabase.Engine.MONGO, null, "us er", "password-0001", "dbs", false,
                REFUSAL_HOST, ResourceLimits.none(),
                DatabaseModel.STATUS_PROVISIONING, DatabaseModel.PLACEMENT_SHARED, null)))
            .as("step 3: nor a space in the user")
            .isEqualTo("database_logical_identifier");
        // 3b. A NEW password shorter than the minimum is refused by the password's own rule, so no create stores a
        //     value too short for every log to redact; one character more is the boundary and passes the rule.
        String tooShort = "p".repeat(ManagedDatabase.Engine.LOGICAL_PASSWORD_MIN - 1);
        assertThat(refusalOf(() -> service.insertRecord("refuseshort",
                ManagedDatabase.Engine.MONGO, null, "user", tooShort, "dbt", false,
                REFUSAL_HOST, ResourceLimits.none(),
                DatabaseModel.STATUS_PROVISIONING, DatabaseModel.PLACEMENT_SHARED, null)))
            .as("step 3b: a password under the minimum is refused")
            .isEqualTo("database_logical_password");
        assertThat(ManagedDatabase.Engine.isNewLogicalPassword(tooShort + "p"))
            .as("step 3b: the minimum itself passes").isTrue();
        assertThat(ManagedDatabase.Engine.isLogicalIdentifier(tooShort))
            .as("step 3b: while a STORED short password still passes the scripts' own rule").isTrue();

        // 4. An UNKNOWN placement token is refused too: the vocabulary is closed.
        assertThat(refusalOf(() -> service.insertRecord("refuseplacement",
                ManagedDatabase.Engine.MONGO, null, "user", "password-0001", "dbp", false,
                REFUSAL_HOST, ResourceLimits.none(),
                DatabaseModel.STATUS_PROVISIONING, "somewhere-else", null)))
            .as("step 4: a placement nobody declared is refused")
            .isEqualTo("database_placement_unknown");

        // 5. POSITIVE ANCHOR: with all four corrected, the very same create succeeds and
        //    mints the engine -- so the refusals above are about what they name.
        Row ok = service.insertRecord("refuseanchor", ManagedDatabase.Engine.MONGO, null,
            "user", "password-0001", "dbok", false, REFUSAL_HOST, ResourceLimits.none(),
            DatabaseModel.STATUS_PROVISIONING, DatabaseModel.PLACEMENT_SHARED, null);
        int mongoEngineId = ok.get(DatabaseModel.ENGINE_ID);
        assertThat(mongoEngineId).as("step 5: the corrected create is bound to an engine")
            .isPositive();

        Db.run(datasource, () -> {
            // 6. An EXPLICIT engine of another kind is refused: a mysql record may not
            //    live in a mongo process however deliberately it is asked for.
            assertThat(refusalOf(() -> service.insertRecord("refusekind",
                    ManagedDatabase.Engine.MYSQL, null, "user", "password-0001", "dbk", false,
                    REFUSAL_HOST, ResourceLimits.none(),
                    DatabaseModel.STATUS_PROVISIONING, DatabaseModel.PLACEMENT_SHARED,
                    mongoEngineId)))
                .as("step 6: an engine of another kind is refused")
                .isEqualTo("database_engine_kind_mismatch");

            // 7. An explicit engine on ANOTHER HOST is refused: a link network only
            //    exists on the daemon both containers share.
            int otherHost = sshServer("shared-engine-edge");
            Row remote = Models.get(DatabaseEngineModel.class).createEmptyRow();
            remote.set(DatabaseEngineModel.NAME, "mongo-remote");
            remote.set(DatabaseEngineModel.ENGINE, "mongo");
            remote.set(DatabaseEngineModel.ROOT_USER, "root");
            remote.set(DatabaseEngineModel.ROOT_PASSWORD, "rootsecret");
            remote.set(DatabaseEngineModel.SERVER_ID, otherHost);
            remote.set(DatabaseEngineModel.STATUS, DatabaseModel.STATUS_ACTIVE);
            Models.get(DatabaseEngineModel.class).save(remote);
            assertThat(refusalOf(() -> service.insertRecord("refusehost",
                    ManagedDatabase.Engine.MONGO, null, "user", "password-0001", "dbh", false,
                    REFUSAL_HOST, ResourceLimits.none(),
                    DatabaseModel.STATUS_PROVISIONING, DatabaseModel.PLACEMENT_SHARED,
                    (Integer) remote.get(DatabaseEngineModel.ID))))
                .as("step 7: an engine on another host is refused")
                .isEqualTo("database_engine_host_mismatch");

            // 8. A record may not declare an image its engine does not run: blank means
            //    the engine's, anything else must MATCH, so nothing lands on the wrong
            //    major version by accident.
            assertThat(refusalOf(() -> service.insertRecord("refuseimage",
                    ManagedDatabase.Engine.MONGO, "mongo:4.4", "user", "password-0001", "dbi",
                    false, REFUSAL_HOST, ResourceLimits.none(),
                    DatabaseModel.STATUS_PROVISIONING, DatabaseModel.PLACEMENT_SHARED,
                    null)))
                .as("step 8: a differing image is refused")
                .isEqualTo("database_image_engine_mismatch");
            // (Its own user: "user" already lives on this engine as refuseanchor's, and a
            // taken user is a refusal of its own, step 4b of the previous journey.)
            Row matching = service.insertRecord("matchingimage",
                ManagedDatabase.Engine.MONGO, MONGO_DEFAULT_IMAGE,
                "usermatch", "password-0001", "dbm", false, REFUSAL_HOST,
                ResourceLimits.none(), DatabaseModel.STATUS_PROVISIONING,
                DatabaseModel.PLACEMENT_SHARED, null);
            assertThat((Integer) matching.get(DatabaseModel.ENGINE_ID))
                .as("step 8 anchor: the engine's OWN image is accepted")
                .isEqualTo(mongoEngineId);

            // 9. The engine cannot die while anything lives on it, on the DELETE lane the
            //    admin form uses as well as through the service.
            Violations inUse = catchThrowableOfType(() ->
                Models.get(DatabaseEngineModel.class).find()
                    .where(DatabaseEngineModel.ID.eq(mongoEngineId)).delete(),
                Violations.class);
            assertThat((Throwable) inUse)
                .as("step 9: deleting a used engine row is refused").isNotNull();
            assertThat(inUse.all().get(0).message().key())
                .as("step 9: and names the databases holding it")
                .isEqualTo("database_engine_in_use");
            assertThat(Models.get(DatabaseEngineModel.class).findById(mongoEngineId))
                .as("step 9: the engine survives the refused delete").isNotNull();
            assertThatThrownBy(() -> DatabaseEngines.destroy(mongoEngineId, true))
                .as("step 9: the service refuses before it ever asks a daemon")
                .isInstanceOf(Violations.class);

            // 10. And a stored record can never be REPOINTED at an engine of another
            //     kind: the guard judges the write, not only the create funnel.
            Row stored = Models.get(DatabaseModel.class).findByName("refuseanchor");
            Row mysqlEngine = Models.get(DatabaseEngineModel.class).createEmptyRow();
            mysqlEngine.set(DatabaseEngineModel.NAME, "mysql-local");
            mysqlEngine.set(DatabaseEngineModel.ENGINE, "mysql");
            mysqlEngine.set(DatabaseEngineModel.ROOT_USER, "root");
            mysqlEngine.set(DatabaseEngineModel.ROOT_PASSWORD, "rootsecret");
            mysqlEngine.set(DatabaseEngineModel.SERVER_ID, ServerModel.localServerId());
            mysqlEngine.set(DatabaseEngineModel.STATUS, DatabaseModel.STATUS_ACTIVE);
            Models.get(DatabaseEngineModel.class).save(mysqlEngine);
            stored.set(DatabaseModel.ENGINE_ID,
                (Integer) mysqlEngine.get(DatabaseEngineModel.ID));
            Violations repointed = catchThrowableOfType(
                () -> Models.get(DatabaseModel.class).save(stored), Violations.class);
            assertThat((Throwable) repointed)
                .as("step 10: repointing a mongo record at a mysql engine is refused")
                .isNotNull();
            assertThat(repointed.all().get(0).message().key())
                .as("step 10: with the same named refusal")
                .isEqualTo("database_engine_kind_mismatch");
            assertThat((Integer) Models.get(DatabaseModel.class).findByName("refuseanchor")
                    .get(DatabaseModel.ENGINE_ID))
                .as("step 10: and the stored binding is untouched")
                .isEqualTo(mongoEngineId);
        });
    }

    /**
     * The three values a logical-database script interpolates are checked against ONE
     * rule, and it is the rule the generated password always passes.
     */
    @Test
    void aLogicalIdentifierIsExactlyWhatTheScriptsCanCarry() {
        // 1. What the product itself produces and what an operator types: a 24-char
        //    url-safe base64 password and an ordinary database name both pass.
        assertThat(ManagedDatabase.Engine.isLogicalIdentifier("kJ8-vQ2mZx_LpR4tN7wYbG1s"))
            .as("step 1: a generated url-safe base64 password passes").isTrue();
        assertThat(ManagedDatabase.Engine.isLogicalIdentifier("oogfonds-staging"))
            .as("step 1: and an ordinary hyphenated name").isTrue();
        for (String allowed : List.of("a", "A1", "app.db", "app_db", "app-db", "0")) {
            assertThat(ManagedDatabase.Engine.isLogicalIdentifier(allowed))
                .as("step 1: '%s' is spellable in every one of the three grammars", allowed)
                .isTrue();
        }

        // 2. Absence, length and every character that changes what a shell or a SQL /
        //    JavaScript body MEANS are refused.
        for (String refused : List.of("", "a b", "a'b", "a$b", "a\"b", "a;b", "a`b",
                "a\\b", "a/b", "a\nb")) {
            assertThat(ManagedDatabase.Engine.isLogicalIdentifier(refused))
                .as("step 2: '%s' is not a logical identifier", refused).isFalse();
        }
        assertThat(ManagedDatabase.Engine.isLogicalIdentifier(null))
            .as("step 2: and neither is an absent value").isFalse();
        assertThat(ManagedDatabase.Engine.isLogicalIdentifier("a".repeat(64)))
            .as("step 2: 64 characters is the boundary and passes").isTrue();
        assertThat(ManagedDatabase.Engine.isLogicalIdentifier("a".repeat(65)))
            .as("step 2: 65 does not").isFalse();
    }

    /**
     * The logical-database commands: the shape that keeps a password out of argv, the
     * refusals of an engine that cannot share and of a value that could break out of the
     * script, and the restore scoped to ONE database on a shared engine.
     */
    @Test
    void theLogicalDatabaseCommandsCarryNoSecretInArgvAndRefuseWhatTheyCannotSpell() {
        ManagedDatabase.Engine mongo = ManagedDatabase.Engine.MONGO;
        String password = "kJ8-vQ2mZx_LpR4tN7wYbG1s";

        // 1. Create: an `sh -c` body whose positional arguments are the port, the root
        //    user, the database and the new user -- and nothing else.
        List<String> create = mongo.createLogicalCommand("root", "tenantdb", "tenantuser");
        assertThat(create.get(0)).as("step 1: the command is a shell body").isEqualTo("sh");
        assertThat(create.get(1)).isEqualTo("-c");
        assertThat(create.get(3))
            .as("step 1: $0 is a recognisable script name, never a value")
            .isEqualTo("hohenheim-create");
        assertThat(create.subList(4, create.size()))
            .as("step 1: the positional arguments are port, root user, database, user")
            .containsExactly(String.valueOf(mongo.port()), "root", "tenantdb", "tenantuser");

        // 2. THE SECRET NEVER RIDES THE COMMAND. Both passwords reach the script through the
        //    environment; that the expanded scripts keep the new one out of every client's argv
        //    too is the next test's, which runs them.
        List<String> env = mongo.logicalEnv("rootsecret", password);
        assertThat(create).as("step 2: no argument carries the new user's password")
            .noneMatch(argument -> argument.contains(password));
        assertThat(create).as("step 2: nor the root password")
            .noneMatch(argument -> argument.contains("rootsecret"));
        assertThat(env).as("step 2: both ride the environment instead")
            .contains(ManagedDatabase.Engine.MONGO_PROBE_PASSWORD + "=rootsecret")
            .contains(ManagedDatabase.Engine.LOGICAL_PASSWORD + "=" + password);
        assertThat(mongo.logicalEnv("rootsecret", null))
            .as("step 2: a command with no new user carries only the root password")
            .containsExactly(ManagedDatabase.Engine.MONGO_PROBE_PASSWORD + "=rootsecret");

        // 3. Drop and fingerprint take the same shape; the drop's last argument is the
        //    explicit data decision, so "revoke access" and "remove the bytes" can never
        //    be the same call by accident.
        assertThat(mongo.dropLogicalCommand("root", "tenantdb", "tenantuser", true))
            .as("step 3: dropping the data says so explicitly")
            .endsWith("tenantdb", "tenantuser", "1");
        assertThat(mongo.dropLogicalCommand("root", "tenantdb", "tenantuser", false))
            .as("step 3: and keeping it says so too")
            .endsWith("tenantdb", "tenantuser", "0");
        assertThat(mongo.fingerprintCommand("root", "tenantdb"))
            .as("step 3: the fingerprint needs no user at all")
            .endsWith(String.valueOf(mongo.port()), "root", "tenantdb");

        // 4. Every engine that CAN share answers all three; redis refuses all three by
        //    name rather than producing a command that would do the wrong thing.
        for (ManagedDatabase.Engine engine : ManagedDatabase.Engine.values()) {
            if (engine.supportsLogicalDatabases()) {
                assertThat(engine.createLogicalCommand("root", "tenantdb", "tenantuser"))
                    .as("step 4 (%s): has a create command", engine).isNotEmpty();
                assertThat(engine.fingerprintCommand("root", "tenantdb"))
                    .as("step 4 (%s): and a fingerprint command", engine).isNotEmpty();
                continue;
            }
            assertThatThrownBy(() -> engine.createLogicalCommand("root", "tenantdb", "u"))
                .as("step 4 (%s): refuses to create a logical database", engine)
                .isInstanceOf(UnsupportedOperationException.class);
            assertThatThrownBy(() -> engine.dropLogicalCommand("root", "tenantdb", "u", true))
                .as("step 4 (%s): refuses to drop one", engine)
                .isInstanceOf(UnsupportedOperationException.class);
            assertThatThrownBy(() -> engine.fingerprintCommand("root", "tenantdb"))
                .as("step 4 (%s): and refuses to fingerprint one", engine)
                .isInstanceOf(UnsupportedOperationException.class);
        }

        // 5. A value that could break out of the script is refused at command BUILD time
        //    as well, not only at the create form -- the script is never handed a quote.
        assertThatThrownBy(() -> mongo.createLogicalCommand("root", "tenant'db", "tenantuser"))
            .as("step 5: a quoted database name never reaches a shell body")
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("logical-database identifier");
        assertThatThrownBy(() -> mongo.dropLogicalCommand("root", "tenantdb", "tenant'user",
                true))
            .as("step 5: nor a quoted user")
            .isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * The credential scripts as a shell really expands them, against stub clients that record their argv and stdin:
     * every NEW password reaches psql, mysql, mongosh and redis-cli as data on stdin and never as an argument, and no
     * root password rides an argument either (mongo's authenticates inside the script it reads), so neither shows in
     * an engine container's process table. The scripts run with an empty stdin of their own, as a docker exec gives
     * them. Live proof that the real clients take them is SharedDatabaseEngineLiveTest (create) and
     * DedicatedDatabasePasswordLiveTest (rotation).
     */
    @Test
    void theCredentialScriptsHandEveryPasswordToTheirClientOnStdinNeverInItsArgv(@TempDir Path dir) throws Exception {
        String password = "kJ8-vQ2mZx_LpR4tN7wYbG1s";
        String root = "root-secret-0001";
        Path bin = Files.createDirectories(dir.resolve("bin"));
        String stub = "#!/bin/sh\n"
            + "printf '%s\\n' \"${0##*/} $*\" >> \"$HH_STUB_LOG/argv\"\n"
            + "input=$(cat)\n"
            + "printf '%s\\n' \"$input\" >> \"$HH_STUB_LOG/stdin\"\n"
            + "case \"$*\" in *'SELECT COUNT'*) echo 0 ;; esac\n"
            + "case \"$input\" in *'CONFIG SET'*) echo OK ;; *hohenheim*) echo hohenheim-ok ;; esac\n";
        for (String client : List.of("psql", "mysql", "mongosh", "redis-cli")) {
            Path file = bin.resolve(client);
            Files.writeString(file, stub);
            assertThat(file.toFile().setExecutable(true)).as("anchor: the %s stub is executable", client).isTrue();
        }
        Map<ManagedDatabase.Engine, String> clients = Map.of(ManagedDatabase.Engine.POSTGRES, "psql",
            ManagedDatabase.Engine.MYSQL, "mysql", ManagedDatabase.Engine.MONGO, "mongosh",
            ManagedDatabase.Engine.REDIS, "redis-cli");

        // 1. A shared engine's create script: the new user's password on stdin, the root's in the environment.
        for (ManagedDatabase.Engine engine : List.of(ManagedDatabase.Engine.POSTGRES, ManagedDatabase.Engine.MYSQL,
                ManagedDatabase.Engine.MONGO)) {
            Path log = Files.createDirectories(dir.resolve("create-" + engine.token()));
            runStubbed(bin, log, engine.createLogicalCommand("root", "tenantdb", "tenantuser"),
                engine.logicalEnv(root, password), "step 1 (" + engine + ")");
            String argv = Files.readString(log.resolve("argv"));
            assertThat(argv).as("step 1 (%s): through its client", engine).contains(clients.get(engine) + " ")
                .as("step 1 (%s): no client was handed the new password as an argument", engine)
                .doesNotContain(password)
                .as("step 1 (%s): nor the root password", engine).doesNotContain(root);
            assertThat(Files.readString(log.resolve("stdin")))
                .as("step 1 (%s): the client read the new one as data on stdin instead", engine).contains(password);
        }

        // 2. A dedicated engine's own rotation, every engine: the same discipline, the current password (the
        //    engine's root) in the environment and the new one on stdin.
        for (ManagedDatabase.Engine engine : ManagedDatabase.Engine.values()) {
            Path log = Files.createDirectories(dir.resolve("rotate-" + engine.token()));
            List<String> command = engine.rotateOwnCommand("appuser");
            List<String> env = engine.rotateEnv(root, password);
            assertThat(String.join(" ", command)).as("step 2 (%s): the command carries neither password", engine)
                .doesNotContain(password).doesNotContain(root);
            assertThat(env).as("step 2 (%s): the new password rides the environment", engine)
                .contains(ManagedDatabase.Engine.LOGICAL_PASSWORD + "=" + password);
            runStubbed(bin, log, command, env, "step 2 (" + engine + ")");
            String argv = Files.readString(log.resolve("argv"));
            assertThat(argv).as("step 2 (%s): through its client", engine).contains(clients.get(engine) + " ")
                .as("step 2 (%s): no client was handed the new password as an argument", engine)
                .doesNotContain(password)
                .as("step 2 (%s): nor the current one", engine).doesNotContain(root);
            assertThat(Files.readString(log.resolve("stdin")))
                .as("step 2 (%s): the client read the new one on stdin", engine).contains(password);
        }
        String mysqlRotation = String.join(" ", ManagedDatabase.Engine.MYSQL.rotateOwnCommand("appuser"));
        assertThat(mysqlRotation).as("step 2: mysql changes the record's user and root, at localhost and %")
            .contains("ALTER USER '$1'@'%'").contains("'root'@'localhost'").contains("'root'@'%'");
        assertThatThrownBy(() -> ManagedDatabase.Engine.POSTGRES.rotateOwnCommand("app'user"))
            .as("step 2: a user the scripts cannot spell never reaches a shell body")
            .isInstanceOf(IllegalArgumentException.class);
        assertThat(ManagedDatabase.Engine.REDIS.rotateOwnCommand("anything"))
            .as("step 2: redis has one password and no user").endsWith("hohenheim-rotate", "6379");

        // 3. Every mongo script run as the engine root keeps the root password out of every argv: the readiness
        //    probe, the drop and the fingerprint authenticate inside the script they read, as the create does.
        Map<String, List<String>> mongoRootScripts = Map.of(
            "ready", ManagedDatabase.Engine.MONGO.readyCommand("root", root, "admin"),
            "drop", ManagedDatabase.Engine.MONGO.dropLogicalCommand("root", "tenantdb", "tenantuser", true),
            "fingerprint", ManagedDatabase.Engine.MONGO.fingerprintCommand("root", "tenantdb"));
        for (Map.Entry<String, List<String>> script : mongoRootScripts.entrySet()) {
            Path log = Files.createDirectories(dir.resolve("mongo-" + script.getKey()));
            runStubbed(bin, log, script.getValue(), ManagedDatabase.Engine.MONGO.logicalEnv(root, null),
                "step 3 (" + script.getKey() + ")");
            assertThat(Files.readString(log.resolve("argv")))
                .as("step 3 (%s): mongosh ran", script.getKey()).contains("mongosh --host 127.0.0.1")
                .as("step 3 (%s): without the root password in its argv", script.getKey()).doesNotContain(root)
                .doesNotContain("--password");
            assertThat(Files.readString(log.resolve("stdin")))
                .as("step 3 (%s): the script authenticates as root inside it", script.getKey())
                .contains("auth('root'");
        }
    }

    /** Runs {@code command} with only the stub clients on its PATH, logging their argv and stdin under {@code log}. */
    private static void runStubbed(Path bin, Path log, List<String> command, List<String> env, String step)
            throws Exception {
        ProcessOutcome outcome = runShell(bin + ":/usr/local/bin:/usr/bin:/bin", log, null, command, env, Map.of());
        assertThat(outcome.exitCode()).as("%s: the script ran to its end: %s", step, outcome.describe()).isZero();
    }

    /**
     * The private directory a credential script writes a password file into is gone however the script ends: the
     * legacy mongo shell's root password file and mongorestore's config file after a failing client and after the
     * script was terminated by a signal mid-run, and mongosh (which reads the password from its environment) never
     * makes one. The redis container command hands redis-server its password in a config file, quoted, never as an
     * argument, past the redis 6 entrypoint's argument rewrite, and leaves no path to the file behind.
     */
    @Test
    void aCredentialScriptLeavesNoPasswordFileBehindAndRedisReadsItsPasswordOnStdin(@TempDir Path dir)
            throws Exception {
        String root = "root-secret-0001";
        Path tmp = Files.createDirectories(dir.resolve("tmp"));
        // The stub records whether a password file existed while it ran, then fails, or terminates the script's own
        // shell (the topmost ancestor naming the script) and exits, as a signal from outside would.
        String stub = "#!/bin/sh\n"
            + "printf '%s\\n' \"${0##*/} $*\" >> \"$HH_STUB_LOG/argv\"\n"
            + "cat >> \"$HH_STUB_LOG/stdin\"\n"
            + "ls \"$TMPDIR\"/*/* >> \"$HH_STUB_LOG/files\" 2>/dev/null\n"
            + "case \"$HH_STUB_MODE\" in\n"
            + "  signal)\n"
            + "    p=$PPID; top=\n"
            + "    while [ -n \"$p\" ] && [ \"$p\" -gt 1 ]; do\n"
            + "      if tr '\\0' ' ' < /proc/$p/cmdline | grep -q \"$HH_STUB_SCRIPT\"; then top=$p; fi\n"
            + "      p=$(sed -n 's/^PPid:[[:space:]]*//p' /proc/$p/status)\n"
            + "    done\n"
            + "    kill -TERM \"$top\"; exit 0 ;;\n"
            + "  *) exit 1 ;;\n"
            + "esac\n";
        Path legacy = Files.createDirectories(dir.resolve("legacy"));
        Path modern = Files.createDirectories(dir.resolve("modern"));
        for (Path client : List.of(legacy.resolve("mongo"), legacy.resolve("mongorestore"), modern.resolve("mongosh"))) {
            Files.writeString(client, stub);
            assertThat(client.toFile().setExecutable(true)).as("anchor: the %s stub is executable", client).isTrue();
        }
        // Only the stubs and the plain tools the scripts use: a host with a real mongosh on its PATH would otherwise
        // answer the legacy-shell journeys with it.
        Path tools = Files.createDirectories(dir.resolve("tools"));
        for (String tool : List.of("sh", "bash", "mktemp", "rm", "sed", "cat", "ls", "tr", "grep", "head", "id")) {
            Path found = Files.exists(Path.of("/usr/bin", tool)) ? Path.of("/usr/bin", tool) : Path.of("/bin", tool);
            Files.createSymbolicLink(tools.resolve(tool), found);
        }
        ManagedDatabase.Engine mongo = ManagedDatabase.Engine.MONGO;
        Map<String, List<String>> scripts = Map.of(
            "hohenheim-rotate", mongo.rotateOwnCommand("appuser"),
            "hohenheim-restore", mongo.restoreFromStdinCommand("appuser", "appdb", 0, null));
        Map<String, List<String>> envs = Map.of(
            "hohenheim-rotate", mongo.rotateEnv(root, "New.password-0000000001"),
            "hohenheim-restore", mongo.restoreEnv(root));

        // 1-2. The legacy shell's root file and mongorestore's config file: written, and gone after a failing client
        //      (step 1) and after the script's shell was terminated while the client ran (step 2).
        for (String mode : List.of("fail", "signal")) {
            for (Map.Entry<String, List<String>> script : scripts.entrySet()) {
                String step = (mode.equals("fail") ? "step 1 (" : "step 2 (") + script.getKey() + ")";
                Path log = Files.createDirectories(dir.resolve(mode + "-" + script.getKey()));
                ProcessOutcome outcome = runShell(legacy + ":" + tools, log, tmp, script.getValue(),
                    envs.get(script.getKey()),
                    Map.of("HH_STUB_MODE", mode, "HH_STUB_SCRIPT", script.getKey()));
                assertThat(Files.readString(log.resolve("files")))
                    .as("%s anchor: the client ran while the password file existed", step)
                    .containsAnyOf("/root", "/auth.yaml");
                assertThat(outcome.exitCode()).as("%s: the script failed: %s", step, outcome.describe())
                    .isEqualTo(mode.equals("fail") ? 1 : 143);
                try (var left = Files.list(tmp)) {
                    assertThat(left.toList()).as("%s: and its private directory is gone", step).isEmpty();
                }
            }
        }

        // 3. mongosh reads the root password from its environment: no file is written at all.
        Path log = Files.createDirectories(dir.resolve("mongosh"));
        runShell(modern + ":" + tools, log, tmp, scripts.get("hohenheim-rotate"), envs.get("hohenheim-rotate"),
            Map.of("HH_STUB_MODE", "fail", "HH_STUB_SCRIPT", "hohenheim-rotate"));
        assertThat(Files.readString(log.resolve("argv"))).as("step 3 anchor: mongosh ran").contains("mongosh ");
        assertThat(Files.exists(log.resolve("files")) ? Files.readString(log.resolve("files")) : "")
            .as("step 3: no password file existed while it ran").isEmpty();
        try (var left = Files.list(tmp)) {
            assertThat(left.toList()).as("step 3: nor any directory for one").isEmpty();
        }

        // 4. Redis: the container command spells no password; its shell hands redis-server the password from the
        //    container's environment as a quoted config line, a backslash and a double quote escaped, in a file it
        //    reads as /dev/stdin, through the image's entrypoint when there is one. The entrypoint stub rewrites its
        //    arguments the way redis 6's does (protected-mode inserted before them unless the second argument is a
        //    regular file naming it), and the stub server reads its first argument as its config, as redis does.
        String password = "Redis\"pass\\word-0000000001";
        List<String> command = ManagedDatabase.Engine.REDIS.containerCommand();
        assertThat(command).as("step 4: redis declares its own command").isNotNull();
        Map<String, String> secret = ManagedDatabase.Engine.REDIS.secretEnv(password);
        assertThat(String.join(" ", command)).as("step 4: the command carries no password").doesNotContain(password);
        Path redisBin = Files.createDirectories(dir.resolve("redis"));
        Files.writeString(redisBin.resolve("redis-server"), "#!/bin/sh\n"
            + "printf '%s\\n' \"${0##*/} $*\" >> \"$HH_STUB_LOG/argv\"\n"
            + "case \"$1\" in -*) cat >> \"$HH_STUB_LOG/stdin\" ;; *) cat \"$1\" >> \"$HH_STUB_LOG/stdin\" ;; esac\n"
            + "ls -A \"$TMPDIR\" >> \"$HH_STUB_LOG/files\"\n");
        Files.writeString(redisBin.resolve("docker-entrypoint.sh"), "#!/bin/sh\n"
            + "printf '%s\\n' \"${0##*/} $*\" >> \"$HH_STUB_LOG/argv\"\n"
            + "if [ \"$1\" = redis-server ] && ! { [ -f \"$2\" ] && grep -q '^protected-mode' \"$2\"; }; then\n"
            + "  shift; set -- redis-server --protected-mode no \"$@\"\n"
            + "fi\n"
            + "exec \"$@\"\n");
        for (String file : List.of("redis-server", "docker-entrypoint.sh")) {
            assertThat(redisBin.resolve(file).toFile().setExecutable(true)).as("anchor: %s is executable", file)
                .isTrue();
        }
        Path redisLog = Files.createDirectories(dir.resolve("redis-log"));
        ProcessOutcome started = runShell(redisBin + ":" + tools, redisLog, tmp, command,
            secret.entrySet().stream().map(entry -> entry.getKey() + "=" + entry.getValue()).toList(), Map.of());
        assertThat(started.exitCode()).as("step 4: the command ran: %s", started.describe()).isZero();
        assertThat(Files.readString(redisLog.resolve("argv")).lines().toList())
            .as("step 4: the entrypoint started redis-server on its config file unrewritten, no password in argv")
            .containsExactly("docker-entrypoint.sh redis-server /dev/stdin", "redis-server /dev/stdin");
        assertThat(Files.readString(redisLog.resolve("stdin")))
            .as("step 4: the config redis-server read names protected-mode and the password, quoted and escaped")
            .isEqualTo("protected-mode no\nrequirepass \"Redis\\\"pass\\\\word-0000000001\"\n");
        assertThat(Files.readString(redisLog.resolve("files")))
            .as("step 4: no path to the config file existed while the server ran").isEmpty();
        try (var left = Files.list(tmp)) {
            assertThat(left.toList()).as("step 4: nor after it").isEmpty();
        }
    }

    /**
     * Runs {@code command} with {@code path} as its PATH (and {@code tmp}, when given, as its TMPDIR), logging the
     * stubs' argv and stdin under {@code log}.
     */
    private static ProcessOutcome runShell(String path, Path log, @Nullable Path tmp, List<String> command,
                                           List<String> env, Map<String, String> extra) throws Exception {
        Subprocess script = Subprocess.of(command)
            .withoutInheritedEnvironment()
            .environment("PATH", path)
            .environment("HH_STUB_LOG", log.toString())
            .stdin(new byte[0])
            .timeout(Duration.ofSeconds(30));
        if (tmp != null) {
            script.environment("TMPDIR", tmp.toString());
        }
        for (String variable : env) {
            int eq = variable.indexOf('=');
            script.environment(variable.substring(0, eq), variable.substring(eq + 1));
        }
        extra.forEach(script::environment);
        return script.run();
    }

    /**
     * TENANT ISOLATION on a shared engine, as the commands spell it: Postgres revokes
     * PUBLIC's connect (on create and engine-wide as the repair), MySQL grants on the ESCAPED
     * database pattern ({@code _} is a GRANT wildcard) and revokes a wildcard grant left from
     * before, dumps carry no owner, a shared restore runs under the record's own role, and
     * the ownership check exists wherever objects have owners. Live proof is
     * {@code SharedDatabaseEngineLiveTest}; this pins the spelling daemon-free.
     */
    @Test
    void sharedEngineCommandsIsolateTenantsAndKeepOwnershipWithTheRecord() {
        ManagedDatabase.Engine postgres = ManagedDatabase.Engine.POSTGRES;
        ManagedDatabase.Engine mysql = ManagedDatabase.Engine.MYSQL;
        ManagedDatabase.Engine mongo = ManagedDatabase.Engine.MONGO;

        // 1. Postgres: a new logical database is closed to PUBLIC the moment it exists, and
        //    the repair command closes every database of the engine (existing ones too).
        assertThat(String.join(" ", postgres.createLogicalCommand("root", "shop_db", "shop_user")))
            .as("step 1: the create script revokes PUBLIC on the new database")
            .contains("REVOKE ALL ON DATABASE");
        List<String> pgIsolate = postgres.isolationCommand("root", "shop_db", "shop_user");
        assertThat(pgIsolate)
            .as("step 1: the repair is a shell body run as root")
            .startsWith("sh", "-c");
        assertThat(pgIsolate.get(2))
            .as("step 1: engine-wide, template0 and the maintenance database aside")
            .contains("REVOKE ALL ON DATABASE %I FROM PUBLIC")
            .contains("datname NOT IN ('template0', 'postgres')")
            .contains("REVOKE CREATE ON SCHEMA public FROM PUBLIC");

        // 2. MySQL: the grant names the ESCAPED pattern, and the create/repair script revokes
        //    a grant on the unescaped (wildcard) spelling first.
        List<String> myCreate = mysql.createLogicalCommand("root", "shop_db", "shop_user");
        assertThat(myCreate)
            .as("step 2: the escaped grant pattern is its own positional argument")
            .endsWith("root", "shop_db", "shop_user", "shop\\_db");
        assertThat(myCreate.get(2))
            .as("step 2: the grant targets the pattern, the revoke the old wildcard spelling")
            .contains("GRANT ALL PRIVILEGES ON \\`$4\\`.*")
            .contains("REVOKE ALL PRIVILEGES ON \\`$2\\`.*");
        assertThat(mysql.isolationCommand("root", "plain", "u1"))
            .as("step 2: a name without underscore grants on itself")
            .endsWith("root", "plain", "u1", "plain");

        // 3. MySQL's fingerprint never pastes a table name into the shell: the statements
        //    are built in SQL with backtick-quoted identifiers and piped as data.
        String fingerprint = mysql.fingerprintCommand("root", "shop_db").get(2);
        assertThat(fingerprint)
            .as("step 3: no shell loop over tenant-controlled table names")
            .doesNotContain("for t in")
            .contains("REPLACE(table_name");

        // 4. Mongo needs no isolation repair and has no object owner.
        assertThat(mongo.isolationCommand("root", "shop_db", "shop_user"))
            .as("step 4: a mongo user lives in its own database; nothing to repair").isNull();
        assertThat(mongo.foreignOwnershipCommand("root", "shop_db", "shop_user"))
            .as("step 4: and nothing to own").isNull();
        assertThat(postgres.foreignOwnershipCommand("root", "shop_db", "shop_user"))
            .as("step 4: postgres and mysql both answer the ownership question")
            .isNotNull();
        assertThat(mysql.foreignOwnershipCommand("root", "shop_db", "shop_user")).isNotNull();

        // 5. The redis engine cannot share and refuses the two new commands too.
        assertThatThrownBy(() -> ManagedDatabase.Engine.REDIS.isolationCommand("root", "d", "u"))
            .as("step 5: redis has no logical databases to isolate")
            .isInstanceOf(UnsupportedOperationException.class);

        // 6. A tenant's namespaced name can reach 42 characters and MySQL refuses a user name
        //    over 32: the derived user fits, stays deterministic, and a short name is kept.
        String longName = "o1a2b3c4d-" + "l".repeat(32);
        String user = TenantDatabases.userNameFor(longName);
        assertThat(user.length())
            .as("step 6: the derived user fits MySQL's limit")
            .isLessThanOrEqualTo(TenantDatabases.MAX_USER_LENGTH);
        assertThat(TenantDatabases.userNameFor(longName))
            .as("step 6: and is the same every time").isEqualTo(user);
        assertThat(TenantDatabases.userNameFor(longName.substring(0, 41) + "m"))
            .as("step 6: two long names differing in their tail still differ").isNotEqualTo(user);
        assertThat(ManagedDatabase.Engine.isLogicalIdentifier(user))
            .as("step 6: and it is still a logical identifier").isTrue();
        assertThat(TenantDatabases.userNameFor("u7-blog"))
            .as("step 6: a short name is used unchanged").isEqualTo("u7_blog");
    }

    // -- helpers ------------------------------------------------------------------

    /** The Microcopy key of the refusal a body raises; fails when it raises none. */
    private static String refusalOf(Runnable body) {
        Violations refused = catchThrowableOfType(body::run, Violations.class);
        assertThat((Throwable) refused).as("the call must be refused").isNotNull();
        return refused.all().get(0).message().key();
    }

    /** A second host record, so "another host" is a real row and not a fiction. */
    private static int sshServer(String name) {
        Row row = Models.get(ServerModel.class).findByName(name);
        if (row == null) {
            row = Models.get(ServerModel.class).createEmptyRow();
            row.set(ServerModel.NAME, name);
            row.set(ServerModel.MODE, ServerModel.MODE_SSH);
            Models.get(ServerModel.class).save(row);
        }
        return row.get(ServerModel.ID);
    }
}
