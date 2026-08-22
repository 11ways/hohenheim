package be.elevenways.hohenheim.test.game;

import be.elevenways.hohenheim.server.ControllerScope;
import be.elevenways.hohenheim.model.DnsRecordModel;
import be.elevenways.hohenheim.model.DnsZoneModel;
import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.hohenheim.model.GameDomainModel;
import be.elevenways.hohenheim.model.ReleasedRouteClaimModel;
import be.elevenways.hohenheim.model.InstanceFileModel;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.InstanceVariableModel;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.model.SiteDomainModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.ports.PortLedger;
import be.elevenways.hohenheim.server.HohenheimDatabase;
import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.hohenheim.server.dns.DnsZoneStore;
import be.elevenways.hohenheim.server.game.GameDomains;
import be.elevenways.hohenheim.server.game.VelocityConfigs;
import be.elevenways.hohenheim.server.instance.InstanceVariables;
import be.elevenways.hohenheim.test.HohenheimTestBase;
import be.elevenways.zenit.auth.model.GrantSubjectType;
import be.elevenways.zenit.auth.model.UserModel;
import be.elevenways.zenit.auth.model.UserPrincipal;
import be.elevenways.zenit.auth.server.AuthModels;
import be.elevenways.zenit.auth.server.RecordGrants;
import be.elevenways.zenit.common.conduit.Conduit;
import be.elevenways.zenit.common.conduit.ConduitAttributes;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.routing.BodyDefinition;
import be.elevenways.zenit.common.routing.ParameterDefinition;
import be.elevenways.zenit.common.api.ResponseCarrier;
import be.elevenways.zenit.common.result.ActionResult;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.common.security.Principal;
import be.elevenways.zenit.common.validation.Violations;
import be.elevenways.protoblast.common.key.IdentifierKey;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer.OrderAnnotation;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * The game-domains security core and its materialization, driven straight at the write
 * funnel: authority over BOTH records, generated rows that are attributed, self-scoped
 * and immutable to everyone else, and cleanup that removes only its own output.
 */
@TestMethodOrder(OrderAnnotation.class)
class GameDomainAuthorityTest extends HohenheimTestBase {

    private static final String HOST = "play.gamedomain.test";
    private static final String ZONE = "gamedomain.test";
    private static final String PROXY_SECRET = "fixture-forwarding-secret-value";

    private static int siteId;
    private static int domainId;
    private static int backendId;
    private static int secondBackendId;
    private static int proxyId;
    private static int tenantInstancesId;
    private static int tenantDomainId;
    private static int zoneId;
    private static int mappingId;

