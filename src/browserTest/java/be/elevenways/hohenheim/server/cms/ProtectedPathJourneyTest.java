package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.AttentionItem;
import be.elevenways.hohenheim.HohenheimCapabilities;
import be.elevenways.hohenheim.HohenheimSlugs;
import be.elevenways.hohenheim.model.AccessListModel;
import be.elevenways.hohenheim.model.AccessRuleModel;
import be.elevenways.hohenheim.model.ProtectedPathModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.test.ApiSupport;
import be.elevenways.hohenheim.test.HohenheimHttpTestBase;
import be.elevenways.zenit.auth.model.GrantSubjectType;
import be.elevenways.zenit.auth.server.RecordGrants;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.validation.Violations;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * A protected path only ever points at a list that really guards it, from both sides: no path is pointed at an open
 * list, and no rule or list write opens a list that protects a path. A path stored open before this rule keeps
 * serving, flagged as open to everyone.
 * A site's Protection tab and overview card lead with what guards the whole app, by the same reading.
 *
 * @author Jelle De Loecker
 * @since  0.2.0
 */
class ProtectedPathJourneyTest extends HohenheimHttpTestBase {

    @Test
    void aProtectedPathOnlyPointsAtAListThatGuardsIt() {
        Row site = site("protected-path-journey");
        int staff = list("journey staff", AccessListModel.SATISFY_ANY);

        // 1. A list with no rule lets everyone through, so protecting a path with it is refused, naming the list.
        assertRefused(() -> protect(site, "/admin", staff), "access_list_admits_everyone",
            "step 1: a path cannot point at a list with no rule");

        // 2. With one rule switched on, the same path saves and reads as protected.
        Row password = rule(staff, null, AccessRuleModel.TYPE_BASIC_AUTH,
            Map.of("username", "editor", "password", "correct horse"), true);
        Row path = protect(site, "/admin", staff);
        assertThat(ProtectedPathParts.protectionBadge(path).key()).as("step 2: the path reads as protected")
            .isEqualTo("protected");

        // 3. Switching the only rule off, or deleting it, would open the path: both writers are refused, naming it.
        password.set(AccessRuleModel.ENABLED, false);
        assertRefused(() -> Models.get(AccessRuleModel.class).save(password), "access_list_would_open",
            "step 3: the last rule switched on cannot be switched off");
        Row stored = Models.get(AccessRuleModel.class).findById(password.get(AccessRuleModel.ID));
        assertRefused(() -> Models.get(AccessRuleModel.class).delete(stored), "access_list_would_open",
            "step 3: nor deleted");

        // 4. An enabled empty group in an ANY list states no requirement, so it would open the list as well.
        assertRefused(() -> rule(staff, null, AccessRuleModel.TYPE_GROUP, Map.of("satisfy", "any"), true),
            "access_list_would_open", "step 4: an empty group in an any-list lets everyone through");

        // 5. With a second rule on, the first one may be switched off: the list still guards the path.
        rule(staff, null, AccessRuleModel.TYPE_IP_ALLOW, Map.of("network", "10.0.0.0/8"), true);
        Row first = Models.get(AccessRuleModel.class).findById(password.get(AccessRuleModel.ID));
        first.set(AccessRuleModel.ENABLED, false);
        Models.get(AccessRuleModel.class).save(first);
        assertThat(ProtectedPathInvariant.isOpen(path)).as("step 5: one rule on still guards").isFalse();

        // 6. A path stored open before this rule (its rules switched off without the hooks, as an old install has it)
        //    keeps serving: it reads "Open to everyone", an attention item names it, and editing it is not refused.
        Models.get(AccessRuleModel.class).find().where(AccessRuleModel.ACCESS_LIST_ID.eq(staff))
            .assign(AccessRuleModel.ENABLED, false).updateAll();
        Row legacy = Models.get(ProtectedPathModel.class).findById(path.get(ProtectedPathModel.ID));
        assertThat(ProtectedPathParts.protectionBadge(legacy).key()).as("step 6: the stored open path is flagged")
            .isEqualTo("open");
        List<AttentionItem> items = new ArrayList<>();
        ProxyAttention.openProtectedPaths(items);
        assertThat(items).as("step 6: an attention item names the open path")
            .anySatisfy(item -> assertThat(String.valueOf(item.title().args().asMap().get("path")))
                .isEqualTo("/admin"));
        legacy.set(ProtectedPathModel.PATH, "/admin-area");
        Models.get(ProtectedPathModel.class).save(legacy);
        assertThat((String) Models.get(ProtectedPathModel.class).findById(path.get(ProtectedPathModel.ID))
            .get(ProtectedPathModel.PATH)).as("step 6: an already open path stays editable").isEqualTo("/admin-area");
    }

