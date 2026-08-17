package be.elevenways.hohenheim.test;

import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.zenit.auth.CapabilityScopes;
import be.elevenways.zenit.auth.model.ApiKeyPrincipal;
import be.elevenways.zenit.auth.model.GrantModel;
import be.elevenways.zenit.auth.model.GrantSubjectType;
import be.elevenways.zenit.auth.model.UserModel;
import be.elevenways.zenit.auth.model.UserPrincipal;
import be.elevenways.zenit.auth.server.ApiKeyService;
import be.elevenways.zenit.auth.server.AuthModels;
import be.elevenways.zenit.auth.server.GrantService;
import be.elevenways.zenit.auth.server.RecordGrants;
import be.elevenways.zenit.common.Zenit;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.common.security.Principal;
import be.elevenways.zenit.common.security.RecordCapabilityDecision;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.time.Instant;
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
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class CapabilityWalkTest extends HohenheimTestBase {

    private static Integer walkSiteId;
    private static Integer walkOperatorId;

    @BeforeAll
    static void seedWalkFixtures() {
        var siteModel = Models.get(SiteModel.class);
        Row site = siteModel.createEmptyRow();
        site.set(SiteModel.NAME, "Walk Site");
        site.set(SiteModel.SLUG, "walk-site");
        site.set(SiteModel.SITE_TYPE, "hohenheim:static");
        site.set(SiteModel.SETTINGS, Map.of("root_path", "/tmp"));
        site.set(SiteModel.STATUS, "active");
        site.set(SiteModel.ENABLED, true);
        siteModel.save(site);
        walkSiteId = site.get(SiteModel.ID);

        Row user = AuthModels.users().createEmptyRow();
        user.set(UserModel.EMAIL, "walk-operator@hohenheim.local");
        user.set(UserModel.DISPLAY_NAME, "Walk Operator");
        user.set(UserModel.ENABLED, true);
        user.set(UserModel.CREATED_AT, Instant.now());
        user.set(UserModel.UPDATED_AT, Instant.now());
        AuthModels.users().save(user);
        walkOperatorId = user.get(UserModel.ID);
    }

    /**
     * The production /manage policy answers with the precedence ROW, not just a
     * boolean: no grant denies, a record grant allows, an operator's explicit
     * global deny of the gate permission kills the grant (row 2, dead while the
     * old wrapper swallowed tri-state), and the admin permission bypasses.
     */
    @Test
    @Order(1)
    void manageDecisionsRideThePrecedenceWalk() {
        UserPrincipal operator = new UserPrincipal(walkOperatorId, "Walk Operator");
        AccessContext ctx = contextFor(operator);

        try {
            // 1. Ungranted: the walk runs (the model HAS a policy) and denies on
            //    the terminal row -- NO_POLICY here means the consumer never
            //    declared its rules, the exact unwired state this test pins.
            assertThat(ctx.capabilityDecision(SiteModel.MODEL_ID, walkSiteId, HohenheimAccess.MANAGE))
                .as("step 1: an ungranted operator must reach the walk's terminal deny row")
                .isEqualTo(RecordCapabilityDecision.NO_GRANT);

            // 2. A record grant flips the decision to GRANT_ALLOWED, and both
            //    policy faces (context and principal-only) agree.
            RecordGrants.grant(GrantSubjectType.USER, walkOperatorId, SiteModel.MODEL_ID, walkSiteId,
                HohenheimAccess.MANAGE, true);
            assertThat(ctx.capabilityDecision(SiteModel.MODEL_ID, walkSiteId, HohenheimAccess.MANAGE))
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
            assertThat(ctx.capabilityDecision(SiteModel.MODEL_ID, walkSiteId, HohenheimAccess.MANAGE))
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
            assertThat(ctx.capabilityDecision(SiteModel.MODEL_ID, walkSiteId, HohenheimAccess.MANAGE))
                .as("step 4: with the deny gone the record grant decides again")
                .isEqualTo(RecordCapabilityDecision.GRANT_ALLOWED);

            // 5. The seeded admin (wildcard grant) takes the ADMIN_BYPASS row.
            Row admin = AuthModels.users().find()
                .where(UserModel.EMAIL.eq("test@hohenheim.local")).first();
            AccessContext adminCtx = contextFor(
                new UserPrincipal(admin.get(UserModel.ID), "Test Admin"));
            assertThat(adminCtx.capabilityDecision(SiteModel.MODEL_ID, walkSiteId, HohenheimAccess.MANAGE))
                .as("step 5: the admin permission must decide ADMIN_BYPASS")
                .isEqualTo(RecordCapabilityDecision.ADMIN_BYPASS);
        } finally {
            RecordGrants.revoke(GrantSubjectType.USER, walkOperatorId, SiteModel.MODEL_ID, walkSiteId,
                HohenheimAccess.MANAGE);
            deleteManageAccessGrants(walkOperatorId);
        }
    }

    /**
     * The REAL cap:hohenheim:site#manage scope is mintable by a holder of the
     * registered, delegable capability -- and only then; an unregistered
     * capability on the same model stays refused.
     */
    @Test
    @Order(2)
    void realManageScopeMintsForAHolderAndOnlyAHolder() {
        UserPrincipal operator = new UserPrincipal(walkOperatorId, "Walk Operator");
        AccessContext actor = contextFor(operator);
        String scope = CapabilityScopes.format(SiteModel.MODEL_ID, HohenheimAccess.MANAGE);

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
                HohenheimAccess.MANAGE, true);
            ApiKeyService.CreatedKey created =
                ApiKeyService.create(actor, walkOperatorId, "walk-ci", List.of(scope), null);
            assertThat(created.plaintext())
                .as("step 2: a holder of the delegable manage capability must mint the scope")
                .startsWith(ApiKeyService.TOKEN_MARKER);

            // 3. The minted key exercises the capability through the SAME walk,
            //    on the principal-only face terminals use.
            ApiKeyPrincipal key = new ApiKeyPrincipal(walkOperatorId, "Walk Operator",
                1, "walk-ci", List.of(scope));
            assertThat(Zenit.getWebSocketAuthenticator()
                    .hasCapability(key, SiteModel.MODEL_ID, walkSiteId, HohenheimAccess.MANAGE))
                .as("step 3: the minted key must hold manage on the granted site").isTrue();
            assertThat(Zenit.getWebSocketAuthenticator()
                    .hasCapability(key, SiteModel.MODEL_ID, walkSiteId + 1000, HohenheimAccess.MANAGE))
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
                HohenheimAccess.MANAGE);
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
