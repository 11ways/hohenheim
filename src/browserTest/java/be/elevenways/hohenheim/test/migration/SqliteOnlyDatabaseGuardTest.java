package be.elevenways.hohenheim.test.migration;

import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.hohenheim.server.HohenheimDatabase;
import be.elevenways.hohenheim.test.TestDatabases;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Boot refuses any non-SQLite database.url/engine: the migration history contains
 * SQLite-only raw SQL (M025 json_type, M043 || concatenation), so another engine
 * would fail or silently corrupt data instead of merely underperforming.
 */
class SqliteOnlyDatabaseGuardTest {

    @Test
    void bootRefusesNonSqliteEnginesBeforeTouchingAnything() throws Exception {
        String previousUrl = HohenheimSettings.VALUES.getValue(HohenheimSettings.Database.URL);
        String previousEngine = HohenheimSettings.VALUES.getValue(HohenheimSettings.Database.ENGINE);
        var datasourceBefore = HohenheimDatabase.datasource();

        try {
            // 1. A PostgreSQL URL is refused with a message naming the concrete hazard.
            HohenheimSettings.VALUES.setValue(HohenheimSettings.Database.URL,
                "jdbc:postgresql://localhost/hohenheim");
            assertThatThrownBy(HohenheimDatabase::init)
                .as("a postgres database.url must be refused at boot")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("POSTGRES")
                .hasMessageContaining("SQLite-only raw SQL")
                .hasMessageContaining("M043");

            // 2. MySQL, the engine where || silently corrupts names, is refused the same way.
            HohenheimSettings.VALUES.setValue(HohenheimSettings.Database.URL,
                "jdbc:mysql://localhost/hohenheim");
            assertThatThrownBy(HohenheimDatabase::init)
                .as("a mysql database.url must be refused at boot")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("MYSQL")
                .hasMessageContaining("corrupt");

            // 3. An explicit engine override cannot smuggle a non-SQLite engine past the guard.
            HohenheimSettings.VALUES.setValue(HohenheimSettings.Database.URL, "");
            HohenheimSettings.VALUES.setValue(HohenheimSettings.Database.ENGINE, "cockroach");
            assertThatThrownBy(HohenheimDatabase::init)
                .as("an explicit non-sqlite database.engine must be refused at boot")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("COCKROACH");

            // 4. The guard fired before any datasource was constructed or registered.
            assertThat(HohenheimDatabase.datasource())
                .as("a refused boot must not have swapped the datasource")
                .isSameAs(datasourceBefore);
        } finally {
            HohenheimSettings.VALUES.setValue(HohenheimSettings.Database.URL, previousUrl);
            HohenheimSettings.VALUES.setValue(HohenheimSettings.Database.ENGINE, previousEngine);
        }

        // 5. The SQLite path the tests and production actually use still boots.
        TestDatabases.freshDatabase();
        assertThat(HohenheimDatabase.datasource())
            .as("a sqlite database boots normally after the refusals")
            .isNotNull()
            .isNotSameAs(datasourceBefore);
    }
}
