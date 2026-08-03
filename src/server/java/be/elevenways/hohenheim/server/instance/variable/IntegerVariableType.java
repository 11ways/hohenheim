package be.elevenways.hohenheim.server.instance.variable;

import be.elevenways.hohenheim.HohenheimFormCopy;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.common.orm.field.Field;
import be.elevenways.zenit.common.orm.field.IntegerField;
import be.elevenways.zenit.common.orm.model.Schema;
import be.elevenways.zenit.common.ui.Icon;
import be.elevenways.zenit.common.validation.validator.Max;
import be.elevenways.zenit.common.validation.validator.Min;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.util.Map;

/** Whole-number variable with optional min/max bounds. */
public final class IntegerVariableType implements VariableTypeHandler {

    public static final Identifier ID = Identifier.of("hohenheim", "integer");
    public static final Schema SETTINGS_SCHEMA = new Schema();

    public static final IntegerField MIN = SETTINGS_SCHEMA.addField(
        IntegerField.builder().name("min")
            .label(HohenheimFormCopy.label("variable_min"))
            .build());

    public static final IntegerField MAX = SETTINGS_SCHEMA.addField(
        IntegerField.builder().name("max")
            .label(HohenheimFormCopy.label("variable_max"))
            .build());

    @Override
    public @NonNull Identifier typeId() { return ID; }

    @Override
    public @NonNull String getDisplayName() { return "Number"; }

    @Override
    public @NonNull Microcopy getLabel() {
        return Microcopy.of("integer").withFilter("scope", "variable_type");
    }

    @Override
    public Icon getIcon() { return Icon.of("hashtag"); }

    @Override
    public String getColor() { return "blue"; }

    @Override
    public Schema getSchema() { return SETTINGS_SCHEMA; }

    @Override
    public @NonNull Field<?, ?> buildField(@NonNull String key, @NonNull String label,
                                           boolean required, @NonNull Map<String, Object> settings) {
        IntegerField.Builder builder = IntegerField.builder().name(key)
            .label(Microcopy.literal(label));
        if (required) {
            builder.required();
        }
        if (settings.get("min") instanceof Number min) {
            builder.validator(Min.of(min.intValue()));
        }
        if (settings.get("max") instanceof Number max) {
            builder.validator(Max.of(max.intValue()));
        }
        return builder.build();
    }
}
