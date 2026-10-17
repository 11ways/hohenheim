package be.elevenways.hohenheim.test.migration;

import be.elevenways.hohenheim.HohenheimActivityAction;
import be.elevenways.hohenheim.migration.HohenheimMigration;
import be.elevenways.hohenheim.model.CertificateModel;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.server.migration.M017_InstanceHealthOnTheRow;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.common.orm.activity.M015_ConvertActivity;
import be.elevenways.zenit.common.orm.datasource.Db;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.field.DateTimeField;
import be.elevenways.zenit.common.orm.field.IntegerField;
import be.elevenways.zenit.common.orm.field.StringField;
import be.elevenways.zenit.common.orm.field.TextField;
import be.elevenways.zenit.common.orm.migration.FrozenModel;
import be.elevenways.zenit.common.orm.migration.MigrationKey;
import be.elevenways.zenit.common.orm.migration.MigrationResult;
import be.elevenways.zenit.common.orm.migration.MigrationRunnerResult;
import be.elevenways.zenit.server.orm.SqliteDatasource;
import be.elevenways.zenit.server.orm.migration.MigrationRunner;
import be.elevenways.zenit.test.support.HistoryRows;
import be.elevenways.zenit.test.support.MigrationTestSupport;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static be.elevenways.hohenheim.test.migration.InstallsAt.withIntegrityMode;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * An install through M016 whose workloads' health lives only in their activity upgrades to rows that carry it: the
 * newest cause, else a correction to error from before causes (its English parsed once), the last start, and a
 * certificate's last failure moment, whether core M015 converted the activity before M017 reads it or after.
 *
 * @author Jelle De Loecker
 * @since  0.10.0
 */
class InstanceHealthMigrationTest {

    private static final Instant T0 = Instant.parse("2026-10-01T10:00:00Z");

    // A field belongs to one schema, so each table shape owns its fields.
    private static final IntegerField SEEDED_ID = IntegerField.builder().name("id").build();
    private static final StringField NAME = StringField.builder().name("name").nullable(true).build();
    private static final StringField STATUS = StringField.builder().name("status").nullable(true).build();
    private static final FrozenModel SEEDED_INSTANCES = new FrozenModel("instances", SEEDED_ID, NAME, STATUS);

    private static final IntegerField ID = IntegerField.builder().name("id").build();
    private static final StringField UPGRADED_NAME = StringField.builder().name("name").nullable(true).build();
    private static final StringField CAUSE = StringField.builder().name("stoppage_cause").nullable(true).build();
    private static final TextField DETAIL = TextField.builder("stoppage_detail").nullable(true).build();
    private static final DateTimeField AT = DateTimeField.builder().name("stoppage_at").nullable(true).build();
    private static final StringField WITHHELD = StringField.builder().name("restart_withheld").nullable(true).build();
    private static final DateTimeField STARTED = DateTimeField.builder().name("started_at").nullable(true).build();
    private static final FrozenModel INSTANCES = new FrozenModel("instances", ID, UPGRADED_NAME, CAUSE, DETAIL, AT,
        WITHHELD, STARTED);

    private static final IntegerField SEEDED_CERT_ID = IntegerField.builder().name("id").build();
    private static final StringField NICE_NAME = StringField.builder().name("nice_name").nullable(true).build();
    private static final FrozenModel SEEDED_CERTIFICATES = new FrozenModel("certificates", SEEDED_CERT_ID, NICE_NAME);

    private static final IntegerField CERT_ID = IntegerField.builder().name("id").build();
    private static final DateTimeField FAILED_AT =
        DateTimeField.builder().name("renewal_failed_at").nullable(true).build();
    private static final FrozenModel CERTIFICATES = new FrozenModel("certificates", CERT_ID, FAILED_AT);

