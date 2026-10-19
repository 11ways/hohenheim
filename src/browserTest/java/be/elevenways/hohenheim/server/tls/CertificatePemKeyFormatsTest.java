package be.elevenways.hohenheim.server.tls;

import org.bouncycastle.asn1.pkcs.PrivateKeyInfo;
import org.bouncycastle.asn1.sec.ECPrivateKey;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.openssl.jcajce.JcaMiscPEMGenerator;
import org.bouncycastle.openssl.jcajce.JcaPEMWriter;
import org.bouncycastle.openssl.jcajce.JcaPKCS8Generator;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.bouncycastle.util.io.pem.PemObject;
import org.bouncycastle.util.io.pem.PemObjectGenerator;
import org.junit.jupiter.api.Test;

import javax.crypto.Cipher;
import javax.crypto.EncryptedPrivateKeyInfo;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.PBEParameterSpec;
import java.io.IOException;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pins the key formats {@link CertificatePem#privateKeyOf}, the one PEM key reader, accepts and refuses.
 *
 * AIDEV-NOTE: IncusTls (PKCS#8 only, EC/RSA/Ed25519 probed with a bare KeyFactory) and GithubProviderClient (RSA,
 * PKCS#1 wrapped into PKCS#8 by hand) read keys themselves before 2026-10-19. Every format either read is still read
 * here; PKCS#1 and SEC1 client identities, which IncusTls refused, now load too.
 *
 * @author Jelle De Loecker
 * @since  0.10.0
 */
class CertificatePemKeyFormatsTest {

    @Test
    void everyUnencryptedKeyFormatEitherFormerReaderTookIsReadJourney() throws Exception {
        KeyPair rsa = pair("RSA", null);
        KeyPair ec = pair("EC", new ECGenParameterSpec("secp256r1"));
        KeyPair ed = pair("Ed25519", null);

        // 1. PKCS#8 (BEGIN PRIVATE KEY), the only shape the Incus lane read: RSA, EC and Ed25519 alike.
        for (KeyPair keys : List.of(rsa, ec, ed)) {
            String pem = pem(new JcaPKCS8Generator(keys.getPrivate(), null));
            assertThat(pem).as("step 1: PKCS#8 text").startsWith("-----BEGIN PRIVATE KEY-----");
            assertSameKey(CertificatePem.privateKeyOf(pem), keys, "step 1: PKCS#8 " + algorithm(keys));
        }

        // 2. PKCS#1 (BEGIN RSA PRIVATE KEY), the GitHub App download format.
        String pkcs1 = pem(new JcaMiscPEMGenerator(rsa.getPrivate()));
        assertThat(pkcs1).as("step 2: PKCS#1 text").startsWith("-----BEGIN RSA PRIVATE KEY-----");
        assertSameKey(CertificatePem.privateKeyOf(pkcs1), rsa, "step 2: PKCS#1 RSA");

        // 3. SEC1 (BEGIN EC PRIVATE KEY) as openssl writes it (curve and public key included), which the Incus lane
        //    refused.
        String sec1 = pem(sec1(ec));
        assertThat(sec1).as("step 3: SEC1 text").startsWith("-----BEGIN EC PRIVATE KEY-----");
        assertSameKey(CertificatePem.privateKeyOf(sec1), ec, "step 3: SEC1 EC");

        // 4. A bundle of certificate and key yields the key; the certificate block is skipped, never misread.
        String bundle = pem(new JcaMiscPEMGenerator(selfSigned(ec)))
            + pem(new JcaPKCS8Generator(ec.getPrivate(), null));
        assertSameKey(CertificatePem.privateKeyOf(bundle), ec, "step 4: the bundle's key");
    }

    @Test
    void anEncryptedKeyOrATextWithoutOneIsNoKey() throws Exception {
        KeyPair rsa = pair("RSA", null);

        // 1. A passphrase-protected PKCS#8 key (BEGIN ENCRYPTED PRIVATE KEY) is refused as no key: nothing here holds a
        //    passphrase.
        String encrypted = pem(encryptedPkcs8(rsa, "secret"));
        assertThat(encrypted).as("step 1: an encrypted text").startsWith("-----BEGIN ENCRYPTED PRIVATE KEY-----");
        assertThatThrownBy(() -> CertificatePem.privateKeyOf(encrypted))
            .as("step 1: an encrypted key is refused").isInstanceOf(IllegalArgumentException.class);

        // 2. A text holding only a certificate is no key either.
        String certificateOnly = pem(new JcaMiscPEMGenerator(selfSigned(rsa)));
        assertThatThrownBy(() -> CertificatePem.privateKeyOf(certificateOnly))
            .as("step 2: a certificate is not a key").isInstanceOf(IllegalArgumentException.class);
    }

    /** The key as {@code openssl pkcs8 -topk8} writes it with a passphrase, sealed by the JDK alone. */
    private static PemObject encryptedPkcs8(KeyPair keys, String passphrase) throws Exception {
        String algorithm = "PBEWithSHA1AndDESede";
        SecretKey key = SecretKeyFactory.getInstance(algorithm)
            .generateSecret(new PBEKeySpec(passphrase.toCharArray()));
        Cipher cipher = Cipher.getInstance(algorithm);
        cipher.init(Cipher.ENCRYPT_MODE, key, new PBEParameterSpec(new byte[16], 10_000));
        byte[] sealed = cipher.doFinal(keys.getPrivate().getEncoded());
        return new PemObject("ENCRYPTED PRIVATE KEY",
            new EncryptedPrivateKeyInfo(cipher.getParameters(), sealed).getEncoded());
    }

    private static KeyPair pair(String algorithm, ECGenParameterSpec curve) throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance(algorithm);
        if (curve != null) {
            generator.initialize(curve);
        } else if ("RSA".equals(algorithm)) {
            generator.initialize(2048);
        }
        return generator.generateKeyPair();
    }

    private static String algorithm(KeyPair keys) {
        return keys.getPrivate().getAlgorithm();
    }

    /** The read key signs what the original public key verifies: the same key, whatever its encoding. */
    private static void assertSameKey(PrivateKey read, KeyPair expected, String step) throws Exception {
        String algorithm = switch (algorithm(expected)) {
            case "RSA" -> "SHA256withRSA";
            case "EC" -> "SHA256withECDSA";
            default -> algorithm(expected);
        };
        byte[] probe = step.getBytes(StandardCharsets.UTF_8);
        Signature signer = Signature.getInstance(algorithm);
        signer.initSign(read);
        signer.update(probe);
        byte[] signed = signer.sign();
        Signature verifier = Signature.getInstance(algorithm);
        verifier.initVerify(expected.getPublic());
        verifier.update(probe);
        assertThat(verifier.verify(signed)).as(step + ": the same key").isTrue();
    }

    /** An ECPrivateKey structure with its named curve and public key, the shape {@code openssl ec} writes. */
    private static PemObject sec1(KeyPair ec) {
        SubjectPublicKeyInfo publicKey = SubjectPublicKeyInfo.getInstance(ec.getPublic().getEncoded());
        try {
            BigInteger scalar = ECPrivateKey.getInstance(
                PrivateKeyInfo.getInstance(ec.getPrivate().getEncoded()).parsePrivateKey()).getKey();
            ECPrivateKey key = new ECPrivateKey(256, scalar, publicKey.getPublicKeyData(),
                publicKey.getAlgorithm().getParameters());
            return new PemObject("EC PRIVATE KEY", key.getEncoded());
        } catch (IOException unencodable) {
            throw new UncheckedIOException(unencodable);
        }
    }

    private static String pem(PemObjectGenerator generator) throws Exception {
        StringWriter text = new StringWriter();
        try (JcaPEMWriter writer = new JcaPEMWriter(text)) {
            writer.writeObject(generator);
        }
        return text.toString();
    }

    private static X509Certificate selfSigned(KeyPair keys) throws Exception {
        X500Name name = new X500Name("CN=pem-formats.test");
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        String signature = "RSA".equals(algorithm(keys)) ? "SHA256withRSA" : "SHA256withECDSA";
        return new JcaX509CertificateConverter().getCertificate(new JcaX509v3CertificateBuilder(name, BigInteger.ONE,
                Date.from(now), Date.from(now.plus(Duration.ofDays(1))), name, keys.getPublic())
            .build(new JcaContentSignerBuilder(signature).build(keys.getPrivate())));
    }
}
