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
                M008_AddCertificateLifecycleFields::new
            )
        );
        runner.migrate();
    }

    public static SqliteDatasource datasource() {
        return datasource;
    }
}
