package be.elevenways.hohenheim.server;

import be.elevenways.zenit.server.security.SecureTokens;

/**
 * Generates strong random secrets (e.g. auto-provisioned managed-database passwords).
 * Authentication itself is handled by zenit-auth; the primitive is zenit's SecureTokens.
 */
public final class Secrets {

    private Secrets() {
    }

    /** A strong random password (URL-safe, no padding) for an auto-provisioned resource. */
    public static String generatePassword() {
        return SecureTokens.randomToken(18);   // 18 bytes -> 24 url-safe base64 chars
    }
}
