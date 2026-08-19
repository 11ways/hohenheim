package be.elevenways.hohenheim.server.proxy;

import be.elevenways.hohenheim.server.auth.BasicCredentials;
import be.elevenways.zenit.auth.server.PasswordHasher;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Access-list basic auth verifies ONLY argon2 hashes: the legacy plaintext-compare
 * fallback silently accepted an unhashed column and is gone.
 */
class BasicAuthPasswordVerificationTest {

    @Test
    void onlyArgon2HashesVerifyAndPlaintextStoredValuesAreRefused() {
        // 1. The correct posture: an argon2 hash verifies its password.
        String hash = PasswordHasher.hash("s3cret");
        assertThat(BasicCredentials.verifyPassword("s3cret", hash, "site"))
            .as("step 1: an argon2 hash verifies the matching password").isTrue();
        assertThat(BasicCredentials.verifyPassword("wrong", hash, "site"))
            .as("step 1: an argon2 hash refuses a wrong password").isFalse();

        // 2. THE fix: a plaintext stored value is refused even when the presented
        //    password matches it exactly. The old code compared them and let it through.
        assertThat(BasicCredentials.verifyPassword("s3cret", "s3cret", "site"))
            .as("step 2: a non-argon2 stored value is refused, never plaintext-compared")
            .isFalse();

        // 3. Absent credentials fail closed.
        assertThat(BasicCredentials.verifyPassword("anything", null, "site"))
            .as("step 3: a null stored password never matches").isFalse();
    }
}
