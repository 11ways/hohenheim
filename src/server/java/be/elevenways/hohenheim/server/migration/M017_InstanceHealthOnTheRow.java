package be.elevenways.hohenheim.server.migration;

import be.elevenways.hohenheim.HohenheimActivityAction;
import be.elevenways.hohenheim.migration.HohenheimMigration;
import be.elevenways.hohenheim.model.CertificateModel;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.protoblast.common.Blast;
import be.elevenways.zenit.common.orm.datasource.ColumnType;
import be.elevenways.zenit.common.orm.datasource.Datasource;
import be.elevenways.zenit.common.orm.datasource.Db;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.field.DateTimeField;
import be.elevenways.zenit.common.orm.field.IntegerField;
import be.elevenways.zenit.common.orm.field.LongField;
import be.elevenways.zenit.common.orm.field.StringField;
import be.elevenways.zenit.common.orm.field.TextField;
import be.elevenways.zenit.common.orm.migration.FrozenModel;
import be.elevenways.zenit.common.orm.migration.MigrationBuilder;
import be.elevenways.zenit.common.orm.query.SortOrder;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Stores each workload's health facts on its instance row (the stoppage behind an ERROR status, its last start) and
 * each certificate's last failure moment, read once from the activity rows that were their only record.
 *
 * AIDEV-NOTE: health stops reading history here (folio-history-DESIGN-v3.2 G.7, P3): the history wave prunes it and
 * stops writing the English detail a correction from before 2026-10-08 is the only record of. The rows are chosen
 * exactly as the readers chose them before: the newest cause row, else the newest reconciler correction when it
 * settled to {@code error} (stored as the stoppage {@code reconciled}); the newest of a deploy and a failed start,
 * when it is the deploy; the newest renewal failure. Only empty columns are filled, so running it again changes
 * nothing. Reversible: the down drops the new columns, and the older readers read the activity rows again.
 *
 * @author Jelle De Loecker
 * @since  0.10.0
 */
public class M017_InstanceHealthOnTheRow extends HohenheimMigration {

    static final String INSTANCES = "instances";
    static final String CERTIFICATES = "certificates";
    static final String ACTIVITY = "zenit_activity";

    static final String STOPPAGE_CAUSE = "stoppage_cause";
    static final String STOPPAGE_DETAIL = "stoppage_detail";
    static final String STOPPAGE_AT = "stoppage_at";
    static final String RESTART_WITHHELD = "restart_withheld";
    static final String STARTED_AT = "started_at";
    static final String RENEWAL_FAILED_AT = "renewal_failed_at";

    private static final String EVENT = "hohenheim.migration.instance_health_on_the_row";

    private static final int BATCH_SIZE = 500;

    /** Between the stored and the settled status in a correction's detail, as the reconciler wrote it. */
    private static final String ARROW = " -> ";

    /** What a correction's detail added when crash-loop protection held the restart back. */
    private static final String CRASH_LOOP_NOTE = "; crash loop, automatic restarts suspended";

    public M017_InstanceHealthOnTheRow() {
        super("017", "Instance health on the instance row", false);
    }

    @Override
    public void up(@NonNull MigrationBuilder schema) {
        // Without a transaction, so a retry after a failed fill finds the columns already there.
        schema.alterTable(INSTANCES, table -> {
            table.addColumn(STOPPAGE_CAUSE, ColumnType.STRING, col -> col.maxLength(64).nullable().ifNotExists());
            table.addColumn(STOPPAGE_DETAIL, ColumnType.TEXT, col -> col.nullable().ifNotExists());
            table.addColumn(STOPPAGE_AT, ColumnType.DATETIME, col -> col.nullable().ifNotExists());
            table.addColumn(RESTART_WITHHELD, ColumnType.STRING, col -> col.maxLength(32).nullable().ifNotExists());
            table.addColumn(STARTED_AT, ColumnType.DATETIME, col -> col.nullable().ifNotExists());
        });
        schema.alterTable(CERTIFICATES, table ->
            table.addColumn(RENEWAL_FAILED_AT, ColumnType.DATETIME, col -> col.nullable().ifNotExists()));
        schema.data("store each workload's stoppage and last start from its activity", "1",
            M017_InstanceHealthOnTheRow::fillInstances);
        schema.data("store each certificate's last failure moment from its activity", "1",
            M017_InstanceHealthOnTheRow::fillCertificates);
    }

