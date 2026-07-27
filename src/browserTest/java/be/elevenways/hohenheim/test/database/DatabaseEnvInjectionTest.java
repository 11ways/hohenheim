package be.elevenways.hohenheim.test.database;

import be.elevenways.hohenheim.test.TestDatabases;
import be.elevenways.hohenheim.HohenheimEndpoints;
import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.hohenheim.model.DatabaseModel;
import be.elevenways.hohenheim.model.SiteDatabaseModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.server.HohenheimDatabase;
import be.elevenways.hohenheim.server.database.DatabaseEnvInjection;
import be.elevenways.hohenheim.server.database.ManagedDatabase;
import be.elevenways.hohenheim.server.sitetype.SiteTypes;
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
        SiteTypes.boot();
        HohenheimEndpoints.init();
        TestDatabases.freshDatabase();
        HohenheimTestRuntime.ensureBooted();
    }

    @AfterEach
    void cleanRows() {
        Models.get(SiteDatabaseModel.class).find().delete();
        Models.get(DatabaseModel.class).find().delete();
        Models.get(SiteModel.class).find().delete();
    }

    private static Integer site(String name) {
        SiteModel sites = Models.get(SiteModel.class);
        Row site = sites.createEmptyRow();
        site.set(SiteModel.NAME, name);
        site.set(SiteModel.SLUG, name);
        site.set(SiteModel.SITE_TYPE, "hohenheim:node");
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
        row.set(DatabaseModel.SERVER_NAME, "local");
        databases.save(row);
        return row.get(DatabaseModel.ID);
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
            row -> new ManagedDatabase.LiveStatus(true,
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
                ? new ManagedDatabase.LiveStatus(false, null)
                : new ManagedDatabase.LiveStatus(true, 3311));

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
            row -> new ManagedDatabase.LiveStatus(true, 5544));
        assertThat(env).isEmpty();

        // A site with no links resolves to nothing without touching the resolver.
        assertThat(DatabaseEnvInjection.envForSite(site("inject-bare"), row -> {
            throw new AssertionError("resolver must not run without links");
        })).isEmpty();
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
