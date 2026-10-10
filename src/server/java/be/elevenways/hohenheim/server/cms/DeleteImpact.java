package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.HohenheimMicrocopy;
import be.elevenways.hohenheim.model.AccessRuleModel;
import be.elevenways.hohenheim.model.CertificateModel;
import be.elevenways.hohenheim.model.DatabaseEngineModel;
import be.elevenways.hohenheim.model.DatabaseModel;
import be.elevenways.hohenheim.model.DnsPeerModel;
import be.elevenways.hohenheim.model.DnsZoneModel;
import be.elevenways.hohenheim.model.DnsZonePeerModel;
import be.elevenways.hohenheim.model.EnvironmentModel;
import be.elevenways.hohenheim.model.GroupedCounts;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.InstanceTemplateModel;
import be.elevenways.hohenheim.model.InstanceVariableModel;
import be.elevenways.hohenheim.model.ProtectedPathModel;
import be.elevenways.hohenheim.model.RuntimeImageModel;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.model.SiteAuthProviderModel;
import be.elevenways.hohenheim.model.SiteDomainModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.server.auth.SiteAuthProviderGuards;
import be.elevenways.hohenheim.server.database.DatabaseEngines;
import be.elevenways.hohenheim.server.database.InstanceDatabaseLinks;
import be.elevenways.hohenheim.server.dns.DnsNames;
import be.elevenways.hohenheim.server.project.ProjectGuards;
import be.elevenways.hohenheim.server.proxy.HostnamePatterns;
import be.elevenways.hohenheim.server.tls.CertificateCoverage;
import be.elevenways.protoblast.common.http.Uri;
import be.elevenways.protoblast.common.i18n.Microcopy;
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
import java.util.Map;
import java.util.Set;

/**
 * What a delete takes with it or why it is dead: the facts a per-record delete confirmation names and every
 * resource's in-use refusal, memoized per request.
 *
 * AIDEV-NOTE: a delete's confirmation and its availability are asked once per ROW while a list page renders, so a
 * naive implementation would issue a query per row. Every table a delete warning or refusal consults is read ONCE
 * per request through {@link RouteScope#memo} and every record on the page is then answered in memory. A
 * conduit-less caller (a test, a detail render outside a request) degrades to reading the tables directly rather
 * than failing. The {@code *InUse} methods are the ONE home of a delete's dead reason; each resource's delete
 * operation reads its own here.
 */
public final class DeleteImpact {

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

    /** Request-scoped snapshot of every managed database, so an attachment listing names it once. */
    private static final IdentifierKey<List<Row>> DATABASES =
        IdentifierKey.of("hohenheim", "delete_impact_databases");

    /** Request-scoped snapshot of every instance, so an attachment listing names its workload once. */
    private static final IdentifierKey<List<Row>> INSTANCES =
        IdentifierKey.of("hohenheim", "delete_impact_instances");

    /** Request-scoped snapshot of every variable, so an environment listing names its holders once. */
    private static final IdentifierKey<List<Row>> VARIABLES =
        IdentifierKey.of("hohenheim", "delete_impact_variables");

    /** Request-scoped database id to the live instances attached to it, shared with the list's Used by cell. */
    private static final IdentifierKey<Map<Integer, List<Row>>> ATTACHED =
        IdentifierKey.of("hohenheim", "database_used_by");

    /** Request-scoped runtime image id to the live instances running it. */
    private static final IdentifierKey<Map<Integer, Long>> IMAGE_INSTANCES =
        IdentifierKey.of("hohenheim", "delete_impact_image_instances");

    /** Request-scoped runtime image id to the templates naming it. */
    private static final IdentifierKey<Map<Integer, Long>> IMAGE_TEMPLATES =
        IdentifierKey.of("hohenheim", "delete_impact_image_templates");

    /** Request-scoped host id to what still references it. */
    private static final IdentifierKey<Map<Integer, ServerModel.References>> SERVER_REFERENCES =
        IdentifierKey.of("hohenheim", "delete_impact_server_references");

    /** Request-scoped host id to the instance a cold migration is moving onto it. */
    private static final IdentifierKey<Map<Integer, Row>> MIGRATIONS =
        IdentifierKey.of("hohenheim", "delete_impact_migrations");

    private DeleteImpact() {}

    // -- why a delete is dead: each @return is the refusal, null while the delete is live -----------------------------

    /** @return the sites gated by the provider (named) and the access rules naming it (counted) */
    static @Nullable Microcopy authProviderInUse(@NonNull Row provider) {
        Integer id = provider.get(SiteAuthProviderModel.ID);
        String sites = join(sitesGatedByAuthProvider(id));
        long rules = rulesNamingAuthProvider(id);
        if (!sites.isEmpty()) {
            return HohenheimMicrocopy.AUTH_PROVIDER.of("delete_in_use").withArg("sites", sites).withArg("rules", rules);
        }
        return rules > 0 ? HohenheimMicrocopy.AUTH_PROVIDER.of("delete_in_use_rules").withArg("rules", rules) : null;
    }

