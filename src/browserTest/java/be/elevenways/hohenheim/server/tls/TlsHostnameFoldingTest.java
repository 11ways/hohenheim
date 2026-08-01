package be.elevenways.hohenheim.server.tls;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Hostname case folding in the certificate lane must be ASCII-only.
 *
 * The JVM default locale is forced to Turkish for the whole journey: under it the
 * no-argument String.toLowerCase() maps 'I' to dotless 'ı', so every hostname gate
 * built on it silently stops matching. Nothing here needs a booted runtime.
 */
class TlsHostnameFoldingTest {

    private static final Locale TURKISH = Locale.forLanguageTag("tr-TR");

    private Locale previous;

    @BeforeEach
    void forceTurkishLocale() {
        this.previous = Locale.getDefault();
        Locale.setDefault(TURKISH);
    }

    @AfterEach
    void restoreLocale() {
        Locale.setDefault(this.previous);
    }

    @Test
    void certificateHostnamesFoldTheSameWayInEveryLocale() {
        // 1. A perfectly valid mixed-case hostname must still pass the ACME gate.
        assertThat(AcmeService.isValidHostname("WIKI.example.test"))
            .as("mixed-case hostname accepted for issuance")
            .isTrue();
        assertThat(AcmeService.isValidHostname("wiki.example.test"))
            .as("lowercase hostname accepted for issuance")
            .isTrue();

        // 2. The HTTP-01 challenge gate: the validation request arrives with whatever
        //    case the peer used, the authorized set holds the ordered hostname.
        AcmeService acme = new AcmeService(new CertificateStore());
        acme.offerHttpChallenge("tok3n", "authz-value", Set.of("wiki.example.test"));

        assertThat(acme.getChallengeResponse("tok3n", "wiki.example.test"))
            .as("challenge answered for the exact hostname")
            .isEqualTo("authz-value");
        assertThat(acme.getChallengeResponse("tok3n", "WIKI.example.test"))
            .as("challenge answered for the same hostname in another case")
            .isEqualTo("authz-value");
        assertThat(acme.getChallengeResponse("tok3n", "other.example.test"))
            .as("challenge withheld from an unauthorized hostname")
            .isNull();

        // 3. SNI selection: certificate SANs are lowercase, the ClientHello name is not.
        Map<String, String> aliases = Map.of(
            "wiki.example.test", "cert-7",
            "*.apps.example.test", "cert-9");

        assertThat(CertificateStore.resolveFromMap("wiki.example.test", aliases))
            .as("exact SNI name resolves its certificate")
            .isEqualTo("cert-7");
        assertThat(CertificateStore.resolveFromMap("WIKI.example.test", aliases))
            .as("mixed-case SNI name resolves the same certificate")
            .isEqualTo("cert-7");
        assertThat(CertificateStore.resolveFromMap("BILLING.apps.example.test", aliases))
            .as("mixed-case SNI name resolves through the wildcard certificate")
            .isEqualTo("cert-9");
        assertThat(CertificateStore.resolveFromMap("wiki.other.test", aliases))
            .as("an unrelated name resolves nothing")
            .isNull();
    }
}