    @Override
    public void down(@NonNull MigrationBuilder schema) {
        schema.alterTable(CERTIFICATES, table -> table.dropColumn(RENEWAL_FAILED_AT));
        schema.alterTable(INSTANCES, table -> {
            table.dropColumn(STARTED_AT);
            table.dropColumn(RESTART_WITHHELD);
            table.dropColumn(STOPPAGE_AT);
            table.dropColumn(STOPPAGE_DETAIL);
            table.dropColumn(STOPPAGE_CAUSE);
        });
    }

    /** Fills every empty stoppage and last start from the newest activity rows that recorded them. */
    public static void fillInstances(@NonNull Datasource datasource) {
        Map<String, HohenheimActivityAction> causes = new HashMap<>();
        Map<String, HohenheimActivityAction> starts = new HashMap<>();
        for (HohenheimActivityAction action : HohenheimActivityAction.values()) {
            if (action.errorCause().isCause()) {
                causes.put(action.id().toString(), action);
            }
            if (action == HohenheimActivityAction.DEPLOYED || (action.errorCause().isCause()
                    && action.errorPhase() == HohenheimActivityAction.ErrorPhase.START)) {
                starts.put(action.id().toString(), action);
            }
        }
        String reconciled = HohenheimActivityAction.RECONCILED.id().toString();
        List<String> actions = new ArrayList<>(causes.keySet());
        actions.addAll(starts.keySet());
        actions.add(reconciled);
        Map<String, Row> newestCause = new HashMap<>();
        Map<String, Row> newestCorrection = new HashMap<>();
        Map<String, Row> newestStart = new HashMap<>();
        IntegerField id = IntegerField.builder("id").build();
        StringField cause = StringField.builder(STOPPAGE_CAUSE).nullable(true).build();
        TextField detail = TextField.builder(STOPPAGE_DETAIL).nullable(true).build();
        DateTimeField at = DateTimeField.builder(STOPPAGE_AT).nullable(true).build();
        StringField withheld = StringField.builder(RESTART_WITHHELD).nullable(true).build();
        DateTimeField startedAt = DateTimeField.builder(STARTED_AT).nullable(true).build();
        int[] counts = new int[3];
        Db.run(datasource, () -> {
            newestPerRecord(InstanceModel.MODEL_ID.toString(), actions, row -> {
                String action = row.get(Activity.ACTION);
                if (causes.containsKey(action)) {
                    newestCause.putIfAbsent(row.get(Activity.RECORD_ID), row);
                }
                if (starts.containsKey(action)) {
                    newestStart.putIfAbsent(row.get(Activity.RECORD_ID), row);
                }
                if (reconciled.equals(action)) {
                    newestCorrection.putIfAbsent(row.get(Activity.RECORD_ID), row);
                }
            });
            FrozenModel instances = new FrozenModel(INSTANCES, id, cause, detail, at, withheld, startedAt);
            for (Row instance : instances.find().all()) {
                String key = String.valueOf((Object) instance.get(id));
                if (instance.get(cause) == null) {
                    Row stopped = newestCause.get(key);
                    Correction correction = stopped != null || !newestCorrection.containsKey(key) ? null
                        : correctionOf(newestCorrection.get(key).get(Activity.DETAIL));
                    if (stopped != null) {
                        HohenheimActivityAction verb = causes.get(stopped.get(Activity.ACTION));
                        instances.find().where(id.eq(instance.get(id)))
                            .assign(cause, verb.id().getPath())
                            .assign(detail, stopped.get(Activity.DETAIL))
                            .assign(at, stopped.get(Activity.CREATED_AT))
                            .assign(withheld, InstanceModel.keyOf(verb.restartWithheld()))
                            .updateAll();
                        counts[0]++;
                    } else if (correction != null && InstanceModel.STATUS_ERROR.equals(correction.settled())) {
                        instances.find().where(id.eq(instance.get(id)))
                            .assign(cause, HohenheimActivityAction.RECONCILED.id().getPath())
                            .assign(at, newestCorrection.get(key).get(Activity.CREATED_AT))
                            .assign(withheld, InstanceModel.keyOf(correction.crashLoop()
                                ? HohenheimActivityAction.RestartWithheld.BY_CRASH_LOOP
                                : HohenheimActivityAction.RestartWithheld.BY_POLICY))
                            .updateAll();
                        counts[1]++;
                    }
                }
                Row start = newestStart.get(key);
                if (instance.get(startedAt) == null && start != null
                        && HohenheimActivityAction.DEPLOYED.id().toString().equals(start.get(Activity.ACTION))) {
                    instances.find().where(id.eq(instance.get(id)))
                        .assign(startedAt, start.get(Activity.CREATED_AT))
                        .updateAll();
                    counts[2]++;
                }
            }
        });
        Blast.log(EVENT, "causes", counts[0], "corrections", counts[1], "starts", counts[2]);
    }

