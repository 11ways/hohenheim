package be.elevenways.hohenheim.test.database;

import be.elevenways.hohenheim.server.ControllerScope;
import be.elevenways.hohenheim.server.runtime.ContainerState;
import be.elevenways.hohenheim.test.TestDatabases;
import be.elevenways.hohenheim.HohenheimEndpoints;
import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.hohenheim.model.DatabaseModel;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.model.SiteDatabaseModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.server.HohenheimDatabase;
import be.elevenways.hohenheim.server.database.DatabaseEnvInjection;
import be.elevenways.hohenheim.server.orm.GeneratedRows;
import be.elevenways.hohenheim.server.database.ManagedDatabase;
import be.elevenways.hohenheim.server.upstream.UpstreamKindHandlers;
import be.elevenways.hohenheim.test.HohenheimTestRuntime;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The env-injection resolver contract: prefixed variable families per attached database,
 * DATABASE_URL pinned to the FIRST link, unavailable databases contributing nothing, and
 * URL-encoded credentials. Live ports are stubbed -- no Docker needed.
 */
class DatabaseEnvInjectionTest {

    @BeforeAll
    static void boot() throws Exception {
        UpstreamKindHandlers.boot();
        HohenheimEndpoints.init();
        TestDatabases.freshDatabase();
        HohenheimTestRuntime.ensureBooted();
    }

    @AfterEach
    void cleanRows() {
        // Generated rows are read-only outside a system scope, fixtures included -- the
        // sweeping scope is the lane that exists for "the declaring record is going away".
        GeneratedRows.sweeping("test", () -> Models.get(InstanceModel.class).find().delete());
        Models.get(SiteDatabaseModel.class).find().delete();
        Models.get(DatabaseModel.class).find().delete();
        Models.get(SiteModel.class).find().delete();
    }

