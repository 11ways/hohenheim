package be.elevenways.hohenheim.test.database;

import be.elevenways.hohenheim.server.runtime.ContainerState;
import be.elevenways.hohenheim.test.TestDatabases;
import be.elevenways.hohenheim.HohenheimEndpoints;
import be.elevenways.hohenheim.model.DatabaseEngineModel;
import be.elevenways.hohenheim.model.DatabaseModel;
import be.elevenways.hohenheim.model.InstanceDatabaseModel;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.server.application.ApplicationReleases;
import be.elevenways.hohenheim.server.database.DatabaseEnvInjection;
import be.elevenways.hohenheim.server.instance.ApplicationKind;
import be.elevenways.hohenheim.server.orm.GeneratedRows;
import be.elevenways.hohenheim.server.database.ManagedDatabase;
import be.elevenways.hohenheim.test.HohenheimTestRuntime;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The env-injection resolver contract: prefixed variable families per attached database,
 * DATABASE_URL pinned to the FIRST link, unavailable databases contributing nothing, and
 * URL-encoded credentials. Live ports are stubbed -- no Docker needed.
 *
 * AIDEV-NOTE: there is exactly ONE lane left. Phase 0 brief 7 deleted {@code envForSite}
 * and with it the only public entry point that asked for {@code Style.PUBLISHED_LOOPBACK},
 * so every journey here now runs the INSTANCE lane, which is always CONTAINER_NETWORK: a
 * workload's own 127.0.0.1 is itself, and the loopback style would hand it an address that
 * reaches nothing. The subjects are unchanged -- families, primary-URL pinning, degradation
 * -- only the address shape they assert moved.
 */
class DatabaseEnvInjectionTest {

    @BeforeAll
    static void boot() throws Exception {
        HohenheimEndpoints.init();
        TestDatabases.freshDatabase();
        HohenheimTestRuntime.ensureBooted();
    }

    @AfterEach
    void cleanRows() {
        // Generated rows are read-only outside a system scope, fixtures included -- the
        // sweeping scope is the lane that exists for "the declaring record is going away".
        GeneratedRows.sweeping("test", () -> Models.get(InstanceModel.class).find().delete());
        Models.get(InstanceDatabaseModel.class).find().delete();
        Models.get(DatabaseModel.class).find().delete();
        // Engines last: the write funnel refuses an engine that still hosts a record.
        Models.get(DatabaseEngineModel.class).find().delete();
    }

    /** The workload whose links are injected: an application, the release-managed kind. */
    private static Integer application(String name) {
        InstanceModel instances = Models.get(InstanceModel.class);
        Row row = instances.createEmptyRow();
        row.set(InstanceModel.NAME, name);
        row.set(InstanceModel.KIND, ApplicationKind.ID.toString());
        row.set(InstanceModel.SERVER_ID, ServerModel.localServerId());
        row.set(InstanceModel.SETTINGS, new LinkedHashMap<>(Map.of("image", "alpine")));
        instances.save(row);
        return row.get(InstanceModel.ID);
    }

    private static Integer database(String name, String engine, String status) {
        DatabaseModel databases = Models.get(DatabaseModel.class);
        Row row = databases.createEmptyRow();
        row.set(DatabaseModel.NAME, name);
        row.set(DatabaseModel.ENGINE, engine);
        row.set(DatabaseModel.DB_USER, "appuser");
        row.set(DatabaseModel.DB_PASSWORD, "s3cret");
        row.set(DatabaseModel.DB_NAME, "appdb");
        row.set(DatabaseModel.STATUS, status);
        row.set(DatabaseModel.SERVER_ID, ServerModel.localServerId());
        databases.save(row);
        Integer id = row.get(DatabaseModel.ID);
        // The engine's address IS its owned instance's handle since the lowering, so a
        // record without one has no address at all -- plant it like production does.
        EngineHandles.plant(id, name, engine, InstanceModel.STATUS_RUNNING);
        return id;
    }

