package be.elevenways.hohenheim.instance;

import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.zenit.common.orm.field.EnumField;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * HOW a template asks its workload to stop before the grace period runs out -- the one
 * declaring home of that vocabulary.
 *
 * @author Jelle De Loecker
 * @since  0.1.0
 */
public enum StopKind {

    /** SIGTERM to the main process, then SIGKILL after the grace period. */
    SIGNAL("signal", "Signal", false),

    /** Write the template's stop_command to the console, then the signal path. */
    COMMAND("command", "Console command", true);

    private final String token;
    private final String displayName;
    private final boolean usesCommand;

    StopKind(String token, String displayName, boolean usesCommand) {
        this.token = token;
        this.displayName = displayName;
        this.usesCommand = usesCommand;
    }

    /** @return the stored column value */
    public @NonNull String token() {
        return this.token;
    }

    public @NonNull String displayName() {
        return this.displayName;
    }

    /** @return whether this kind reads the template's {@code stop_command} */
    public boolean usesCommand() {
        return this.usesCommand;
    }

    public @NonNull Microcopy label() {
        return Microcopy.of(this.token).withFilter("scope", "stop_kind");
    }

    /**
     * The schema-field builder carrying this vocabulary, so no stored option set can drift.
     * Callers finish it with their own label/help, never with more values.
     */
    public static EnumField.@NonNull Builder fieldBuilder(@NonNull String name) {

        EnumField.Builder builder = EnumField.builder(name);

        for (StopKind kind : values()) {
            builder.value(kind.token(), value -> value
                .displayName(kind.displayName())
                .label(kind.label()));
        }

        return builder.defaultValue(SIGNAL.token());
    }

    /** @return the matching kind, or null when unknown (fail closed, never a default) */
    public static @Nullable StopKind forToken(@Nullable String token) {

        if (token == null) {
            return null;
        }

        for (StopKind kind : values()) {
            if (kind.token.equals(token)) {
                return kind;
            }
        }

        return null;
    }
}
