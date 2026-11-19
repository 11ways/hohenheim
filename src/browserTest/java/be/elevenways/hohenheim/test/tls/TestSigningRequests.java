package be.elevenways.hohenheim.test.tls;

import org.bouncycastle.asn1.pkcs.PKCSObjectIdentifiers;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.ExtensionsGenerator;
import org.bouncycastle.asn1.x509.GeneralName;
import org.bouncycastle.asn1.x509.GeneralNames;
import org.bouncycastle.asn1.x509.KeyUsage;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.bouncycastle.pkcs.PKCS10CertificationRequest;
import org.bouncycastle.pkcs.jcajce.JcaPKCS10CertificationRequestBuilder;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.spec.ECGenParameterSpec;
import java.util.Base64;
import java.util.List;

/**
 * Real PKCS#10 signing requests for the tests of an order from a requester's own key: the key stays here, only the
 * request travels, exactly as a mail server would make one.
 *
 * @author Jelle De Loecker
 * @since  0.10.0
 */
public final class TestSigningRequests {

    private TestSigningRequests() {
    }

    /** What a request may carry beyond its names, for the refusal cases. */
    public enum Extra { NONE, KEY_USAGE, ORGANISATION }

    /** @return a fresh RSA key pair of {@code bits} */
    public static @NonNull KeyPair rsa(int bits) throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(bits);
        return generator.generateKeyPair();
    }

    /** @return a fresh EC key pair on the named curve ({@code secp256r1}, {@code secp256k1}, ...) */
    public static @NonNull KeyPair ec(@NonNull String curve) throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec(curve));
        return generator.generateKeyPair();
    }

    /** @return a PEM request for {@code names}, the first as its common name, signed by {@code key} */
    public static @NonNull String pem(@NonNull KeyPair key, @NonNull List<String> names) throws Exception {
        return pem(der(key, names, Extra.NONE));
    }

    /** @return the DER of a request for {@code names} with {@code extra}, signed by its own key */
    public static byte @NonNull [] der(@NonNull KeyPair key, @NonNull List<String> names, @NonNull Extra extra)
            throws Exception {
        X500Name subject = new X500Name((extra == Extra.ORGANISATION ? "O=Elevenways," : "") + "CN=" + names.get(0));
        JcaPKCS10CertificationRequestBuilder builder = new JcaPKCS10CertificationRequestBuilder(subject,
            key.getPublic());
        ExtensionsGenerator extensions = new ExtensionsGenerator();
        GeneralName[] alternatives = names.stream().map(name -> new GeneralName(GeneralName.dNSName, name))
            .toArray(GeneralName[]::new);
        extensions.addExtension(Extension.subjectAlternativeName, false, new GeneralNames(alternatives));
        if (extra == Extra.KEY_USAGE) {
            extensions.addExtension(Extension.keyUsage, true, new KeyUsage(KeyUsage.digitalSignature));
        }
        builder.addAttribute(PKCSObjectIdentifiers.pkcs_9_at_extensionRequest, extensions.generate());
        String algorithm = "RSA".equals(key.getPublic().getAlgorithm()) ? "SHA256withRSA" : "SHA256withECDSA";
        PKCS10CertificationRequest request = builder.build(new JcaContentSignerBuilder(algorithm)
            .build(key.getPrivate()));
        return request.getEncoded();
    }

    /** @return the DER as one CERTIFICATE REQUEST block */
    public static @NonNull String pem(byte @NonNull [] der) {
        return "-----BEGIN CERTIFICATE REQUEST-----\n"
            + Base64.getMimeEncoder(64, new byte[] {'\n'}).encodeToString(der)
            + "\n-----END CERTIFICATE REQUEST-----\n";
    }
}
