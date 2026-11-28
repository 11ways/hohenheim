package be.elevenways.hohenheim.server.files;

import be.elevenways.hohenheim.model.BackupTargetModel;
import be.elevenways.zenit.common.coerce.PrimitiveCoercion;
import be.elevenways.zenit.common.edit.submit.CoercionContext;
import be.elevenways.zenit.common.edit.submit.CoercionOutcome;
import be.elevenways.zenit.common.edit.submit.FieldSubmitCoercion;
import be.elevenways.zenit.common.edit.submit.SubmittedValueCoercion;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.field.StringField;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.validation.ValidationContext;
import be.elevenways.zenit.common.validation.Violation;
import be.elevenways.zenit.server.operation.RecordCodes;
import be.elevenways.zenit.server.storage.Storages;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.Map;

/**
 * A site's "Files kept in": the name of the storage its own files live in, drawn as the picker of the storages the
 * person editing may use ({@link SitePicks}).
 *
 * AIDEV-NOTE: a storage record is also given by the code every tool shows for it (BT3): the field reads that code as
 * the record's name before the picker judges it against the storages the person may use, so the picker and the tools
 * speak one vocabulary while the site keeps storing the name. A code is read unjudged: the picker's own options are
 * the gate, so an unoffered storage's code is refused as any unoffered name is.
 *
 * @author Jelle De Loecker
 * @since  0.10.0
 */
public class SiteStorageField extends StringField implements FieldSubmitCoercion<String> {

    protected SiteStorageField(Builder builder) {
        super(builder);
    }

    public static Builder builder(String name) {
        Builder builder = new Builder();
        builder.name(name);
        return builder;
    }

    @Override
    public @Nullable Map<String, Object> jsonScalarSchema() {
        return Map.of("type", "string");
    }

    @Override
    public @NonNull CoercionOutcome<String> coerceSubmitted(@NonNull Object raw, @NonNull CoercionContext ctx) {
        PrimitiveCoercion.Result<String> text = PrimitiveCoercion.toText(raw);
        if (!text.ok()) {
            ValidationContext validation = ValidationContext.builder(getName()).path(ctx.path()).field(this).build();
            ctx.violations().add(Violation.of(validation, raw,
                validation.message(SubmittedValueCoercion.INVALID_STRING_MESSAGE_KEY)));
            return CoercionOutcome.invalid();
        }
        return CoercionOutcome.valid(nameOf(text.value()));
    }

    /**
     * @return the name of the storage record the text gives by its code, else the text as given: a storage whose name
     *     is the text itself wins, so a name shaped like a code never turns into another storage
     */
    static @Nullable String nameOf(@Nullable String given) {
        if (given == null || given.isBlank() || Storages.has(given)) return given;
        BackupTargetModel storages = Models.get(BackupTargetModel.class);
        if (storages.find().where(BackupTargetModel.NAME.eq(given)).first() != null) return given;
        String key = RecordCodes.keyOfCode(BackupTargetModel.MODEL_ID, given.strip());
        Row storage = key == null ? null : storages.findById(Integer.valueOf(key));
        return storage == null ? given : storage.get(BackupTargetModel.NAME);
    }

    public static class Builder extends StringField.Builder {

        @Override
        protected SiteStorageField buildField() {
            return new SiteStorageField(this);
        }
    }
}