    @BeforeAll
    static void fixtures() {
        var sites = Models.get(SiteModel.class);
        Row site = sites.createEmptyRow();
        site.set(SiteModel.NAME, "Game site");
        site.set(SiteModel.SLUG, "game-site");
        site.set(SiteModel.UPSTREAM_KIND, "hohenheim:static");
        site.set(SiteModel.SETTINGS, Map.of("root_path", "/tmp"));
        site.set(SiteModel.STATUS, "active");
        site.set(SiteModel.ENABLED, true);
        sites.save(site);
        siteId = site.get(SiteModel.ID);

        var domains = Models.get(SiteDomainModel.class);
        Row domain = domains.createEmptyRow();
        domain.set(SiteDomainModel.SITE_ID, siteId);
        domain.set(SiteDomainModel.HOSTNAME, HOST);
        domain.set(SiteDomainModel.MATCH_TYPE, SiteDomainModel.MATCH_EXACT);
        domains.save(domain);
        domainId = domain.get(SiteDomainModel.ID);

        backendId = instance("game-backend");
        secondBackendId = instance("game-backend-2");
        proxyId = instance("game-proxy");

        tenantInstancesId = user("tenant-instances@gamedomain.test");
        tenantDomainId = user("tenant-domain@gamedomain.test");

        RecordGrants.grant(GrantSubjectType.USER, tenantInstancesId, InstanceModel.MODEL_ID, backendId,
            HohenheimAccess.MANAGE, true);
        RecordGrants.grant(GrantSubjectType.USER, tenantInstancesId, InstanceModel.MODEL_ID, secondBackendId,
            HohenheimAccess.MANAGE, true);
        RecordGrants.grant(GrantSubjectType.USER, tenantInstancesId, InstanceModel.MODEL_ID, proxyId,
            HohenheimAccess.MANAGE, true);
        RecordGrants.grant(GrantSubjectType.USER, tenantDomainId, SiteModel.MODEL_ID, siteId,
            HohenheimAccess.MANAGE, true);

        var zones = Models.get(DnsZoneModel.class);
        Row zone = zones.createEmptyRow();
        zone.set(DnsZoneModel.ORIGIN, ZONE);
        zone.set(DnsZoneModel.ENABLED, true);
        zone.set(DnsZoneModel.DEFAULT_TTL, 3600);
        zone.set(DnsZoneModel.NEGATIVE_TTL, 300);
        zone.set(DnsZoneModel.SOA_REFRESH, 7200);
        zone.set(DnsZoneModel.SOA_RETRY, 3600);
        zone.set(DnsZoneModel.SOA_EXPIRE, 1209600);
        zones.save(zone);
        zoneId = zone.get(DnsZoneModel.ID);
        DnsZoneStore.INSTANCE.reload();

        // The proxy's forwarding secret (what a Velocity template's secret variable
        // mints in production).
        var variables = Models.get(InstanceVariableModel.class);
        Row secret = variables.createEmptyRow();
        secret.set(InstanceVariableModel.INSTANCE_ID, proxyId);
        secret.set(InstanceVariableModel.KEY, GameDomains.PROXY_SECRET_KEY);
        secret.set(InstanceVariableModel.KIND, InstanceVariableModel.KIND_SECRET);
        secret.set(InstanceVariableModel.SECRET_VALUE, PROXY_SECRET);
        variables.save(secret);

        // The proxy's PUBLIC pre-allocated port (what a public-exposure deploy claims
        // BEFORE create). DNS generation deliberately rides ONLY a public claim: a
        // loopback port would make every generated row a dangling pointer.
        PortLedger.claimPreallocated(ServerModel.canonicalServerId(null), "", 25599,
            "tcp", InstanceModel.MODEL_ID, proxyId, "game-domain-test");
    }

    private static int instance(String name) {
        var model = Models.get(InstanceModel.class);
        Row row = model.createEmptyRow();
        row.set(InstanceModel.NAME, name);
        row.set(InstanceModel.KIND, "hohenheim:docker_container");
        row.set(InstanceModel.STATUS, InstanceModel.STATUS_CREATED);
        row.set(InstanceModel.SETTINGS, Map.of("image", "alpine", "tag", "latest"));
        model.save(row);
        return row.get(InstanceModel.ID);
    }

    private static int user(String email) {
        Row user = AuthModels.users().createEmptyRow();
        user.set(UserModel.EMAIL, email);
        user.set(UserModel.DISPLAY_NAME, email);
        user.set(UserModel.ENABLED, true);
        user.set(UserModel.CREATED_AT, Instant.now());
        user.set(UserModel.UPDATED_AT, Instant.now());
        AuthModels.users().save(user);
        return user.get(UserModel.ID);
    }

    private static Row mappingRow(int domain, int backend, int proxy) {
        Row row = Models.get(GameDomainModel.class).createEmptyRow();
        row.set(GameDomainModel.SITE_DOMAIN_ID, domain);
        row.set(GameDomainModel.BACKEND_INSTANCE_ID, backend);
        row.set(GameDomainModel.PROXY_INSTANCE_ID, proxy);
        row.set(GameDomainModel.BACKEND_PORT, 25565);
        row.set(GameDomainModel.ENABLED, true);
        return row;
    }

    private static long mappingCount() {
        return Models.get(GameDomainModel.class).find().count();
    }

    private static Row generatedFileRow() {
        return Models.get(InstanceFileModel.class).find()
            .where(InstanceFileModel.INSTANCE_ID.eq(proxyId))
            .and(InstanceFileModel.CONTAINER_PATH.eq(VelocityConfigs.CONFIG_PATH))
            .first();
    }

