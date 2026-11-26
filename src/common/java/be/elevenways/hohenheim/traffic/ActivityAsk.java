package be.elevenways.hohenheim.traffic;

import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.Objects;

/**
 * THE spelling of what a live traffic link asks for, as its open data and as a TV link's scope: every site, one site,
 * or (open data only) what a TV link's token opens.
 *
 * @param kind  what is asked
 * @param value the site's source, or the TV link's token; null for every site
 * @author Jelle De Loecker
 * @since  0.1.0
 */
public record ActivityAsk(@NonNull Kind kind, @Nullable String value) {

    /** What a live link asks for. */
    public enum Kind {
        EVERY("every"),
        SITE("site"),
        TV("tv");

        private final @NonNull String token;

        Kind(@NonNull String token) {
            this.token = token;
        }
    }

    private static final char SEPARATOR = ':';

    public ActivityAsk {
        Objects.requireNonNull(kind, "kind cannot be null");
        if ((kind == Kind.EVERY) != (value == null)) {
            throw new IllegalArgumentException("Every site names no value; one site and a TV link do");
        }
    }

    public static @NonNull ActivityAsk every() {
        return new ActivityAsk(Kind.EVERY, null);
    }

    public static @NonNull ActivityAsk site(@NonNull String source) {
        return new ActivityAsk(Kind.SITE, source);
    }

    public static @NonNull ActivityAsk tv(@NonNull String token) {
        return new ActivityAsk(Kind.TV, token);
    }

    /** @return the ask a spelling names, null for anything else */
    public static @Nullable ActivityAsk parse(@Nullable Object spelled) {
        if (!(spelled instanceof String text) || text.isEmpty()) {
            return null;
        }
        int at = text.indexOf(SEPARATOR);
        String head = at < 0 ? text : text.substring(0, at);
        String value = at < 0 ? null : text.substring(at + 1);
        for (Kind kind : Kind.values()) {
            if (kind.token.equals(head) && (kind == Kind.EVERY ? value == null : value != null && !value.isEmpty())) {
                return new ActivityAsk(kind, value);
            }
        }
        return null;
    }

    /** @return this ask's spelling, which {@link #parse} reads back */
    public @NonNull String spelled() {
        return this.value == null ? this.kind.token : this.kind.token + SEPARATOR + this.value;
    }
}
