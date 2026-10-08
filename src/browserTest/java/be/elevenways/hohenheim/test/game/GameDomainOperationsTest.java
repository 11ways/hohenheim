package be.elevenways.hohenheim.test.game;

import be.elevenways.hohenheim.game.GameDomainOperations;
import be.elevenways.hohenheim.model.GameDomainModel;
import be.elevenways.hohenheim.model.InstanceFileModel;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.InstanceVariableModel;
import be.elevenways.hohenheim.model.SiteDomainModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.server.game.GameDomains;
import be.elevenways.hohenheim.server.game.VelocityConfigs;
import be.elevenways.hohenheim.test.ApiSupport;
import be.elevenways.hohenheim.test.HohenheimTestBase;
import be.elevenways.zenit.auth.model.UserModel;
import be.elevenways.zenit.auth.model.UserPrincipal;
import be.elevenways.zenit.common.operation.ZenitPlacementSurface;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.refusal.DomainRefusal;
import be.elevenways.zenit.common.refusal.ZenitRefusalReason;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.server.operation.OperationPipeline;
import be.elevenways.zenit.server.operation.OperationRequest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static be.elevenways.hohenheim.test.TestAccessContexts.contextFor;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * The game-domain mapping writes as the admin entry runs them: create, update and delete are operations over the
 * GameDomains funnel, the update reviewed against the mapping's lock version.
 */
class GameDomainOperationsTest extends HohenheimTestBase {

    private static final String PREFIX = "game-ops-";

    private static int domainId;
    private static int backendId;
    private static int proxyId;
    private static AccessContext operator;
    private static AccessContext tenant;

    @BeforeAll
    static void fixtures() {
        var sites = Models.get(SiteModel.class);
        Row site = sites.createEmptyRow();
        site.set(SiteModel.NAME, PREFIX + "site");
        site.set(SiteModel.SLUG, PREFIX + "site");
        site.set(SiteModel.UPSTREAM_KIND, "hohenheim:static");
        site.set(SiteModel.SETTINGS, Map.of("root_path", "/tmp"));
        site.set(SiteModel.STATUS, "active");
        site.set(SiteModel.ENABLED, true);
        sites.save(site);

        var domains = Models.get(SiteDomainModel.class);
        Row domain = domains.createEmptyRow();
        domain.set(SiteDomainModel.SITE_ID, site.get(SiteModel.ID));
        domain.set(SiteDomainModel.HOSTNAME, "play.game-ops.test");
        domain.set(SiteDomainModel.MATCH_TYPE, SiteDomainModel.MATCH_EXACT);
        domains.save(domain);
        domainId = domain.get(SiteDomainModel.ID);

        backendId = instance(PREFIX + "backend");
        proxyId = instance(PREFIX + "proxy");

        // The proxy's forwarding secret, what a Velocity template's secret variable mints in production.
        var variables = Models.get(InstanceVariableModel.class);
        Row secret = variables.createEmptyRow();
        secret.set(InstanceVariableModel.INSTANCE_ID, proxyId);
        secret.set(InstanceVariableModel.KEY, GameDomains.PROXY_SECRET_KEY);
        secret.set(InstanceVariableModel.KIND, InstanceVariableModel.KIND_SECRET);
        secret.set(InstanceVariableModel.SECRET_VALUE, "game-ops-forwarding-secret");
        variables.save(secret);

        Row admin = Models.get(UserModel.class).find().where(UserModel.EMAIL.eq("test@hohenheim.local")).first();
        operator = contextFor(new UserPrincipal(admin.get(UserModel.ID), "Test Admin"));
        tenant = contextFor(new UserPrincipal(ApiSupport.user(PREFIX + "tenant@hohenheim.local"), "Game Tenant"));
    }

