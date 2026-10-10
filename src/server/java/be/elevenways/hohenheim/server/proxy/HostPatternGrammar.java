package be.elevenways.hohenheim.server.proxy;

import be.elevenways.hohenheim.net.Hostnames;
import be.elevenways.protoblast.common.annotation.BlastAutoLoad;
import be.elevenways.zenit.server.http.HostPattern;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * The server's half of {@link Hostnames#PATTERNS}: zenit's HostPattern, installed at class-load.
 *
 * @author Jelle De Loecker <jelle@elevenways.be>
 * @since 0.1.0
 */
@BlastAutoLoad
public final class HostPatternGrammar implements Hostnames.PatternGrammar {

    public static final boolean LOADED = install();

    private HostPatternGrammar() {
    }

    private static boolean install() {
        Hostnames.PATTERNS.install(new HostPatternGrammar());
        return true;
    }

    /** A route's hostname names no port: the listener addresses are a column of their own. */
    @Override
    public @Nullable String refusal(@NonNull String value) {
        try {
            return HostPattern.parse(value).port() == null ? null
                : "a route hostname names no port; its listeners are a column of their own";
        } catch (IllegalArgumentException refused) {
            return refused.getMessage();
        }
    }

    /**
     * AIDEV-NOTE: the translation is held to {@link #refusal}, not only to core's grammar: core's HostPattern carries a
     * port, a route hostname never does, and a stored row with one must fail M011 by name rather than be respelled
     * into a pattern no request host can match.
     */
    @Override
    public @NonNull String fromLegacyGlob(@NonNull String glob) {
        String translated = HostPattern.fromLegacyGlob(glob);
        String refused = this.refusal(translated);
        if (refused != null) {
            throw new IllegalArgumentException(refused);
        }
        return translated;
    }

    @Override
    public int specificity(@NonNull String pattern) {
        return HostnamePatterns.specificity(HostPattern.parse(pattern));
    }

    @Override
    public boolean overlaps(@NonNull String first, @NonNull String second) {
        return HostPattern.parse(first).overlap(HostPattern.parse(second)) == HostPattern.Overlap.OVERLAPS;
    }

    @Override
    public @NonNull String tieKey(@NonNull String pattern) {
        return HostnamePatterns.tieKey(HostPattern.parse(pattern));
    }

    @Override
    public @NonNull String legacyTieKey(@NonNull String legacyGlob) {
        return HostnamePatterns.legacyTieKey(legacyGlob, HostPattern.parse(HostPattern.fromLegacyGlob(legacyGlob)));
    }
}