    private static Row generatedSrvRow() {
        return generatedRow(DnsRecordModel.TYPE_SRV);
    }

    private static Row generatedRow(String type) {
        return Models.get(DnsRecordModel.class).find()
            .where(DnsRecordModel.GENERATED_BY.eq(GameDomains.SOURCE))
            .and(DnsRecordModel.GENERATED_FOR_MODEL.eq(GameDomainModel.MODEL_ID.toString()))
            .and(DnsRecordModel.TYPE.eq(type))
            .first();
    }

    @Test
    @Order(1)
    void authorityOverBothRecordsIsRequiredAndARefusalCreatesNothing() {
        // 1. A principal with manage on BOTH instances but NOT the domain's site is
        //    refused, and the mapping does NOT exist afterwards.
        AccessContext instancesOnly = contextFor(
            new UserPrincipal(tenantInstancesId, "Tenant Instances"));
        Throwable refusedDomain = catchThrowable(() ->
            GameDomains.applyAuthorized(instancesOnly, mappingRow(domainId, backendId, proxyId)));
        assertThat(refusedDomain)
            .as("step 1: instance authority alone must not create a mapping")
            .isInstanceOf(Violations.class);
        assertThat(mappingCount())
            .as("step 1: the refused mapping was NOT created")
            .isZero();

        // 2. A principal with manage on the domain's site but NOT the instances is
        //    refused the same way.
        AccessContext domainOnly = contextFor(
            new UserPrincipal(tenantDomainId, "Tenant Domain"));
        Throwable refusedInstance = catchThrowable(() ->
            GameDomains.applyAuthorized(domainOnly, mappingRow(domainId, backendId, proxyId)));
        assertThat(refusedInstance)
            .as("step 2: domain authority alone must not create a mapping")
            .isInstanceOf(Violations.class);
        assertThat(mappingCount())
            .as("step 2: the refused mapping was NOT created")
            .isZero();

        // 3. Granting the SAME principal the missing site authority makes the identical
        //    call succeed -- the refusal above was the authority check and nothing else.
        RecordGrants.grant(GrantSubjectType.USER, tenantInstancesId, SiteModel.MODEL_ID, siteId,
            HohenheimAccess.MANAGE, true);
        Row mapping = GameDomains.applyAuthorized(instancesOnly,
            mappingRow(domainId, backendId, proxyId));
        mappingId = mapping.get(GameDomainModel.ID);
        assertThat(mappingCount())
            .as("step 3: with authority over BOTH records the mapping is created")
            .isEqualTo(1);
    }

