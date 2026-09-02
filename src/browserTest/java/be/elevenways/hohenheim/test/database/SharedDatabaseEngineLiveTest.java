package be.elevenways.hohenheim.test.database;

import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.hohenheim.model.DatabaseEngineModel;
import be.elevenways.hohenheim.model.DatabaseModel;
import be.elevenways.hohenheim.model.InstanceDatabaseModel;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.server.ControllerScope;
import be.elevenways.hohenheim.server.database.DatabaseEngines;
import be.elevenways.hohenheim.server.database.DatabaseInstances;
import be.elevenways.hohenheim.server.database.DatabaseService;
import be.elevenways.hohenheim.server.database.EngineHost;
import be.elevenways.hohenheim.server.database.ManagedDatabase;
import be.elevenways.hohenheim.server.docker.DockerClient;
import be.elevenways.hohenheim.server.docker.ResourceLimits;
import be.elevenways.hohenheim.server.docker.ServerService;
import be.elevenways.hohenheim.server.instance.InstanceService;
import be.elevenways.hohenheim.test.HohenheimTestRuntime;
import be.elevenways.hohenheim.test.host.HostFixtures;
import be.elevenways.hohenheim.test.live.LiveLane;
import be.elevenways.hohenheim.test.network.PrivateNetns;
import be.elevenways.zenit.common.orm.datasource.Datasources;
import be.elevenways.zenit.common.orm.datasource.Db;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.validation.Violations;
import be.elevenways.zenit.server.orm.SqliteDatasource;
import be.elevenways.zenit.server.orm.migration.MigrationRunner;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * Shared database engines against a REAL daemon: one engine process holding several
 * managed databases, each with its OWN user that reaches its own logical database and
 * NOTHING else, backed up and restored one logical database at a time, and a dedicated
 * database MOVED onto an engine with its workload following it to the new address.
 *
 * AIDEV-NOTE: every isolation claim is asserted on the ENGINE'S OWN REPLY, and every
 * negative is preceded by the identical probe taken as a POSITIVE ANCHOR from the same
 * container -- "unreachable because the probe is broken" must never be able to pass as
 * "unauthorized". The same discipline as InstanceDatabaseLinkLiveTest, for the same
 * reason: a client that exits zero while the server refused has bitten this repo twice.
 *
 * AIDEV-NOTE: the class owns its own sqlite control plane, so its controller identity --
 * and therefore every daemon resource NAME it mints -- is unique to this fork. It never
 * reads a daemon-wide listing; several forks share one daemon.
 */
@Tag("slow") // live lane: needs a real daemon/host/image; runs via `zenit-dev test --all`
class SharedDatabaseEngineLiveTest {

    private static final Path SOCKET = Path.of(DockerClient.DEFAULT_SOCKET);
    private static final String MONGO_IMAGE = "mongo:7";
    private static final String MYSQL_IMAGE = "mysql:8.0";
    private static final String POSTGRES_IMAGE = "postgres:17-alpine";
    private static final String ALPINE_IMAGE = "alpine:latest";

    private static SqliteDatasource datasource;
    private static PrivateNetns netns;
    private static Path backupRoot;
    private static String originalBackupPath;

    /** Daemon objects the PRODUCTION lanes should have reclaimed and did not. */
    private static final List<String> leftovers = new ArrayList<>();

    @BeforeAll
    static void setUp() throws Exception {
        File db = File.createTempFile("hohenheim-shared-engine-live", ".db");
        db.delete();
        db.deleteOnExit();
        datasource = new SqliteDatasource("jdbc:sqlite:" + db.getAbsolutePath());
        new MigrationRunner(datasource).migrate().requireSuccess();
        Datasources.register(Datasources.DEFAULT, datasource);
        HohenheimTestRuntime.ensureBooted();
        netns = PrivateNetns.installEnforcing();
        backupRoot = Files.createTempDirectory("hohenheim-shared-engine-backups");
        originalBackupPath = HohenheimSettings.VALUES.getValue(
            HohenheimSettings.Database.BACKUP_PATH);
        HohenheimSettings.VALUES.setValue(HohenheimSettings.Database.BACKUP_PATH,
            backupRoot.toString());
    }

