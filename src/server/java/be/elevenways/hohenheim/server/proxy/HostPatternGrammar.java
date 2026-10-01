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

    @Override
    public @NonNull String respellOneOrMoreLeading(@NonNull String value) {
        return HostPattern.respellOneOrMoreLeading(value);
    }
}