    @Test
    @Order(2)
    void materializationGeneratesAttributedConfigDnsAndSecret() {
        // 1. The proxy's velocity.toml row is GENERATED and attributed.
        Row file = generatedFileRow();
        assertThat(file).as("step 1: the generated config row exists").isNotNull();
        assertThat((String) file.get(InstanceFileModel.GENERATED_BY))
            .as("step 1: the config row carries the game_domain source")
            .isEqualTo(GameDomains.SOURCE);
        assertThat((String) file.get(InstanceFileModel.GENERATED_FOR_MODEL))
            .as("step 1: the config row is anchored to the proxy instance")
            .isEqualTo(InstanceModel.MODEL_ID.toString());
        assertThat((Integer) file.get(InstanceFileModel.GENERATED_FOR_ID))
            .isEqualTo(proxyId);
        String content = file.get(InstanceFileModel.CONTENT);
        assertThat(content)
            .as("step 1: forced-hosts maps the hostname onto the backend's link address")
            .contains("\"" + HOST + "\" = [\"backend-" + backendId + "\"]")
            .contains("backend-" + backendId + " = \""
                + ControllerScope.handle(ControllerScope.KIND_INSTANCE, backendId) + ":25565\"");

        // 2. The generated SRV row exists, attributed to the MAPPING, with the proxy's
        //    PUBLIC pre-allocated port and the hostname as target.
        Row srv = generatedSrvRow();
        assertThat(srv).as("step 2: the generated SRV row exists").isNotNull();
        assertThat((String) srv.get(DnsRecordModel.NAME))
            .as("step 2: the owner name is the minecraft SRV of the mapped host")
            .isEqualTo("_minecraft._tcp.play");
        assertThat(DnsRecordModel.portOf(srv))
            .as("step 2: the SRV port is the proxy's PUBLIC pre-allocated port")
            .isEqualTo(25599);
        assertThat((String) srv.get(DnsRecordModel.VALUE)).isEqualTo(HOST);
        assertThat((Integer) srv.get(DnsRecordModel.GENERATED_FOR_ID)).isEqualTo(mappingId);
        assertThat((Integer) srv.get(DnsRecordModel.ZONE_ID)).isEqualTo(zoneId);

        // 3. The forwarding secret was handed to the backend as an ENCRYPTED variable:
        //    the model read decrypts it to the proxy's value, the raw column is a
        //    zenc$ envelope that never contains the plaintext.
        assertThat(new InstanceVariables().valuesFor(backendId)
            .get(GameDomains.BACKEND_SECRET_KEY))
            .as("step 3: the backend received the proxy's forwarding secret")
            .isEqualTo(PROXY_SECRET);
        var raw = HohenheimDatabase.datasource().rawQuery(
            "SELECT secret_value AS c FROM instance_variables WHERE instance_id = "
                + backendId + " AND key = '" + GameDomains.BACKEND_SECRET_KEY + "'");
        String envelope = String.valueOf(raw.get(0).get("c"));
        assertThat(envelope)
            .as("step 3: the handed-off secret is CIPHERTEXT at rest")
            .startsWith("zenc$")
            .doesNotContain(PROXY_SECRET);

        // 4. Generated rows are read-only to every non-system caller: an edit and a
        //    delete of the config row are refused, same for the SRV row.
        Row fileEdit = generatedFileRow();
        fileEdit.set(InstanceFileModel.CONTENT, "tampered");
        Throwable editRefused = catchThrowable(
            () -> Models.get(InstanceFileModel.class).save(fileEdit));
        assertThat(editRefused)
            .as("step 4: editing the generated config row is refused")
            .isInstanceOf(Violations.class);
        Throwable deleteRefused = catchThrowable(
            () -> Models.get(InstanceFileModel.class).delete(generatedFileRow()));
        assertThat(deleteRefused)
            .as("step 4: deleting the generated config row is refused")
            .isInstanceOf(Violations.class);
        Row srvEdit = generatedSrvRow();
        srvEdit.set(DnsRecordModel.DATA,
            DnsRecordModel.dataFor(DnsRecordModel.TYPE_SRV, 0, 5, 1));
        Throwable srvRefused = catchThrowable(
            () -> Models.get(DnsRecordModel.class).save(srvEdit));
        assertThat(srvRefused)
            .as("step 4: editing the generated SRV row is refused")
            .isInstanceOf(Violations.class);

        // 5. Materialization on CHANGE: pointing the mapping at the second backend
        //    re-renders the config (new backend, old one gone) and keeps ONE SRV row.
        AccessContext actor = contextFor(
            new UserPrincipal(tenantInstancesId, "Tenant Instances"));
        Row change = Models.get(GameDomainModel.class).findById(mappingId);
        change.set(GameDomainModel.BACKEND_INSTANCE_ID, secondBackendId);
        GameDomains.applyAuthorized(actor, change);
        String changed = generatedFileRow().get(InstanceFileModel.CONTENT);
        assertThat(changed)
            .as("step 5: the re-rendered config aims the host at the NEW backend")
            .contains("\"" + HOST + "\" = [\"backend-" + secondBackendId + "\"]")
            .doesNotContain("backend-" + backendId + " =");
        assertThat(Models.get(DnsRecordModel.class).find()
            .where(DnsRecordModel.GENERATED_BY.eq(GameDomains.SOURCE)).count())
            .as("step 5: reconciliation keeps exactly one generated SRV row")
            .isEqualTo(1);
    }

