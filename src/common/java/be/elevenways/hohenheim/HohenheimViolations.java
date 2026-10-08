package be.elevenways.hohenheim;

import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.protoblast.common.i18n.LocaleChain;
import be.elevenways.protoblast.common.i18n.MessageResolvers;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.setting.ContentLocales;
import be.elevenways.zenit.common.validation.Violation;
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

    /**
     * A failure as text for a record a person reads later: an operation's stored reason, a log line.
     *
     * AIDEV-NOTE: a refusal reads as its own messages, never as {@code Violations.getMessage()}, which is a debug
     * rendering by its own contract ("1 violation(s): -> workspace_build_failed {reason=...}") and reached the
     * Deploys tab verbatim. Where a reason is stored there is no reader to localize for, so it is the installation's
     * default content locale, the choice {@code Violations.describedIn} makes for the same reason.
     *
     * @return the refusal's messages, else the failure's message, else its type
     */
    public static @NonNull String reasonOf(@NonNull Throwable cause) {
        if (cause instanceof Violations refused && !refused.isEmpty()) {
            StringBuilder text = new StringBuilder();
            for (Violation violation : refused) {
                if (text.length() > 0) {
                    text.append(' ');
                }
                text.append(textOf(violation.message()));
            }
            return text.toString();
        }
        return cause.getMessage() != null ? cause.getMessage() : cause.toString();
    }

    /** @return the message resolved in the installation's default content locale */
    public static @NonNull String textOf(@NonNull Microcopy message) {
        return message.resolve(LocaleChain.of(ContentLocales.getDefault()), MessageResolvers.getDefault());
    }
}
