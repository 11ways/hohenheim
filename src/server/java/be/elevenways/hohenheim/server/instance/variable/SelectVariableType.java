package be.elevenways.hohenheim.server.instance.variable;

import be.elevenways.hohenheim.HohenheimIds;
import be.elevenways.hohenheim.HohenheimMicrocopy;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.common.orm.field.EnumField;
import be.elevenways.zenit.common.orm.field.Field;
import be.elevenways.zenit.common.orm.field.ListField;
import be.elevenways.zenit.common.orm.field.StringField;
import be.elevenways.zenit.common.orm.model.Schema;
import be.elevenways.zenit.common.ui.BadgeColor;
import be.elevenways.zenit.common.ui.ColorHue;
import be.elevenways.zenit.common.ui.Icon;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.util.List;
import java.util.Map;

/**
 * Closed-choice variable: the declared options ARE the enum values, so a value outside
 * them is a typed coercion refusal, not a string that slipped through.
 */
public final class SelectVariableType implements VariableTypeHandler {

    public static final Identifier ID = HohenheimIds.id("select");
    public static final Schema SETTINGS_SCHEMA = new Schema();

    public static final ListField<String> OPTIONS = SETTINGS_SCHEMA.addField(
        ListField.builder(StringField.builder().name("option").build()).name("options")
            .label(HohenheimMicrocopy.HOHENHEIM_FIELD.of("variable_options"))
            .help(HohenheimMicrocopy.HELP.of("variable_options"))
            .build());

    @Override
    public @NonNull Identifier typeId() { return ID; }

    @Override
    public @NonNull String getDisplayName() { return "Choice"; }

    @Override
    public Icon getIcon() { return Icon.of("list"); }

    @Override
    public BadgeColor color() { return ColorHue.PURPLE; }

    @Override
    public Schema getSchema() { return SETTINGS_SCHEMA; }

    @Override
    public @NonNull Field<?, ?> buildField(@NonNull String key, @NonNull String label,
                                           boolean required, @NonNull Map<String, Object> settings) {
        EnumField.Builder builder = EnumField.builder(key).label(Microcopy.literal(label));
        if (settings.get("options") instanceof List<?> options) {
            for (Object option : options) {
                if (option instanceof String value && !value.isBlank()) {
                    String trimmed = value.trim();
                    builder.value(trimmed, v -> v.displayName(trimmed));
                }
            }
        }
        if (required) {
            builder.required();
        }
        return builder.build();
    }
}
