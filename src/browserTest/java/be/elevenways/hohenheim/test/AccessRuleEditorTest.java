package be.elevenways.hohenheim.test;

import be.elevenways.hohenheim.HohenheimCapabilities;
import be.elevenways.hohenheim.HohenheimSlugs;
import be.elevenways.hohenheim.model.AccessListModel;
import be.elevenways.hohenheim.model.AccessRuleModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.server.auth.BasicCredentials;
import be.elevenways.hohenheim.server.cms.AccessRuleParts;
import be.elevenways.zenit.auth.model.GrantSubjectType;
import be.elevenways.zenit.auth.server.RecordGrants;
import be.elevenways.zenit.cms.common.page.CmsEndpoints;
import be.elevenways.zenit.cms.common.page.CmsRoutes;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.validation.Violations;
import com.microsoft.playwright.options.ViewportSize;
import org.junit.jupiter.api.Test;

import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Authors a NESTED rule tree through the admin UI end to end, each rule added in ONE step through the add dialog:
 * a login switched on from the start, a group, rules added inside that group, reordering, the line naming what the
 * list guards, and who is offered the add at all; and separately walks one rule through refusal, draft, enable and
 * both switch states.
 */
class AccessRuleEditorTest extends HohenheimTestBase {

    private static final String DIALOG = ".pl-alertdialog-modal[data-open]";
    private static final String INPUT_FORM = DIALOG + " form[data-cms-action-input]";
    private static final String OK = DIALOG + " pl-button[data-cms-confirm-ok]";
    private static final String ADD_RULE = "[data-access-rule-add] pl-button[data-action-id*='access_rule_add']";

    @Test
    void addsARuleInOneStepAndNestsRulesInsideAGroup() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        Row list = Models.get(AccessListModel.class).createEmptyRow();
        list.set(AccessListModel.NAME, "Editor journey " + suffix);
        list.set(AccessListModel.SATISFY, AccessListModel.SATISFY_ALL);
        Models.get(AccessListModel.class).save(list);
        int listId = list.get(AccessListModel.ID);
        String rulesUrl = "/admin/access-lists/" + listId + "/page/rules";

        // 1. A list starts with no rules, says so, says nothing uses it, and offers ONE "Add rule", in the Rules card:
        //    no form of the page's own, and no "Add inside" select anywhere.
        navigateToApp(rulesUrl);
        waitForHydration();
        assertThat(page.locator("pl-empty-state").count())
            .as("step 1: a list with no rules shows the empty state").isEqualTo(1);
        assertThat(page.locator("pl-empty-state").textContent())
            .as("step 1: which says everyone gets in meanwhile").contains("lets every request through");
        assertThat(page.content())
            .as("step 1: and the root group's mode is stated in words")
            .contains("EVERY one of these rules passes");
        assertThat(page.locator("[data-access-list-guards]").textContent())
            .as("step 1: a list nothing uses says so in its one line").contains("Guards nothing yet.");
        assertThat(page.locator("pl-button[data-action-id*='access_rule_add']").count())
            .as("step 1: the add is drawn exactly once").isEqualTo(1);
        assertThat(page.locator(ADD_RULE).count()).as("step 1: in the Rules card's header").isEqualTo(1);
        assertThat(page.locator("pl-select[name='parent_id'], #add-rule-form").count())
            .as("step 1: no page form and no parent select").isZero();

        // 2. Add rule opens the dialog titled by the list, on a password rule that is switched on. The dialog draws
        //    its words in the browser, where they may land after it opens: wait for them, never read the first frame.
        click(ADD_RULE);
        page.waitForSelector(INPUT_FORM + " " + field("data.username") + " input");
        assertContainsText(DIALOG + " pl-alert-dialog-title", "Add a rule to Editor journey " + suffix,
            "step 2: the dialog names the list");
        assertContainsText(DIALOG + " pl-alert-dialog-description", "It counts from the moment you add it.",
            "step 2: and says when the rule counts");
        assertContainsText(DIALOG + " pl-button[data-cms-confirm-cancel]", "Cancel",
            "step 2: and offers to cancel");
        assertThat(page.locator(DIALOG + " pl-select[name='type'] .pl-select-value").textContent())
            .as("step 2: the kind defaults to a password").contains("Password");
        assertThat(page.locator(DIALOG + " pl-switch[name='enabled'] > button").getAttribute("aria-checked"))
            .as("step 2: and the rule is switched on").isEqualTo("true");

