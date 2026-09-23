package be.elevenways.hohenheim.test;

import be.elevenways.hohenheim.server.auth.BasicCredentials;
import be.elevenways.hohenheim.server.auth.types.BasicAuthProviderType;
import be.elevenways.zenit.auth.server.PasswordHasher;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure-logic tests for the Basic auth provider: hashed storage over the username -> password
 * map shape and header verification. The full gate (session establishment, 401 challenge,
 * session binding) is exercised by ProxyAuthGateTest.
 *
 * @author Jelle De Loecker <jelle@elevenways.be>
 * @since 0.1.0
 */
public class BasicAuthProviderTypeTest {

    private final BasicAuthProviderType type = new BasicAuthProviderType();

    private static String basicHeader(String user, String pass) {
        String raw = user + ":" + pass;
        return "Basic " + Base64.getEncoder().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    private static Map<String, Object> submitted(Map<String, String> credentials) {
        return Map.of(BasicAuthProviderType.CREDENTIALS, credentials);
    }

    @Test
    void passwordsAreHashedOnSaveAndPlaintextNeverVerifies() {
        // 1. A typed password is stored as an argon2 hash, never as itself.
        Map<String, Object> stored = type.normalizeConfigForSave(submitted(Map.of("alice", "s3cret")), null);
        Map<String, String> creds = BasicAuthProviderType.credentials(stored);
        assertThat(creds).as("step 1: one credential stored").hasSize(1);
        assertThat(BasicCredentials.isHashed(creds.get("alice"))).as("step 1: stored as an argon2 hash").isTrue();
        assertThat(creds.get("alice")).as("step 1: the plaintext is not in the config").doesNotContain("s3cret");

        // 2. The hash verifies the right password and nothing else; malformed headers are refused.
        assertThat(BasicAuthProviderType.verify(basicHeader("alice", "s3cret"), creds))
            .as("step 2: the right password verifies").isEqualTo("alice");
        assertThat(BasicAuthProviderType.verify(basicHeader("alice", "wrong"), creds))
            .as("step 2: a wrong password does not").isNull();
        assertThat(BasicAuthProviderType.verify(basicHeader("mallory", "s3cret"), creds))
            .as("step 2: an unknown user does not").isNull();
        assertThat(BasicAuthProviderType.verify(null, creds)).as("step 2: no header").isNull();
        assertThat(BasicAuthProviderType.verify("Bearer xyz", creds)).as("step 2: another scheme").isNull();
        assertThat(BasicAuthProviderType.verify("Basic not-base64!!", creds)).as("step 2: garbage").isNull();

        // 3. Re-submitting the form (which shows the stored hash) keeps the hash as it is.
        Map<String, Object> resubmitted = type.normalizeConfigForSave(
            submitted(Map.of("alice", creds.get("alice"))), stored);
        assertThat(BasicAuthProviderType.credentials(resubmitted).get("alice"))
            .as("step 3: an unchanged hash is not hashed again").isEqualTo(creds.get("alice"));

        // 4. The plaintext fallback is gone: a value stored unhashed never verifies, even when
        //    the presented password equals it.
        Map<String, String> plaintext = Map.of("alice", "s3cret");
        assertThat(BasicAuthProviderType.verify(basicHeader("alice", "s3cret"), plaintext))
            .as("step 4: a plaintext stored value is refused, never compared").isNull();
    }

    @Test
    void blankSubmitKeepsTheStoredHashAndANewBlankNeverVerifies() {
        Map<String, Object> existing = type.normalizeConfigForSave(submitted(Map.of("alice", "s3cret")), null);
        String aliceHash = BasicAuthProviderType.credentials(existing).get("alice");

        // 1. Clearing alice's box keeps her password; a new user with no password is stored blank.
        Map<String, String> edit = new LinkedHashMap<>();
        edit.put("alice", "");
        edit.put("bob", " ");
        Map<String, String> creds = BasicAuthProviderType.credentials(
            type.normalizeConfigForSave(submitted(edit), existing));
        assertThat(creds.get("alice")).as("step 1: a blank submit keeps the stored hash").isEqualTo(aliceHash);
        assertThat(BasicAuthProviderType.verify(basicHeader("alice", "s3cret"), creds))
            .as("step 1: so alice's password still works").isEqualTo("alice");

        // 2. A blank password is never a working credential.
        assertThat(BasicAuthProviderType.verify(basicHeader("bob", ""), creds))
            .as("step 2: an empty password never verifies").isNull();
        assertThat(BasicAuthProviderType.verify(basicHeader("bob", " "), creds))
            .as("step 2: nor does the blank text itself").isNull();
    }

    @Test
    void legacyShapesRemainVerifiable() {
        String hash = PasswordHasher.hash("s3cret");
        Map<String, Object> stored = Map.of(BasicAuthProviderType.CREDENTIALS, Map.of("alice", hash));
        assertThat(BasicAuthProviderType.verify(basicHeader("alice", "s3cret"),
            BasicAuthProviderType.credentials(stored))).as("an argon2 map entry verifies").isEqualTo("alice");

        Map<String, Object> legacy = Map.of(BasicAuthProviderType.CREDENTIALS, List.of(
            Map.of(BasicAuthProviderType.USERNAME, "old", BasicAuthProviderType.PASSWORD_HASH, "$argon2fake")));
        assertThat(BasicAuthProviderType.credentials(legacy).get("old"))
            .as("the legacy list shape is still read").isEqualTo("$argon2fake");
    }

    @Test
    void requiredPermissionIsInert() {
        assertThat(type.usesRequiredPermission()).isFalse();
    }
}
