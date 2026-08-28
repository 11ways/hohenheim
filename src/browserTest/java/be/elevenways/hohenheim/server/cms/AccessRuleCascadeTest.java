package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.model.AccessListModel;
import be.elevenways.hohenheim.model.AccessRuleModel;
import be.elevenways.hohenheim.model.SiteDomainModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.test.HohenheimTestBase;
import be.elevenways.hohenheim.test.TenantConduits;
import be.elevenways.zenit.auth.model.UserModel;
import be.elevenways.zenit.auth.model.UserPrincipal;
import be.elevenways.zenit.auth.server.AuthModels;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.security.AccessContext;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Nothing a delete removes leaves its children behind on an admin surface: access rules
 * die with their list and with the group that encloses them, and a soft-deleted site's
 * hostnames leave the cross-site catalog with it.
 *
 * AIDEV-NOTE: the two halves live in one class because they are one finding -- the
 * 2026-08-27 pass ended with an access rule naming a list id nothing resolved and a domain
 * row naming site "3". The answers are deliberately DIFFERENT: a rule has no meaning
 * without its list and is deleted, a hostname is what a site restore brings back and is
 * only hidden.
 */
class AccessRuleCascadeTest extends HohenheimTestBase {

    /**
     * A list, a group inside it, two rules inside the group and one beside it: deleting the
     * group takes exactly its subtree, deleting the list takes the rest.
     */
    @Test
    void rulesDieWithTheirGroupAndWithTheirList() {
        int listId = accessList("Cascade List");
        int groupId = rule(listId, null, AccessRuleModel.TYPE_GROUP);
        int insideA = rule(listId, groupId, AccessRuleModel.TYPE_IP_ALLOW);
        int insideB = rule(listId, insideA, AccessRuleModel.TYPE_IP_DENY);
        int beside = rule(listId, null, AccessRuleModel.TYPE_IP_ALLOW);

        // 1. The tree stands as written, nesting two levels deep.
        assertThat(rulesOf(listId)).as("step 1: four rules stored").isEqualTo(4);

        // 2. Deleting the GROUP takes everything under it, at every depth -- not just its
        //    direct children -- and leaves the rule beside it untouched.
        Models.get(AccessRuleModel.class).delete(ruleRow(groupId));
        assertThat(ruleRow(insideA)).as("step 2: the direct child is gone").isNull();
        assertThat(ruleRow(insideB)).as("step 2: the grandchild is gone too").isNull();
        assertThat(ruleRow(beside)).as("step 2: a sibling of the group survives").isNotNull();

        // 3. Deleting the LIST takes the rules it still holds: an orphaned rule outlives
        //    the id it names, and the next list to be created can be handed that id.
        Models.get(AccessListModel.class).delete(listRow(listId));
        assertThat(rulesOf(listId)).as("step 3: the list leaves no rules behind").isZero();
    }

    /**
     * A soft-deleted site's hostnames leave every admin surface with it -- the rows stay
     * stored, because that is what a restore brings the hostnames back from.
     */
    @Test
    void aSoftDeletedSitesHostnamesLeaveTheCatalog() {
        SiteModel sites = Models.get(SiteModel.class);
        Row site = sites.createEmptyRow();
        site.set(SiteModel.NAME, "Cascade Site");
        site.set(SiteModel.SLUG, "cascade-site");
        site.set(SiteModel.UPSTREAM_KIND, "hohenheim:address");
        site.set(SiteModel.STATUS, SiteModel.STATUS_ACTIVE);
        site.set(SiteModel.ENABLED, true);
        sites.save(site);
        int siteId = site.get(SiteModel.ID);

        SiteDomainModel domains = Models.get(SiteDomainModel.class);
        Row domain = domains.createEmptyRow();
        domain.set(SiteDomainModel.SITE_ID, siteId);
        domain.set(SiteDomainModel.HOSTNAME, "cascade.orphan.test");
        domain.set(SiteDomainModel.MATCH_TYPE, SiteDomainModel.MATCH_EXACT);
        domains.save(domain);
        int domainId = domain.get(SiteDomainModel.ID);

        SiteDomainResource resource = new SiteDomainResource();
        AccessContext operator = operator();

        // 1. While the site lives, its hostname is in the catalog.
        assertThat(listedDomainIds(resource, operator))
            .as("step 1: the catalog lists a live site's hostname")
            .contains(domainId);

        // 2. Soft-deleting the site takes it out: the Site column used to resolve to a bare
        //    id that opened nothing.
        site.set(SiteModel.DELETED_AT, Instant.now());
        sites.save(site);
        assertThat(listedDomainIds(resource, operator))
            .as("step 2: a soft-deleted site's hostname leaves the catalog")
            .doesNotContain(domainId);

        // 3. And the row is still STORED -- hidden, not destroyed.
        assertThat(Models.get(SiteDomainModel.class).findById(domainId))
            .as("step 3: the hostname a restore would bring back is still there")
            .isNotNull();
    }

    /** The domain ids the resource's own access scope lets an operator see. */
    private static java.util.List<Integer> listedDomainIds(SiteDomainResource resource,
                                                           AccessContext accessContext) {
        return Models.get(SiteDomainModel.class).find()
            .where(resource.accessFunction().decide(accessContext).predicate().criteria())
            .all().stream()
            .map(row -> (Integer) row.get(SiteDomainModel.ID))
            .toList();
    }

    private static AccessContext operator() {
        Row admin = AuthModels.users().find()
            .where(UserModel.EMAIL.eq("test@hohenheim.local")).first();
        return AccessContext.of(TenantConduits.stubFor(
            new UserPrincipal(admin.get(UserModel.ID), "Test Admin")));
    }

    private static int accessList(String name) {
        AccessListModel lists = Models.get(AccessListModel.class);
        Row list = lists.createEmptyRow();
        list.set(AccessListModel.NAME, name);
        lists.save(list);
        return list.get(AccessListModel.ID);
    }

    /** An enabled rule of {@code type}; an IP rule carries a network, which its kind requires. */
    private static int rule(int listId, Integer parentId, String type) {
        AccessRuleModel rules = Models.get(AccessRuleModel.class);
        Row rule = rules.createEmptyRow();
        rule.set(AccessRuleModel.ACCESS_LIST_ID, listId);
        rule.set(AccessRuleModel.PARENT_ID, parentId);
        rule.set(AccessRuleModel.TYPE, type);
        rule.set(AccessRuleModel.ENABLED, true);
        if (!AccessRuleModel.TYPE_GROUP.equals(type)) {
            rule.set(AccessRuleModel.DATA,
                Map.of(AccessRuleModel.NETWORK.getName(), "10.0.0.0/8"));
        }
        rules.save(rule);
        return rule.get(AccessRuleModel.ID);
    }

    private static long rulesOf(int listId) {
        return Models.get(AccessRuleModel.class).find()
            .where(AccessRuleModel.ACCESS_LIST_ID.eq(listId)).count();
    }

    private static Row ruleRow(int id) {
        return Models.get(AccessRuleModel.class).findById(id);
    }

    private static Row listRow(int id) {
        return Models.get(AccessListModel.class).findById(id);
    }
}
