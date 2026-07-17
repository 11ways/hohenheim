package be.elevenways.hohenheim.server.dns;

import be.elevenways.hohenheim.model.DnsPeerModel;
import be.elevenways.zenit.common.orm.datasource.Row;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.xbill.DNS.Name;
import org.xbill.DNS.TSIG;

import java.util.Locale;

/**
 * Builds dnsjava {@link TSIG} instances from stored peer credentials and maps
 * the small set of algorithm names Hohenheim accepts.
 */
public final class DnsTsig {

    private DnsTsig() {}

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
