package be.elevenways.hohenheim.test;

import be.elevenways.hohenheim.HohenheimSources;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.HohenheimCapabilities;
import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.zenit.auth.CapabilityScopes;
import be.elevenways.zenit.auth.model.ApiKeyPrincipal;
import be.elevenways.zenit.auth.model.GrantModel;
import be.elevenways.zenit.auth.model.GrantSubjectType;
import be.elevenways.zenit.auth.model.UserModel;
import be.elevenways.zenit.auth.model.UserPrincipal;
import be.elevenways.zenit.auth.server.ApiKeyService;
import be.elevenways.zenit.auth.server.GrantService;
import be.elevenways.zenit.auth.server.RecordGrants;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.common.security.Principal;
import be.elevenways.zenit.common.security.RecordCapabilityDecision;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The record-capability consumer proof: hohenheim's REAL site policy (registered
 * vocabulary + declared rules) routes /manage authorization through the fixed
 * precedence walk in RecordCapabilities, the tri-state gate row (GATE_DENIED) is
 * alive against the production permission checker, and the real cap: scope is
 * mintable exactly for a held grant.
 */
class CapabilityWalkTest extends HohenheimTestBase {

    private static Integer walkSiteId;
    private static Integer walkOperatorId;

    @BeforeAll
    static void seedWalkFixtures() {
        var siteModel = Models.get(SiteModel.class);
        Row site = siteModel.createEmptyRow();
        site.set(SiteModel.NAME, "Walk Site");
        site.set(SiteModel.SLUG, "walk-site");
        site.set(SiteModel.UPSTREAM_KIND, "hohenheim:static");
        site.set(SiteModel.SETTINGS, Map.of("root_path", "/tmp"));
        site.set(SiteModel.STATUS, "active");
        site.set(SiteModel.ENABLED, true);
        siteModel.save(site);
        walkSiteId = site.get(SiteModel.ID);

        walkOperatorId = ApiSupport.user("walk-operator@hohenheim.local", "Walk Operator");
    }

