package be.elevenways.hohenheim.server;

import be.elevenways.hohenheim.migration.*;
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
        datasource = new SqliteDatasource("jdbc:sqlite:hohenheim.db");

        MigrationRunner runner = new MigrationRunner(
            (MigrationCapableDatasource) datasource,
            List.of(
                M001_CreateUsers::new,
                M002_CreateOrganizations::new,
                M003_CreateSites::new,
                M004_CreateSiteDomains::new,
                M005_CreateCertificates::new,
                M006_CreateAuditLog::new
            )
        );
        runner.migrate();
    }

    public static SqliteDatasource datasource() {
        return datasource;
    }
}