        // 3. A blank username is refused IN the dialog, on its field, and nothing is written.
        click(OK);
        page.waitForSelector(DIALOG + " " + field("data.username") + " pl-field-error");
        assertThat(page.locator(DIALOG).count()).as("step 3: the dialog stays open").isEqualTo(1);
        assertThat(Models.get(AccessRuleModel.class).findForAccessList(listId))
            .as("step 3: no rule was written").isEmpty();

        // 4. With a username and a password the rule lands at the top, switched ON, its password stored as the hash
        //    the request-time gate verifies the login against.
        page.locator(DIALOG + " " + field("data.username") + " input:not([type='hidden'])").fill("matthias");
        page.locator(DIALOG + " " + field("data.password") + " input:not([type='hidden'])").fill("correct horse");
        waitForNavigationEnded(() -> click(OK));
        waitForHidden(DIALOG);
        page.waitForSelector(".hh-rule-row");
        page.waitForSelector("pl-toast, .pl-toast");
        waitForHydration();
        List<Row> added = Models.get(AccessRuleModel.class).findForAccessList(listId);
        assertThat(added).as("step 4: one rule").hasSize(1);
        Row login = added.get(0);
        assertThat((String) login.get(AccessRuleModel.TYPE)).as("step 4: a password rule")
            .isEqualTo(AccessRuleModel.TYPE_BASIC_AUTH);
        assertThat((Integer) login.get(AccessRuleModel.PARENT_ID)).as("step 4: at the top of the tree").isNull();
        assertThat((Boolean) login.get(AccessRuleModel.ENABLED)).as("step 4: switched on from the start").isTrue();
        String stored = (String) AccessRuleModel.dataOf(login).get(AccessRuleModel.BASIC_AUTH_PASSWORD.getName());
        assertThat(BasicCredentials.isHashed(stored)).as("step 4: the password is stored as a hash").isTrue();
        assertThat(BasicCredentials.matchesHeader(basic("matthias", "correct horse"), "matthias", stored, "test"))
            .as("step 4: the gate's own check accepts the login").isTrue();
        assertThat(BasicCredentials.matchesHeader(basic("matthias", "wrong"), "matthias", stored, "test"))
            .as("step 4: and refuses another password").isFalse();
        assertThat(page.locator(".hh-rule-row .hh-rule-state[data-rule-state='on']").count())
            .as("step 4: the row says it is on").isEqualTo(1);
        assertThat(page.content()).as("step 4: the password is nowhere on the page").doesNotContain("correct horse");

        // 5. A group, added the same way: the kind is chosen in the dialog.
        click(ADD_RULE);
        page.waitForSelector(INPUT_FORM + " " + field("data.username") + " input");
        chooseOption("type", AccessRuleModel.TYPE_GROUP);
        waitForNavigationEnded(() -> click(OK));
        waitForHidden(DIALOG);
        page.waitForSelector(".hh-rule-row[data-rule-type='group']");
        waitForHydration();
        int groupId = groupIdValue(listId);
        String groupRow = ".hh-rule-row[data-rule-id='" + groupId + "'] ";
        String addInside = "pl-button[data-action-id*='access_rule_add_inside']";
        assertThat(page.locator(groupRow + addInside).count())
            .as("step 5: the group's own row offers Add inside").isEqualTo(1);
        assertThat(page.locator(".hh-rule-row[data-rule-id='" + login.get(AccessRuleModel.ID) + "'] " + addInside)
            .count()).as("step 5: a leaf offers none").isZero();

