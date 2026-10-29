package be.elevenways.hohenheim.test;

import be.elevenways.protoblast.testsupport.time.Poll;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.field.EnumField;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.Models;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.time.Duration;

/**
 * Bounded waits for a record's status column to reach a value an async writer stamps.
 *
 * @author Jelle De Loecker
 * @since 0.10.0
 */
public final class RecordStatus {

    private RecordStatus() {
    }

    /**
     * Wait until the {@code model} row {@code id} reads {@code expected} in its {@code status} column; a missing row
     * reads as not there yet.
     *
     * @throws AssertionError naming {@code what}, the record and the last status read when {@code bound} passes first
     */
    public static void await(@NonNull String what, @NonNull Class<? extends Model> model, @NonNull EnumField status,
                             int id, @NonNull String expected, @NonNull Duration bound, @NonNull Duration interval) {
        Poll.value(what + " (" + model.getSimpleName() + " " + id + " reaching status " + expected + ")", bound,
            interval, () -> {
                Row row = Models.get(model).findById(id);
                return row == null ? null : row.get(status);
            }, expected::equals);
    }
}
