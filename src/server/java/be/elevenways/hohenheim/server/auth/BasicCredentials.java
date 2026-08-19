package be.elevenways.hohenheim.server.auth;

import be.elevenways.protoblast.common.Blast;
import be.elevenways.zenit.auth.server.PasswordHasher;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;

/**
 * THE argon2 home for access-list basic credentials: hashing on save and verification at
 * request time, so the two halves cannot drift into disagreeing about what a stored value
 * is.
 *
 * @author Jelle De Loecker &lt;jelle@elevenways.be&gt;
 * @since 0.1.0
 */
public final class BasicCredentials {

    private static final String ARGON2_PREFIX = "$argon2";

    private BasicCredentials() {
    }

    /**
     * @return the argon2 hash of a plaintext password, or the value unchanged when it is
     *         already hashed (a keep-blank submit restores the stored hash) or blank
     */
    public static @Nullable String hashIfNeeded(@Nullable String password) {
        if (password == null || password.isBlank() || password.startsWith(ARGON2_PREFIX)) {
            return password;
        }
        return PasswordHasher.hash(password);
    }

    /** @return true when the value is an argon2 hash rather than something unhashed */
    public static boolean isHashed(@Nullable String stored) {
        return stored != null && stored.startsWith(ARGON2_PREFIX);
    }

    /**
     * @param context what to name in the refusal log (the site being guarded)
     * @return true only when the stored value is an argon2 hash that verifies the presented
     *         password; any other non-null stored value is refused LOUDLY, never compared
     */
    // AIDEV-NOTE: this used to fall back to a constant-time PLAINTEXT compare for stored
    // values without the $argon2 prefix -- security theater that silently accepted an
    // unhashed column. The rule editor hashes on every save, so a non-argon2 stored value
    // is operator/data corruption and must fail closed and loud.
    public static boolean verifyPassword(@Nullable String presented, @Nullable String stored,
                                         @Nullable String context) {
        if (stored == null || presented == null) {
            return false;
        }
        if (!isHashed(stored)) {
            Blast.log("AccessListGate: stored basic-auth password for", context,
                "is not an argon2 hash; refusing authentication. Re-save the rule to hash it.");
            return false;
        }
        return PasswordHasher.verify(presented, stored);
    }

    /**
     * Verify an {@code Authorization: Basic} header against one expected credential pair.
     *
     * @return true when the header presents exactly this username and password
     */
    public static boolean matchesHeader(@Nullable String authHeader, @NonNull String username,
                                        @Nullable String storedHash, @Nullable String context) {
        Presented presented = parse(authHeader);
        if (presented == null) {
            return false;
        }
        // Both halves always run: the password verify is not skipped on a username miss,
        // so a wrong username and a wrong password cost the same time.
        boolean passwordMatches = verifyPassword(presented.password(), storedHash, context);
        return MessageDigest.isEqual(username.getBytes(StandardCharsets.UTF_8),
            presented.username().getBytes(StandardCharsets.UTF_8)) && passwordMatches;
    }

    /** The credentials an {@code Authorization: Basic} header carries, or null. */
    public static @Nullable Presented parse(@Nullable String authHeader) {
        if (authHeader == null || !authHeader.startsWith("Basic ")) {
            return null;
        }
        try {
            String decoded = new String(Base64.getDecoder().decode(authHeader.substring(6)),
                StandardCharsets.UTF_8);
            int colon = decoded.indexOf(':');
            if (colon < 0) {
                return null;
            }
            return new Presented(decoded.substring(0, colon), decoded.substring(colon + 1));
        } catch (IllegalArgumentException malformed) {
            return null;
        }
    }

    /** A decoded basic-auth header. */
    public record Presented(@NonNull String username, @NonNull String password) {
    }
}