        // 6. Add inside lands an allowed network UNDER the group; the tree reloads with it indented and numbered
        //    inside its parent.
        addInside(groupRow + addInside, AccessRuleModel.TYPE_IP_ALLOW, "10.0.0.0/8");
        assertThat(Models.get(AccessRuleModel.class).findChildren(listId, groupId))
            .as("step 6: the rule landed inside the group").hasSize(1);
        navigateToApp(rulesUrl);
        waitForHydration();
        assertThat(page.locator(".hh-rule-row").count()).as("step 6: three nodes render").isEqualTo(3);
        assertThat(page.locator(".hh-rule-row").nth(2).getAttribute("style"))
            .as("step 6: the child is indented one level").contains("--hh-rule-depth: 1");
        assertThat(page.locator(".hh-rule-row").nth(2).locator(".hh-rule-path").textContent().trim())
            .as("step 6: and numbered inside its parent").isEqualTo("2.1");
        assertThat(page.content()).as("step 6: the rule's own summary reads as what it decides")
            .contains("10.0.0.0/8");
        assertThat(page.locator(".hh-rule-row .hh-rule-state[data-rule-state='off']").count())
            .as("step 6: nothing is left switched off").isZero();

        // 7. A second rule inside the same group, and REORDERING within that group. The moves and the confirmed
        //    delete live in the row's menu (the list's own row menu), never as buttons on the row.
        addInside(groupRow + addInside, AccessRuleModel.TYPE_IP_DENY, "10.1.0.0/16");
        navigateToApp(rulesUrl);
        waitForHydration();
        List<Row> siblings = Models.get(AccessRuleModel.class).findChildren(listId, groupId);
        assertThat(siblings).as("step 7: both leaves live inside the group").hasSize(2);
        int first = siblings.get(0).get(AccessRuleModel.ID);
        String firstRow = ".hh-rule-row[data-rule-id='" + first + "'] ";
        String trigger = firstRow + "cms-group-row-menu pl-dropdown-menu-trigger pl-button";
        String menu = "he-bottom .pl-dropdown-menu-content__popup:visible button.cms-menu-action";
        String moveDown = menu + "[data-action-id*='move_down']";
        String delete = menu + "[data-action-id*='delete']";
        assertThat(page.locator(firstRow + "pl-button[data-action-id*='move_']").count())
            .as("step 7: no move is a button on the row").isZero();
        assertThat(page.locator(firstRow + "pl-button[data-action-id*='toggle']").count())
            .as("step 7: a leaf's switch stays on the row").isEqualTo(1);
        click(trigger);
        page.waitForSelector(moveDown);
        assertThat(page.locator(menu + "[data-action-id*='move_up']").count())
            .as("step 7: the row's menu offers both moves").isEqualTo(1);
        page.locator(delete).first().click();
        page.waitForSelector(DIALOG);
        assertContainsText(DIALOG + " pl-button[data-cms-confirm-cancel]", "Cancel",
            "step 7: the menu's delete still asks first");
        click(DIALOG + " pl-button[data-cms-confirm-cancel]");
        waitForHidden(DIALOG);
        assertThat(Models.get(AccessRuleModel.class).findById(first))
            .as("step 7: a cancelled delete deletes nothing").isNotNull();
        click(trigger);
        page.waitForSelector(moveDown);
        page.click(moveDown);
        page.waitForSelector("pl-toast, .pl-toast");
        waitForHydration();
        assertThat((Integer) Models.get(AccessRuleModel.class).findChildren(listId, groupId).get(1)
                .get(AccessRuleModel.ID))
            .as("step 7: the moved rule is now the second child").isEqualTo(first);
        navigateToApp(rulesUrl);
        waitForHydration();
        assertThat(page.locator(".hh-rule-row").nth(3).locator(".hh-rule-path").textContent().trim())
            .as("step 7: the second child is 2.2").isEqualTo("2.2");
        assertThat(page.locator(".hh-rule-row").nth(3).getAttribute("data-rule-id"))
            .as("step 7: which is the rule that was moved down").isEqualTo(String.valueOf(first));

