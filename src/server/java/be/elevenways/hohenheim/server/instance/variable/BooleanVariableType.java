package be.elevenways.hohenheim.server.instance.variable;

import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.common.orm.field.BooleanField;
import be.elevenways.zenit.common.orm.field.Field;
import be.elevenways.zenit.common.orm.model.Schema;
import be.elevenways.zenit.common.ui.Icon;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.util.Map;

/** On/off variable; stores "true"/"false". */
public final class BooleanVariableType implements VariableTypeHandler {

    public static final Identifier ID = Identifier.of("hohenheim", "boolean");
    public static final Schema SETTINGS_SCHEMA = new Schema();

    @Override
    public @NonNull Identifier typeId() { return ID; }

    @Override
    public @NonNull String getDisplayName() { return "Switch"; }

    @Override
    public @NonNull Microcopy getLabel() {
        return Microcopy.of("boolean").withFilter("scope", "variable_type");
    }

    @Override
    public Icon getIcon() { return Icon.of("toggle-on"); }

    @Override
    public String getColor() { return "green"; }

    @Override
    public Schema getSchema() { return SETTINGS_SCHEMA; }

    @Override
    public @NonNull Field<?, ?> buildField(@NonNull String key, @NonNull String label,
                                           boolean required, @NonNull Map<String, Object> settings) {
        // Required is meaningless for a switch (absent = false); it is not applied.
        return BooleanField.builder(key).label(Microcopy.literal(label)).build();
    }

    @Override
    public String toStoredString(Object coerced) {
        return Boolean.TRUE.equals(coerced) ? "true" : "false";
    }
}
