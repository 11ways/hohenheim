package be.elevenways.hohenheim.server.tls;

import org.bouncycastle.asn1.pkcs.PrivateKeyInfo;
import org.bouncycastle.openssl.PEMKeyPair;
import org.bouncycastle.openssl.PEMParser;
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.security.PrivateKey;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * THE reading of certificate PEM text: a chain is the text's CERTIFICATE blocks only, a key is the first private key
 * of the key text.
 *
 * AIDEV-NOTE: a stored certificate text may also hold its private key (a bundle pasted or written as one). The proxy's
 * store, the public chain download and an upload all split through here, so none of them parses a key block as a
 * certificate (the JDK's "signed fields invalid") or hands it out as part of the chain.
 *
 * @author Jelle De Loecker
 * @since  0.10.0
 */
public final class CertificatePem {

    /** One certificate block of a PEM text; whatever else the text holds is never read as the chain. */
    private static final Pattern CERTIFICATE_BLOCK =
        Pattern.compile("-----BEGIN CERTIFICATE-----[\\s\\S]*?-----END CERTIFICATE-----");

    /** One private key block of a PEM text, PKCS#8, a bare key pair or passphrase-protected alike. */
    private static final Pattern KEY_BLOCK =
        Pattern.compile("-----BEGIN ([A-Z0-9 ]*)PRIVATE KEY-----[\\s\\S]*?-----END \\1PRIVATE KEY-----");

    private CertificatePem() {
    }

    /** @return the text's CERTIFICATE blocks, in the order given; empty for null or a text without one */
    public static @NonNull List<String> certificateBlocks(@Nullable String pem) {
        return blocksOf(CERTIFICATE_BLOCK, pem);
    }

    /** @return the text's CERTIFICATE blocks, each ended by a newline; empty when it holds none */
    public static @NonNull String chainText(@Nullable String pem) {
        return joined(certificateBlocks(pem));
    }

    /** @return the text's private key blocks, each ended by a newline; empty for null or a text without one */
    public static @NonNull String keyText(@Nullable String pem) {
        return joined(blocksOf(KEY_BLOCK, pem));
    }

    private static @NonNull List<String> blocksOf(@NonNull Pattern kind, @Nullable String pem) {
        List<String> blocks = new ArrayList<>();
        if (pem == null) {
            return blocks;
        }
        Matcher block = kind.matcher(pem);
        while (block.find()) {
            blocks.add(block.group());
        }
        return blocks;
    }

    private static @NonNull String joined(@NonNull List<String> blocks) {
        StringBuilder text = new StringBuilder();
        for (String block : blocks) {
            text.append(block).append('\n');
        }
        return text.toString();
    }

    /**
     * @return every certificate of the text's CERTIFICATE blocks, in the order given; empty when it holds none
     * @throws CertificateException when a certificate block does not parse
     */
    public static @NonNull List<X509Certificate> chainOf(@Nullable String pem) throws CertificateException {
        CertificateFactory factory = CertificateFactory.getInstance("X.509");
        List<X509Certificate> chain = new ArrayList<>();
        for (String block : certificateBlocks(pem)) {
            chain.add((X509Certificate) factory.generateCertificate(
                new ByteArrayInputStream(block.getBytes(StandardCharsets.UTF_8))));
        }
        return chain;
    }

    /**
     * @return the first private key in the text, written as a bare key pair or as PKCS#8
     * @throws IOException              when the text does not parse as PEM
     * @throws IllegalArgumentException when it holds no unencrypted private key
     */
    public static @NonNull PrivateKey privateKeyOf(@NonNull String pem) throws IOException {
        JcaPEMKeyConverter converter = new JcaPEMKeyConverter();
        try (PEMParser parser = new PEMParser(new StringReader(pem))) {
            for (Object read = parser.readObject(); read != null; read = parser.readObject()) {
                if (read instanceof PEMKeyPair pair) {
                    return converter.getPrivateKey(pair.getPrivateKeyInfo());
                }
                if (read instanceof PrivateKeyInfo info) {
                    return converter.getPrivateKey(info);
                }
            }
        }
        throw new IllegalArgumentException("No private key in the PEM text");
    }
}