        // 8. Once an app uses the list, its one line names the app and leads to it.
        int siteId = site("rules-journey-" + suffix);
        int panelSiteId = site("rules-journey-panel-" + suffix);
        UUID managerId = ApiSupport.user("rules-manager-" + suffix + "@hohenheim.local", "Rules Manager");
        UUID outsiderId = ApiSupport.user("rules-outsider-" + suffix + "@hohenheim.local", "Rules Outsider");
        try {
            Row site = Models.get(SiteModel.class).findById(siteId);
            site.set(SiteModel.ACCESS_LIST_ID, listId);
            Models.get(SiteModel.class).save(site);
            navigateToApp(rulesUrl);
            waitForHydration();
            String use = "[data-access-list-guards] [data-access-list-use]";
            assertThat(page.locator("[data-access-list-guards]").textContent())
                .as("step 8: the line names the app as a whole")
                .contains("Guards rules-journey-" + suffix + " (whole app).");
            assertThat(page.locator(use + " a").textContent().trim())
                .as("step 8: the app's name is the link").isEqualTo("rules-journey-" + suffix);
            assertThat(page.locator(use + " a").getAttribute("href"))
                .as("step 8: to the app's page").contains("/admin/sites/" + siteId);

            // 9. On /manage, a tenant managing the list is offered the add; one that does not manage it reaches
            //    neither the tab nor the add, and its invoke writes nothing.
            //    The /manage access lists admit a tenant managing some app, so both manage one.
            for (UUID tenant : List.of(managerId, outsiderId)) {
                RecordGrants.grant(GrantSubjectType.USER, tenant, SiteModel.MODEL_ID, panelSiteId,
                    HohenheimCapabilities.MANAGE, true);
            }
            RecordGrants.grant(GrantSubjectType.USER, managerId, AccessListModel.MODEL_ID, listId,
                HohenheimCapabilities.MANAGE, true);
            String manageUrl = "/manage/access-lists/" + listId + "/page/rules";
            HttpResponse<String> managed = httpGet(manageUrl, sessionFor(managerId).token());
            assertThat(managed.statusCode()).as("step 9: the manager opens the tab").isEqualTo(200);
            assertThat(rendered(managed.body())).as("step 9: and is offered the add")
                .contains("data-access-rule-add");
            TestSession outsider = sessionFor(outsiderId);
            assertThat(httpGet(manageUrl, outsider.token()).statusCode())
                .as("step 9: the outsider does not reach the tab").isEqualTo(404);
            String invoke = CmsRoutes.invoke(HohenheimSlugs.MANAGE, HohenheimSlugs.ACCESS_LISTS,
                    AccessRuleParts.ADD.id()).with(CmsEndpoints.SUBJECT_PARAM, String.valueOf(listId))
                .with(CmsEndpoints.TAB_PARAM, HohenheimSlugs.Tab.RULES).toUrl();
            int before = Models.get(AccessRuleModel.class).findForAccessList(listId).size();
            HttpResponse<String> refused = httpPostForm(invoke,
                confirmed("type=ip_allow&data.network=10.9.0.0%2F16&enabled=true"), outsider.token(),
                outsider.csrf());
            assertThat(refused.statusCode()).as("step 9: the outsider's add is refused as missing").isEqualTo(404);
            assertThat(Models.get(AccessRuleModel.class).findForAccessList(listId))
                .as("step 9: and writes nothing").hasSize(before);
        } finally {
            for (UUID tenant : List.of(managerId, outsiderId)) {
                RecordGrants.revoke(GrantSubjectType.USER, tenant, SiteModel.MODEL_ID, panelSiteId,
                    HohenheimCapabilities.MANAGE);
            }
            RecordGrants.revoke(GrantSubjectType.USER, managerId, AccessListModel.MODEL_ID, listId,
                HohenheimCapabilities.MANAGE);
            HardDeletes.byId(Models.get(SiteModel.class), siteId);
            HardDeletes.byId(Models.get(SiteModel.class), panelSiteId);
        }

