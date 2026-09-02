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
import be.elevenways.hohenheim.server.docker.ResourceLimits;
import be.elevenways.hohenheim.server.docker.ServerService;
import be.elevenways.hohenheim.server.instance.InstanceCapacity;
import be.elevenways.hohenheim.test.HohenheimTestRuntime;
import be.elevenways.hohenheim.test.TestDatabases;
import be.elevenways.hohenheim.test.host.HostFixtures;
import be.elevenways.zenit.common.orm.datasource.Db;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.datasource.sql.SqlDatasource;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.validation.Violations;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

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
            "usera", "passworda", "dba", false, ServerService.LOCAL, ResourceLimits.none(),
            DatabaseService.STATUS_PROVISIONING);
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
                .isNotEqualTo("passworda");
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
            "userb", "passwordb", "dbb", false, ServerService.LOCAL, ResourceLimits.none(),
            DatabaseService.STATUS_PROVISIONING);
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
                    ManagedDatabase.Engine.MONGO, null, "userc", "passwordc", "dba", false,
                    ServerService.LOCAL, ResourceLimits.none(),
                    DatabaseService.STATUS_PROVISIONING)))
                .as("step 4b: a taken logical database name is refused by name")
                .isEqualTo("database_logical_name_taken");
            assertThat(refusalOf(() -> service.insertRecord("sharedc",
                    ManagedDatabase.Engine.MONGO, null, "usera", "passwordc", "dbc", false,
                    ServerService.LOCAL, ResourceLimits.none(),
                    DatabaseService.STATUS_PROVISIONING)))
                .as("step 4b: a taken logical user is refused by name")
                .isEqualTo("database_logical_user_taken");
            assertThat(Models.get(DatabaseModel.class).findByName("sharedc"))
                .as("step 4b: and neither refusal left a record behind").isNull();
            assertThat(DatabaseEngines.databasesOn(engineA))
                .as("step 4b: the engine still holds exactly its two").hasSize(2);

            // 5. A DEDICATED record beside them is unaffected: it owns nothing on the
            //    engine and the engine's own instance is still the only one.
            service.insertRecord("dedicatedc", ManagedDatabase.Engine.MONGO, null,
                "userc", "passwordc", "dbc", false, ServerService.LOCAL,
                ResourceLimits.of(256, null), DatabaseService.STATUS_PROVISIONING,
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
                ManagedDatabase.Engine.REDIS, null, "user", "password", "db0", false,
                REFUSAL_HOST, ResourceLimits.none(),
                DatabaseService.STATUS_PROVISIONING, DatabaseModel.PLACEMENT_SHARED, null)))
            .as("step 1: redis cannot host a logical database")
            .isEqualTo("database_placement_unsupported");

        // 2. A shared record declares no ceilings of its own: the ENGINE is what is
        //    booked and resized, so a per-record limit would describe nothing.
        assertThat(refusalOf(() -> service.insertRecord("refuselimits",
                ManagedDatabase.Engine.MONGO, null, "user", "password", "dbl", false,
                REFUSAL_HOST, ResourceLimits.of(512, null),
                DatabaseService.STATUS_PROVISIONING, DatabaseModel.PLACEMENT_SHARED, null)))
            .as("step 2: ceilings belong to the engine, not to a logical database")
            .isEqualTo("database_shared_limits");

        // 3. The three values the logical-database SCRIPTS interpolate are refused rather
        //    than escaped for three quoting grammars -- a quote in the password included.
        assertThat(refusalOf(() -> service.insertRecord("refusequote",
                ManagedDatabase.Engine.MONGO, null, "user", "pass'word", "dbq", false,
                REFUSAL_HOST, ResourceLimits.none(),
                DatabaseService.STATUS_PROVISIONING, DatabaseModel.PLACEMENT_SHARED, null)))
            .as("step 3: a quote in the password cannot ride a shell script")
            .isEqualTo("database_logical_identifier");
        assertThat(refusalOf(() -> service.insertRecord("refusespace",
                ManagedDatabase.Engine.MONGO, null, "us er", "password", "dbs", false,
                REFUSAL_HOST, ResourceLimits.none(),
                DatabaseService.STATUS_PROVISIONING, DatabaseModel.PLACEMENT_SHARED, null)))
            .as("step 3: nor a space in the user")
            .isEqualTo("database_logical_identifier");

        // 4. An UNKNOWN placement token is refused too: the vocabulary is closed.
        assertThat(refusalOf(() -> service.insertRecord("refuseplacement",
                ManagedDatabase.Engine.MONGO, null, "user", "password", "dbp", false,
                REFUSAL_HOST, ResourceLimits.none(),
                DatabaseService.STATUS_PROVISIONING, "somewhere-else", null)))
            .as("step 4: a placement nobody declared is refused")
            .isEqualTo("database_placement_unknown");

        // 5. POSITIVE ANCHOR: with all four corrected, the very same create succeeds and
        //    mints the engine -- so the refusals above are about what they name.
        Row ok = service.insertRecord("refuseanchor", ManagedDatabase.Engine.MONGO, null,
            "user", "password", "dbok", false, REFUSAL_HOST, ResourceLimits.none(),
            DatabaseService.STATUS_PROVISIONING, DatabaseModel.PLACEMENT_SHARED, null);
        int mongoEngineId = ok.get(DatabaseModel.ENGINE_ID);
        assertThat(mongoEngineId).as("step 5: the corrected create is bound to an engine")
            .isPositive();

        Db.run(datasource, () -> {
            // 6. An EXPLICIT engine of another kind is refused: a mysql record may not
            //    live in a mongo process however deliberately it is asked for.
            assertThat(refusalOf(() -> service.insertRecord("refusekind",
                    ManagedDatabase.Engine.MYSQL, null, "user", "password", "dbk", false,
                    REFUSAL_HOST, ResourceLimits.none(),
                    DatabaseService.STATUS_PROVISIONING, DatabaseModel.PLACEMENT_SHARED,
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
                    ManagedDatabase.Engine.MONGO, null, "user", "password", "dbh", false,
                    REFUSAL_HOST, ResourceLimits.none(),
                    DatabaseService.STATUS_PROVISIONING, DatabaseModel.PLACEMENT_SHARED,
                    (Integer) remote.get(DatabaseEngineModel.ID))))
                .as("step 7: an engine on another host is refused")
                .isEqualTo("database_engine_host_mismatch");

            // 8. A record may not declare an image its engine does not run: blank means
            //    the engine's, anything else must MATCH, so nothing lands on the wrong
            //    major version by accident.
            assertThat(refusalOf(() -> service.insertRecord("refuseimage",
                    ManagedDatabase.Engine.MONGO, "mongo:4.4", "user", "password", "dbi",
                    false, REFUSAL_HOST, ResourceLimits.none(),
                    DatabaseService.STATUS_PROVISIONING, DatabaseModel.PLACEMENT_SHARED,
                    null)))
                .as("step 8: a differing image is refused")
                .isEqualTo("database_image_engine_mismatch");
            // (Its own user: "user" already lives on this engine as refuseanchor's, and a
            // taken user is a refusal of its own, step 4b of the previous journey.)
            Row matching = service.insertRecord("matchingimage",
                ManagedDatabase.Engine.MONGO, MONGO_DEFAULT_IMAGE,
                "usermatch", "password", "dbm", false, REFUSAL_HOST,
                ResourceLimits.none(), DatabaseService.STATUS_PROVISIONING,
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

        // 2. THE SECRET NEVER RIDES ARGV. Both passwords reach the script through the
        //    environment, which is what keeps them out of a container's process table.
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
