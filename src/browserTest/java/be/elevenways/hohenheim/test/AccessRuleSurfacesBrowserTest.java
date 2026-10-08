package be.elevenways.hohenheim.test;

import be.elevenways.hohenheim.HohenheimIds;
import be.elevenways.hohenheim.HohenheimSlugs;
import be.elevenways.hohenheim.model.AccessListModel;
import be.elevenways.hohenheim.model.AccessRuleModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.hohenheim.server.cms.AccessRuleParts;
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

/**
 * The access-rule entries (admin and the /manage twin) of the Hohenheim legacy-admin remainder (batch 1b), stored
 * before the rule tree moves onto core's TreeBehaviour and the entries onto shared parts, and compared exactly after.
 *
 * AIDEV-NOTE: the stored set ({@code /panel-surfaces/access-rules.txt}) is the behaviour captured on the legacy
 * access-rule resource and its /manage twin (deleted, now AccessRuleParts). The fixture tree covers both move edges (a
 * first, a middle and a last sibling, and a lone child), both toggle directions and a rule of a list the tenant does
 * not manage. A failing comparison is a changed surface, never a file to refresh; an accepted difference is declared
 * as a move.
 */
class AccessRuleSurfacesBrowserTest extends HohenheimTestBase {

    private static final String PREFIX = "access-rule-surfaces-";
    private static final String ADMIN = HohenheimSlugs.ADMIN;
    private static final String MANAGE = HohenheimSlugs.MANAGE;
    private static final String LISTS = HohenheimSlugs.ACCESS_LISTS;
    private static final String RULES = "access-rules";

    private static int tenantId;
    private static int siteId;
    private static String tenantListId;
    private static String sharedListId;
    private static String groupId;
    private static String allowId;
    private static String credentialsId;
    private static String childId;
    private static String sharedRuleId;
    private static AccessContext operator;
    private static AccessContext tenant;
    private static AccessContext tenantEmpty;

    @BeforeAll
    static void seed() {
        tenantId = ApiSupport.user(PREFIX + "tenant@hohenheim.local", "Access Rule Surfaces Tenant");
        int emptyId = ApiSupport.user(PREFIX + "empty@hohenheim.local", "Access Rule Surfaces Empty");
        int tenantList = accessList(PREFIX + "tenant");
        int sharedList = accessList(PREFIX + "shared");
        tenantListId = String.valueOf(tenantList);
        sharedListId = String.valueOf(sharedList);
        // The tenant list's root run: a group first, an enabled network leaf in the middle, a disabled leaf last;
        // the group holds one lone child.
        int group = rule(tenantList, null, AccessRuleModel.TYPE_GROUP, Map.of("satisfy", "any"), true, 0);
        groupId = String.valueOf(group);
        allowId = String.valueOf(rule(tenantList, null, AccessRuleModel.TYPE_IP_ALLOW,
            Map.of("network", "10.0.0.0/8"), true, 1));
        credentialsId = String.valueOf(rule(tenantList, null, AccessRuleModel.TYPE_BASIC_AUTH, Map.of(), false, 2));
        childId = String.valueOf(rule(tenantList, group, AccessRuleModel.TYPE_IP_DENY,
            Map.of("network", "10.1.0.0/16"), true, 0));
        sharedRuleId = String.valueOf(rule(sharedList, null, AccessRuleModel.TYPE_IP_ALLOW,
            Map.of("network", "192.168.0.0/16"), true, 0));
        RecordGrants.grant(GrantSubjectType.USER, tenantId, AccessListModel.MODEL_ID, tenantList,
            HohenheimAccess.MANAGE, true);
        // AIDEV-NOTE: the legacy /manage eligibility counted no access-list grant, so the tenant also manages a site
        // (the AccessSurfacesBrowserTest fixture) for the panel to admit it at all.
        siteId = site(PREFIX + "site");
        RecordGrants.grant(GrantSubjectType.USER, tenantId, SiteModel.MODEL_ID, siteId, HohenheimAccess.MANAGE, true);
        operator = TenantConduits.operator();
        tenant = AccessContext.of(TenantConduits.stubFor(new UserPrincipal(tenantId, "Access Rule Surfaces Tenant")));
        tenantEmpty = AccessContext.of(TenantConduits.stubFor(
            new UserPrincipal(emptyId, "Access Rule Surfaces Empty")));
    }

    /** The fixture rows leave with the class; deleting a list takes its rules (the list cascade). */
    @AfterAll
    static void cleanUp() {
        RecordGrants.revoke(GrantSubjectType.USER, tenantId, SiteModel.MODEL_ID, siteId, HohenheimAccess.MANAGE);
        HardDeletes.byId(Models.get(SiteModel.class), siteId);
        RecordGrants.revoke(GrantSubjectType.USER, tenantId, AccessListModel.MODEL_ID, Integer.parseInt(tenantListId),
            HohenheimAccess.MANAGE);
        HardDeletes.byId(Models.get(AccessListModel.class), Integer.parseInt(tenantListId));
        HardDeletes.byId(Models.get(AccessListModel.class), Integer.parseInt(sharedListId));
    }

