package be.elevenways.hohenheim.server.instance.variable;

import be.elevenways.hohenheim.HohenheimFormCopy;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.common.orm.field.BooleanField;
import be.elevenways.zenit.common.orm.field.Field;
import be.elevenways.zenit.common.orm.field.IntegerField;
import be.elevenways.zenit.common.orm.field.StringField;
import be.elevenways.zenit.common.orm.model.Schema;
import be.elevenways.zenit.common.ui.Icon;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.util.Map;

/**
 * Secret variable: the value lands ONLY in instance_variables' encrypted
 * {@code secret_value} column, masked on every form surface. With {@code generate} on,
 * a blank submit mints a random token (SecureTokens) instead of failing required --
 * the Velocity forwarding-secret shape.
 */
public final class SecretVariableType implements VariableTypeHandler {

    public static final Identifier ID = Identifier.of("hohenheim", "secret");
    public static final Schema SETTINGS_SCHEMA = new Schema();

    public static final BooleanField GENERATE = SETTINGS_SCHEMA.addField(
        BooleanField.builder("generate")
            .defaultValue(true)
            .label(HohenheimFormCopy.label("variable_generate"))
            .help(HohenheimFormCopy.help("variable_generate"))
            .build());

    public static final IntegerField GENERATE_BYTES = SETTINGS_SCHEMA.addField(
        IntegerField.builder().name("generate_bytes")
            .defaultValue(32)
            .label(HohenheimFormCopy.label("variable_generate_bytes"))
            .build());

    @Override
    public @NonNull Identifier typeId() { return ID; }

    @Override
    public @NonNull String getDisplayName() { return "Secret"; }

    @Override
    public @NonNull Microcopy getLabel() {
        return Microcopy.of("secret").withFilter("scope", "variable_type");
    }

    @Override
    public Icon getIcon() { return Icon.of("key"); }

    @Override
    public String getColor() { return "orange"; }

    @Override
    public Schema getSchema() { return SETTINGS_SCHEMA; }

    @Override
    public boolean isSecretValue() { return true; }

    @Override
    public @NonNull Field<?, ?> buildField(@NonNull String key, @NonNull String label,
                                           boolean required, @NonNull Map<String, Object> settings) {
        StringField.Builder builder = StringField.builder().name(key)
            .label(Microcopy.literal(label))
            .secret();
        // A generated secret is never required AT SUBMIT: blank means "mint one".
        if (required && !generates(settings)) {
            builder.required();
        }
        return builder.build();
    }

    /** Whether a blank submit should mint a token for this variable's settings. */
    public static boolean generates(@NonNull Map<String, Object> settings) {
        Object generate = settings.get("generate");
        // The declared default is ON; only an explicit false turns it off.
        return !Boolean.FALSE.equals(generate) && !"false".equals(generate);
    }

    /** The declared token entropy in bytes (default 32). */
    public static int generateBytes(@NonNull Map<String, Object> settings) {
        return settings.get("generate_bytes") instanceof Number bytes && bytes.intValue() > 0
            ? bytes.intValue() : 32;
    }
}
