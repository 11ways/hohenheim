package be.elevenways.hohenheim.test;

import be.elevenways.zenit.auth.model.GrantModel;
import be.elevenways.zenit.auth.model.PermissionGroupModel;
import be.elevenways.zenit.auth.model.UserModel;
import be.elevenways.zenit.auth.server.AuthModels;
import be.elevenways.zenit.common.orm.datasource.Row;
import com.microsoft.playwright.assertions.PlaywrightAssertions;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Browser journeys for zenit-auth's generated admin resources wired into
 * HohenheimPanel's security group: users and roles each walk
 * list -> create -> edit (with the grants editor) -> row actions -> delete
 * through the REAL panel, asserting on auth_users / auth_grants /
 * auth_permission_groups storage rather than rendered markup.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AuthAdminResourcesTest extends HohenheimTestBase {

    private static final String DIALOG = ".pl-dialog-modal[data-open]";

    @Test
    @Order(1)
    void usersResourceJourneyThroughThePanel() {
        // Step 1: the list renders in the security group with the seeded admin.
        navigateToApp("/admin/users");
        waitForHydration();
        PlaywrightAssertions.assertThat(
            page.locator("pl-app-sidebar a[href='/admin/users']")).hasCount(1);
        assertThat(page.locator("body").textContent()).contains("test@hohenheim.local");

        // Step 2: CREATE with one grant added through the permissions editor.
        navigateToApp("/admin/users/new");
        waitForHydration();
        page.fill("input[name='email']", "journey-user@hohenheim.local");
        page.fill("input[name='display_name']", "Journey User");
        click("pl-permissions-editor button.add-row");
        waitForSelector("input[name='grants.0.permission']");
        page.fill("input[name='grants.0.permission']", "journey.read");
        click(".cms-form-actions pl-button[type='submit']");
        page.waitForCondition(() -> AuthModels.users().find()
            .where(UserModel.EMAIL.eq("journey-user@hohenheim.local")).first() != null);
        Row user = AuthModels.users().find()
            .where(UserModel.EMAIL.eq("journey-user@hohenheim.local")).first();
        Integer userId = user.get(UserModel.ID);
        assertThat(user.get(UserModel.ENABLED)).as("created users start enabled").isTrue();
        page.waitForCondition(() -> grantValue(userId, "journey.read") != null);
        assertThat(grantValue(userId, "journey.read"))
            .as("the grant added in the create form must be stored").isTrue();

        // Step 3: EDIT -- the editor seeds the stored grant; add a negative one
        // and a group membership, storage-asserted.
        navigateToApp("/admin/users/" + userId);
        waitForHydration();
        waitForSelector("input[name='grants.0.permission']");
        assertThat(page.locator("input[name='grants.0.permission']").inputValue())
            .as("the edit form must seed the stored grant").isEqualTo("journey.read");
        // Add BOTH rows before filling them: the editor's row list is a
        // non-keyed each, so a later addRow re-renders earlier rows from the
        // model and a not-yet-synced select would visually reset.
        click("pl-permissions-editor button.add-row");
        waitForSelector("input[name='grants.1.permission']");
        click("pl-permissions-editor button.add-row");
        waitForSelector("input[name='grants.2.permission']");
        page.fill("input[name='grants.1.permission']", "journey.write");
        page.selectOption("select[name='grants.1.value']", "false");
        page.fill("input[name='grants.2.permission']", "group.journey-role");
        click(".cms-form-actions pl-button[type='submit']");
        page.waitForCondition(() -> grantValue(userId, "group.journey-role") != null);
        assertThat(grantValue(userId, "journey.write"))
            .as("the NEGATIVE grant must be stored with value=false").isFalse();
        assertThat(grantValue(userId, "group.journey-role"))
            .as("the group membership must be stored as a group.<slug> grant").isTrue();

        // Step 4: the toggle-enabled row action confirms through the shell dialog.
        navigateToApp("/admin/users");
        waitForHydration();
        String row = "pl-table-row:has-text('journey-user@hohenheim.local') ";
        String toggle = row + "pl-button[formaction$='/action/toggle-enabled']";
        waitForSelector(toggle);
        click(toggle);
        assertIsVisible(DIALOG);
        click("[data-cms-confirm-cancel]");
        page.waitForTimeout(300);
        assertThat((Boolean) reload(userId).get(UserModel.ENABLED))
            .as("cancel must not disable the user").isTrue();
        click(toggle);
        assertIsVisible(DIALOG);
        click("[data-cms-confirm-ok]");
        page.waitForCondition(() -> Boolean.FALSE.equals(reload(userId).get(UserModel.ENABLED)));

        // Step 5: the logout-everywhere row action confirms and succeeds. Fresh
        // navigation: the toggle's refresh re-render is still swapping rows, and
        // clicking into that swap races a detaching element.
        navigateToApp("/admin/users");
        waitForHydration();
        String logout = row + "pl-button[formaction$='/action/logout-everywhere']";
        waitForSelector(logout);
        click(logout);
        assertIsVisible(DIALOG);
        click("[data-cms-confirm-ok]");
        page.waitForCondition(() -> page.locator(".pl-dialog-modal[data-open]").count() == 0);

        // Step 6: DELETE through the row's destructive confirm; the row and its
        // grants disappear from storage.
        navigateToApp("/admin/users");
        waitForHydration();
        String delete = row + "pl-button[formaction$='/delete']";
        waitForSelector(delete);
        click(delete);
        assertIsVisible(DIALOG);
        click("[data-cms-confirm-ok]");
        page.waitForCondition(() -> AuthModels.users().find()
            .where(UserModel.ID.eq(userId)).first() == null);
        page.waitForCondition(() -> AuthModels.grants().find()
            .where(GrantModel.SUBJECT_TYPE.eq("user"))
            .and(GrantModel.SUBJECT_ID.eq(userId)).count() == 0);
    }

    @Test
    @Order(2)
    void rolesResourceJourneyThroughThePanel() {
        // Step 1: CREATE a role with one grant.
        navigateToApp("/admin/roles/new");
        waitForHydration();
        page.fill("input[name='slug']", "journey-role");
        page.fill("input[name='title']", "Journey Role");
        click("pl-permissions-editor button.add-row");
        waitForSelector("input[name='grants.0.permission']");
        page.fill("input[name='grants.0.permission']", "journey.role.perm");
        click(".cms-form-actions pl-button[type='submit']");
        page.waitForCondition(() -> AuthModels.permissionGroups().find()
            .where(PermissionGroupModel.SLUG.eq("journey-role")).first() != null);
        Row role = AuthModels.permissionGroups().find()
            .where(PermissionGroupModel.SLUG.eq("journey-role")).first();
        Integer roleId = role.get(PermissionGroupModel.ID);
        page.waitForCondition(() -> groupGrantValue(roleId, "journey.role.perm") != null);

        // Step 2: EDIT -- drop the grant (remove the row), storage loses the row.
        navigateToApp("/admin/roles/" + roleId);
        waitForHydration();
        waitForSelector("input[name='grants.0.permission']");
        click("pl-permissions-editor button.remove");
        page.waitForCondition(() ->
            page.locator("input[name='grants.0.permission']").count() == 0);
        click(".cms-form-actions pl-button[type='submit']");
        page.waitForCondition(() -> groupGrantValue(roleId, "journey.role.perm") == null);

        // Step 3: a membership grant references the role; deleting the role from
        // its detail form cleans the membership up.
        Row member = AuthModels.users().createEmptyRow();
        member.set(UserModel.EMAIL, "journey-member@hohenheim.local");
        member.set(UserModel.DISPLAY_NAME, "Journey Member");
        member.set(UserModel.ENABLED, true);
        member.set(UserModel.CREATED_AT, Instant.now());
        member.set(UserModel.UPDATED_AT, Instant.now());
        AuthModels.users().save(member);
        Integer memberId = member.get(UserModel.ID);
        Row membership = AuthModels.grants().createEmptyRow();
        membership.set(GrantModel.SUBJECT_TYPE, "user");
        membership.set(GrantModel.SUBJECT_ID, memberId);
        membership.set(GrantModel.PERMISSION, "group.journey-role");
        membership.set(GrantModel.VALUE, true);
        membership.set(GrantModel.CREATED_AT, Instant.now());
        AuthModels.grants().save(membership);

        try {
            navigateToApp("/admin/roles/" + roleId);
            waitForHydration();
            waitForSelector("form.cms-delete-form pl-button");
            click("form.cms-delete-form pl-button");
            assertIsVisible(DIALOG);
            click("[data-cms-confirm-ok]");
            page.waitForCondition(() -> AuthModels.permissionGroups().find()
                .where(PermissionGroupModel.ID.eq(roleId)).first() == null);
            page.waitForCondition(() -> AuthModels.grants().find()
                .where(GrantModel.SUBJECT_TYPE.eq("user"))
                .and(GrantModel.SUBJECT_ID.eq(memberId))
                .and(GrantModel.PERMISSION.eq("group.journey-role")).first() == null);
        } finally {
            AuthModels.users().find().where(UserModel.ID.eq(memberId)).delete();
        }
    }

    private static Boolean grantValue(Integer userId, String permission) {
        Row row = AuthModels.grants().find()
            .where(GrantModel.SUBJECT_TYPE.eq("user"))
            .and(GrantModel.SUBJECT_ID.eq(userId))
            .and(GrantModel.PERMISSION.eq(permission))
            .first();
        return row == null ? null : row.get(GrantModel.VALUE);
    }

    private static Boolean groupGrantValue(Integer roleId, String permission) {
        Row row = AuthModels.grants().find()
            .where(GrantModel.SUBJECT_TYPE.eq("group"))
            .and(GrantModel.SUBJECT_ID.eq(roleId))
            .and(GrantModel.PERMISSION.eq(permission))
            .first();
        return row == null ? null : row.get(GrantModel.VALUE);
    }

    private static Row reload(Integer userId) {
        return AuthModels.users().find().where(UserModel.ID.eq(userId)).first();
    }
}
