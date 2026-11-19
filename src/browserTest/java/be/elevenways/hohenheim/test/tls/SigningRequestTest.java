package be.elevenways.hohenheim.test.tls;

import be.elevenways.hohenheim.server.tls.SigningRequest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A requester's signing request is judged before any order: it must be signed by the key it certifies, name exactly
 * the requested hostnames, carry a key the CA issues for and ask for nothing beyond its names.
 *
 * @author Jelle De Loecker
 * @since  0.10.0
 */
class SigningRequestTest {

    private static final List<String> NAMES = List.of("mx.csr.test", "mail.csr.test");

    private static KeyPair rsa2048;

    @BeforeAll
    static void keys() throws Exception {
        rsa2048 = TestSigningRequests.rsa(2048);
    }

    @Test
    void aRequestSignedByItsKeyForExactlyTheNamesReadsAndPinsThatKey() throws Exception {
        // 1. The names may come in any order and case: the set is what is compared.
        SigningRequest read = SigningRequest.read(TestSigningRequests.pem(rsa2048, NAMES),
            List.of("MAIL.csr.test", "mx.csr.test"));
        assertThat(read.names()).as("step 1: the request's names").containsExactly("mx.csr.test", "mail.csr.test");

        // 2. The pin is the SubjectPublicKeyInfo's SHA-256 of THE requester's key, what DANE 3 1 1 publishes.
        assertThat(read.publicKeyPin()).as("step 2: the pin is the requester's key")
            .isEqualTo(SigningRequest.publicKeyPin(rsa2048.getPublic()));

        // 3. What is stored is one clean block, read again to the same request.
        assertThat(read.pem()).as("step 3: one CERTIFICATE REQUEST block").startsWith("-----BEGIN CERTIFICATE REQUEST")
            .endsWith("-----END CERTIFICATE REQUEST-----\n");
        assertThat(SigningRequest.read(read.pem(), NAMES).der()).as("step 3: stored and read again, unchanged")
            .isEqualTo(read.der());

        // 4. ECDSA P-256 is the other accepted key.
        assertThat(SigningRequest.read(TestSigningRequests.pem(TestSigningRequests.ec("secp256r1"), NAMES), NAMES)
                .names()).as("step 4: a P-256 request reads").hasSize(2);
    }

    @Test
    void aRequestBreakingARuleIsRefusedByName() throws Exception {
        // 1. Not signed by the key it carries: a flipped signature bit is no proof of possession.
        byte[] tampered = TestSigningRequests.der(rsa2048, NAMES, TestSigningRequests.Extra.NONE);
        tampered[tampered.length - 1] ^= 0x01;
        assertRefused(TestSigningRequests.pem(tampered), NAMES, SigningRequest.Refusal.BAD_SIGNATURE, "step 1");

        // 2. A name more, a name less or another name: never exactly the requested set.
        assertRefused(TestSigningRequests.pem(rsa2048, NAMES), List.of("mx.csr.test"),
            SigningRequest.Refusal.NAMES_DIFFER, "step 2: a name more");
        assertRefused(TestSigningRequests.pem(rsa2048, List.of("mx.csr.test")), NAMES,
            SigningRequest.Refusal.NAMES_DIFFER, "step 2: a name less");

        // 3. Keys: RSA below 2048 is weak; a curve the CA refuses is unsupported.
        assertRefused(TestSigningRequests.pem(TestSigningRequests.rsa(1024), NAMES), NAMES,
            SigningRequest.Refusal.WEAK_KEY, "step 3: RSA 1024");
        assertRefused(TestSigningRequests.pem(TestSigningRequests.ec("secp521r1"), NAMES), NAMES,
            SigningRequest.Refusal.UNSUPPORTED_KEY, "step 3: P-521");

        // 4. Anything Hohenheim would not honour is refused instead of silently dropped.
        assertRefused(TestSigningRequests.pem(TestSigningRequests.der(rsa2048, NAMES,
            TestSigningRequests.Extra.KEY_USAGE)), NAMES, SigningRequest.Refusal.UNSUPPORTED_CONTENT,
            "step 4: a key usage extension");
        assertRefused(TestSigningRequests.pem(TestSigningRequests.der(rsa2048, NAMES,
            TestSigningRequests.Extra.ORGANISATION)), NAMES, SigningRequest.Refusal.UNSUPPORTED_CONTENT,
            "step 4: a subject organisation");

        // 5. Not a request at all.
        assertRefused("not a signing request", NAMES, SigningRequest.Refusal.UNREADABLE, "step 5: text");
        assertRefused(null, NAMES, SigningRequest.Refusal.UNREADABLE, "step 5: nothing");
    }

    private static void assertRefused(String pem, List<String> hostnames, SigningRequest.Refusal refusal,
                                      String step) {
        assertThatThrownBy(() -> SigningRequest.read(pem, hostnames)).as(step)
            .isInstanceOf(SigningRequest.Refused.class)
            .extracting(refused -> ((SigningRequest.Refused) refused).refusal())
            .isEqualTo(refusal);
    }
}
