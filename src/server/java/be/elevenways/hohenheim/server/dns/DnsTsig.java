package be.elevenways.hohenheim.server.dns;

import be.elevenways.hohenheim.model.DnsPeerModel;
import be.elevenways.hohenheim.net.Hostnames;
import be.elevenways.zenit.common.orm.datasource.Row;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.xbill.DNS.Name;
import org.xbill.DNS.TSIG;
import org.xbill.DNS.TextParseException;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Builds dnsjava {@link TSIG} instances from stored peer credentials and maps
 * the small set of algorithm names Hohenheim accepts.
 */
public final class DnsTsig {

    /**
     * THE TSIG algorithm vocabulary: each member carries its stored name and its dnsjava
     * algorithm as facts.
     *
     * AIDEV-NOTE: the stored names are what production peer rows hold ({@code hmac-sha256}
     * and siblings, lowercase, the only values DnsPeerResource and the peer API ever
     * accepted), so they never change. An unknown stored name used to fall into a
     * {@code default -> HMAC_SHA256} arm and was silently keyed as SHA-256; it is refused now.
     */
    public enum Algorithm {
        HMAC_SHA256("hmac-sha256", TSIG.HMAC_SHA256),
        HMAC_SHA512("hmac-sha512", TSIG.HMAC_SHA512),
        HMAC_SHA384("hmac-sha384", TSIG.HMAC_SHA384),
        HMAC_SHA224("hmac-sha224", TSIG.HMAC_SHA224),
        HMAC_SHA1("hmac-sha1", TSIG.HMAC_SHA1);

        private final String key;
        private final Name dnsName;

        Algorithm(@NonNull String key, @NonNull Name dnsName) {
            this.key = key;
            this.dnsName = dnsName;
        }

        /** @return the stored (and form-submitted) name */
        public @NonNull String key() {
            return this.key;
        }

        /** @return the algorithm as dnsjava names it on the wire */
        public @NonNull Name dnsName() {
            return this.dnsName;
        }

        /** @return the member a (case-insensitive, trimmed, root-dot-tolerant) name spells, or null */
        public static @Nullable Algorithm fromKey(@Nullable String value) {
            if (value == null) {
                return null;
            }
            String key = Hostnames.stripTrailingDots(value.trim().toLowerCase(Locale.ROOT));
            for (Algorithm algorithm : values()) {
                if (algorithm.key.equals(key)) {
                    return algorithm;
                }
            }
            return null;
        }
    }

    /** The algorithm a peer row with NO algorithm stored is keyed with (the column is optional). */
    public static final Algorithm DEFAULT = Algorithm.HMAC_SHA256;

    /**
     * The algorithm names Hohenheim accepts, derived from {@link Algorithm}.
     *
     * AIDEV-NOTE: the vocabulary's one declaring home is the enum. {@code DnsPeerResource}
     * kept its own copy of this set until 2026-08-19, so adding an algorithm meant editing two
     * files and forgetting one silently refused a value the mapper understood.
     */
    public static final Set<String> ALGORITHMS = keys();

    private DnsTsig() {}

    private static @NonNull Set<String> keys() {
        Set<String> keys = new LinkedHashSet<>();
        for (Algorithm algorithm : Algorithm.values()) {
            keys.add(algorithm.key());
        }
        return Set.copyOf(keys);
    }

    /** @return true when the (case-insensitive, trimmed) algorithm name is one we accept */
    public static boolean isSupportedAlgorithm(@Nullable String algorithm) {
        return Algorithm.fromKey(algorithm) != null;
    }

    /**
     * @return the peer's TSIG key, or null when the peer has no key configured
     * @throws IllegalArgumentException when the stored algorithm is not one we accept; the
     *         caller must fail CLOSED (refuse the transfer, ignore the NOTIFY), never treat it
     *         as "no key"
     */
    public static @Nullable TSIG forPeer(@NonNull Row peer) {
        String keyName = peer.get(DnsPeerModel.TSIG_KEY_NAME);
        String secret = peer.get(DnsPeerModel.TSIG_SECRET);
        if (keyName == null || keyName.isBlank() || secret == null || secret.isBlank()) {
            return null;
        }
        Name algorithm = algorithmName(peer.get(DnsPeerModel.TSIG_ALGORITHM));
        return new TSIG(algorithm, canonicalKeyName(keyName), secret.trim());
    }

    /**
     * @return the algorithm's dnsjava Name; a blank value is {@link #DEFAULT}
     * @throws IllegalArgumentException when a non-blank value names no accepted algorithm
     */
    public static @NonNull Name algorithmName(@Nullable String algorithm) {
        if (algorithm == null || algorithm.isBlank()) {
            return DEFAULT.dnsName();
        }
        Algorithm known = Algorithm.fromKey(algorithm);
        if (known == null) {
            throw new IllegalArgumentException("Unsupported TSIG algorithm: " + algorithm);
        }
        return known.dnsName();
    }

    /** TSIG key names are DNS names; store/compare them lowercased and absolute. */
    public static @NonNull Name canonicalKeyName(@NonNull String keyName) {
        String value = keyName.trim().toLowerCase(Locale.ROOT);
        try {
            return Name.fromString(Hostnames.stripTrailingDots(value) + ".");
        }
        catch (TextParseException e) {
            throw new IllegalArgumentException("Invalid TSIG key name: " + keyName, e);
        }
    }
}