    @Test
    void aRuleWithoutItsValueSaysSoInsteadOfALabelAlone() {
        int list = list("journey titles", AccessListModel.SATISFY_ANY);
        Row unnamed = rule(list, null, AccessRuleModel.TYPE_BASIC_AUTH, Map.of(), false);

        // 1. A password rule without a username yet names that, never "Username" followed by nothing.
        assertThat(AccessRuleSummaries.summaryOf(unnamed, AccessRuleModel.TYPE_BASIC_AUTH).key())
            .as("step 1: the missing username is said").isEqualTo("summary_basic_auth_missing");

        // 2. Once it has one, the summary carries it.
        Row named = rule(list, null, AccessRuleModel.TYPE_BASIC_AUTH,
            Map.of("username", "editor", "password", "correct horse"), true);
        assertThat(AccessRuleSummaries.summaryOf(named, AccessRuleModel.TYPE_BASIC_AUTH).key())
            .as("step 2: a username is shown").isEqualTo("summary_basic_auth");
    }

    @Test
    void theProtectionTabLeadsWithWhatGuardsTheWholeApp() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String listName = "DAEL lab " + suffix;
        int lab = list(listName, AccessListModel.SATISFY_ANY);
        rule(lab, null, AccessRuleModel.TYPE_BASIC_AUTH, Map.of("username", "anna", "password", "correct horse"),
            true);
        Row guarded = site("whole-app-guarded-" + suffix);
        guarded.set(SiteModel.ACCESS_LIST_ID, lab);
        Models.get(SiteModel.class).save(guarded);
        Integer guardedId = guarded.get(SiteModel.ID);
        String rules = "/admin/" + HohenheimSlugs.ACCESS_LISTS + "/" + lab + "/page/" + HohenheimSlugs.Tab.RULES;

        // 1. A site guarded by a whole-app list leads its Protection tab with the Whole app card: the list by name,
        //    linked to its rules, how it lets visitors in, Protected, and "Edit who gets in" to those rules.
        String tab = adminGet(protectionTab("admin", guardedId)).body();
        String band = bandOf(tab);
        assertThat(band).as("step 1: the Whole app card, saying what it guards")
            .contains("Whole app").contains("What every visitor must pass, on every address of this app.");
        assertThat(band).as("step 1: THE ASSERTION -- the list by name, linked to its Rules tab")
            .contains("data-app-protection=\"Access list " + listName + "\"")
            .contains("href=\"" + rules + "\"");
        assertThat(band).as("step 1: how it lets visitors in, as protection")
            .contains("Password, 1 person").contains("Protected").doesNotContain("data-app-protection-open");
        assertThat(band).as("step 1: the card's door leads to who gets in").contains("Edit who gets in");

        // 2. Under it the paths section, headed as the paths that carry their own protection; empty, it says every
        //    path follows the whole app and offers protecting one in place.
        String paths = tab.substring(tab.indexOf("data-cms-child-list=\"" + HohenheimSlugs.PROTECTED_PATHS + "\""));
        assertThat(paths).as("step 2: the section's own heading").contains("Paths with their own protection");
        assertThat(paths).as("step 2: the empty section says what protecting a path does")
            .contains("Until a path is protected here, it follows its app");
        assertThat(paths).as("step 2: and offers Protect a path there")
            .contains("/invoke/hohenheim.protect_path");

        // 3. The overview's Protection card reads "Whole app" while no path has its own, linked to the same rules.
        String overview = adminGet("/admin/sites/" + guardedId + "/page/overview").body();
        assertThat(overview).as("step 3: the whole-app row, named for what it covers")
            .contains("data-app-protection=\"Whole app\"")
            .contains("Access list " + listName + ": Password, 1 person");
        assertThat(overview).as("step 3: THE ASSERTION -- linked to the list's rules")
            .contains("href=\"" + rules + "\"");

        // 4. A tenant managing the site but not the list reads the list's name without a door to it.
        UUID tenantId = ApiSupport.user("whole-app-" + suffix + "@hohenheim.local", "Whole App Tenant");
        RecordGrants.grant(GrantSubjectType.USER, tenantId, SiteModel.MODEL_ID, guardedId,
            HohenheimCapabilities.MANAGE, true);
        String tenantBand = bandOf(httpGet(protectionTab("manage", guardedId), sessionFor(tenantId).token()).body());
        assertThat(tenantBand).as("step 4: the list by name")
            .contains("data-app-protection=\"Access list " + listName + "\"");
        assertThat(tenantBand).as("step 4: THE ASSERTION -- no link to a list the tenant cannot open")
            .doesNotContain("/" + HohenheimSlugs.ACCESS_LISTS + "/" + lab)
            .doesNotContain("Edit who gets in")
            .doesNotContain("data-widget-card-link");

