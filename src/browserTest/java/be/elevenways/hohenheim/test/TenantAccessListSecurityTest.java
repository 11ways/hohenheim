package be.elevenways.hohenheim.test;

import be.elevenways.hohenheim.model.AccessListModel;
import be.elevenways.hohenheim.model.AccessRuleModel;
import be.elevenways.hohenheim.model.ProtectedPathModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.zenit.auth.model.GrantSubjectType;
import be.elevenways.zenit.auth.model.UserModel;
import be.elevenways.zenit.auth.model.UserPrincipal;
import be.elevenways.zenit.auth.server.AuthModels;
import be.elevenways.zenit.auth.server.RecordGrants;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.orm.query.criteria.Criteria;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.common.validation.Violation;
import be.elevenways.zenit.common.validation.Violations;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * The multi-operator boundary around access lists and protected paths: a tenant sees only
 * the lists it manages plus the shared ones, edits and deletes only its own, cannot
 * publish a list installation-wide, and can guard only its own sites -- with shared lists
 * attachable and foreign private lists not. Driven at the MODEL through TenantConduits,
 * because the write pipeline is the gate the /manage forms merely render.
 */
class TenantAccessListSecurityTest extends HohenheimTestBase {

    private static UserPrincipal alice;
    private static UserPrincipal bob;
    private static Integer aliceId;
    private static Integer bobId;
    private static Integer aliceSiteId;
    private static Integer bobSiteId;
    private static Integer aliceListId;
    private static Integer bobListId;
    private static Integer bobRuleId;
    private static Integer sharedListId;

    @BeforeAll
    static void seed() {
        Row aliceRow = user("alice-acl@hohenheim.local", "Alice Tenant");
        Row bobRow = user("bob-acl@hohenheim.local", "Bob Tenant");
        aliceId = aliceRow.get(UserModel.ID);
        bobId = bobRow.get(UserModel.ID);
        alice = new UserPrincipal(aliceId, "Alice Tenant");
        bob = new UserPrincipal(bobId, "Bob Tenant");

        aliceSiteId = site("Alice ACL Site", "alice-acl-site");
        bobSiteId = site("Bob ACL Site", "bob-acl-site");
        RecordGrants.grant(GrantSubjectType.USER, aliceId, SiteModel.MODEL_ID,
            aliceSiteId, HohenheimAccess.MANAGE, true);
        RecordGrants.grant(GrantSubjectType.USER, bobId, SiteModel.MODEL_ID,
            bobSiteId, HohenheimAccess.MANAGE, true);

        aliceListId = list("Alice List", false);
        bobListId = list("Bob List", false);
        sharedListId = list("Shared List", true);
        RecordGrants.grant(GrantSubjectType.USER, aliceId, AccessListModel.MODEL_ID,
            aliceListId, HohenheimAccess.MANAGE, true);
        RecordGrants.grant(GrantSubjectType.USER, bobId, AccessListModel.MODEL_ID,
            bobListId, HohenheimAccess.MANAGE, true);
        bobRuleId = rule(bobListId);
    }

    @Test
    void aTenantSeesOnlyItsOwnListsPlusTheSharedOnes() {
        AccessContext ctx = AccessContext.of(TenantConduits.stubFor(alice));
        Criteria scope = HohenheimAccess.accessListScope(ctx);
        assertThat(scope).as("a tenant scope is never unconstrained").isNotNull();
        List<Integer> visible = Models.get(AccessListModel.class).find().where(scope).all()
            .stream().map(row -> (Integer) row.get(AccessListModel.ID)).toList();
        assertThat(visible)
            .as("shared plus managed, never a stranger's private list")
            .contains(aliceListId, sharedListId)
            .doesNotContain(bobListId);
    }

    @Test
    void aTenantEditsAndDeletesOnlyItsOwnLists() {
        // Positive anchor: the tenant's own list accepts an edit.
        refusalOf(alice, () -> {
            Row own = Models.get(AccessListModel.class).findById(aliceListId);
            own.set(AccessListModel.NAME, "Alice List Renamed");
            Models.get(AccessListModel.class).save(own);
        }, null);

        // A stranger's list refuses the same edit, on every writer.
        refusalOf(alice, () -> {
            Row foreign = Models.get(AccessListModel.class).findById(bobListId);
            foreign.set(AccessListModel.NAME, "Hijacked");
            Models.get(AccessListModel.class).save(foreign);
        }, "tenant_access_list_not_managed");

        // Deleting it refuses too.
        refusalOf(alice, () -> {
            var model = Models.get(AccessListModel.class);
            model.delete(model.findById(bobListId));
        }, "tenant_access_list_not_managed");
        assertThat(Models.get(AccessListModel.class).findById(bobListId)).isNotNull();
    }

    @Test
    void aTenantCannotPublishAListInstallationWide() {
        refusalOf(alice, () -> {
            Row own = Models.get(AccessListModel.class).findById(aliceListId);
            own.set(AccessListModel.SHARED, true);
            Models.get(AccessListModel.class).save(own);
        }, "tenant_field_frozen");
        assertThat(Models.get(AccessListModel.class).findById(aliceListId)
            .get(AccessListModel.SHARED)).isNotEqualTo(Boolean.TRUE);
    }

