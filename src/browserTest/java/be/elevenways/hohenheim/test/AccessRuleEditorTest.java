package be.elevenways.hohenheim.test;

import be.elevenways.hohenheim.model.AccessListModel;
import be.elevenways.hohenheim.model.AccessRuleModel;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Authors a NESTED rule tree through the admin UI end to end: add a group, add a rule
 * inside that group, fill it in, switch it on, reorder, and reload to prove the tree that
 * comes back is the one that was built.
 */
class AccessRuleEditorTest extends HohenheimTestBase {

    @Test
    void authorsANestedRuleTreeThroughTheAdminUi() {
        // 1. A list starts with no rules at all, and says so.
        Row list = Models.get(AccessListModel.class).createEmptyRow();
        list.set(AccessListModel.NAME, "Editor journey list");
        list.set(AccessListModel.SATISFY, AccessListModel.SATISFY_ALL);
        Models.get(AccessListModel.class).save(list);
        int listId = list.get(AccessListModel.ID);
        String rulesUrl = "/admin/access-lists/" + listId + "/page/rules";

        navigateToApp(rulesUrl);
        waitForHydration();
        assertThat(page.locator("pl-empty-state").count())
            .as("step 1: a list with no rules shows the empty state").isEqualTo(1);
        assertThat(page.content())
            .as("step 1: and the root group's mode is stated in words")
            .contains("EVERY one of these rules passes");

        // 2. Add a GROUP at the top level. The type select defaults to it, so this is one
        //    click on a page that came from the server.
        page.click("#add-rule-submit");
        page.waitForSelector(".hh-rule-row");
        waitForHydration();
        assertThat(page.locator(".hh-rule-row").count())
            .as("step 2: the group is in the tree").isEqualTo(1);
        assertThat(page.locator(".hh-rule-row[data-rule-type='group']").count())
            .as("step 2: as a group").isEqualTo(1);

        // 3. Add an ALLOWED NETWORK rule INSIDE that group: pick the group as the parent and
        //    the type as the rule kind, both through the real selects.
        navigateToApp(rulesUrl);
        waitForHydration();
        chooseOption("parent_id", groupId(listId));
        chooseOption("type", AccessRuleModel.TYPE_IP_ALLOW);
        page.click("#add-rule-submit");
        // A leaf lands on its OWN form, so it can be filled in immediately -- the redirect
        // the add endpoint answers with.
        page.waitForURL("**/admin/access-rules/**");
        waitForHydration();
        assertThat(Models.get(AccessRuleModel.class).findForAccessList(listId).stream()
                .map(rule -> rule.get(AccessRuleModel.TYPE) + "/" + rule.get(AccessRuleModel.PARENT_ID))
                .toList())
            .as("step 3: the group plus the leaf that was just added INTO it")
            .containsExactlyInAnyOrder("group/null",
                AccessRuleModel.TYPE_IP_ALLOW + "/" + groupIdValue(listId));
        page.waitForSelector("[name='data.network']");

        // 4. Fill the rule in and switch it on. A new leaf is born switched OFF, which is
        //    what keeps live traffic flowing while it is half-typed.
        int ruleId = leafId(listId);
        Row fresh = Models.get(AccessRuleModel.class).findById(ruleId);
        assertThat((Boolean) fresh.get(AccessRuleModel.ENABLED))
            .as("step 4: a new leaf starts switched off").isFalse();

        page.fill("pl-input[name='data.network'] input", "10.0.0.0/8");
        page.click("pl-switch[name='enabled'] button");
        page.click(".cms-form-actions pl-button[type='submit']");
        page.waitForSelector("pl-toast, .pl-toast");
        waitForHydration();

        Row saved = Models.get(AccessRuleModel.class).findById(ruleId);
        assertThat(AccessRuleModel.dataOf(saved).get("network"))
            .as("step 4: the network reached the per-type data map").isEqualTo("10.0.0.0/8");
        assertThat((Boolean) saved.get(AccessRuleModel.ENABLED))
            .as("step 4: and the operator switched it on").isTrue();
        assertThat((Integer) saved.get(AccessRuleModel.PARENT_ID))
            .as("step 4: nested inside the group it was added to")
            .isEqualTo(groupIdValue(listId));

        // 5. Back on the tab, the tree RELOADS as it was built: a group with a child under
        //    it, the child indented one level and numbered inside its parent.
        navigateToApp(rulesUrl);
        waitForHydration();
        assertThat(page.locator(".hh-rule-row").count())
            .as("step 5: both nodes render").isEqualTo(2);
        assertThat(page.locator(".hh-rule-row").nth(1).getAttribute("style"))
            .as("step 5: the child is indented one level").contains("--hh-rule-depth: 1");
        assertThat(page.locator(".hh-rule-row").nth(1).locator(".hh-rule-path").textContent().trim())
            .as("step 5: and numbered inside its parent").isEqualTo("1.1");
        assertThat(page.content())
            .as("step 5: the rule's own summary reads as what it decides")
            .contains("10.0.0.0/8");
        assertThat(page.locator(".hh-rule-row [data-rule-disabled]").count())
            .as("step 5: nothing is left switched off").isZero();

        // 6. A second leaf inside the same group, and REORDERING within that group.
        chooseOption("parent_id", groupId(listId));
        chooseOption("type", AccessRuleModel.TYPE_IP_DENY);
        page.click("#add-rule-submit");
        page.waitForURL("**/admin/access-rules/**");
        navigateToApp(rulesUrl);
        waitForHydration();
        assertThat(page.locator(".hh-rule-row").count())
            .as("step 6: three nodes now").isEqualTo(3);

        List<Row> siblings = Models.get(AccessRuleModel.class)
            .findChildren(listId, groupIdValue(listId));
        assertThat(siblings).as("step 6: both leaves live inside the group").hasSize(2);
        int first = siblings.get(0).get(AccessRuleModel.ID);

        String moveDown = ".hh-rule-row[data-rule-id='" + first + "'] "
            + "pl-button[data-action-id*='move_down']";
        assertThat(page.locator(moveDown).count())
            .as("step 6: the rule resource's own move action renders on the row").isEqualTo(1);
        page.click(moveDown);
        page.waitForSelector("pl-toast, .pl-toast");
        waitForHydration();

        List<Row> reordered = Models.get(AccessRuleModel.class)
            .findChildren(listId, groupIdValue(listId));
        assertThat((Integer) reordered.get(1).get(AccessRuleModel.ID))
            .as("step 6: the moved rule is now the second child").isEqualTo(first);

        // 7. And the tree the server renders after all of that is still the tree that was
        //    authored -- nesting, order and all.
        navigateToApp(rulesUrl);
        waitForHydration();
        assertThat(page.locator(".hh-rule-row").nth(1).locator(".hh-rule-path").textContent().trim())
            .as("step 7: the first child keeps outline number 1.1").isEqualTo("1.1");
        assertThat(page.locator(".hh-rule-row").nth(2).locator(".hh-rule-path").textContent().trim())
            .as("step 7: and the second is 1.2").isEqualTo("1.2");
        assertThat(page.locator(".hh-rule-row").nth(2).getAttribute("data-rule-id"))
            .as("step 7: which is the rule that was moved down").isEqualTo(String.valueOf(first));
    }

