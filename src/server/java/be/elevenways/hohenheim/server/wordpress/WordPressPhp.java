package be.elevenways.hohenheim.server.wordpress;

import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * The PHP versions the WordPress starter templates ship, each mapped to the official
 * {@code wordpress:<tag>} Apache image -- the one declaring home of that vocabulary; the
 * seeder derives one template per member and nothing else spells an image tag.
 *
 * AIDEV-NOTE: a FROZEN member is a tag Docker Hub no longer publishes new builds for, kept
 * because an imported docroot brings its own WordPress files, so only the image's PHP matters
 * to an import. {@link #PHP_7_4} froze at WordPress 6.1.1 (2022-11-16) and serves the Phoenix
 * di-ax sites; {@link #PHP_8_1} left the upstream matrix when PHP 8.1 reached end of life
 * (2025-12-31; the matrix read 8.2..8.5 on 2026-09-23). A fresh site starts on
 * {@link #recommended()}.
 *
 * AIDEV-NOTE: {@link #original()} marks the members the first seed wave planted. That wave
 * is LEDGERED under one key on every running installation, so a member added later seeds
 * under its own key (WordPressTemplateSeeder.ledgerKeyOf) -- appending it to the original
 * wave would never reach an installation that already ran it. Existing templates and the
 * instances created from them keep their stored tag.
 */
public enum WordPressPhp {

    PHP_8_5("8.5", "php8.5-apache", false, false),
    PHP_8_4("8.4", "php8.4-apache", false, false),
    PHP_8_3("8.3", "php8.3-apache", false, false),
    PHP_8_2("8.2", "php8.2-apache", false, false),
    PHP_8_1("8.1", "php8.1-apache", true, true),
    PHP_7_4("7.4", "php7.4-apache", true, true);

    /** The official image every member's tag belongs to. */
    public static final String IMAGE = "wordpress";

    private final String version;
    private final String tag;
    private final boolean frozen;
    private final boolean original;

    WordPressPhp(String version, String tag, boolean frozen, boolean original) {
        this.version = version;
        this.tag = tag;
        this.frozen = frozen;
        this.original = original;
    }

    /**
     * The member a NEW site should start on: maintained upstream and the broadest plugin
     * compatibility among the maintained ones.
     */
    public static @NonNull WordPressPhp recommended() {
        return PHP_8_4;
    }

    /** @return the PHP version as it reads in a template name ("8.1") */
    public @NonNull String version() {
        return this.version;
    }

    /** @return the image tag under {@link #IMAGE} */
    public @NonNull String tag() {
        return this.tag;
    }

    /** @return whether Docker Hub no longer updates this tag (import-only member) */
    public boolean frozen() {
        return this.frozen;
    }

    /** @return whether the first, ledgered seed wave planted this member's template */
    public boolean original() {
        return this.original;
    }

    /** @return the matching member, or null when no member carries this version (fail closed) */
    public static @Nullable WordPressPhp forVersion(@Nullable String version) {
        if (version == null) {
            return null;
        }
        for (WordPressPhp php : values()) {
            if (php.version.equals(version)) {
                return php;
            }
        }
        return null;
    }
}