    @Test
    @Order(3)
    void aRecordsRideTheServerAddressAuthorityAndOnlyIt() {
        var records = Models.get(DnsRecordModel.class);
        var servers = Models.get(ServerModel.class);
        int serverId = ServerModel.canonicalServerId(null);

        // 1. No address declared: the SRV exists (public port), NO A/AAAA is generated
        //    -- an A record pointing at an address nothing declared would be the
        //    reports-success shape by name.
        assertThat(generatedRow(DnsRecordModel.TYPE_A))
            .as("step 1: no A row without a declared server address")
            .isNull();

        // 2. A hand-authored A row at the SAME owner name, BEFORE any generation: the
        //    reconciler must never adopt or delete it.
        Row handAuthored = records.createEmptyRow();
        handAuthored.set(DnsRecordModel.ZONE_ID, zoneId);
        handAuthored.set(DnsRecordModel.NAME, "play");
        handAuthored.set(DnsRecordModel.TYPE, DnsRecordModel.TYPE_A);
        handAuthored.set(DnsRecordModel.VALUE, "192.0.2.200");
        handAuthored.set(DnsRecordModel.ENABLED, true);
        records.save(handAuthored);
        int handAuthoredId = handAuthored.get(DnsRecordModel.ID);

        // 3. An invalid declared address is REFUSED on the model funnel itself.
        Row server = servers.findById(serverId);
        server.set(ServerModel.PUBLIC_IPV4, "not-an-address");
        Throwable invalid = catchThrowable(() -> servers.save(server));
        assertThat(invalid)
            .as("step 3: a non-literal server address is refused")
            .isInstanceOf(Violations.class);

        // 4. Declaring real addresses generates the A and AAAA rows: attributed to the
        //    mapping, owner name = the mapped host, values = the DECLARED addresses.
        Row declare = servers.findById(serverId);
        declare.set(ServerModel.PUBLIC_IPV4, "192.0.2.10");
        declare.set(ServerModel.PUBLIC_IPV6, "2001:db8::10");
        servers.save(declare);
        Row a = generatedRow(DnsRecordModel.TYPE_A);
        assertThat(a).as("step 4: the generated A row exists").isNotNull();
        assertThat((String) a.get(DnsRecordModel.NAME)).isEqualTo("play");
        assertThat((String) a.get(DnsRecordModel.VALUE))
            .as("step 4: the A value is the DECLARED public IPv4")
            .isEqualTo("192.0.2.10");
        assertThat((Integer) a.get(DnsRecordModel.GENERATED_FOR_ID)).isEqualTo(mappingId);
        Row aaaa = generatedRow(DnsRecordModel.TYPE_AAAA);
        assertThat(aaaa).as("step 4: the generated AAAA row exists").isNotNull();
        assertThat((String) aaaa.get(DnsRecordModel.VALUE)).isEqualTo("2001:db8::10");

        // 5. The host's address CHANGES: the generated value moves with the declaration
        //    (a stale A record is a dangling pointer); the hand-authored row is
        //    untouched either way.
        Row move = servers.findById(serverId);
        move.set(ServerModel.PUBLIC_IPV4, "192.0.2.20");
        servers.save(move);
        assertThat((String) generatedRow(DnsRecordModel.TYPE_A).get(DnsRecordModel.VALUE))
            .as("step 5: the generated A row moved with the host address")
            .isEqualTo("192.0.2.20");
        assertThat((String) records.findById(handAuthoredId).get(DnsRecordModel.VALUE))
            .as("step 5: the hand-authored A row at the same name was never adopted")
            .isEqualTo("192.0.2.200");

        // 6. The DNS gate is the PUBLIC claim: swap the proxy's public claim for a
        //    loopback one and every generated DNS row comes down -- SRV included --
        //    because nothing outside this host could reach what they point at.
        PortLedger.releaseOwnerFully(InstanceModel.MODEL_ID, proxyId);
        PortLedger.recordObserved(serverId, "127.0.0.1", 25599, "tcp",
            InstanceModel.MODEL_ID, proxyId, "game-domain-test");
        GameDomains.afterServerAddressChange(serverId);
        assertThat(generatedSrvRow())
            .as("step 6: a loopback-published proxy generates NO SRV row")
            .isNull();
        assertThat(generatedRow(DnsRecordModel.TYPE_A))
            .as("step 6: a loopback-published proxy generates NO A row")
            .isNull();
        assertThat(records.findById(handAuthoredId))
            .as("step 6: the hand-authored row survives the takedown")
            .isNotNull();

        // 7. Restore the public claim, clear the declared addresses: the SRV returns,
        //    the A/AAAA stay down (no address authority), the hand row still stands.
        PortLedger.releaseOwnerFully(InstanceModel.MODEL_ID, proxyId);
        PortLedger.claimPreallocated(serverId, "", 25599, "tcp",
            InstanceModel.MODEL_ID, proxyId, "game-domain-test");
        Row clear = servers.findById(serverId);
        clear.set(ServerModel.PUBLIC_IPV4, null);
        clear.set(ServerModel.PUBLIC_IPV6, null);
        servers.save(clear);
        assertThat(generatedSrvRow())
            .as("step 7: the SRV row returns with the public claim")
            .isNotNull();
        assertThat(generatedRow(DnsRecordModel.TYPE_A))
            .as("step 7: clearing the declared address takes the A row down")
            .isNull();
        assertThat(generatedRow(DnsRecordModel.TYPE_AAAA))
            .as("step 7: clearing the declared address takes the AAAA row down")
            .isNull();
        records.delete(handAuthoredId);
    }

