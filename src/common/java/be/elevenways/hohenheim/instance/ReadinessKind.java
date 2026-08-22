package be.elevenways.hohenheim.instance;

import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.zenit.common.orm.field.EnumField;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * HOW a template decides its workload is ready to take traffic -- the one declaring home
 * of that vocabulary.
 *
 * AIDEV-NOTE: {@code console_line} keeps reading its matcher from the template's existing
 * {@code readiness_line} column; the other two read {@code readiness_target}. That split is
 * why {@link #usesTarget()} is a fact on the member rather than an if in the editor: an
 * editor that guessed would show the wrong input for whichever member it forgot.
 *
 * @author Jelle De Loecker
 * @since  0.1.0
 */
public enum ReadinessKind {

    /** The declared port accepts a TCP connection. */
    PORT("port", "Port open", false),

    /** An HTTP path answers below 500. */
    HTTP("http", "HTTP path", true),

    /** A line matching the template's readiness_line appears on the console. */
    CONSOLE_LINE("console_line", "Console line", false);

    private final String token;
    private final String displayName;
    private final boolean usesTarget;

    ReadinessKind(String token, String displayName, boolean usesTarget) {
        this.token = token;
        this.displayName = displayName;
        this.usesTarget = usesTarget;
    }

    /** @return the stored column value */
    public @NonNull String token() {
        return this.token;
    }

    public @NonNull String displayName() {
        return this.displayName;
    }

    /** @return whether this kind reads {@code readiness_target} rather than {@code readiness_line} */
    public boolean usesTarget() {
        return this.usesTarget;
    }

    public @NonNull Microcopy label() {
        return Microcopy.of(this.token).withFilter("scope", "readiness_kind");
    }

    /**
     * The schema-field builder carrying this vocabulary, so no stored option set can drift.
     * Callers finish it with their own label/help, never with more values.
     */
    public static EnumField.@NonNull Builder fieldBuilder(@NonNull String name) {

        EnumField.Builder builder = EnumField.builder(name);

        for (ReadinessKind kind : values()) {
            builder.value(kind.token(), value -> value
                .displayName(kind.displayName())
                .label(kind.label()));
        }

        return builder.defaultValue(PORT.token());
    }

    /** @return the matching kind, or null when unknown (fail closed, never a default) */
    public static @Nullable ReadinessKind forToken(@Nullable String token) {

        if (token == null) {
            return null;
        }

        for (ReadinessKind kind : values()) {
            if (kind.token.equals(token)) {
                return kind;
            }
        }

        return null;
    }
}
