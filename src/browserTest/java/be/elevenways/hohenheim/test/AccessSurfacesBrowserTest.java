package be.elevenways.hohenheim.test;

import be.elevenways.hohenheim.HohenheimSlugs;
import be.elevenways.hohenheim.model.AccessListModel;
import be.elevenways.hohenheim.model.AccessRuleModel;
import be.elevenways.hohenheim.model.ProtectedPathModel;
import be.elevenways.hohenheim.model.ReleasedRouteClaimModel;
import be.elevenways.hohenheim.model.SiteAuthProviderModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.HohenheimCapabilities;
import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.hohenheim.server.auth.types.BasicAuthProviderType;
import be.elevenways.hohenheim.server.cms.AuthProviderParts;
import be.elevenways.hohenheim.server.cms.ReleasedClaimParts;
import be.elevenways.protoblast.common.time.Now;
import be.elevenways.zenit.auth.model.GrantSubjectType;
import be.elevenways.zenit.auth.model.UserPrincipal;
import be.elevenways.zenit.auth.server.RecordGrants;
import be.elevenways.zenit.cms.common.render.table.SynthesizedRowActions;
import be.elevenways.zenit.cms.test.support.PanelSurfaces;
import be.elevenways.zenit.cms.test.support.PlacedOperationMoves;
import be.elevenways.zenit.cms.test.support.SurfaceBaselines;
import be.elevenways.zenit.cms.test.support.SurfaceCase;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.refusal.ZenitRefusalReason;
import be.elevenways.zenit.common.security.AccessContext;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static be.elevenways.hohenheim.HohenheimSlugs.ADMIN;
import static be.elevenways.hohenheim.HohenheimSlugs.MANAGE;

/**
 * The access entries of the Hohenheim legacy-admin remainder (batch 1a: access lists, protected paths, auth providers,
 * released claims), admin and tenant twins, stored before they move onto shared parts and compared exactly after it.
 *
 * AIDEV-NOTE: the stored set ({@code /panel-surfaces/access.txt}) is the behaviour captured on the legacy
 * AccessListResource, ManageAccessListResource, ProtectedPathResource, ManageProtectedPathResource,
 * AuthProviderResource and ReleasedClaimResource. A failing comparison is a changed surface, never a file to refresh;
 * an accepted difference is declared as a move or a twin table entry.
 */
class AccessSurfacesBrowserTest extends HohenheimTestBase {

    private static final String PREFIX = "access-surfaces-";
    private static final String LISTS = HohenheimSlugs.ACCESS_LISTS;
    private static final String PATHS = "protected-paths";
    private static final String PROVIDERS = "auth-providers";
    private static final String CLAIMS = "released-claims";

    private static int tenantId;
    private static String siteId;
    private static String sharedListId;
    private static String tenantListId;
    private static String pathId;
    private static String freeProviderId;
    private static String usedProviderId;
    private static String claimId;
    private static AccessContext operator;
    private static AccessContext tenant;
    private static AccessContext tenantEmpty;

    @BeforeAll
    static void seed() {
        tenantId = ApiSupport.user(PREFIX + "tenant@hohenheim.local", "Access Surfaces Tenant");
        int emptyId = ApiSupport.user(PREFIX + "empty@hohenheim.local", "Access Surfaces Empty");
        sharedListId = String.valueOf(accessList(PREFIX + "shared", true));
        int tenantList = accessList(PREFIX + "tenant", false);
        tenantListId = String.valueOf(tenantList);
        int site = site(PREFIX + "site", null);
        siteId = String.valueOf(site);
        pathId = String.valueOf(protectedPath(site, "/private", tenantList));
        freeProviderId = String.valueOf(provider(PREFIX + "free"));
        int used = provider(PREFIX + "used");
        usedProviderId = String.valueOf(used);
        site(PREFIX + "gated", used);
        claimId = String.valueOf(claim(PREFIX + "released.access.test"));
        RecordGrants.grant(GrantSubjectType.USER, tenantId, SiteModel.MODEL_ID, site, HohenheimCapabilities.MANAGE,
            true);
        RecordGrants.grant(GrantSubjectType.USER, tenantId, AccessListModel.MODEL_ID, tenantList,
            HohenheimCapabilities.MANAGE, true);
        operator = TenantConduits.operator();
        tenant = AccessContext.of(TenantConduits.stubFor(new UserPrincipal(tenantId, "Access Surfaces Tenant")));
        tenantEmpty = AccessContext.of(TenantConduits.stubFor(new UserPrincipal(emptyId, "Access Surfaces Empty")));
    }