    /**
     * Open a pl-select by its field and pick one option (the chevron is pointer-inert), then
     * wait for the overlay to close: the popup is portalled and a click that lands while it
     * is still open never reaches the button underneath.
     */
    private void chooseOption(String name, String value) {
        page.click("pl-select[name='" + name + "'] .pl-select-field");
        page.waitForSelector("he-bottom .pl-select-popup[data-open]");
        page.click("he-bottom .pl-select-popup[data-open] div[role='option'][data-value='"
            + value + "']");
        page.waitForSelector("he-bottom .pl-select-popup[data-open]",
            new com.microsoft.playwright.Page.WaitForSelectorOptions()
                .setState(com.microsoft.playwright.options.WaitForSelectorState.DETACHED));
        assertThat(page.locator("pl-select[name='" + name + "'] .pl-select-value").textContent())
            .as("the select shows the chosen option").isNotBlank();
    }

    private static String groupId(int listId) {
        return String.valueOf(groupIdValue(listId));
    }

    private static Integer groupIdValue(int listId) {
        return Models.get(AccessRuleModel.class).find()
            .where(AccessRuleModel.ACCESS_LIST_ID.eq(listId))
            .and(AccessRuleModel.TYPE.eq(AccessRuleModel.TYPE_GROUP))
            .first().get(AccessRuleModel.ID);
    }

    private static int leafId(int listId) {
        for (Row rule : Models.get(AccessRuleModel.class).findForAccessList(listId)) {
            if (!AccessRuleModel.TYPE_GROUP.equals(rule.get(AccessRuleModel.TYPE))) {
                return rule.get(AccessRuleModel.ID);
            }
        }
        throw new IllegalStateException("no leaf rule was created");
    }
}