    /**
     * The production /manage policy answers with the precedence ROW, not just a
     * boolean: no grant denies, a record grant allows, an operator's explicit
     * global deny of the gate permission kills the grant (row 2, dead while the
     * old wrapper swallowed tri-state), and the admin permission bypasses.
     */
    @Test
    void manageDecisionsRideThePrecedenceWalk() {
        UserPrincipal operator = new UserPrincipal(walkOperatorId, "Walk Operator");
        AccessContext ctx = contextFor(operator);

        try {
            // 1. Ungranted: the walk runs (the model HAS a policy) and denies on
            //    the terminal row -- NO_POLICY here means the consumer never
            //    declared its rules, the exact unwired state this test pins.
            assertThat(ctx.capabilityDecision(SiteModel.MODEL_ID, walkSiteId, HohenheimCapabilities.MANAGE))
                .as("step 1: an ungranted operator must reach the walk's terminal deny row")
                .isEqualTo(RecordCapabilityDecision.NO_GRANT);

            // 2. A record grant flips the decision to GRANT_ALLOWED, and both
            //    policy faces (context and principal-only) agree.
            RecordGrants.grant(GrantSubjectType.USER, walkOperatorId, SiteModel.MODEL_ID, walkSiteId,
                HohenheimCapabilities.MANAGE, true);
            assertThat(ctx.capabilityDecision(SiteModel.MODEL_ID, walkSiteId, HohenheimCapabilities.MANAGE))
                .as("step 2: a positive record grant must decide GRANT_ALLOWED")
                .isEqualTo(RecordCapabilityDecision.GRANT_ALLOWED);
            assertThat(HohenheimAccess.canManageSite(ctx, walkSiteId))
                .as("step 2: the context face must allow the granted site").isTrue();
            assertThat(HohenheimAccess.canManageSite((Principal) operator, walkSiteId))
                .as("step 2: the principal-only face must agree").isTrue();
            assertThat(HohenheimAccess.managedSiteIds(operator))
                .as("step 2: enumeration must confirm the granted site")
                .containsExactly(walkSiteId);

            // 3. An explicit global DENY of the gate permission must kill the
            //    record grant: precedence row 2 (GATE_DENIED). This is the
            //    tri-state the deleted wrapper used to swallow (its inherited
            //    decide() mapped every false to abstain).
            GrantService.createDirectGrant(GrantSubjectType.USER, walkOperatorId, "hohenheim.manage.access", false);
            assertThat(ctx.capabilityDecision(SiteModel.MODEL_ID, walkSiteId, HohenheimCapabilities.MANAGE))
                .as("step 3: an explicit gate denial must decide GATE_DENIED, not fall through to the grant")
                .isEqualTo(RecordCapabilityDecision.GATE_DENIED);
            assertThat(HohenheimAccess.canManageSite(ctx, walkSiteId))
                .as("step 3: the gate denial must refuse the site").isFalse();
            assertThat(HohenheimAccess.canManageSite((Principal) operator, walkSiteId))
                .as("step 3: the principal-only face must refuse too").isFalse();
            assertThat(HohenheimAccess.managedSiteIds(operator))
                .as("step 3: enumeration must confirm nothing under a gate denial").isEmpty();

            // 4. Removing the deny restores the grant decision.
            deleteManageAccessGrants(walkOperatorId);
            assertThat(ctx.capabilityDecision(SiteModel.MODEL_ID, walkSiteId, HohenheimCapabilities.MANAGE))
                .as("step 4: with the deny gone the record grant decides again")
                .isEqualTo(RecordCapabilityDecision.GRANT_ALLOWED);

            // 5. The seeded admin (wildcard grant) takes the ADMIN_BYPASS row.
            Row admin = Models.get(UserModel.class).find()
                .where(UserModel.EMAIL.eq("test@hohenheim.local")).first();
            AccessContext adminCtx = contextFor(
                new UserPrincipal(admin.get(UserModel.ID), "Test Admin"));
            assertThat(adminCtx.capabilityDecision(SiteModel.MODEL_ID, walkSiteId, HohenheimCapabilities.MANAGE))
                .as("step 5: the admin permission must decide ADMIN_BYPASS")
                .isEqualTo(RecordCapabilityDecision.ADMIN_BYPASS);
        } finally {
            RecordGrants.revoke(GrantSubjectType.USER, walkOperatorId, SiteModel.MODEL_ID, walkSiteId,
                HohenheimCapabilities.MANAGE);
            deleteManageAccessGrants(walkOperatorId);
        }
    }

    /**
     * The REAL cap:hohenheim:site#manage scope is mintable by a holder of the
     * registered, delegable capability -- and only then; an unregistered
     * capability on the same model stays refused.
     */
    @Test
    void realManageScopeMintsForAHolderAndOnlyAHolder() {
        UserPrincipal operator = new UserPrincipal(walkOperatorId, "Walk Operator");
        AccessContext actor = contextFor(operator);
        String scope = CapabilityScopes.format(SiteModel.MODEL_ID, HohenheimCapabilities.MANAGE);

        try {
            // 1. Without a holding, the delegation rule refuses the mint.
            assertThatThrownBy(() -> ApiKeyService.create(actor, walkOperatorId, "walk-ci",
                    List.of(scope), null))
                .as("step 1: an operator holding manage on no record must not mint the scope")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("do not currently hold");

            // 2. With the grant, the SAME mint succeeds: the production vocabulary
            //    registers manage as delegable, so this is the first mintable cap:
            //    scope in a real install.
            RecordGrants.grant(GrantSubjectType.USER, walkOperatorId, SiteModel.MODEL_ID, walkSiteId,
                HohenheimCapabilities.MANAGE, true);
            ApiKeyService.CreatedKey created =
                ApiKeyService.create(actor, walkOperatorId, "walk-ci", List.of(scope), null);
            assertThat(created.plaintext())
                .as("step 2: a holder of the delegable manage capability must mint the scope")
                .startsWith(ApiKeyService.TOKEN_MARKER);

            // 3. The minted key exercises the capability through the SAME walk,
            //    on the principal-only face terminals use.
            ApiKeyPrincipal key = new ApiKeyPrincipal(walkOperatorId, "Walk Operator",
                1, "walk-ci", List.of(scope));
            assertThat(AccessContext.detached(key)
                    .hasCapability(SiteModel.MODEL_ID, walkSiteId, HohenheimCapabilities.MANAGE))
                .as("step 3: the minted key must hold manage on the granted site").isTrue();
            assertThat(AccessContext.detached(key)
                    .hasCapability(SiteModel.MODEL_ID, walkSiteId + 1000, HohenheimCapabilities.MANAGE))
                .as("step 3: the key must hold nothing on other records").isFalse();

            // 4. An UNREGISTERED capability on the site model stays unmintable,
            //    even for this holder.
            assertThatThrownBy(() -> ApiKeyService.create(actor, walkOperatorId, "walk-ci2",
                    List.of(CapabilityScopes.format(SiteModel.MODEL_ID, "exec")), null))
                .as("step 4: an unregistered capability must refuse to mint")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown capability");
        } finally {
            RecordGrants.revoke(GrantSubjectType.USER, walkOperatorId, SiteModel.MODEL_ID, walkSiteId,
                HohenheimCapabilities.MANAGE);
        }
    }

