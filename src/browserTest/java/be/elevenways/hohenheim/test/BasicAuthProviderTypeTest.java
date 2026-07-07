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
 * Pure-logic tests for the Basic auth provider: save-time hashing over the
 * username -> password map shape and header verification. The full gate
 * (session establishment, 401 challenge) is exercised by the proxy
 * integration tests.
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
        return Map.of(BasicAuthProviderType.CREDENTIALS, Map.of(user, pass));
    }

    @Test
    void savedConfigHashesPasswordAndNeverStoresPlaintext() {
        Map<String, Object> stored = type.normalizeConfigForSave(submitted("alice", "s3cret"), null);

        Map<String, String> creds = BasicAuthProviderType.credentialHashes(stored);
        assertEquals(1, creds.size());
        String hash = creds.get("alice");
        assertTrue(hash != null && hash.startsWith("$argon2"), "password must be Argon2-hashed");
    }

    @Test
    void verifyAcceptsCorrectCredentialAndRejectsWrongOnes() {
        Map<String, Object> stored = type.normalizeConfigForSave(submitted("alice", "s3cret"), null);
        Map<String, String> creds = BasicAuthProviderType.credentialHashes(stored);

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
        Map<String, Object> updated = type.normalizeConfigForSave(submitted("alice", ""), existing);

        Map<String, String> creds = BasicAuthProviderType.credentialHashes(updated);
        assertEquals(1, creds.size());
        assertEquals(BasicAuthProviderType.credentialHashes(existing).get("alice"), creds.get("alice"));
        assertEquals("alice", BasicAuthProviderType.verify(basicHeader("alice", "s3cret"), creds));
    }

    @Test
    void resubmittedStoredHashRoundTripsUnchanged() {
        Map<String, Object> existing = type.normalizeConfigForSave(submitted("alice", "s3cret"), null);
        String hash = BasicAuthProviderType.credentialHashes(existing).get("alice");

        // The KeyValue editor redisplays the stored hash; resubmitting it must not re-hash it.
        Map<String, Object> updated = type.normalizeConfigForSave(submitted("alice", hash), existing);

        assertEquals(hash, BasicAuthProviderType.credentialHashes(updated).get("alice"));
        assertEquals("alice", BasicAuthProviderType.verify(basicHeader("alice", "s3cret"),
            BasicAuthProviderType.credentialHashes(updated)));
    }

    @Test
    void blankPasswordWithoutExistingHashIsDropped() {
        Map<String, Object> stored = type.normalizeConfigForSave(submitted("ghost", ""), null);
        assertFalse(BasicAuthProviderType.credentialHashes(stored).containsKey("ghost"));
    }

    @Test
    void legacyListShapeIsStillReadable() {
        Map<String, Object> legacy = Map.of(BasicAuthProviderType.CREDENTIALS, List.of(
            Map.of(BasicAuthProviderType.USERNAME, "old", BasicAuthProviderType.PASSWORD_HASH, "$argon2fake")));
        assertEquals("$argon2fake", BasicAuthProviderType.credentialHashes(legacy).get("old"));
    }

    @Test
    void requiredPermissionIsInert() {
        assertFalse(type.usesRequiredPermission());
    }
}
