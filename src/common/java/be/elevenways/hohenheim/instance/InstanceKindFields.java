package be.elevenways.hohenheim.instance;

import be.elevenways.hohenheim.HohenheimMicrocopy;
import be.elevenways.zenit.common.edit.submit.SubmittedValueCoercion;
import be.elevenways.zenit.common.orm.field.DoubleField;
import be.elevenways.zenit.common.orm.field.EnumField;
import be.elevenways.zenit.common.orm.field.IntegerField;
import be.elevenways.zenit.common.orm.field.NumberField;
import be.elevenways.zenit.common.orm.field.SchemaField;
import be.elevenways.zenit.common.orm.field.StringField;
import be.elevenways.zenit.common.orm.field.StringMapField;
import be.elevenways.zenit.common.orm.model.Schema;
import be.elevenways.zenit.common.validation.Violations;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The settings fields several instance kinds and resource-capped models share, each declared once with its key, type,
 * label and help.
 *
 * AIDEV-NOTE: a field belongs to the one schema it is added to ({@code Schema.addField} sets its parent), so every
 * factory builds a fresh field and a kind adds the ones it uses. The system container and VM kinds name an image ALIAS,
 * not a container image, so they declare their own field under {@link #IMAGE} with their own words.
 *
 * @author Jelle De Loecker
 * @since 0.10.0
 */
public final class InstanceKindFields {

    /** The container image a workload runs; the Incus kinds store their image alias under the same key. */
    public static final String IMAGE = "image";

    public static final String ENVIRONMENT_VARIABLES = "environment_variables";

    /** The memory ceiling in MiB, the host-capacity denominator. */
    public static final String MEMORY_LIMIT_MB = "memory_limit_mb";

    public static final String CPU_LIMIT = "cpu_limit";

    private InstanceKindFields() {
    }

    public static @NonNull StringField image() {
        return StringField.builder().name(IMAGE)
            .label(HohenheimMicrocopy.HOHENHEIM_FIELD.of("image"))
            .help(HohenheimMicrocopy.HELP.of("image")).build();
    }

    public static @NonNull StringMapField environmentVariables() {
        return StringMapField.builder(ENVIRONMENT_VARIABLES)
            .label(HohenheimMicrocopy.HOHENHEIM_FIELD.of("environment_variables"))
            .help(HohenheimMicrocopy.HELP.of("environment_variables")).secret().build();
    }

    public static @NonNull IntegerField memoryLimit() {
        return IntegerField.builder().name(MEMORY_LIMIT_MB)
            .label(HohenheimMicrocopy.HOHENHEIM_FIELD.of("memory_limit"))
            .help(HohenheimMicrocopy.HELP.of("memory_limit")).build();
    }

    public static @NonNull DoubleField cpuLimit() {
        return DoubleField.builder().name(CPU_LIMIT)
            .label(HohenheimMicrocopy.HOHENHEIM_FIELD.of("cpu_limit"))
            .help(HohenheimMicrocopy.HELP.of("cpu_limit")).build();
    }

    public static @NonNull EnumField consoleKind() {
        return ConsoleKind.fieldBuilder()
            .label(HohenheimMicrocopy.HOHENHEIM_FIELD.of("console_kind"))
            .help(HohenheimMicrocopy.HELP.of("console_kind")).build();
    }

    /** @param vm whether the kind is a VM, the only one offered the VM-only origins */
    public static @NonNull EnumField imageOrigin(boolean vm) {
        return ImageOrigin.fieldBuilder(vm)
            .label(HohenheimMicrocopy.HOHENHEIM_FIELD.of("image_origin"))
            .help(HohenheimMicrocopy.HELP.of("image_origin")).build();
    }

    /**
     * Coerces every number setting the discriminator's schema declares to its field's type, as the settings form does.
     *
     * AIDEV-NOTE: zenit reads a stored settings number through {@code NumberField.toApp}, which throws on text, so a
     * "512" stored from an imported document would make its whole row unreadable. A writer of settings that skipped
     * the form (portability import of kind and variable settings, backup restore) passes them through here first; ""
     * reads as absent.
     *
     * @param settingsField the model's settings column, whose schema follows a sibling value (an instance kind, a
     *                      variable type)
     * @param discriminator that sibling value
     * @throws Violations naming the setting when a value is not a number
     */
    public static @NonNull Map<String, Object> typedNumbers(@NonNull SchemaField settingsField,
                                                            @NonNull String discriminator,
                                                            @NonNull Map<String, Object> settings) {
        Schema schema = settingsField.resolveSchemaForSiblingValue(discriminator);
        if (schema == null) {
            return settings;
        }
        Map<String, Object> typed = new LinkedHashMap<>(settings);
        schema.getFields().forEach((name, field) -> {
            if (field instanceof NumberField<?, ?> && typed.containsKey(name)) {
                typed.put(name, SubmittedValueCoercion.coerceFieldOrThrow(field, typed.get(name)));
            }
        });
        return typed;
    }
}