        // 10. On a phone (390 px) every row stays inside its card: the summary wraps by words, never past three lines,
        //     and the actions drop under it instead of running out of the card.
        ViewportSize wide = page.viewportSize();
        page.setViewportSize(390, 844);
        try {
            navigateToApp(rulesUrl);
            waitForHydration();
            Object misfits = page.evaluate("() => [...document.querySelectorAll('.hh-rule-row')].flatMap(row => {"
                + " const card = row.closest('pl-card').getBoundingClientRect();"
                + " const summary = row.querySelector('.hh-rule-summary');"
                + " const style = getComputedStyle(summary);"
                + " const line = parseFloat(style.lineHeight) || parseFloat(style.fontSize) * 1.5;"
                + " const out = [];"
                + " if (summary.getBoundingClientRect().height > 3 * line + 1)"
                + "  out.push(row.dataset.ruleId + ' summary');"
                + " const parts = row.querySelectorAll('.hh-rule-line > *, .hh-rule-actions pl-button');"
                + " for (const part of [row, ...parts]) {"
                + "  const box = part.getBoundingClientRect();"
                + "  if (box.width === 0 && box.height === 0) continue;"
                + "  if (box.left < card.left - 0.5 || box.right > card.right + 0.5)"
                + "   out.push(row.dataset.ruleId + ' ' + part.tagName + ' ' + box.left + '..' + box.right);"
                + " }"
                + " return out; })");
            assertThat(page.locator(".hh-rule-row").count()).as("step 10: the rows render on a phone").isEqualTo(4);
            assertThat(misfits).as("step 10: THE ASSERTION -- no summary past three lines, nothing out of the card")
                .isEqualTo(List.of());
        } finally {
            if (wide != null) {
                page.setViewportSize(wide.width, wide.height);
            }
        }
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

        // 1. A leaf of the tree, born the way the automation API makes one: switched off
        //    and empty. Created directly here because what follows is about SAVING it,
        //    not about the add dialog the journey above already walks.
        Row leaf = model.createEmptyRow();
        leaf.set(AccessRuleModel.ACCESS_LIST_ID, listId);
        leaf.set(AccessRuleModel.TYPE, AccessRuleModel.TYPE_IP_ALLOW);
        leaf.set(AccessRuleModel.ENABLED, false);
        model.save(leaf);
        int ruleId = leaf.get(AccessRuleModel.ID);
        String saveUrl = "/admin/access-rules/" + ruleId;

        // 2. Garbage in the kind's own field is refused AT SAVE, in the sentence that says
        //    what belongs there -- not accepted now and complained about at the enable gate.
        HttpResponse<String> nonsenseSave = assertNotStale(httpPostEdit(saveUrl,
            "type=ip_allow&data.network=not-an-ip-address", sessionToken, csrfToken));
        assertThat(nonsenseSave.statusCode())
            .as("step 2: a refused save rerenders the form instead of redirecting")
            .isEqualTo(422);
        assertThat(nonsenseSave.body())
            .as("step 2: and names what a network looks like")
            .contains("Enter one IP address or CIDR network");
        assertThat(AccessRuleModel.dataOf(model.findById(ruleId)).get("network"))
            .as("step 2: nothing nonsensical reached the row").isNull();

        // 3. EMPTY while switched off stays allowed: a rule switched off is a draft by design,
        //    which the switch in the add dialog offers too.
        HttpResponse<String> draftSave = httpPostEdit(saveUrl,
            "type=ip_allow&data.network=", sessionToken, csrfToken);
        assertThat(draftSave.statusCode())
            .as("step 3: the empty draft saves").isEqualTo(302);
        assertThat((Boolean) model.findById(ruleId).get(AccessRuleModel.ENABLED))
            .as("step 3: still switched off").isFalse();