    /**
     * /manage eligibility is ONE answer on every lane (S2: hohenheim.manage.access is core's computed permission): a
     * request-backed context and a detached one agree for an ungranted account, for a record-grant holder with no
     * global grant, and under an explicit global deny.
     */
    @Test
    void manageEligibilityIsOneAnswerOnTheRequestAndTheDetachedLane() {
        int tenantId = ApiSupport.user("walk-eligible@hohenheim.local", "Walk Eligible");
        UserPrincipal tenant = new UserPrincipal(tenantId, "Walk Eligible");

        try {
            // 1. No grant at all: neither lane admits the account.
            assertThat(List.of(contextFor(tenant).hasPermission(HohenheimSources.MANAGE_ACCESS),
                    AccessContext.detached(tenant).hasPermission(HohenheimSources.MANAGE_ACCESS)))
                .as("step 1: an ungranted account is eligible on neither lane").containsExactly(false, false);

            // 2. A manage grant on one site and no global grant: the computation admits it on BOTH lanes.
            RecordGrants.grant(GrantSubjectType.USER, tenantId, SiteModel.MODEL_ID, walkSiteId,
                HohenheimCapabilities.MANAGE, true);
            assertThat(List.of(contextFor(tenant).hasPermission(HohenheimSources.MANAGE_ACCESS),
                    AccessContext.detached(tenant).hasPermission(HohenheimSources.MANAGE_ACCESS)))
                .as("step 2: a record-grant holder is eligible on the request and the detached lane alike")
                .containsExactly(true, true);

            // 3. The account's key scoped only to that record capability is not admitted: the computation answers
            //    for the account, and the key's scopes do not cover the permission (review 10 D02).
            ApiKeyPrincipal narrowKey = new ApiKeyPrincipal(tenantId, "Walk Eligible", 7, "walk-narrow",
                List.of(CapabilityScopes.format(SiteModel.MODEL_ID, HohenheimCapabilities.MANAGE)));
            assertThat(List.of(contextFor(narrowKey).hasPermission(HohenheimSources.MANAGE_ACCESS),
                    AccessContext.detached(narrowKey).hasPermission(HohenheimSources.MANAGE_ACCESS)))
                .as("step 3: a key scoped below the permission is eligible on neither lane")
                .containsExactly(false, false);
            ApiKeyPrincipal manageKey = new ApiKeyPrincipal(tenantId, "Walk Eligible", 8, "walk-manage",
                List.of("hohenheim.manage.access",
                    CapabilityScopes.format(SiteModel.MODEL_ID, HohenheimCapabilities.MANAGE)));
            assertThat(List.of(contextFor(manageKey).hasPermission(HohenheimSources.MANAGE_ACCESS),
                    AccessContext.detached(manageKey).hasPermission(HohenheimSources.MANAGE_ACCESS)))
                .as("step 3: a key declaring the permission (and the record capability) is eligible as its account is")
                .containsExactly(true, true);

            // 4. An explicit global deny wins over the computation on both lanes.
            GrantService.createDirectGrant(GrantSubjectType.USER, tenantId, "hohenheim.manage.access", false);
            assertThat(List.of(contextFor(tenant).hasPermission(HohenheimSources.MANAGE_ACCESS),
                    AccessContext.detached(tenant).hasPermission(HohenheimSources.MANAGE_ACCESS)))
                .as("step 4: an explicit deny refuses on both lanes").containsExactly(false, false);
        } finally {
            RecordGrants.revoke(GrantSubjectType.USER, tenantId, SiteModel.MODEL_ID, walkSiteId,
                HohenheimCapabilities.MANAGE);
            deleteManageAccessGrants(tenantId);
        }
    }

