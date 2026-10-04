package be.elevenways.hohenheim.test;

import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.field.IntegerField;
import be.elevenways.zenit.common.orm.field.SchemaField;
import be.elevenways.zenit.common.orm.migration.FrozenModel;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * The legacy {@code payload} column of a stored schedule step, read raw: what the upgrade tests assert M011 cleared,
 * after the live model stopped naming the column.
 *
 * @author Jelle De Loecker
 * @since 0.1.0
 */
public final class LegacyStepPayloads {

    private LegacyStepPayloads() {
    }

    /** @return the step's stored payload in the current datasource, null when it carries none */
    public static @Nullable Object of(int stepId) {
        IntegerField id = IntegerField.builder().name("id").build();
        SchemaField payload = SchemaField.builder("payload").build();
        Row row = new FrozenModel("zenit_record_schedule_steps", id, payload).find().where(id.eq(stepId)).first();
        return row == null ? null : row.get(payload);
    }
}
