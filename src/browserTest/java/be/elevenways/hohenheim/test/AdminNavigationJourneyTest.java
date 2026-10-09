package be.elevenways.hohenheim.test;

import be.elevenways.hohenheim.server.cms.AppParts;
import be.elevenways.hohenheim.server.cms.HohenheimPanel;
import be.elevenways.hohenheim.server.cms.ManagePanel;
import be.elevenways.protoblast.common.i18n.LocaleChain;
import be.elevenways.protoblast.common.i18n.MessageResolvers;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.zenit.auth.model.UserModel;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.auth.model.UserPrincipal;
import be.elevenways.zenit.cms.common.panel.Panel;
import be.elevenways.zenit.cms.common.panel.PanelCluster;
import be.elevenways.zenit.cms.common.panel.PanelEntry;
import be.elevenways.zenit.cms.common.panel.PanelNav;
import be.elevenways.zenit.cms.common.panel.PanelRegistry;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.security.AccessContext;
import org.junit.jupiter.api.Test;

import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The admin sidebar of the 2026-09-30 boards, end to end: eight entries in one block, four of them clusters whose
 * members are tabs, every entry explaining itself, and every entry demoted OUT of the sidebar still reachable at its
 * URL and linked from the surface that adopted it.
 *
 * AIDEV-NOTE: the expected lists below are a DELIBERATE inventory, not a snapshot to bless away. The sidebar grew to
 * 39 schema-shaped entries by accretion once -- every new resource simply appeared. A peer added without a nav
 * decision fails HERE, which is the point: adding one costs a line in this test, in a cluster or in the demoted list.
 */
class AdminNavigationJourneyTest extends HohenheimTestBase {

    /** The sidebar, in the order it renders: one unlabelled block. */
    private static final List<String> EXPECTED_SIDEBAR = List.of("dashboard", AppParts.SLUG, "databases", "servers",
        HohenheimPanel.DOMAINS_CLUSTER, HohenheimPanel.ACCESS_CLUSTER, HohenheimPanel.ACTIVITY_CLUSTER,
        HohenheimPanel.SETTINGS_CLUSTER);

    /** Each cluster's members, in the order its tabs show them. */
    private static final Map<String, List<String>> EXPECTED_CLUSTERS = Map.of(
        HohenheimPanel.DOMAINS_CLUSTER, List.of("domains", "certificates", "dns-zones", "released-claims"),
        // Roles stay a member (their pages stand under Access) but no tab: zenit-auth's People list reaches them.
        HohenheimPanel.ACCESS_CLUSTER, List.of("access-lists", "bans", "users", "auth-providers", "spamservice"),
        HohenheimPanel.ACTIVITY_CLUSTER, List.of("activity", "inbox", "deliveries"),
        HohenheimPanel.SETTINGS_CLUSTER, List.of("settings", "instance-templates", "runtime-images", "git-providers",
            "database-engines", "notifications", "backup-targets", "task-schedules", "task-runs", "build-info"));

    /**
     * Every peer demoted out of the sidebar, with the surface that adopted it. showInNav(false) removes an ENTRY,
     * never a route -- if one of these 404s, the demotion silently deleted a feature.
     */
    private static final List<String> DEMOTED_SLUGS = List.of(
        // What an app is made of: the Apps list's toolbar.
        "sites", "instances", "stacks", "projects",
        // Instance record tabs (snapshots, backups) and the Instances list header.
        "instance-snapshots", "instance-backups", "instance-quotas",
        "game-domains",
        // The Sites list header.
        "auth-providers", "previews", "builds", "releases",
        // The Projects, Environments, Hosts and DNS zones list headers.
        "environments", "environment-variables", "reconcile-findings", "dns-peers",
        // Access: the People list's Roles card and Role column.
        "roles",
        // Access: the abuse-protection overview page.
        "spamservice-installation", "spamservice-clients", "spamservice-samples",
        "spamservice-security-events", "spamservice-words", "spamservice-reputation");

    /** The sub-surfaces the abuse-protection front door must link, now that nav does not. */
    private static final List<String> SPAMSERVICE_SECTIONS = List.of(
        "spamservice-installation", "spamservice-clients", "spamservice-samples",
        "spamservice-security-events", "spamservice-words", "spamservice-reputation");

