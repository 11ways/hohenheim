package be.elevenways.hohenheim.model;

import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.field.IntegerField;
import be.elevenways.zenit.common.orm.field.StringField;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.query.QueryBuilder;
import be.elevenways.zenit.common.orm.query.SortOrder;
import org.checkerframework.checker.nullness.qual.NonNull;

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
}