    /** The admin surface renders: list page and create form answer 200 for the admin. */
    @Test
    @Order(4)
    void adminResourceRenders() throws Exception {
        java.net.http.HttpClient client = java.net.http.HttpClient.newHttpClient();
        for (String path : new String[] {"/admin/game-domains", "/admin/game-domains/new"}) {
            java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder()
                .uri(java.net.URI.create("http://localhost:" + getServerPort() + path))
                .header("Cookie", be.elevenways.zenit.auth.server.AuthCookieSupport
                    .sessionCookieName() + "=" + sessionToken)
                .build();
            java.net.http.HttpResponse<String> response = client.send(request,
                java.net.http.HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode())
                .as("admin page " + path + " renders")
                .isEqualTo(200);
        }
    }

    /**
     * A game mapping MINTS no hostname: it binds to an existing exact site_domain row, so
     * the released-claim quarantine judges it once, on that row's own write. Driven because
     * "structurally guarded by the same write hook" is a claim, not a verification.
     */
    @Test
    @Order(6)
    void aGameMappingCannotIntroduceAHostnameAndInheritsTheDomainRowsQuarantine() {
        Integer savedWindow = HohenheimSettings.VALUES.getValue(
            HohenheimSettings.Security.RELEASE_QUARANTINE_DAYS);
        try {
            aGameMappingInheritsTheDomainRowsQuarantine();
        } finally {
            HohenheimSettings.VALUES.setValue(
                HohenheimSettings.Security.RELEASE_QUARANTINE_DAYS, savedWindow);
        }
    }