    @Test
    void ruleRowsAnswerToTheirParentList() {
        // Positive anchor: a rule lands on the tenant's own list.
        refusalOf(alice, () -> rule(aliceListId), null);

        // A stranger's tree accepts nothing...
        refusalOf(alice, () -> rule(bobListId), "tenant_access_list_not_managed");

        // ...and loses nothing: neither an edit nor a delete of its rules.
        refusalOf(alice, () -> {
            Row foreign = Models.get(AccessRuleModel.class).findById(bobRuleId);
            foreign.set(AccessRuleModel.ENABLED, false);
            Models.get(AccessRuleModel.class).save(foreign);
        }, "tenant_access_list_not_managed");
        refusalOf(alice, () -> {
            var model = Models.get(AccessRuleModel.class);
            model.delete(model.findById(bobRuleId));
        }, "tenant_access_list_not_managed");
        assertThat(Models.get(AccessRuleModel.class).findById(bobRuleId)).isNotNull();
    }

    @Test
    void protectedPathsAnswerToTheSiteAndToTheListsUsability() {
        // Positive anchors: an own list and a shared list both guard an own folder.
        refusalOf(alice, () -> protect(aliceSiteId, "/own", aliceListId), null);
        refusalOf(alice, () -> protect(aliceSiteId, "/shared", sharedListId), null);

        // A stranger's site is not guardable...
        refusalOf(alice, () -> protect(bobSiteId, "/attack", aliceListId),
            "tenant_site_not_managed");

        // ...and a stranger's PRIVATE list is not attachable, even to an own site.
        refusalOf(alice, () -> protect(aliceSiteId, "/foreign", bobListId),
            "tenant_access_list_not_usable");

        // Deleting another tenant's guard is refused: dropping protection is as much a
        // write as adding it.
        int bobGuard = protectAsSystem(bobSiteId, "/bob-private", bobListId);
        refusalOf(alice, () -> {
            var model = Models.get(ProtectedPathModel.class);
            model.delete(model.findById(bobGuard));
        }, "tenant_site_not_managed");
        assertThat(Models.get(ProtectedPathModel.class).findById(bobGuard)).isNotNull();
    }

    // --- Fixture helpers ---------------------------------------------------------------

    private static Row user(String email, String name) {
        Row row = AuthModels.users().createEmptyRow();
        row.set(UserModel.EMAIL, email);
        row.set(UserModel.DISPLAY_NAME, name);
        row.set(UserModel.ENABLED, true);
        row.set(UserModel.CREATED_AT, Instant.now());
        row.set(UserModel.UPDATED_AT, Instant.now());
        AuthModels.users().save(row);
        return row;
    }

    private static Integer site(String name, String slug) {
        Model model = Models.get(SiteModel.class);
        Row row = model.createEmptyRow();
        row.set(SiteModel.NAME, name);
        row.set(SiteModel.SLUG, slug);
        row.set(SiteModel.UPSTREAM_KIND, "hohenheim:static");
        row.set(SiteModel.SETTINGS, Map.of("root_path", "/tmp"));
        row.set(SiteModel.STATUS, "active");
        row.set(SiteModel.ENABLED, true);
        model.save(row);
        return row.get(SiteModel.ID);
    }

    private static int list(String name, boolean shared) {
        var model = Models.get(AccessListModel.class);
        Row row = model.createEmptyRow();
        row.set(AccessListModel.NAME, name);
        row.set(AccessListModel.SATISFY, AccessListModel.SATISFY_ANY);
        row.set(AccessListModel.SHARED, shared);
        model.save(row);
        return row.get(AccessListModel.ID);
    }

    private static int rule(int listId) {
        var model = Models.get(AccessRuleModel.class);
        Row row = model.createEmptyRow();
        row.set(AccessRuleModel.ACCESS_LIST_ID, listId);
        row.set(AccessRuleModel.TYPE, AccessRuleModel.TYPE_IP_ALLOW);
        row.set(AccessRuleModel.DATA, new LinkedHashMap<>(Map.of("network", "127.0.0.1")));
        row.set(AccessRuleModel.ENABLED, true);
        row.set(AccessRuleModel.SORT, 0);
        model.save(row);
        return row.get(AccessRuleModel.ID);
    }

    private static void protect(Integer siteId, String path, int listId) {
        var model = Models.get(ProtectedPathModel.class);
        Row row = model.createEmptyRow();
        row.set(ProtectedPathModel.SITE_ID, siteId);
        row.set(ProtectedPathModel.PATH, path);
        row.set(ProtectedPathModel.ACCESS_LIST_ID, listId);
        model.save(row);
    }

    /** A system write (no request in flight), for planting the other tenant's fixture. */
    private static int protectAsSystem(Integer siteId, String path, int listId) {
        var model = Models.get(ProtectedPathModel.class);
        Row row = model.createEmptyRow();
        row.set(ProtectedPathModel.SITE_ID, siteId);
        row.set(ProtectedPathModel.PATH, path);
        row.set(ProtectedPathModel.ACCESS_LIST_ID, listId);
        model.save(row);
        return row.get(ProtectedPathModel.ID);
    }

    /**
     * Run {@code body} as the given tenant and assert the single refusal key it produces,
     * or that it produces none when {@code expectedKey} is null.
     */
    private static void refusalOf(UserPrincipal principal, Runnable body,
                                  String expectedKey) {
        Violations violations = catchThrowableOfType(
            () -> TenantConduits.as(principal, body), Violations.class);
        if (expectedKey == null) {
            assertThat((Object) violations).as("expected the write to pass").isNull();
            return;
        }
        assertThat((Object) violations).as("expected a refusal: " + expectedKey).isNotNull();
        Violation refusal = violations.all().get(0);
        assertThat(refusal.message().key()).isEqualTo(expectedKey);
    }
}
