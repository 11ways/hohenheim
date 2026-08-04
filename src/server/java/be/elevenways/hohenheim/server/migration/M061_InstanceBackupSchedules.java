package be.elevenways.hohenheim.server.migration;

import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.server.schedule.InstanceBackupAction;
import be.elevenways.protoblast.common.Blast;
import be.elevenways.zenit.common.orm.datasource.Datasource;
import be.elevenways.zenit.common.orm.datasource.Db;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.datasource.sql.SqlDatasource;
import be.elevenways.zenit.common.orm.migration.Migration;
import be.elevenways.zenit.common.orm.migration.MigrationBuilder;
import be.elevenways.zenit.common.task.record.M001_CreateRecordScheduleTables;
import be.elevenways.zenit.common.task.record.RecordScheduleModel;
import be.elevenways.zenit.common.task.record.RecordScheduleStepModel;

import java.util.List;
import java.util.Map;

/**
 * Migrates per-instance backups off the daily-task workaround onto real per-record
 * schedules: every live instance with the old backup_enabled flag gets a one-step
 * "Nightly backup" schedule (cron 0 4 * * *, server zone, system authority -- the old
 * task ran as operator config, so run_as stays null), then the flag column is dropped.
 * The retired BackupInstances task's system_task row is reaped by ordinary catalog
 * reconciliation on next boot.
 *
 * AIDEV-NOTE: server source set because the backfill reads the doomed column raw
 * (the model field is deleted in this same commit) and references the backup action's
 * identifier; the count of converted instances is slogged -- a data heal must report
 * what it changed.
 *
 * @author Jelle De Loecker
 */
public class M061_InstanceBackupSchedules extends Migration {

    public M061_InstanceBackupSchedules() {
        super("2026_08_04_110000", "Instance backup schedules");
        // The backfill writes zenit_record_schedules; make the ordering structural.
        dependsOn(M001_CreateRecordScheduleTables.class);
    }

    @Override
    public void up(MigrationBuilder schema) {
        schema.data("Convert backup_enabled flags into per-instance backup schedules", "1",
                M061_InstanceBackupSchedules::backfill);
        schema.alterTable("instances", table -> table.dropColumn("backup_enabled"));
    }

    @Override
    public void down(MigrationBuilder schema) {
        schema.alterTable("instances", table -> table.addColumn("backup_enabled",
                be.elevenways.zenit.common.orm.datasource.ColumnType.BOOLEAN,
                col -> col.nullable(false).defaultValue(false).ifNotExists()));
    }

    /** Reads the doomed flag column raw and reports the converted count. */
    public static void backfill(Datasource datasource) {
        SqlDatasource sql = datasource.unwrap(SqlDatasource.class);
        List<Row> flagged = sql.rawQuery(
                "SELECT id FROM instances WHERE backup_enabled = 1 AND deleted_at IS NULL");

        Db.run(datasource, () -> {
            RecordScheduleModel schedules = new RecordScheduleModel();
            RecordScheduleStepModel steps = new RecordScheduleStepModel();
            int converted = 0;

            for (Row instance : flagged) {
                Object id = instance.get("id");

                Row schedule = schedules.createEmptyRow();
                schedule.set(RecordScheduleModel.MODEL, InstanceModel.MODEL_ID.toString());
                schedule.set(RecordScheduleModel.RECORD_ID, String.valueOf(id));
                schedule.set(RecordScheduleModel.NAME, "Nightly backup");
                schedule.set(RecordScheduleModel.CRON, "0 4 * * *");
                schedule.set(RecordScheduleModel.ENABLED, true);
                schedules.save(schedule);

                Row step = steps.createEmptyRow();
                step.set(RecordScheduleStepModel.SCHEDULE_ID, schedule.get(RecordScheduleModel.ID));
                step.set(RecordScheduleStepModel.POSITION, 1);
                step.set(RecordScheduleStepModel.ACTION, InstanceBackupAction.ID.toString());
                step.set(RecordScheduleStepModel.OFFSET_SECONDS, 0);
                step.set(RecordScheduleStepModel.FAILURE_POLICY, RecordScheduleStepModel.POLICY_ABORT);
                steps.save(step);

                converted++;
            }

            Blast.slog("hohenheim.migration.backup_schedules_converted",
                    Map.of("count", converted));
        });
    }
}
