package be.elevenways.hohenheim.test;

import be.elevenways.hohenheim.model.StoredRows;
import be.elevenways.zenit.common.orm.behaviour.SoftDeleteBehaviour;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.query.criteria.Criteria;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Test teardown that PHYSICALLY removes rows, trashed ones included, whatever the model's delete means.
 *
 * AIDEV-NOTE: sites, instances and preview deployments carry SoftDeleteBehaviour, so their
 * {@code model.delete(...)} is a soft delete: the row stays (holding its unique slug, its port
 * claims and its foreign keys) and only disappears from default finds. A cleanup that means
 * "gone" says so here -- the behaviour's forceDelete, which runs the hard path and its remove
 * hooks -- and it reaches rows an earlier step already trashed. A model without the behaviour
 * falls back to its plain delete, so a cleanup never has to know which kind it holds.
 *
 * @author Jelle De Loecker
 * @since  0.1.0
 */
public final class HardDeletes {

    private HardDeletes() {
    }

    /** @return whether a row with this primary key existed (trashed or live) and was removed */
    public static boolean byId(@NonNull Model model, @Nullable Object id) {
        SoftDeleteBehaviour softDelete = model.getSchema().getBehaviour(SoftDeleteBehaviour.class);
        if (softDelete == null) {
            return id != null && model.delete(id);
        }
        Row stored = StoredRows.byId(model, id);
        return stored != null && softDelete.forceDelete(stored);
    }

    /** @return whether the row was removed; reads its primary key, never its other columns */
    public static boolean row(@NonNull Model model, @NonNull Row row) {
        return byId(model, model.getPrimaryKeyValue(row));
    }

    /** @return how many rows matching {@code criteria}, trashed included, were removed */
    public static int where(@NonNull Model model, @NonNull Criteria criteria) {
        int removed = 0;
        for (Row row : model.find().withTrashed().where(criteria).all()) {
            if (row(model, row)) {
                removed++;
            }
        }
        return removed;
    }
}
