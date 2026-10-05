package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.AttentionItem;
import be.elevenways.hohenheim.model.AccessListModel;
import be.elevenways.hohenheim.model.AccessRuleModel;
import be.elevenways.hohenheim.model.ProtectedPathModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.test.HohenheimTestBase;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.validation.Violations;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * A protected path only ever points at a list that really guards it, from both sides: no path is pointed at an open
 * list, and no rule or list write opens a list that protects a path. A path stored open before this rule keeps
 * serving, flagged as open to everyone.
 *
 * @author Jelle De Loecker
 * @since  0.2.0
 */
class ProtectedPathJourneyTest extends HohenheimTestBase {

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
