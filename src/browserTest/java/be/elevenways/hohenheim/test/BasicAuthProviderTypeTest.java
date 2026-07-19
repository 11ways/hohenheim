package be.elevenways.hohenheim.test;

import be.elevenways.hohenheim.server.auth.types.BasicAuthProviderType;
import be.elevenways.zenit.auth.server.PasswordHasher;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure-logic tests for the Basic auth provider: editable storage over the
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
    void savedConfigKeepsPasswordEditable() {
        Map<String, Object> stored = type.normalizeConfigForSave(submitted("alice", "s3cret"), null);

        Map<String, String> creds = BasicAuthProviderType.credentials(stored);
        assertEquals(1, creds.size());
        assertEquals("s3cret", creds.get("alice"));
    }

    @Test
    void verifyAcceptsCorrectCredentialAndRejectsWrongOnes() {
        Map<String, Object> stored = type.normalizeConfigForSave(submitted("alice", "s3cret"), null);
        Map<String, String> creds = BasicAuthProviderType.credentials(stored);

        assertEquals("alice", BasicAuthProviderType.verify(basicHeader("alice", "s3cret"), creds));
        assertNull(BasicAuthProviderType.verify(basicHeader("alice", "wrong"), creds));
        assertNull(BasicAuthProviderType.verify(basicHeader("mallory", "s3cret"), creds));
        assertNull(BasicAuthProviderType.verify(null, creds));
        assertNull(BasicAuthProviderType.verify("Bearer xyz", creds));
        assertNull(BasicAuthProviderType.verify("Basic not-base64!!", creds));
    }

    @Test
    void blankPasswordIsAVisibleEditableCredential() {
        Map<String, Object> existing = type.normalizeConfigForSave(submitted("alice", "s3cret"), null);
        Map<String, Object> updated = type.normalizeConfigForSave(submitted("alice", ""), existing);

        Map<String, String> creds = BasicAuthProviderType.credentials(updated);
        assertEquals(1, creds.size());
        assertEquals("", creds.get("alice"));
        assertEquals("alice", BasicAuthProviderType.verify(basicHeader("alice", ""), creds));
        assertNull(BasicAuthProviderType.verify(basicHeader("alice", "s3cret"), creds));
    }

    @Test
    void legacyArgon2CredentialRemainsVerifiable() {
        String hash = PasswordHasher.hash("s3cret");
        Map<String, Object> stored = Map.of(BasicAuthProviderType.CREDENTIALS, Map.of("alice", hash));
        assertEquals("alice", BasicAuthProviderType.verify(basicHeader("alice", "s3cret"),
            BasicAuthProviderType.credentials(stored)));
        assertNull(BasicAuthProviderType.verify(basicHeader("alice", "wrong"),
            BasicAuthProviderType.credentials(stored)));
    }

    @Test
    void legacyListShapeIsStillReadable() {
        Map<String, Object> legacy = Map.of(BasicAuthProviderType.CREDENTIALS, List.of(
            Map.of(BasicAuthProviderType.USERNAME, "old", BasicAuthProviderType.PASSWORD_HASH, "$argon2fake")));
        assertEquals("$argon2fake", BasicAuthProviderType.credentials(legacy).get("old"));
    }

    @Test
    void requiredPermissionIsInert() {
        assertFalse(type.usesRequiredPermission());
    }
}
