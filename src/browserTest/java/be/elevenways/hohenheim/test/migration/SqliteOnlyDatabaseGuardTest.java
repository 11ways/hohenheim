package be.elevenways.hohenheim.test.migration;

import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.hohenheim.server.HohenheimDatabase;
import be.elevenways.hohenheim.server.database.ControlPlaneBackups;
import be.elevenways.hohenheim.test.TestDatabases;
import be.elevenways.zenit.server.setting.ServerSettings;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Boot refuses any non-SQLite database url, whichever key names it: the route-claim
 * registry's overlap refusal rides SQLite's single-writer transaction serialization, so
 * another engine would silently hand two sites the same hostname instead of merely
 * underperforming. And the url is resolved ONCE, by the framework's precedence (zenit's
 * database.url, else hohenheim's deprecated path/url fallback), for the server and for the
 * restore lane alike.
 */
class SqliteOnlyDatabaseGuardTest {

    @Test
    void bootRefusesNonSqliteEnginesAndResolvesOneUrlForEveryLane() throws Exception {
        String previousUrl = HohenheimSettings.VALUES.getValue(HohenheimSettings.Database.URL);
        String previousZenitUrl = ServerSettings.VALUES.getValue(ServerSettings.Database.URL);
        var datasourceBefore = HohenheimDatabase.datasource();

        try {
            // 1. A PostgreSQL URL in the deprecated hohenheim key is refused with a message
            //    naming the concrete hazard.
            HohenheimSettings.VALUES.setValue(HohenheimSettings.Database.URL,
                "jdbc:postgresql://localhost/hohenheim");
            assertThatThrownBy(HohenheimDatabase::init)
                .as("step 1: a postgres fallback url must be refused at boot")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("POSTGRES")
                .hasMessageContaining("single-writer")
                .hasMessageContaining("RouteClaims");

            // 2. zenit's own database.url is guarded just the same -- it WINS over the
            //    hohenheim fallback, so a guard reading only the hohenheim key would be
            //    bypassed by the key that actually decides.
            HohenheimSettings.VALUES.setValue(HohenheimSettings.Database.URL, "");
            ServerSettings.VALUES.setValue(ServerSettings.Database.URL, "jdbc:mysql://localhost/hohenheim");
            assertThatThrownBy(HohenheimDatabase::init)
                .as("step 2: a mysql database.url must be refused at boot")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("MYSQL")
                .hasMessageContaining("hostname");

            // 3. The guard fired before any datasource was constructed or registered.
            assertThat(HohenheimDatabase.datasource())
                .as("step 3: a refused boot must not have swapped the datasource")
                .isSameAs(datasourceBefore);

            // 4. Precedence, and ONE resolution: a SQLite database.url wins over the
            //    hohenheim path, and the restore lane names exactly that file.
            Path chosen = Files.createTempFile("hohenheim-precedence", ".db");
            Files.deleteIfExists(chosen);
            ServerSettings.VALUES.setValue(ServerSettings.Database.URL, "jdbc:sqlite:" + chosen);
            assertThat(HohenheimDatabase.resolution().url())
                .as("step 4: zenit's database.url wins over hohenheim's database.path")
                .isEqualTo("jdbc:sqlite:" + chosen);
            assertThat(ControlPlaneBackups.databaseFile())
                .as("step 4: the file a control-plane restore replaces is the one the server opens")
                .isEqualTo(chosen);

            // 5. Unset, the deprecated hohenheim path is still honoured as the fallback, so an
            //    upgraded production install opens the same file it always did.
            ServerSettings.VALUES.setValue(ServerSettings.Database.URL, null);
            String path = HohenheimSettings.VALUES.getValue(HohenheimSettings.Database.PATH);
            assertThat(HohenheimDatabase.resolution().url())
                .as("step 5: without database.url the hohenheim path is the url")
                .isEqualTo("jdbc:sqlite:" + path);
            assertThat(ControlPlaneBackups.databaseFile())
                .as("step 5: and the restore lane agrees")
                .isEqualTo(Path.of(path));
        } finally {
            HohenheimSettings.VALUES.setValue(HohenheimSettings.Database.URL, previousUrl);
            ServerSettings.VALUES.setValue(ServerSettings.Database.URL, previousZenitUrl);
        }

        // 6. The SQLite path the tests and production actually use still boots.
        TestDatabases.freshDatabase();
        assertThat(HohenheimDatabase.datasource())
            .as("step 6: a sqlite database boots normally after the refusals")
            .isNotNull()
            .isNotSameAs(datasourceBefore);
    }
}
