package be.elevenways.hohenheim.server.auth;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * THE Basic header parser: the scheme is case-insensitive (RFC 7617), a malformed header is
 * no credential, and the dyndns2 lane's colon-less token reads through the same parser.
 */
class BasicCredentialsParseTest {

    private static String encoded(String userPass) {
        return Base64.getEncoder().encodeToString(userPass.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void theSchemeIsCaseInsensitiveAndTheBareUserLaneIsOptIn() {
        // 1. The canonical spelling parses into its two halves.
        BasicCredentials.Presented canonical = BasicCredentials.parse("Basic " + encoded("alice:s3cret"));
        assertThat(canonical).as("step 1: a canonical header parses").isNotNull();
        assertThat(canonical.username()).as("step 1: the username").isEqualTo("alice");
        assertThat(canonical.password()).as("step 1: the password, colons after the first kept")
            .isEqualTo("s3cret");

        // 2. Any case of the scheme name is the same scheme; it used to be refused.
        for (String scheme : new String[] {"basic ", "BASIC ", "bAsIc "}) {
            assertThat(BasicCredentials.parse(scheme + encoded("alice:s3cret")))
                .as("step 2: scheme '" + scheme.trim() + "' is Basic")
                .isEqualTo(canonical);
        }

        // 3. Not Basic, not base64, or no colon: no credential at all.
        assertThat(BasicCredentials.parse("Bearer " + encoded("alice:s3cret")))
            .as("step 3: another scheme is no credential").isNull();
        assertThat(BasicCredentials.parse("Basic !!!not-base64"))
            .as("step 3: malformed base64 is no credential").isNull();
        assertThat(BasicCredentials.parse("Basic " + encoded("token-only")))
            .as("step 3: a colon-less value is no credential on the strict lane").isNull();
        assertThat(BasicCredentials.parse(null)).as("step 3: no header").isNull();

        // 4. The dyndns2 lane opts into the colon-less form: the whole value is the username.
        BasicCredentials.Presented bare = BasicCredentials.parseAllowingBareUser(
            "basic " + encoded("token-only"));
        assertThat(bare).as("step 4: a bare token parses on the lenient lane").isNotNull();
        assertThat(bare.username()).as("step 4: as the username").isEqualTo("token-only");
        assertThat(bare.password()).as("step 4: with an empty password").isEmpty();
    }
}
