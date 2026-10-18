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
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.InstanceTemplateModel;
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
import be.elevenways.zenit.common.orm.field.IntegerField;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.orm.query.KeyedRows;
import be.elevenways.zenit.common.routing.RouteScope;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What a delete takes with it or why it is dead: the facts a per-record delete confirmation names and every
 * resource's in-use refusal.
 *
 * AIDEV-NOTE: the {@code *InUse} methods are the ONE home of a delete's dead reason; each resource's delete operation
 * declares its own here as an {@code Availability.batch}. A list render asks the batch ONCE over the page's rows,
 * so each answer is one read narrowed to the asked keys ({@code countBy}, {@code KeyedRows}), never a query per row
 * and never a whole-table snapshot; a single subject (the record page, an invoke) is the batch of one. The
 * confirmations and the list cells below still read their tables once per request through {@link RouteScope#memo}.
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

    /** Request-scoped database id to the live instances attached to it, shared with the list's Used by cell. */
    private static final IdentifierKey<Map<Integer, List<Row>>> ATTACHED =
        IdentifierKey.of("hohenheim", "database_used_by");

    private DeleteImpact() {}

    // -- why a delete is dead: one refusal per asked row, null while its delete is live ------------------------------

    /**
     * Who uses one auth provider: the live sites it gates, named, and the access rules naming it, counted.
     *
     * @param sites the names of the sites whose login gate it is
     * @param rules how many access rules name it
     */
    record AuthProviderUses(@NonNull List<String> sites, long rules) {

        /** @return how many places use it: the rules plus the sites */
        long count() {
            return this.rules + this.sites.size();
        }
    }

    /**
     * THE who-uses-this-provider read, for one provider or a page of them: the gating sites in one read and the
     * naming rules in one read. The delete's refusal and the list's "used by" cell both read it.
     *
     * @return provider id to its uses; a provider nothing uses maps to none (no sites, zero rules)
     */
    static @NonNull Map<Integer, AuthProviderUses> authProviderUses(@NonNull List<? extends Row> providers) {
        List<Integer> ids = idsOf(providers, SiteAuthProviderModel.ID);
        Map<Integer, List<Row>> gating = KeyedRows.byAsked(Models.get(SiteModel.class).find(),
            SiteModel.AUTH_PROVIDER_ID, ids);
        Map<Integer, Long> naming = SiteAuthProviderGuards.rulesNamingAmong(ids);
        Map<Integer, AuthProviderUses> uses = new HashMap<>();
        for (Integer id : ids) {
            List<String> sites = new ArrayList<>();
            for (Row site : gating.getOrDefault(id, List.of())) {
                sites.add(siteName(site));
            }
            uses.put(id, new AuthProviderUses(sites, naming.getOrDefault(id, 0L)));
        }
        return uses;
    }

    /** @return per provider: the sites it gates (named) and the access rules naming it (counted) */
    static @NonNull List<@Nullable Microcopy> authProvidersInUse(@NonNull List<? extends Row> providers) {
        Map<Integer, AuthProviderUses> uses = authProviderUses(providers);
        List<Microcopy> reasons = new ArrayList<>(providers.size());
        for (Row provider : providers) {
            Integer id = provider.get(SiteAuthProviderModel.ID);
            AuthProviderUses use = id == null ? null : uses.get(id);
            List<String> sites = use == null ? List.of() : use.sites();
            long rules = use == null ? 0 : use.rules();
            reasons.add(!sites.isEmpty() ? HohenheimMicrocopy.AUTH_PROVIDER.of("delete_in_use")
                    .withArg("sites", join(sites)).withArg("rules", rules)
                : rules > 0 ? HohenheimMicrocopy.AUTH_PROVIDER.of("delete_in_use_rules").withArg("rules", rules)
                : null);
        }
        return reasons;
    }

    /**
     * AIDEV-NOTE: both tiers count. Since 2026-08-08 a database can be attached to an instance, and a refusal that only
     * counted SITES would have let a tenant destroy the engine out from under their own running game server.
     *
     * @return per database: the live workloads holding it, named
     */
    static @NonNull List<@Nullable Microcopy> databasesInUse(@NonNull List<? extends Row> databases) {
        Map<Integer, List<Row>> holding = InstanceDatabaseLinks.liveInstancesAmong(idsOf(databases, DatabaseModel.ID));
        List<Microcopy> reasons = new ArrayList<>(databases.size());
        for (Row database : databases) {
            Integer id = database.get(DatabaseModel.ID);
            String workloads = workloadNames(id == null ? List.of() : holding.getOrDefault(id, List.of()));
            reasons.add(workloads.isEmpty() ? null : HohenheimMicrocopy.DATABASE.of("delete_in_use")
                .withArg("name", String.valueOf((Object) database.get(DatabaseModel.NAME)))
                .withArg("workloads", workloads));
        }
        return reasons;
    }

    /** @return {@link #databasesInUse} of one database */
    static @Nullable Microcopy databaseInUse(@NonNull Row database) {
        return databasesInUse(List.of(database)).get(0);
    }

    /** @return per engine: the databases still living on it, named */
    static @NonNull List<@Nullable Microcopy> enginesInUse(@NonNull List<? extends Row> engines) {
        Map<Integer, List<Row>> hosting = KeyedRows.byAsked(Models.get(DatabaseModel.class).find(),
            DatabaseModel.ENGINE_ID, idsOf(engines, DatabaseEngineModel.ID));
        List<Microcopy> reasons = new ArrayList<>(engines.size());
        for (Row engine : engines) {
            Integer id = engine.get(DatabaseEngineModel.ID);
            List<Row> hosted = id == null ? List.of() : hosting.getOrDefault(id, List.of());
            reasons.add(hosted.isEmpty() ? null : HohenheimMicrocopy.DATABASE_ENGINE.of("delete_in_use")
                .withArg("name", String.valueOf((Object) engine.get(DatabaseEngineModel.NAME)))
                .withArg("databases", DatabaseEngines.names(hosted)));
        }
        return reasons;
    }

    /**
     * Without the peer the secondary zones replicating from it decay to {@code error} and stop answering once their SOA
     * expire window closes; the enforcement for every other writer is {@code DnsPeerCascades}.
     *
     * @return per peer: the secondary zones replicating from it, named
     */
    static @NonNull List<@Nullable Microcopy> dnsPeersInUse(@NonNull List<? extends Row> peers) {
        Map<Integer, List<Row>> replicating = KeyedRows.byAsked(Models.get(DnsZoneModel.class).find(),
            DnsZoneModel.PRIMARY_PEER_ID, idsOf(peers, DnsPeerModel.ID));
        List<Microcopy> reasons = new ArrayList<>(peers.size());
        for (Row peer : peers) {
            Integer id = peer.get(DnsPeerModel.ID);
            List<String> origins = new ArrayList<>();
            for (Row zone : id == null ? List.<Row>of() : replicating.getOrDefault(id, List.of())) {
                if (DnsZoneModel.ROLE_SECONDARY.equals(DnsZoneModel.roleOf(zone))) {
                    addOrigin(origins, zone);
                }
            }
            reasons.add(origins.isEmpty() ? null
                : HohenheimMicrocopy.DNS_PEER.of("delete_in_use").withArg("zones", join(origins)));
        }
        return reasons;
    }

    /** @return per environment: what still groups under it, in the write funnel's own words */
    static @NonNull List<@Nullable Microcopy> environmentsInUse(@NonNull List<? extends Row> environments) {
        Map<Integer, ProjectGuards.EnvironmentUsage> usages =
            ProjectGuards.usageAmong(idsOf(environments, EnvironmentModel.ID));
        List<Microcopy> reasons = new ArrayList<>(environments.size());
        for (Row environment : environments) {
            Integer id = environment.get(EnvironmentModel.ID);
            ProjectGuards.EnvironmentUsage usage = id == null ? null : usages.get(id);
            reasons.add(usage == null || usage.isEmpty() ? null : usage.refusal());
        }
        return reasons;
    }

    /** @return per image: how many live instances and templates still run inside it */
    static @NonNull List<@Nullable Microcopy> runtimeImagesInUse(@NonNull List<? extends Row> images) {
        List<Integer> ids = idsOf(images, RuntimeImageModel.ID);
        Map<Integer, Long> running = Models.get(InstanceModel.class).find()
            .countBy(InstanceModel.RUNTIME_IMAGE_ID, ids);
        Map<Integer, Long> naming = Models.get(InstanceTemplateModel.class).find()
            .countBy(InstanceTemplateModel.RUNTIME_IMAGE_ID, ids);
        List<Microcopy> reasons = new ArrayList<>(images.size());
        for (Row image : images) {
            Integer id = image.get(RuntimeImageModel.ID);
            long instances = id == null ? 0 : running.getOrDefault(id, 0L);
            long templates = id == null ? 0 : naming.getOrDefault(id, 0L);
            reasons.add(instances > 0 || templates > 0 ? HohenheimMicrocopy.RUNTIME_IMAGE.of("delete_in_use")
                .withArg("instances", instances).withArg("templates", templates) : null);
        }
        return reasons;
    }

    /** @return per host: why it cannot go -- it is this machine, a migration is landing on it, or records name it */
    static @NonNull List<@Nullable Microcopy> serversInUse(@NonNull List<? extends Row> servers) {
        List<Integer> ids = idsOf(servers, ServerModel.ID);
        Map<Integer, Row> migrations = ServerModel.migrationsOnto(ids);
        Map<Integer, ServerModel.References> references = ServerModel.referencesAmong(ids);
        List<Microcopy> reasons = new ArrayList<>(servers.size());
        for (Row server : servers) {
            Integer id = server.get(ServerModel.ID);
            Row migrating = id == null ? null : migrations.get(id);
            ServerModel.References held = id == null ? ServerModel.References.NONE
                : references.getOrDefault(id, ServerModel.References.NONE);
            reasons.add(ServerParts.local(server) ? HohenheimMicrocopy.SERVER.of("delete_local")
                : migrating != null ? HohenheimMicrocopy.SERVER.of("delete_migrating")
                    .withArg("instance", String.valueOf((Object) migrating.get(InstanceModel.NAME)))
                : held.any() ? held.describe(HohenheimMicrocopy.SERVER.of("delete_in_use")) : null);
        }
        return reasons;
    }

    /** @return the live instance rows a database is attached to, in link order */
    static @NonNull List<Row> liveInstancesOf(@Nullable Integer databaseId) {
        return databaseId == null ? List.of()
            : RouteScope.memo(ATTACHED, InstanceDatabaseLinks::liveInstancesByDatabase)
            .getOrDefault(databaseId, List.of());
    }

    /**
     * The names of the live workloads attached to a database, joined for a sentence; empty when nothing holds it.
     *
     * AIDEV-NOTE: names only, never a path in prose. The list's Used by cell links each app for a reader who
     * may open it; a reason or refusal is a sentence.
     */
    public static @NonNull String workloadsHolding(@Nullable Integer databaseId) {
        return workloadNames(liveInstancesOf(databaseId));
    }

    private static @NonNull String workloadNames(@NonNull List<Row> instances) {
        List<String> workloads = new ArrayList<>();
        for (Row instance : instances) {
            workloads.add(String.valueOf((Object) instance.get(InstanceModel.NAME)));
        }
        return join(workloads);
    }

    /** @return the non-null ids of these rows, in row order */
    private static @NonNull List<Integer> idsOf(@NonNull List<? extends Row> rows, @NonNull IntegerField id) {
        List<Integer> ids = new ArrayList<>(rows.size());
        for (Row row : rows) {
            Integer value = row.get(id);
            if (value != null) {
                ids.add(value);
            }
        }
        return ids;
    }

    // -- what a delete takes with it ----------------------------------------------------------------------------------

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

    /** @return the instance's name, trashed included, or null when the reference is absent or dangling */
    static @Nullable String instanceNameOf(@Nullable Integer instanceId) {
        Row instance = instanceOf(instanceId);
        return instance == null ? null : instance.get(InstanceModel.NAME);
    }

    /**
     * @return the instance's name, or null when the reference is absent, dangling or trashed: an app that was deleted
     *         keeps running nowhere, so a dialog naming it as running would claim what is not there
     */
    static @Nullable String liveInstanceNameOf(@Nullable Integer instanceId) {
        Row instance = instanceOf(instanceId);
        return instance == null || InstanceModel.SOFT_DELETE.isTrashed(instance) ? null
            : instance.get(InstanceModel.NAME);
    }

    private static @Nullable Row instanceOf(@Nullable Integer instanceId) {
        if (instanceId == null) {
            return null;
        }
        for (Row instance : instances()) {
            if (instanceId.equals(instance.get(InstanceModel.ID))) {
                return instance;
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
}
