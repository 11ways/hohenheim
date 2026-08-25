package be.elevenways.hohenheim.server.tls;

import be.elevenways.hohenheim.model.CertificateModel;
import be.elevenways.protoblast.common.util.BlastString;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.orm.query.criteria.CompositeCriteria;
import be.elevenways.zenit.common.orm.query.criteria.CompositeOperator;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Which stored certificate covers a hostname, by SAN list (exact or
 * single-label wildcard, the {@link CertificateStore} semantics) -- DB-backed,
 * so it also answers for certificates the TLS store has not loaded (pending,
 * error). Active certificates win over broken ones.
 *
 * @author Jelle De Loecker
 * @since 0.2.0
 */
public final class CertificateCoverage {

    private CertificateCoverage() {}

    /** @return the covering certificate row (active preferred), or null */
    public static @Nullable Row coveringCertificate(@Nullable String hostname) {
        if (hostname == null || hostname.isEmpty()) {
            return null;
        }
        String needle = BlastString.lower(hostname);
        List<Row> certs = Models.get(CertificateModel.class).find()
            .where(new CompositeCriteria(CompositeOperator.OR,
                CertificateModel.PROVIDER.isNull(),
                CertificateModel.PROVIDER.ne(CertificateModel.PROVIDER_ACME_ACCOUNT)))
            .all();

        Row fallback = null;
        for (Row cert : certs) {
            if (!covers(cert, needle)) {
                continue;
            }
            if (CertificateModel.STATUS_ACTIVE.equals(cert.get(CertificateModel.STATUS))) {
                return cert;
            }
            if (fallback == null) {
                fallback = cert;
            }
        }
        return fallback;
    }

    /**
     * THE stored SAN-list parse: comma- or whitespace-separated, lowercased, blanks
     * dropped -- so a reader of a certificate's names never re-spells the separator.
     *
     * @return the names the certificate declares, in stored order
     */
    public static @NonNull List<String> namesOf(@NonNull Row cert) {
        String names = cert.get(CertificateModel.DOMAIN_NAMES_TEXT);
        if (names == null || names.isEmpty()) {
            return List.of();
        }
        List<String> parsed = new ArrayList<>();
        for (String raw : names.split("[,\\s]+")) {
            String name = BlastString.lower(raw.trim());
            if (!name.isEmpty()) {
                parsed.add(name);
            }
        }
        return parsed;
    }

    private static boolean covers(@NonNull Row cert, @NonNull String hostname) {
        int dot = hostname.indexOf('.');
        String wildcard = dot > 0 ? "*" + hostname.substring(dot) : null;
        for (String name : namesOf(cert)) {
            if (name.equals(hostname) || name.equals(wildcard)) {
                return true;
            }
        }
        return false;
    }
}
