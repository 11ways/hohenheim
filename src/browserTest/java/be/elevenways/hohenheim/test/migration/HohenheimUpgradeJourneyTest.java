package be.elevenways.hohenheim.test.migration;

import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.hohenheim.HohenheimSources;
import be.elevenways.hohenheim.instance.InstanceOperations;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.server.HohenheimDatabase;
import be.elevenways.hohenheim.server.HohenheimSettingsBoot;
import be.elevenways.hohenheim.test.HohenheimTestRuntime;
import be.elevenways.hohenheim.test.TestDatabases;
import be.elevenways.hohenheim.test.TenantConduits;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.protoblast.common.time.Now;
import be.elevenways.zenit.auth.AuthKeys;
import be.elevenways.zenit.auth.model.ApiKeyModel;
import be.elevenways.zenit.auth.model.ApiKeyPrincipal;
import be.elevenways.zenit.auth.model.UserModel;
import be.elevenways.zenit.auth.model.UserPrincipal;
import be.elevenways.zenit.auth.server.ApiKeyService;
import be.elevenways.zenit.auth.server.AuthModels;
import be.elevenways.zenit.auth.server.PermissionHolders;
import be.elevenways.zenit.auth.server.RecordGrants;
import be.elevenways.zenit.common.Zenit;
import be.elevenways.zenit.common.data.view.SavedQuery;
import be.elevenways.zenit.common.data.view.SavedView;
import be.elevenways.zenit.common.data.view.SavedViewModel;
import be.elevenways.zenit.common.orm.activity.ActivityEntry;
import be.elevenways.zenit.common.orm.activity.ActivityGroup;
import be.elevenways.zenit.common.orm.activity.ActivityLog;
import be.elevenways.zenit.common.orm.activity.ActivityPageRequest;
import be.elevenways.zenit.common.orm.activity.ZenitActivityAction;
import be.elevenways.zenit.common.orm.datasource.Db;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.orm.revision.RevisionModel;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.common.session.Session;
import be.elevenways.zenit.common.session.SessionToken;
import be.elevenways.zenit.common.task.TaskCatalog;
import be.elevenways.zenit.common.task.TaskDescriptor;
import be.elevenways.zenit.common.task.orm.SystemTaskHistoryModel;
import be.elevenways.zenit.common.task.orm.SystemTaskModel;
import be.elevenways.zenit.common.task.record.RecordScheduleModel;
import be.elevenways.zenit.common.task.record.RecordScheduleStepModel;
import be.elevenways.zenit.comms.CommsChannel;
import be.elevenways.zenit.comms.server.CommsDeliveryModel;
import be.elevenways.zenit.comms.server.CommsInboxModel;
import be.elevenways.zenit.comms.server.hub.HubIdempotency;
import be.elevenways.zenit.server.ServerZenitRuntime;
import be.elevenways.zenit.server.data.SavedViews;
import be.elevenways.zenit.server.operation.OperationPipeline;
import be.elevenways.zenit.server.orm.crypto.EncryptionKeyring;
import be.elevenways.zenit.server.orm.crypto.FieldEncryption;
import be.elevenways.zenit.server.task.record.SchedulePlacements;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.Reader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The module-fit upgrade: a control-plane database the code of tag before-module-fit wrote at Hohenheim's
 * production migration level (010; kuifje runs 009, robbedoes 010) goes through the deploy lane's
 * {@code --run-migrations} (the migrations above it, then the stored-id reconciler), boots under today's code and
 * reads as before.
 *
 * AIDEV-NOTE: the fixture (src/browserTest/resources/upgrade, README there) was written by the OLD code, so its rows
 * carry the old spellings (zenit-auth:user, zenit-comms:hub_send, zenitmedia:media, class-name task types). Its
 * keyring travels with it: KeyringGuard refuses a database without the key its marker names, exactly as production.
 *
 * AIDEV-NOTE: the clock is pinned to one hour after the seed for the whole journey. The session and the hub receipt
 * carry 24h windows; an upgrade on the deploy day is the case under test, never a fixture that ages out.
 *
 * @author Jelle De Loecker
 * @since  0.1.0
 */
class HohenheimUpgradeJourneyTest {

