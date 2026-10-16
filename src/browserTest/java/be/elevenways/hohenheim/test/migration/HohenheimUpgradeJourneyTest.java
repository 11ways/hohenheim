package be.elevenways.hohenheim.test.migration;

import be.elevenways.hohenheim.test.LegacyStepPayloads;
import be.elevenways.hohenheim.migration.HohenheimMigration;
import be.elevenways.hohenheim.migration.InitialMigration;
import be.elevenways.hohenheim.migration.M011_ReviewHardening;
import be.elevenways.hohenheim.server.migration.M015_ServerLanParent;
import be.elevenways.hohenheim.model.InstanceTemplateModel;
import be.elevenways.hohenheim.model.ProjectModel;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.hohenheim.server.project.Projects;
import be.elevenways.hohenheim.server.quota.OwnerBudget;
import be.elevenways.zenit.auth.cms.SubjectTag;
import be.elevenways.zenit.auth.model.GrantSubjectType;
import be.elevenways.zenit.auth.model.PermissionGroupModel;
import be.elevenways.zenit.common.orm.migration.MigrationException;
import be.elevenways.zenit.common.orm.migration.MigrationKey;
import be.elevenways.zenit.common.orm.migration.PrincipalColumns;
import be.elevenways.zenit.notifications.server.NotificationInboxOwners;
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
import be.elevenways.zenit.auth.model.UserModel;
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
import be.elevenways.zenit.notifications.DeliveryChannel;
import be.elevenways.zenit.notifications.server.DeliveryModel;
import be.elevenways.zenit.notifications.server.NotificationInboxModel;
import be.elevenways.zenit.notifications.server.hub.HubIdempotency;
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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The upgrade path: a control-plane database the code of tag before-module-fit wrote at Hohenheim's
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

    /** The projects and quota overrides step 0 writes at their M010 shape: numeric group and account ids. */
    private static final int OPERATORS_PROJECT = 901;
    private static final int ORPHANED_PROJECT = 902;
    private static final int OPERATOR_QUOTA = 901;
    private static final int SHARED_QUOTA = 902;
    /** An account and a permission group no row holds any more, which an id column still names. */
    private static final int DELETED_ACCOUNT = 777;
    private static final int DELETED_GROUP = 555;

    /** The quota override of a crowd of owners, the first of them deleted, whose bucket stamp is folded already. */
    private static final int CROWD_QUOTA = 903;
    private static final int FIRST_DELETED_OWNER = 801;
    private static final int CROWD_DELETED_OWNERS = 18;
    /** A deleted group only owner tokens name, beside a deleted account and group only a record's grants name. */
    private static final int DELETED_OWNER_GROUP = 557;
    private static final int GRANTED_DELETED_GROUP = 556;
    private static final int GRANTED_DELETED_ACCOUNT = 779;
    /** The record whose manage grants hold the five owners of the root disk's stamp; it was never stored. */
    private static final int GRANTED_INSTANCE = 901;

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
        int roleId = Integer.parseInt(facts.getProperty("role.id"));
        int siteId = Integer.parseInt(facts.getProperty("site.id"));
        int instanceId = Integer.parseInt(facts.getProperty("instance.id"));
        String url = "jdbc:sqlite:" + database.toAbsolutePath();
        Instant retainedInstant = Instant.parse("1969-12-31T23:59:59.987654Z");
        execute(url, "UPDATE sites SET created_at = '" + retainedInstant + "' WHERE id = " + siteId);

        assertThat(scalar(url, "SELECT MAX(version) FROM zenit_migrations WHERE stream = 'be.elevenways.hohenheim'"))
            .as("step 0: the fixture is an install at Hohenheim's production level").isEqualTo("010");
        // The fixture's workload carries the old default crash policy, as every production instance does.
        execute(url, "UPDATE instances SET crash_policy = 'none' WHERE id = " + instanceId);
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
        // Every account and permission group Hohenheim stores, by the numbers M010 knew: the posture acknowledger,
        // two template approvers (one account since deleted), two projects' groups (one deleted), packed owner sets
        // in two quota overrides and the released claim, and the owner tokens behind the bucket stamps; plus their
        // history: a project revision, a template's approval and a site's stamp change.
        execute(url, "UPDATE servers SET acknowledged_by = '" + operatorId + "', acknowledged_by_label = 'Operator'"
            + " WHERE id = 1");
        execute(url, "UPDATE instance_templates SET approved_by_user_id = " + operatorId + " WHERE id = 1");
        execute(url, "UPDATE instance_templates SET approved_by_user_id = " + DELETED_ACCOUNT + " WHERE id = 2");
        execute(url, "INSERT INTO projects (id, name, group_id) VALUES (" + OPERATORS_PROJECT + ", 'Operators', "
            + roleId + "), (" + ORPHANED_PROJECT + ", 'Orphaned', " + DELETED_GROUP + ")");
        execute(url, "INSERT INTO instance_quotas (id, subjects, max_instances) VALUES (" + OPERATOR_QUOTA + ", 'user:"
            + operatorId + "', 3), (" + SHARED_QUOTA + ", 'group:" + roleId + "' || char(10) || 'user:" + operatorId
            + "', 5)");
        execute(url, "UPDATE released_route_claims SET former_subjects = 'user:" + operatorId + "' WHERE id = "
            + RELEASED_CLAIM);
        // Shared owners behind the bucket stamps: the site's pair under the limit, naming a deleted account and group
        // that only owner tokens name; the instance's crowd, folded already, its set readable in a quota override;
        // the root disk's five, under the limit until they are UUIDs, their set held by a record's manage grants.
        List<String> crowdOwners = new ArrayList<>(List.of("group:" + roleId, "group:" + DELETED_OWNER_GROUP,
            "user:" + operatorId));
        for (int owner = FIRST_DELETED_OWNER; owner < FIRST_DELETED_OWNER + CROWD_DELETED_OWNERS; owner++) {
            crowdOwners.add("user:" + owner);
        }
        String crowd = HohenheimAccess.packSubjects(Set.copyOf(crowdOwners));
        String pair = HohenheimAccess.packSubjects(Set.of("group:" + roleId, "group:" + DELETED_OWNER_GROUP,
            "user:" + operatorId, "user:" + FIRST_DELETED_OWNER));
        String five = HohenheimAccess.packSubjects(Set.of("group:" + roleId, "group:" + GRANTED_DELETED_GROUP,
            "user:" + adminId, "user:" + operatorId, "user:" + GRANTED_DELETED_ACCOUNT));
        String pairStamp = OwnerBudget.SITES.bucketOf(pair);
        String crowdStamp = OwnerBudget.INSTANCES.bucketOf(crowd);
        String fiveStamp = OwnerBudget.DISK_GB.bucketOf(five);
        assertThat(List.of(pairStamp, crowdStamp, fiveStamp))
            .as("step 0: the crowd's stamp is folded, the pair's and the five's are not")
            .extracting(stamp -> stamp.contains("sha256:")).containsExactly(false, true, false);
        execute(url, "INSERT INTO instance_quotas (id, subjects, max_instances) VALUES (" + CROWD_QUOTA + ", "
            + literal(crowd) + ", 7)");
        execute(url, "UPDATE sites SET quota_bucket = " + literal(pairStamp) + " WHERE id = " + siteId);
        execute(url, "UPDATE instances SET quota_bucket = " + literal(crowdStamp) + ", root_disk_bucket = "
            + literal(fiveStamp) + " WHERE id = " + instanceId);
        int grant = 0;
        for (String owner : five.split("\n")) {
            String[] subject = owner.split(":");
            execute(url, "INSERT INTO auth_record_grants (id, subject_type, subject_id, model, record_id, capability,"
                + " value) VALUES ('upgrade-owner-" + ++grant + "', '" + subject[0] + "', " + subject[1] + ", '"
                + InstanceModel.MODEL_ID + "', '" + GRANTED_INSTANCE + "', 'manage', 1)");
        }
        execute(url, "INSERT INTO zenit_activity (model, record_id, action, origin, delta, created_at) VALUES"
            + " ('hohenheim:instance', '" + instanceId + "', 'update', 'system', '{\"quota_bucket\":{\"before\":null,"
            + "\"after\":\"" + crowdStamp + "\"},\"root_disk_bucket\":{\"before\":\"" + OwnerBudget.DISK_GB.bucketOf(
            "user:" + operatorId) + "\",\"after\":\"" + fiveStamp.replace("\n", "\\n") + "\"}}', "
            + seededAt.toEpochMilli() + ")");
        execute(url, "INSERT INTO zenit_revisions (model, record_id, revision, snapshot, origin) VALUES"
            + " ('hohenheim:project', '" + OPERATORS_PROJECT + "', 1, '{\"fields\":{\"id\":i" + OPERATORS_PROJECT
            + ",\"name\":\"Operators\",\"group_id\":i" + roleId + "}}', 'system')");
        execute(url, "INSERT INTO zenit_activity (model, record_id, action, origin, delta, created_at) VALUES"
            + " ('hohenheim:instance_template', '1', 'update', 'system', '{\"approved_by_user_id\":{\"before\":null,"
            + "\"after\":i" + operatorId + "}}', " + seededAt.toEpochMilli() + "), ('hohenheim:site', '" + siteId
            + "', 'update', 'system', '{\"quota_bucket\":{\"before\":null,\"after\":\"hohenheim:sites:user:"
            + operatorId + "\"}}', " + seededAt.toEpochMilli() + ")");

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
        TestDatabases.releaseHandBoundDefault();
        // The rehearsal opens its own copy; step 1 points the path back at the install itself.
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
        // 1b. M015 stored every account and group the rehearsal copy names by the UUID zenit-auth gave it; its down
        //     writes the numbers back exactly, and a rerun reaches the same UUIDs.
        String rehearsedOperator = accountUuid(rehearsalUrl, operatorId);
        assertThat(scalar(rehearsalUrl, "SELECT requested_by_user_id FROM certificates WHERE id = " + OPERATOR_CERT))
            .as("step 1b: the requester is the operator's UUID").isEqualTo(rehearsedOperator);
        MigrationKey rekey = MigrationKey.of(HohenheimMigration.STREAM, "015");
        MigrationRunner.overCompleteSet(rehearsal, withoutCreationSource).acknowledgeMissingMigrationVersions("001")
            .rollback(rekey).requireSuccess();
        assertThat(strings(rehearsalUrl, "SELECT requested_by_user_id FROM certificates WHERE id = " + OPERATOR_CERT
                + " UNION ALL SELECT approved_by_user_id FROM instance_templates WHERE id IN (1, 2)"
                + " UNION ALL SELECT group_id FROM projects WHERE id IN (" + OPERATORS_PROJECT + ", " + ORPHANED_PROJECT
                + ") UNION ALL SELECT acknowledged_by FROM servers WHERE id = 1"))
            .as("step 1b: the down restores every number, a deleted account's and group's included")
            .containsExactlyInAnyOrder(String.valueOf(operatorId), String.valueOf(operatorId),
                String.valueOf(DELETED_ACCOUNT), String.valueOf(roleId), String.valueOf(DELETED_GROUP),
                String.valueOf(operatorId));
        assertThat(strings(rehearsalUrl, "SELECT subjects FROM instance_quotas WHERE id IN (" + OPERATOR_QUOTA + ", "
                + SHARED_QUOTA + ", " + CROWD_QUOTA + ") UNION ALL SELECT quota_bucket FROM sites WHERE id = " + siteId
                + " UNION ALL SELECT quota_bucket FROM instances WHERE id = " + instanceId
                + " UNION ALL SELECT root_disk_bucket FROM instances WHERE id = " + instanceId))
            .as("step 1b: and every owner token's number, a folded stamp's and one the rekey folded included")
            .containsExactlyInAnyOrder("user:" + operatorId, "group:" + roleId + "\nuser:" + operatorId, crowd,
                pairStamp, crowdStamp, fiveStamp);
        assertThat(instanceStamps(rehearsalUrl, instanceId))
            .as("step 1b: and the stamps in the instance's history")
            .containsExactly(crowdStamp, OwnerBudget.DISK_GB.bucketOf("user:" + operatorId), fiveStamp);
        assertThat(scalar(rehearsalUrl, "SELECT sql FROM sqlite_master WHERE type = 'table' AND name = "
                + "'instance_quotas'"))
            .as("step 1b: the quota owner column is the 400 characters it was").contains("VARCHAR(400)");
        MigrationRunner.overCompleteSet(rehearsal, withoutCreationSource).acknowledgeMissingMigrationVersions("001")
            .migrate().requireSuccess();
        assertThat(scalar(rehearsalUrl, "SELECT requested_by_user_id FROM certificates WHERE id = " + OPERATOR_CERT))
            .as("step 1b: the rerun reaches the same UUID").isEqualTo(rehearsedOperator);
        String rehearsedCrowd = rekeyed(rehearsalUrl, crowd);
        assertThat(strings(rehearsalUrl, "SELECT quota_bucket FROM instances WHERE id = " + instanceId
                + " UNION ALL SELECT root_disk_bucket FROM instances WHERE id = " + instanceId))
            .as("step 1b: and the same stamps")
            .containsExactly(OwnerBudget.INSTANCES.bucketOf(rehearsedCrowd),
                OwnerBudget.DISK_GB.bucketOf(rekeyed(rehearsalUrl, five)));
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
        UUID adminUuid = UUID.fromString(accountUuid(url, adminId));
        UUID operatorUuid = UUID.fromString(accountUuid(url, operatorId));
        UUID role = UUID.fromString(scalar(url, "SELECT new_id FROM zenit_record_rekeys WHERE model = '"
            + PermissionGroupModel.MODEL_ID + "' AND old_id = '" + roleId + "'"));
        assertThat(PermissionHolders.userIdsHolding(HohenheimSources.ADMIN_ACCESS))
            .as("step 3: the admin's * grant and the operator's role both hold the admin permission")
            .contains(adminUuid, operatorUuid);
        UserPrincipal operator = new UserPrincipal(operatorUuid, "Upgrade Operator");
        assertThat(RecordGrants.recordIds(operator, SiteModel.MODEL_ID, "manage"))
            .as("step 3: the operator still manages the site").contains(String.valueOf(siteId));
        assertThat(RecordGrants.recordIds(operator, InstanceModel.MODEL_ID, "power"))
            .as("step 3: and still powers the instance").contains(String.valueOf(instanceId));
        CertificateModel certificates = Models.get(CertificateModel.class);
        Row operatorCert = certificates.findById(OPERATOR_CERT);
        assertThat((UUID) operatorCert.get(CertificateModel.REQUESTED_BY_USER_ID))
            .as("step 3: the operator's certificate stores its requester's UUID").isEqualTo(operatorUuid);
        assertThat(CertificateModel.requesterOf(operatorCert))
            .as("step 3: and reads it back as the operator's account").isEqualTo(PrincipalRef.account(operatorUuid));
        assertThat(RecordOwnership.ownerOf(CertificateModel.MODEL_ID, OPERATOR_CERT))
            .as("step 3: whom the owner rule names as its owner").isEqualTo(PrincipalRef.account(operatorUuid));
        Row unattendedCert = certificates.findById(UNATTENDED_CERT);
        assertThat(CertificateModel.requesterOf(unattendedCert))
            .as("step 3: an unattended certificate still has no requester").isNull();
        assertThat((String) unattendedCert.get(CertificateModel.REQUESTED_BY_KIND))
            .as("step 3: and no kind was invented for it").isNull();

        // 3b. Every other account and group Hohenheim stores is its UUID too: the posture acknowledger, the template
        //     approvers and the project groups (a deleted one's a fresh UUID nothing else carries), every owner token
        //     and every history value naming them.
        assertThat(ServerModel.ACKNOWLEDGER.read(Models.get(ServerModel.class).findById(1)))
            .as("step 3b: the acknowledger is the operator's account").isEqualTo(PrincipalRef.account(operatorUuid));
        InstanceTemplateModel templates = Models.get(InstanceTemplateModel.class);
        assertThat((UUID) templates.findById(1).get(InstanceTemplateModel.APPROVED_BY_USER_ID))
            .as("step 3b: the approver is the operator").isEqualTo(operatorUuid);
        UUID deletedApprover = templates.findById(2).get(InstanceTemplateModel.APPROVED_BY_USER_ID);
        assertThat(deletedApprover).as("step 3b: a deleted approver is adopted, never dropped").isNotNull()
            .isNotIn(adminUuid, operatorUuid)
            .isEqualTo(UUID.fromString(accountUuid(url, DELETED_ACCOUNT)));
        ProjectModel projects = Models.get(ProjectModel.class);
        assertThat((UUID) projects.findById(OPERATORS_PROJECT).get(ProjectModel.GROUP_ID))
            .as("step 3b: the project's group is the role's UUID").isEqualTo(role);
        assertThat(Projects.membershipPermissionOf(projects.findById(OPERATORS_PROJECT)))
            .as("step 3b: and still names its membership").isEqualTo("group.upgrade_operators");
        assertThat((UUID) projects.findById(ORPHANED_PROJECT).get(ProjectModel.GROUP_ID))
            .as("step 3b: a deleted group is adopted").isNotNull().isNotEqualTo(role);
        String operatorToken = new SubjectTag(GrantSubjectType.USER, operatorUuid).token();
        String roleToken = new SubjectTag(GrantSubjectType.GROUP, role).token();
        String crowdNow = rekeyed(url, crowd);
        String pairNow = rekeyed(url, pair);
        String fiveNow = rekeyed(url, five);
        assertThat(List.of(accountUuid(url, FIRST_DELETED_OWNER),
                accountUuid(url, FIRST_DELETED_OWNER + CROWD_DELETED_OWNERS - 1), groupUuid(url, DELETED_OWNER_GROUP)))
            .as("step 3b: the deleted owners only owner tokens name are adopted").doesNotContainNull();
        assertThat(scalar(url, "SELECT subjects FROM instance_quotas WHERE id = " + CROWD_QUOTA))
            .as("step 3b: the crowd's override holds its UUID tokens, past the 400 characters the column had")
            .isEqualTo(crowdNow).hasSizeGreaterThan(400);
        assertThat(scalar(url, "SELECT sql FROM sqlite_master WHERE type = 'table' AND name = 'instance_quotas'"))
            .as("step 3b: the quota owner column is text now").doesNotContain("VARCHAR(400)");
        assertThat(scalar(url, "SELECT name FROM sqlite_master WHERE type = 'index'"
                + " AND name = 'instance_quotas_subjects_unique'"))
            .as("step 3b: one override per owner still").isNotNull();
        assertThat(strings(url, "SELECT subjects FROM instance_quotas WHERE id IN (" + OPERATOR_QUOTA + ", "
                + SHARED_QUOTA + ") UNION ALL SELECT former_subjects FROM released_route_claims WHERE id = "
                + RELEASED_CLAIM))
            .as("step 3b: every packed owner set holds UUID tokens, sorted as they are packed")
            .containsExactlyInAnyOrder(operatorToken, HohenheimAccess.packSubjects(Set.of(roleToken, operatorToken)),
                operatorToken);
        Row stamped = Models.get(InstanceModel.class).findById(instanceId);
        assertThat(List.of(Models.get(SiteModel.class).findById(siteId).get(SiteModel.QUOTA_BUCKET),
                stamped.get(InstanceModel.QUOTA_BUCKET), stamped.get(InstanceModel.ROOT_DISK_BUCKET)))
            .as("step 3b: every bucket stamp is the bucket today's code charges its owners")
            .containsExactly(OwnerBudget.SITES.bucketOf(pairNow), OwnerBudget.INSTANCES.bucketOf(crowdNow),
                OwnerBudget.DISK_GB.bucketOf(fiveNow));
        assertThat(List.of(OwnerBudget.SITES.bucketOf(pairNow), OwnerBudget.INSTANCES.bucketOf(crowdNow),
                OwnerBudget.DISK_GB.bucketOf(fiveNow)))
            .as("step 3b: the pair stays under the limit, the crowd stays folded, the five fold now")
            .extracting(bucket -> bucket.contains("sha256:")).containsExactly(false, true, true);
        assertThat(instanceStamps(url, instanceId))
            .as("step 3b: the stamps in the instance's history follow, the folded ones included")
            .containsExactly(OwnerBudget.INSTANCES.bucketOf(crowdNow), OwnerBudget.DISK_GB.bucketOf(operatorToken),
                OwnerBudget.DISK_GB.bucketOf(fiveNow));
        assertThat(List.of(M015_ServerLanParent.SITES, M015_ServerLanParent.DATABASES, M015_ServerLanParent.INSTANCES,
                M015_ServerLanParent.DISK_GB, M015_ServerLanParent.NICS, M015_ServerLanParent.PREVIEWS))
            .as("step 3b: M015 froze the very prefixes the budgets spell")
            .containsExactly(OwnerBudget.SITES.prefix(), OwnerBudget.DATABASES.prefix(),
                OwnerBudget.INSTANCES.prefix(), OwnerBudget.DISK_GB.prefix(), OwnerBudget.NICS.prefix(),
                OwnerBudget.PREVIEWS.prefix());
        assertThat(scalar(url, "SELECT snapshot FROM zenit_revisions WHERE model = 'hohenheim:project' AND record_id = '"
                + OPERATORS_PROJECT + "'"))
            .as("step 3b: the project's revision names the role's UUID").contains(role.toString());
        assertThat(strings(url, "SELECT delta FROM zenit_activity WHERE (model = 'hohenheim:instance_template'"
                + " AND record_id = '1' AND delta LIKE '%approved_by_user_id%') OR (model = 'hohenheim:site'"
                + " AND delta LIKE '%quota_bucket%')"))
            .as("step 3b: the approval and the stamp change in history name the operator's UUID")
            .hasSize(2).allSatisfy(delta -> assertThat(delta).contains(operatorUuid.toString()));

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

        // 4c. Every stored workload restarts after a crash now, and a row written without a policy does too.
        assertThat(scalar(url, "SELECT crash_policy FROM instances WHERE id = " + instanceId))
            .as("step 4c: the workload stored with the old default restarts after a crash")
            .isEqualTo(InstanceModel.CRASH_DEFAULT);
        assertThat(scalar(url, "SELECT dflt_value FROM pragma_table_info('instances') WHERE name = 'crash_policy'"))
            .as("step 4c: the column's default is the restart policy")
            .isEqualTo("'" + InstanceModel.CRASH_DEFAULT + "'");

        // 5. The API key keeps its scopes, its zenit-auth model scope under today's spelling, and authenticates.
        Row key = Models.get(ApiKeyModel.class).find().noCache().where(ApiKeyModel.LABEL.eq("upgrade-key")).first();
        assertThat(key.get(ApiKeyModel.SCOPES))
            .as("step 5: every scope kept, in order, the model scope read today")
            .isEqualTo(List.of("cap:hohenheim:instance#power", "cap:zenit:user#read", "hohenheim.admin.access"));
        ApiKeyPrincipal keyPrincipal = ApiKeyService.authenticate(facts.getProperty("api_key.plaintext"));
        assertThat(keyPrincipal).as("step 5: the old key still authenticates").isNotNull();
        assertThat(keyPrincipal.reference()).as("step 5: as its owner")
            .isEqualTo(new PrincipalRef(ZenitPrincipalKind.ACCOUNT, operatorUuid));
        assertThat(keyPrincipal.scopes()).as("step 5: with the same scopes")
            .containsExactly("cap:hohenheim:instance#power", "cap:zenit:user#read", "hohenheim.admin.access");

        // 6. Activity on framework models reads through today's model ids; no old spelling is left behind.
        List<ActivityEntry> userHistory = history(Models.get(UserModel.class), operatorUuid);
        assertThat(userHistory).as("step 6: the operator's creation is in its history")
            .anySatisfy(entry -> assertThat(entry.action()).isEqualTo(ZenitActivityAction.CREATE.id().toString()));
        assertThat(strings(url, "SELECT DISTINCT model FROM zenit_activity"))
            .as("step 6: every activity row names today's model id")
            .contains("zenit:user", "zenit:grant", "zenit:permission_group", "zenit:record_grant", "zenit:api_key",
                "zenit:notification_inbox_item", "zenit:notification_delivery", "zenit:saved_view",
                "zenit:record_schedule_step")
            .noneMatch(model -> model.startsWith("zenit-auth:") || model.startsWith("zenit-comms:")
                || model.startsWith("zenit:comms_"));

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
        AccessContext admin = AccessContext.of(TenantConduits.stubFor(new UserPrincipal(adminUuid, "Upgrade Admin")));
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

        // 11. A receipt claimed under the old type key replays: the hub send, which bound no actor. The saved view's
        //     command was bound to the admin's pre-UUID number, which core unbinds (M012: a kindless number cannot
        //     say whose it was), so replaying it is refused rather than granted to whoever holds that number now.
        assertThat(scalar(url,
                "SELECT type_key FROM zenit_command_receipts WHERE command_key = 'comms_hub:1:upgrade-journey'"))
            .as("step 11: the receipt keeps the type key it was claimed under").isEqualTo("zenit-comms:hub_send");
        HubIdempotency.Outcome hub = HubIdempotency.begin(Integer.parseInt(facts.getProperty("hub.project")),
            facts.getProperty("hub.key"), facts.getProperty("hub.body"));
        assertThat(hub).as("step 11: the same hub send replays").isInstanceOf(HubIdempotency.Outcome.Replay.class);
        assertThat(((HubIdempotency.Outcome.Replay) hub).response()).as("step 11: with its stored response")
            .isEqualTo(facts.getProperty("hub.response"));
        assertThat(scalar(url, "SELECT actor FROM zenit_command_receipts WHERE command_key = '"
                + facts.getProperty("saved_view.command") + "'"))
            .as("step 11: the saved view's receipt binds no principal any more")
            .startsWith(PrincipalColumns.UNBOUND_PREFIX);
        assertThatThrownBy(() -> SavedViews.save(admin, SavedViews.hostOf(MEDIA_SOURCE),
                UUID.fromString(facts.getProperty("saved_view.command")),
                SavedView.Draft.of("Recent uploads", false, SavedQuery.EMPTY), 0))
            .as("step 11: so its command no longer replays").hasMessageContaining("already bound");

        // 12. The operator's session still signs them in.
        Session session = Zenit.getSessionStore().get(SessionToken.of(facts.getProperty("session.secret")));
        assertThat(session).as("step 12: the session resolves").isNotNull();
        assertThat(session.get(AuthKeys.USER_ID)).as("step 12: to the operator").isEqualTo(operatorUuid);

        // 13. The rows Hohenheim's alert wrote, in today's notifications_ tables: both inboxes, both deliveries on
        //     today's inbox channel, and no comms_ table left behind.
        assertThat(strings(url, "SELECT name FROM sqlite_master WHERE type = 'table' AND name LIKE 'comms\\_%' "
                + "ESCAPE '\\'"))
            .as("step 13: every comms_ table was renamed").isEmpty();
        assertThat(strings(url, "SELECT name FROM sqlite_master WHERE type = 'table' AND name LIKE "
                + "'notifications\\_%' ESCAPE '\\'"))
            .as("step 13: to its notifications_ name").contains("notifications_deliveries", "notifications_inbox_items",
                "notifications_hub_projects", "notifications_watch_queue");
        Db.run(HohenheimDatabase.datasource(), () -> {
            List<Row> inbox = Models.get(NotificationInboxModel.class).find().noCache().all();
            assertThat(inbox).extracting(row -> row.get(NotificationInboxModel.RECIPIENT))
                .as("step 13: each administrator's inbox item")
                .containsExactlyInAnyOrder(NotificationInboxOwners.userKey(adminUuid),
                    NotificationInboxOwners.userKey(operatorUuid));
            assertThat(inbox).extracting(row -> row.get(NotificationInboxModel.NOTIFICATION_KEY))
                .as("step 13: for the certificate alert").containsOnly("hohenheim:cert_expiring");
            List<Row> deliveries = Models.get(DeliveryModel.class).find().noCache().all();
            assertThat(deliveries).extracting(row -> DeliveryChannel.find(row.get(DeliveryModel.CHANNEL)))
                .as("step 13: both deliveries read as the inbox channel").containsOnly(DeliveryChannel.INBOX);
        });

        // 14. The next boot finds nothing left in the old spellings.
        assertThat(strings(url, "SELECT action FROM zenit_record_schedule_steps UNION SELECT model FROM "
                + "zenit_record_schedules UNION SELECT model FROM auth_record_grants UNION SELECT type FROM "
                + "system_task"))
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

    /** @return {@code value} as an SQLite string literal, its newlines spelled with char(10) */
    private static String literal(String value) {
        return "'" + value.replace("'", "''").replace("\n", "' || char(10) || '") + "'";
    }

    /** @return the packed owner set {@code pack} names once every owner is its UUID, as the rekey maps store them */
    private static String rekeyed(String url, String pack) throws SQLException {
        Set<String> owners = new HashSet<>();
        for (String owner : pack.split("\n")) {
            String[] subject = owner.split(":");
            int old = Integer.parseInt(subject[1]);
            String id = subject[0].equals(GrantSubjectType.USER.key()) ? accountUuid(url, old) : groupUuid(url, old);
            assertThat(id).as("the owner %s is mapped", owner).isNotNull();
            owners.add(subject[0] + ":" + id);
        }
        return HohenheimAccess.packSubjects(owners);
    }

    /** @return the instance's stamp change in history: the quota bucket after, the root disk's before and after */
    private static List<String> instanceStamps(String url, int instanceId) throws SQLException {
        Map<?, ?> delta = (Map<?, ?>) Zenit.DRY.parse(scalar(url, "SELECT delta FROM zenit_activity WHERE model ="
            + " 'hohenheim:instance' AND record_id = '" + instanceId + "' AND delta LIKE '%root_disk_bucket%'"));
        Map<?, ?> quota = (Map<?, ?>) delta.get("quota_bucket");
        Map<?, ?> rootDisk = (Map<?, ?>) delta.get("root_disk_bucket");
        return List.of(String.valueOf(quota.get("after")), String.valueOf(rootDisk.get("before")),
            String.valueOf(rootDisk.get("after")));
    }

    /** @return the UUID zenit-auth renumbered permission group {@code old} to, as the rekey map stores it */
    private static String groupUuid(String url, int old) throws SQLException {
        return scalar(url, "SELECT new_id FROM zenit_record_rekeys WHERE model = '" + PermissionGroupModel.MODEL_ID
            + "' AND old_id = '" + old + "'");
    }

    private static void execute(String url, String sql) throws SQLException {
        try (Connection connection = DriverManager.getConnection(url);
             Statement statement = connection.createStatement()) {
            statement.executeUpdate(sql);
        }
    }

    /** @return the UUID zenit-auth renumbered account {@code old} to, as the rekey map stores it */
    private static String accountUuid(String url, int old) throws SQLException {
        return scalar(url, "SELECT new_id FROM zenit_principal_rekeys WHERE kind = '" + ZenitPrincipalKind.ACCOUNT.id()
            + "' AND old_id = '" + old + "'");
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
