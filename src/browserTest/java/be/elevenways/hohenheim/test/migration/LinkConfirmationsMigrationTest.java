package be.elevenways.hohenheim.test.migration;

import be.elevenways.hohenheim.migration.M007_LinkConfirmations;
import be.elevenways.zenit.common.orm.migration.MigrationKey;
import be.elevenways.zenit.server.orm.SqliteDatasource;
import be.elevenways.zenit.server.orm.migration.MigrationRunner;
import be.elevenways.zenit.test.support.TestSqliteFiles;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.List;

import static be.elevenways.hohenheim.test.migration.InstallsAt.withIntegrityMode;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The live upgrade from the published M006 adds when each link was confirmed and confirms every link already made at
 * the moment it was made, so no joining side undoes a link both sides made before; its rollback drops only the column.
 *
 * @author Jelle De Loecker
 * @since  0.10.0
 */
class LinkConfirmationsMigrationTest {

    private static final String MADE = "2026-11-24T02:48:11.123456Z";

    private static final String COLUMNS = "SELECT name FROM pragma_table_info('linked_servers')";

    @Test
    void theUpgradeConfirmsEveryLinkMadeBeforeAndTheRollbackDropsOnlyTheColumn() throws Exception {
        String version = new M007_LinkConfirmations().getVersion();
        File db = File.createTempFile("hohenheim-link-confirmations", ".db");
        db.delete();
        db.deleteOnExit();

        // 1. A published install holds M001 to M006 as the live hosts applied them, and two links: one as every link
        //    is stored, and one that never recorded when it was made.
        InstallsAt.migrateBelow(db.toPath(), version);
        SqliteDatasource datasource = new SqliteDatasource(TestSqliteFiles.url(db.toPath()));
        try {
            datasource.rawUpdate("INSERT INTO linked_servers (id, name, role, base_url, created_at) VALUES"
                + " ('0193a8b0-0000-7000-8000-000000000001', 'robbedoes', 'master', 'https://robbedoes.example',"
                + " '" + MADE + "')");
            datasource.rawUpdate("INSERT INTO linked_servers (id, name, role, base_url) VALUES"
                + " ('0193a8b0-0000-7000-8000-000000000002', 'kuifje', 'managed', 'https://kuifje.example')");
            List<String> before = columnsOf(datasource);
            assertThat(before).as("step 1: the published schema has no confirmation yet")
                .doesNotContain("confirmed_at");

            // 2. The upgrade, under the strict integrity posture the hosts run, applies M007 alone.
            withIntegrityMode("fail", () -> assertThat(new MigrationRunner(datasource).migrate().requireSuccess()
                .getAppliedCount()).as("step 2: the upgrade applies M007 alone").isEqualTo(1));

            // 3. Every link is confirmed: at the moment it was made, or at the upgrade when it recorded none.
            assertThat(columnsOf(datasource)).as("step 3: the column exists").contains("confirmed_at")
                .hasSize(before.size() + 1);
            assertThat(confirmedAt(datasource, 1)).as("step 3: confirmed when it was made").isEqualTo(MADE);
            assertThat(datasource.rawQuery("SELECT created_at FROM linked_servers WHERE id ="
                + " '0193a8b0-0000-7000-8000-000000000001'").get(0).get("created_at"))
                .as("step 3: the moment it was made is kept").isEqualTo(MADE);
            assertThat(confirmedAt(datasource, 2)).as("step 3: a link without a creation moment is confirmed too")
                .isNotNull();

            // 4. The rollback drops the column and keeps both links.
            new MigrationRunner(datasource).rollback(List.of(MigrationKey.of(new M007_LinkConfirmations())))
                .requireSuccess();
            assertThat(columnsOf(datasource)).as("step 4: the table is as it was").containsExactlyElementsOf(before);
            assertThat(((Number) datasource.rawQuery("SELECT COUNT(*) AS c FROM linked_servers").get(0).get("c"))
                .longValue()).as("step 4: both links survive").isEqualTo(2L);
        } finally {
            datasource.close();
        }
    }

    private static List<String> columnsOf(SqliteDatasource datasource) {
        return datasource.rawQuery(COLUMNS).stream().map(row -> String.valueOf(row.get("name"))).toList();
    }

    private static Object confirmedAt(SqliteDatasource datasource, int link) {
        return datasource.rawQuery("SELECT confirmed_at FROM linked_servers WHERE id ="
            + " '0193a8b0-0000-7000-8000-00000000000" + link + "'").get(0).get("confirmed_at");
    }
}
