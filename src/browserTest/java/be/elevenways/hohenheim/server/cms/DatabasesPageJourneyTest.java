package be.elevenways.hohenheim.server.cms;

import be.elevenways.zenit.cms.common.resource.RecordOverview;
import be.elevenways.hohenheim.AttentionItem;
import be.elevenways.hohenheim.AttentionSubject;
import be.elevenways.hohenheim.HohenheimSlugs;
import be.elevenways.hohenheim.WorkloadTier;
import be.elevenways.hohenheim.app.DashboardStat;
import be.elevenways.hohenheim.host.WorkloadView;
import be.elevenways.hohenheim.model.DatabaseEngineModel;
import be.elevenways.hohenheim.model.DatabaseModel;
import be.elevenways.hohenheim.model.InstanceDatabaseModel;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.server.database.DatabaseBackups;
import be.elevenways.hohenheim.server.database.DatabaseEngines;
import be.elevenways.hohenheim.server.database.DatabaseInstances;
import be.elevenways.hohenheim.server.instance.OwnedInstances;
import be.elevenways.hohenheim.test.database.EngineHandles;
import be.elevenways.hohenheim.test.HardDeletes;
import be.elevenways.hohenheim.test.HohenheimTestBase;
import be.elevenways.hohenheim.test.TenantConduits;
import be.elevenways.protoblast.common.i18n.LocaleChain;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.time.Now;
import be.elevenways.zenit.common.Zenit;
import be.elevenways.zenit.cms.common.action.ActionStyle;
import be.elevenways.zenit.cms.common.action.PanelAction;
import be.elevenways.zenit.cms.common.panel.Panel;
import be.elevenways.zenit.cms.common.panel.PanelRegistry;
import be.elevenways.zenit.common.security.AccessContext;
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
import java.util.Objects;
import java.util.regex.Pattern;
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
            // 1. A persistent database an app uses, its engine running, and a temporary one nobody uses.
            Row shop = database(cleanup, "shop", false);
            plantEngine(cleanup, shop, InstanceModel.STATUS_RUNNING);
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
            HttpResponse<String> list = adminGet("/admin/" + HohenheimSlugs.DATABASES);
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
            list = adminGet("/admin/" + HohenheimSlugs.DATABASES);
            assertThat(list.body()).as("step 3: the list reads the newest dump's size").contains("18.0 MB")
                .as("step 3: a dump from last night is not overdue").doesNotContain(OVERDUE);

            HttpResponse<String> overview = adminGet("/admin/" + HohenheimSlugs.DATABASES + "/" + shop
                .get(DatabaseModel.ID)
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
                .contains("/admin/" + HohenheimSlugs.DATABASES + "/" + shop.get(DatabaseModel.ID) + "/page/overview");

            // 5. The nightly backup stopped: the newest dump is three days old, so the list warns instead of
            //    reading it as done.
            Files.delete(directory.resolve("20261003-030000.sql"));
            Files.delete(directory.resolve("20261002-030000.sql"));
            dump(directory, "20260930-030000.sql", 1024, now.minus(3, ChronoUnit.DAYS));
            list = adminGet("/admin/" + HohenheimSlugs.DATABASES);
            assertThat(list.body()).as("step 5: an old newest dump says the nightly backup stopped")
                .contains("1.0 KB; " + OVERDUE);

            // 6. Back up now leads the heading by position; the once-in-a-lifetime move to a shared engine is neither
            //    the primary button nor in the heading row, only in its More menu.
            List<PanelAction<Row>> actions = DatabaseParts.admin().actions();
            assertThat(actions.get(0).id()).as("step 6: Back up now comes first")
                .isEqualTo(DatabaseParts.BACK_UP_NOW.id());
            PanelAction<Row> move = actions.stream()
                .filter(action -> action.id().equals(DatabaseParts.MOVE_TO_SHARED.id())).findFirst().orElseThrow();
            assertThat(move.style()).as("step 6: the move is not the primary action").isEqualTo(ActionStyle.DEFAULT);
            assertThat(move.inlineOnRecord()).as("step 6: and waits in the heading's More menu").isFalse();

            // 7. The board's words around the rows: the lead, the create button, and Used by linking each app to
            //    its overview, the overview card's own link.
            list = adminGet("/admin/" + HohenheimSlugs.DATABASES);
            assertThat(list.body()).as("step 7: the list's lead in the board's words")
                .contains("Managed databases your apps use, with their backups.")
                .as("step 7: the header button reads as the board's")
                .containsPattern(Pattern.compile("data-cms-create[^>]*>.{0,1000}?Create database", Pattern.DOTALL));
            String appOverview = InstanceParts.recordRoute("admin", app, null).toUrl();
            assertThat(appOverview).as("step 7: the app's front door is its overview")
                .isEqualTo("/admin/" + HohenheimSlugs.INSTANCES + "/" + app.get(InstanceModel.ID) + "/page/"
                    + RecordOverview.SLUG);
            assertThat(list.body()).as("step 7: Used by links the app to that overview")
                .contains("class=\"cms-record-link\" href=\"" + appOverview + "\">" + PREFIX + "app</a>");

            // 8. The Engines card under the list (board Databases): each shared engine by its kind and host, how
            //    many databases it holds and its state, opening the engine; after the rows, never above them.
            Row engine = row(cleanup, Models.get(DatabaseEngineModel.class), Map.of(
                DatabaseEngineModel.NAME.getName(), PREFIX + "engine", DatabaseEngineModel.ENGINE.getName(), "mysql",
                DatabaseEngineModel.ROOT_USER.getName(), "root", DatabaseEngineModel.ROOT_PASSWORD.getName(), "rootpw",
                DatabaseEngineModel.SERVER_ID.getName(), ServerModel.localServerId(),
                DatabaseEngineModel.STATUS.getName(), DatabaseModel.STATUS_ACTIVE));
            row(cleanup, Models.get(DatabaseModel.class), Map.of(DatabaseModel.NAME.getName(), PREFIX + "shared",
                DatabaseModel.ENGINE.getName(), "mysql", DatabaseModel.PLACEMENT.getName(),
                DatabaseModel.PLACEMENT_SHARED, DatabaseModel.ENGINE_ID.getName(), engine.get(DatabaseEngineModel.ID),
                DatabaseModel.DB_NAME.getName(), "shareddb", DatabaseModel.DB_USER.getName(), "shareduser",
                DatabaseModel.DB_PASSWORD.getName(), "shared-secret-password",
                DatabaseModel.SERVER_ID.getName(), ServerModel.localServerId(),
                DatabaseModel.STATUS.getName(), DatabaseModel.STATUS_ACTIVE));
            String withEngine = adminGet("/admin/" + HohenheimSlugs.DATABASES).body();
            int card = withEngine.indexOf("data-cms-list-card");
            int engines = withEngine.indexOf("data-cms-list-widgets-below");
            assertThat(engines).as("step 8: the Engines card renders").isPositive();
            assertThat(engines).as("step 8: under the list, as the board draws it").isGreaterThan(card);
            String below = withEngine.substring(engines);
            assertThat(below).as("step 8: titled and explained in the board's words")
                .contains("Engines").contains("Shared engines hold many databases, each with its own user.")
                .as("step 8: the engine by its kind and host").contains("MySQL on " + host)
                .as("step 8: how many databases it holds and what its engine does, never the stored \"active\"")
                .contains("1 database, not running")
                .as("step 8: opening the engine")
                .contains("/admin/" + HohenheimSlugs.DATABASE_ENGINES + "/" + engine.get(DatabaseEngineModel.ID)
                + "/open");
        } finally {
            for (int i = cleanup.size() - 1; i >= 0; i--) {
                cleanup.get(i).run();
            }
        }
    }

    /**
     * One stopped shared database two apps use is ONE problem, at its root, and every surface reads the same verdict
     * (D12): the database's item names the apps it holds back, theirs fold under it, and its list cell, the Engines
     * card, an app's Databases tab and Back up now all say what its engine does.
     */
    @Test
    void aDatabaseDumpIsABackupTheDashboardCounts() throws Exception {
        List<Runnable> cleanup = new ArrayList<>();
        try {
            // 1. Whatever the shared database already holds is the baseline; a persistent database without a dump
            //    is one more thing that should be backed up and is not (the Databases list's "Never").
            int[] before = backupsTile();
            Row kept = database(cleanup, "d13-kept", false);
            int[] missing = backupsTile();
            assertThat(missing).as("step 1: the database counts, not yet backed up")
                .containsExactly(before[0], before[1] + 1);
            assertThat(DatabaseParts.backupOf(kept).state()).as("step 1: the list's own verdict says never")
                .isEqualTo(DatabaseParts.BackupState.NEVER);

            // 2. Its nightly dump lands: the tile counts it backed up, by the same verdict the Last backup cell reads,
            //    and names it as the newest copy (DEP10: "0, No app has a backup target" beside a 16-hour-old dump).
            Path directory = DatabaseBackups.directoryOf(PREFIX + "d13-kept");
            cleanup.add(() -> deleteTree(directory));
            Files.createDirectories(directory);
            dump(directory, "20261008-030000.sql", 18 * 1024 * 1024, Now.instant().minus(16, ChronoUnit.HOURS));
            assertThat(DatabaseParts.backupOf(kept).state()).as("step 2: a dump from last night is done")
                .isEqualTo(DatabaseParts.BackupState.DONE);
            assertThat(backupsTile()).as("step 2: the tile counts it backed up")
                .containsExactly(before[0] + 1, before[1] + 1);
            DashboardStat tile = backupsStat();
            assertThat(tile.detail()).as("step 2: and names the newest copy with its size")
                .startsWith("Newest ").endsWith(", 18.0 MB");

            // 3. A dump older than the nightly allows is not a good backup: counted, not backed up.
            dump(directory, "20261008-030000.sql", 18 * 1024 * 1024, Now.instant().minus(3, ChronoUnit.DAYS));
            assertThat(backupsTile()).as("step 3: an overdue dump does not count as backed up")
                .containsExactly(before[0], before[1] + 1);

            // 4. A temporary database is never backed up, so it is not counted at all.
            database(cleanup, "d13-scratch", true);
            assertThat(backupsTile()).as("step 4: a temporary database leaves the count alone")
                .containsExactly(before[0], before[1] + 1);
        } finally {
            for (int i = cleanup.size() - 1; i >= 0; i--) {
                cleanup.get(i).run();
            }
        }
    }

    /** @return the dashboard's Backups tile for the operator */
    private static DashboardStat backupsStat() {
        Panel admin = Objects.requireNonNull(PanelRegistry.getBySlug(HohenheimSlugs.ADMIN), "the admin panel");
        AccessContext operator = TenantConduits.operator();
        return DashboardStats.read(admin, AppDirectory.read(admin, operator), operator).stream()
            .filter(stat -> stat.key().equals("backups")).findFirst().orElseThrow();
    }

    /** @return the Backups tile as {backed up, should be backed up}; "0" reads as nothing to back up */
    private static int[] backupsTile() {
        String value = backupsStat().value();
        if (!value.contains(" of ")) {
            return new int[] {0, Integer.parseInt(value.trim())};
        }
        String[] parts = value.split(" of ");
        return new int[] {Integer.parseInt(parts[0].trim()), Integer.parseInt(parts[1].trim())};
    }

    @Test
    void aDatabaseThatDoesNotRunIsOneProblemEverySurfaceReads() throws Exception {
        List<Runnable> cleanup = new ArrayList<>();
        try {
            // 1. A shared engine running one database that two apps use: nothing to say.
            Row engine = row(cleanup, Models.get(DatabaseEngineModel.class), Map.of(
                DatabaseEngineModel.NAME.getName(), PREFIX + "d12-engine", DatabaseEngineModel.ENGINE.getName(), "mysql",
                DatabaseEngineModel.ROOT_USER.getName(), "root", DatabaseEngineModel.ROOT_PASSWORD.getName(), "rootpw",
                DatabaseEngineModel.SERVER_ID.getName(), ServerModel.localServerId(),
                DatabaseEngineModel.STATUS.getName(), DatabaseModel.STATUS_ACTIVE));
            Row shared = row(cleanup, Models.get(DatabaseModel.class), Map.of(DatabaseModel.NAME.getName(),
                PREFIX + "d12-shop", DatabaseModel.ENGINE.getName(), "mysql", DatabaseModel.PLACEMENT.getName(),
                DatabaseModel.PLACEMENT_SHARED, DatabaseModel.ENGINE_ID.getName(), engine.get(DatabaseEngineModel.ID),
                DatabaseModel.DB_NAME.getName(), "d12shop", DatabaseModel.DB_USER.getName(), "d12shop",
                DatabaseModel.DB_PASSWORD.getName(), "d12-secret-password",
                DatabaseModel.SERVER_ID.getName(), ServerModel.localServerId(),
                DatabaseModel.STATUS.getName(), DatabaseModel.STATUS_ACTIVE));
            String engineHandle = EngineHandles.plantEngine(engine.get(DatabaseEngineModel.ID), PREFIX + "d12-engine",
                "mysql", InstanceModel.STATUS_RUNNING);
            Row engineInstance = DatabaseInstances.owned(shared.get(DatabaseModel.ID));
            assertThat(engineInstance).as("step 1: the planted engine serves the shared database (" + engineHandle
                + ")").isNotNull();
            cleanup.add(() -> OwnedInstances.inScopeUnchecked(DatabaseEngines.SOURCE, DatabaseEngineModel.MODEL_ID,
                engine.get(DatabaseEngineModel.ID), () -> HardDeletes.row(Models.get(InstanceModel.class), engineInstance)));
            Row shopApp = app(cleanup, PREFIX + "d12-shop-app", shared);
            app(cleanup, PREFIX + "d12-blog-app", shared);
            int sharedId = shared.get(DatabaseModel.ID);
            assertThat(DatabaseVerdict.ofDatabase(shared).state()).as("step 1: its engine runs, so it runs")
                .isEqualTo(DatabaseVerdict.State.RUNNING);
            assertThat(rootOf(AttentionCollector.databases(), sharedId)).as("step 1: a running database raises nothing")
                .isNull();
            // The host's Workloads card names the engine once, booked at the memory its owned instance holds in the
            // ledger; the instance it runs as is not listed beside it (DEP10: dbengine-mongo-local and mongo-local).
            List<WorkloadView> workloads = ServerOverviewState.workloadsOf(HohenheimSlugs.ADMIN,
                ServerModel.localServerId());
            assertThat(workloads).as("step 1: the engine is listed once, as the engine")
                .filteredOn(view -> view.name().equals(PREFIX + "d12-engine")).singleElement()
                .satisfies(view -> {
                    assertThat(view.tier()).isEqualTo(WorkloadTier.DATABASE_ENGINE);
                    assertThat(view.bookedMb()).as("step 1: booked at its instance's ledger memory")
                        .isEqualTo(engineInstance.get(InstanceModel.CAPACITY_MB));
                });
            assertThat(workloads).as("step 1: its instance is not a second row")
                .noneSatisfy(view -> assertThat(view.name()).isEqualTo(engineInstance.get(InstanceModel.NAME)));
            assertThat(workloads).as("step 1: the shared database books nothing of its own")
                .filteredOn(view -> view.name().equals(PREFIX + "d12-shop")).singleElement()
                .satisfies(view -> assertThat(view.bookedMb()).isNull());

            // 2. The engine stops. The database is the root: one item titled by the database, with its action, saying
            //    how many apps it holds back, in English and in Dutch.
            engineStatus(engineInstance, InstanceModel.STATUS_STOPPED);
            List<AttentionItem> tier = AttentionCollector.databases();
            AttentionItem root = rootOf(tier, sharedId);
            assertThat(root).as("step 2: the stopped database raises its own item").isNotNull();
            assertThat(say(root.title())).as("step 2: titled in words by the database")
                .isEqualTo("Database " + PREFIX + "d12-shop is not running");
            assertThat(root.title().resolve(LocaleChain.ofTags("nl"), Zenit.getMessageResolver()))
                .as("step 2: in Dutch too").isEqualTo("Database " + PREFIX + "d12-shop draait niet");
            assertThat(say(root.detail())).as("step 2: saying why").isEqualTo("Its engine is not running");
            assertThat(say(root.heldBack())).as("step 2: naming the apps it holds back").isEqualTo("2 apps use it");
            assertThat(say(root.action())).as("step 2: with its action").isEqualTo("Open the database");
            assertThat(root.target().toUrl()).as("step 2: opening the database's front door")
                .isEqualTo("/admin/" + HohenheimSlugs.DATABASES + "/" + sharedId + "/open");

            // 3. Each app's own item names the database as its cause and folds under it: the dashboard's band and the
            //    Databases band draw the root alone, never "Instance Shop" twice over.
            List<AttentionItem> perApp = tier.stream()
                .filter(item -> AttentionSubject.database(sharedId).equals(item.causedBy())).toList();
            assertThat(perApp).as("step 3: one item per app, each caused by the database").hasSize(2);
            List<AttentionItem> dashboard = DashboardAttention.read(AttentionCollector.collect(), true).attention();
            assertThat(dashboard).as("step 3: the dashboard draws the database's item")
                .anySatisfy(item -> assertThat(item.about()).isEqualTo(AttentionSubject.database(sharedId)));
            assertThat(dashboard).as("step 3: and folds the apps' items under it")
                .noneSatisfy(item -> assertThat(item.causedBy()).isEqualTo(AttentionSubject.database(sharedId)));
            assertThat(DashboardAttention.band(tier)).as("step 3: the Databases band folds them the same way")
                .noneSatisfy(item -> assertThat(item.causedBy()).isEqualTo(AttentionSubject.database(sharedId)));
            String list = adminGet("/admin/" + HohenheimSlugs.DATABASES).body();
            assertThat(list).as("step 3: the Databases page leads with the database's item")
                .contains("Database " + PREFIX + "d12-shop is not running").contains("2 apps use it")
                .doesNotContain(PREFIX + "d12-shop-app cannot use its database");

            // 4. One verdict: the list's state cell, the Engines card, the app's Databases tab and Back up now all read
            //    what the engine does, never the stored "active" the attention item contradicted.
            assertThat(list).as("step 4: the list's state cell reads not running")
                .contains("data-state=\"" + DatabaseVerdict.State.NOT_RUNNING.token() + "\"")
                .as("step 4: the Engines card says the same").contains("1 database, not running");
            String cannotBackUp = "No backup can be made now. Its engine is not running.";
            assertThat(say(DatabaseParts.backUpUnavailable(shared))).as("step 4: Back up now is offered dead, saying why")
                .isEqualTo(cannotBackUp);
            assertThat(say(DatabaseParts.neverBackedUpDetail(shared)))
                .as("step 4: Last backup reads the same reason, never \"Back up now\"").isEqualTo(cannotBackUp);
            assertThat(list).as("step 4: the list's Last backup cell says it").contains(cannotBackUp);
            // 4b. Delete is dead while the apps hold it, naming them in words: the Used by cell is where they link.
            assertThat(say(DeleteImpact.databaseInUse(shared))).as("step 4b: the in-use reason names the apps")
                .isEqualTo("Database '" + PREFIX + "d12-shop' is attached to " + PREFIX + "d12-shop-app, " + PREFIX
                    + "d12-blog-app. Detach it on each instance's Databases tab first.")
                .as("step 4b: and pastes no path into the sentence").doesNotContain("/admin/");
            String appTab = adminGet("/admin/" + HohenheimSlugs.INSTANCES + "/" + shopApp.get(InstanceModel.ID)
                + "/page/databases").body();
            assertThat(appTab).as("step 4: the app's Databases tab reads the same words").contains("Not running</a>");

            // 5. The engine runs again: the item, the apps' items and the not-running words all clear.
            engineStatus(engineInstance, InstanceModel.STATUS_RUNNING);
            assertThat(rootOf(AttentionCollector.databases(), sharedId)).as("step 5: nothing left to say").isNull();
            assertThat(AttentionCollector.databases()).as("step 5: no app is held back")
                .noneSatisfy(item -> assertThat(item.causedBy()).isEqualTo(AttentionSubject.database(sharedId)));
            assertThat(adminGet("/admin/" + HohenheimSlugs.DATABASES).body()).as("step 5: the list reads running")
                .contains("data-state=\"" + DatabaseVerdict.State.RUNNING.token() + "\"");
            assertThat(DatabaseParts.backUpUnavailable(shared)).as("step 5: Back up now is live again").isNull();
            assertThat(say(DatabaseParts.neverBackedUpDetail(shared))).as("step 5: Last backup offers it again")
                .isEqualTo("Back up now, or wait for the nightly backup");
            assertThat(DatabaseParts.moveUnavailable(shared)).as("step 5: a serving database may move").isNull();

            // 6. A dedicated database with no engine to run on cannot move onto a shared engine either (the move dumps
            //    it from the engine it leaves): Move to shared engine is offered dead, in the verdict's words.
            Row dedicated = database(cleanup, "d12-own", false);
            String cannotMove = "It cannot move now. It has no engine to run on.";
            assertThat(say(DatabaseParts.moveUnavailable(dedicated))).as("step 6: the move says why it is dead")
                .isEqualTo(cannotMove);
            assertThat(adminGet("/admin/" + HohenheimSlugs.DATABASES + "/" + dedicated.get(DatabaseModel.ID)
                + "/page/overview").body()).as("step 6: its record menu carries the reason")
                .contains("data-cms-action-reason").contains(cannotMove);
        } finally {
            for (int i = cleanup.size() - 1; i >= 0; i--) {
                cleanup.get(i).run();
            }
        }
    }

    private static AttentionItem rootOf(List<AttentionItem> items, int databaseId) {
        return items.stream().filter(item -> AttentionSubject.database(databaseId).equals(item.about()))
            .findFirst().orElse(null);
    }

    /** A stopped docker app using this database. */
    private static Row app(List<Runnable> cleanup, String name, Row database) {
        Row app = row(cleanup, Models.get(InstanceModel.class), Map.of(InstanceModel.NAME.getName(), name,
            InstanceModel.KIND.getName(), "hohenheim:docker_container",
            InstanceModel.SETTINGS.getName(), new LinkedHashMap<>(Map.of("image", "alpine")),
            InstanceModel.STATUS.getName(), InstanceModel.STATUS_STOPPED,
            InstanceModel.SERVER_ID.getName(), ServerModel.localServerId()));
        row(cleanup, Models.get(InstanceDatabaseModel.class), Map.of(
            InstanceDatabaseModel.INSTANCE_ID.getName(), app.get(InstanceModel.ID),
            InstanceDatabaseModel.DATABASE_ID.getName(), database.get(DatabaseModel.ID),
            InstanceDatabaseModel.ENV_PREFIX.getName(), "DB"));
        return app;
    }

    /** The engine instance serving a dedicated database, planted with no daemon. */
    private static void plantEngine(List<Runnable> cleanup, Row database, String status) {
        EngineHandles.plant(database.get(DatabaseModel.ID), String.valueOf((Object) database.get(DatabaseModel.NAME)),
            "mysql", status);
        Row planted = DatabaseInstances.owned(database.get(DatabaseModel.ID));
        // A generated row is only removed in its owner's scope (GeneratedRows), as its owner's own teardown does.
        cleanup.add(() -> OwnedInstances.inScopeUnchecked(DatabaseInstances.SOURCE, DatabaseModel.MODEL_ID,
            database.get(DatabaseModel.ID), () -> HardDeletes.row(Models.get(InstanceModel.class), planted)));
    }

    /** What the status reconciler would have stamped, written the way it writes (hook-free). */
    private static void engineStatus(Row engineInstance, String status) {
        Models.get(InstanceModel.class).find().where(InstanceModel.ID.eq(engineInstance.get(InstanceModel.ID)))
            .assign(InstanceModel.STATUS, status)
            .bypassBehaviours()
            .updateAll();
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