    private static void link(Integer instanceId, Integer databaseId, String prefix) {
        InstanceDatabaseModel links = Models.get(InstanceDatabaseModel.class);
        Row link = links.createEmptyRow();
        link.set(InstanceDatabaseModel.INSTANCE_ID, instanceId);
        link.set(InstanceDatabaseModel.DATABASE_ID, databaseId);
        link.set(InstanceDatabaseModel.ENV_PREFIX, prefix);
        links.save(link);
    }

    @Test
    void attachedDatabasesResolveToPrefixedFamiliesWithPrimaryUrl() {
        Integer applicationId = application("inject-two");
        link(applicationId, database("maindb", "postgres", DatabaseModel.STATUS_ACTIVE), "DB");
        link(applicationId, database("cachedb", "redis", DatabaseModel.STATUS_ACTIVE), "CACHE");

        Map<String, String> env = DatabaseEnvInjection.envForInstance(applicationId,
            row -> new ManagedDatabase.LiveStatus(ContainerState.RUNNING,
                "maindb".equals(row.get(DatabaseModel.NAME)) ? 5544 : 6380));

        String pgUrl = "postgres://appuser:s3cret@" + EngineHandles.of("maindb") + ":5432/appdb";
        assertThat(env).containsEntry("DB_HOST", EngineHandles.of("maindb"));
        assertThat(env).containsEntry("DB_PORT", "5432");
        assertThat(env).containsEntry("DB_USER", "appuser");
        assertThat(env).containsEntry("DB_PASSWORD", "s3cret");
        assertThat(env).containsEntry("DB_NAME", "appdb");
        assertThat(env).containsEntry("DB_URL", pgUrl);
        assertThat(env).containsEntry("DATABASE_URL", pgUrl);
        assertThat(env).containsEntry("CACHE_URL",
            "redis://:s3cret@" + EngineHandles.of("cachedb") + ":6379");
        assertThat(env).containsEntry("CACHE_PORT", "6379");
    }

    @Test
    void unavailableDatabaseContributesNothingAndNeverReassignsPrimaryUrl() {
        Integer applicationId = application("inject-down");
        link(applicationId, database("downdb", "postgres", DatabaseModel.STATUS_ACTIVE), "DB");
        link(applicationId, database("updb", "mysql", DatabaseModel.STATUS_ACTIVE), "SECOND");

        // The primary (first) link's container is stopped; the second resolves.
        Map<String, String> env = DatabaseEnvInjection.envForInstance(applicationId,
            row -> "downdb".equals(row.get(DatabaseModel.NAME))
                ? new ManagedDatabase.LiveStatus(ContainerState.STOPPED, null)
                : new ManagedDatabase.LiveStatus(ContainerState.RUNNING, 3311));

        assertThat(env).doesNotContainKey("DB_HOST");
        assertThat(env).doesNotContainKey("DB_URL");
        // DATABASE_URL belongs to the first link; it must not silently point elsewhere.
        assertThat(env).doesNotContainKey("DATABASE_URL");
        assertThat(env).containsEntry("SECOND_URL",
            "mysql://appuser:s3cret@" + EngineHandles.of("updb") + ":3306/appdb");
    }

    @Test
    void nonActiveRecordsAndFailedResolutionDegradeToNoVariables() {
        Integer applicationId = application("inject-failed");
        link(applicationId, database("faileddb", "postgres", DatabaseModel.STATUS_FAILED), "DB");

        Map<String, String> env = DatabaseEnvInjection.envForInstance(applicationId,
            row -> new ManagedDatabase.LiveStatus(ContainerState.RUNNING, 5544));
        assertThat(env).isEmpty();

        // A workload with no links resolves to nothing without touching the resolver.
        assertThat(DatabaseEnvInjection.envForInstance(application("inject-bare"), row -> {
            throw new AssertionError("resolver must not run without links");
        })).isEmpty();
    }

