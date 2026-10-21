package be.elevenways.hohenheim.test.migration;

import be.elevenways.hohenheim.migration.M001_HohenheimBaseline;
import be.elevenways.zenit.server.orm.SqliteDatasource;
import be.elevenways.zenit.server.orm.migration.MigrationRunner;
import be.elevenways.zenit.test.support.SchemaSnapshot;
import be.elevenways.zenit.test.support.TestSqliteFiles;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The standing squash proof: M001_HohenheimBaseline builds exactly the schema the 001..019 chain it replaced left.
 *
 * AIDEV-NOTE: the chain's classes are deleted, so its schema is PINNED as a SchemaSnapshot rendering
 * ({@value #CHAIN_RESOURCE}): rendered once on 2026-10-21 from a fresh SQLite database the old chain migrated through
 * auto-discovery (hohenheim 718bedc3), kept to the tables the baseline creates so another stream's schema change never
 * touches it. It is history, never regenerated; an appended migration changes neither side. The baseline runs alone,
 * which also proves it depends on no other stream's table.
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
        new MigrationRunner(datasource, List.of(M001_HohenheimBaseline::new)).migrate().requireSuccess();

        // The runner's own zenit_ ledgers are no part of the stream's schema.
        SchemaSnapshot baseline = SchemaSnapshot.of(datasource, table -> !table.startsWith("zenit_"));
        assertThat(chain.tables()).as("the pin holds the whole stream").hasSize(59);
        SchemaSnapshot.assertSame(chain, baseline, "baseline schema == squashed 001..019 chain schema");
        datasource.close();
    }
}
