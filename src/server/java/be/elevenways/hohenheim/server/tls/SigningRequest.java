package be.elevenways.hohenheim.server.tls;

import be.elevenways.hohenheim.HohenheimMicrocopy;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.diagnostic.DigestAlgorithm;
import be.elevenways.protoblast.common.util.BlastString;
import org.bouncycastle.asn1.ASN1ObjectIdentifier;
import org.bouncycastle.asn1.pkcs.Attribute;
import org.bouncycastle.asn1.pkcs.PKCSObjectIdentifiers;
import org.bouncycastle.asn1.x500.AttributeTypeAndValue;
import org.bouncycastle.asn1.x500.RDN;
import org.bouncycastle.asn1.x500.style.BCStyle;
import org.bouncycastle.asn1.sec.SECObjectIdentifiers;
import org.bouncycastle.asn1.x500.style.IETFUtils;
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.Extensions;
import org.bouncycastle.asn1.x509.GeneralName;
import org.bouncycastle.asn1.x509.GeneralNames;
import org.bouncycastle.asn1.x9.X9ObjectIdentifiers;
import org.bouncycastle.openssl.PEMParser;
import org.bouncycastle.operator.jcajce.JcaContentVerifierProviderBuilder;
import org.bouncycastle.pkcs.PKCS10CertificationRequest;
import org.bouncycastle.pkcs.jcajce.JcaPKCS10CertificationRequest;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.io.StringReader;
import java.security.PublicKey;
import java.security.interfaces.RSAPublicKey;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * A requester's PKCS#10 signing request, read and judged once: Hohenheim orders a certificate from it only when it is
 * signed by the key it certifies, names exactly the requested hostnames and asks for nothing Hohenheim would not honour.
 *
 * AIDEV-NOTE: the accepted keys are what the CA accepts at finalize (RSA 2048/3072/4096, ECDSA P-256/P-384), so a
 * request is refused here rather than after its challenges were already answered. Nothing beyond the names is honoured
 * (the CA writes its own subject, usages and lifetime), so a subject attribute other than the common name, an
 * attribute other than the extension request and an extension other than the subject alternative names are refused
 * by name instead of being silently dropped.
 *
 * @author Jelle De Loecker
 * @since  0.10.0
 */
public final class SigningRequest {

    /** Why a signing request was refused; each reads its own sentence ({@link Refused#sentence}). */
    public enum Refusal {

        /** Not one PEM CERTIFICATE REQUEST block that parses. */
        UNREADABLE,

        /** Its signature does not verify against the key it carries: no proof that the requester holds that key. */
        BAD_SIGNATURE,

        /** Signed with a digest the CA no longer accepts (SHA-1, MD5) or an algorithm it does not know. */
        WEAK_SIGNATURE,

        /** An RSA key below 2048 bits. */
        WEAK_KEY,

        /** A key the CA does not issue for: another RSA size, another curve or another algorithm. */
        UNSUPPORTED_KEY,

        /** The names it carries are not exactly the requested hostnames. */
        NAMES_DIFFER,

        /** A subject attribute, request attribute, extension or alternative name Hohenheim would not honour. */
        UNSUPPORTED_CONTENT
    }

    /** A refused signing request. */
    public static final class Refused extends RuntimeException {

        private final Refusal refusal;
        private final String detail;

        /** @param detail the technical token the sentence names (a size, an OID, the names carried), or empty */
        Refused(@NonNull Refusal refusal, @NonNull String detail) {
            super("Signing request refused (" + refusal + ")" + (detail.isEmpty() ? "" : ": " + detail));
            this.refusal = refusal;
            this.detail = detail;
        }

        public @NonNull Refusal refusal() {
            return this.refusal;
        }

        /** @return the refusal in the requester's words, naming what was refused */
        public @NonNull Microcopy sentence() {
            String key = switch (this.refusal) {
                case UNREADABLE -> "signing_request_unreadable";
                case BAD_SIGNATURE -> "signing_request_bad_signature";
                case WEAK_SIGNATURE -> "signing_request_weak_signature";
                case WEAK_KEY -> "signing_request_weak_key";
                case UNSUPPORTED_KEY -> "signing_request_unsupported_key";
                case NAMES_DIFFER -> "signing_request_names_differ";
                case UNSUPPORTED_CONTENT -> "signing_request_unsupported_content";
            };
            return HohenheimMicrocopy.VIOLATIONS.of(key).withArg("detail", this.detail);
        }
    }