    @Test
    void containerNetworkStyleUsesContainerHostnameAndEnginePort() {
        Integer applicationId = application("inject-container");
        link(applicationId, database("containerdb", "postgres", DatabaseModel.STATUS_ACTIVE), "DB");
        link(applicationId, database("containercache", "redis", DatabaseModel.STATUS_ACTIVE),
            "CACHE");

        // 1. The instance lane resolves the DB container hostname + the engine's own port
        //    -- the published loopback port must appear NOWHERE, because inside a workload
        //    container 127.0.0.1 is the container itself.
        Map<String, String> env = DatabaseEnvInjection.envForInstance(applicationId,
            row -> new ManagedDatabase.LiveStatus(ContainerState.RUNNING, 5544));
        String pgUrl = "postgres://appuser:s3cret@"
            + EngineHandles.of("containerdb") + ":5432/appdb";
        assertThat(env).as("step 1: host is the database ENGINE's container hostname")
            .containsEntry("DB_HOST", EngineHandles.of("containerdb"));
        assertThat(env).as("step 1: port is the engine's native port, never the published one")
            .containsEntry("DB_PORT", "5432");
        assertThat(env).as("step 1: the primary URL carries the same address")
            .containsEntry("DATABASE_URL", pgUrl).containsEntry("DB_URL", pgUrl);
        assertThat(env).as("step 1: the second family follows its engine's port")
            .containsEntry("CACHE_HOST", EngineHandles.of("containercache"))
            .containsEntry("CACHE_PORT", "6379")
            .containsEntry("CACHE_URL",
                "redis://:s3cret@" + EngineHandles.of("containercache") + ":6379");
        assertThat(env.values()).as("step 1: no variable smuggles a loopback address in")
            .noneMatch(value -> value.contains("127.0.0.1"));

        // 2. A running engine with no published port yet still resolves: the instance lane
        //    does not NEED the published port to hand out a reachable address.
        Map<String, String> unpublished = DatabaseEnvInjection.envForInstance(applicationId,
            row -> new ManagedDatabase.LiveStatus(ContainerState.RUNNING, null));
        assertThat(unpublished).as("step 2: the container address needs no published port")
            .containsEntry("DB_HOST", EngineHandles.of("containerdb"));

        // 3. A stopped engine contributes nothing at all: credentials for a dead database
        //    are the silent-success shape.
        assertThat(DatabaseEnvInjection.envForInstance(applicationId,
            row -> new ManagedDatabase.LiveStatus(ContainerState.STOPPED, null)))
            .as("step 3: a stopped database contributes no variables").isEmpty();
    }

    /**
     * WHERE an application's links land: the application carries them, but the container
     * that consumes them is its serving RELEASE -- so the release resolves its link owner
     * back to the application, and the application resolves its consumer forward.
     */
    @Test
    void anApplicationsLinksAreConsumedByItsReleaseContainer() {
        Integer applicationId = application("inject-release-owner");
        link(applicationId, database("releasedb", "postgres", DatabaseModel.STATUS_ACTIVE), "DB");

        // 1. The application's own env is the family its links declare.
        Map<String, String> owned = DatabaseEnvInjection.envForInstance(applicationId,
            row -> new ManagedDatabase.LiveStatus(ContainerState.RUNNING, 5544));
        assertThat(owned).as("step 1: the application carries the link family")
            .containsEntry("DB_HOST", EngineHandles.of("releasedb"));

        // 2. A serving release generated FOR that application carries no links of its own.
        int releaseId = release(applicationId);
        Row releaseRow = Models.get(InstanceModel.class).findById(releaseId);
        assertThat(DatabaseEnvInjection.envForInstance(releaseId, row -> {
            throw new AssertionError("a release declares no links of its own");
        })).as("step 2: a release has no links keyed to itself").isEmpty();

        // 3. Yet the link OWNER of that release is the application, which is what makes
        //    the release container start with the application's database variables.
        assertThat(ApplicationReleases.linkOwnerOf(releaseRow))
            .as("step 3: a release resolves its link owner back to its application")
            .isEqualTo(applicationId);
        assertThat(ApplicationReleases.consumerInstanceOf(applicationId))
            .as("step 3: and the application resolves forward to the container that"
                + " actually consumes them")
            .isEqualTo(releaseId);
    }

