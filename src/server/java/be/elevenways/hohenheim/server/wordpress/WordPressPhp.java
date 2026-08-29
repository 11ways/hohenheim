package be.elevenways.hohenheim.server.wordpress;

import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * The PHP versions the WordPress starter templates ship, each mapped to the official
 * {@code wordpress:<tag>} Apache image -- the one declaring home of that vocabulary; the
 * seeder derives one template per member and nothing else spells an image tag.
 *
 * AIDEV-NOTE: {@link #PHP_7_4} is a FROZEN image (Docker Hub stopped publishing
 * php7.4 variants at WordPress 6.1.1, 2022-11-16) and exists for one reason: the Phoenix
 * di-ax sites run PHP 7.4 and an imported docroot brings its own WordPress files, so the
 * image's bundled WordPress version is irrelevant to an import and only its PHP matters.
 * A fresh site starts on {@link #PHP_8_1} or newer.
 */
public enum WordPressPhp {

    PHP_8_1("8.1", "php8.1-apache", false),
    PHP_7_4("7.4", "php7.4-apache", true);

    /** The official image every member's tag belongs to. */
    public static final String IMAGE = "wordpress";

    private final String version;
    private final String tag;
    private final boolean frozen;

    WordPressPhp(String version, String tag, boolean frozen) {
        this.version = version;
        this.tag = tag;
        this.frozen = frozen;
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