    /** The RSA sizes the CA issues for. */
    private static final Set<Integer> RSA_BITS = Set.of(2048, 3072, 4096);

    /** The signature algorithms accepted on a request: SHA-2 with RSA or ECDSA. */
    private static final Set<ASN1ObjectIdentifier> SIGNATURES = Set.of(
        PKCSObjectIdentifiers.sha256WithRSAEncryption, PKCSObjectIdentifiers.sha384WithRSAEncryption,
        PKCSObjectIdentifiers.sha512WithRSAEncryption, X9ObjectIdentifiers.ecdsa_with_SHA256,
        X9ObjectIdentifiers.ecdsa_with_SHA384, X9ObjectIdentifiers.ecdsa_with_SHA512);

    /** The curves the CA issues for: P-256 and P-384. */
    private static final Set<ASN1ObjectIdentifier> CURVES = Set.of(SECObjectIdentifiers.secp256r1,
        SECObjectIdentifiers.secp384r1);

    private final String pem;
    private final byte[] der;
    private final PublicKey publicKey;
    private final List<String> names;

    private SigningRequest(@NonNull String pem, byte @NonNull [] der, @NonNull PublicKey publicKey,
                           @NonNull List<String> names) {
        this.pem = pem;
        this.der = der;
        this.publicKey = publicKey;
        this.names = names;
    }

    /**
     * Reads and judges a signing request for exactly these hostnames.
     *
     * @param hostnames the names the certificate is asked for; their order and case do not matter
     * @throws Refused on the first rule the request breaks
     */
    public static @NonNull SigningRequest read(@Nullable String pem, @NonNull List<String> hostnames) {
        PKCS10CertificationRequest request = parse(pem);
        JcaPKCS10CertificationRequest jca = new JcaPKCS10CertificationRequest(request);
        PublicKey key;
        try {
            key = jca.getPublicKey();
        } catch (Exception unreadable) {
            throw new Refused(Refusal.UNSUPPORTED_KEY, request.getSubjectPublicKeyInfo().getAlgorithm().getAlgorithm()
                .getId());
        }
        judgeKey(request.getSubjectPublicKeyInfo(), key);
        ASN1ObjectIdentifier signature = request.getSignatureAlgorithm().getAlgorithm();
        if (!SIGNATURES.contains(signature)) {
            throw new Refused(Refusal.WEAK_SIGNATURE, signature.getId());
        }
        boolean verified;
        try {
            verified = request.isSignatureValid(new JcaContentVerifierProviderBuilder().build(
                request.getSubjectPublicKeyInfo()));
        } catch (Exception broken) {
            verified = false;
        }
        if (!verified) {
            throw new Refused(Refusal.BAD_SIGNATURE, "");
        }
        Set<String> carried;
        try {
            carried = carriedNames(request);
        } catch (Refused refused) {
            throw refused;
        } catch (RuntimeException malformed) {
            // A malformed extension request (an attribute value that is no Extensions) reads as no request at all.
            throw new Refused(Refusal.UNREADABLE, "");
        }
        Set<String> requested = new LinkedHashSet<>();
        for (String hostname : hostnames) {
            requested.add(BlastString.lower(hostname.trim()));
        }
        if (!carried.equals(requested)) {
            throw new Refused(Refusal.NAMES_DIFFER, carried.isEmpty() ? "-" : String.join(", ", carried));
        }
        byte[] der;
        try {
            der = request.getEncoded();
        } catch (Exception unencodable) {
            throw new Refused(Refusal.UNREADABLE, "");
        }
        return new SigningRequest(CertificatePem.signingRequestPemOf(der), der, key, List.copyOf(carried));
    }

    /** @return the request as one clean CERTIFICATE REQUEST block, what is stored and renewed from */
    public @NonNull String pem() {
        return this.pem;
    }

    /** @return the request's DER, what the CA's finalize receives */
    public byte @NonNull [] der() {
        return this.der.clone();
    }

    /** @return the names the request carries, lowercased, in its own order */
    public @NonNull List<String> names() {
        return this.names;
    }

    /** @return the SHA-256 of the key's SubjectPublicKeyInfo in hex, what a DANE 3 1 1 record pins */
    public @NonNull String publicKeyPin() {
        return publicKeyPin(this.publicKey);
    }

