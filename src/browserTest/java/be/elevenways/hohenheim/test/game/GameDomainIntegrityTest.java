package be.elevenways.hohenheim.test.game;

import be.elevenways.hohenheim.model.DnsRecordModel;
import be.elevenways.hohenheim.model.DnsZoneModel;
import be.elevenways.hohenheim.model.GameDomainModel;
import be.elevenways.hohenheim.model.InstanceFileModel;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.model.SiteDomainModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.ports.PortLedger;
import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.hohenheim.server.dns.DnsZoneStore;
import be.elevenways.hohenheim.server.game.GameDomains;
import be.elevenways.hohenheim.server.game.VelocityConfigs;
import be.elevenways.hohenheim.server.orm.GeneratedRows;
import be.elevenways.hohenheim.test.HohenheimTestBase;
import be.elevenways.protoblast.common.key.IdentifierKey;
import be.elevenways.protoblast.common.time.Now;
import be.elevenways.zenit.auth.model.GrantSubjectType;
import be.elevenways.zenit.auth.model.UserModel;
import be.elevenways.zenit.auth.model.UserPrincipal;
import be.elevenways.zenit.auth.server.AuthModels;
import be.elevenways.zenit.auth.server.RecordGrants;
import be.elevenways.zenit.common.api.ResponseCarrier;
import be.elevenways.zenit.common.conduit.Conduit;
import be.elevenways.zenit.common.conduit.ConduitAttributes;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.result.ActionResult;
import be.elevenways.zenit.common.routing.BodyDefinition;
import be.elevenways.zenit.common.routing.ParameterDefinition;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.common.validation.Violations;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer.OrderAnnotation;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * The game-domain write funnel's integrity: generated DNS lands only in a zone this
 * controller is PRIMARY for, and a refusal after the save leaves nothing behind.
 *
 * AIDEV-NOTE: the two defects this pins. ONE: the zone lookup read every enabled zone,
 * secondaries included, so a mapping under a more specific REPLICA zone wrote its SRV/A
 * rows into the replica and bumped its serial. TWO: the mapping was saved before the
 * steps that can refuse (link, push, re-render), so a refusal left it stored and the
 * operator's retry was refused as a duplicate.
 */
@TestMethodOrder(OrderAnnotation.class)
class GameDomainIntegrityTest extends HohenheimTestBase {

    private static final String PRIMARY_ORIGIN = "gamezone.test";
    private static final String REPLICA_ORIGIN = "replica.gamezone.test";
    private static final String REPLICA_HOST = "srv.replica.gamezone.test";
    private static final String PLAY_HOST = "play.gamezone.test";

    private static int primaryZoneId;
    private static int replicaZoneId;
    private static int replicaDomainId;
    private static int playDomainId;
    private static int backendId;
    private static int proxyId;
    private static int secondProxyId;
    private static int tenantId;
    private static int replicaMappingId;

    @BeforeAll
    static void fixtures() {
        var sites = Models.get(SiteModel.class);
        Row site = sites.createEmptyRow();
        site.set(SiteModel.NAME, "Game integrity site");
        site.set(SiteModel.SLUG, "game-integrity-site");
        site.set(SiteModel.UPSTREAM_KIND, "hohenheim:static");
        site.set(SiteModel.SETTINGS, Map.of("root_path", "/tmp"));
        site.set(SiteModel.STATUS, "active");
        site.set(SiteModel.ENABLED, true);
        sites.save(site);
        int siteId = site.get(SiteModel.ID);

        replicaDomainId = domain(siteId, REPLICA_HOST);
        playDomainId = domain(siteId, PLAY_HOST);

        backendId = instance("game-integrity-backend");
        proxyId = instance("game-integrity-proxy");
        secondProxyId = instance("game-integrity-proxy-2");

        tenantId = user("tenant@game-integrity.test");
        RecordGrants.grant(GrantSubjectType.USER, tenantId, SiteModel.MODEL_ID, siteId,
            HohenheimAccess.MANAGE, true);
        for (int instance : List.of(backendId, proxyId, secondProxyId)) {
            RecordGrants.grant(GrantSubjectType.USER, tenantId, InstanceModel.MODEL_ID, instance,
                HohenheimAccess.MANAGE, true);
        }

        primaryZoneId = zone(PRIMARY_ORIGIN, DnsZoneModel.ROLE_PRIMARY);
        replicaZoneId = zone(REPLICA_ORIGIN, DnsZoneModel.ROLE_SECONDARY);
        DnsZoneStore.INSTANCE.reload();

        int serverId = ServerModel.canonicalServerId(null);
        PortLedger.claimPreallocated(serverId, "", 25611, "tcp",
            InstanceModel.MODEL_ID, proxyId, "game-integrity-test");
        PortLedger.claimPreallocated(serverId, "", 25612, "tcp",
            InstanceModel.MODEL_ID, secondProxyId, "game-integrity-test");
    }