    /**
     * Review 10 D02, the live shape: a non-admin account holding instance VIEW through a record grant is eligible for
     * /manage, and its key scoped only to that instance capability is not, on either lane.
     */
    @Test
    void anInstanceViewerIsEligibleButItsViewScopedKeyIsNot() {
        Row instance = Models.get(InstanceModel.class).createEmptyRow();
        instance.set(InstanceModel.NAME, "walk-viewed-instance");
        instance.set(InstanceModel.KIND, "hohenheim:docker_container");
        instance.set(InstanceModel.SETTINGS, new LinkedHashMap<>(Map.of("image", "alpine", "tag", "latest")));
        instance.set(InstanceModel.STATUS, "stopped");
        Models.get(InstanceModel.class).save(instance);
        int instanceId = instance.get(InstanceModel.ID);
        int viewerId = ApiSupport.user("walk-instance-viewer@hohenheim.local", "Walk Instance Viewer");
        UserPrincipal viewer = new UserPrincipal(viewerId, "Walk Instance Viewer");

        try {
            // 1. Instance VIEW through a record grant, no global grant: the account keeps the intended widening.
            RecordGrants.grant(GrantSubjectType.USER, viewerId, InstanceModel.MODEL_ID, instanceId,
                HohenheimCapabilities.VIEW, true);
            assertThat(List.of(contextFor(viewer).hasPermission(HohenheimSources.MANAGE_ACCESS),
                    AccessContext.detached(viewer).hasPermission(HohenheimSources.MANAGE_ACCESS)))
                .as("step 1: an instance viewer is eligible on the request and the detached lane")
                .containsExactly(true, true);

            // 2. Its key scoped only to instance view is refused the computed permission on both lanes.
            ApiKeyPrincipal viewKey = new ApiKeyPrincipal(viewerId, "Walk Instance Viewer", 9, "walk-view",
                List.of(CapabilityScopes.format(InstanceModel.MODEL_ID, "view")));
            assertThat(viewKey.coversCapability(InstanceModel.MODEL_ID, HohenheimCapabilities.VIEW))
                .as("step 2: the key does cover the instance view it was minted for").isTrue();
            assertThat(List.of(contextFor(viewKey).hasPermission(HohenheimSources.MANAGE_ACCESS),
                    AccessContext.detached(viewKey).hasPermission(HohenheimSources.MANAGE_ACCESS)))
                .as("step 2: a key scoped only to instance view is not eligible for /manage")
                .containsExactly(false, false);
        } finally {
            RecordGrants.revoke(GrantSubjectType.USER, viewerId, InstanceModel.MODEL_ID, instanceId,
                HohenheimCapabilities.VIEW);
            Models.get(InstanceModel.class).find().where(InstanceModel.ID.eq(instanceId)).delete();
        }
    }

    private static void deleteManageAccessGrants(int userId) {
        for (Row grant : GrantService.listDirectGrants(GrantSubjectType.USER, userId)) {
            if ("hohenheim.manage.access".equals(grant.get(GrantModel.PERMISSION))) {
                GrantService.deleteDirectGrant(GrantSubjectType.USER, userId, grant.get(GrantModel.ID));
            }
        }
    }

    /** @see TestAccessContexts#contextFor -- a FRESH conduit per call, memo included. */
    private static AccessContext contextFor(Principal principal) {
        return TestAccessContexts.contextFor(principal);
    }
}