    /** @return the SHA-256 of a public key's SubjectPublicKeyInfo in lowercase hex */
    public static @NonNull String publicKeyPin(@NonNull PublicKey key) {
        return DigestAlgorithm.SHA_256.hex(key.getEncoded());
    }

    private static @NonNull PKCS10CertificationRequest parse(@Nullable String pem) {
        if (pem == null || pem.isBlank()) {
            throw new Refused(Refusal.UNREADABLE, "");
        }
        try (PEMParser parser = new PEMParser(new StringReader(pem))) {
            Object first = parser.readObject();
            if (!(first instanceof PKCS10CertificationRequest request)) {
                throw new Refused(Refusal.UNREADABLE, "");
            }
            if (parser.readObject() != null) {
                throw new Refused(Refusal.UNREADABLE, "");
            }
            return request;
        } catch (Refused refused) {
            throw refused;
        } catch (Exception unreadable) {
            throw new Refused(Refusal.UNREADABLE, "");
        }
    }

    /**
     * Accepts the keys the CA issues for, judged on the request's own SubjectPublicKeyInfo.
     *
     * AIDEV-NOTE: an EC key is judged by its named-curve OID, never by its field size, which would accept
     * brainpoolP256r1 and secp256k1 the CA refuses.
     */
    private static void judgeKey(@NonNull SubjectPublicKeyInfo info, @NonNull PublicKey key) {
        ASN1ObjectIdentifier algorithm = info.getAlgorithm().getAlgorithm();
        if (PKCSObjectIdentifiers.rsaEncryption.equals(algorithm) && key instanceof RSAPublicKey rsa) {
            int bits = rsa.getModulus().bitLength();
            if (bits < 2048) {
                throw new Refused(Refusal.WEAK_KEY, "RSA " + bits);
            }
            if (!RSA_BITS.contains(bits)) {
                throw new Refused(Refusal.UNSUPPORTED_KEY, "RSA " + bits);
            }
            return;
        }
        if (X9ObjectIdentifiers.id_ecPublicKey.equals(algorithm)) {
            if (info.getAlgorithm().getParameters() instanceof ASN1ObjectIdentifier curve && CURVES.contains(curve)) {
                return;
            }
            throw new Refused(Refusal.UNSUPPORTED_KEY, "EC " + info.getAlgorithm().getParameters());
        }
        throw new Refused(Refusal.UNSUPPORTED_KEY, key.getAlgorithm());
    }

    /** The names the request carries: its subject common name and its DNS alternative names, nothing else allowed. */
    private static @NonNull Set<String> carriedNames(@NonNull PKCS10CertificationRequest request) {
        Set<String> names = new LinkedHashSet<>();
        for (RDN rdn : request.getSubject().getRDNs()) {
            for (AttributeTypeAndValue value : rdn.getTypesAndValues()) {
                if (!BCStyle.CN.equals(value.getType())) {
                    throw new Refused(Refusal.UNSUPPORTED_CONTENT,
                        BCStyle.INSTANCE.oidToDisplayName(value.getType()) + "=");
                }
                names.add(BlastString.lower(IETFUtils.valueToString(value.getValue()).trim()));
            }
        }
        for (Attribute attribute : request.getAttributes()) {
            if (!PKCSObjectIdentifiers.pkcs_9_at_extensionRequest.equals(attribute.getAttrType())) {
                throw new Refused(Refusal.UNSUPPORTED_CONTENT, attribute.getAttrType().getId());
            }
            for (var value : attribute.getAttributeValues()) {
                Extensions extensions = Extensions.getInstance(value);
                for (ASN1ObjectIdentifier oid : extensions.getExtensionOIDs()) {
                    if (!Extension.subjectAlternativeName.equals(oid)) {
                        throw new Refused(Refusal.UNSUPPORTED_CONTENT, oid.getId());
                    }
                }
                GeneralNames alternatives = GeneralNames.fromExtensions(extensions, Extension.subjectAlternativeName);
                if (alternatives == null) {
                    continue;
                }
                for (GeneralName alternative : alternatives.getNames()) {
                    if (alternative.getTagNo() != GeneralName.dNSName) {
                        throw new Refused(Refusal.UNSUPPORTED_CONTENT, String.valueOf(alternative.getName()));
                    }
                    names.add(BlastString.lower(alternative.getName().toString().trim()));
                }
            }
        }
        return names;
    }
}