    /**
     * Take every mapping this class made down again: the database is shared with the other
     * shared-server classes, and GameDomainAuthorityTest counts mappings and generated rows
     * globally.
     */
    @AfterAll
    static void removeMappings() {
        var files = Models.get(InstanceFileModel.class);
        for (Row stray : files.find()
                .where(InstanceFileModel.CONTAINER_PATH.eq(VelocityConfigs.CONFIG_PATH))
                .and(InstanceFileModel.GENERATED_BY.isNull()).all()) {
            Integer owner = stray.get(InstanceFileModel.INSTANCE_ID);
            if (owner != null && (owner == proxyId || owner == secondProxyId)) {
                files.delete(stray);
            }
        }
        for (int instance : List.of(proxyId, secondProxyId)) {
            for (Row mapping : Models.get(GameDomainModel.class).findByProxyId(instance)) {
                GameDomains.deleteAuthorized(tenant(), mapping.get(GameDomainModel.ID));
            }
        }
    }

    /** Generated DNS for a host under a replica zone lands in the covering PRIMARY zone. */
    @Test
    @Order(1)
    void generatedDnsNeverLandsInASecondaryZone() {
        long replicaSerial = serialOf(replicaZoneId);

        // 1. Map a hostname whose MOST specific enabled zone is a secondary.
        Row mapping = GameDomains.applyAuthorized(tenant(),
            mappingRow(replicaDomainId, backendId, proxyId));
        replicaMappingId = mapping.get(GameDomainModel.ID);

        // 2. The SRV row exists, in the primary zone, named relative to it.
        Row srv = generatedRow(replicaMappingId, DnsRecordModel.TYPE_SRV);
        assertThat(srv).as("step 2: the generated SRV row exists").isNotNull();
        assertThat((Integer) srv.get(DnsRecordModel.ZONE_ID))
            .as("step 2: it was written into the zone this controller is PRIMARY for")
            .isEqualTo(primaryZoneId);
        assertThat((String) srv.get(DnsRecordModel.NAME))
            .as("step 2: its owner name is relative to the primary origin")
            .isEqualTo("_minecraft._tcp.srv.replica");

        // 3. The replica received no row and no serial bump.
        assertThat(Models.get(DnsRecordModel.class).find()
            .where(DnsRecordModel.ZONE_ID.eq(replicaZoneId)).count())
            .as("step 3: nothing was written into the secondary zone").isZero();
        assertThat(serialOf(replicaZoneId))
            .as("step 3: the secondary zone's serial was not bumped").isEqualTo(replicaSerial);

        // 4. With no primary covering the name at all, the lookup answers null rather
        //    than falling back to the replica.
        assertThat(GameDomains.zoneFor("elsewhere.replica-only.test"))
            .as("step 4: no covering zone answers null").isNull();
        assertThat((Integer) GameDomains.zoneFor(REPLICA_HOST).get(DnsZoneModel.ID))
            .as("step 4: the replica-covered name resolves to the primary")
            .isEqualTo(primaryZoneId);
    }

