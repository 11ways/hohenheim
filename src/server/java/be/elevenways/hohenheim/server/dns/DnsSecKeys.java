package be.elevenways.hohenheim.server.dns;

import org.checkerframework.checker.nullness.qual.NonNull;
import org.xbill.DNS.DNSKEYRecord;
import org.xbill.DNS.DNSSEC;
import org.xbill.DNS.DClass;
import org.xbill.DNS.Name;

import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
// PKCS8EncodedKeySpec/X509EncodedKeySpec used for load; getEncoded() already yields those formats.

/**
 * ECDSA P-256 (DNSSEC algorithm 13) key material for online zone signing: a
 * single Combined Signing Key per zone, stored as base64 PKCS#8 / X.509 and
 * surfaced as a DNSKEY (flags 257: Zone Key + Secure Entry Point).
 */
public final class DnsSecKeys {

    public static final int ALGORITHM = DNSSEC.Algorithm.ECDSAP256SHA256;
    /** Zone Key (256) + Secure Entry Point (1): a CSK is both KSK and ZSK. */
    private static final int CSK_FLAGS = DNSKEYRecord.Flags.ZONE_KEY | DNSKEYRecord.Flags.SEP_KEY;
    private static final int PROTOCOL = 3;

    private final PrivateKey privateKey;
    private final PublicKey publicKey;
    private final DNSKEYRecord dnsKey;

    private DnsSecKeys(@NonNull PrivateKey privateKey, @NonNull PublicKey publicKey,
                       @NonNull DNSKEYRecord dnsKey) {
        this.privateKey = privateKey;
        this.publicKey = publicKey;
        this.dnsKey = dnsKey;
    }

    public @NonNull PrivateKey getPrivateKey() {
        return this.privateKey;
    }

    /** The apex DNSKEY (TTL supplied at signing time via {@link #dnsKeyAt}). */
    public @NonNull DNSKEYRecord getDnsKey() {
        return this.dnsKey;
    }

    public int getKeyTag() {
        return this.dnsKey.getFootprint();
    }

    /** A copy of the DNSKEY at the given apex/TTL (the stored one is minted at TTL 0). */
    public @NonNull DNSKEYRecord dnsKeyAt(@NonNull Name origin, long ttl) {
        return new DNSKEYRecord(origin, DClass.IN, ttl, CSK_FLAGS, PROTOCOL, ALGORITHM,
            this.dnsKey.getKey());
    }

    /** Freshly generate a CSK for a new signing zone. */
    public static @NonNull DnsSecKeys generate(@NonNull Name origin) {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
            generator.initialize(new ECGenParameterSpec("secp256r1"));
            KeyPair pair = generator.generateKeyPair();
            DNSKEYRecord dnsKey = new DNSKEYRecord(origin, DClass.IN, 0, CSK_FLAGS, PROTOCOL,
                ALGORITHM, pair.getPublic());
            return new DnsSecKeys(pair.getPrivate(), pair.getPublic(), dnsKey);
        }
        catch (Exception e) {
            throw new IllegalStateException("Could not generate a DNSSEC key: " + e.getMessage(), e);
        }
    }

    /** Reconstruct from the stored base64 PKCS#8 / X.509 material. */
    public static @NonNull DnsSecKeys load(@NonNull Name origin, @NonNull String privateB64,
                                           @NonNull String publicB64) {
        try {
            KeyFactory factory = KeyFactory.getInstance("EC");
            PrivateKey privateKey = factory.generatePrivate(
                new PKCS8EncodedKeySpec(Base64.getDecoder().decode(privateB64)));
            PublicKey publicKey = factory.generatePublic(
                new X509EncodedKeySpec(Base64.getDecoder().decode(publicB64)));
            DNSKEYRecord dnsKey = new DNSKEYRecord(origin, DClass.IN, 0, CSK_FLAGS, PROTOCOL,
                ALGORITHM, publicKey);
            return new DnsSecKeys(privateKey, publicKey, dnsKey);
        }
        catch (Exception e) {
            throw new IllegalStateException("Could not load the DNSSEC key: " + e.getMessage(), e);
        }
    }

    public @NonNull String privateKeyBase64() {
        return Base64.getEncoder().encodeToString(privateKey.getEncoded());
    }

    public @NonNull String publicKeyBase64() {
        return Base64.getEncoder().encodeToString(publicKey.getEncoded());
    }
}