    private void aGameMappingInheritsTheDomainRowsQuarantine() {
        HohenheimSettings.VALUES.setValue(HohenheimSettings.Security.RELEASE_QUARANTINE_DAYS, 30);
        String released = "arena.gamedomain.test";
        var domains = Models.get(SiteDomainModel.class);
        var sites = Models.get(SiteModel.class);

        // 1. A TENANT-owned site serves the hostname and then abandons the row.
        Row victimSite = sites.createEmptyRow();
        victimSite.set(SiteModel.NAME, "Arena site");
        victimSite.set(SiteModel.SLUG, "arena-site");
        victimSite.set(SiteModel.UPSTREAM_KIND, "hohenheim:static");
        victimSite.set(SiteModel.SETTINGS, Map.of("root_path", "/tmp"));
        victimSite.set(SiteModel.STATUS, "active");
        victimSite.set(SiteModel.ENABLED, true);
        sites.save(victimSite);
        int victimSiteId = victimSite.get(SiteModel.ID);
        int arenaTenant = user("tenant-arena@gamedomain.test");
        RecordGrants.grant(GrantSubjectType.USER, arenaTenant, SiteModel.MODEL_ID, victimSiteId,
            HohenheimAccess.MANAGE, true);
        Row victimDomain = domains.createEmptyRow();
        victimDomain.set(SiteDomainModel.SITE_ID, victimSiteId);
        victimDomain.set(SiteDomainModel.HOSTNAME, released);
        victimDomain.set(SiteDomainModel.MATCH_TYPE, SiteDomainModel.MATCH_EXACT);
        domains.save(victimDomain);
        domains.delete(victimDomain.get(SiteDomainModel.ID));
        Row ledgered = Models.get(ReleasedRouteClaimModel.class).find()
            .where(ReleasedRouteClaimModel.HOSTNAME.eq(released)).first();
        assertThat(ledgered).as("step 1: abandoning the domain row is ledgered").isNotNull();
        assertThat((String) ledgered.get(ReleasedRouteClaimModel.FORMER_SUBJECTS))
            .as("step 1: under the TENANT, not an empty operator set")
            .isEqualTo("user:" + arenaTenant);

        // 2. The game tier owns no hostname of its own: a mapping can only point at a
        //    site_domain row, and pointing at one that does not exist is refused by name.
        Row danglingMapping = mappingRow(999_999, backendId, proxyId);
        AccessContext actor = contextFor(
            new UserPrincipal(tenantInstancesId, "Tenant Instances"));
        assertThat(catchThrowable(() -> GameDomains.applyAuthorized(actor, danglingMapping)))
            .as("step 2: a mapping cannot conjure the hostname it serves")
            .isInstanceOf(Violations.class);

        // 3. So the ONLY way to serve the released hostname through the game tier is to
        //    create the site_domain row first -- and the quarantine refuses that.
        Row seize = domains.createEmptyRow();
        seize.set(SiteDomainModel.SITE_ID, siteId);
        seize.set(SiteDomainModel.HOSTNAME, released);
        seize.set(SiteDomainModel.MATCH_TYPE, SiteDomainModel.MATCH_EXACT);
        assertThat(catchThrowable(() -> domains.save(seize)))
            .as("step 3: another owner's site cannot re-claim the released game hostname")
            .isInstanceOf(Violations.class);
        assertThat(domains.find().where(SiteDomainModel.HOSTNAME.eq(released)).all())
            .as("step 3: and no domain row for it exists to hang a mapping on").isEmpty();
    }

    @Test
    @Order(5)
    void cleanupRemovesOnlyItsOwnOutput() {
        // 1. A hand-authored SRV row beside the generated one (same zone, own name).
        var records = Models.get(DnsRecordModel.class);
        Row foreign = records.createEmptyRow();
        foreign.set(DnsRecordModel.ZONE_ID, zoneId);
        foreign.set(DnsRecordModel.NAME, "_minecraft._tcp.manual");
        foreign.set(DnsRecordModel.TYPE, DnsRecordModel.TYPE_SRV);
        foreign.set(DnsRecordModel.DATA,
            DnsRecordModel.dataFor(DnsRecordModel.TYPE_SRV, 0, 0, 25565));
        foreign.set(DnsRecordModel.VALUE, "manual." + ZONE);
        foreign.set(DnsRecordModel.ENABLED, true);
        records.save(foreign);
        int foreignId = foreign.get(DnsRecordModel.ID);

        // 2. Deleting the mapping removes the generated config row and the generated
        //    SRV row -- and ONLY those: the hand-authored row survives.
        AccessContext actor = contextFor(
            new UserPrincipal(tenantInstancesId, "Tenant Instances"));
        GameDomains.deleteAuthorized(actor, mappingId);
        assertThat(mappingCount()).as("step 2: the mapping is gone").isZero();
        assertThat(generatedFileRow())
            .as("step 2: the generated config row died with the last mapping")
            .isNull();
        assertThat(generatedSrvRow())
            .as("step 2: the generated SRV row died with the mapping")
            .isNull();
        assertThat(records.findById(foreignId))
            .as("step 2: the hand-authored SRV row was never adopted or deleted")
            .isNotNull();

        // 3. A hand-authored instance_files row on the generated PATH refuses the next
        //    mapping outright (never adopted, never overwritten) -- and nothing lands.
        var files = Models.get(InstanceFileModel.class);
        Row squatter = files.createEmptyRow();
        squatter.set(InstanceFileModel.INSTANCE_ID, proxyId);
        squatter.set(InstanceFileModel.CONTAINER_PATH, VelocityConfigs.CONFIG_PATH);
        squatter.set(InstanceFileModel.CONTENT, "# operator-authored velocity.toml");
        files.save(squatter);
        Throwable conflict = catchThrowable(() -> GameDomains.applyAuthorized(actor,
            mappingRow(domainId, backendId, proxyId)));
        assertThat(conflict)
            .as("step 3: a hand-authored file on the generated path refuses the mapping")
            .isInstanceOf(Violations.class);
        assertThat(mappingCount())
            .as("step 3: the refused mapping was NOT created")
            .isZero();
        assertThat((String) files.findById(squatter.get(InstanceFileModel.ID))
            .get(InstanceFileModel.CONTENT))
            .as("step 3: the hand-authored file is untouched")
            .isEqualTo("# operator-authored velocity.toml");
        files.delete(squatter);

        // 4. The domain-row cascade: recreate the mapping, then delete the DOMAIN row;
        //    the mapping and its generated output die with it.
        Row recreated = GameDomains.applyAuthorized(actor,
            mappingRow(domainId, backendId, proxyId));
        assertThat(generatedFileRow())
            .as("step 4: the recreated mapping re-materialized its config")
            .isNotNull();
        Models.get(SiteDomainModel.class).find()
            .where(SiteDomainModel.ID.eq(domainId)).delete();
        assertThat(Models.get(GameDomainModel.class)
            .findById(recreated.get(GameDomainModel.ID)))
            .as("step 4: the mapping died with its domain row")
            .isNull();
        assertThat(generatedFileRow())
            .as("step 4: the generated config died with the mapping")
            .isNull();
        assertThat(generatedSrvRow())
            .as("step 4: the generated SRV row died with the mapping")
            .isNull();
        assertThat(records.findById(foreignId))
            .as("step 4: the hand-authored SRV row STILL survives every cascade")
            .isNotNull();
    }