    /** The fixture rows leave with the class: they are options of other classes' picks. */
    @AfterAll
    static void cleanUp() {
        HardDeletes.byId(Models.get(ProtectedPathModel.class), Integer.parseInt(pathId));
        RecordGrants.revoke(GrantSubjectType.USER, tenantId, SiteModel.MODEL_ID, Integer.parseInt(siteId),
            HohenheimCapabilities.MANAGE);
        for (Row site : Models.get(SiteModel.class).find().withTrashed()
                .where(SiteModel.NAME.startsWith(PREFIX)).all()) {
            HardDeletes.byId(Models.get(SiteModel.class), site.get(SiteModel.ID));
        }
        HardDeletes.byId(Models.get(ReleasedRouteClaimModel.class), Integer.parseInt(claimId));
        HardDeletes.byId(Models.get(SiteAuthProviderModel.class), Integer.parseInt(freeProviderId));
        HardDeletes.byId(Models.get(SiteAuthProviderModel.class), Integer.parseInt(usedProviderId));
        RecordGrants.revoke(GrantSubjectType.USER, tenantId, AccessListModel.MODEL_ID, Integer.parseInt(tenantListId),
            HohenheimCapabilities.MANAGE);
        HardDeletes.byId(Models.get(AccessListModel.class), Integer.parseInt(tenantListId));
        HardDeletes.byId(Models.get(AccessListModel.class), Integer.parseInt(sharedListId));
    }

    @Test
    void theAccessEntriesOfferWhatTheyOfferedBeforeTheMove() {
        // The quarantine lift moved from its legacy record action route onto the placed operation of the same id, and
        // the auth-provider delete's synthesized row action is its delete_auth_provider operation (its in-use refusal
        // the operation's availability); every other fact compares exactly.
        SurfaceBaselines stored = SurfaceBaselines.load(AccessSurfacesBrowserTest.class, "/panel-surfaces/access.txt")
            .placedOperations(PlacedOperationMoves.of(ReleasedClaimParts.LIFT.id())
                .synthesized(PROVIDERS, SynthesizedRowActions.DELETE, AuthProviderParts.DELETE.id()));

        // 1. The admin entries for the operator, record-less and on each record; a tenant is refused the panel.
        for (String entry : List.of(LISTS, PATHS, PROVIDERS, CLAIMS)) {
            stored.check(capture(SurfaceCase.of(ADMIN, entry, "operator", operator)));
            stored.check(capture(SurfaceCase.of(ADMIN, entry, "tenant", tenant)
                .refusedFor(ZenitRefusalReason.FORBIDDEN)));
        }
        stored.check(capture(SurfaceCase.of(ADMIN, LISTS, "operator", operator).onRecord(sharedListId, "shared")));
        stored.check(capture(SurfaceCase.of(ADMIN, LISTS, "operator", operator).onRecord(tenantListId, "tenant")));
        stored.check(capture(SurfaceCase.of(ADMIN, LISTS, "operator", operator)
            .selecting(List.of(sharedListId, tenantListId), "sel")));
        stored.check(capture(SurfaceCase.of(ADMIN, PATHS, "operator", operator).onRecord(pathId, "path")));
        stored.check(capture(SurfaceCase.of(ADMIN, PROVIDERS, "operator", operator)
            .onRecord(freeProviderId, "free")));
        stored.check(capture(SurfaceCase.of(ADMIN, PROVIDERS, "operator", operator)
            .onRecord(usedProviderId, "used")));
        stored.check(capture(SurfaceCase.of(ADMIN, PROVIDERS, "operator", operator)
            .selecting(List.of(freeProviderId, usedProviderId), "sel")));
        stored.check(capture(SurfaceCase.of(ADMIN, CLAIMS, "operator", operator).onRecord(claimId, "claim")));

        // 2. The /manage twins for a tenant managing one site and one list; a tenant holding nothing is refused.
        for (String entry : List.of(LISTS, PATHS)) {
            stored.check(capture(SurfaceCase.of(MANAGE, entry, "tenant", tenant)));
        }
        stored.check(capture(SurfaceCase.of(MANAGE, LISTS, "tenant-empty", tenantEmpty)
            .refusedFor(ZenitRefusalReason.FORBIDDEN)));
        stored.check(capture(SurfaceCase.of(MANAGE, LISTS, "tenant", tenant).onRecord(tenantListId, "tenant")));
        stored.check(capture(SurfaceCase.of(MANAGE, LISTS, "tenant", tenant).onRecord(sharedListId, "shared")));
        stored.check(capture(SurfaceCase.of(MANAGE, PATHS, "tenant", tenant).onRecord(pathId, "path")));

        // 3. Every stored case matched exactly.
        stored.finish();
    }

