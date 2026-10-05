package be.elevenways.hohenheim.test.migration;

import be.elevenways.hohenheim.test.LegacyStepPayloads;
import be.elevenways.hohenheim.migration.InitialMigration;
import be.elevenways.hohenheim.migration.M011_ReviewHardening;
import be.elevenways.zenit.common.orm.migration.MigrationException;
import be.elevenways.zenit.server.orm.migration.MigrationRunner;
import be.elevenways.zenit.common.security.ZenitPrincipalKind;
import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.hohenheim.HohenheimSources;
import be.elevenways.hohenheim.instance.InstanceOperations;
import be.elevenways.hohenheim.model.CertificateModel;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.SiteDomainModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.server.HohenheimDatabase;
import be.elevenways.hohenheim.server.HohenheimSettingsBoot;
import be.elevenways.hohenheim.server.proxy.RouteClaims;
import be.elevenways.hohenheim.test.HohenheimTestRuntime;
import be.elevenways.hohenheim.test.TestDatabases;
import be.elevenways.hohenheim.test.TenantConduits;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.protoblast.common.time.Now;
import be.elevenways.zenit.auth.AuthKeys;
import be.elevenways.zenit.auth.model.ApiKeyModel;
import be.elevenways.zenit.auth.model.ApiKeyPrincipal;
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
import be.elevenways.zenit.common.security.PrincipalRef;
import be.elevenways.zenit.common.security.RecordOwnership;
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
import be.elevenways.zenit.common.time.ClockOffset;
import be.elevenways.zenit.server.ServerZenitRuntime;
import be.elevenways.zenit.server.data.SavedViews;
import be.elevenways.zenit.server.http.HostPattern;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

    /** The certificate ids step 0 writes into the copy; the fixture holds none. */
    private static final int OPERATOR_CERT = 901;
    private static final int UNATTENDED_CERT = 902;

    /** The disabled chain step 0 writes with the console and app update steps. */
    private static final int CONSOLE_SCHEDULE = 901;

    /** The finished legacy run step 0 writes into the copy. */
    private static final int LEGACY_RUN = 901;

    /** The wildcard route and the released wildcard claim step 0 writes, spelled as Hohenheim stored them. */
    private static final int WILDCARD_DOMAIN = 901;
    private static final int RELEASED_CLAIM = 901;

    /** An exact route step 0 writes with force_ssl switched off, beside the wildcard row's stored default. */
    private static final int PLAIN_DOMAIN = 902;
    private static final String LEGACY_WILDCARD = "*.wild.upgrade.test";
    private static final String RELEASED_WILDCARD = "*.gone.upgrade.test";

    private static ClockOffset.Pin seedClock;

    @AfterAll
    static void restore() {
        if (seedClock != null) {
            seedClock.close();
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
        // Pinned, so step 2's boot keeps it over the configured clock.offset.
        Instant seededAt = Instant.parse(facts.getProperty("seeded_at"));
        Instant realNow = Now.instant().minus(Now.offset());
        seedClock = ClockOffset.pin(Duration.between(realNow, seededAt.plus(Duration.ofHours(1))));

        int adminId = Integer.parseInt(facts.getProperty("admin.id"));
        int operatorId = Integer.parseInt(facts.getProperty("operator.id"));
        int siteId = Integer.parseInt(facts.getProperty("site.id"));
        int instanceId = Integer.parseInt(facts.getProperty("instance.id"));
        String url = "jdbc:sqlite:" + database.toAbsolutePath();
        Instant retainedInstant = Instant.parse("1969-12-31T23:59:59.987654Z");
        execute(url, "UPDATE sites SET created_at = '" + retainedInstant + "' WHERE id = " + siteId);

        assertThat(scalar(url, "SELECT MAX(version) FROM zenit_migrations WHERE stream = 'be.elevenways.hohenheim'"))
            .as("step 0: the fixture is an install at Hohenheim's production level").isEqualTo("010");
        // The fixture predates certificate owners, so two orders are written at their M010 shape: the operator's and
        // an unattended one (no requester).
        execute(url, "INSERT INTO certificates (id, nice_name, provider, status, domain_names_text,"
            + " requested_by_user_id) VALUES (" + OPERATOR_CERT + ", 'Operator order', 'letsencrypt', 'active',"
            + " 'operator.upgrade.test', " + operatorId + "), (" + UNATTENDED_CERT + ", 'Unattended order',"
            + " 'letsencrypt', 'active', 'unattended.upgrade.test', NULL)");
        // A second, disabled chain holds the two legacy actions the fixture never stored: a console line and an app
        // update, at their M010 shape.
        execute(url, "INSERT INTO zenit_record_schedules (id, model, record_id, name, cron, enabled, kind) VALUES ("
            + CONSOLE_SCHEDULE + ", 'hohenheim:instance', '" + instanceId + "', 'warn and update', '0 5 * * *', 0,"
            + " 'cron')");
        execute(url, "INSERT INTO zenit_record_schedule_steps (schedule_id, step_order, action, payload) VALUES ("
            + CONSOLE_SCHEDULE + ", 0, 'hohenheim:console_command', '{\"command\":\"say upgrading\"}'), ("
            + CONSOLE_SCHEDULE + ", 1, 'hohenheim:app_update', NULL)");
        // And one finished run of the schedule as the legacy executor recorded it: step 1 ran a backup, step 4 a power
        // action, before step 1 was edited to the stop it is today.
        String ranAt = seededAt.minus(Duration.ofDays(1)).toString();
        execute(url, "INSERT INTO zenit_record_schedule_runs (id, schedule_id, model, record_id, status, fired_by,"
            + " claim_fence, step_results, started_at, ended_at) VALUES (" + LEGACY_RUN + ", "
            + facts.getProperty("schedule.id") + ", 'hohenheim:instance', '" + instanceId + "', 'completed', 'cron', 0,"
            + " '{\"steps\":[{\"step_id\":1,\"position\":0,\"action\":\"hohenheim:backup\",\"status\":\"ok\","
            + "\"attempts\":1,\"started_at\":\"" + ranAt + "\",\"ended_at\":\"" + ranAt + "\"},{\"step_id\":4,"
            + "\"position\":3,\"action\":\"hohenheim:power\",\"status\":\"ok\",\"attempts\":1,\"started_at\":\""
            + ranAt + "\",\"ended_at\":\"" + ranAt + "\"}]}', " + seededAt.minus(Duration.ofDays(1)).toEpochMilli()
            + ", " + seededAt.minus(Duration.ofDays(1)).toEpochMilli() + ")");

        // A wildcard route and a released wildcard claim, whose leading '*.' meant one or more labels, each with
        // the claim key the old code stamped: hostname, path and listeners joined by newlines, no path or listener.
        execute(url, "INSERT INTO site_domains (id, site_id, hostname, match_type, live_route_key) VALUES ("
            + WILDCARD_DOMAIN + ", " + siteId + ", '" + LEGACY_WILDCARD + "', 'wildcard', '" + LEGACY_WILDCARD
            + "' || char(10) || char(10))");
        execute(url, "INSERT INTO site_domains (id, site_id, hostname, match_type, force_ssl) VALUES (" + PLAIN_DOMAIN
            + ", " + siteId + ", 'plain.upgrade.test', 'exact', 0)");
        execute(url, "INSERT INTO released_route_claims (id, claim_key, hostname, match_type, former_site_id,"
            + " released_at) VALUES (" + RELEASED_CLAIM + ", '" + RELEASED_WILDCARD + "' || char(10) || char(10), '"
            + RELEASED_WILDCARD + "', 'wildcard', " + siteId + ", " + seededAt.toEpochMilli() + ")");

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
        // 1a. Rehearse an install whose table-creation source was removed, retaining its APPLIED history row.
        Path rehearsalDatabase = dir.resolve("unreplayable-history.db");
        Files.copy(database, rehearsalDatabase);
        String rehearsalUrl = "jdbc:sqlite:" + rehearsalDatabase.toAbsolutePath();
        Zenit.SETTINGS_VALUES.setValue(HohenheimSettings.Database.PATH, rehearsalDatabase.toAbsolutePath().toString());
        var rehearsal = HohenheimDatabase.openDatasource();
        Models.get(SiteModel.class);
        var withoutCreationSource = MigrationRunner.discoverMigrations(rehearsal.getDatasourceIdentifier()).stream()
            .filter(supplier -> !(supplier.get() instanceof InitialMigration)).toList();
        var withoutOwnerScope = withoutCreationSource.stream()
            .filter(supplier -> !(supplier.get() instanceof M011_ReviewHardening)).toList();
        assertThatThrownBy(() -> MigrationRunner.overCompleteSet(rehearsal, withoutOwnerScope)
            .acknowledgeMissingMigrationVersions("001").migrate().requireSuccess())
            .as("step 1a: missing creation history cannot silently omit the declared sites instant scope")
            .isInstanceOf(MigrationException.class).hasMessageContaining("sites").hasMessageContaining("FrozenModel");
        MigrationRunner.overCompleteSet(rehearsal, withoutCreationSource)
            .acknowledgeMissingMigrationVersions("001").migrate().requireSuccess();
        assertThat(scalar(rehearsalUrl, "SELECT created_at FROM sites WHERE id = " + siteId))
            .as("step 1a: M011's frozen owner scope preserves the pre-epoch microseconds without creation source")
            .isEqualTo(Now.instantText(retainedInstant));
        HohenheimDatabase.closeDatasource();
        Zenit.SETTINGS_VALUES.setValue(HohenheimSettings.Database.PATH, database.toAbsolutePath().toString());
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

        // 3. The role grant and both record grants decide as before, and each certificate keeps its owner.
        assertThat(PermissionHolders.userIdsHolding(HohenheimSources.ADMIN_ACCESS))
            .as("step 3: the admin's * grant and the operator's role both hold the admin permission")
            .contains(adminId, operatorId);
        UserPrincipal operator = new UserPrincipal(operatorId, "Upgrade Operator");
        assertThat(RecordGrants.recordIds(operator, SiteModel.MODEL_ID, "manage"))
            .as("step 3: the operator still manages the site").contains(String.valueOf(siteId));
        assertThat(RecordGrants.recordIds(operator, InstanceModel.MODEL_ID, "power"))
            .as("step 3: and still powers the instance").contains(String.valueOf(instanceId));
        CertificateModel certificates = Models.get(CertificateModel.class);
        Row operatorCert = certificates.findById(OPERATOR_CERT);
        assertThat((Integer) operatorCert.get(CertificateModel.REQUESTED_BY_USER_ID))
            .as("step 3: the operator's certificate keeps its stored requester id").isEqualTo(operatorId);
        assertThat(CertificateModel.requesterOf(operatorCert))
            .as("step 3: and reads it back as the operator's account").isEqualTo(PrincipalRef.account(operatorId));
        assertThat(RecordOwnership.ownerOf(CertificateModel.MODEL_ID, OPERATOR_CERT))
            .as("step 3: whom the owner rule names as its owner").isEqualTo(PrincipalRef.account(operatorId));
        Row unattendedCert = certificates.findById(UNATTENDED_CERT);
        assertThat(CertificateModel.requesterOf(unattendedCert))
            .as("step 3: an unattended certificate still has no requester").isNull();
        assertThat((String) unattendedCert.get(CertificateModel.REQUESTED_BY_KIND))
            .as("step 3: and no kind was invented for it").isNull();

        // 4. M011 ran on production data: the operator-owned address site is marked a trusted upstream.
        Row site = Models.get(SiteModel.class).findById(siteId);
        assertThat(site.get(SiteModel.TRUSTED_UPSTREAM))
            .as("step 4: a tenant-owned dialing site written by an operator is trusted").isEqualTo(true);

        // M011 respelled both wildcards into HostPattern's one-or-more '**.', their claim keys with them, so the route
        // still names every depth under its domain and never the apex, and the quarantine still holds its claim.
        Row wildcard = Models.get(SiteDomainModel.class).findById(WILDCARD_DOMAIN);
        String respelled = wildcard.get(SiteDomainModel.HOSTNAME);
        assertThat(respelled).as("step 4: the stored wildcard is respelled").isEqualTo("**.wild.upgrade.test");
        assertThat((String) wildcard.get(SiteDomainModel.LIVE_ROUTE_KEY))
            .as("step 4: its claim key is the one today's code computes").isEqualTo(RouteClaims.keyOf(wildcard));
        HostPattern routed = HostPattern.parse(respelled);
        assertThat(routed.matches("a.wild.upgrade.test") && routed.matches("a.b.wild.upgrade.test"))
            .as("step 4: the route names one label and more under its domain, as before").isTrue();
        assertThat(routed.matches("wild.upgrade.test")).as("step 4: and still never the apex").isFalse();
        assertThat(strings(url, "SELECT hostname || '|' || claim_key FROM released_route_claims WHERE id = "
            + RELEASED_CLAIM)).as("step 4: the released claim and its key are respelled")
            .containsExactly("**.gone.upgrade.test|" + RouteClaims.keyOf("**.gone.upgrade.test", "wildcard", null,
                null));

        // 4b. Every stored route keeps the force_ssl it had, and none is armed to change it: the latch that forces
        //     HTTPS once a certificate works is for rows written from here on.
        Row plain = Models.get(SiteDomainModel.class).findById(PLAIN_DOMAIN);
        assertThat((Boolean) wildcard.get(SiteDomainModel.FORCE_SSL))
            .as("step 4b: the route stored with the old default stays forced").isTrue();
        assertThat((Boolean) plain.get(SiteDomainModel.FORCE_SSL))
            .as("step 4b: the route stored unforced stays unforced").isFalse();
        assertThat(List.of(wildcard.get(SiteDomainModel.FORCE_SSL_AUTO), plain.get(SiteDomainModel.FORCE_SSL_AUTO)))
            .as("step 4b: and neither is armed").containsOnly(false);

        // 5. The API key keeps its scopes, its zenit-auth model scope under today's spelling, and authenticates.
        Row key = AuthModels.apiKeys().find().noCache().where(ApiKeyModel.LABEL.eq("upgrade-key")).first();
        assertThat(key.get(ApiKeyModel.SCOPES))
            .as("step 5: every scope kept, in order, the model scope read today")
            .isEqualTo(List.of("cap:hohenheim:instance#power", "cap:zenit:user#read", "hohenheim.admin.access"));
        ApiKeyPrincipal keyPrincipal = ApiKeyService.authenticate(facts.getProperty("api_key.plaintext"));
        assertThat(keyPrincipal).as("step 5: the old key still authenticates").isNotNull();
        assertThat(keyPrincipal.reference()).as("step 5: as its owner")
            .isEqualTo(new PrincipalRef(ZenitPrincipalKind.ACCOUNT, operatorId));
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
            assertThat(LegacyStepPayloads.of(step.get(RecordScheduleStepModel.ID)))
                .as("step 9: step %s keeps no legacy payload", step.get(RecordScheduleStepModel.ID)).isNull();
        }
        assertThat(steps).extracting(step -> step.get(RecordScheduleStepModel.INPUT))
            .as("step 9: only the snapshot step carries an input, its note")
            .containsExactly(null, Map.of("note", "before nightly start"), null, null, null);
        assertThat(OperationPipeline.readJson(InstanceOperations.SNAPSHOT,
                steps.get(1).get(RecordScheduleStepModel.INPUT), null))
            .as("step 9: the stored input reads through the snapshot operation's form")
            .isEqualTo(new InstanceOperations.SnapshotInput("before nightly start"));

        List<Row> consoleChain = Models.get(RecordScheduleStepModel.class).findChain(CONSOLE_SCHEDULE);
        assertThat(consoleChain).extracting(step -> step.get(RecordScheduleStepModel.ACTION))
            .as("step 9: the console line and the app update name their operations")
            .containsExactly(InstanceOperations.CONSOLE_COMMAND.id().toString(),
                InstanceOperations.APP_UPDATE.id().toString());
        assertThat(consoleChain).extracting(step -> step.get(RecordScheduleStepModel.INPUT))
            .as("step 9: the console line moved into the stored input; the app update takes none")
            .containsExactly(Map.of("command", "say upgrading"), null);
        assertThat(OperationPipeline.readJson(InstanceOperations.CONSOLE_COMMAND,
                consoleChain.get(0).get(RecordScheduleStepModel.INPUT), null))
            .as("step 9: and reads through the console operation's form")
            .isEqualTo(new InstanceOperations.ConsoleCommandInput("say upgrading"));
        for (Row step : consoleChain) {
            assertThat(SchedulePlacements.find(Identifier.tryParse(step.get(RecordScheduleStepModel.ACTION))))
                .as("step 9: step %s runs a placed operation", step.get(RecordScheduleStepModel.ID)).isNotNull();
            assertThat(LegacyStepPayloads.of(step.get(RecordScheduleStepModel.ID)))
                .as("step 9: step %s keeps no legacy payload", step.get(RecordScheduleStepModel.ID)).isNull();
        }

        // The finished run keeps what it ran, not what its step runs today: the backup is respelled to the backup
        // operation although step 1 is now a stop, and the power action, whose record never named which, stays.
        assertThat(scalar(url, "SELECT operation FROM zenit_record_schedule_step_runs WHERE run_id = " + LEGACY_RUN
            + " AND step_id = 1")).as("step 9: a run recorded as backup still reads as backup")
            .isEqualTo(InstanceOperations.BACKUP.id().toString());
        assertThat(scalar(url, "SELECT operation FROM zenit_record_schedule_step_runs WHERE run_id = " + LEGACY_RUN
            + " AND step_id = 4")).as("step 9: a recorded power run keeps the action it recorded")
            .isEqualTo("hohenheim:power");
        assertThat(scalar(url, "SELECT COUNT(*) FROM zenit_record_schedule_step_runs WHERE run_id = " + LEGACY_RUN
            + " AND input IS NOT NULL")).as("step 9: and no input was invented for its history").isEqualTo("0");

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

    private static void execute(String url, String sql) throws SQLException {
        try (Connection connection = DriverManager.getConnection(url);
             Statement statement = connection.createStatement()) {
            statement.executeUpdate(sql);
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
