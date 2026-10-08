package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.model.DatabaseModel;
import be.elevenways.hohenheim.model.InstanceDatabaseModel;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.server.database.DatabaseBackups;
import be.elevenways.hohenheim.test.HardDeletes;
import be.elevenways.hohenheim.test.HohenheimTestBase;
import be.elevenways.protoblast.common.i18n.LocaleChain;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.time.Now;
import be.elevenways.zenit.common.Zenit;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.Models;
import org.junit.jupiter.api.Test;

import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Databases list and a database's overview read as board Databases: where each runs, which apps use it and when it
 * was last backed up (read from the dumps on disk, never a stored claim), and "Back up now" beside them, offered dead
 * where a backup cannot be taken.
 *
 * @author Jelle De Loecker
 * @since  0.9.0
 */
class DatabasesPageJourneyTest extends HohenheimTestBase {

    private static final String PREFIX = "w9a-db-";
    private static final LocaleChain EN = LocaleChain.ofTags("en");
    private static final String OVERDUE = "the nightly backup has not written a newer one";

    @Test
    void aDatabaseSaysWhereItRunsWhoUsesItAndItsLastBackup() throws Exception {
        List<Runnable> cleanup = new ArrayList<>();
        try {
            // 1. A persistent database an app uses, and a temporary one nobody uses.
            Row shop = database(cleanup, "shop", false);
            Row scratch = database(cleanup, "scratch", true);
            Row app = row(cleanup, Models.get(InstanceModel.class), Map.of(InstanceModel.NAME.getName(), PREFIX + "app",
                InstanceModel.KIND.getName(), "hohenheim:docker_container",
                InstanceModel.SETTINGS.getName(), new LinkedHashMap<>(Map.of("image", "alpine")),
                InstanceModel.STATUS.getName(), InstanceModel.STATUS_STOPPED,
                InstanceModel.SERVER_ID.getName(), ServerModel.localServerId()));
            row(cleanup, Models.get(InstanceDatabaseModel.class), Map.of(
                InstanceDatabaseModel.INSTANCE_ID.getName(), app.get(InstanceModel.ID),
                InstanceDatabaseModel.DATABASE_ID.getName(), shop.get(DatabaseModel.ID),
                InstanceDatabaseModel.ENV_PREFIX.getName(), "DB"));
            String host = ServerModel.nameOf(ServerModel.localServerId());

            // 2. Before any dump: where it runs, who uses it, "Never" for the persistent one, and the temporary one
            //    is "Not backed up".
            HttpResponse<String> list = adminGet("/admin/" + DatabaseParts.SLUG);
            assertThat(list.statusCode()).as("step 2: the Databases list renders").isEqualTo(200);
            assertThat(list.body()).as("step 2: where each database runs, under its name")
                .contains("Own container on " + host)
                .as("step 2: the app that uses it").contains(PREFIX + "app")
                .as("step 2: one that nobody uses").contains("No app")
                .as("step 2: a persistent database never dumped").contains("Never")
                .as("step 2: a temporary one is not backed up").contains("Not backed up");
            assertThat(DatabaseParts.backUpUnavailable(shop)).as("step 2: a running database can be backed up now")
                .isNull();
            assertThat(say(DatabaseParts.backUpUnavailable(scratch))).as("step 2: a temporary one cannot")
                .isEqualTo("A temporary database is not backed up.");

            // 3. Three dumps on disk: the list reads the newest, the overview lists them under the retention, and
            //    pruning keeps the newest.
            Path directory = DatabaseBackups.directoryOf(PREFIX + "shop");
            cleanup.add(() -> deleteTree(directory));
            Files.createDirectories(directory);
            Instant now = Now.instant();
            dump(directory, "20261001-030000.sql", 2048, now.minus(2, ChronoUnit.DAYS));
            dump(directory, "20261002-030000.sql", 4096, now.minus(1, ChronoUnit.DAYS));
            dump(directory, "20261003-030000.sql", 18 * 1024 * 1024, now.minus(3, ChronoUnit.HOURS));
            assertThat(DatabaseBackups.stored(PREFIX + "shop")).extracting(DatabaseBackups.Stored::file)
                .as("step 3: the dumps on disk, newest first")
                .containsExactly("20261003-030000.sql", "20261002-030000.sql", "20261001-030000.sql");
            list = adminGet("/admin/" + DatabaseParts.SLUG);
            assertThat(list.body()).as("step 3: the list reads the newest dump's size").contains("18.0 MB")
                .as("step 3: a dump from last night is not overdue").doesNotContain(OVERDUE);

            HttpResponse<String> overview = adminGet("/admin/" + DatabaseParts.SLUG + "/" + shop.get(DatabaseModel.ID)
                + "/page/overview");
            assertThat(overview.statusCode()).as("step 3: the database's overview renders").isEqualTo(200);
            assertThat(overview.body()).as("step 3: the connection names, never the password")
                .contains("shopdb", "shopuser").doesNotContain("shop-secret-password")
                .as("step 3: the app using it links to its page").contains(PREFIX + "app")
                .as("step 3: every dump with its size").contains("18.0 MB", "4.0 KB", "2.0 KB")
                .as("step 3: and how many are kept").contains("dumps, on this machine");

            DatabaseBackups.prune(directory, 2);
            assertThat(DatabaseBackups.stored(PREFIX + "shop")).extracting(DatabaseBackups.Stored::file)
                .as("step 3: pruning keeps the newest two").containsExactly("20261003-030000.sql",
                    "20261002-030000.sql");

            // 4. The front door: a database row opens its overview, and the overview is the landing tab.
            assertThat(list.body()).as("step 4: a row opens the overview")
                .contains("/admin/" + DatabaseParts.SLUG + "/" + shop.get(DatabaseModel.ID) + "/page/overview");

            // 5. The nightly backup stopped: the newest dump is three days old, so the list warns instead of
            //    reading it as done.
            Files.delete(directory.resolve("20261003-030000.sql"));
            Files.delete(directory.resolve("20261002-030000.sql"));
            dump(directory, "20260930-030000.sql", 1024, now.minus(3, ChronoUnit.DAYS));
            list = adminGet("/admin/" + DatabaseParts.SLUG);
            assertThat(list.body()).as("step 5: an old newest dump says the nightly backup stopped")
                .contains("1.0 KB; " + OVERDUE);
        } finally {
            for (int i = cleanup.size() - 1; i >= 0; i--) {
                cleanup.get(i).run();
            }
        }
    }

