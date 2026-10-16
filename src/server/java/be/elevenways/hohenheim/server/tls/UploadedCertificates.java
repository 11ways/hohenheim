package be.elevenways.hohenheim.server.tls;

import be.elevenways.hohenheim.HohenheimMicrocopy;
import be.elevenways.protoblast.common.time.Now;
import be.elevenways.zenit.common.validation.Violations;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.nio.charset.StandardCharsets;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * THE judge of an uploaded certificate: its PEM chain and private key are refused, on the field at fault and in
 * words, unless both parse, the key belongs to the first certificate, each certificate is issued by the next one,
 * every one is valid now and, when an address is named, the first one covers it.
 *
 * AIDEV-NOTE: the certificate form (CertificateParts) and the put-online HTTPS step both judge through here, so a
 * certificate the wizard accepts is one the certificate form would accept too. The key is only parsed here; storing it
 * is the certificate row's encrypted secret column's job.
 *
 * @author Jelle De Loecker
 * @since  0.9.0
 */
public final class UploadedCertificates {

    /** The form field the certificate chain is given in, on the certificate form and in the wizard alike. */
    public static final String CERTIFICATE_FIELD = "certificate_pem";

    /** The form field the private key is given in. */
    public static final String KEY_FIELD = "private_key_pem";

    /** An accepted upload: its chain, server certificate first, and the private key of that first certificate. */
    public record Accepted(@NonNull List<X509Certificate> chain, @NonNull PrivateKey key) {

        /** @return the server certificate, the one the key belongs to */
        public @NonNull X509Certificate leaf() {
            return this.chain.getFirst();
        }
    }

    private UploadedCertificates() {
    }

    /**
     * @param hostname the address the certificate must cover, or null when it serves whatever names it carries
     * @throws Violations on the certificate or key field, naming what is wrong
     */
    public static @NonNull Accepted check(@Nullable String certificatePem, @Nullable String keyPem,
                                          @Nullable String hostname) {
        List<X509Certificate> chain = chainOf(certificatePem);
        PrivateKey key = keyOf(keyPem);
        if (!belongsTo(key, chain.getFirst().getPublicKey())) {
            throw Violations.ofField(KEY_FIELD, null, HohenheimMicrocopy.VIOLATIONS.of("cert_key_mismatch"));
        }
        for (int i = 0; i + 1 < chain.size(); i++) {
            if (!issuedBy(chain.get(i), chain.get(i + 1))) {
                throw Violations.ofField(CERTIFICATE_FIELD, null, HohenheimMicrocopy.VIOLATIONS.of("cert_chain_order")
                    .withArg("position", String.valueOf(i + 1)).withArg("next", String.valueOf(i + 2)));
            }
        }
        Instant now = Now.instant();
        for (int i = 0; i < chain.size(); i++) {
            X509Certificate certificate = chain.get(i);
            if (certificate.getNotAfter().toInstant().isBefore(now)) {
                throw Violations.ofField(CERTIFICATE_FIELD, null, HohenheimMicrocopy.VIOLATIONS.of("cert_expired")
                    .withArg("position", String.valueOf(i + 1))
                    .withArg("date", certificate.getNotAfter().toInstant().toString()));
            }
            if (certificate.getNotBefore().toInstant().isAfter(now)) {
                throw Violations.ofField(CERTIFICATE_FIELD, null,
                    HohenheimMicrocopy.VIOLATIONS.of("cert_not_yet_valid")
                        .withArg("position", String.valueOf(i + 1))
                        .withArg("date", certificate.getNotBefore().toInstant().toString()));
            }
        }
        if (hostname != null) {
            List<String> names = new ArrayList<>(CertificateStore.hostnamesOf(chain.getFirst()));
            if (!CertificateCoverage.covers(names, hostname)) {
                throw Violations.ofField(CERTIFICATE_FIELD, null, HohenheimMicrocopy.VIOLATIONS
                    .of("cert_not_covering").withArg("hostname", hostname)
                    .withArg("names", names.isEmpty() ? "-" : String.join(", ", names)));
            }
        }
        return new Accepted(chain, key);
    }

    /**
     * @return the first certificate of a PEM, judged only as parseable: what a stored certificate is re-read with on an
     *         edit that does not replace it (an expired Let's Encrypt row must stay editable)
     * @throws Violations on the certificate field when it does not parse
     */
    public static @NonNull X509Certificate leafOf(@Nullable String certificatePem) {
        return chainOf(certificatePem).getFirst();
    }

    /** @return every certificate of the PEM's CERTIFICATE blocks, in the order given ({@link CertificatePem}) */
    private static @NonNull List<X509Certificate> chainOf(@Nullable String pem) {
        if (pem == null || pem.isBlank()) {
            throw Violations.ofField(CERTIFICATE_FIELD, null, HohenheimMicrocopy.VIOLATIONS.of("cert_pem_required"));
        }
        List<X509Certificate> chain;
        try {
            chain = CertificatePem.chainOf(pem);
        } catch (Exception unreadable) {
            // The parser's own words name its internals, never what to paste: the refusal says that instead.
            throw Violations.ofField(CERTIFICATE_FIELD, null, HohenheimMicrocopy.VIOLATIONS.of("cert_pem_invalid"));
        }
        if (chain.isEmpty()) {
            throw Violations.ofField(CERTIFICATE_FIELD, null, HohenheimMicrocopy.VIOLATIONS.of("cert_pem_invalid"));
        }
        return chain;
    }

    /** @return the private key in the PEM, whether written as a bare key pair or as PKCS#8 */
    private static @NonNull PrivateKey keyOf(@Nullable String pem) {
        if (pem == null || pem.isBlank()) {
            throw Violations.ofField(KEY_FIELD, null, HohenheimMicrocopy.VIOLATIONS.of("key_pem_required"));
        }
        try {
            return CertificatePem.privateKeyOf(pem);
        } catch (Exception unreadable) {
            // Never quote the PEM: a certificate pasted into the key field must not be echoed, nor a key anywhere.
            throw Violations.ofField(KEY_FIELD, null, HohenheimMicrocopy.VIOLATIONS.of("key_pem_invalid"));
        }
    }

    /** Whether the private key signs what the certificate's public key verifies. */
    private static boolean belongsTo(@NonNull PrivateKey key, @NonNull PublicKey publicKey) {
        if (!key.getAlgorithm().equals(publicKey.getAlgorithm())) {
            return false;
        }
        String algorithm = switch (key.getAlgorithm()) {
            case "RSA" -> "SHA256withRSA";
            case "EC" -> "SHA256withECDSA";
            case "Ed25519", "EdDSA" -> "Ed25519";
            default -> null;
        };
        if (algorithm == null) {
            return false;
        }
        try {
            byte[] probe = "hohenheim-key-probe".getBytes(StandardCharsets.UTF_8);
            Signature signer = Signature.getInstance(algorithm);
            signer.initSign(key);
            signer.update(probe);
            byte[] signed = signer.sign();
            Signature verifier = Signature.getInstance(algorithm);
            verifier.initVerify(publicKey);
            verifier.update(probe);
            return verifier.verify(signed);
        } catch (Exception mismatch) {
            return false;
        }
    }

    /** Whether {@code issuer} signed {@code certificate} under its own name. */
    private static boolean issuedBy(@NonNull X509Certificate certificate, @NonNull X509Certificate issuer) {
        if (!certificate.getIssuerX500Principal().equals(issuer.getSubjectX500Principal())) {
            return false;
        }
        try {
            certificate.verify(issuer.getPublicKey());
            return true;
        } catch (Exception notSigned) {
            return false;
        }
    }
}
