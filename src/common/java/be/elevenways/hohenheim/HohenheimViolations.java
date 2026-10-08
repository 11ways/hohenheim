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

    /**
     * A stored reason as text for its reader: what {@link #reasonOf} stores, or, for a reason stored before it existed,
     * the refusal it debug-rendered ("1 violation(s):  -> workspace_build_failed {reason=...}") in its own words.
     *
     * AIDEV-NOTE: only ONE violation is read back, because a list of them joins entries AND args with ", " and cannot be
     * split without guessing. A key this catalog does not hold, or any other shape, keeps the stored text: an old row
     * reads as it was stored rather than as a different message. A message that changed its arguments since (the
     * build failure took {@code code} where it stored {@code reason}) cannot be filled from the row: it reads as the
     * row's one stored argument when there is exactly one, else as stored.
     *
     * @return the stored text, or the one refusal it renders in the installation's default content locale
     */
    public static @NonNull String storedText(@NonNull String stored) {
        String prefix = "1 violation(s): ";
        int arrow = stored.indexOf(" -> ");
        if (!stored.startsWith(prefix) || arrow < prefix.length() - 1) {
            return stored;
        }
        String entry = stored.substring(arrow + 4);
        int space = entry.indexOf(' ');
        String key = space < 0 ? entry : entry.substring(0, space);
        if (!isToken(key)) {
            return stored;
        }
        Microcopy message = text(key);
        if (space >= 0) {
            String args = entry.substring(space + 1);
            if (!args.startsWith("{") || !args.endsWith("}")) {
                return stored;
            }
            message = withStoredArgs(message, args.substring(1, args.length() - 1));
            if (message == null) {
                return stored;
            }
        }
        LocaleChain locales = LocaleChain.of(ContentLocales.getDefault());
        String text = message.tryResolve(locales, MessageResolvers.getDefault());
        if (text == null) {
            return stored;
        }
        String source = message.resolveSource(locales, MessageResolvers.getDefault());
        int open = source.indexOf("{$");
        while (open >= 0) {
            int end = open + 2;
            while (end < source.length() && isToken(source.substring(end, end + 1))) {
                end++;
            }
            if (!message.args().asMap().containsKey(source.substring(open + 2, end))) {
                return message.args().asMap().size() == 1
                    ? String.valueOf(message.args().asMap().values().iterator().next()) : stored;
            }
            open = source.indexOf("{$", end);
        }
        return text;
    }

    /** @return the message with each "name=value" of a debug-rendered args map, or null when one is malformed */
    private static @Nullable Microcopy withStoredArgs(@NonNull Microcopy message, @NonNull String body) {
        Microcopy result = message;
        int start = 0;
        while (start < body.length()) {
            int equals = body.indexOf('=', start);
            if (equals < 0 || !isToken(body.substring(start, equals))) {
                return null;
            }
            int end = nextArgument(body, equals + 1);
            result = result.withArg(body.substring(start, equals), body.substring(equals + 1, end));
            start = end < body.length() ? end + 2 : end;
        }
        return result;
    }

    /** @return where the next ", name=" begins after {@code from}, else the end: a value may itself hold ", " */
    private static int nextArgument(@NonNull String body, int from) {
        int comma = body.indexOf(", ", from);
        while (comma >= 0) {
            int equals = body.indexOf('=', comma + 2);
            if (equals > comma + 2 && isToken(body.substring(comma + 2, equals))) {
                return comma;
            }
            comma = body.indexOf(", ", comma + 2);
        }
        return body.length();
    }

    private static boolean isToken(@NonNull String text) {
        if (text.isEmpty()) {
            return false;
        }
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (!(c >= 'a' && c <= 'z' || c >= '0' && c <= '9' || c == '_')) {
                return false;
            }
        }
        return true;
    }

    /** @return the message resolved in the installation's default content locale */
    public static @NonNull String textOf(@NonNull Microcopy message) {
        return message.resolve(LocaleChain.of(ContentLocales.getDefault()), MessageResolvers.getDefault());
    }
}