    /** Production-shaped context for a bare principal (the CapabilityWalkTest idiom). */
    private static AccessContext contextFor(Principal principal) {
        StubConduit conduit = new StubConduit();
        conduit.setAttribute(ConduitAttributes.PRINCIPAL, principal);
        return AccessContext.of(conduit);
    }

    /** Attribute-only Conduit; every request-flavored method throws. */
    private static final class StubConduit implements Conduit {

        private final Map<IdentifierKey<?>, Object> attributes = new HashMap<>();

        @Override
        @SuppressWarnings("unchecked")
        public <T> T getAttribute(IdentifierKey<T> key) {
            return (T) this.attributes.get(key);
        }

        @Override
        public <T> void setAttribute(IdentifierKey<T> key, T value) {
            if (value == null) {
                this.attributes.remove(key);
            } else {
                this.attributes.put(key, value);
            }
        }

        @Override
        public ResponseCarrier getResponseCarrier() {
            throw new UnsupportedOperationException("stub carries no response");
        }

        @Override
        public <T> T getParameter(ParameterDefinition<T> parameter) {
            throw new UnsupportedOperationException("stub carries no request");
        }

        @Override
        public <T> T getBody(BodyDefinition<T> definition) {
            throw new UnsupportedOperationException("stub carries no request");
        }

        @Override
        public boolean isHawkeyeRequest() {
            return false;
        }

        @Override
        public void enableStreamingResponse() {
            throw new UnsupportedOperationException("stub carries no response");
        }

        @Override
        public void notFound() {
            throw new UnsupportedOperationException("stub carries no response");
        }

        @Override
        public void forbidden() {
            throw new UnsupportedOperationException("stub carries no response");
        }

        @Override
        public void badRequest() {
            throw new UnsupportedOperationException("stub carries no response");
        }

        @Override
        public void badRequest(String message) {
            throw new UnsupportedOperationException("stub carries no response");
        }

        @Override
        public <T> ActionResult<T> softRedirect(String url) {
            throw new UnsupportedOperationException("stub carries no response");
        }

        @Override
        public <T> ActionResult<T> hardRedirect(String url) {
            throw new UnsupportedOperationException("stub carries no response");
        }
    }
}