    @AfterAll
    static void tearDown() {
        if (originalBackupPath != null) {
            HohenheimSettings.VALUES.setValue(HohenheimSettings.Database.BACKUP_PATH,
                originalBackupPath);
        }
        PrivateNetns.uninstall(netns);
        netns = null;
        // A separate verdict, deliberately: reporting it inside a journey's finally would
        // REPLACE whatever assertion actually named the defect.
        assertThat(leftovers)
            .as("this class left no engine container or volume of its own behind")
            .isEmpty();
    }

    // -- journey 1: one engine, two tenants ------------------------------------

    /**
     * TWO shared mongo databases on ONE engine process: both work with their own
     * credentials, neither can read the other, a backup and restore stay inside one
     * logical database, a destroy removes exactly one of them, and the engine outlives
     * its last database only until an operator says otherwise.
     */
    @Test
    void twoLogicalDatabasesShareOneMongoEngineWithoutSharingAnythingElse() throws Exception {
        DockerClient docker = requireDaemon(MONGO_IMAGE);
        Db.run(datasource, () -> {
            try {
                sharedMongoJourney(docker);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
    }

    private static void sharedMongoJourney(DockerClient docker) throws IOException {
        HostFixtures.admitLocal();
        DatabaseService service = new DatabaseService(datasource);
        String nameA = "sharedmga" + System.nanoTime();
        String nameB = "sharedmgb" + System.nanoTime();
        String dbA = "tenanta";
        String dbB = "tenantb";
        String passwordA = "mgpwA1234";
        String passwordB = "mgpwB1234";
        Integer engineId = null;
        String engineVolume = null;
        try {
            // 1. Two shared databases, created synchronously: both active, both SHARED,
            //    both naming the same engine.
            service.create(nameA, ManagedDatabase.Engine.MONGO, MONGO_IMAGE, "usera",
                passwordA, dbA, false, ServerService.LOCAL, ResourceLimits.none(),
                DatabaseModel.PLACEMENT_SHARED);
            service.create(nameB, ManagedDatabase.Engine.MONGO, MONGO_IMAGE, "userb",
                passwordB, dbB, false, ServerService.LOCAL, ResourceLimits.none(),
                DatabaseModel.PLACEMENT_SHARED);
            DatabaseService.Detail detailA = service.detail(nameA);
            DatabaseService.Detail detailB = service.detail(nameB);
            assertThat(detailA.status()).as("step 1: A provisioned").isEqualTo("active");
            assertThat(detailB.status()).as("step 1: B provisioned").isEqualTo("active");
            assertThat(detailA.placement()).as("step 1: A is shared")
                .isEqualTo(DatabaseModel.PLACEMENT_SHARED);
            assertThat(detailA.engineName()).as("step 1: and names its engine")
                .isEqualTo(detailB.engineName()).isNotBlank();

            // 2. There is exactly ONE engine container, and it is what serves BOTH -- the
            //    entire point of the change, asserted at the daemon rather than inferred.
            Row engineRow = Models.get(DatabaseEngineModel.class)
                .findOnHost(ServerModel.localServerId(), "mongo");
            assertThat(engineRow).as("step 2: the host has a mongo engine row").isNotNull();
            engineId = engineRow.get(DatabaseEngineModel.ID);
            engineVolume = EngineHost.ofEngine(engineRow).dataVolume();
            String engine = EngineHandles.ofEngine(engineRow);
            assertThat(running(docker, engine))
                .as("step 2: the engine container runs").isTrue();
            assertThat(EngineHandles.of(nameA))
                .as("step 2: A is served by the engine container").isEqualTo(engine);
            assertThat(EngineHandles.of(nameB))
                .as("step 2: and so is B -- one process, two databases").isEqualTo(engine);
            assertThat(DatabaseEngines.databasesOn(engineId))
                .as("step 2: the engine row knows both").hasSize(2);

            // 3. A's OWN user works on A's own database: a write and a read-back through
            //    the credential the record carries, authenticating on its own database.
            String wrote = mongo(docker, engine, "usera", passwordA, dbA, dbA,
                "db.probe.insertOne({v: 'forty-two'}); print(db.probe.findOne().v);");
            assertThat(wrote).as("step 3: A's own credential writes and reads back: %s", wrote)
                .contains("forty-two");

            // 4. NEGATIVE CONTROL, with step 3 as its anchor: the SAME credential reaching
            //    for B's database is refused by the engine itself.
            String crossed = mongo(docker, engine, "usera", passwordA, dbA, dbA,
                "print(db.getSiblingDB('" + dbB + "').probe.find().toArray().length);");
            assertThat(crossed.toLowerCase(java.util.Locale.ROOT))
                .as("step 4: A's user is NOT authorized on B's database: %s", crossed)
                .contains("not authorized");
            String ownB = mongo(docker, engine, "userb", passwordB, dbB, dbB,
                "db.probe.insertOne({v: 'b-value'}); print(db.probe.findOne().v);");
            assertThat(ownB).as("step 4 anchor: B's own user works on B, so step 4 is a"
                + " permission boundary and not a broken probe").contains("b-value");

            // 5. The FINGERPRINT of one logical database, the comparison the move relies
            //    on: it names the collection and carries the engine's own hash.
            String fingerprint = fingerprint(docker, engine, engineRow, dbA);
            assertThat(fingerprint).as("step 5: the fingerprint names the collection")
                .contains("probe");
            assertThat(fingerprint).as("step 5: and carries the engine's own hash")
                .contains("md5=");

            // 6. Backup and restore are scoped to ONE logical database: dump A, destroy
            //    its collection, restore, and the document is back.
            DatabaseService.BackupDownload dump = service.backupDownload(nameA);
            assertThat(dump.content().length)
                .as("step 6: the dump of one logical database is not empty").isPositive();
            Path dumpFile = Files.createTempFile("shared-engine-dump", ".archive");
            try {
                Files.write(dumpFile, dump.content());
                mongo(docker, engine, "usera", passwordA, dbA, dbA, "db.probe.drop();");
                assertThat(mongo(docker, engine, "usera", passwordA, dbA, dbA,
                        "print('count=' + db.probe.countDocuments({}));"))
                    .as("step 6: the collection really is gone before the restore")
                    .contains("count=0");
                service.restoreFromFile(nameA, dumpFile);
                assertThat(mongo(docker, engine, "usera", passwordA, dbA, dbA,
                        "print('count=' + db.probe.countDocuments({}));"))
                    .as("step 6: the restore put the one document back")
                    .contains("count=1");
            } finally {
                Files.deleteIfExists(dumpFile);
            }
            assertThat(mongo(docker, engine, "userb", passwordB, dbB, dbB,
                    "print('count=' + db.probe.countDocuments({}));"))
                .as("step 6 anchor: and it touched nothing in B")
                .contains("count=1");

            // 7. Destroying A drops exactly A: its user cannot authenticate any more and
            //    its database is gone, while B answers and the ENGINE keeps running.
            service.destroy(nameA, true);
            assertThat(service.detail(nameA)).as("step 7: A's record is gone").isNull();
            String rootList = mongoRoot(engineRow, docker, engine,
                "print(db.adminCommand({listDatabases: 1}).databases"
                    + ".map(function(entry) { return entry.name; }).join(','));");
            assertThat(rootList).as("step 7: the engine no longer holds A's database: %s",
                    rootList).doesNotContain(dbA);
            assertThat(mongo(docker, engine, "usera", passwordA, dbA, dbA, "print('reached');")
                    .toLowerCase(java.util.Locale.ROOT))
                .as("step 7: and A's user cannot authenticate at all any more")
                .doesNotContain("reached");
            assertThat(mongo(docker, engine, "userb", passwordB, dbB, dbB,
                    "print('count=' + db.probe.countDocuments({}));"))
                .as("step 7 anchor: B is untouched by A's destroy").contains("count=1");
            assertThat(running(docker, engine))
                .as("step 7: the engine survives its database's destroy -- recreating one"
                    + " costs a minute and a port").isTrue();

            // 8. The engine cannot go while B lives on it, and the refusal names B.
            final int used = engineId;
            Throwable inUse = catchThrowable(() -> DatabaseEngines.destroy(used, true));
            assertThat(inUse).as("step 8: destroying a used engine is refused")
                .isInstanceOf(Violations.class);
            assertThat(((Violations) inUse).all().get(0).message().key())
                .as("step 8: by name").isEqualTo("database_engine_in_use");
            assertThat(running(docker, engine))
                .as("step 8: and the refusal touched no container").isTrue();

            // 9. With its last database gone the engine is an explicit operator decision:
            //    container, volume and row all go, verified at the daemon.
            service.destroy(nameB, true);
            DatabaseEngines.destroy(engineId, true);
            assertThat(Models.get(DatabaseEngineModel.class).findById(used))
                .as("step 9: the engine row is gone").isNull();
            assertThat(exists(docker, engine))
                .as("step 9: and so is its container").isFalse();
            assertThat(volumeExists(docker, engineVolume))
                .as("step 9: the data volume followed the explicit removeData").isFalse();
            engineId = null;
            engineVolume = null;
        } finally {
            cleanUpDatabases(service, nameA, nameB);
            cleanUpEngine(docker, engineId, engineVolume);
        }
    }

    // -- journey 2: the move ----------------------------------------------------

    /**
     * A DEDICATED database with a live workload attached MOVES onto the host's shared
     * engine: the data crosses verified, the workload is redeployed onto the new address,
     * the old container goes and its data volume stays as the rollback.
     */
    @Test
    void aDedicatedDatabaseMovesOntoTheSharedEngineAndItsWorkloadFollows() throws Exception {
        DockerClient docker = requireDaemon(MONGO_IMAGE);
        LiveLane.requireImage(docker, ALPINE_IMAGE);
        Db.run(datasource, () -> {
            try {
                moveJourney(docker);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
    }

    private static void moveJourney(DockerClient docker) throws IOException {
        HostFixtures.admitLocal();
        DatabaseService service = new DatabaseService(datasource);
        String name = "movemg" + System.nanoTime();
        String database = "moved";
        String user = "moveuser";
        String password = "movepw1234";
        Integer instanceId = null;
        Integer engineId = null;
        String engineVolume = null;
        String dedicatedVolume = null;
        try {
            // 1. A DEDICATED, volume-backed mongo with a workload attached and running.
            service.create(name, ManagedDatabase.Engine.MONGO, MONGO_IMAGE, user, password,
                database, false, ServerService.LOCAL, ResourceLimits.none(),
                DatabaseModel.PLACEMENT_DEDICATED);
            Row record = Models.get(DatabaseModel.class).findByName(name);
            int databaseId = record.get(DatabaseModel.ID);
            dedicatedVolume = DatabaseInstances.dataVolumeOf(name);
            String dedicatedHandle = EngineHandles.of(name);
            assertThat(service.detail(name).placement())
                .as("step 1: it really starts dedicated")
                .isEqualTo(DatabaseModel.PLACEMENT_DEDICATED);
            assertThat(volumeExists(docker, dedicatedVolume))
                .as("step 1: on a named data volume of its own").isTrue();

            instanceId = workload("move-workload");
            attach(instanceId, databaseId, "DB");
            new InstanceService().deploy(instanceId);
            String workloadHandle = ControllerScope.handle(ControllerScope.KIND_INSTANCE,
                instanceId);
            assertThat(containerEnv(docker, workloadHandle).get("DB_HOST"))
                .as("step 1: the workload is injected with the DEDICATED container")
                .isEqualTo(dedicatedHandle);

            // 2. Three documents through the dedicated container, as its own root user.
            mongo(docker, dedicatedHandle, user, password, "admin", "admin",
                "db.getSiblingDB('" + database + "').moved.insertMany("
                    + "[{n: 1}, {n: 2}, {n: 3}]);");
            assertThat(mongo(docker, dedicatedHandle, user, password, "admin", "admin",
                    "print('count=' + db.getSiblingDB('" + database
                        + "').moved.countDocuments({}));"))
                .as("step 2: three documents exist on the dedicated engine")
                .contains("count=3");

            // 3. THE MOVE, synchronously.
            service.moveToSharedEngine(name);
            DatabaseService.Detail moved = service.detail(name);
            assertThat(moved.placement()).as("step 3: the record is shared now")
                .isEqualTo(DatabaseModel.PLACEMENT_SHARED);
            assertThat(moved.status()).as("step 3: and active").isEqualTo("active");
            assertThat(moved.failureReason()).as("step 3: with no failure recorded").isNull();
            assertThat(moved.engineName()).as("step 3: naming its engine").isNotBlank();

            Row engineRow = Models.get(DatabaseEngineModel.class)
                .findOnHost(ServerModel.localServerId(), "mongo");
            engineId = engineRow.get(DatabaseEngineModel.ID);
            engineVolume = EngineHost.ofEngine(engineRow).dataVolume();
            String engine = EngineHandles.ofEngine(engineRow);

            // 4. The DATA crossed: the engine holds all three documents, readable both by
            //    the controller's root and by the record's OWN credential on its own
            //    database -- the user was recreated there, so authSource is that database.
            assertThat(mongoRoot(engineRow, docker, engine,
                    "print('count=' + db.getSiblingDB('" + database
                        + "').moved.countDocuments({}));"))
                .as("step 4: the engine holds the three documents").contains("count=3");
            assertThat(mongo(docker, engine, user, password, database, database,
                    "print('count=' + db.moved.countDocuments({}));"))
                .as("step 4: and the record's own user reads them on its own database")
                .contains("count=3");

            // 5. The WORKLOAD followed: it was redeployed and now dials the ENGINE, on
            //    the engine's port, authenticating against its own logical database.
            Map<String, String> env = containerEnv(docker, workloadHandle);
            assertThat(env.get("DB_HOST"))
                .as("step 5: the workload now names the ENGINE container").isEqualTo(engine);
            assertThat(env.get("DB_PORT")).as("step 5: on mongo's own port")
                .isEqualTo("27017");
            assertThat(env.get("DATABASE_URL"))
                .as("step 5: and its URL authenticates on its own database")
                .endsWith("?authSource=" + database)
                .contains("@" + engine + ":27017/" + database);

            // 6. The OLD runtime is gone -- instance row and container -- while its data
            //    VOLUME deliberately survives as the operator's rollback.
            Row stillThere = Models.get(DatabaseModel.class).findByName(name);
            assertThat(DatabaseInstances.ownedBy(EngineHost.dedicated(stillThere)))
                .as("step 6: the old dedicated instance row is gone").isNull();
            assertThat(exists(docker, dedicatedHandle))
                .as("step 6: and so is its container").isFalse();
            assertThat(volumeExists(docker, dedicatedVolume))
                .as("step 6: while the old data volume is KEPT as the rollback").isTrue();

            // 7. The DUMP is kept too, where the nightly prune never looks.
            Path moves = backupRoot.resolve("moves").resolve(name);
            assertThat(Files.isDirectory(moves))
                .as("step 7: the move wrote its rollback dump directory").isTrue();
            try (var entries = Files.list(moves)) {
                List<Path> files = entries.toList();
                assertThat(files).as("step 7: with the dump in it").isNotEmpty();
                assertThat(files.get(0).getFileName().toString())
                    .as("step 7: named as a mongo archive").endsWith(".archive");
            }

            // 8. NEGATIVE CONTROL: moving again is refused by name -- there is no second
            //    move, and the refusal is not a silent no-op that would look like one.
            assertThatThrownBy(() -> service.moveToSharedEngine(name))
                .as("step 8: a record already on an engine cannot move again")
                .isInstanceOf(IOException.class)
                .hasMessageContaining("already lives on a shared engine");
        } finally {
            if (instanceId != null) {
                try {
                    new InstanceService().destroy(instanceId);
                } catch (RuntimeException ignored) {
                    // best effort
                }
            }
            cleanUpDatabases(service, name);
            cleanUpEngine(docker, engineId, engineVolume);
            if (dedicatedVolume != null) {
                removeVolume(docker, dedicatedVolume);
            }
        }
    }

    // -- journey 3: the SQL engines --------------------------------------------

    /**
     * The mechanism is generic, not Mongo-shaped: a shared MySQL database and a shared
     * PostgreSQL database each get their own logical database and user, answer their own
     * client, fingerprint per table, and are dropped from the engine on destroy.
     */
    @Test
    void mysqlAndPostgresHostLogicalDatabasesThroughTheSameMechanism() throws Exception {
        DockerClient docker = requireDaemon(MYSQL_IMAGE);
        LiveLane.requireImage(docker, POSTGRES_IMAGE);
        Db.run(datasource, () -> {
            try {
                sqlJourney(docker);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
    }

    private static void sqlJourney(DockerClient docker) throws IOException {
        HostFixtures.admitLocal();
        DatabaseService service = new DatabaseService(datasource);
        String mysqlName = "sharedmy" + System.nanoTime();
        String postgresName = "sharedpg" + System.nanoTime();
        String mysqlDatabase = "myshared";
        String postgresDatabase = "pgshared";
        String password = "sqlpw1234";
        Integer mysqlEngineId = null;
        Integer postgresEngineId = null;
        String mysqlVolume = null;
        String postgresVolume = null;
        try {
            // 1. One shared database per SQL engine, created synchronously.
            service.create(mysqlName, ManagedDatabase.Engine.MYSQL, MYSQL_IMAGE, "myuser",
                password, mysqlDatabase, false, ServerService.LOCAL, ResourceLimits.none(),
                DatabaseModel.PLACEMENT_SHARED);
            service.create(postgresName, ManagedDatabase.Engine.POSTGRES, POSTGRES_IMAGE,
                "pguser", password, postgresDatabase, false, ServerService.LOCAL,
                ResourceLimits.none(), DatabaseModel.PLACEMENT_SHARED);
            assertThat(service.detail(mysqlName).placement())
                .as("step 1: the mysql record is shared")
                .isEqualTo(DatabaseModel.PLACEMENT_SHARED);
            assertThat(service.detail(postgresName).placement())
                .as("step 1: and so is the postgres one")
                .isEqualTo(DatabaseModel.PLACEMENT_SHARED);

            Row mysqlEngine = Models.get(DatabaseEngineModel.class)
                .findOnHost(ServerModel.localServerId(), "mysql");
            Row postgresEngine = Models.get(DatabaseEngineModel.class)
                .findOnHost(ServerModel.localServerId(), "postgres");
            mysqlEngineId = mysqlEngine.get(DatabaseEngineModel.ID);
            postgresEngineId = postgresEngine.get(DatabaseEngineModel.ID);
            mysqlVolume = EngineHost.ofEngine(mysqlEngine).dataVolume();
            postgresVolume = EngineHost.ofEngine(postgresEngine).dataVolume();
            String myHandle = EngineHandles.ofEngine(mysqlEngine);
            String pgHandle = EngineHandles.ofEngine(postgresEngine);

            // 2. Each database's OWN credential creates a table and a row inside the
            //    engine -- the grant really is a per-database grant, not a login.
            DockerClient.ExecResult myWrite = docker.exec(myHandle, List.of("mysql",
                "-u", "myuser", mysqlDatabase, "-e",
                "CREATE TABLE probe (id INT); INSERT INTO probe VALUES (42);"),
                List.of("MYSQL_PWD=" + password));
            assertThat(myWrite.exitCode())
                .as("step 2: mysql's own user writes: %s", myWrite.stderr()).isZero();
            DockerClient.ExecResult myRead = docker.exec(myHandle, List.of("mysql",
                "-u", "myuser", mysqlDatabase, "-N", "-e", "SELECT id FROM probe"),
                List.of("MYSQL_PWD=" + password));
            assertThat(myRead.stdout()).as("step 2: and reads back").contains("42");

            DockerClient.ExecResult pgWrite = docker.exec(pgHandle, List.of("psql",
                "-v", "ON_ERROR_STOP=1", "-U", "pguser", "-d", postgresDatabase, "-c",
                "CREATE TABLE probe (id INT); INSERT INTO probe VALUES (42);"),
                List.of("PGPASSWORD=" + password));
            assertThat(pgWrite.exitCode())
                .as("step 2: postgres's own user writes: %s", pgWrite.stderr()).isZero();
            DockerClient.ExecResult pgRead = docker.exec(pgHandle, List.of("psql",
                "-U", "pguser", "-d", postgresDatabase, "-tA", "-c", "SELECT id FROM probe"),
                List.of("PGPASSWORD=" + password));
            assertThat(pgRead.stdout()).as("step 2: and reads back").contains("42");

            // 3. The fingerprint is per TABLE on both, which is what the move compares.
            assertThat(fingerprint(docker, myHandle, mysqlEngine, mysqlDatabase))
                .as("step 3: mysql's fingerprint names the table")
                .contains("probe");
            assertThat(fingerprint(docker, pgHandle, postgresEngine, postgresDatabase))
                .as("step 3: and so does postgres's").contains("probe");

            // 4. Destroy drops the logical database ON the engine, asserted by asking the
            //    engine's own catalogue -- with the pre-destroy listing as the anchor.
            assertThat(mysqlDatabases(docker, myHandle, mysqlEngine))
                .as("step 4 anchor: the engine lists the database before the destroy")
                .contains(mysqlDatabase);
            assertThat(postgresDatabases(docker, pgHandle, postgresEngine))
                .as("step 4 anchor: and so does postgres").contains(postgresDatabase);
            service.destroy(mysqlName, true);
            service.destroy(postgresName, true);
            assertThat(mysqlDatabases(docker, myHandle, mysqlEngine))
                .as("step 4: mysql no longer lists it").doesNotContain(mysqlDatabase);
            assertThat(postgresDatabases(docker, pgHandle, postgresEngine))
                .as("step 4: and neither does postgres").doesNotContain(postgresDatabase);

            // 5. Both engines then go on the operator's explicit word.
            DatabaseEngines.destroy(mysqlEngineId, true);
            DatabaseEngines.destroy(postgresEngineId, true);
            assertThat(exists(docker, myHandle))
                .as("step 5: the mysql engine container is gone").isFalse();
            assertThat(exists(docker, pgHandle))
                .as("step 5: the postgres engine container is gone").isFalse();
            assertThat(volumeExists(docker, mysqlVolume))
                .as("step 5: with its volume").isFalse();
            assertThat(volumeExists(docker, postgresVolume))
                .as("step 5: and postgres's").isFalse();
            mysqlEngineId = null;
            postgresEngineId = null;
            mysqlVolume = null;
            postgresVolume = null;
        } finally {
            cleanUpDatabases(service, mysqlName, postgresName);
            cleanUpEngine(docker, mysqlEngineId, mysqlVolume);
            cleanUpEngine(docker, postgresEngineId, postgresVolume);
        }
    }

    // -- helpers ----------------------------------------------------------------

    private static DockerClient requireDaemon(String image) {
        LiveLane.require(LiveLane.Need.DOCKER_SOCKET, Files.exists(SOCKET),
            "Docker socket not present");
        DockerClient docker = new DockerClient();
        LiveLane.requireImage(docker, image);
        LiveLane.require(LiveLane.Need.NETNS, netns != null,
            "no private netns: the instance tier refuses to deploy unprotected");
        return docker;
    }

    /**
     * A mongosh conversation INSIDE the engine container over the very URL shape the
     * product injects into a workload -- credentials, database and {@code authSource} --
     * so a script here can only reach what a real consumer could.
     */
    private static String mongo(DockerClient docker, String handle, String user,
                                String password, String database, String authDatabase,
                                String script) throws IOException {
        String uri = "mongodb://" + user + ":" + password + "@127.0.0.1:27017/" + database
            + "?authSource=" + authDatabase;
        DockerClient.ExecResult result = docker.exec(handle,
            List.of("mongosh", uri, "--quiet", "--eval", script), List.of());
        return result.stdout() + result.stderr();
    }

    /** The same, as the ENGINE's controller-owned root user in {@code admin}. */
    private static String mongoRoot(Row engineRow, DockerClient docker, String handle,
                                    String script) throws IOException {
        EngineHost host = EngineHost.ofEngine(engineRow);
        return mongo(docker, handle, host.rootUser(), host.rootPassword(), "admin", "admin",
            script);
    }

    /** The PRODUCT's own fingerprint command, run where the move runs it. */
    private static String fingerprint(DockerClient docker, String handle, Row engineRow,
                                      String database) throws IOException {
        EngineHost host = EngineHost.ofEngine(engineRow);
        DockerClient.ExecResult result = docker.exec(handle,
            host.engine().fingerprintCommand(host.rootUser(), database),
            host.engine().logicalEnv(host.rootPassword(), null));
        assertThat(result.exitCode())
            .as("the fingerprint command must succeed: %s", result.stderr()).isZero();
        return result.stdout();
    }

    private static String mysqlDatabases(DockerClient docker, String handle, Row engineRow)
            throws IOException {
        EngineHost host = EngineHost.ofEngine(engineRow);
        DockerClient.ExecResult result = docker.exec(handle,
            List.of("mysql", "-u", host.rootUser(), "-N", "-e", "SHOW DATABASES"),
            List.of("MYSQL_PWD=" + host.rootPassword()));
        return result.stdout();
    }

    private static String postgresDatabases(DockerClient docker, String handle, Row engineRow)
            throws IOException {
        EngineHost host = EngineHost.ofEngine(engineRow);
        DockerClient.ExecResult result = docker.exec(handle,
            List.of("psql", "-U", host.rootUser(), "-d", "postgres", "-tA", "-c",
                "SELECT datname FROM pg_database"),
            List.of("PGPASSWORD=" + host.rootPassword()));
        return result.stdout();
    }

    private static boolean running(DockerClient docker, String handle) throws IOException {
        Object state = docker.inspectContainer(handle).get("State");
        return state instanceof Map<?, ?> map && Boolean.TRUE.equals(map.get("Running"));
    }

    private static boolean exists(DockerClient docker, String handle) {
        try {
            docker.inspectContainer(handle);
            return true;
        } catch (IOException absent) {
            return false;
        }
    }

    private static boolean volumeExists(DockerClient docker, String volume) {
        try {
            docker.inspectVolume(volume);
            return true;
        } catch (IOException absent) {
            return false;
        }
    }

    private static int workload(String name) {
        Map<String, Object> settings = new LinkedHashMap<>();
        settings.put("image", "alpine");
        settings.put("tag", "latest");
        settings.put("command", "sleep 600");
        settings.put("container_port", 8080);
        Row row = Models.get(InstanceModel.class).createEmptyRow();
        row.set(InstanceModel.NAME, name);
        row.set(InstanceModel.KIND, "hohenheim:docker_container");
        row.set(InstanceModel.SERVER_ID, ServerModel.localServerId());
        row.set(InstanceModel.SETTINGS, settings);
        Models.get(InstanceModel.class).save(row);
        return row.get(InstanceModel.ID);
    }

    private static void attach(int instanceId, int databaseId, String prefix) {
        Row row = Models.get(InstanceDatabaseModel.class).createEmptyRow();
        row.set(InstanceDatabaseModel.INSTANCE_ID, instanceId);
        row.set(InstanceDatabaseModel.DATABASE_ID, databaseId);
        row.set(InstanceDatabaseModel.ENV_PREFIX, prefix);
        Models.get(InstanceDatabaseModel.class).save(row);
    }

    private static Map<String, String> containerEnv(DockerClient docker, String handle)
            throws IOException {
        Map<String, Object> inspect = docker.inspectContainer(handle);
        Map<String, String> env = new LinkedHashMap<>();
        if (inspect.get("Config") instanceof Map<?, ?> config
                && config.get("Env") instanceof List<?> entries) {
            for (Object entry : entries) {
                String text = String.valueOf(entry);
                int eq = text.indexOf('=');
                if (eq > 0) {
                    env.put(text.substring(0, eq), text.substring(eq + 1));
                }
            }
        }
        return env;
    }

    private static void cleanUpDatabases(DatabaseService service, String... names) {
        for (String name : names) {
            try {
                service.destroy(name, true);
            } catch (Exception ignored) {
                // best effort; the assertions are the outcome
            }
            try {
                service.forceDestroyRecord(name);
            } catch (RuntimeException ignored) {
                // best effort
            }
        }
    }

    /**
     * Take the engine down, and RECORD what could not be reclaimed for
     * {@link #tearDown} to answer for.
     */
    private static void cleanUpEngine(DockerClient docker, Integer engineId, String volume) {
        if (engineId != null) {
            String handle = null;
            try {
                Row row = Models.get(DatabaseEngineModel.class).findById(engineId);
                if (row != null) {
                    handle = DatabaseInstances.handleOf(EngineHost.ofEngine(row));
                }
            } catch (RuntimeException ignored) {
                // the row is already gone
            }
            try {
                DatabaseEngines.destroy(engineId, true);
            } catch (Exception refused) {
                try {
                    DatabaseEngines.forceDestroy(engineId);
                } catch (Exception ignored) {
                    // best effort
                }
            }
            if (handle != null && exists(docker, handle)) {
                leftovers.add(handle);
                try {
                    docker.removeContainer(handle, true);
                } catch (IOException ignored) {
                    // the daemon keeps it; the AfterAll assertion reports it
                }
            }
        }
        if (volume != null && volumeExists(docker, volume)) {
            leftovers.add(volume);
            removeVolume(docker, volume);
        }
    }

    private static void removeVolume(DockerClient docker, String volume) {
        try {
            docker.removeVolume(volume, true);
        } catch (IOException ignored) {
            // best effort
        }
    }
}
