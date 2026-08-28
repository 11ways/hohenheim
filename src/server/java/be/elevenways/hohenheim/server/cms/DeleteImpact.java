package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.model.CertificateModel;
import be.elevenways.hohenheim.model.DnsZoneModel;
import be.elevenways.hohenheim.model.SiteDomainModel;
import be.elevenways.hohenheim.server.dns.DnsNames;
import be.elevenways.hohenheim.server.proxy.HostnamePatterns;
import be.elevenways.hohenheim.server.tls.CertificateCoverage;
import be.elevenways.protoblast.common.http.Uri;
import be.elevenways.protoblast.common.key.IdentifierKey;
import be.elevenways.protoblast.common.util.BlastString;
import be.elevenways.zenit.common.conduit.Conduit;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.routing.RouteScope;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

/**
 * The facts a per-record delete confirmation names, memoized per request.
 *
 * AIDEV-NOTE: {@code Resource.deleteConfirmationFor} is called once per ROW while a list
 * page renders, so a naive implementation would issue a query per row. The two tables a
 * delete warning has to consult (site hostnames, certificate SAN lists) are snapshotted
 * ONCE per request through the conduit's attribute scope and every zone/site/certificate
 * on the page is then answered in memory. A conduit-less caller (a test, a detail render
 * outside a request) degrades to reading the tables directly rather than failing.
 */
final class DeleteImpact {

    /** Request-scoped snapshot of every site hostname, so a list page reads the table once. */
    private static final IdentifierKey<List<Row>> DOMAINS =
        IdentifierKey.of("hohenheim", "delete_impact_domains");

    /** Request-scoped snapshot of every certificate, for the same reason. */
    private static final IdentifierKey<List<Row>> CERTIFICATES =
        IdentifierKey.of("hohenheim", "delete_impact_certificates");

    /** Request-scoped snapshot of every zone, so a records listing resolves origins once. */
    private static final IdentifierKey<List<Row>> ZONES =
        IdentifierKey.of("hohenheim", "delete_impact_zones");

    private DeleteImpact() {}

    /**
     * The origin of the zone a record answers in.
     *
     * @param zoneId the record's stored zone reference
     * @return the origin, or null when the reference is absent or dangling
     */
    static @Nullable String originOfZone(@Nullable Integer zoneId) {
        if (zoneId == null) {
            return null;
        }
        for (Row zone : zones()) {
            if (zoneId.equals(zone.get(DnsZoneModel.ID))) {
                return zone.get(DnsZoneModel.ORIGIN);
            }
        }
        return null;
    }

    /** @return the hostnames bound to one site, in table order */
    static @NonNull List<String> hostnamesOfSite(@Nullable Integer siteId) {
        List<String> hostnames = new ArrayList<>();
        if (siteId == null) {
            return hostnames;
        }
        for (Row domain : domains()) {
            if (siteId.equals(domain.get(SiteDomainModel.SITE_ID))) {
                String hostname = hostname(domain);
                if (hostname != null) {
                    hostnames.add(hostname);
                }
            }
        }
        return hostnames;
    }

    /**
     * The site hostnames and certificate names that resolve inside a zone, deduplicated.
     *
     * @param origin the zone origin, already normalized by {@code DnsNames}
     */
    static @NonNull List<String> dependentsOfZone(@Nullable String origin) {
        Set<String> dependents = new LinkedHashSet<>();
        if (origin == null || origin.isEmpty()) {
            return new ArrayList<>(dependents);
        }
        for (Row domain : domains()) {
            String hostname = hostname(domain);
            if (hostname != null && DnsNames.zoneContains(origin, hostname)) {
                dependents.add(hostname);
            }
        }
        for (Row certificate : certificates()) {
            for (String name : CertificateCoverage.namesOf(certificate)) {
                if (DnsNames.zoneContains(origin, name)) {
                    dependents.add(name);
                }
            }
        }
        return new ArrayList<>(dependents);
    }