    /** Fills every empty last failure moment from the certificate's newest renewal failure row. */
    public static void fillCertificates(@NonNull Datasource datasource) {
        IntegerField id = IntegerField.builder("id").build();
        DateTimeField failedAt = DateTimeField.builder(RENEWAL_FAILED_AT).nullable(true).build();
        Map<String, Row> newest = new HashMap<>();
        int[] count = new int[1];
        Db.run(datasource, () -> {
            newestPerRecord(CertificateModel.MODEL_ID.toString(),
                List.of(HohenheimActivityAction.RENEWAL_FAILED.id().toString()),
                row -> newest.putIfAbsent(row.get(Activity.RECORD_ID), row));
            FrozenModel certificates = new FrozenModel(CERTIFICATES, id, failedAt);
            for (Row certificate : certificates.find().where(failedAt.isNull()).all()) {
                Row failure = newest.get(String.valueOf((Object) certificate.get(id)));
                if (failure != null) {
                    certificates.find().where(id.eq(certificate.get(id)))
                        .assign(failedAt, failure.get(Activity.CREATED_AT))
                        .updateAll();
                    count[0]++;
                }
            }
        });
        Blast.log(EVENT, "certificate failures", count[0]);
    }

    /**
     * The correction a stored reconciler row records, read as the reconciler's writer shaped it before 2026-10-17.
     *
     * @return the correction, null for a detail no correction wrote
     */
    static @Nullable Correction correctionOf(@Nullable String detail) {
        int arrow = detail == null ? -1 : detail.indexOf(ARROW);
        if (arrow < 0) {
            return null;
        }
        String rest = detail.substring(arrow + ARROW.length());
        int end = rest.indexOf(' ');
        return new Correction(end < 0 ? rest : rest.substring(0, end), detail.contains(CRASH_LOOP_NOTE));
    }

    /**
     * A correction as a reconciler row records it.
     *
     * @param settled   the status the record was corrected to
     * @param crashLoop whether crash-loop protection held the restart back
     */
    record Correction(@NonNull String settled, boolean crashLoop) {
    }

    /** Hands every activity row of one model and these actions to the visitor, newest first per record. */
    private static void newestPerRecord(@NonNull String model, @NonNull List<String> actions,
                                        @NonNull Consumer<Row> visitor) {
        FrozenModel activity = Activity.ROWS;
        Long cursor = null;
        while (true) {
            var query = activity.find().where(Activity.MODEL.eq(model)).where(Activity.ACTION.in(actions));
            if (cursor != null) {
                query.and(Activity.ID.lt(cursor));
            }
            List<Row> batch = query.orderBy(Activity.ID, SortOrder.DESC).limit(BATCH_SIZE).all();
            if (batch.isEmpty()) {
                return;
            }
            batch.forEach(visitor);
            cursor = batch.getLast().get(Activity.ID);
        }
    }

    /** The activity columns this migration reads, frozen; a field belongs to one schema, so the model is built once. */
    private static final class Activity {

        static final LongField ID = LongField.builder("id").build();
        static final StringField MODEL = StringField.builder("model").build();
        static final StringField RECORD_ID = StringField.builder("record_id").build();
        static final StringField ACTION = StringField.builder("action").build();
        static final TextField DETAIL = TextField.builder("detail").nullable(true).build();
        static final DateTimeField CREATED_AT = DateTimeField.builder("created_at").nullable(true).build();
        static final FrozenModel ROWS = new FrozenModel(ACTIVITY, ID, MODEL, RECORD_ID, ACTION, DETAIL, CREATED_AT);
    }
}
