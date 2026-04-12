package be.elevenways.hohenheim.server;

import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.hohenheim.migration.M001_CreateUsers;
import be.elevenways.hohenheim.migration.M002_CreateOrganizations;
import be.elevenways.hohenheim.migration.M003_CreateSites;
import be.elevenways.hohenheim.migration.M004_CreateSiteDomains;
import be.elevenways.hohenheim.migration.M005_CreateCertificates;
import be.elevenways.hohenheim.migration.M006_CreateAuditLog;
import be.elevenways.hohenheim.migration.M007_CreateSessions;
import be.elevenways.hohenheim.migration.M008_AddCertificateLifecycleFields;
import be.elevenways.hohenheim.migration.M009_CreateAccessLists;
import be.elevenways.hohenheim.migration.M010_AddDomainLeExclude;
import be.elevenways.hohenheim.migration.M011_CreateProclogs;
import be.elevenways.hohenheim.migration.M012_CreateSystemUsers;
import be.elevenways.hohenheim.migration.M013_CreateNodeVersions;
import be.elevenways.hohenheim.migration.M014_AddSiteSourceFields;
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
                M001_CreateUsers::new,
                M002_CreateOrganizations::new,
                M003_CreateSites::new,
                M004_CreateSiteDomains::new,
                M005_CreateCertificates::new,
                M006_CreateAuditLog::new,
                M007_CreateSessions::new,
                M008_AddCertificateLifecycleFields::new,
                M009_CreateAccessLists::new,
                M010_AddDomainLeExclude::new,
                M011_CreateProclogs::new,
                M012_CreateSystemUsers::new,
                M013_CreateNodeVersions::new,
                M014_AddSiteSourceFields::new
            )
        );
        runner.migrate();
    }

    public static SqliteDatasource datasource() {
        return datasource;
    }
}
