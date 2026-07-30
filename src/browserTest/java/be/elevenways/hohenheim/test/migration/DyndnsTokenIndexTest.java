package be.elevenways.hohenheim.test.migration;

import be.elevenways.hohenheim.migration.M046_DyndnsTokenIndex;
import be.elevenways.zenit.common.orm.OrmConstants;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.migration.Migration;
import be.elevenways.zenit.common.orm.migration.MigrationBuilder;
import be.elevenways.zenit.common.orm.migration.operation.AddIndexOperation;
import be.elevenways.zenit.common.orm.migration.operation.MigrationOperation;
import be.elevenways.zenit.server.orm.SqliteDatasource;
import be.elevenways.zenit.server.orm.migration.MigrationRunner;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The dyndns token lookup is an AUTHENTICATION path that runs on every router poll, so
 * the digest column must be indexed rather than fully scanned. This journey pins the
 * declared index name (up() and down() must be able to name the same object), the index
 * the backend actually stores, and -- the point of the whole thing -- that SQLite's
 * planner really uses it instead of scanning the table.
 *
 * @author Jelle De Loecker
 */
class DyndnsTokenIndexTest {

    /** The name M046 declares. A drift here breaks the migration's own down(). */
    private static final String INDEX_NAME = "dns_records_dyndns_token_index";

    @Test
    void theDyndnsTokenLookupRidesADeclaredIndex() throws Exception {
        SqliteDatasource datasource = emptyDatabase("dyndns-index");

        // 1. The migration DECLARES the index under an explicit name, on the digest
        //    column, with no database involved. An explicit name (not a derived one) is
        //    what keeps it inside zenit's 63-character index-name guard.
        MigrationBuilder recorded = new MigrationBuilder();
        Migration migration = new M046_DyndnsTokenIndex();
        migration.up(recorded);
        AddIndexOperation declared = null;
        for (MigrationOperation operation : recorded.getOperations()) {
            if (operation instanceof AddIndexOperation index) {
                declared = index;
            }
        }
        assertThat(declared).as("step 1: M046 must record an AddIndex operation").isNotNull();
        assertThat(declared.getIndexName())
            .as("step 1: the index must carry the name down() drops")
            .isEqualTo(INDEX_NAME);
        assertThat(declared.getColumnNames())
            .as("step 1: the index must cover the digest column the lookup filters on")
            .isEqualTo(List.of("dyndns_token"));
        assertThat(INDEX_NAME.length())
            .as("step 1: the name must fit the backend index-name limit")
            .isLessThanOrEqualTo(OrmConstants.MAX_INDEX_NAME_LENGTH);

        // 2. A normal full migrate really stores an index under that literal name. Before
        //    M046 existed the whole column was unindexed and this row was absent.
        new MigrationRunner(datasource).migrate().requireSuccess();
        List<Row> stored = datasource.rawQuery(
            "SELECT sql AS def FROM sqlite_master WHERE type = ? AND name = ?", "index", INDEX_NAME);
        assertThat(stored)
            .as("step 2: sqlite_master must carry an index named " + INDEX_NAME)
            .hasSize(1);
        assertThat(String.valueOf(stored.get(0).get("def")))
            .as("step 2: the stored index must cover dns_records.dyndns_token")
            .contains("dns_records")
            .contains("dyndns_token");

        // 3. The planner USES it: the query the auth path runs must resolve through the
        //    index, not "SCAN dns_records". This is the assertion that would have failed
        //    before the fix even if an index had merely been declared somewhere.
        String plan = queryPlan(datasource,
            "SELECT id FROM dns_records WHERE dyndns_token = ?", "sha256:whatever");
        assertThat(plan)
            .as("step 3: the token lookup must use the index, plan was: " + plan)
            .contains(INDEX_NAME);
        assertThat(plan)
            .as("step 3: the token lookup must not scan the table, plan was: " + plan)
            .doesNotContain("SCAN dns_records");
    }

    /** SQLite's own plan text for a statement, joined into one line. */
    private static String queryPlan(SqliteDatasource datasource, String sql, Object... params) {
        StringBuilder plan = new StringBuilder();
        for (Row row : datasource.rawQuery("EXPLAIN QUERY PLAN " + sql, params)) {
            if (!plan.isEmpty()) {
                plan.append(" | ");
            }
            plan.append(row.get("detail"));
        }
        return plan.toString();
    }

    private static SqliteDatasource emptyDatabase(String label) throws Exception {
        File db = File.createTempFile("hohenheim-" + label, ".db");
        db.delete();
        db.deleteOnExit();
        return new SqliteDatasource("jdbc:sqlite:" + db.getAbsolutePath());
    }
}
