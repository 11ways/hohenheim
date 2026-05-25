package be.elevenways.hohenheim.test;

import be.elevenways.hohenheim.server.AuthHelper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit test for {@link AuthHelper} utilities that don't need the runtime.
 */
class AuthHelperTest {

    @Test
    void generatePasswordIsStrongUrlSafeAndUnique() {
        String a = AuthHelper.generatePassword();
        String b = AuthHelper.generatePassword();

        assertThat(a).isNotBlank().hasSizeGreaterThanOrEqualTo(20);
        assertThat(a).matches("[A-Za-z0-9_-]+");   // url-safe base64, no padding
        assertThat(a).isNotEqualTo(b);             // two draws differ (random)
    }
}
