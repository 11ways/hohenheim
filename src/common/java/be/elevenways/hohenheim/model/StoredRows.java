package be.elevenways.hohenheim.model;

import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.field.Field;
import be.elevenways.zenit.common.orm.model.Model;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * THE read of a record as it stands in storage, soft-deleted rows included.
 *
 * AIDEV-NOTE: sites, instances and preview deployments carry SoftDeleteBehaviour, whose
 * before-find hook hides a trashed row from findById and every default find. A write hook or
 * a lifecycle step that compares a write against the STORED row must still see a trashed one:
 * a restore is a trashed-to-live transition and a re-save of a trashed row stays trashed, and
 * reading either as "no stored row" turns it into a CREATE (quota charged twice, every
 * update-only refusal skipped). Every such read comes through here; a default find stays the
 * answer for "is there a LIVE record".
 *
 * @author Jelle De Loecker
 * @since  0.1.0
 */
public final class StoredRows {

    private StoredRows() {
    }

    /** @return the stored row with this primary key, trashed included, or null when absent */
    @SuppressWarnings("unchecked")
    public static @Nullable Row byId(@NonNull Model model, @Nullable Object id) {
        if (id == null) {
            return null;
        }
        Field<Object, ?> primaryKey = (Field<Object, ?>) model.getPrimaryKeyField();
        return model.find().withTrashed().where(primaryKey.eq(id)).first();
    }

    /** @return the stored version of a row being written, trashed included, or null on a create */
    public static @Nullable Row of(@NonNull Model model, @NonNull Row row) {
        String key = model.getPrimaryKeyField().getName();
        return row.has(key) ? byId(model, row.get(key)) : null;
    }
}