    /**
     * AIDEV-NOTE: both tiers count. Since 2026-08-08 a database can be attached to an instance, and a refusal that only
     * counted SITES would have let a tenant destroy the engine out from under their own running game server.
     *
     * @return the live workloads holding the database, named
     */
    static @Nullable Microcopy databaseInUse(@NonNull Row database) {
        String workloads = workloadsHolding(database.get(DatabaseModel.ID));
        return workloads.isEmpty() ? null : HohenheimMicrocopy.DATABASE.of("delete_in_use")
            .withArg("name", String.valueOf((Object) database.get(DatabaseModel.NAME)))
            .withArg("workloads", workloads);
    }

    /** @return the databases still living on the engine, named */
    static @Nullable Microcopy engineInUse(@NonNull Row engine) {
        Integer engineId = engine.get(DatabaseEngineModel.ID);
        List<Row> hosted = new ArrayList<>();
        for (Row database : engineId == null ? List.<Row>of() : databases()) {
            if (engineId.equals(database.get(DatabaseModel.ENGINE_ID))) {
                hosted.add(database);
            }
        }
        return hosted.isEmpty() ? null : HohenheimMicrocopy.DATABASE_ENGINE.of("delete_in_use")
            .withArg("name", String.valueOf((Object) engine.get(DatabaseEngineModel.NAME)))
            .withArg("databases", DatabaseEngines.names(hosted));
    }

    /**
     * Without the peer the secondary zones replicating from it decay to {@code error} and stop answering once their SOA
     * expire window closes; the enforcement for every other writer is {@code DnsPeerCascades}.
     *
     * @return the secondary zones replicating from the peer, named
     */
    static @Nullable Microcopy dnsPeerInUse(@NonNull Row peer) {
        String zones = join(secondaryZonesOfPeer(peer.get(DnsPeerModel.ID)));
        return zones.isEmpty() ? null : HohenheimMicrocopy.DNS_PEER.of("delete_in_use").withArg("zones", zones);
    }

    /** @return what still groups under the environment, in the write funnel's own words */
    static @Nullable Microcopy environmentInUse(@NonNull Row environment) {
        ProjectGuards.EnvironmentUsage usage = environmentUsage(environment.get(EnvironmentModel.ID));
        return usage.isEmpty() ? null : usage.refusal();
    }

    /** @return how many live instances and templates still run inside the image */
    static @Nullable Microcopy runtimeImageInUse(@NonNull Row image) {
        Integer id = image.get(RuntimeImageModel.ID);
        if (id == null) {
            return null;
        }
        long instances = RouteScope.memo(IMAGE_INSTANCES, () -> GroupedCounts.of(Models.get(InstanceModel.class).find(),
            InstanceModel.RUNTIME_IMAGE_ID)).getOrDefault(id, 0L);
        long templates = RouteScope.memo(IMAGE_TEMPLATES, () -> GroupedCounts.of(Models.get(InstanceTemplateModel.class).find(),
            InstanceTemplateModel.RUNTIME_IMAGE_ID)).getOrDefault(id, 0L);
        return instances > 0 || templates > 0 ? HohenheimMicrocopy.RUNTIME_IMAGE.of("delete_in_use")
            .withArg("instances", instances).withArg("templates", templates) : null;
    }

    /** @return why the host cannot go: it is this machine, a migration is landing on it, or records still name it */
    static @Nullable Microcopy serverInUse(@NonNull Row server) {
        if (ServerParts.local(server)) {
            return HohenheimMicrocopy.SERVER.of("delete_local");
        }
        Integer id = server.get(ServerModel.ID);
        if (id == null) {
            return null;
        }
        Row migrating = RouteScope.memo(MIGRATIONS, ServerModel::migrationsByTarget).get(id);
        if (migrating != null) {
            return HohenheimMicrocopy.SERVER.of("delete_migrating")
                .withArg("instance", String.valueOf((Object) migrating.get(InstanceModel.NAME)));
        }
        ServerModel.References references = RouteScope.memo(SERVER_REFERENCES, ServerModel::referencesByServer)
            .getOrDefault(id, ServerModel.References.NONE);
        return references.any() ? references.describe(HohenheimMicrocopy.SERVER.of("delete_in_use")) : null;
    }

    /** @return the live instance rows a database is attached to, in link order */
    static @NonNull List<Row> liveInstancesOf(@Nullable Integer databaseId) {
        return databaseId == null ? List.of()
            : RouteScope.memo(ATTACHED, InstanceDatabaseLinks::liveInstancesByDatabase).getOrDefault(databaseId, List.of());
    }

    /**
     * The names of the live workloads attached to a database, joined for a sentence; empty when nothing holds it.
     *
     * AIDEV-NOTE: names only, never a path in prose. The list's Used by cell links each app for a reader who
     * may open it; a reason or refusal is a sentence.
     */
    public static @NonNull String workloadsHolding(@Nullable Integer databaseId) {
        List<String> workloads = new ArrayList<>();
        for (Row instance : liveInstancesOf(databaseId)) {
            workloads.add(String.valueOf((Object) instance.get(InstanceModel.NAME)));
        }
        return join(workloads);
    }

