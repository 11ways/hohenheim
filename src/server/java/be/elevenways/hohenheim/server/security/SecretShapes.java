package be.elevenways.hohenheim.server.security;

import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Shape checks for stored and adopted secret values, shared by every place that
 * hashes a bearer credential in place ({@code SiteApiKeys}, {@code DynamicDnsService}).
 *
 * @author Jelle De Loecker
 */
public final class SecretShapes {

    /**
     * Minimum length of an operator-entered plaintext credential we will adopt
     * (hash and store). Minted tokens are {@code marker + 32} chars of 192-bit
     * randomness, so they clear this floor with room to spare.
     */
    public static final int MIN_ADOPTABLE_LENGTH = 32;

    /** Minimum number of distinct characters in an adoptable plaintext credential. */
    public static final int MIN_ADOPTABLE_DISTINCT = 12;

    private SecretShapes() {}

    /**
     * Validates the COMPLETE digest shape: the given prefix followed by exactly
     * 64 lowercase hex characters. Anything else -- including a plaintext value
     * that merely STARTS with the prefix -- is not a digest.
     */
    public static boolean isSha256Digest(@Nullable String value, @NonNull String prefix) {
        if (value == null || value.length() != prefix.length() + 64 || !value.startsWith(prefix)) {
            return false;
        }
        for (int i = prefix.length(); i < value.length(); i++) {
            char c = value.charAt(i);
            boolean hex = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f');
            if (!hex) {
                return false;
            }
        }
        return true;
    }

    /**
     * Entropy floor for ADOPTING an operator-entered plaintext credential that will
     * be stored as a fast SHA-256 digest: a copied database makes a weak value
     * dictionary-recoverable, so short or repetitive strings are refused instead
     * of silently accepted.
     *
     * AIDEV-NOTE: length + distinct-character count is a PROXY for entropy, not a
     * measurement -- a long structured string can still be weak. The real guarantee
     * remains the minted 192-bit tokens; this floor only refuses the obviously
     * dictionary-shaped input. Already-stored legacy plaintext is deliberately NOT
     * refused: the seeders hash it to a digest BEFORE save, so the write hooks only
     * ever see digests for grandfathered values and a weak legacy key keeps working
     * (as defence-in-depth-only) instead of bricking the row.
     */
    public static boolean isAdoptablePlaintext(@Nullable String value) {
        if (value == null || value.length() < MIN_ADOPTABLE_LENGTH) {
            return false;
        }
        return value.chars().distinct().count() >= MIN_ADOPTABLE_DISTINCT;
    }
}