        // 5. A site without a list is open to everyone, said plainly; its form is the door to guard it.
        Integer openId = site("whole-app-open-" + suffix).get(SiteModel.ID);
        String openBand = bandOf(adminGet(protectionTab("admin", openId)).body());
        assertThat(openBand).as("step 5: open to everyone, in words")
            .contains("data-app-protection=\"Open to everyone\"")
            .contains("Anyone who has the address can open this app.")
            .doesNotContain("Protected");
        assertThat(openBand).as("step 5: the card's door is the site's form")
            .contains("href=\"/admin/sites/" + openId + "\"").contains("Change");

        // 6. A whole-app list that lets everyone through reads as open, as an open path does, never as protected.
        int empty = list("Empty " + suffix, AccessListModel.SATISFY_ANY);
        Row hollow = site("whole-app-hollow-" + suffix);
        hollow.set(SiteModel.ACCESS_LIST_ID, empty);
        Models.get(SiteModel.class).save(hollow);
        String hollowBand = bandOf(adminGet(protectionTab("admin", hollow.get(SiteModel.ID))).body());
        assertThat(hollowBand).as("step 6: THE ASSERTION -- flagged open")
            .contains("data-app-protection-open=\"1\"").contains("Open to everyone").doesNotContain("Protected");

        // 7. Once a path has its own protection, the overview's row is the rest of the app: "Everything else".
        protect(guarded, "/members", lab);
        assertThat(adminGet("/admin/sites/" + guardedId + "/page/overview").body())
            .as("step 7: the row names the rest beside the path")
            .contains("data-app-protection=\"Everything else\"")
            .contains("data-app-protection=\"/members\"");
    }

    private static String protectionTab(String panel, Object siteId) {
        return "/" + panel + "/sites/" + siteId + "/page/" + HohenheimSlugs.PROTECTED_PATHS;
    }

    /** @return the tab's band about the site, from its mount to the first section */
    private static String bandOf(String page) {
        int start = page.indexOf("data-cms-child-list-band");
        int end = page.indexOf("data-cms-child-list=\"" + HohenheimSlugs.PROTECTED_PATHS + "\"");
        assertThat(start).as("the band draws above the paths section: " + page).isNotNegative().isLessThan(end);
        return page.substring(start, end);
    }

    private static void assertRefused(Runnable write, String key, String step) {
        assertThat(catchThrowable(write::run)).as(step)
            .isInstanceOfSatisfying(Violations.class, violations -> assertThat(violations.all())
                .anySatisfy(violation -> assertThat(violation.message().key()).isEqualTo(key)));
    }

    private static Row site(String name) {
        Row site = Models.get(SiteModel.class).createEmptyRow();
        site.set(SiteModel.NAME, name);
        site.set(SiteModel.SLUG, name);
        site.set(SiteModel.UPSTREAM_KIND, "hohenheim:static");
        site.set(SiteModel.SETTINGS, Map.of("root_path", "/tmp"));
        site.set(SiteModel.ENABLED, true);
        Models.get(SiteModel.class).save(site);
        return site;
    }

    private static int list(String name, String satisfy) {
        Row list = Models.get(AccessListModel.class).createEmptyRow();
        list.set(AccessListModel.NAME, name);
        list.set(AccessListModel.SATISFY, satisfy);
        Models.get(AccessListModel.class).save(list);
        return list.get(AccessListModel.ID);
    }

    private static Row rule(int listId, Integer parentId, String type, Map<String, Object> data, boolean enabled) {
        Row rule = Models.get(AccessRuleModel.class).createEmptyRow();
        rule.set(AccessRuleModel.ACCESS_LIST_ID, listId);
        rule.set(AccessRuleModel.PARENT_ID, parentId);
        rule.set(AccessRuleModel.TYPE, type);
        rule.set(AccessRuleModel.DATA, new LinkedHashMap<>(data));
        rule.set(AccessRuleModel.ENABLED, enabled);
        Models.get(AccessRuleModel.class).save(rule);
        return Models.get(AccessRuleModel.class).findById(rule.get(AccessRuleModel.ID));
    }

    private static Row protect(Row site, String path, int listId) {
        Row row = Models.get(ProtectedPathModel.class).createEmptyRow();
        row.set(ProtectedPathModel.SITE_ID, site.get(SiteModel.ID));
        row.set(ProtectedPathModel.PATH, path);
        row.set(ProtectedPathModel.ACCESS_LIST_ID, listId);
        Models.get(ProtectedPathModel.class).save(row);
        return Models.get(ProtectedPathModel.class).findById(row.get(ProtectedPathModel.ID));
    }
}