    /** A capture with every generated fixture id declared at the bindings a destination carries it. */
    private static PanelSurfaces capture(SurfaceCase fixture) {
        return PanelSurfaces.capture(fixture
            .key(LISTS, "shared_list", sharedListId).key(LISTS, "tenant_list", tenantListId)
            .key(PATHS, "path", pathId).key(PROVIDERS, "free_provider", freeProviderId)
            .key(PROVIDERS, "used_provider", usedProviderId).key(CLAIMS, "claim", claimId)
            .key(HohenheimSlugs.SITES, "site", siteId).key("site_id", "site", siteId).key("parent", "site", siteId));
    }

    private static int accessList(String name, boolean shared) {
        Model lists = Models.get(AccessListModel.class);
        Row row = lists.createEmptyRow();
        row.set(AccessListModel.NAME, name);
        row.set(AccessListModel.SHARED, shared);
        lists.save(row);
        return row.get(AccessListModel.ID);
    }

    private static int site(String slug, Integer authProviderId) {
        Model sites = Models.get(SiteModel.class);
        Row row = sites.createEmptyRow();
        row.set(SiteModel.NAME, slug);
        row.set(SiteModel.SLUG, slug);
        row.set(SiteModel.UPSTREAM_KIND, "hohenheim:static");
        row.set(SiteModel.SETTINGS, Map.of("root_path", "/tmp"));
        row.set(SiteModel.STATUS, SiteModel.STATUS_ACTIVE);
        row.set(SiteModel.ENABLED, false);
        row.set(SiteModel.AUTH_PROVIDER_ID, authProviderId);
        sites.save(row);
        return row.get(SiteModel.ID);
    }

    private static int protectedPath(int site, String path, int list) {
        // A path only points at a list that guards it (ProtectedPathInvariant): the list gets one rule first.
        Row rule = Models.get(AccessRuleModel.class).createEmptyRow();
        rule.set(AccessRuleModel.ACCESS_LIST_ID, list);
        rule.set(AccessRuleModel.TYPE, AccessRuleModel.TYPE_IP_ALLOW);
        rule.set(AccessRuleModel.DATA, new LinkedHashMap<>(Map.of("network", "10.0.0.0/8")));
        rule.set(AccessRuleModel.ENABLED, true);
        Models.get(AccessRuleModel.class).save(rule);
        Model paths = Models.get(ProtectedPathModel.class);
        Row row = paths.createEmptyRow();
        row.set(ProtectedPathModel.SITE_ID, site);
        row.set(ProtectedPathModel.PATH, path);
        row.set(ProtectedPathModel.ACCESS_LIST_ID, list);
        paths.save(row);
        return row.get(ProtectedPathModel.ID);
    }

    private static int provider(String name) {
        Model providers = Models.get(SiteAuthProviderModel.class);
        Row row = providers.createEmptyRow();
        row.set(SiteAuthProviderModel.NAME, name);
        row.set(SiteAuthProviderModel.PROVIDER_TYPE, "hohenheim:basic");
        row.set(SiteAuthProviderModel.CONFIG, new BasicAuthProviderType().normalizeConfigForSave(
            Map.of("credentials", Map.of("alice", "s3cret")), null));
        providers.save(row);
        return row.get(SiteAuthProviderModel.ID);
    }

    private static int claim(String hostname) {
        Model claims = Models.get(ReleasedRouteClaimModel.class);
        Row row = claims.createEmptyRow();
        row.set(ReleasedRouteClaimModel.CLAIM_KEY, PREFIX + "claim-key");
        row.set(ReleasedRouteClaimModel.HOSTNAME, hostname);
        row.set(ReleasedRouteClaimModel.FORMER_SUBJECTS, HohenheimAccess.packSubjects(Set.of("user:" + tenantId)));
        row.set(ReleasedRouteClaimModel.RELEASED_AT, Now.instant());
        claims.save(row);
        return row.get(ReleasedRouteClaimModel.ID);
    }
}