    @Test
    void credentialsAreUrlEncodedInConnectionUrls() {
        String url = DatabaseEnvInjection.connectionUrl(ManagedDatabase.Engine.POSTGRES,
            "127.0.0.1", 5432, "app user", "p@ss:w/rd+x", "appdb");
        assertThat(url).isEqualTo("postgres://app%20user:p%40ss%3Aw%2Frd%2Bx@127.0.0.1:5432/appdb");

        String mongo = DatabaseEnvInjection.connectionUrl(ManagedDatabase.Engine.MONGO,
            "127.0.0.1", 27017, "root", "secret", "appdb");
        assertThat(mongo).isEqualTo("mongodb://root:secret@127.0.0.1:27017/appdb?authSource=admin");
    }

    /**
     * A SHARED record's injected address is its ENGINE's container, and its Mongo user
     * authenticates against its OWN logical database -- the credential was created there,
     * so {@code authSource=admin} (the dedicated shape) would refuse every login.
     */
    @Test
    void aSharedRecordIsInjectedWithTheEnginesHostAndItsOwnAuthSource() {
        // 1. The derivation itself, at the seam every consumer reads: a dedicated record
        //    authenticates in admin (its user IS the engine root), a shared one on its own
        //    logical database.
        Integer applicationId = application("inject-shared");
        Integer dedicatedId = database("sharedded", "mongo", DatabaseModel.STATUS_ACTIVE);
        Row dedicated = Models.get(DatabaseModel.class).findById(dedicatedId);
        assertThat(DatabaseEnvInjection.authDatabaseOf(dedicated))
            .as("step 1: a dedicated record's root user lives in admin")
            .isEqualTo(DatabaseEnvInjection.MONGO_ROOT_AUTH_DATABASE);

        Row engineRow = engine("mongo-inject", "mongo");
        int engineId = engineRow.get(DatabaseEngineModel.ID);
        String engineHandle = EngineHandles.plantEngine(engineId, "mongo-inject", "mongo",
            InstanceModel.STATUS_RUNNING);
        Integer sharedId = sharedDatabase("sharedlogical", "mongo", engineId, "tenantdb");
        Row shared = Models.get(DatabaseModel.class).findById(sharedId);
        assertThat(DatabaseEnvInjection.authDatabaseOf(shared))
            .as("step 1: a shared record's user lives on its own logical database")
            .isEqualTo("tenantdb");

        // 2. The URL builder carries it, and the two shapes really differ.
        String sharedUrl = DatabaseEnvInjection.connectionUrl(ManagedDatabase.Engine.MONGO,
            engineHandle, 27017, "appuser", "s3cret", "tenantdb", "tenantdb");
        assertThat(sharedUrl)
            .as("step 2: the shared URL names its own database as the auth source")
            .isEqualTo("mongodb://appuser:s3cret@" + engineHandle
                + ":27017/tenantdb?authSource=tenantdb");
        assertThat(DatabaseEnvInjection.connectionUrl(ManagedDatabase.Engine.MONGO,
                engineHandle, 27017, "appuser", "s3cret", "tenantdb"))
            .as("step 2: while the default overload keeps the dedicated admin shape")
            .endsWith("?authSource=admin");

        // 3. End to end through the injection lane: DB_HOST is the ENGINE's container --
        //    the shared record owns none of its own -- and DATABASE_URL carries the
        //    per-database auth source.
        link(applicationId, sharedId, "DB");
        Map<String, String> env = DatabaseEnvInjection.envForInstance(applicationId,
            row -> new ManagedDatabase.LiveStatus(ContainerState.RUNNING, 27018));
        assertThat(env).as("step 3: the address is the ENGINE's container handle")
            .containsEntry("DB_HOST", engineHandle);
        assertThat(env).as("step 3: on the engine's own port, never the published one")
            .containsEntry("DB_PORT", "27017");
        assertThat(env).as("step 3: and the primary URL is the shared shape")
            .containsEntry("DATABASE_URL", sharedUrl)
            .containsEntry("DB_URL", sharedUrl);

        // 4. NEGATIVE CONTROL: the same record with its engine's instance gone resolves to
        //    nothing at all -- an address is never guessed from the record's name.
        GeneratedRows.sweeping("test", () -> Models.get(InstanceModel.class).find()
            .where(InstanceModel.GENERATED_FOR_MODEL.eq(DatabaseEngineModel.MODEL_ID.toString()))
            .delete());
        assertThat(DatabaseEnvInjection.envForInstance(applicationId,
                row -> new ManagedDatabase.LiveStatus(ContainerState.RUNNING, 27018)))
            .as("step 4: with no engine instance the shared record contributes nothing")
            .isEmpty();
    }