    @Test
    void anInstallThatConvertedItsActivityFirstKeepsEveryHealthReadingOnItsRows() throws Exception {
        // 1. An install through M016 that has not converted its activity yet holds the seeded rows (see seed).
        SqliteDatasource datasource = beforeConversion("hohenheim-instance-health-converted");
        Seeded seeded = seed(datasource);

        // 2. Core M015 converts the activity first; the upgrade under integrity=fail then applies M017 alone,
        //    reading the frozen v1 copy.
        withIntegrityMode("fail", () -> new MigrationRunner(datasource,
            InstallsAt.discoveredThrough(datasource.getDatasourceIdentifier(), "016")).migrate().requireSuccess());
        assertThat(M015_ConvertActivity.appliedOn(datasource)).as("step 2: core M015 converted the activity")
            .isTrue();
        withIntegrityMode("fail", () -> {
            MigrationRunnerResult upgrade = new MigrationRunner(datasource,
                InstallsAt.discoveredThrough(datasource.getDatasourceIdentifier(), "017")).migrate();
            assertThat(upgrade.isSuccess()).as("step 2: the upgrade from 016 succeeds").isTrue();
            assertThat(upgrade.getResults().stream().filter(MigrationResult::isApplied)
                .map(MigrationResult::getVersion).toList()).as("step 2: it applies exactly M017")
                .containsExactly("017");
        });

        // 3-7. Every health reading is on its row.
        assertHealthOnTheRows(datasource, seeded);

        // 8. Running the fill again overwrites nothing already stored, and the rollback drops the columns.
        Db.run(datasource, () -> INSTANCES.find().where(ID.eq(seeded.caused()))
            .assign(CAUSE, "workload_never_ready").updateAll());
        M017_InstanceHealthOnTheRow.fillInstances(datasource);
        M017_InstanceHealthOnTheRow.fillCertificates(datasource);
        assertThat((String) row(datasource, seeded.caused()).get(CAUSE)).as("step 8: a stored stoppage stays")
            .isEqualTo("workload_never_ready");
        assertThat(List.of(row(datasource, seeded.legacy()).get(CAUSE), row(datasource, seeded.legacy()).get(AT)))
            .as("step 8: and the converted one is the same").containsExactly("reconciled", at(5));
        assertThat(new M017_InstanceHealthOnTheRow().getIrreversibleReason())
            .as("step 8: the migration is reversible").isNull();
        withIntegrityMode("fail", () -> new MigrationRunner(datasource,
                InstallsAt.discoveredThrough(datasource.getDatasourceIdentifier(), "017"))
            .rollback(MigrationKey.of(HohenheimMigration.STREAM, "017")).requireSuccess());
        assertThat(datasource.liveColumnNames("instances")).as("step 8: the rollback drops the columns")
            .doesNotContain("stoppage_cause", "stoppage_detail", "stoppage_at", "restart_withheld", "started_at");
        assertThat(datasource.liveColumnNames("certificates")).as("step 8: on certificates too")
            .doesNotContain("renewal_failed_at");
        datasource.close();
    }

    @Test
    void anInstallThatReadsItsActivityBeforeConvertingItKeepsEveryHealthReadingOnItsRows() throws Exception {
        // 1. The same install through M016, its activity not converted yet.
        SqliteDatasource datasource = beforeConversion("hohenheim-instance-health-unconverted");
        Seeded seeded = seed(datasource);

        // 2. One upgrade under integrity=fail applies M017 while zenit_activity is still the live table, then core
        //    M015 converts it.
        withIntegrityMode("fail", () -> {
            MigrationRunnerResult upgrade = new MigrationRunner(datasource,
                InstallsAt.discoveredThrough(datasource.getDatasourceIdentifier(), "017")).migrate();
            assertThat(upgrade.isSuccess()).as("step 2: the upgrade from 016 succeeds").isTrue();
            List<String> applied = upgrade.getResults().stream().filter(MigrationResult::isApplied)
                .map(MigrationResult::getVersion).toList();
            String conversion = new M015_ConvertActivity().getVersion();
            assertThat(applied).as("step 2: it applies M017 and core M015").contains("017", conversion);
            assertThat(applied.indexOf("017")).as("step 2: M017 before core M015")
                .isLessThan(applied.indexOf(conversion));
        });

        // 3-7. Every health reading is on its row.
        assertHealthOnTheRows(datasource, seeded);
        datasource.close();
    }

    /** The seeded records' ids. */
    private record Seeded(int legacy, int caused, int failed, int stopped, int quiet, int cert) {
    }

    /** A new SQLite install through Hohenheim's M016 that stopped right before core M015. */
    private static SqliteDatasource beforeConversion(@NonNull String name) throws Exception {
        File file = File.createTempFile(name, ".db");
        file.delete();
        file.deleteOnExit();
        SqliteDatasource datasource = new SqliteDatasource("jdbc:sqlite:" + file.getAbsolutePath());
        MigrationTestSupport.migrateBefore(datasource, M015_ConvertActivity.class,
            InstallsAt.discoveredThrough(datasource.getDatasourceIdentifier(), "016"));
        assertThat(datasource.liveColumnNames("instances")).as("step 1: Hohenheim's tables are there before M015")
            .contains("status");
        return datasource;
    }

    /**
     * Seeds a workload whose only fact is a correction to error with crash-loop protection (after a deploy), one with
     * an exit cause newer than an old correction, one whose start failed after a deploy, one corrected to stopped,
     * one with no history, and a certificate that failed twice, as v1 activity rows.
     */
    private static Seeded seed(@NonNull SqliteDatasource datasource) {
        int legacy = instance(datasource, "legacy");
        int caused = instance(datasource, "caused");
        int failed = instance(datasource, "failed");
        int stopped = instance(datasource, "stopped");
        int quiet = instance(datasource, "quiet");
        int cert = certificate(datasource);
        activity(datasource, InstanceModel.MODEL_ID, legacy, HohenheimActivityAction.DEPLOYED, "h", 0);
        activity(datasource, InstanceModel.MODEL_ID, legacy, HohenheimActivityAction.RECONCILED,
            "running -> error (died without an observed stop; the daemon reports the workload absent;"
                + " crash loop, automatic restarts suspended)", 5);
        activity(datasource, InstanceModel.MODEL_ID, caused, HohenheimActivityAction.RECONCILED,
            "running -> error (died without an observed stop; the daemon reports the workload stopped)", 1);
        activity(datasource, InstanceModel.MODEL_ID, caused, HohenheimActivityAction.WORKLOAD_EXITED, "137", 2);
        activity(datasource, InstanceModel.MODEL_ID, failed, HohenheimActivityAction.DEPLOYED, "h", 3);
        activity(datasource, InstanceModel.MODEL_ID, failed, HohenheimActivityAction.WORKLOAD_START_FAILED,
            "image missing", 4);
        activity(datasource, InstanceModel.MODEL_ID, stopped, HohenheimActivityAction.RECONCILED,
            "running -> stopped (died without an observed stop; the daemon reports the workload stopped)", 6);
        activity(datasource, CertificateModel.MODEL_ID, cert, HohenheimActivityAction.RENEWAL_FAILED, "boom", 7);
        activity(datasource, CertificateModel.MODEL_ID, cert, HohenheimActivityAction.RENEWAL_FAILED,
            "boom again", 8);
        return new Seeded(legacy, caused, failed, stopped, quiet, cert);
    }

