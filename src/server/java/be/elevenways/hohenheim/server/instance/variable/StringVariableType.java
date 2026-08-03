package be.elevenways.hohenheim.server.instance.variable;

import be.elevenways.hohenheim.HohenheimFormCopy;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.common.orm.field.Field;
import be.elevenways.zenit.common.orm.field.IntegerField;
import be.elevenways.zenit.common.orm.field.StringField;
import be.elevenways.zenit.common.orm.model.Schema;
import be.elevenways.zenit.common.ui.Icon;
import be.elevenways.zenit.common.validation.validator.MaxLength;
import be.elevenways.zenit.common.validation.validator.Regex;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.util.Map;

/** Free-text variable with optional regex pattern and length cap. */
public final class StringVariableType implements VariableTypeHandler {

    public static final Identifier ID = Identifier.of("hohenheim", "string");
    public static final Schema SETTINGS_SCHEMA = new Schema();

    public static final StringField PATTERN = SETTINGS_SCHEMA.addField(
        StringField.builder().name("pattern")
            .label(HohenheimFormCopy.label("variable_pattern"))
            .help(HohenheimFormCopy.help("variable_pattern"))
            .build());

    public static final IntegerField MAX_LENGTH = SETTINGS_SCHEMA.addField(
        IntegerField.builder().name("max_length")
            .label(HohenheimFormCopy.label("variable_max_length"))
            .build());

    @Override
    public @NonNull Identifier typeId() { return ID; }

    @Override
    public @NonNull String getDisplayName() { return "Text"; }

    @Override
    public @NonNull Microcopy getLabel() {
        return Microcopy.of("string").withFilter("scope", "variable_type");
    }

    @Override
    public Icon getIcon() { return Icon.of("font"); }

    @Override
    public String getColor() { return "gray"; }

    @Override
    public Schema getSchema() { return SETTINGS_SCHEMA; }

    @Override
    public @NonNull Field<?, ?> buildField(@NonNull String key, @NonNull String label,
                                           boolean required, @NonNull Map<String, Object> settings) {
        StringField.Builder builder = StringField.builder().name(key)
            .label(Microcopy.literal(label));
        if (required) {
            builder.required();
        }
        Object pattern = settings.get("pattern");
        if (pattern instanceof String regex && !regex.isBlank()) {
            builder.validator(Regex.of(regex.trim()));
        }
        if (settings.get("max_length") instanceof Number max && max.intValue() > 0) {
            builder.validator(MaxLength.of(max.intValue()));
        }
        return builder.build();
    }
}