    @Test
    void adminSidebarIsTheBoardsEightAndEveryDemotedPeerStaysReachable() throws Exception {
        Panel admin = PanelRegistry.getBySlug("admin");
        assertThat(admin).as("the admin panel is registered").isNotNull();
        AccessContext operator = adminContext();

        // 1. The sidebar is exactly the boards' eight entries, in one block, in that order.
        List<PanelNav.Section> sections = PanelNav.sections(admin, operator);
        assertThat(sections).as("step 1: one block, no groups").hasSize(1);
        assertThat(sections.get(0).entries().stream().map(PanelEntry::slug).toList())
            .as("step 1: Dashboard, Apps, Databases, Hosts, Domains, Access, Activity, Settings")
            .containsExactlyElementsOf(EXPECTED_SIDEBAR);
        assertThat(sections.get(0).group().labelled())
            .as("step 1: the block renders without a heading")
            .isFalse();
        assertThat(sections.get(0).group().separatorBefore())
            .as("step 1: and without a rule")
            .isFalse();

        // 2. Every entry explains itself in both shipped locales, and no two share a navOrder.
        Set<Integer> orders = new HashSet<>();
        for (PanelEntry peer : sections.get(0).entries()) {
            assertDescribed(peer, "step 2");
            assertThat(orders.add(peer.navOrder()))
                .as("step 2: '" + peer.slug() + "' has a navOrder no sibling already claims")
                .isTrue();
        }

        // 3. Each cluster holds exactly its members, as tabs in that order, and each member describes itself (the
        //    command palette shows the description beside it).
        for (Map.Entry<String, List<String>> expected : EXPECTED_CLUSTERS.entrySet()) {
            PanelCluster cluster = (PanelCluster) admin.entryBySlug(expected.getKey());
            assertThat(cluster).as("step 3: the '" + expected.getKey() + "' cluster is registered").isNotNull();
            List<PanelEntry> members = PanelNav.clusterMembers(admin, cluster, operator);
            assertThat(members.stream().map(PanelEntry::slug).toList())
                .as("step 3: the '" + expected.getKey() + "' cluster's tabs")
                .containsExactlyElementsOf(expected.getValue());
            for (PanelEntry member : members) {
                assertThat(member.label().tryResolve(LocaleChain.ofTags("en"), MessageResolvers.getDefault()))
                    .as("step 3: member '" + member.slug() + "' has a label")
                    .isNotBlank();
            }
        }
        assertThat(admin.entryBySlug("domains").label()
                .tryResolve(LocaleChain.ofTags("en"), MessageResolvers.getDefault()))
            .as("step 3: the Domains cluster's first tab is called Addresses, as the overview's card is")
            .isEqualTo("Addresses");

        // 4. Demotion removed ENTRIES, not routes: every demoted peer still answers on its own URL, and so does
        //    every cluster, landing on its first member.
        List<String> unreachable = new ArrayList<>();
        for (String slug : DEMOTED_SLUGS) {
            int status = adminGet("/admin/" + slug).statusCode();
            if (status != 200) {
                unreachable.add(slug + " -> " + status);
            }
        }
        assertThat(unreachable)
            .as("step 4: every demoted peer is still reachable at /admin/<slug>")
            .isEmpty();
        for (Map.Entry<String, List<String>> cluster : EXPECTED_CLUSTERS.entrySet()) {
            HttpResponse<String> landing = adminGet("/admin/" + cluster.getKey());
            assertThat(landing.statusCode())
                .as("step 4: the '" + cluster.getKey() + "' cluster's URL redirects to its landing member")
                .isEqualTo(302);
            assertThat(landing.headers().firstValue("Location").orElse(""))
                .as("step 4: the '" + cluster.getKey() + "' cluster lands on its first member")
                .endsWith("/admin/" + cluster.getValue().get(0));
        }

        // 5. And each demoted peer is REACHABLE BY CLICKING, not only by typing: the Apps list's toolbar links what
        //    an app is made of, and the abuse-protection front door every spamservice sub-surface it swallowed.
        String apps = adminGet("/admin/apps").body();
        for (String slug : List.of("sites", "instances", "stacks", "projects")) {
            assertThat(apps).as("step 5: the Apps list links /admin/" + slug).contains("/admin/" + slug);
        }
        String overview = adminGet("/admin/spamservice").body();
        for (String slug : SPAMSERVICE_SECTIONS) {
            assertThat(overview)
                .as("step 5: the abuse-protection overview links /admin/" + slug)
                .contains("/admin/" + slug);
        }

        // 6. The sibling catalogs demoted onto a parent list are linked from that list's header.
        assertThat(adminGet("/admin/instances").body())
            .as("step 6: the Instances list heads to its sibling catalogs and histories")
            .contains("/admin/backup-targets")
            .contains("/admin/instance-quotas")
            .contains("/admin/game-domains")
            .contains("/admin/builds")
            .contains("/admin/releases");
        assertThat(adminGet("/admin/sites").body())
            .as("step 6: the Sites list heads to its sibling catalogs")
            .contains("/admin/auth-providers")
            .contains("/admin/previews")
            .doesNotContain("/admin/builds");
        assertThat(adminGet("/admin/projects").body())
            .as("step 6: the Projects list heads to environments")
            .contains("/admin/environments");
        assertThat(adminGet("/admin/environments").body())
            .as("step 6: the Environments list heads to its variables")
            .contains("/admin/environment-variables");
        assertThat(adminGet("/admin/servers").body())
            .as("step 6: the Hosts list heads to the reconciler's findings")
            .contains("/admin/reconcile-findings");
        assertThat(adminGet("/admin/dns-zones").body())
            .as("step 6: the DNS zones list heads to the federation peers")
            .contains("/admin/dns-peers");

        // 7. The delegated panel keeps its own nav: unique orders, a description per entry, the tenant's Apps list,
        //    and NONE of the operator-only header links the shared resource superclasses declare.
        Panel manage = PanelRegistry.getBySlug(ManagePanel.SLUG);
        assertThat(manage).as("step 7: the manage panel is registered").isNotNull();
        List<String> manageSlugs = new ArrayList<>();
        for (PanelNav.Section section : PanelNav.sections(manage, operator)) {
            Set<Integer> manageOrders = new HashSet<>();
            for (PanelEntry peer : section.entries()) {
                manageSlugs.add(peer.slug());
                assertThat(peer.description())
                    .as("step 7: manage entry '" + peer.slug() + "' declares a description")
                    .isNotNull();
                assertThat(manageOrders.add(peer.navOrder()))
                    .as("step 7: manage entry '" + peer.slug() + "' has a unique navOrder")
                    .isTrue();
            }
        }
        assertThat(manageSlugs).as("step 7: the delegated panel lists the tenant's apps").contains(AppParts.SLUG);
        assertThat(adminGet("/manage/sites").body())
            .as("step 7: the delegated Sites list offers no /admin sibling links")
            .doesNotContain("/admin/auth-providers")
            .doesNotContain("/admin/builds");
    }

    /** A sidebar entry's description resolves in both shipped locales, never to its raw key. */
    private static void assertDescribed(PanelEntry peer, String step) {
        Microcopy description = peer.description();
        assertThat(description).as(step + ": '" + peer.slug() + "' declares a description").isNotNull();
        for (String tag : List.of("en", "nl")) {
            String resolved = description.tryResolve(LocaleChain.ofTags(tag), MessageResolvers.getDefault());
            assertThat(resolved)
                .as(step + ": '" + peer.slug() + "' has a " + tag + " description (key '" + description.key() + "')")
                .isNotNull()
                .isNotBlank()
                .isNotEqualTo(description.key());
        }
    }

    /** The seeded admin as a production-shaped context (see {@link TestAccessContexts}). */
    private static AccessContext adminContext() {
        Row user = Models.get(UserModel.class).find()
            .where(UserModel.EMAIL.eq("test@hohenheim.local")).first();
        assertThat(user).as("the harness seeded its admin").isNotNull();
        return TestAccessContexts.contextFor(new UserPrincipal(
            ((Integer) user.get(UserModel.ID)).longValue(), user.get(UserModel.DISPLAY_NAME)));
    }
}
