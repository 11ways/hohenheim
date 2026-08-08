package be.elevenways.hohenheim.server.orm;

import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.field.Field;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.query.QueryBuilder;
import be.elevenways.zenit.common.orm.query.criteria.EqualsCriteria;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * THE narrow write for a record a long operation holds open: it assigns onto the
 * in-memory {@link Row} AND persists exactly the assigned columns, as one set-based
 * statement addressed by primary key.
 *
 * AIDEV-NOTE: this is the generalization of {@code InstanceOperationGuard.stampRole},
 * for records that carry no fence. {@code Models.save(row)} writes every column PRESENT
 * on the Row, and a row loaded from the database carries all of them -- so a save is a
 * WHOLE-ROW REWRITE that silently reverts anything anyone else wrote between load and
 * save, while reporting success. Operation records (releases, builds, snapshots,
 * backups, console logs) are held open across minutes of daemon work, which makes that
 * window enormous; the snapshot NOTE an operator edits mid-capture is a writer that
 * exists today. A step that changes three columns must write three columns.
 *
 * Deliberately NOT OptimisticLockingBehaviour: an operation's terminal stamp may not
 * start throwing at the operator because a rival touched an unrelated column -- the
 * work already happened and the record has to say so.
 */
public final class RecordStamp {

    private final @NonNull Model model;
    private final @NonNull Row row;
    private final @NonNull Map<Field<?, ?>, Object> assignments = new LinkedHashMap<>();

    private RecordStamp(@NonNull Model model, @NonNull Row row) {
        this.model = model;
        this.row = row;
    }

    /** Start a narrow write against the row's own record. */
    public static @NonNull RecordStamp on(@NonNull Model model, @NonNull Row row) {
        return new RecordStamp(model, row);
    }

    /** The record this stamp writes, carrying everything staged so far. */
    public @NonNull Row row() {
        return this.row;
    }

    /** Stage {@code field = value}, on the Row as well as in the statement. */
    public <T, I> @NonNull RecordStamp set(@NonNull Field<T, I> field, @Nullable T value) {
        this.row.set(field, value);
        this.assignments.put(field, value);
        return this;
    }

    /**
     * Persist the staged columns, and nothing else.
     *
     * @return rows matched -- 0 means the record is GONE (history pruned under the
     *         operation, a hard delete); the caller's own columns were not written and
     *         there is nothing left to write them to
     * @throws IllegalStateException when nothing was staged
     */
    public int write() {
        if (this.assignments.isEmpty()) {
            throw new IllegalStateException("RecordStamp.write() needs at least one set()");
        }
        Field<?, ?> primaryKey = this.model.getPrimaryKeyField();
        QueryBuilder<Row> query = this.model.find()
            .where(identifies(primaryKey, this.row.get(primaryKey.getName())));
        for (Map.Entry<Field<?, ?>, Object> assignment : this.assignments.entrySet()) {
            query = query.assign(assignment.getKey(), assignment.getValue());
        }
        return query.updateAll();
    }

    @SuppressWarnings("unchecked")
    private static <T, I> @NonNull EqualsCriteria<T, I> identifies(@NonNull Field<T, I> field,
                                                                  @Nullable Object value) {
        if (value == null) {
            throw new IllegalStateException("RecordStamp needs a saved row: the "
                + field.getName() + " of the row it was handed is null");
        }
        return field.eq((T) value);
    }
}
