package be.elevenways.hohenheim.instance;

import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.zenit.common.orm.field.EnumField;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * WHICH console surface a template's workload gets -- the one declaring home of that
 * vocabulary.
 *
 * AIDEV-NOTE: exactly one member on purpose. The Janeway console is phase 3, and a member
 * declared before the surface exists would be selectable and then render nothing; a
 * one-member enum still buys the closed vocabulary (an unknown stored token resolves to
 * null and refuses) that a bare string column does not.
 *
 * @author Jelle De Loecker
 * @since  0.1.0
 */
public enum ConsoleKind {

    /** The existing raw stdout/stdin console. */
    PLAIN("plain", "Plain console");

    private final String token;
    private final String displayName;

    ConsoleKind(String token, String displayName) {
        this.token = token;
        this.displayName = displayName;
    }

    /** @return the stored column value */
    public @NonNull String token() {
        return this.token;
    }

    public @NonNull String displayName() {
        return this.displayName;
    }

    public @NonNull Microcopy label() {
        return Microcopy.of(this.token).withFilter("scope", "console_kind");
    }

    /**
     * The schema-field builder carrying this vocabulary, so no stored option set can drift.
     * Callers finish it with their own label/help, never with more values.
     */
    public static EnumField.@NonNull Builder fieldBuilder(@NonNull String name) {

        EnumField.Builder builder = EnumField.builder(name);

        for (ConsoleKind kind : values()) {
            builder.value(kind.token(), value -> value
                .displayName(kind.displayName())
                .label(kind.label()));
        }

        return builder.defaultValue(PLAIN.token());
    }

    /** @return the matching kind, or null when unknown (fail closed, never a default) */
    public static @Nullable ConsoleKind forToken(@Nullable String token) {

        if (token == null) {
            return null;
        }

        for (ConsoleKind kind : values()) {
            if (kind.token.equals(token)) {
                return kind;
            }
        }

        return null;
    }
}
