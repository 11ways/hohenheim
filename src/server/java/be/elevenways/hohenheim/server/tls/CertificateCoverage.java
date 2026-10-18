package be.elevenways.hohenheim.server.tls;

import be.elevenways.hohenheim.model.CertificateModel;
import be.elevenways.hohenheim.server.ServerMain;
import be.elevenways.protoblast.common.util.BlastString;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.orm.query.criteria.CompositeCriteria;
import be.elevenways.zenit.common.orm.query.criteria.CompositeOperator;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * Which stored certificate covers a hostname, by SAN list (exact or
 * single-label wildcard, the {@link CertificateStore} semantics) -- DB-backed,
 * so it also answers for certificates the TLS store has not loaded (pending,
 * error). Active certificates win over served ones whose renewal failed, and those over the rest.
 *
 * @author Jelle De Loecker
 * @since 0.2.0
 */
public final class CertificateCoverage {

    private CertificateCoverage() {}

    /** @return the covering certificate row (active preferred, then served), or null */
    public static @Nullable Row coveringCertificate(@Nullable String hostname) {
        if (hostname == null || hostname.isEmpty()) {
            return null;
        }
        return coveringCertificate(hostname, candidates());
    }

    /**
     * The certificate rows a name can be covered by (every row but the ACME account key), read once for a page of
     * names ({@link #coveringCertificate(String, List)}).
     */
    public static @NonNull List<Row> candidates() {
        return Models.get(CertificateModel.class).find()
            .where(new CompositeCriteria(CompositeOperator.OR,
                CertificateModel.PROVIDER.isNull(),
                CertificateModel.PROVIDER.ne(CertificateModel.PROVIDER_ACME_ACCOUNT)))
            .all();
    }

    /**
     * {@link #candidates()} read at most once, on its first ask: the one supplier a page of names shares, so the page
     * reads the certificate rows once, and a page whose names never ask (every one covered by a working certificate)
     * reads none.
     */
    public static @NonNull Supplier<List<Row>> candidatesOnce() {
        AtomicReference<List<Row>> read = new AtomicReference<>();
        return () -> read.updateAndGet(rows -> rows != null ? rows : candidates());
    }

    /**
     * {@link #coveringCertificate(String)} among certificate rows already read ({@link #candidates()}).
     *
     * @return the covering certificate row (active preferred, then served), or null
     */
    public static @Nullable Row coveringCertificate(@Nullable String hostname, @NonNull List<Row> certs) {
        if (hostname == null || hostname.isEmpty()) {
            return null;
        }
        String needle = BlastString.lower(hostname);

        Row served = null;
        Row fallback = null;
        for (Row cert : certs) {
            if (!covers(cert, needle)) {
                continue;
            }
            if (CertificateModel.STATUS_ACTIVE.equals(cert.get(CertificateModel.STATUS))) {
                return cert;
            }
            if (served == null && CertificateModel.served(cert)) {
                served = cert;
            }
            if (fallback == null) {
                fallback = cert;
            }
        }
        return served != null ? served : fallback;
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

    /**
     * The names a working certificate covers: what the running proxy's certificate store loaded (the names it answers
     * a handshake for), and the names a served certificate row ({@link CertificateModel#SERVED_STATUSES}) declares
     * only where no proxy runs in this process (a node without the proxy role, a test), because the stored rows are
     * then all there is to read.
     *
     * AIDEV-NOTE: THE rule routing (RouteTableBuilder's global force), the force-HTTPS latch, "Get a certificate" and
     * every HTTPS display (AppHealth.workingNames, which also asks whether the listener terminates) read. A served row
     * is not a working certificate: a row without loadable material was once sent to HTTPS by the global setting,
     * auto-forced and hiding "Get a certificate", while the proxy could serve it nothing.
     */
    public static @NonNull Set<String> workingNames() {
        var proxy = ServerMain.getProxyServer();
        return workingNames(proxy == null ? null : proxy.getCertificateStore());
    }

    /**
     * @param store the proxy's certificate store, null where no proxy runs in this process
     * @return {@link #workingNames()} for that store: what it loaded, or the served rows without one
     */
    public static @NonNull Set<String> workingNames(@Nullable CertificateStore store) {
        return store == null ? servedNames() : store.servedNames();
    }

    /**
     * Whether a certificate row is one the running proxy loaded and serves; true where no proxy runs in this process,
     * where the stored rows are all there is to read ({@link #workingNames()}'s same rule).
     */
    public static boolean loaded(@NonNull Row cert) {
        var proxy = ServerMain.getProxyServer();
        CertificateStore store = proxy == null ? null : proxy.getCertificateStore();
        Integer id = cert.get(CertificateModel.ID);
        return store == null || id != null && store.holds(id);
    }

    /** Every name a served certificate row declares, read once. */
    private static @NonNull Set<String> servedNames() {
        Set<String> names = new HashSet<>();
        for (Row cert : Models.get(CertificateModel.class).find()
                .where(CertificateModel.STATUS.in(CertificateModel.SERVED_STATUSES)).all()) {
            if (!CertificateModel.PROVIDER_ACME_ACCOUNT.equals(cert.get(CertificateModel.PROVIDER))) {
                names.addAll(namesOf(cert));
            }
        }
        return names;
    }

    /** @return whether one of {@code names} (a SAN list or {@link #workingNames()}) covers the hostname */
    public static boolean covers(@NonNull Collection<String> names, @Nullable String hostname) {
        if (hostname == null || hostname.isEmpty()) {
            return false;
        }
        String needle = BlastString.lower(hostname);
        int dot = needle.indexOf('.');
        return names.contains(needle) || dot > 0 && names.contains("*" + needle.substring(dot));
    }

    /** @return whether the certificate's SAN list covers the hostname */
    public static boolean covers(@NonNull Row cert, @Nullable String hostname) {
        return covers(namesOf(cert), hostname);
    }
}
