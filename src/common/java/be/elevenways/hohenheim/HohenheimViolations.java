package be.elevenways.hohenheim;

import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.validation.Violations;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * THE home of Hohenheim's violation copy: every message Hohenheim raises is declared under {@code scope=violations}.
 *
 * AIDEV-NOTE: the scope is spelled here and nowhere else; a raiser that spells it itself is how one message silently
 * loses the filter and resolves some other entry of the same word. Hohenheim's catalog keeps its own scope rather
 * than core's ValidationMicrocopy one: re-keying it would let Hohenheim entries shadow core's validation messages.
 *
 * @author Jelle De Loecker
 * @since 0.1.0
 */
public final class HohenheimViolations {

    /** The filter value every Hohenheim violation message is declared and looked up under. */
    public static final String SCOPE = "violations";

    private HohenheimViolations() {
    }

    /** @return the message under the violations scope, ready for its args */
    public static @NonNull Microcopy text(@NonNull String key) {
        return Microcopy.of(key).withFilter("scope", SCOPE);
    }

    /** @return a refusal anchored on one field */
    public static @NonNull Violations ofField(@NonNull String field, @Nullable Object value, @NonNull String key) {
        return Violations.ofField(field, value, text(key));
    }

    /** @return a form-level refusal */
    public static @NonNull Violations ofForm(@NonNull String key) {
        return Violations.ofForm(text(key));
    }

    /**
     * An operation on an instance refused: the message names the instance and, when a failure caused the refusal,
     * its reason.
     */
    public static @NonNull Microcopy instanceRefusalText(@NonNull String key, @NonNull Row instance,
                                                         @Nullable Throwable cause) {
        Microcopy text = text(key).withArg("name", String.valueOf((Object) instance.get(InstanceModel.NAME)));
        return cause == null ? text : text.withArg("reason", reasonOf(cause));
    }

    /** @return {@link #instanceRefusalText} as a form-level refusal */
    public static @NonNull Violations instanceRefusal(@NonNull String key, @NonNull Row instance,
                                                      @Nullable Throwable cause) {
        return Violations.ofForm(instanceRefusalText(key, instance, cause));
    }

    /** @return a failure's message, or its type and nothing else when it carries none */
    public static @NonNull String reasonOf(@NonNull Throwable cause) {
        return cause.getMessage() != null ? cause.getMessage() : cause.toString();
    }
}
