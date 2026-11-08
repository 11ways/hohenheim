package be.elevenways.hohenheim.model;

import be.elevenways.hohenheim.HohenheimMicrocopy;
import be.elevenways.zenit.common.orm.field.EnumField;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.List;
import java.util.Map;

/**
 * THE redirect vocabulary every redirect Hohenheim answers shares: its status codes and which targets it may send a
 * visitor to.
 *
 * @author Jelle De Loecker
 * @since  0.10.0
 */
public final class RedirectStatuses {

    /** The status a redirect answers when none is chosen or the stored one is no member. */
    public static final String DEFAULT = "302";

    /** Every status a redirect may answer, in the order a picker offers them. */
    public static final List<String> CODES = List.of("301", "302", "307", "308");

    private static final Map<String, String> DISPLAY_NAMES = Map.of("301", "301 Permanent", "302", "302 Found",
        "307", "307 Temporary Redirect", "308", "308 Permanent Redirect");

    private RedirectStatuses() {
    }

    /** @return a status field named {@code name}, offering {@link #CODES} in the reader's words */
    public static EnumField.@NonNull Builder field(@NonNull String name) {
        EnumField.Builder field = EnumField.builder(name);
        for (String code : CODES) {
            field.value(code, DISPLAY_NAMES.get(code), HohenheimMicrocopy.REDIRECT_STATUS.of(code));
        }
        return field.label(HohenheimMicrocopy.HOHENHEIM_FIELD.of("redirect_status"))
            .help(HohenheimMicrocopy.HELP.of("redirect_status"));
    }

    /** @return the status a stored value answers: the member, else {@link #DEFAULT} */
    public static int codeOf(@Nullable Object stored) {
        return codeOf(stored, DEFAULT);
    }

    /** @return the status a stored value answers: the member, else {@code fallback} (itself a member) */
    public static int codeOf(@Nullable Object stored, @NonNull String fallback) {
        String value = stored == null ? fallback : String.valueOf(stored).trim();
        return Integer.parseInt(CODES.contains(value) ? value : fallback);
    }

    /**
     * @return whether a redirect may send a visitor to {@code target}: an http(s) URL or a path on the same site, never
     *         a protocol-relative {@code //host} that would leave the site while reading as a path, and never a value
     *         holding a control character a Location header would carry
     */
    public static boolean isTarget(@Nullable String target) {
        if (target == null || target.isBlank() || !target.equals(target.strip())
                || target.chars().anyMatch(c -> c < 0x20 || c == 0x7F)) {
            return false;
        }
        if (target.startsWith("/")) {
            return !target.startsWith("//") && !target.startsWith("/\\");
        }
        return target.startsWith("http://") || target.startsWith("https://");
    }
}
