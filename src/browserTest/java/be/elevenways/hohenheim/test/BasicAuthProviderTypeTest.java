package be.elevenways.hohenheim.test;

import be.elevenways.hohenheim.server.auth.types.BasicAuthProviderType;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure-logic tests for the Basic auth provider: save-time hashing and header verification. The
 * full gate (session establishment, 401 challenge) is exercised by the proxy integration tests.
 *
 * @author Jelle De Loecker <jelle@elevenways.be>
 * @since 0.1.0
 */
public class BasicAuthProviderTypeTest {

    private final BasicAuthProviderType type = new BasicAuthProviderType();

    private static String basicHeader(String user, String pass) {
        String raw = user + ":" + pass;
        return "Basic " + java.util.Base64.getEncoder()
            .encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    private static Map<String, Object> submitted(String user, String pass) {
        return Map.of(BasicAuthProviderType.CREDENTIALS,
            List.of(Map.of(BasicAuthProviderType.USERNAME, user, BasicAuthProviderType.PASSWORD, pass)));
    }

    @Test
    void savedConfigHashesPasswordAndNeverStoresPlaintext() {
        Map<String, Object> stored = type.normalizeConfigForSave(submitted("alice", "s3cret"), null);

        List<Map<String, Object>> creds = BasicAuthProviderType.credentialList(stored);
        assertEquals(1, creds.size());
        assertEquals("alice", creds.get(0).get(BasicAuthProviderType.USERNAME));
        assertNull(creds.get(0).get(BasicAuthProviderType.PASSWORD), "plaintext must not be stored");
        String hash = (String) creds.get(0).get(BasicAuthProviderType.PASSWORD_HASH);
        assertTrue(hash != null && hash.startsWith("$argon2"), "password must be Argon2-hashed");
    }

    @Test
    void verifyAcceptsCorrectCredentialAndRejectsWrongOnes() {
        Map<String, Object> stored = type.normalizeConfigForSave(submitted("alice", "s3cret"), null);
        List<Map<String, Object>> creds = BasicAuthProviderType.credentialList(stored);

        assertEquals("alice", BasicAuthProviderType.verify(basicHeader("alice", "s3cret"), creds));
        assertNull(BasicAuthProviderType.verify(basicHeader("alice", "wrong"), creds));
        assertNull(BasicAuthProviderType.verify(basicHeader("mallory", "s3cret"), creds));
        assertNull(BasicAuthProviderType.verify(null, creds));
        assertNull(BasicAuthProviderType.verify("Bearer xyz", creds));
        assertNull(BasicAuthProviderType.verify("Basic not-base64!!", creds));
    }

    @Test
    void blankPasswordOnEditCarriesForwardExistingHash() {
        Map<String, Object> existing = type.normalizeConfigForSave(submitted("alice", "s3cret"), null);

        // Re-submit alice with a blank password: the prior hash must be preserved.
        Map<String, Object> reSubmitted = Map.of(BasicAuthProviderType.CREDENTIALS,
            List.of(Map.of(BasicAuthProviderType.USERNAME, "alice", BasicAuthProviderType.PASSWORD, "")));
        Map<String, Object> updated = type.normalizeConfigForSave(reSubmitted, existing);

        List<Map<String, Object>> creds = BasicAuthProviderType.credentialList(updated);
        assertEquals(1, creds.size());
        assertEquals(BasicAuthProviderType.credentialList(existing).get(0).get(BasicAuthProviderType.PASSWORD_HASH),
            creds.get(0).get(BasicAuthProviderType.PASSWORD_HASH));
        assertEquals("alice", BasicAuthProviderType.verify(basicHeader("alice", "s3cret"), creds));
    }

    @Test
    void blankPasswordWithoutExistingHashIsDropped() {
        Map<String, Object> reSubmitted = Map.of(BasicAuthProviderType.CREDENTIALS,
            List.of(Map.of(BasicAuthProviderType.USERNAME, "ghost", BasicAuthProviderType.PASSWORD, "")));
        Map<String, Object> stored = type.normalizeConfigForSave(reSubmitted, null);

        assertFalse(BasicAuthProviderType.credentialList(stored).stream()
            .anyMatch(c -> "ghost".equals(c.get(BasicAuthProviderType.USERNAME))));
    }

    @Test
    void requiredPermissionIsInert() {
        assertFalse(type.usesRequiredPermission());
    }
}
