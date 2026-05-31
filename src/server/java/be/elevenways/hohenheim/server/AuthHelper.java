package be.elevenways.hohenheim.server;

import java.security.SecureRandom;
import java.util.Base64;

/**
 * Credential helpers. Admin authentication is handled by zenit-auth; this only generates
 * strong random secrets (e.g. auto-provisioned managed-database passwords).
 */
public class AuthHelper {

    private static final SecureRandom RANDOM = new SecureRandom();

    /** A strong random password (URL-safe, no padding) for an auto-provisioned resource. */
    public static String generatePassword() {
        byte[] bytes = new byte[18];   // 18 bytes -> 24 url-safe base64 chars
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