    /** Steps 3 to 7: what the upgrade stored on each seeded row. */
    private static void assertHealthOnTheRows(@NonNull SqliteDatasource datasource, @NonNull Seeded seeded) {
        int legacy = seeded.legacy();
        int caused = seeded.caused();
        int failed = seeded.failed();
        int stopped = seeded.stopped();
        int quiet = seeded.quiet();
        int cert = seeded.cert();

        // 3. The correction from before causes is the stoppage it records, crash-loop protection as why; the deploy
        //    before it is the last start.
        Row first = row(datasource, legacy);
        assertThat(List.of(first.get(CAUSE), first.get(WITHHELD), first.get(AT), first.get(STARTED)))
            .as("step 3: the converted correction and the last start")
            .containsExactly("reconciled", "by_crash_loop", at(5), at(0));
        assertThat((String) first.get(DETAIL)).as("step 3: a correction keeps no detail").isNull();

        // 4. A stored cause wins over an older correction, with its detail and its verb's restart fact.
        Row second = row(datasource, caused);
        assertThat(List.of(second.get(CAUSE), second.get(DETAIL), second.get(WITHHELD), second.get(AT)))
            .as("step 4: the newest cause").containsExactly("workload_exited", "137", "by_policy", at(2));

        // 5. A failed start after a deploy names no start.
        Row third = row(datasource, failed);
        assertThat(List.of(third.get(CAUSE), third.get(DETAIL), third.get(WITHHELD)))
            .as("step 5: the failed start").containsExactly("workload_start_failed", "image missing", "none");
        assertThat((Instant) third.get(STARTED)).as("step 5: and no start").isNull();

        // 6. A correction to stopped and a record without history store nothing.
        for (int id : List.of(stopped, quiet)) {
            Row untouched = row(datasource, id);
            assertThat(List.of(String.valueOf((Object) untouched.get(CAUSE)),
                    String.valueOf((Object) untouched.get(AT)), String.valueOf((Object) untouched.get(STARTED))))
                .as("step 6: nothing to store for " + untouched.get(UPGRADED_NAME)).containsOnly("null");
        }

        // 7. The certificate's moment is its newest failure.
        assertThat(certificateFailedAt(datasource, cert)).as("step 7: the newest failure").isEqualTo(at(8));
    }

    private static Instant at(int minutes) {
        return T0.plusSeconds(60L * minutes);
    }

    private static int instance(SqliteDatasource datasource, String name) {
        List<Integer> ids = new ArrayList<>();
        Db.run(datasource, () -> {
            Row row = SEEDED_INSTANCES.createEmptyRow();
            row.set(NAME, name);
            row.set(STATUS, InstanceModel.STATUS_ERROR);
            ids.add(SEEDED_INSTANCES.save(row).get(SEEDED_ID));
        });
        return ids.get(0);
    }

    private static int certificate(SqliteDatasource datasource) {
        List<Integer> ids = new ArrayList<>();
        Db.run(datasource, () -> {
            Row row = SEEDED_CERTIFICATES.createEmptyRow();
            row.set(NICE_NAME, "failing.test");
            ids.add(SEEDED_CERTIFICATES.save(row).get(SEEDED_CERT_ID));
        });
        return ids.get(0);
    }

    private static void activity(SqliteDatasource datasource, Identifier model, int recordId,
                                 HohenheimActivityAction action, String detail, int minutes) {
        HistoryRows.legacyRow(model, String.valueOf(recordId)).action(action.id().toString()).origin("system")
            .detail(detail).createdAt(at(minutes)).insert(datasource);
    }

    private static Row row(SqliteDatasource datasource, int id) {
        List<Row> found = new ArrayList<>();
        Db.run(datasource, () -> found.add(INSTANCES.find().where(ID.eq(id)).first()));
        return found.get(0);
    }

    private static Instant certificateFailedAt(SqliteDatasource datasource, int id) {
        List<Instant> found = new ArrayList<>();
        Db.run(datasource, () -> found.add(CERTIFICATES.find().where(CERT_ID.eq(id)).first().get(FAILED_AT)));
        return found.get(0);
    }
}
