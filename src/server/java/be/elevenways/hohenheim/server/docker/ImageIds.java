package be.elevenways.hohenheim.server.docker;

import be.elevenways.protoblast.diagnostic.DigestAlgorithm;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * THE content-addressed id a Docker daemon names a local image by: {@code sha256:} and the image config's SHA-256.
 *
 * AIDEV-NOTE: a reference carrying the prefix names an image this daemon holds, never a registry repository: pulling
 * it would send the digest to a registry as a tag name. The digest's own shape is protoblast's
 * {@link DigestAlgorithm#isHex}.
 *
 * @author Jelle De Loecker
 * @since 0.1.0
 */
public final class ImageIds {

    /** What a content-addressed id starts with, ahead of its hex digest. */
    public static final String PREFIX = "sha256:";

    private ImageIds() {
    }

    /** @return whether {@code reference} names an image by its content id rather than by a repository */
    public static boolean isContentAddressed(@Nullable String reference) {
        return reference != null && reference.startsWith(PREFIX);
    }

    /** @return whether {@code value} is a whole content id, its digest included; null answers false */
    public static boolean isImageId(@Nullable String value) {
        return value != null && value.startsWith(PREFIX)
            && DigestAlgorithm.SHA_256.isHex(value.substring(PREFIX.length()));
    }

    /** @return the digest of a content id, or {@code id} itself when it carries no prefix */
    public static @NonNull String bare(@NonNull String id) {
        return isContentAddressed(id) ? id.substring(PREFIX.length()) : id;
    }
}
