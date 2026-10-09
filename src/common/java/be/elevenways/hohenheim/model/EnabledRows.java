package be.elevenways.hohenheim.model;

import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.field.BooleanField;
import be.elevenways.zenit.common.orm.field.StringField;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.query.SortOrder;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.util.List;

/**
 * The one query shape of a model whose rows carry an enabled flag and a name.
 *
 * @author Jelle De Loecker
 * @since  0.1.0
 */
final class EnabledRows {

    private EnabledRows() {
    }

    /** @return the model's enabled rows, name-ordered */
    static @NonNull List<Row> byName(@NonNull Model model, @NonNull BooleanField enabled, @NonNull StringField name) {
        return model.find().where(enabled.eq(true)).orderBy(name, SortOrder.ASC).all();
    }
}
