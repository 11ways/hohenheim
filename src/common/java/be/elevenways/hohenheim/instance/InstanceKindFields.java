package be.elevenways.hohenheim.instance;

import be.elevenways.protoblast.common.i18n.wording.ByteUnit;

import be.elevenways.hohenheim.HohenheimMicrocopy;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.hohenheim.ports.PublishedPort;
import be.elevenways.zenit.common.edit.submit.SubmittedValueCoercion;
import be.elevenways.zenit.common.orm.field.DoubleField;
import be.elevenways.zenit.common.orm.field.EnumField;
import be.elevenways.zenit.common.orm.field.IntegerField;
import be.elevenways.zenit.common.orm.field.NumberField;
import be.elevenways.zenit.common.orm.field.SchemaField;
import be.elevenways.zenit.common.orm.field.StringField;
import be.elevenways.zenit.common.orm.field.MapEntries;
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

    /** The memory ceiling in MiB an app may grow to, the cap its runtime enforces. */
    public static final String MEMORY_LIMIT_MB = "memory_limit_mb";

    /**
     * The memory in MiB an app is guaranteed, the amount its host books for it; absent, it books its maximum.
     *
     * AIDEV-NOTE: only the kinds whose amounts live in their settings map declare it; a managed database and a stack
     * service keep theirs in their own columns, which carry no reservation, so they book their maximum.
     */
    public static final String MEMORY_RESERVED_MB = "memory_reserved_mb";

    public static final String CPU_LIMIT = "cpu_limit";

    /** An app's command before each forward deploy, declared on the app and carried onto each release. */
    public static final String PRE_DEPLOY_COMMAND = "pre_deploy_command";

    /** That command's time limit in seconds, declared and carried the same way. */
    public static final String PRE_DEPLOY_TIMEOUT = "pre_deploy_timeout";

    /**
     * The seconds a workload is given to exit on SIGTERM before SIGKILL, by every stop and by the halt of a restart.
     *
     * AIDEV-NOTE: the one home of the number: the stop lane reads it and the "Image from app" help names it as an
     * argument, so the help can never promise another grace than the stop gives.
     */
    public static final int STOP_GRACE_SECONDS = 10;

    private InstanceKindFields() {
    }

    public static @NonNull StringField image() {
        return image(HohenheimMicrocopy.HELP.of("image"));
    }

    /** @param help the kind's own words for its image, where the shared ones (a Dockerfile build) are false for it */
    public static @NonNull StringField image(@NonNull Microcopy help) {
        return StringField.builder().name(IMAGE)
            .label(HohenheimMicrocopy.HOHENHEIM_FIELD.of("image"))
            .help(help).build();
    }

    public static @NonNull StringMapField environmentVariables() {
        return StringMapField.builder(ENVIRONMENT_VARIABLES)
            .label(HohenheimMicrocopy.HOHENHEIM_FIELD.of("environment_variables"))
            .help(HohenheimMicrocopy.HELP.of("environment_variables")).entries(keyValues()).secret().build();
    }

    /** @return the entry shape of a map of names to plain texts (variables, build arguments) */
    public static @NonNull MapEntries keyValues() {
        return MapEntries.keyedBy("key", HohenheimMicrocopy.HELP.of("map_entry_key"))
            .value("value", HohenheimMicrocopy.HELP.of("map_entry_value"));
    }

    public static @NonNull IntegerField memoryLimit() {
        return IntegerField.builder().name(MEMORY_LIMIT_MB)
            .label(HohenheimMicrocopy.HOHENHEIM_FIELD.of("memory_limit"))
            .help(HohenheimMicrocopy.HELP.of("memory_limit")).byteSize(ByteUnit.MEGABYTE).build();
    }

    public static @NonNull IntegerField memoryReserved() {
        return IntegerField.builder().name(MEMORY_RESERVED_MB)
            .label(HohenheimMicrocopy.HOHENHEIM_FIELD.of("memory_reserved"))
            .help(HohenheimMicrocopy.HELP.of("memory_reserved")).byteSize(ByteUnit.MEGABYTE).build();
    }

    public static @NonNull DoubleField cpuLimit() {
        return DoubleField.builder().name(CPU_LIMIT)
            .label(HohenheimMicrocopy.HOHENHEIM_FIELD.of("cpu_limit"))
            .help(HohenheimMicrocopy.HELP.of("cpu_limit")).build();
    }

    public static @NonNull EnumField consoleKind() {
        return ConsoleKind.fieldBuilder().inputName("console_mode")
            .label(HohenheimMicrocopy.HOHENHEIM_FIELD.of("console_kind"))
            .help(HohenheimMicrocopy.HELP.of("console_kind")).build();
    }

    /** The ports a container publishes on its host, by name ({@link PublishedPort} reads and writes the values). */
    public static @NonNull StringMapField publishedPorts() {
        return publishedPorts(HohenheimMicrocopy.HELP.of("published_ports"));
    }

    /** {@link #publishedPorts()} of a workload of this isolation, its help saying where the ports listen in its words. */
    public static @NonNull StringMapField publishedPorts(@NonNull WorkloadIsolation isolation) {
        return publishedPorts(HohenheimMicrocopy.HELP.of("published_ports").withFilter("workload", isolation.token()));
    }

    private static @NonNull StringMapField publishedPorts(@NonNull Microcopy help) {
        return StringMapField.builder(PublishedPort.FIELD)
            .label(HohenheimMicrocopy.HOHENHEIM_FIELD.of("published_ports")).help(help)
            .entries(PublishedPort.entries()).build();
    }

    /** The SRV service a game address publishes for the game port, such as {@code minecraft}. */
    public static @NonNull StringField srvService() {
        return StringField.builder().name(PublishedPort.SRV_SERVICE)
            .label(HohenheimMicrocopy.HOHENHEIM_FIELD.of("srv_service"))
            .help(HohenheimMicrocopy.HELP.of("srv_service")).build();
    }

    /** How the controller asks the service for its live figure ({@link StatusProbe} is the vocabulary's home). */
    public static @NonNull EnumField statusProbe() {
        return StatusProbe.field();
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