    // -- what a delete takes with it ----------------------------------------------------------------------------------

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
            if (providerId.equals(site.get(SiteModel.AUTH_PROVIDER_ID))) {
                String name = site.get(SiteModel.NAME);
                gated.add(name == null || name.isBlank() ? String.valueOf((Object) site.get(SiteModel.ID)) : name);
            }
        }
        return gated;
    }

    /** @return how many access rules name one auth provider */
    static long rulesNamingAuthProvider(@Nullable Integer providerId) {
        return SiteAuthProviderGuards.rulesNaming(providerId, rules());
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
                    && !InstanceModel.SOFT_DELETE.isTrashed(instance)) {
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

    /** @return the managed database's name, or null when the reference is absent or dangling */
    static @Nullable String databaseNameOf(@Nullable Integer databaseId) {
        if (databaseId == null) {
            return null;
        }
        for (Row database : databases()) {
            if (databaseId.equals(database.get(DatabaseModel.ID))) {
                return database.get(DatabaseModel.NAME);
            }
        }
        return null;
    }

    /** @return the instance's name, or null when the reference is absent or dangling */
    static @Nullable String instanceNameOf(@Nullable Integer instanceId) {
        if (instanceId == null) {
            return null;
        }
        for (Row instance : instances()) {
            if (instanceId.equals(instance.get(InstanceModel.ID))) {
                return instance.get(InstanceModel.NAME);
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
        for (AccessListUse use : usesOfAccessList(accessListId)) {
            gated.add(use.path() != null ? use.path() : use.site());
        }
        return gated;
    }

    /**
     * One place an access list gates: a whole site ({@code path} null), or one protected path on a site.
     *
     * @param site the site's name (its id when it has none)
     */
    record AccessListUse(@Nullable String path, @NonNull String site) {
    }

    /** @return every place one access list gates: the sites naming it, then the protected paths naming it */
    static @NonNull List<AccessListUse> usesOfAccessList(@Nullable Integer accessListId) {
        List<AccessListUse> uses = new ArrayList<>();
        if (accessListId == null) {
            return uses;
        }
        for (Row site : sites()) {
            if (accessListId.equals(site.get(SiteModel.ACCESS_LIST_ID))) {
                uses.add(new AccessListUse(null, siteName(site)));
            }
        }
        for (Row path : paths()) {
            if (accessListId.equals(path.get(ProtectedPathModel.ACCESS_LIST_ID))) {
                String pattern = path.get(ProtectedPathModel.PATH);
                if (pattern != null && !pattern.isBlank()) {
                    Integer siteId = path.get(ProtectedPathModel.SITE_ID);
                    Row site = sites().stream().filter(row -> siteId != null && siteId.equals(row.get(SiteModel.ID)))
                        .findFirst().orElse(null);
                    uses.add(new AccessListUse(pattern, site == null ? String.valueOf(siteId) : siteName(site)));
                }
            }
        }
        return uses;
    }

    private static @NonNull String siteName(@NonNull Row site) {
        String name = site.get(SiteModel.NAME);
        return name == null || name.isBlank() ? String.valueOf((Object) site.get(SiteModel.ID)) : name;
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
    static @Nullable String arrivalHostname(@Nullable Conduit conduit) {
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
        return RouteScope.memo(DOMAINS, () -> Models.get(SiteDomainModel.class).find().all());
    }

    private static @NonNull List<Row> certificates() {
        return RouteScope.memo(CERTIFICATES, () -> Models.get(CertificateModel.class).find().all());
    }

    private static @NonNull List<Row> zones() {
        return RouteScope.memo(ZONES, () -> Models.get(DnsZoneModel.class).find().all());
    }

    /** LIVE sites only (the soft-delete find hook): a trashed site gates and names nothing. */
    private static @NonNull List<Row> sites() {
        return RouteScope.memo(SITES, () -> Models.get(SiteModel.class).find().all());
    }

    private static @NonNull List<Row> paths() {
        return RouteScope.memo(PATHS, () -> Models.get(ProtectedPathModel.class).find().all());
    }

    private static @NonNull List<Row> rules() {
        return RouteScope.memo(RULES, () -> Models.get(AccessRuleModel.class).find().all());
    }

    private static @NonNull List<Row> zonePeers() {
        return RouteScope.memo(ZONE_PEERS, () -> Models.get(DnsZonePeerModel.class).find().all());
    }

    private static @NonNull List<Row> environments() {
        return RouteScope.memo(ENVIRONMENTS, () -> Models.get(EnvironmentModel.class).find().all());
    }

    private static @NonNull List<Row> databases() {
        return RouteScope.memo(DATABASES, () -> Models.get(DatabaseModel.class).find().all());
    }

    /** Soft-deleted instances included: an attachment to a destroyed workload still names it. */
    private static @NonNull List<Row> instances() {
        return RouteScope.memo(INSTANCES, () -> Models.get(InstanceModel.class).find().withTrashed().all());
    }

    private static @NonNull List<Row> variables() {
        return RouteScope.memo(VARIABLES, () -> Models.get(InstanceVariableModel.class).find().all());
    }
}
