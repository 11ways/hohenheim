package be.elevenways.hohenheim.instance;

import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.zenit.common.orm.field.EnumField;
import be.elevenways.zenit.common.validation.Violations;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.Map;

/**
 * WHICH console surface a workload's primary process gets -- the one declaring home of
 * that vocabulary, carried in the kind SETTINGS under {@link #SETTING} (and therefore in
 * every template's settings baseline).
 *
 * AIDEV-NOTE: {@link #TTY} is the "Janeway" console phase 3 reserved: the container is
 * created with a pseudo-terminal, the console socket carries keystrokes and resize frames,
 * and a TUI in the workload (Alchemy's Janeway, htop, a REPL) renders the way it would in
 * a real terminal. The token is {@code tty} and not {@code janeway} because the mechanism
 * is the pseudo-terminal, not one product's TUI. An unknown stored token still resolves to
 * null and refuses, which is the closed vocabulary a bare string column could not buy.
 *
 * @author Jelle De Loecker
 * @since  0.1.0
 */
public enum ConsoleKind {

    /** The raw stdout/stdin console: output streamed, commands sent one line at a time. */
    PLAIN("plain", "Plain console", false),

    /** A pseudo-terminal on the primary process: keystrokes, echo, resize, full-screen TUIs. */
    TTY("tty", "Interactive terminal", true);

    /** The settings key every kind that offers a console declares this vocabulary under. */
    public static final String SETTING = "console_kind";

    private final String token;
    private final String displayName;
    private final boolean interactive;

    ConsoleKind(String token, String displayName, boolean interactive) {
        this.token = token;
        this.displayName = displayName;
        this.interactive = interactive;
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
     * Whether the primary process runs behind a pseudo-terminal: the console echoes and
     * takes raw keystrokes plus resize frames instead of one command line per POST.
     */
    public boolean interactive() {
        return this.interactive;
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

    /**
     * The kind a settings map declares under {@link #SETTING}: absent or blank is
     * {@link #PLAIN} (the column default), an unknown token is null so the caller refuses.
     */
    public static @Nullable ConsoleKind declaredIn(@Nullable Map<String, ?> settings) {

        Object declared = settings == null ? null : settings.get(SETTING);

        if (declared == null || String.valueOf(declared).isBlank()) {
            return PLAIN;
        }

        return forToken(String.valueOf(declared).trim());
    }

    /**
     * {@link #declaredIn}, refusing an unknown token by name -- the shape every kind's
     * {@code specFor} uses, so a stored token this build does not know never deploys as
     * "plain" by accident.
     *
     * @throws Violations {@code console_kind_unknown} on {@code settings.console_kind}
     */
    public static @NonNull ConsoleKind requireDeclared(@Nullable Map<String, ?> settings) {

        ConsoleKind kind = declaredIn(settings);

        if (kind == null) {
            throw Violations.ofField("settings." + SETTING,
                String.valueOf(settings == null ? null : settings.get(SETTING)),
                Microcopy.of("console_kind_unknown").withFilter("scope", "violations")
                    .withArg("token", String.valueOf(settings == null ? null : settings.get(SETTING))));
        }

        return kind;
    }
}