    /**
     * The hostname this very request reached the admin panel at, when the zone answers
     * for it -- the one delete that can lock the operator out of the surface they are
     * clicking in.
     *
     * @return the hostname, or null when it lies outside the zone or is unknowable
     */
    static @Nullable String adminHostnameInZone(@Nullable String origin) {
        if (origin == null || origin.isEmpty()) {
            return null;
        }
        String hostname = arrivalHostname(RouteScope.currentConduit());
        if (hostname == null) {
            return null;
        }
        return DnsNames.zoneContains(origin, hostname) ? hostname : null;
    }

    /**
     * The hostname this very request reached the admin panel at, when THIS SITE is the one
     * serving it -- the disable and the delete that take the surface the operator is
     * clicking in offline.
     *
     * AIDEV-NOTE: coverage is asked of {@link HostnamePatterns#covers}, the same matcher the
     * certificate walk uses, so a wildcard row serving the panel answers too. It fails
     * CLOSED on a REGEX row, which for that matcher is the safe direction and for this one
     * is not: a panel routed by a regex row gets the confirmation but not the refusal. The
     * recovery path stays open by construction either way -- reaching the backend directly
     * (an ssh forward to its port) arrives at a hostname no site domain covers, so nothing
     * is ever refused there.
     *
     * @param conduit the request to read the arrival hostname off; null when there is none
     * @return the hostname, or null when this site does not serve it or it is unknowable
     */
    static @Nullable String adminHostnameOfSite(@Nullable Integer siteId,
                                                @Nullable Conduit conduit) {
        String hostname = arrivalHostname(conduit);
        if (siteId == null || hostname == null) {
            return null;
        }
        for (Row domain : domains()) {
            if (!siteId.equals(domain.get(SiteDomainModel.SITE_ID))) {
                continue;
            }
            if (HostnamePatterns.covers(domain.get(SiteDomainModel.HOSTNAME),
                    domain.get(SiteDomainModel.MATCH_TYPE), hostname)) {
                return hostname;
            }
        }
        return null;
    }

    /** @return the lowercased hostname this request arrived on, or null when unknowable */
    private static @Nullable String arrivalHostname(@Nullable Conduit conduit) {
        String requestOrigin = conduit == null ? null : conduit.getRequestOrigin();
        if (requestOrigin == null || requestOrigin.isEmpty()) {
            return null;
        }
        String hostname = new Uri(requestOrigin).getHostname();
        if (hostname == null || hostname.isEmpty()) {
            return null;
        }
        return BlastString.lower(hostname);
    }

    /** @return the names joined for a dialog sentence, empty when there are none */
    static @NonNull String join(@NonNull List<String> names) {
        return String.join(", ", names);
    }

    private static @Nullable String hostname(@NonNull Row domain) {
        String hostname = domain.get(SiteDomainModel.HOSTNAME);
        if (hostname == null || hostname.isBlank()) {
            return null;
        }
        return BlastString.lower(hostname.trim());
    }

    private static @NonNull List<Row> domains() {
        return snapshot(DOMAINS, () -> Models.get(SiteDomainModel.class).find().all());
    }

    private static @NonNull List<Row> certificates() {
        return snapshot(CERTIFICATES, () -> Models.get(CertificateModel.class).find().all());
    }

    private static @NonNull List<Row> zones() {
        return snapshot(ZONES, () -> Models.get(DnsZoneModel.class).find().all());
    }

    /** Read a snapshot from the request scope, loading it once when it is not there yet. */
    private static @NonNull List<Row> snapshot(@NonNull IdentifierKey<List<Row>> key,
                                               @NonNull Supplier<List<Row>> loader) {
        Conduit conduit = RouteScope.currentConduit();
        if (conduit == null) {
            return loader.get();
        }
        try {
            List<Row> cached = conduit.getAttribute(key);
            if (cached != null) {
                return cached;
            }
            List<Row> loaded = loader.get();
            conduit.setAttribute(key, loaded);
            return loaded;
        } catch (UnsupportedOperationException attributeless) {
            // An attribute-less conduit degrades to reading the table.
            return loader.get();
        }
    }
}
