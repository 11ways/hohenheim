package be.elevenways.hohenheim.model;

import be.elevenways.zenit.common.orm.OrmConstants;
import be.elevenways.zenit.common.orm.behaviour.SoftDeleteBehaviour;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.datasource.context.SaveToDatasource;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.Schema;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

/**
 * THE reading of a write that only TRASHES a soft-deleted record, so a rule judging a record's
 * shape can tell a delete from an edit.
 *
 * AIDEV-NOTE: zenit's SoftDeleteBehaviour stamps deleted_at by saving the WHOLE loaded row
 * through the ordinary save pipeline, so every before-validate hook judges every column the
 * row carries -- a delete used to run none of them. A record whose unrelated columns stopped
 * satisfying a shape rule (a grouping whose grants moved, a host whose runtime changed, a site
 * shape an older release accepted) could then never be deleted. A shape rule asks this and
 * lets such a write through; the rules that OWN the trash transition (quota release, route
 * claims, authority over frozen columns, generated-row attribution) keep judging it. A
 * restore is deliberately NOT such a write: the record returns to live and is judged as one.
 *
 * @author Jelle De Loecker
 * @since  0.1.0
 */
public final class SoftDeleteWrites {

    private SoftDeleteWrites() {
    }

    /**
     * Whether this write takes a live row to trashed and moves no other column; lifecycle
     * columns and updated_at (stamped by the save itself) are not compared.
     *
     * AIDEV-NOTE: fails toward "not only a trash": an unreadable stored row, a value that does
     * not compare equal, or a column an earlier hook rewrote all make the write judged in full,
     * which is the pre-existing behaviour and never a way past a rule.
     */
    public static boolean onlyTrashes(@NonNull SaveToDatasource context) {
        Row row = context.getRow();
        Model model = context.getModel();
        if (context.isCreate() || row == null || model == null) {
            return false;
        }
        Schema schema = model.getSchema();
        SoftDeleteBehaviour softDelete = schema.getBehaviour(SoftDeleteBehaviour.class);
        if (softDelete == null) {
            return false;
        }
        String deletedAt = softDelete.deletedAtField().getName();
        if (!row.has(deletedAt) || row.get(deletedAt) == null) {
            return false;
        }
        Row stored = StoredRows.of(model, row);
        if (stored == null || softDelete.isTrashed(stored)) {
            return false;
        }
        Set<String> uncompared = new HashSet<>(schema.getLifecycleFieldNames());
        uncompared.add(OrmConstants.UPDATED_AT_COLUMN);
        for (String name : schema.getFields().keySet()) {
            if (uncompared.contains(name) || !row.has(name)) {
                continue;
            }
            if (!Objects.deepEquals(row.get(name), stored.get(name))) {
                return false;
            }
        }
        return true;
    }
}