    private static final Identifier OLD_MEDIA_SOURCE = Identifier.of("zenitmedia", "media");
    private static final Identifier MEDIA_SOURCE = Identifier.of("zenit", "media");

    private static Duration previousOffset;

    @AfterAll
    static void restore() {
        if (previousOffset != null) {
            Now.setOffset(previousOffset);
        }
        FieldEncryption.installKeyring(null);
    }

    @Test
    void anInstallAtM010UpgradesAndReadsAsBefore() throws Exception {
        // 0. A private copy of the fixture and its keyring, with the clock one hour after the seed.
        Path dir = Files.createTempDirectory("hh-upgrade-m010");
        Path database = dir.resolve("hohenheim.db");
        Path keys = dir.resolve("field-encryption.keys");
        copyResource("upgrade/m010.sqlite", database);
        copyResource("upgrade/m010.keys", keys);
        Properties facts = new Properties();
        try (Reader reader = new InputStreamReader(resource("upgrade/m010.properties"), StandardCharsets.UTF_8)) {
            facts.load(reader);
        }
        previousOffset = Now.offset();
        Now.setOffset(Duration.ZERO);
        Instant seededAt = Instant.parse(facts.getProperty("seeded_at"));
        Now.setOffset(Duration.between(Now.instant(), seededAt.plus(Duration.ofHours(1))));

        int adminId = Integer.parseInt(facts.getProperty("admin.id"));
        int operatorId = Integer.parseInt(facts.getProperty("operator.id"));
        int siteId = Integer.parseInt(facts.getProperty("site.id"));
        int instanceId = Integer.parseInt(facts.getProperty("instance.id"));
        String url = "jdbc:sqlite:" + database.toAbsolutePath();

        assertThat(scalar(url, "SELECT MAX(version) FROM zenit_migrations WHERE stream = 'be.elevenways.hohenheim'"))
            .as("step 0: the fixture is an install at Hohenheim's production level").isEqualTo("010");

        // 1. The deploy lane: the framework's --run-migrations over Hohenheim's datasource, exactly as ServerMain
        //    dispatches it, applies everything above the fixture and then reconciles the stored ids.
        HohenheimSettingsBoot.forceDefinitions();
        Zenit.SETTINGS_VALUES.setValue(HohenheimSettings.Roles.PROXY, true);
        Zenit.SETTINGS_VALUES.setValue(HohenheimSettings.Roles.DNS, true);
        Zenit.SETTINGS_VALUES.setValue(HohenheimSettings.Roles.FIREWALL, true);
        Zenit.SETTINGS_VALUES.setValue(HohenheimSettings.Roles.STACKS, true);
        Zenit.SETTINGS_VALUES.setValue(HohenheimSettings.Roles.DATABASES, true);
        Zenit.SETTINGS_VALUES.setValue(HohenheimSettings.Roles.INSTANCES, true);
        HohenheimSettingsBoot.load();
        Zenit.SETTINGS_VALUES.setValue(HohenheimSettings.Database.PATH, database.toAbsolutePath().toString());
        FieldEncryption.installKeyring(EncryptionKeyring.loadOrCreate(keys));
        HohenheimTestRuntime.declareAccessModelsOnce();
        assertThat(ServerZenitRuntime.runMigrationsIfRequested(new String[] {"--run-migrations"},
                HohenheimDatabase::openDatasource))
            .as("step 1: the migration lane ran").isTrue();
        assertThat(scalar(url, "SELECT MAX(version) FROM zenit_migrations WHERE stream = 'be.elevenways.hohenheim'"))
            .as("step 1: Hohenheim's stream moved past the production level").isNotEqualTo("010");
        assertThat(strings(url, "SELECT stored_value FROM zenit_stored_id_reconciliation"))
            .as("step 1: the reconciler rewrote the old spellings it found")
            .contains("zenit-auth:user", "zenitmedia:media",
                "be.elevenways.hohenheim.server.task.SuperviseProxyListeners");

        // 2. Today's code boots over the upgraded file, its datasource-bound services on it whatever ran before.
        HohenheimDatabase.init();
        HohenheimTestRuntime.ensureBooted();
        TestDatabases.adoptCurrentDatabase();

        // 3. The role grant and both record grants decide as before.
        assertThat(PermissionHolders.userIdsHolding(HohenheimSources.ADMIN_ACCESS))
            .as("step 3: the admin's * grant and the operator's role both hold the admin permission")
            .contains(adminId, operatorId);
        UserPrincipal operator = new UserPrincipal(operatorId, "Upgrade Operator");
        assertThat(RecordGrants.recordIds(operator, SiteModel.MODEL_ID, "manage"))
            .as("step 3: the operator still manages the site").contains(String.valueOf(siteId));
        assertThat(RecordGrants.recordIds(operator, InstanceModel.MODEL_ID, "power"))
            .as("step 3: and still powers the instance").contains(String.valueOf(instanceId));

        // 4. M011 ran on production data: the operator-owned address site is marked a trusted upstream.
        Row site = Models.get(SiteModel.class).findById(siteId);
        assertThat(site.get(SiteModel.TRUSTED_UPSTREAM))
            .as("step 4: a tenant-owned dialing site written by an operator is trusted").isEqualTo(true);

        // 5. The API key keeps its scopes, its zenit-auth model scope under today's spelling, and authenticates.
        Row key = AuthModels.apiKeys().find().noCache().where(ApiKeyModel.LABEL.eq("upgrade-key")).first();
        assertThat(key.get(ApiKeyModel.SCOPES))
            .as("step 5: every scope kept, in order, the model scope read today")
            .isEqualTo(List.of("cap:hohenheim:instance#power", "cap:zenit:user#read", "hohenheim.admin.access"));
        ApiKeyPrincipal keyPrincipal = ApiKeyService.authenticate(facts.getProperty("api_key.plaintext"));
        assertThat(keyPrincipal).as("step 5: the old key still authenticates").isNotNull();
        assertThat(keyPrincipal.id()).as("step 5: as its owner").isEqualTo(operatorId);
        assertThat(keyPrincipal.scopes()).as("step 5: with the same scopes")
            .containsExactly("cap:hohenheim:instance#power", "cap:zenit:user#read", "hohenheim.admin.access");

        // 6. Activity on framework models reads through today's model ids; no old spelling is left behind.
        List<ActivityEntry> userHistory = history(AuthModels.users(), operatorId);
        assertThat(userHistory).as("step 6: the operator's creation is in its history")
            .anySatisfy(entry -> assertThat(entry.action()).isEqualTo(ZenitActivityAction.CREATE.id().toString()));
        assertThat(strings(url, "SELECT DISTINCT model FROM zenit_activity"))
            .as("step 6: every activity row names today's model id")
            .contains("zenit:user", "zenit:grant", "zenit:permission_group", "zenit:record_grant", "zenit:api_key",
                "zenit:comms_inbox_item", "zenit:comms_delivery", "zenit:saved_view", "zenit:record_schedule_step")
            .noneMatch(model -> model.startsWith("zenit-auth:") || model.startsWith("zenit-comms:"));

        // 7. The site's revision and the activity row linked to it read as before.
        List<Row> revisions = new RevisionModel(HohenheimDatabase.datasource()).find().noCache()
            .where(RevisionModel.MODEL.eq(SiteModel.MODEL_ID.toString()))
            .and(RevisionModel.RECORD_ID.eq(String.valueOf(siteId))).all();
        assertThat(revisions).as("step 7: the site's revision is there").hasSize(1);
        assertThat(history(Models.get(SiteModel.class), siteId))
            .as("step 7: the site's update is in its history, linked to revision 1")
            .anySatisfy(entry -> {
                assertThat(entry.action()).isEqualTo(ZenitActivityAction.UPDATE.id().toString());
                assertThat(entry.revision()).isEqualTo(1);
            });

        // 8. The saved view names today's source and its owner still finds it there.
        UUID viewId = UUID.fromString(facts.getProperty("saved_view.id"));
        assertThat(OLD_MEDIA_SOURCE.toString()).as("step 8: the fixture stored the old source id")
            .isEqualTo(facts.getProperty("saved_view.source"));
        Row viewRow = Models.get(SavedViewModel.class).find().noCache().where(SavedViewModel.ID.eq(viewId)).first();
        assertThat(viewRow.get(SavedViewModel.SOURCE_ID)).as("step 8: the view row names today's source")
            .isEqualTo(MEDIA_SOURCE.toString());
        AccessContext admin = AccessContext.of(TenantConduits.stubFor(new UserPrincipal(adminId, "Upgrade Admin")));
        SavedView view = SavedViews.find(admin, SavedViews.hostOf(MEDIA_SOURCE), viewId);
        assertThat(view).as("step 8: the owner finds the view on the media list").isNotNull();
        assertThat(view.name()).as("step 8: under its name").isEqualTo("Recent uploads");

        // 9. The schedule and its steps keep running: M011 named the operation each stored power, snapshot and backup
        //    step now runs (a power step by its stored operation, a blank one as the old default restart), moved the
        //    snapshot's note into the stored input, and every step resolves to an operation placed as a schedule step
        //    whose form reads that input.
        Row schedule = Models.get(RecordScheduleModel.class)
            .findById(Integer.parseInt(facts.getProperty("schedule.id")));
        assertThat(schedule.get(RecordScheduleModel.MODEL)).as("step 9: the schedule targets the instance model")
            .isEqualTo(InstanceModel.MODEL_ID.toString());
        List<Row> steps = Models.get(RecordScheduleStepModel.class).findChain(schedule.get(RecordScheduleModel.ID));
        assertThat(steps).extracting(step -> step.get(RecordScheduleStepModel.ACTION))
            .as("step 9: the five steps in order, each naming its operation")
            .containsExactly(InstanceOperations.STOP.id().toString(), InstanceOperations.SNAPSHOT.id().toString(),
                InstanceOperations.BACKUP.id().toString(), InstanceOperations.START.id().toString(),
                InstanceOperations.RESTART.id().toString());
        for (Row step : steps) {
            Identifier action = Identifier.tryParse(step.get(RecordScheduleStepModel.ACTION));
            assertThat(SchedulePlacements.find(action))
                .as("step 9: step %s runs an operation placed as a schedule step", step.get(RecordScheduleStepModel.ID))
                .isNotNull();
            assertThat(step.get(RecordScheduleStepModel.PAYLOAD))
                .as("step 9: step %s keeps no legacy payload", step.get(RecordScheduleStepModel.ID)).isNull();
        }
        assertThat(steps).extracting(step -> step.get(RecordScheduleStepModel.INPUT))
            .as("step 9: only the snapshot step carries an input, its note")
            .containsExactly(null, Map.of("note", "before nightly start"), null, null, null);
        assertThat(OperationPipeline.readJson(InstanceOperations.SNAPSHOT,
                steps.get(1).get(RecordScheduleStepModel.INPUT), null))
            .as("step 9: the stored input reads through the snapshot operation's form")
            .isEqualTo(new InstanceOperations.SnapshotInput("before nightly start"));

        // 10. Every system task row names a catalog task by its id, history included; none is a class name.
        List<Row> tasks = Models.get(SystemTaskModel.class).find().noCache().all();
        TaskDescriptor supervise = TaskCatalog.get("be.elevenways.hohenheim.server.task.SuperviseProxyListeners");
        assertThat(supervise).as("step 10: a class name the old boot stored still names its task").isNotNull();
        assertThat(tasks).extracting(task -> task.get(SystemTaskModel.TYPE))
            .as("step 10: that task's row is stored under its id").contains(supervise.typePath());
        for (Row task : tasks) {
            String type = task.get(SystemTaskModel.TYPE);
            TaskDescriptor descriptor = TaskCatalog.get(type);
            assertThat(descriptor).as("step 10: task %s is in the catalog", type).isNotNull();
            assertThat(type).as("step 10: and stored under its id").isEqualTo(descriptor.typePath());
        }
        for (Row run : Models.get(SystemTaskHistoryModel.class).find().noCache().all()) {
            String type = run.get(SystemTaskHistoryModel.TASK_TYPE);
            assertThat(TaskCatalog.get(type)).as("step 10: history of %s names a catalog task", type).isNotNull();
            assertThat(type).as("step 10: history is stored under the task id").doesNotStartWith("be.elevenways.");
        }

        // 11. A receipt claimed under the old type key replays: the hub send, then the saved view's command.
        assertThat(scalar(url, "SELECT type_key FROM zenit_command_receipts WHERE command_key = 'comms_hub:1:upgrade-journey'"))
            .as("step 11: the receipt keeps the type key it was claimed under").isEqualTo("zenit-comms:hub_send");
        HubIdempotency.Outcome hub = HubIdempotency.begin(Integer.parseInt(facts.getProperty("hub.project")),
            facts.getProperty("hub.key"), facts.getProperty("hub.body"));
        assertThat(hub).as("step 11: the same hub send replays").isInstanceOf(HubIdempotency.Outcome.Replay.class);
        assertThat(((HubIdempotency.Outcome.Replay) hub).response()).as("step 11: with its stored response")
            .isEqualTo(facts.getProperty("hub.response"));
        SavedView replayed = SavedViews.save(admin, SavedViews.hostOf(MEDIA_SOURCE),
            UUID.fromString(facts.getProperty("saved_view.command")),
            SavedView.Draft.of("Recent uploads", false, SavedQuery.EMPTY), 0);
        assertThat(replayed.id()).as("step 11: the saved view's command replays to the view it created")
            .isEqualTo(viewId);

        // 12. The operator's session still signs them in.
        Session session = Zenit.getSessionStore().get(SessionToken.of(facts.getProperty("session.secret")));
        assertThat(session).as("step 12: the session resolves").isNotNull();
        assertThat(session.get(AuthKeys.USER_ID)).as("step 12: to the operator").isEqualTo((long) operatorId);

        // 13. The comms rows Hohenheim's alert wrote: both inboxes, both deliveries on today's inbox channel.
        Db.run(HohenheimDatabase.datasource(), () -> {
            List<Row> inbox = Models.get(CommsInboxModel.class).find().noCache().all();
            assertThat(inbox).extracting(row -> row.get(CommsInboxModel.RECIPIENT))
                .as("step 13: each administrator's inbox item").containsExactlyInAnyOrder("user:1", "user:2");
            assertThat(inbox).extracting(row -> row.get(CommsInboxModel.NOTIFICATION_KEY))
                .as("step 13: for the certificate alert").containsOnly("hohenheim:cert_expiring");
            List<Row> deliveries = Models.get(CommsDeliveryModel.class).find().noCache().all();
            assertThat(deliveries).extracting(row -> CommsChannel.find(row.get(CommsDeliveryModel.CHANNEL)))
                .as("step 13: both deliveries read as the inbox channel").containsOnly(CommsChannel.INBOX);
        });

        // 14. The next boot finds nothing left in the old spellings.
        assertThat(strings(url, "SELECT action FROM zenit_record_schedule_steps UNION SELECT model FROM "
                + "zenit_record_schedules UNION SELECT model FROM auth_record_grants UNION SELECT type FROM system_task"))
            .as("step 14: no stored-id column holds an old spelling")
            .noneMatch(value -> value.startsWith("zenit-") || value.startsWith("be.elevenways."));
    }

    private static List<ActivityEntry> history(be.elevenways.zenit.common.orm.model.Model model, Object recordId) {
        List<ActivityEntry> entries = new ArrayList<>();
        for (ActivityGroup group : ActivityLog.forRecord(model, recordId, ActivityPageRequest.first(50), null)
                .groups()) {
            entries.addAll(group.entries());
        }
        return entries;
    }

    private static InputStream resource(String path) {
        InputStream stream = HohenheimUpgradeJourneyTest.class.getClassLoader().getResourceAsStream(path);
        assertThat(stream).as("the fixture resource %s", path).isNotNull();
        return stream;
    }

    private static void copyResource(String path, Path target) throws Exception {
        try (InputStream stream = resource(path)) {
            Files.copy(stream, target);
        }
    }

    private static String scalar(String url, String sql) throws SQLException {
        List<String> values = strings(url, sql);
        return values.isEmpty() ? null : values.get(0);
    }

    private static List<String> strings(String url, String sql) throws SQLException {
        List<String> values = new ArrayList<>();
        try (Connection connection = DriverManager.getConnection(url);
             Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(sql)) {
            while (rows.next()) {
                values.add(rows.getString(1));
            }
        }
        return values;
    }
}
