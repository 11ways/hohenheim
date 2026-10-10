package be.elevenways.hohenheim.server.util;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.regex.Pattern;

/**
 * THE shape of a SHA-256 digest as Hohenheim stores and checks it: 64 lowercase hex digits, or Docker's prefixed id.
 *
 * @author Jelle De Loecker <jelle@elevenways.be>
 * @since 0.1.0
 */
public final class Sha256 {

    private static final String HEX_DIGITS = "[0-9a-f]{64}";

    private static final Pattern HEX = Pattern.compile(HEX_DIGITS);

    private static final Pattern IMAGE_ID = Pattern.compile("sha256:" + HEX_DIGITS);

    private Sha256() {
    }

    /** @return whether {@code value} is a lowercase hex digest; null answers false */
    public static boolean isHex(@Nullable String value) {
        return value != null && HEX.matcher(value).matches();
    }

    /** @return whether {@code value} is a Docker content id ({@code sha256:<hex>}); null answers false */
    public static boolean isImageId(@Nullable String value) {
        return value != null && IMAGE_ID.matcher(value).matches();
    }
}