    private static Integer site(String name) {
        SiteModel sites = Models.get(SiteModel.class);
        Row site = sites.createEmptyRow();
        site.set(SiteModel.NAME, name);
        site.set(SiteModel.SLUG, name);
        site.set(SiteModel.UPSTREAM_KIND, "hohenheim:static");
        site.set(SiteModel.ENABLED, true);
        sites.save(site);
        return site.get(SiteModel.ID);
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

    private static void link(Integer siteId, Integer databaseId, String prefix) {
        SiteDatabaseModel links = Models.get(SiteDatabaseModel.class);
        Row link = links.createEmptyRow();
        link.set(SiteDatabaseModel.SITE_ID, siteId);
        link.set(SiteDatabaseModel.DATABASE_ID, databaseId);
        link.set(SiteDatabaseModel.ENV_PREFIX, prefix);
        links.save(link);
    }

    @Test
    void attachedDatabasesResolveToPrefixedFamiliesWithPrimaryUrl() {
        Integer siteId = site("inject-two");
        link(siteId, database("maindb", "postgres", DatabaseModel.STATUS_ACTIVE), "DB");
        link(siteId, database("cachedb", "redis", DatabaseModel.STATUS_ACTIVE), "CACHE");

        Map<String, String> env = DatabaseEnvInjection.envForSite(siteId,
            row -> new ManagedDatabase.LiveStatus(ContainerState.RUNNING,
                "maindb".equals(row.get(DatabaseModel.NAME)) ? 5544 : 6380));

        String pgUrl = "postgres://appuser:s3cret@127.0.0.1:5544/appdb";
        assertThat(env).containsEntry("DB_HOST", "127.0.0.1");
        assertThat(env).containsEntry("DB_PORT", "5544");
        assertThat(env).containsEntry("DB_USER", "appuser");
        assertThat(env).containsEntry("DB_PASSWORD", "s3cret");
        assertThat(env).containsEntry("DB_NAME", "appdb");
        assertThat(env).containsEntry("DB_URL", pgUrl);
        assertThat(env).containsEntry("DATABASE_URL", pgUrl);
        assertThat(env).containsEntry("CACHE_URL", "redis://:s3cret@127.0.0.1:6380");
        assertThat(env).containsEntry("CACHE_PORT", "6380");
    }

    @Test
    void unavailableDatabaseContributesNothingAndNeverReassignsPrimaryUrl() {
        Integer siteId = site("inject-down");
        link(siteId, database("downdb", "postgres", DatabaseModel.STATUS_ACTIVE), "DB");
        link(siteId, database("updb", "mysql", DatabaseModel.STATUS_ACTIVE), "SECOND");

        // The primary (first) link's container is stopped; the second resolves.
        Map<String, String> env = DatabaseEnvInjection.envForSite(siteId,
            row -> "downdb".equals(row.get(DatabaseModel.NAME))
                ? new ManagedDatabase.LiveStatus(ContainerState.STOPPED, null)
                : new ManagedDatabase.LiveStatus(ContainerState.RUNNING, 3311));

        assertThat(env).doesNotContainKey("DB_HOST");
        assertThat(env).doesNotContainKey("DB_URL");
        // DATABASE_URL belongs to the first link; it must not silently point elsewhere.
        assertThat(env).doesNotContainKey("DATABASE_URL");
        assertThat(env).containsEntry("SECOND_URL", "mysql://appuser:s3cret@127.0.0.1:3311/appdb");
    }

    @Test
    void nonActiveRecordsAndFailedResolutionDegradeToNoVariables() {
        Integer siteId = site("inject-failed");
        link(siteId, database("faileddb", "postgres", DatabaseModel.STATUS_FAILED), "DB");

        Map<String, String> env = DatabaseEnvInjection.envForSite(siteId,
            row -> new ManagedDatabase.LiveStatus(ContainerState.RUNNING, 5544));
        assertThat(env).isEmpty();

        // A site with no links resolves to nothing without touching the resolver.
        assertThat(DatabaseEnvInjection.envForSite(site("inject-bare"), row -> {
            throw new AssertionError("resolver must not run without links");
        })).isEmpty();
    }

    @Test
    void containerNetworkStyleUsesContainerHostnameAndEnginePort() {
        Integer siteId = site("inject-container");
        link(siteId, database("containerdb", "postgres", DatabaseModel.STATUS_ACTIVE), "DB");
        link(siteId, database("containercache", "redis", DatabaseModel.STATUS_ACTIVE), "CACHE");

        // 1. The container style resolves the DB container hostname + the engine's own
        //    port -- the published loopback port must appear NOWHERE, because inside a
        //    site container 127.0.0.1 is the container itself.
        Map<String, String> env = DatabaseEnvInjection.envForSite(siteId,
            row -> new ManagedDatabase.LiveStatus(ContainerState.RUNNING, 5544),
            DatabaseEnvInjection.Style.CONTAINER_NETWORK);
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

        // 2. A running engine with no published port yet still resolves in container
        //    style (it does not NEED the published port), while the loopback style
        //    correctly refuses the same state.
        Map<String, String> unpublished = DatabaseEnvInjection.envForSite(siteId,
            row -> new ManagedDatabase.LiveStatus(ContainerState.RUNNING, null),
            DatabaseEnvInjection.Style.CONTAINER_NETWORK);
        assertThat(unpublished).as("step 2: container style needs no published port")
            .containsEntry("DB_HOST", EngineHandles.of("containerdb"));
        assertThat(DatabaseEnvInjection.envForSite(siteId,
            row -> new ManagedDatabase.LiveStatus(ContainerState.RUNNING, null)))
            .as("step 2: the loopback style still refuses a portless container").isEmpty();

        // 3. A stopped engine contributes nothing in EITHER style: credentials for a
        //    dead database are the silent-success shape.
        assertThat(DatabaseEnvInjection.envForSite(siteId,
            row -> new ManagedDatabase.LiveStatus(ContainerState.STOPPED, null),
            DatabaseEnvInjection.Style.CONTAINER_NETWORK))
            .as("step 3: a stopped database contributes no variables").isEmpty();
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
}
