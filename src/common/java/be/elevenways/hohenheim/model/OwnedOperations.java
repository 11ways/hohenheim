package be.elevenways.hohenheim.model;

import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.field.DateTimeField;
import be.elevenways.zenit.common.orm.field.EnumField;
import be.elevenways.zenit.common.orm.field.IntegerField;
import be.elevenways.zenit.common.orm.field.StringField;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.query.QueryBuilder;
import be.elevenways.zenit.common.orm.query.SortOrder;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.util.Collection;
import java.util.Map;

/**
 * The history query every operation model keyed by its owning record ({@code for_model}, {@code for_id}) answers
 * alike: the build and the release operations; their pruning is {@link Retention#keepNewest}.
 *
 * @author Jelle De Loecker
 * @since 0.1.0
 */
final class OwnedOperations {

    private OwnedOperations() {
    }

    /** @return one owning record's operations, newest first */
    static @NonNull QueryBuilder<Row> newestFirst(@NonNull Model model, @NonNull StringField forModelField,
                                                  @NonNull IntegerField forIdField, @NonNull IntegerField idField,
                                                  @NonNull String forModel, int forId) {
        return model.find()
            .where(forModelField.eq(forModel))
            .where(forIdField.eq(forId))
            .orderBy(idField, SortOrder.DESC);
    }

    /**
     * The operation in {@code status} that finished last, of each of several owning records of one model, in one
     * grouped read.
     *
     * AIDEV-NOTE: ordered by when it finished, never by id: an operation can finish after a newer one started. One
     * without a finish time cannot say when it finished, so it is left out instead of sorting where its backend puts a
     * NULL (first, descending, on PostgreSQL), which hid an older one that did finish.
     *
     * @return owning record id to that operation; a record with none is absent
     */
    static @NonNull Map<Integer, Row> lastFinishedAmong(@NonNull Model model, @NonNull StringField forModelField,
                                                        @NonNull IntegerField forIdField,
                                                        @NonNull DateTimeField finishedField,
                                                        @NonNull EnumField statusField, @NonNull String status,
                                                        @NonNull String forModel,
                                                        @NonNull Collection<Integer> forIds) {
        return FirstPerOwner.of(model.find()
            .where(forModelField.eq(forModel))
            .where(statusField.eq(status))
            .where(finishedField.isNotNull())
            .orderBy(finishedField, SortOrder.DESC), forIdField, forIds);
    }
}
