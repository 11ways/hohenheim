package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.model.AccessRuleModel;
import be.elevenways.hohenheim.model.CertificateModel;
import be.elevenways.hohenheim.model.DnsZoneModel;
import be.elevenways.hohenheim.model.DnsZonePeerModel;
import be.elevenways.hohenheim.model.EnvironmentModel;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.InstanceVariableModel;
import be.elevenways.hohenheim.model.ProtectedPathModel;
import be.elevenways.hohenheim.model.SiteDomainModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.server.auth.SiteAuthProviderGuards;
import be.elevenways.hohenheim.server.dns.DnsNames;
import be.elevenways.hohenheim.server.project.ProjectGuards;
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

    /** Request-scoped snapshot of every site, so an access-list listing names its users once. */
    private static final IdentifierKey<List<Row>> SITES =
        IdentifierKey.of("hohenheim", "delete_impact_sites");

    /** Request-scoped snapshot of every protected path, for the same reason. */
    private static final IdentifierKey<List<Row>> PATHS =
        IdentifierKey.of("hohenheim", "delete_impact_paths");

    /** Request-scoped snapshot of every access rule, so a rule count costs no query per row. */
    private static final IdentifierKey<List<Row>> RULES =
        IdentifierKey.of("hohenheim", "delete_impact_rules");

    /** Request-scoped snapshot of every zone-peer link, so a peer listing resolves its zones once. */
    private static final IdentifierKey<List<Row>> ZONE_PEERS =
        IdentifierKey.of("hohenheim", "delete_impact_zone_peers");

    /** Request-scoped snapshot of every environment, so a variable listing names its owner once. */
    private static final IdentifierKey<List<Row>> ENVIRONMENTS =
        IdentifierKey.of("hohenheim", "delete_impact_environments");

    /** Request-scoped snapshot of every instance, so an environment listing names its holders once. */
    private static final IdentifierKey<List<Row>> INSTANCES =
        IdentifierKey.of("hohenheim", "delete_impact_instances");

    /** Request-scoped snapshot of every variable, so an environment listing names its holders once. */
    private static final IdentifierKey<List<Row>> VARIABLES =
        IdentifierKey.of("hohenheim", "delete_impact_variables");

    private DeleteImpact() {}

    /** @return the origins of the SECONDARY zones that replicate from one peer */
    static @NonNull List<String> secondaryZonesOfPeer(@Nullable Integer peerId) {
        List<String> origins = new ArrayList<>();
        if (peerId == null) {
            return origins;
        }
        for (Row zone : zones()) {
            if (peerId.equals(zone.get(DnsZoneModel.PRIMARY_PEER_ID))
                    && DnsZoneModel.ROLE_SECONDARY.equals(DnsZoneModel.roleOf(zone))) {
                addOrigin(origins, zone);
            }
        }
        return origins;
    }

    /** @return the origins of the zones linked to one peer as a NOTIFY/AXFR target, deduplicated */
    static @NonNull List<String> zonesLinkedToPeer(@Nullable Integer peerId) {
        Set<String> origins = new LinkedHashSet<>();
        if (peerId == null) {
            return new ArrayList<>(origins);
        }
        for (Row link : zonePeers()) {
            if (!peerId.equals(link.get(DnsZonePeerModel.PEER_ID))) {
                continue;
            }
            String origin = originOfZone(link.get(DnsZonePeerModel.ZONE_ID));
            if (origin != null && !origin.isEmpty()) {
                origins.add(origin);
            }
        }
        return new ArrayList<>(origins);
    }

    /** @return the names of the LIVE sites whose login gate is one auth provider */
    static @NonNull List<String> sitesGatedByAuthProvider(@Nullable Integer providerId) {
        List<String> gated = new ArrayList<>();
        if (providerId == null) {
            return gated;
        }
        for (Row site : sites()) {
            if (providerId.equals(site.get(SiteModel.AUTH_PROVIDER_ID))
                    && site.get(SiteModel.DELETED_AT) == null) {
                String name = site.get(SiteModel.NAME);
                gated.add(name == null || name.isBlank() ? String.valueOf((Object) site.get(SiteModel.ID)) : name);
            }
        }
        return gated;
    }

    /** @return how many access rules name one auth provider */
    static long rulesNamingAuthProvider(@Nullable Integer providerId) {
        if (providerId == null) {
            return 0;
        }
        long rules = 0;
        for (Row rule : rules()) {
            if (AccessRuleModel.TYPE_AUTH_PROVIDER.equals(rule.get(AccessRuleModel.TYPE))
                    && providerId.equals(SiteAuthProviderGuards.providerIdOf(rule))) {
                rules++;
            }
        }
        return rules;
    }

    /**
     * What still references one environment, answered from the request snapshots; the
     * wording is {@link ProjectGuards.EnvironmentUsage}'s, so the dead delete affordance
     * and the write gate's refusal name the same holders.
     */
    static ProjectGuards.@NonNull EnvironmentUsage environmentUsage(@Nullable Integer environmentId) {
        List<String> instances = new ArrayList<>();
        List<String> variables = new ArrayList<>();
        if (environmentId == null) {
            return new ProjectGuards.EnvironmentUsage(instances, variables);
        }
        for (Row instance : instances()) {
            if (environmentId.equals(instance.get(InstanceModel.ENVIRONMENT_ID))
                    && instance.get(InstanceModel.DELETED_AT) == null) {
                instances.add(ProjectGuards.EnvironmentUsage.nameOf(
                    instance.get(InstanceModel.NAME), instance.get(InstanceModel.ID)));
            }
        }
        for (Row variable : variables()) {
            if (environmentId.equals(variable.get(InstanceVariableModel.ENVIRONMENT_ID))) {
                variables.add(ProjectGuards.EnvironmentUsage.nameOf(
                    variable.get(InstanceVariableModel.KEY), variable.get(InstanceVariableModel.ID)));
            }
        }
        return new ProjectGuards.EnvironmentUsage(instances, variables);
    }

    /** @return the environment's name, or null when the reference is absent or dangling */
    static @Nullable String environmentNameOf(@Nullable Integer environmentId) {
        if (environmentId == null) {
            return null;
        }
        for (Row environment : environments()) {
            if (environmentId.equals(environment.get(EnvironmentModel.ID))) {
                return environment.get(EnvironmentModel.NAME);
            }
        }
        return null;
    }

    private static void addOrigin(@NonNull List<String> origins, @NonNull Row zone) {
        String origin = zone.get(DnsZoneModel.ORIGIN);
        if (origin != null && !origin.isEmpty()) {
            origins.add(origin);
        }
    }

    /**
     * Everything that is gated by one access list and stops being gated when it goes:
     * the sites naming it, then the protected paths naming it.
     *
     * AIDEV-NOTE: this is the whole point of the access-list delete dialog. A route entry
     * whose list is gone compiles to a null tree, and {@code AccessListGate} treats a null
     * tree as ALLOW -- so the delete does not break the gate, it silently opens it.
     */
    static @NonNull List<String> gatedByAccessList(@Nullable Integer accessListId) {
        List<String> gated = new ArrayList<>();
        if (accessListId == null) {
            return gated;
        }
        for (Row site : sites()) {
            if (accessListId.equals(site.get(SiteModel.ACCESS_LIST_ID))) {
                String name = site.get(SiteModel.NAME);
                gated.add(name == null || name.isBlank() ? String.valueOf((Object) site.get(SiteModel.ID)) : name);
            }
        }
        for (Row path : paths()) {
            if (accessListId.equals(path.get(ProtectedPathModel.ACCESS_LIST_ID))) {
                String pattern = path.get(ProtectedPathModel.PATH);
                if (pattern != null && !pattern.isBlank()) {
                    gated.add(pattern);
                }
            }
        }
        return gated;
    }

    /** @return how many rules die with one access list, at any depth */
    static long rulesOfAccessList(@Nullable Integer accessListId) {
        if (accessListId == null) {
            return 0;
        }
        long rules = 0;
        for (Row rule : rules()) {
            if (accessListId.equals(rule.get(AccessRuleModel.ACCESS_LIST_ID))) {
                rules++;
            }
        }
        return rules;
    }

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

    private static @NonNull List<Row> sites() {
        return snapshot(SITES, () -> Models.get(SiteModel.class).find().all());
    }

    private static @NonNull List<Row> paths() {
        return snapshot(PATHS, () -> Models.get(ProtectedPathModel.class).find().all());
    }

    private static @NonNull List<Row> rules() {
        return snapshot(RULES, () -> Models.get(AccessRuleModel.class).find().all());
    }

    private static @NonNull List<Row> zonePeers() {
        return snapshot(ZONE_PEERS, () -> Models.get(DnsZonePeerModel.class).find().all());
    }

    private static @NonNull List<Row> environments() {
        return snapshot(ENVIRONMENTS, () -> Models.get(EnvironmentModel.class).find().all());
    }

    /** Soft-deleted instances included; environment usage filters them out itself. */
    private static @NonNull List<Row> instances() {
        return snapshot(INSTANCES, () -> Models.get(InstanceModel.class).find().withTrashed().all());
    }

    private static @NonNull List<Row> variables() {
        return snapshot(VARIABLES, () -> Models.get(InstanceVariableModel.class).find().all());
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