    /** A shared engine row, planted the way the allocation funnel writes one. */
    private static Row engine(String name, String engineToken) {
        DatabaseEngineModel engines = Models.get(DatabaseEngineModel.class);
        Row row = engines.createEmptyRow();
        row.set(DatabaseEngineModel.NAME, name);
        row.set(DatabaseEngineModel.ENGINE, engineToken);
        row.set(DatabaseEngineModel.ROOT_USER, "root");
        row.set(DatabaseEngineModel.ROOT_PASSWORD, "rootsecret");
        row.set(DatabaseEngineModel.SERVER_ID, ServerModel.localServerId());
        row.set(DatabaseEngineModel.STATUS, DatabaseModel.STATUS_ACTIVE);
        engines.save(row);
        return row;
    }

    /** A shared database record bound to an engine; it owns no instance of its own. */
    private static Integer sharedDatabase(String name, String engineToken, int engineId,
                                          String databaseName) {
        DatabaseModel databases = Models.get(DatabaseModel.class);
        Row row = databases.createEmptyRow();
        row.set(DatabaseModel.NAME, name);
        row.set(DatabaseModel.ENGINE, engineToken);
        row.set(DatabaseModel.DB_USER, "appuser");
        row.set(DatabaseModel.DB_PASSWORD, "s3cret");
        row.set(DatabaseModel.DB_NAME, databaseName);
        row.set(DatabaseModel.STATUS, DatabaseModel.STATUS_ACTIVE);
        row.set(DatabaseModel.SERVER_ID, ServerModel.localServerId());
        row.set(DatabaseModel.PLACEMENT, DatabaseModel.PLACEMENT_SHARED);
        row.set(DatabaseModel.ENGINE_ID, engineId);
        databases.save(row);
        return row.get(DatabaseModel.ID);
    }

    /** A serving release row generated FOR the application, authored in its system scope. */
    private static int release(int applicationId) {
        int[] created = new int[1];
        ApplicationReleases.inScopeUnchecked(applicationId, () -> {
            InstanceModel instances = Models.get(InstanceModel.class);
            Row row = instances.createEmptyRow();
            row.set(InstanceModel.NAME, "inject-release");
            row.set(InstanceModel.KIND, "hohenheim:release");
            row.set(InstanceModel.SETTINGS, new LinkedHashMap<>(Map.of("image", "alpine")));
            row.set(InstanceModel.RUNTIME_ROLE, InstanceModel.ROLE_SERVING);
            instances.save(row);
            created[0] = row.get(InstanceModel.ID);
        });
        return created[0];
    }
}