        // 4. Switching it ON with nothing filled in is refused by the SAME check, with the
        //    same sentence.
        HttpResponse<String> enableEmpty = assertNotStale(httpPostEdit(saveUrl,
            "type=ip_allow&data.network=&enabled=true", sessionToken, csrfToken));
        assertThat(enableEmpty.statusCode())
            .as("step 4: enabling an empty rule is refused").isEqualTo(422);
        assertThat(enableEmpty.body())
            .as("step 4: with the same message the save refusal used")
            .contains("Enter one IP address or CIDR network");
        assertThat((Boolean) model.findById(ruleId).get(AccessRuleModel.ENABLED))
            .as("step 4: and the rule is still off").isFalse();

        // 5. Filled in and switched on, it saves -- and the tab SAYS it is on, instead of
        //    saying nothing at all.
        HttpResponse<String> goodSave = httpPostEdit(saveUrl,
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
     * The add dialog survives a SOFT navigation onto the tab: reaching the Rules tab by clicking it re-renders the
     * page client-side, where the invoke target is a DRY-revived one, and the dialog must still open and add.
     */
    @Test
    void addsFromTheDialogWhenTheTabIsReachedBySoftNavigation() {
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
        page.waitForSelector(ADD_RULE);
        waitForHydration();

        // 3. The client-rendered add still opens its dialog and adds: a group, chosen in the dialog.
        click(ADD_RULE);
        page.waitForSelector(INPUT_FORM + " " + field("data.username") + " input");
        chooseOption("type", AccessRuleModel.TYPE_GROUP);
        waitForNavigationEnded(() -> click(OK));
        waitForHidden(DIALOG);
        page.waitForSelector(".hh-rule-row");
        assertThat(Models.get(AccessRuleModel.class).findForAccessList(listId))
            .as("step 3: the dialog's add created the rule").hasSize(1);
    }

    /** Opens a group's "Add inside", picks the kind and the network, and adds; the tab comes back with the row. */
    private void addInside(String button, String type, String network) {
        int before = page.locator(".hh-rule-row").count();
        click(button);
        page.waitForSelector(INPUT_FORM + " " + field("data.username") + " input");
        chooseOption("type", type);
        page.waitForSelector(DIALOG + " " + field("data.network") + " input");
        page.locator(DIALOG + " " + field("data.network") + " input:not([type='hidden'])").fill(network);
        waitForNavigationEnded(() -> click(OK));
        waitForHidden(DIALOG);
        page.waitForCondition(() -> page.locator(".hh-rule-row").count() == before + 1);
        waitForHydration();
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

    private static String field(String path) {
        return "pl-field[data-path='" + path + "']";
    }

    private static String basic(String username, String password) {
        return "Basic " + Base64.getEncoder().encodeToString((username + ":" + password)
            .getBytes(StandardCharsets.UTF_8));
    }

    private static Integer groupIdValue(int listId) {
        return Models.get(AccessRuleModel.class).find()
            .where(AccessRuleModel.ACCESS_LIST_ID.eq(listId))
            .and(AccessRuleModel.TYPE.eq(AccessRuleModel.TYPE_GROUP))
            .first().get(AccessRuleModel.ID);
    }

    private static int site(String slug) {
        Row row = Models.get(SiteModel.class).createEmptyRow();
        row.set(SiteModel.NAME, slug);
        row.set(SiteModel.SLUG, slug);
        row.set(SiteModel.UPSTREAM_KIND, "hohenheim:static");
        row.set(SiteModel.SETTINGS, Map.of("root_path", "/tmp"));
        row.set(SiteModel.STATUS, SiteModel.STATUS_ACTIVE);
        row.set(SiteModel.ENABLED, false);
        Models.get(SiteModel.class).save(row);
        return row.get(SiteModel.ID);
    }
}
