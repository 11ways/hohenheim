package be.elevenways.hohenheim.server;

import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.hohenheim.migration.M003_CreateSites;
import be.elevenways.hohenheim.migration.M004_CreateSiteDomains;
import be.elevenways.hohenheim.migration.M005_CreateCertificates;
import be.elevenways.hohenheim.migration.M006_CreateAuditLog;
import be.elevenways.hohenheim.migration.M008_AddCertificateLifecycleFields;
import be.elevenways.hohenheim.migration.M009_CreateAccessLists;
import be.elevenways.hohenheim.migration.M010_AddDomainLeExclude;
import be.elevenways.hohenheim.migration.M011_CreateProclogs;
import be.elevenways.hohenheim.migration.M012_CreateSystemUsers;
import be.elevenways.hohenheim.migration.M013_CreateNodeVersions;
import be.elevenways.hohenheim.migration.M014_AddSiteSourceFields;
import be.elevenways.hohenheim.migration.M015_CreateManagedDatabases;
import be.elevenways.hohenheim.migration.M016_AddDatabaseStatus;
import be.elevenways.hohenheim.migration.M017_CreateServers;
import be.elevenways.hohenheim.migration.M018_AddDatabaseServer;
import be.elevenways.hohenheim.migration.M019_CreateNotificationChannels;
import be.elevenways.zenit.auth.server.migration.M001_CreateAuthTables;
import be.elevenways.zenit.server.orm.SqliteDatasource;
import be.elevenways.zenit.common.orm.migration.MigrationCapableDatasource;
import be.elevenways.zenit.common.orm.migration.MigrationRunner;

import java.util.List;

/**
 * Database initialization and datasource management.
 */
public class HohenheimDatabase {

    private static SqliteDatasource datasource;

    public static void init() {
        String dbPath = HohenheimSettings.VALUES.getValue(HohenheimSettings.Database.PATH);
        datasource = new SqliteDatasource("jdbc:sqlite:" + dbPath);

        MigrationRunner runner = new MigrationRunner(
            (MigrationCapableDatasource) datasource,
            List.of(
                M003_CreateSites::new,
                M004_CreateSiteDomains::new,
                M005_CreateCertificates::new,
                M006_CreateAuditLog::new,
                M008_AddCertificateLifecycleFields::new,
                M009_CreateAccessLists::new,
                M010_AddDomainLeExclude::new,
                M011_CreateProclogs::new,
                M012_CreateSystemUsers::new,
                M013_CreateNodeVersions::new,
                M014_AddSiteSourceFields::new,
                M015_CreateManagedDatabases::new,
                M016_AddDatabaseStatus::new,
                M017_CreateServers::new,
                M018_AddDatabaseServer::new,
                M019_CreateNotificationChannels::new,
                M001_CreateAuthTables::new   // zenit-auth schema (auth_users, auth_external_identities, ...)
            )
        );
        runner.migrate();
    }

    public static SqliteDatasource datasource() {
        return datasource;
    }
}
