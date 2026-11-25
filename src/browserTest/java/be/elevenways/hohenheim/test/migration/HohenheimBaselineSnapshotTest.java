package be.elevenways.hohenheim.test.migration;

import be.elevenways.hohenheim.migration.M001_HohenheimBaseline;
import be.elevenways.hohenheim.migration.M002_AfterBaseline;
import be.elevenways.hohenheim.migration.M003_CertificateSigningRequests;
import be.elevenways.hohenheim.migration.M004_SiteOutages;
import be.elevenways.hohenheim.migration.M005_LinkedServers;
import be.elevenways.hohenheim.migration.M006_SharedRecords;
import be.elevenways.hohenheim.migration.M007_LinkConfirmations;
import be.elevenways.hohenheim.migration.M008_StreamPortLists;
import be.elevenways.zenit.common.orm.migration.M001_ZenitBaseline;
import be.elevenways.zenit.common.orm.migration.Migration;
import be.elevenways.zenit.common.orm.migration.MigrationKey;
import be.elevenways.zenit.server.orm.SqliteDatasource;
import be.elevenways.zenit.server.orm.migration.MigrationRunner;
import be.elevenways.zenit.test.support.SchemaSnapshot;
import be.elevenways.zenit.test.support.TestSqliteFiles;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The standing squash proof: M001_HohenheimBaseline builds exactly the schema the 001..019 chain it replaced left.
 *
 * AIDEV-NOTE: the chain's classes are deleted, so its schema is PINNED as a SchemaSnapshot rendering
 * ({@value #CHAIN_RESOURCE}): rendered once on 2026-10-21 from a fresh SQLite database the old chain migrated through
 * auto-discovery (hohenheim 718bedc3), kept to the tables the baseline creates so another stream's schema change never
 * touches it. It is history, never regenerated; an appended migration changes neither side. The baseline runs with
 * no other Hohenheim stream; core's baseline runs beside it because the published M006 depends on it, and its tables
 * are left out of the compare (read from a database core's baseline built alone). The published M002 to M008 run with
 * it (a runner admits no chain short of its published migration) and are rolled back through their own down() before
 * the compare.
 *
 * @author Jelle De Loecker
 * @since  0.10.0
 */
class HohenheimBaselineSnapshotTest {

    /** Classpath resource holding the old chain's schema as {@link SchemaSnapshot#render} wrote it. */
    private static final String CHAIN_RESOURCE = "hohenheim-chain-019.schema";

    @Test
    void theBaselineBuildsTheSchemaTheSquashedChainLeft() throws Exception {
        SchemaSnapshot chain;
        try (InputStream stream = getClass().getClassLoader().getResourceAsStream(CHAIN_RESOURCE)) {
            assertThat(stream).as("the pinned chain schema %s is on the test classpath", CHAIN_RESOURCE).isNotNull();
            chain = SchemaSnapshot.read(new String(stream.readAllBytes(), StandardCharsets.UTF_8));
        }

        File db = File.createTempFile("hohenheim-baseline-snapshot", ".db");
        db.delete();
        db.deleteOnExit();
        SqliteDatasource datasource = new SqliteDatasource(TestSqliteFiles.url(db.toPath()));
        List<Supplier<Migration>> migrations = List.of(M001_ZenitBaseline::new, M001_HohenheimBaseline::new,
            M002_AfterBaseline::new, M003_CertificateSigningRequests::new, M004_SiteOutages::new,
            M005_LinkedServers::new, M006_SharedRecords::new, M007_LinkConfirmations::new, M008_StreamPortLists::new);
        new MigrationRunner(datasource, migrations).migrate().requireSuccess();
        new MigrationRunner(datasource, migrations).rollback(MigrationKey.of(new M008_StreamPortLists()))
            .requireSuccess();
        new MigrationRunner(datasource, migrations).rollback(MigrationKey.of(new M007_LinkConfirmations()))
            .requireSuccess();
        new MigrationRunner(datasource, migrations).rollback(MigrationKey.of(new M006_SharedRecords())).requireSuccess();
        new MigrationRunner(datasource, migrations).rollback(MigrationKey.of(new M005_LinkedServers())).requireSuccess();
        new MigrationRunner(datasource, migrations).rollback(MigrationKey.of(new M004_SiteOutages())).requireSuccess();
        new MigrationRunner(datasource, migrations).rollback(MigrationKey.of(new M003_CertificateSigningRequests()))
            .requireSuccess();
        new MigrationRunner(datasource, migrations).rollback(MigrationKey.of(new M002_AfterBaseline()))
            .requireSuccess();

        // The runner's own zenit_ ledgers and core's baseline tables are no part of the stream's schema.
        Set<String> core = coreTables();
        SchemaSnapshot baseline = SchemaSnapshot.of(datasource, table -> !table.startsWith("zenit_")
            && !core.contains(table));
        assertThat(chain.tables()).as("the pin holds the whole stream").hasSize(59);
        SchemaSnapshot.assertSame(chain, baseline, "baseline schema == squashed 001..019 chain schema");
        datasource.close();
    }

    /** @return the tables core's baseline builds on its own */
    private static Set<String> coreTables() throws Exception {
        File db = File.createTempFile("hohenheim-baseline-snapshot-core", ".db");
        db.delete();
        db.deleteOnExit();
        SqliteDatasource datasource = new SqliteDatasource(TestSqliteFiles.url(db.toPath()));
        try {
            new MigrationRunner(datasource, List.<Supplier<Migration>>of(M001_ZenitBaseline::new)).migrate()
                .requireSuccess();
            return SchemaSnapshot.of(datasource, table -> true).tables();
        } finally {
            datasource.close();
        }
    }
}
