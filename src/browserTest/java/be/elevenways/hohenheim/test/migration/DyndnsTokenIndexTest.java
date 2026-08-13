package be.elevenways.hohenheim.test.migration;

import be.elevenways.zenit.common.orm.datasource.Datasources;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.server.orm.SqliteDatasource;
import be.elevenways.zenit.server.orm.migration.MigrationRunner;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The dyndns token lookup is an AUTHENTICATION path that runs on every router poll, so
 * the digest column must be indexed rather than fully scanned. The digest lives in its
 * own {@code dns_dyndns_credentials} table: this journey pins that the migrated schema
 * stores an index over its {@code token_digest} column and that SQLite's planner really
 * uses it.
 *
 * @author Jelle De Loecker
 */
class DyndnsTokenIndexTest {

    @Test
    void theDyndnsTokenLookupRidesADeclaredIndex() throws Exception {
        SqliteDatasource datasource = emptyDatabase("dyndns-index");

        // 1. A normal full migrate stores an index over dns_dyndns_credentials.token_digest.
        new MigrationRunner(datasource).migrate().requireSuccess();
        // ONE database per test class: the controller identity (and therefore every
        // daemon resource name) resolves through the CURRENT datasource, and a Db scope
        // is thread-local -- so a second, unregistered database would hand any
        // thread-hopping work a different controller's token than the records came from.
        Datasources.register(Datasources.DEFAULT, datasource);
        List<Row> stored = datasource.rawQuery(
            "SELECT name AS n, sql AS def FROM sqlite_master"
            + " WHERE type = ? AND tbl_name = ? AND sql LIKE ?",
            "index", "dns_dyndns_credentials", "%token_digest%");
        assertThat(stored)
            .as("step 1: the credential table must carry an index over token_digest")
            .isNotEmpty();

        // 2. The planner USES it: the query the auth path runs must resolve through the
        //    index, not "SCAN dns_dyndns_credentials".
        String plan = queryPlan(datasource,
            "SELECT record_id FROM dns_dyndns_credentials WHERE token_digest = ?",
            "sha256:whatever");
        assertThat(plan)
            .as("step 2: the token lookup must not scan the table, plan was: " + plan)
            .doesNotContain("SCAN dns_dyndns_credentials");
        assertThat(plan)
            .as("step 2: the token lookup must ride an index, plan was: " + plan)
            .contains("USING INDEX");
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
