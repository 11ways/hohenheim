package be.elevenways.hohenheim.server.dns;

import be.elevenways.hohenheim.model.DnsPeerModel;
import be.elevenways.zenit.common.orm.datasource.Row;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.xbill.DNS.Name;
import org.xbill.DNS.TSIG;

import java.util.Locale;
import java.util.Set;

/**
 * Builds dnsjava {@link TSIG} instances from stored peer credentials and maps
 * the small set of algorithm names Hohenheim accepts.
 */
public final class DnsTsig {

    /**
     * The algorithm names Hohenheim accepts; {@link #algorithmName} maps each one.
     *
     * AIDEV-NOTE: the vocabulary's one declaring home. {@code DnsPeerResource} kept its
     * own copy of this set until 2026-08-19, so adding an algorithm meant editing two
     * files and forgetting one silently refused a value the mapper understood.
     */
    public static final Set<String> ALGORITHMS = Set.of(
        "hmac-sha256", "hmac-sha512", "hmac-sha384", "hmac-sha224", "hmac-sha1");

    private DnsTsig() {}

    /** @return true when the (case-insensitive, trimmed) algorithm name is one we accept */
    public static boolean isSupportedAlgorithm(@Nullable String algorithm) {
        return algorithm != null && ALGORITHMS.contains(algorithm.trim().toLowerCase(Locale.ROOT));
    }

    /** @return the peer's TSIG key, or null when the peer has no key configured */
    public static @Nullable TSIG forPeer(@NonNull Row peer) {
        String keyName = peer.get(DnsPeerModel.TSIG_KEY_NAME);
        String secret = peer.get(DnsPeerModel.TSIG_SECRET);
        if (keyName == null || keyName.isBlank() || secret == null || secret.isBlank()) {
            return null;
        }
        Name algorithm = algorithmName(peer.get(DnsPeerModel.TSIG_ALGORITHM));
        return new TSIG(algorithm, canonicalKeyName(keyName), secret.trim());
    }

    /** @return the algorithm's dnsjava Name, defaulting to HMAC-SHA256 for unknown/blank values */
    public static @NonNull Name algorithmName(@Nullable String algorithm) {
        String value = algorithm != null ? algorithm.trim().toLowerCase(Locale.ROOT) : "";
        return switch (value) {
            case "hmac-sha1" -> TSIG.HMAC_SHA1;
            case "hmac-sha224" -> TSIG.HMAC_SHA224;
            case "hmac-sha384" -> TSIG.HMAC_SHA384;
            case "hmac-sha512" -> TSIG.HMAC_SHA512;
            default -> TSIG.HMAC_SHA256;
        };
    }

    /** TSIG key names are DNS names; store/compare them lowercased and absolute. */
    public static @NonNull Name canonicalKeyName(@NonNull String keyName) {
        String value = keyName.trim().toLowerCase(Locale.ROOT);
        try {
            return Name.fromString(value.endsWith(".") ? value : value + ".");
        }
        catch (org.xbill.DNS.TextParseException e) {
            throw new IllegalArgumentException("Invalid TSIG key name: " + keyName, e);
        }
    }
}