    /**
     * A refusal AFTER the save rolls the whole write back: the mapping keeps its old
     * proxy, the new proxy got no generated file, and the retry succeeds.
     */
    @Test
    @Order(2)
    void aRefusalAfterTheSaveLeavesTheMappingAsItWas() {
        var files = Models.get(InstanceFileModel.class);

        // 1. A second mapping keeps the first proxy's generated config non-empty.
        GameDomains.applyAuthorized(tenant(), mappingRow(playDomainId, backendId, proxyId));

        // 2. The operator replaces the first proxy's generated config by hand, AFTER the
        //    mappings existed. Re-rendering that proxy now refuses (game_file_conflict).
        Row generated = configRow(proxyId);
        assertThat((String) generated.get(InstanceFileModel.GENERATED_BY))
            .as("step 2: fixture: the first proxy's config is generated")
            .isEqualTo(GameDomains.SOURCE);
        GeneratedRows.sweeping(GameDomains.SOURCE, () -> files.delete(generated));
        Row handAuthored = files.createEmptyRow();
        handAuthored.set(InstanceFileModel.INSTANCE_ID, proxyId);
        handAuthored.set(InstanceFileModel.CONTAINER_PATH, VelocityConfigs.CONFIG_PATH);
        handAuthored.set(InstanceFileModel.CONTENT, "# operator-authored velocity.toml");
        files.save(handAuthored);

        // 3. Moving the replica mapping to the second proxy SAVES first and re-renders the
        //    OLD proxy after: that re-render refuses.
        Row move = Models.get(GameDomainModel.class).findById(replicaMappingId);
        move.set(GameDomainModel.PROXY_INSTANCE_ID, secondProxyId);
        Throwable refused = catchThrowable(() -> GameDomains.applyAuthorized(tenant(), move));
        assertThat(refused)
            .as("step 3: the old proxy's re-render refuses the move")
            .isInstanceOf(Violations.class);

        // 4. THE DEFECT: nothing of the refused write survives.
        assertThat((Integer) Models.get(GameDomainModel.class).findById(replicaMappingId)
                .get(GameDomainModel.PROXY_INSTANCE_ID))
            .as("step 4: the stored mapping still names the first proxy")
            .isEqualTo(proxyId);
        assertThat(configRow(secondProxyId))
            .as("step 4: the second proxy's generated config was rolled back too")
            .isNull();

        // 5. POSITIVE ANCHOR: with the conflict gone the SAME move succeeds, so step 3's
        //    refusal was the conflict and a retry is never refused as a duplicate.
        files.delete(handAuthored);
        Row retry = Models.get(GameDomainModel.class).findById(replicaMappingId);
        retry.set(GameDomainModel.PROXY_INSTANCE_ID, secondProxyId);
        assertThat(catchThrowable(() -> GameDomains.applyAuthorized(tenant(), retry)))
            .as("step 5: the retried move succeeds").isNull();
        assertThat((Integer) Models.get(GameDomainModel.class).findById(replicaMappingId)
                .get(GameDomainModel.PROXY_INSTANCE_ID))
            .as("step 5: and is stored").isEqualTo(secondProxyId);
        assertThat(configRow(secondProxyId))
            .as("step 5: the second proxy now carries its generated config").isNotNull();
    }

    // -- fixtures -------------------------------------------------------------

    private static int domain(int siteId, String hostname) {
        var domains = Models.get(SiteDomainModel.class);
        Row domain = domains.createEmptyRow();
        domain.set(SiteDomainModel.SITE_ID, siteId);
        domain.set(SiteDomainModel.HOSTNAME, hostname);
        domain.set(SiteDomainModel.MATCH_TYPE, SiteDomainModel.MATCH_EXACT);
        domains.save(domain);
        return domain.get(SiteDomainModel.ID);
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
        user.set(UserModel.CREATED_AT, Now.instant());
        user.set(UserModel.UPDATED_AT, Now.instant());
        AuthModels.users().save(user);
        return user.get(UserModel.ID);
    }

    private static int zone(String origin, String role) {
        var zones = Models.get(DnsZoneModel.class);
        Row zone = zones.createEmptyRow();
        zone.set(DnsZoneModel.ORIGIN, origin);
        zone.set(DnsZoneModel.ROLE, role);
        zone.set(DnsZoneModel.ENABLED, true);
        zone.set(DnsZoneModel.DEFAULT_TTL, 3600);
        zone.set(DnsZoneModel.NEGATIVE_TTL, 300);
        zone.set(DnsZoneModel.SOA_REFRESH, 7200);
        zone.set(DnsZoneModel.SOA_RETRY, 3600);
        zone.set(DnsZoneModel.SOA_EXPIRE, 1209600);
        zones.save(zone);
        return zone.get(DnsZoneModel.ID);
    }

    private static long serialOf(int zoneId) {
        Object serial = Models.get(DnsZoneModel.class).findById(zoneId).get(DnsZoneModel.SERIAL);
        return serial instanceof Number number ? number.longValue() : 0L;
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

    private static Row generatedRow(int mappingId, String type) {
        return Models.get(DnsRecordModel.class).find()
            .where(DnsRecordModel.GENERATED_BY.eq(GameDomains.SOURCE))
            .and(DnsRecordModel.GENERATED_FOR_MODEL.eq(GameDomainModel.MODEL_ID.toString()))
            .and(DnsRecordModel.GENERATED_FOR_ID.eq(mappingId))
            .and(DnsRecordModel.TYPE.eq(type))
            .first();
    }

    private static Row configRow(int instanceId) {
        return Models.get(InstanceFileModel.class).find()
            .where(InstanceFileModel.INSTANCE_ID.eq(instanceId))
            .and(InstanceFileModel.CONTAINER_PATH.eq(VelocityConfigs.CONFIG_PATH))
            .first();
    }

    private static AccessContext tenant() {
        StubConduit conduit = new StubConduit();
        conduit.setAttribute(ConduitAttributes.PRINCIPAL,
            new UserPrincipal(tenantId, "Game integrity tenant"));
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