    @Test
    void theAccessRuleEntriesOfferWhatTheyOfferedBeforeTheMove() {
        // The moves and the toggle keep their ids and move only their route onto the placed operations; the synthesized
        // delete is the canonical delete_access_rule operation (subtree included, the tree's delete policy), and the
        // legacy resource's own access_rule_delete, which duplicated it, is retired in its favour.
        SurfaceBaselines stored = SurfaceBaselines.load(AccessRuleSurfacesBrowserTest.class,
                "/panel-surfaces/access-rules.txt")
            .placedOperations(PlacedOperationMoves.of(AccessRuleParts.ORDER.moveUp().id(),
                    AccessRuleParts.ORDER.moveDown().id(), AccessRuleParts.TOGGLE.id())
                .synthesized(RULES, SynthesizedRowActions.DELETE, AccessRuleParts.DELETE.id())
                .retired(RULES, HohenheimIds.id("access_rule_delete"), AccessRuleParts.DELETE.id()));

        // 1. The admin entry for the operator, record-less, on each rule and over a selection; a tenant is refused.
        stored.check(capture(SurfaceCase.of(ADMIN, RULES, "operator", operator)));
        stored.check(capture(SurfaceCase.of(ADMIN, RULES, "tenant", tenant).refusedFor(ZenitRefusalReason.FORBIDDEN)));
        stored.check(capture(SurfaceCase.of(ADMIN, RULES, "operator", operator).onRecord(groupId, "group")));
        stored.check(capture(SurfaceCase.of(ADMIN, RULES, "operator", operator).onRecord(allowId, "allow")));
        stored.check(capture(SurfaceCase.of(ADMIN, RULES, "operator", operator)
            .onRecord(credentialsId, "credentials")));
        stored.check(capture(SurfaceCase.of(ADMIN, RULES, "operator", operator).onRecord(childId, "child")));
        stored.check(capture(SurfaceCase.of(ADMIN, RULES, "operator", operator).onRecord(sharedRuleId, "shared")));
        stored.check(capture(SurfaceCase.of(ADMIN, RULES, "operator", operator)
            .selecting(List.of(allowId, credentialsId), "sel")));

        // 2. The /manage twin for a tenant managing the one list; a tenant holding nothing is refused.
        stored.check(capture(SurfaceCase.of(MANAGE, RULES, "tenant", tenant)));
        stored.check(capture(SurfaceCase.of(MANAGE, RULES, "tenant-empty", tenantEmpty)
            .refusedFor(ZenitRefusalReason.FORBIDDEN)));
        stored.check(capture(SurfaceCase.of(MANAGE, RULES, "tenant", tenant).onRecord(allowId, "allow")));
        stored.check(capture(SurfaceCase.of(MANAGE, RULES, "tenant", tenant).onRecord(childId, "child")));

        // 3. Every stored case matched exactly.
        stored.finish();
    }

    /** A capture with every generated fixture id declared at the bindings a destination carries it. */
    private static PanelSurfaces capture(SurfaceCase fixture) {
        return PanelSurfaces.capture(fixture
            .key(RULES, "group", groupId).key(RULES, "allow", allowId).key(RULES, "credentials", credentialsId)
            .key(RULES, "child", childId).key(RULES, "shared_rule", sharedRuleId)
            .key(LISTS, "tenant_list", tenantListId).key(LISTS, "shared_list", sharedListId)
            .key("access_list_id", "tenant_list", tenantListId).key("access_list_id", "shared_list", sharedListId)
            .key("parent", "tenant_list", tenantListId).key("parent", "shared_list", sharedListId));
    }

    private static int site(String slug) {
        Model sites = Models.get(SiteModel.class);
        Row row = sites.createEmptyRow();
        row.set(SiteModel.NAME, slug);
        row.set(SiteModel.SLUG, slug);
        row.set(SiteModel.UPSTREAM_KIND, "hohenheim:static");
        row.set(SiteModel.SETTINGS, Map.of("root_path", "/tmp"));
        row.set(SiteModel.STATUS, SiteModel.STATUS_ACTIVE);
        row.set(SiteModel.ENABLED, false);
        sites.save(row);
        return row.get(SiteModel.ID);
    }

    private static int accessList(String name) {
        Model lists = Models.get(AccessListModel.class);
        Row row = lists.createEmptyRow();
        row.set(AccessListModel.NAME, name);
        lists.save(row);
        return row.get(AccessListModel.ID);
    }

    /** A rule row stored through the model at an explicit sibling position, the AccessListEnforcementTest shape. */
    private static int rule(int listId, Integer parentId, String type, Map<String, Object> data, boolean enabled,
                            int sort) {
        Model rules = Models.get(AccessRuleModel.class);
        Row row = rules.createEmptyRow();
        row.set(AccessRuleModel.ACCESS_LIST_ID, listId);
        row.set(AccessRuleModel.PARENT_ID, parentId);
        row.set(AccessRuleModel.TYPE, type);
        row.set(AccessRuleModel.DATA, new LinkedHashMap<>(data));
        row.set(AccessRuleModel.ENABLED, enabled);
        row.set(AccessRuleModel.SORT, sort);
        rules.save(row);
        return row.get(AccessRuleModel.ID);
    }
}
