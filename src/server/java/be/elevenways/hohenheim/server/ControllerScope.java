package be.elevenways.hohenheim.server;

import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * THE namer for everything hohenheim writes onto a resource pool it does not own alone:
 * Docker containers/networks/volumes/image tags, Incus instances/ACLs/bridges/volumes,
 * nftables tables, and object keys on a shared backup target.
 *
 * Every name carries this controller's {@link ControllerIdentity} token, so two
 * controllers pointed at one daemon mint DIFFERENT names for their respective record #1
 * and each one's ownership guard can genuinely refuse the other's workload.
 *
 * AIDEV-NOTE: the shape is {@code hohenheim-<token>-<kind>-<discriminator>} and the token
 * slot is what {@link #parse} keys on -- a fixed-length lowercase alnum segment in
 * position 2. That is what lets the reconciler tell OUR resource from ANOTHER
 * CONTROLLER's (same shape, different token) from a pre-namespace legacy name (position 2
 * is a kind word, never token-shaped) from a stranger's. Do not put the token last: the
 * discriminator is the part that may itself contain dashes (database and stack names).
 *
 * AIDEV-NOTE: the {@code hohenheim-} prefix STAYS in front of the token. It is what
 * DockerReconciler's "hohenheim-prefixed but matches no naming scheme" collision bucket
 * keys on, and dropping it would make another controller's resources indistinguishable
 * from a stranger's.
 */
public final class ControllerScope {

    /** The product prefix every managed name starts with. */
    public static final String PREFIX = "hohenheim";

    // Kind words. One per tier that names a daemon-side resource.
    public static final String KIND_INSTANCE = "instance";
    public static final String KIND_SITE = "site";
    public static final String KIND_DB = "db";
    public static final String KIND_STACK = "stack";
    public static final String KIND_BUILD = "build";
    public static final String KIND_PREVIEW = "preview";
    public static final String KIND_DBLINK = "dblink";
    public static final String KIND_GAMELINK = "gamelink";

    /** A parsed managed name. */
    public record Scoped(@NonNull String token, @NonNull String kind,
                         @NonNull String discriminator) {

        /** @return whether this name was minted by THIS controller */
        public boolean isOurs() {
            return this.token.equals(ControllerIdentity.token());
        }
    }

    /** A parsed per-controller SHARED name: the {@link #scoped}/{@link #shortScoped} shape. */
    public record SharedScoped(@NonNull String token, @NonNull String suffix) {

        /** @return whether this name was minted by THIS controller */
        public boolean isOurs() {
            return this.token.equals(ControllerIdentity.token());
        }
    }

    private ControllerScope() {
    }

    /**
     * The workload handle of one record.
     *
     * @param kind          one of the {@code KIND_*} words
     * @param discriminator the record id, or the record's unique name for name-keyed tiers
     */
    public static @NonNull String handle(@NonNull String kind, @NonNull Object discriminator) {
        return kindPrefix(kind) + discriminator;
    }

    /**
     * The scoped prefix every handle of one kind starts with, for callers that SCAN a
     * daemon listing rather than name a single record.
     */
    public static @NonNull String kindPrefix(@NonNull String kind) {
        return PREFIX + "-" + ControllerIdentity.token() + "-" + kind + "-";
    }

    /**
     * A scoped name for a per-controller SHARED object (one per daemon per controller,
     * not one per record), e.g. the Incus isolation ACL.
     */
    public static @NonNull String scoped(@NonNull String suffix) {
        return PREFIX + "-" + ControllerIdentity.token() + "-" + suffix;
    }

    /**
     * A scoped name for a length-capped consumer.
     *
     * @param prefix a short product marker; the result is {@code prefix-token}, so a
     *               3-character prefix fits the kernel's 15-character interface-name limit
     */
    public static @NonNull String shortScoped(@NonNull String prefix) {
        return prefix + "-" + ControllerIdentity.token();
    }

    /**
     * A scoped nftables identifier: underscore-joined, because nft identifiers reject
     * the hyphen every Docker/Incus name uses.
     */
    public static @NonNull String nftName(@NonNull String base) {
        return base + "_" + ControllerIdentity.token();
    }

    /** @return whether a token-shaped segment could be a controller token */
    public static boolean isTokenShaped(@NonNull String segment) {
        if (segment.length() != ControllerIdentity.TOKEN_LENGTH) {
            return false;
        }
        for (int i = 0; i < segment.length(); i++) {
            char c = segment.charAt(i);
            if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'z'))) {
                return false;
            }
        }
        return true;
    }

    /**
     * Parse a per-controller SHARED name minted by {@link #scoped} back into its parts.
     *
     * AIDEV-NOTE: {@link #parse} deliberately cannot read these. It demands a kind AND a
     * discriminator ({@code hohenheim-<token>-<kind>-<rest>}), which a shared object does
     * not have -- it is one object per controller, so there is nothing to discriminate.
     * Feeding {@code hohenheim-<token>-isolation} to parse() answers null, and a reaper
     * that read that as "not ours" would be reading it as "not a hohenheim name at all".
     *
     * @return null when the name is not {@code hohenheim-<token>-<suffix>} shaped -- a
     *         pre-namespace name like {@code hohenheim-isolation} included, because that
     *         one is attributable to NO controller
     */
    public static @Nullable SharedScoped parseShared(@NonNull String name) {
        String prefix = PREFIX + "-";
        if (!name.startsWith(prefix)) {
            return null;
        }
        String rest = name.substring(prefix.length());
        int tokenEnd = rest.indexOf('-');
        if (tokenEnd < 0 || tokenEnd == rest.length() - 1) {
            return null;
        }
        String token = rest.substring(0, tokenEnd);
        return isTokenShaped(token)
            ? new SharedScoped(token, rest.substring(tokenEnd + 1)) : null;
    }

    /**
     * Parse a length-capped name minted by {@link #shortScoped}.
     *
     * @param prefix the same short product marker the name was minted with
     * @return null when the name is not {@code prefix-<token>} shaped
     */
    public static @Nullable SharedScoped parseShortScoped(@NonNull String prefix,
                                                          @NonNull String name) {
        String head = prefix + "-";
        if (!name.startsWith(head)) {
            return null;
        }
        String token = name.substring(head.length());
        return isTokenShaped(token) ? new SharedScoped(token, prefix) : null;
    }

    /**
     * Parse a managed name back into its parts.
     *
     * @return null when the name is not {@code hohenheim-<token>-<kind>-<rest>} shaped --
     *         a pre-namespace legacy name, or a stranger's
     */
    public static @Nullable Scoped parse(@NonNull String name) {
        String prefix = PREFIX + "-";
        if (!name.startsWith(prefix)) {
            return null;
        }
        String rest = name.substring(prefix.length());
        int tokenEnd = rest.indexOf('-');
        if (tokenEnd < 0) {
            return null;
        }
        String token = rest.substring(0, tokenEnd);
        if (!isTokenShaped(token)) {
            return null;
        }
        String afterToken = rest.substring(tokenEnd + 1);
        int kindEnd = afterToken.indexOf('-');
        if (kindEnd <= 0 || kindEnd == afterToken.length() - 1) {
            return null;
        }
        return new Scoped(token, afterToken.substring(0, kindEnd),
            afterToken.substring(kindEnd + 1));
    }
}
