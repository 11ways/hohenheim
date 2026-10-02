package be.elevenways.hohenheim.test;

import be.elevenways.hohenheim.HohenheimParams;
import be.elevenways.hohenheim.HohenheimSlugs;
import be.elevenways.hohenheim.model.DatabaseModel;
import be.elevenways.hohenheim.model.DnsPeerModel;
import be.elevenways.hohenheim.model.DnsZoneModel;
import be.elevenways.hohenheim.model.GameDomainModel;
import be.elevenways.hohenheim.model.GitProviderModel;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.SiteDomainModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.hohenheim.server.source.GiteaProviderKind;
import be.elevenways.hohenheim.source.GitProviderOperations;
import be.elevenways.zenit.auth.model.GrantSubjectType;
import be.elevenways.zenit.auth.model.UserModel;
import be.elevenways.zenit.auth.model.UserPrincipal;
import be.elevenways.zenit.auth.server.AuthModels;
import be.elevenways.zenit.auth.server.RecordGrants;
import be.elevenways.zenit.cms.test.support.PanelSurfaceComparer;
import be.elevenways.zenit.cms.test.support.PanelSurfaces;
import be.elevenways.zenit.cms.test.support.PlacedOperationMoves;
import be.elevenways.zenit.cms.test.support.SurfaceBaselines;
import be.elevenways.zenit.cms.test.support.SurfaceCase;
import be.elevenways.zenit.cms.test.support.TwinCorrespondence;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.refusal.ZenitRefusalReason;
import be.elevenways.zenit.common.security.AccessContext;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The database, DNS and git provider entries of stage 5 B13, admin and tenant twins, stored before they move onto
 * shared parts and compared exactly after it.
 *
 * AIDEV-NOTE: the stored set ({@code /panel-surfaces/database-dns-git.txt}) is the behaviour captured before the
 * legacy GitProviderResource, ManageGitProviderResource, GameDomainResource and DnsZonePeerResource moved onto parts
 * (GitProviderParts, GameDomainResource's parts, DnsZonePeerParts); the zone, database and credentials tabs are
 * captured on their hosts' records. The one accepted difference is the connection test's route, declared as a placed
 * operation move. A failing comparison is a changed surface, never a file to refresh.
 */
class DatabaseDnsGitSurfacesBrowserTest extends HohenheimTestBase {

    private static final String PREFIX = "b13-surfaces-";
    private static final String ADMIN = HohenheimSlugs.ADMIN;
    private static final String MANAGE = HohenheimSlugs.MANAGE;
    private static final String GIT_PROVIDERS = HohenheimSlugs.GIT_PROVIDERS;
    private static final String GAME_DOMAINS = "game-domains";
    private static final String ZONE_PEERS = "dns-zone-peers";
    private static final String ZONES = HohenheimSlugs.DNS_ZONES;
    private static final String DATABASES = "databases";

    private static String sharedProviderId;
    private static String tenantProviderId;
    private static String gameDomainId;
    private static String siteId;
    private static String domainId;
    private static String backendId;
    private static String proxyId;
    private static String primaryZoneId;
    private static String replicaZoneId;
    private static String zonePeerId;
    private static String databaseId;
    private static AccessContext operator;
    private static AccessContext tenantGit;
    private static AccessContext tenantDatabaseView;
    private static AccessContext tenantDatabaseCredentials;
    private static AccessContext tenantEmpty;

    @BeforeAll
    static void seed() {
        int gitId = ApiSupport.user(PREFIX + "git@hohenheim.local", "B13 Git Tenant");
        int viewId = ApiSupport.user(PREFIX + "db-view@hohenheim.local", "B13 Database Viewer");
        int credentialsId = ApiSupport.user(PREFIX + "db-credentials@hohenheim.local", "B13 Database Credentials");
        int emptyId = ApiSupport.user(PREFIX + "empty@hohenheim.local", "B13 Empty Tenant");

        sharedProviderId = String.valueOf(provider(PREFIX + "shared", true));
        int tenantProvider = provider(PREFIX + "tenant", false);
        tenantProviderId = String.valueOf(tenantProvider);
        RecordGrants.grant(GrantSubjectType.USER, gitId, GitProviderModel.MODEL_ID, tenantProvider,
            HohenheimAccess.MANAGE, true);

        int site = site(PREFIX + "site");
        siteId = String.valueOf(site);
        int domain = domain(site, PREFIX + "game.surfaces.test");
        domainId = String.valueOf(domain);
        int backend = instance(PREFIX + "backend");
        backendId = String.valueOf(backend);
        int proxy = instance(PREFIX + "proxy");
        proxyId = String.valueOf(proxy);
        gameDomainId = String.valueOf(gameDomain(domain, backend, proxy));

        int peer = DnsFixtures.transferPeer(PREFIX + "peer", "192.0.2.53", 53);
        int primary = DnsFixtures.createZone(PREFIX + "primary.test", DnsZoneModel.ROLE_PRIMARY, null);
        primaryZoneId = String.valueOf(primary);
        replicaZoneId = String.valueOf(DnsFixtures.createZone(PREFIX + "replica.test", DnsZoneModel.ROLE_SECONDARY,
            peer));
        zonePeerId = String.valueOf(DnsFixtures.linkZonePeer(primary, peer));

        int database = database(PREFIX + "db");
        databaseId = String.valueOf(database);
        RecordGrants.grant(GrantSubjectType.USER, viewId, DatabaseModel.MODEL_ID, database, HohenheimAccess.VIEW,
            true);
        RecordGrants.grant(GrantSubjectType.USER, credentialsId, DatabaseModel.MODEL_ID, database,
            HohenheimAccess.VIEW, true);
        RecordGrants.grant(GrantSubjectType.USER, credentialsId, DatabaseModel.MODEL_ID, database,
            HohenheimAccess.CREDENTIALS, true);

        operator = access(operatorPrincipal());
        tenantGit = access(new UserPrincipal(gitId, "B13 Git Tenant"));
        tenantDatabaseView = access(new UserPrincipal(viewId, "B13 Database Viewer"));
        tenantDatabaseCredentials = access(new UserPrincipal(credentialsId, "B13 Database Credentials"));
        tenantEmpty = access(new UserPrincipal(emptyId, "B13 Empty Tenant"));
    }

    @Test
    void theDatabaseDnsAndGitEntriesOfferWhatTheyOfferedBeforeTheMove() {
        // The connection test moved from its legacy record action route onto the placed operation of the same id.
        SurfaceBaselines stored = SurfaceBaselines.load(DatabaseDnsGitSurfacesBrowserTest.class,
            "/panel-surfaces/database-dns-git.txt")
            .placedOperations(PlacedOperationMoves.of(GitProviderOperations.TEST_CONNECTION.id()));

        // 1. The admin entries for the operator, record-less and on each record; a tenant is refused the panel.
        for (String entry : List.of(GIT_PROVIDERS, GAME_DOMAINS, ZONE_PEERS)) {
            stored.check(capture(SurfaceCase.of(ADMIN, entry, "operator", operator)));
            stored.check(capture(SurfaceCase.of(ADMIN, entry, "tenant-git", tenantGit)
                .refusedFor(ZenitRefusalReason.FORBIDDEN)));
        }
        stored.check(capture(SurfaceCase.of(ADMIN, GIT_PROVIDERS, "operator", operator)
            .onRecord(sharedProviderId, "shared")));
        stored.check(capture(SurfaceCase.of(ADMIN, GIT_PROVIDERS, "operator", operator)
            .onRecord(tenantProviderId, "tenant")));
        stored.check(capture(SurfaceCase.of(ADMIN, GAME_DOMAINS, "operator", operator)
            .onRecord(gameDomainId, "mapping")));
        stored.check(capture(SurfaceCase.of(ADMIN, ZONE_PEERS, "operator", operator)
            .onRecord(zonePeerId, "link")));
        stored.check(capture(SurfaceCase.of(ADMIN, ZONE_PEERS, "operator", operator).named(ADMIN + "." + ZONE_PEERS
            + ".operator.prefill").withParameter(HohenheimParams.ZONE_ID_PREFILL.getName(), primaryZoneId)));
        stored.check(capture(SurfaceCase.of(ADMIN, GIT_PROVIDERS, "operator", operator)
            .selecting(List.of(sharedProviderId, tenantProviderId), "sel")));

        // 2. The tabs these entries' pages are: the zone file and secondaries tabs on a primary and a replica zone,
        //    the restore tab on a database.
        stored.check(capture(SurfaceCase.of(ADMIN, ZONES, "operator", operator)
            .onRecord(primaryZoneId, "primary")));
        stored.check(capture(SurfaceCase.of(ADMIN, ZONES, "operator", operator)
            .onRecord(replicaZoneId, "replica")));
        stored.check(capture(SurfaceCase.of(ADMIN, DATABASES, "operator", operator)
            .onRecord(databaseId, "database")));

        // 3. The /manage twins: the tenant's own git provider (the operator's shared one is out of its scope), and the
        //    database credentials tab for a VIEW and a CREDENTIALS delegate; a tenant holding nothing is refused.
        stored.check(capture(SurfaceCase.of(MANAGE, GIT_PROVIDERS, "tenant-git", tenantGit)));
        stored.check(capture(SurfaceCase.of(MANAGE, GIT_PROVIDERS, "tenant-git", tenantGit)
            .onRecord(tenantProviderId, "tenant")));
        stored.check(capture(SurfaceCase.of(MANAGE, GIT_PROVIDERS, "tenant-git", tenantGit)
            .onRecord(sharedProviderId, "shared")));
        stored.check(capture(SurfaceCase.of(MANAGE, GIT_PROVIDERS, "tenant-empty", tenantEmpty)
            .refusedFor(ZenitRefusalReason.FORBIDDEN)));
        stored.check(capture(SurfaceCase.of(MANAGE, DATABASES, "tenant-db-view", tenantDatabaseView)
            .onRecord(databaseId, "database")));
        stored.check(capture(SurfaceCase.of(MANAGE, DATABASES, "tenant-db-credentials", tenantDatabaseCredentials)
            .onRecord(databaseId, "database")));

        // 4. Every stored case matched exactly, and the tenant's git provider twin is the admin's on the same record
        //    but for its one listed difference.
        List<String> failures = new ArrayList<>();
        try {
            stored.finish();
        } catch (AssertionError mismatch) {
            failures.add(mismatch.getMessage());
        }
        try {
            PanelSurfaceComparer.assertNarrower(stored.captured(MANAGE + "." + GIT_PROVIDERS + ".tenant-git.tenant"),
                stored.captured(ADMIN + "." + GIT_PROVIDERS + ".operator.tenant"), gitProvidersTable());
        } catch (AssertionError difference) {
            failures.add(difference.getMessage());
        }
        if (!failures.isEmpty()) {
            throw new AssertionError(String.join("\n\n", failures));
        }
    }

    /**
     * The /manage git provider twin's deliberate differences from the admin provider resource: SHARED and the created
     * column are absent (narrower, so unlisted), and its name column is its own plain one.
     */
    private static TwinCorrespondence gitProvidersTable() {
        return TwinCorrespondence.between(MANAGE + "/" + GIT_PROVIDERS, ADMIN + "/" + GIT_PROVIDERS)
            .own("column name shown=true hidden=false sortable=false filterable=false copyable=false subtext="
                + " relation=false");
    }

    /**
     * A capture with every generated fixture id declared at the bindings a destination carries it, and the peer
     * Selects' choices (every stored peer, other classes' included) written as host elements.
     */
    private static PanelSurfaces capture(SurfaceCase fixture) {
        List<String> enabledPeers = new ArrayList<>();
        for (Row peer : Models.get(DnsPeerModel.class).findEnabled()) {
            enabledPeers.add(String.valueOf((Object) peer.get(DnsPeerModel.ID)));
        }
        List<String> allPeers = new ArrayList<>();
        for (Row peer : Models.get(DnsPeerModel.class).find().all()) {
            allPeers.add(String.valueOf((Object) peer.get(DnsPeerModel.ID)));
        }
        SurfaceCase keyed = fixture.hostOptions("peer_id", enabledPeers).hostOptions("primary_peer_id", allPeers)
            .key(GIT_PROVIDERS, "shared_provider", sharedProviderId)
            .key(GIT_PROVIDERS, "tenant_provider", tenantProviderId)
            .key(GAME_DOMAINS, "mapping", gameDomainId)
            .key(ZONE_PEERS, "link", zonePeerId)
            .key(ZONES, "primary_zone", primaryZoneId).key(ZONES, "replica_zone", replicaZoneId)
            .key(DATABASES, "database", databaseId)
            .key(HohenheimSlugs.SITES, "site", siteId)
            .key("domains", "domain", domainId)
            .key(HohenheimSlugs.INSTANCES, "backend", backendId).key(HohenheimSlugs.INSTANCES, "proxy", proxyId)
            .key("zone_id", "primary_zone", primaryZoneId)
            .key("parent", "primary_zone", primaryZoneId).key("parent", "replica_zone", replicaZoneId);
        return PanelSurfaces.capture(keyed);
    }

    private static AccessContext access(UserPrincipal principal) {
        return AccessContext.of(TenantConduits.stubFor(principal));
    }

    private static UserPrincipal operatorPrincipal() {
        Row admin = AuthModels.users().find().where(UserModel.EMAIL.eq("test@hohenheim.local")).first();
        return new UserPrincipal(admin.get(UserModel.ID), "Test Admin");
    }

    private static int provider(String name, boolean shared) {
        Model providers = Models.get(GitProviderModel.class);
        Row row = providers.createEmptyRow();
        row.set(GitProviderModel.NAME, name);
        row.set(GitProviderModel.KIND, GiteaProviderKind.ID.toString());
        row.set(GitProviderModel.BASE_URL, "https://git.surfaces.test");
        row.set(GitProviderModel.SHARED, shared);
        row.set(GitProviderModel.ACCESS_TOKEN, "token-" + name);
        providers.save(row);
        return row.get(GitProviderModel.ID);
    }

    private static int site(String slug) {
        Model sites = Models.get(SiteModel.class);
        Row row = sites.createEmptyRow();
        row.set(SiteModel.NAME, slug);
        row.set(SiteModel.SLUG, slug);
        row.set(SiteModel.UPSTREAM_KIND, "hohenheim:static");
        row.set(SiteModel.SETTINGS, Map.of("root_path", "/tmp"));
        row.set(SiteModel.STATUS, "active");
        row.set(SiteModel.ENABLED, false);
        sites.save(row);
        return row.get(SiteModel.ID);
    }

    private static int domain(int site, String hostname) {
        Model domains = Models.get(SiteDomainModel.class);
        Row row = domains.createEmptyRow();
        row.set(SiteDomainModel.SITE_ID, site);
        row.set(SiteDomainModel.HOSTNAME, hostname);
        row.set(SiteDomainModel.MATCH_TYPE, SiteDomainModel.MATCH_EXACT);
        row.set(SiteDomainModel.FORCE_SSL, false);
        domains.save(row);
        return row.get(SiteDomainModel.ID);
    }

    private static int instance(String name) {
        Model instances = Models.get(InstanceModel.class);
        Row row = instances.createEmptyRow();
        row.set(InstanceModel.NAME, name);
        row.set(InstanceModel.KIND, "hohenheim:docker_container");
        row.set(InstanceModel.SETTINGS, new LinkedHashMap<>(
            Map.of("image", "alpine", "tag", "latest", "command", "sleep 300")));
        row.set(InstanceModel.STATUS, InstanceModel.STATUS_CREATED);
        instances.save(row);
        return row.get(InstanceModel.ID);
    }

    private static int gameDomain(int domain, int backend, int proxy) {
        Model mappings = Models.get(GameDomainModel.class);
        Row row = mappings.createEmptyRow();
        row.set(GameDomainModel.SITE_DOMAIN_ID, domain);
        row.set(GameDomainModel.BACKEND_INSTANCE_ID, backend);
        row.set(GameDomainModel.PROXY_INSTANCE_ID, proxy);
        row.set(GameDomainModel.BACKEND_PORT, 25565);
        row.set(GameDomainModel.ENABLED, false);
        mappings.save(row);
        return row.get(GameDomainModel.ID);
    }

    private static int database(String name) {
        Model databases = Models.get(DatabaseModel.class);
        Row row = databases.createEmptyRow();
        row.set(DatabaseModel.NAME, name);
        row.set(DatabaseModel.ENGINE, "postgres");
        row.set(DatabaseModel.DB_USER, "surfaces");
        row.set(DatabaseModel.DB_PASSWORD, "s3cret");
        row.set(DatabaseModel.DB_NAME, "surfaces");
        row.set(DatabaseModel.STATUS, DatabaseModel.STATUS_ACTIVE);
        databases.save(row);
        return row.get(DatabaseModel.ID);
    }
}
