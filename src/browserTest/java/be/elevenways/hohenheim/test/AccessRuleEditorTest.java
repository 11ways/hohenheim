package be.elevenways.hohenheim.test;

import be.elevenways.hohenheim.model.AccessListModel;
import be.elevenways.hohenheim.model.AccessRuleModel;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.validation.Violations;
import org.junit.jupiter.api.Test;

import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Authors a NESTED rule tree through the admin UI end to end: add a group, add a rule
 * inside that group, fill it in, switch it on, reorder, and reload to prove the tree that
 * comes back is the one that was built; and separately walks one rule through refusal,
 * draft, enable and both switch states.
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
        assertThat(page.locator(".hh-rule-row .hh-rule-state[data-rule-state='off']").count())
            .as("step 5: nothing is left switched off").isZero();
        assertThat(page.locator(".hh-rule-row .hh-rule-state[data-rule-state='on']").count())
            .as("step 5: and both nodes SAY they are on, rather than showing nothing")
            .isEqualTo(2);

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
     * The two halves of the rule's data check, and what each state SAYS: nonsense is
     * refused the moment it is saved, an empty draft is not, and the row states on/off in
     * words on the tab, on the list and on the button that flips it.
     */
    @Test
    void refusesNonsenseSettingsOnSaveAndStatesTheOnOffState() throws Exception {
        Row list = Models.get(AccessListModel.class).createEmptyRow();
        list.set(AccessListModel.NAME, "Rule validation list");
        list.set(AccessListModel.SATISFY, AccessListModel.SATISFY_ANY);
        Models.get(AccessListModel.class).save(list);
        int listId = list.get(AccessListModel.ID);
        String rulesUrl = "/admin/access-lists/" + listId + "/page/rules";
        AccessRuleModel model = Models.get(AccessRuleModel.class);

        // 0. The check itself, at its own home: the SAME call refuses nonsense whichever
        //    way the switch stands, and lets an empty draft through while it is off.
        Row nonsense = model.createEmptyRow();
        nonsense.set(AccessRuleModel.ACCESS_LIST_ID, listId);
        nonsense.set(AccessRuleModel.TYPE, AccessRuleModel.TYPE_IP_ALLOW);
        nonsense.set(AccessRuleModel.DATA, Map.of("network", "not-an-ip-address"));
        nonsense.set(AccessRuleModel.ENABLED, false);
        assertThatThrownBy(() -> model.save(nonsense))
            .as("step 0: a nonsense network is refused even for a rule that is OFF")
            .isInstanceOf(Violations.class);

        Row emptyDraft = model.createEmptyRow();
        emptyDraft.set(AccessRuleModel.ACCESS_LIST_ID, listId);
        emptyDraft.set(AccessRuleModel.TYPE, AccessRuleModel.TYPE_IP_ALLOW);
        emptyDraft.set(AccessRuleModel.ENABLED, false);
        model.save(emptyDraft);
        assertThat((Integer) emptyDraft.get(AccessRuleModel.ID))
            .as("step 0: while an EMPTY rule that is off is a legitimate draft").isNotNull();
        emptyDraft.set(AccessRuleModel.ENABLED, true);
        assertThatThrownBy(() -> model.save(emptyDraft))
            .as("step 0: which the enable gate then refuses, through that same home")
            .isInstanceOf(Violations.class);
        model.delete(model.findById(emptyDraft.get(AccessRuleModel.ID)));

        // 1. A leaf of the tree, born the way the tab's add form makes one: switched off
        //    and empty. Created directly here because what follows is about SAVING it,
        //    not about the add form the journey above already walks.
        Row leaf = model.createEmptyRow();
        leaf.set(AccessRuleModel.ACCESS_LIST_ID, listId);
        leaf.set(AccessRuleModel.TYPE, AccessRuleModel.TYPE_IP_ALLOW);
        leaf.set(AccessRuleModel.ENABLED, false);
        model.save(leaf);
        int ruleId = leaf.get(AccessRuleModel.ID);
        String saveUrl = "/admin/access-rules/" + ruleId;

        // 2. Garbage in the kind's own field is refused AT SAVE, in the sentence that says
        //    what belongs there -- not accepted now and complained about at the enable gate.
        HttpResponse<String> nonsenseSave = httpPostForm(saveUrl,
            "type=ip_allow&data.network=not-an-ip-address", sessionToken, csrfToken);
        assertThat(nonsenseSave.statusCode())
            .as("step 2: a refused save rerenders the form instead of redirecting")
            .isEqualTo(200);
        assertThat(nonsenseSave.body())
            .as("step 2: and names what a network looks like")
            .contains("Enter one IP address or CIDR network");
        assertThat(AccessRuleModel.dataOf(model.findById(ruleId)).get("network"))
            .as("step 2: nothing nonsensical reached the row").isNull();

        // 3. EMPTY while switched off stays allowed: a new rule is a draft by design, and
        //    the add form promises exactly that.
        HttpResponse<String> draftSave = httpPostForm(saveUrl,
            "type=ip_allow&data.network=", sessionToken, csrfToken);
        assertThat(draftSave.statusCode())
            .as("step 3: the empty draft saves").isEqualTo(302);
        assertThat((Boolean) model.findById(ruleId).get(AccessRuleModel.ENABLED))
            .as("step 3: still switched off").isFalse();

        // 4. Switching it ON with nothing filled in is refused by the SAME check, with the
        //    same sentence.
        HttpResponse<String> enableEmpty = httpPostForm(saveUrl,
            "type=ip_allow&data.network=&enabled=true", sessionToken, csrfToken);
        assertThat(enableEmpty.statusCode())
            .as("step 4: enabling an empty rule is refused").isEqualTo(200);
        assertThat(enableEmpty.body())
            .as("step 4: with the same message the save refusal used")
            .contains("Enter one IP address or CIDR network");
        assertThat((Boolean) model.findById(ruleId).get(AccessRuleModel.ENABLED))
            .as("step 4: and the rule is still off").isFalse();

        // 5. Filled in and switched on, it saves -- and the tab SAYS it is on, instead of
        //    saying nothing at all.
        HttpResponse<String> goodSave = httpPostForm(saveUrl,
            "type=ip_allow&data.network=10.1.0.0%2F16&enabled=true", sessionToken, csrfToken);
        assertThat(goodSave.statusCode()).as("step 5: a usable rule saves").isEqualTo(302);
        assertThat((Boolean) model.findById(ruleId).get(AccessRuleModel.ENABLED))
            .as("step 5: the rule is on").isTrue();

        navigateToApp(rulesUrl);
        waitForHydration();
        String row = ".hh-rule-row[data-rule-id='" + ruleId + "'] ";
        assertThat(page.locator(row + ".hh-rule-state[data-rule-state='on']").textContent().trim())
            .as("step 5: the state pill says ON in words").isEqualTo("On");
        assertThat(page.locator(row + "pl-button[data-action-id*='toggle']").textContent().trim())
            .as("step 5: and the button says what the click will DO").isEqualTo("Switch off");

        // 6. Flipping it says the opposite, both times: two states, both stated.
        page.click(row + "pl-button[data-action-id*='toggle']");
        page.waitForSelector("pl-toast, .pl-toast");
        waitForHydration();
        navigateToApp(rulesUrl);
        waitForHydration();
        assertThat(page.locator(row + ".hh-rule-state[data-rule-state='off']").textContent().trim())
            .as("step 6: the switched-off rule says OFF").isEqualTo("Off");
        assertThat(page.locator(row + "pl-button[data-action-id*='toggle']").textContent().trim())
            .as("step 6: and the button now offers the other direction").isEqualTo("Switch on");

        // 7. The rule LIST tells the same story in the same words as the tab. Searched by
        //    network, because the list is shared with every other rule this run created.
        navigateToApp("/admin/access-rules?q=10.1.0.0");
        page.waitForSelector("pl-table-row[data-row-key='" + ruleId + "']");
        waitForHydration();
        assertThat(page.locator("pl-table-row[data-row-key='" + ruleId + "'] "
                + "pl-table-cell[data-column='enabled']").textContent().trim())
            .as("step 7: the list states the switch as On/Off, not as a colour")
            .isEqualTo("Off");
    }

    /**
     * The add form survives a SOFT navigation onto the tab: reaching the Rules tab by
     * clicking it re-renders the page client-side, where the POST target is a DRY-revived
     * one, and a form that loses its method attribute there submits GET and earns a 405.
     */
    @Test
    void keepsThePostMethodWhenTheTabIsReachedBySoftNavigation() {
        // 1. A list, reached the way an operator reaches it: the record's own edit page.
        Row list = Models.get(AccessListModel.class).createEmptyRow();
        list.set(AccessListModel.NAME, "Soft nav list");
        list.set(AccessListModel.SATISFY, AccessListModel.SATISFY_ANY);
        Models.get(AccessListModel.class).save(list);
        int listId = list.get(AccessListModel.ID);

        navigateToApp("/admin/access-lists/" + listId);
        waitForHydration();

        // 2. Click the Rules tab instead of typing its URL: an anchor inside the shell is a
        //    soft navigation, so the tab's template renders in the BROWSER.
        page.click(".cms-record-tabs a[href$='/page/rules']");
        page.waitForSelector("#add-rule-form");
        waitForHydration();

        // 3. The add form still declares POST. A missing attribute reads back as "get",
        //    which is exactly the 405 this covers.
        assertThat(page.locator("#add-rule-form").evaluate("form => form.method"))
            .as("step 3: the client-rendered add form still posts").isEqualTo("post");

        // 4. And it actually posts: the click creates the group rather than 405-ing.
        page.click("#add-rule-submit");
        page.waitForSelector(".hh-rule-row");
        assertThat(Models.get(AccessRuleModel.class).findForAccessList(listId))
            .as("step 4: the add lane created the rule").hasSize(1);
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