    private static Row database(List<Runnable> cleanup, String name, boolean ephemeral) {
        return row(cleanup, Models.get(DatabaseModel.class), Map.of(DatabaseModel.NAME.getName(), PREFIX + name,
            DatabaseModel.ENGINE.getName(), "mysql", DatabaseModel.PLACEMENT.getName(), DatabaseModel.PLACEMENT_DEDICATED,
            DatabaseModel.DB_NAME.getName(), name + "db", DatabaseModel.DB_USER.getName(), name + "user",
            DatabaseModel.DB_PASSWORD.getName(), name + "-secret-password", DatabaseModel.EPHEMERAL.getName(), ephemeral,
            DatabaseModel.STATUS.getName(), DatabaseModel.STATUS_ACTIVE));
    }

    private static Row row(List<Runnable> cleanup, Model model, Map<String, Object> values) {
        Row row = model.createEmptyRow();
        values.forEach(row::set);
        model.save(row);
        cleanup.add(() -> HardDeletes.row(model, row));
        return row;
    }

    private static void dump(Path directory, String file, int bytes, Instant at) throws Exception {
        Path path = directory.resolve(file);
        Files.write(path, new byte[bytes]);
        Files.setLastModifiedTime(path, FileTime.from(at));
    }

    private static void deleteTree(Path directory) {
        try (Stream<Path> files = Files.list(directory)) {
            for (Path file : files.toList()) {
                Files.deleteIfExists(file);
            }
            Files.deleteIfExists(directory);
        } catch (Exception gone) {
            // Nothing left to remove.
        }
    }

    private static String say(Microcopy copy) {
        return copy == null ? "" : copy.resolve(EN, Zenit.getMessageResolver());
    }
}