    @Test
    void aMappingIsWrittenThroughItsOperationsAndReviewedAgainstItsVersion() {
        // 1. The operator's create runs the funnel: the mapping is stored at version 0 and its proxy config
        //    materialized.
        Integer created = OperationPipeline.invoke(OperationRequest.of(GameDomainOperations.CREATE,
                ZenitPlacementSurface.HTTP_API)
            .caller(operator)
            .form(form(25565))).value();
        assertThat(created).as("step 1: the create answers the new mapping's id").isNotNull();
        int mappingId = created;
        assertThat((Integer) mapping(mappingId).get(GameDomainModel.VERSION))
            .as("step 1: a new mapping starts at version 0").isEqualTo(0);
        assertThat(generatedConfig())
            .as("step 1: the funnel materialized the proxy's forced-hosts config").isNotNull();

        // 2. A caller without the admin panel's access is refused at admission, and the mapping is untouched.
        Throwable refused = catchThrowable(() -> update(tenant, mappingId, 0, 25570));
        assertThat(refused)
            .as("step 2: a tenant cannot run the admin update")
            .isInstanceOfSatisfying(DomainRefusal.class,
                refusal -> assertThat(refusal.is(ZenitRefusalReason.FORBIDDEN)).isTrue());
        assertThat((Integer) mapping(mappingId).get(GameDomainModel.BACKEND_PORT))
            .as("step 2: the port is unchanged").isEqualTo(25565);

        // 3. A concurrent save moves the mapping to version 1; an edit reviewed at version 0 refuses as stale.
        Row concurrent = mapping(mappingId);
        concurrent.set(GameDomainModel.ENABLED, false);
        Models.get(GameDomainModel.class).save(concurrent);
        assertThat((Integer) mapping(mappingId).get(GameDomainModel.VERSION))
            .as("step 3: the concurrent save counted").isEqualTo(1);
        Throwable stale = catchThrowable(() -> update(operator, mappingId, 0, 25571));
        assertThat(stale)
            .as("step 3: the stale edit is refused")
            .isInstanceOfSatisfying(DomainRefusal.class,
                refusal -> assertThat(refusal.is(ZenitRefusalReason.STALE)).isTrue());
        assertThat((Integer) mapping(mappingId).get(GameDomainModel.BACKEND_PORT))
            .as("step 3: the stale edit wrote nothing").isEqualTo(25565);

        // 4. The same edit reviewed at the current version saves and counts.
        update(operator, mappingId, 1, 25572);
        assertThat((Integer) mapping(mappingId).get(GameDomainModel.BACKEND_PORT))
            .as("step 4: the reviewed edit saved").isEqualTo(25572);
        assertThat((Integer) mapping(mappingId).get(GameDomainModel.VERSION))
            .as("step 4: and moved the version on").isEqualTo(2);

        // 5. The delete removes the mapping and the config it generated.
        OperationPipeline.invoke(OperationRequest.of(GameDomainOperations.DELETE, ZenitPlacementSurface.HTTP_API)
            .caller(operator)
            .subjects(List.of(mapping(mappingId))));
        assertThat(Models.get(GameDomainModel.class).findById(mappingId))
            .as("step 5: the mapping is gone").isNull();
        assertThat(generatedConfig())
            .as("step 5: and so is its proxy's generated config").isNull();
    }

    private static void update(AccessContext caller, int mappingId, long reviewed, int port) {
        OperationPipeline.invoke(OperationRequest.of(GameDomainOperations.UPDATE, ZenitPlacementSurface.HTTP_API)
            .caller(caller)
            .subjects(List.of(mapping(mappingId)))
            .expectedVersion(reviewed)
            .form(form(port)));
    }

    private static Map<String, Object> form(int port) {
        return Map.of(
            "site_domain_id", String.valueOf(domainId),
            "backend_instance_id", String.valueOf(backendId),
            "proxy_instance_id", String.valueOf(proxyId),
            "backend_port", String.valueOf(port),
            "enabled", "true");
    }

    private static Row mapping(int id) {
        return Models.get(GameDomainModel.class).findById(id);
    }

    private static Row generatedConfig() {
        return Models.get(InstanceFileModel.class).find()
            .where(InstanceFileModel.INSTANCE_ID.eq(proxyId))
            .and(InstanceFileModel.CONTAINER_PATH.eq(VelocityConfigs.CONFIG_PATH))
            .first();
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
}
