package be.elevenways.hohenheim.test;

import be.elevenways.hohenheim.HohenheimSlugs;
import be.elevenways.hohenheim.HohenheimMicrocopy;
import be.elevenways.hohenheim.model.AccessListModel;
import be.elevenways.hohenheim.model.CertificateModel;
import be.elevenways.hohenheim.model.DatabaseModel;
import be.elevenways.hohenheim.model.DnsRecordModel;
import be.elevenways.hohenheim.model.GitProviderModel;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.protoblast.common.i18n.LocaleChain;
import be.elevenways.protoblast.common.i18n.MessageResolvers;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.auth.model.UserModel;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.auth.model.UserPrincipal;
import be.elevenways.zenit.cms.common.panel.Panel;
import be.elevenways.zenit.cms.common.panel.PanelCluster;
import be.elevenways.zenit.cms.common.panel.PanelEntry;
import be.elevenways.zenit.cms.common.panel.PanelNav;
import be.elevenways.zenit.cms.common.panel.PanelRegistry;
import be.elevenways.zenit.cms.common.resource.PanelResource;
import be.elevenways.zenit.cms.common.resource.RecordTab;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.common.security.KnownCapabilities;
import be.elevenways.zenit.common.security.KnownCapability;
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
    private static final List<String> EXPECTED_SIDEBAR = List.of("dashboard", HohenheimSlugs.APPS, "databases",
        "servers",
        HohenheimSlugs.Cluster.DOMAIN_NAMES, HohenheimSlugs.Cluster.ACCESS, HohenheimSlugs.Cluster.LOG,
        HohenheimSlugs.Cluster.CONFIGURE);

    /** Each cluster's members, in the order its tabs show them. */
    private static final Map<String, List<String>> EXPECTED_CLUSTERS = Map.of(
        HohenheimSlugs.Cluster.DOMAIN_NAMES, List.of("domains", "certificates", "dns-zones", "released-claims"),
        // Roles stay a member (their pages stand under Access) but no tab: zenit-auth's People list reaches them.
        HohenheimSlugs.Cluster.ACCESS, List.of("access-lists", "bans", "users", "auth-providers", "spamservice"),
        HohenheimSlugs.Cluster.LOG, List.of("activity", "inbox", "deliveries"),
        HohenheimSlugs.Cluster.CONFIGURE, List.of("settings", "instance-templates", "runtime-images", "git-providers",
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
        Panel manage = PanelRegistry.getBySlug(HohenheimSlugs.MANAGE);
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
        assertThat(manageSlugs).as("step 7: the delegated panel lists the tenant's apps").contains(HohenheimSlugs.APPS);
        assertThat(adminGet("/manage/sites").body())
            .as("step 7: the delegated Sites list offers no /admin sibling links")
            .doesNotContain("/admin/auth-providers")
            .doesNotContain("/admin/builds");

        // 8. What an app is made of stands under Apps (PanelEntry.standsUnder): an app's own page marks the Apps entry
        //    in the sidebar, on both panels, though its list keeps out of the sidebar.
        for (String slug : List.of("sites", "instances", "stacks", "projects")) {
            PanelEntry hidden = admin.entryBySlug(slug);
            assertThat(hidden).as("step 8: '" + slug + "' is registered").isNotNull();
            assertThat(PanelNav.sidebarEntryOf(admin, hidden).slug())
                .as("step 8: a page of '" + slug + "' marks Apps in the sidebar").isEqualTo(HohenheimSlugs.APPS);
        }
        for (String slug : List.of("sites", "instances")) {
            assertThat(PanelNav.sidebarEntryOf(manage, manage.entryBySlug(slug)).slug())
                .as("step 8: a tenant's page of '" + slug + "' marks their Apps").isEqualTo(HohenheimSlugs.APPS);
        }

        // 9. A person opens on what they can manage (zenit-auth's "Can manage" tab, slug reach), before their
        //    credentials.
        List<String> personTabs = ((PanelResource<?>) admin.entryBySlug("users")).tabs().declared().stream()
            .map(RecordTab::slug).toList();
        assertThat(personTabs).as("step 9: a person's page has zenit-auth's Can manage tab").contains("reach");
        assertThat(personTabs.indexOf("reach")).as("step 9: before their credentials")
            .isLessThan(personTabs.indexOf("credentials"));

        // 10. The People list and Can manage read every capability a person can hold in words, in en and nl: as a
        //     holder ("Tenant of Survival"), in a sentence ("Console, power, configure"), and, for a level held alone,
        //     with what it allows ("Manage: its addresses, settings and protected paths").
        List<String> unworded = new ArrayList<>();
        for (Identifier model : List.of(SiteModel.MODEL_ID, InstanceModel.MODEL_ID, DatabaseModel.MODEL_ID,
                DnsRecordModel.MODEL_ID, GitProviderModel.MODEL_ID, AccessListModel.MODEL_ID,
                CertificateModel.MODEL_ID)) {
            for (KnownCapability capability : KnownCapabilities.forModel(model)) {
                Microcopy label = capability.label();
                if (label == null) {
                    continue;
                }
                for (String tag : List.of("en", "nl")) {
                    String plain = label.tryResolve(LocaleChain.ofTags(tag), MessageResolvers.getDefault());
                    String holder = label.withFilter("context", "holder")
                        .tryResolve(LocaleChain.ofTags(tag), MessageResolvers.getDefault());
                    String sentence = label.withFilter("case", "sentence")
                        .tryResolve(LocaleChain.ofTags(tag), MessageResolvers.getDefault());
                    if (holder == null || holder.equals(plain) || sentence == null || sentence.equals(plain)) {
                        unworded.add(tag + " " + model + "#" + capability.capability());
                    }
                }
            }
        }
        assertThat(unworded).as("step 10: every capability has its holder and sentence spelling").isEmpty();
        assertThat(HohenheimMicrocopy.CAPABILITY.of("manage").withFilter("context", "holder")
            .tryResolve(LocaleChain.ofTags("en"), MessageResolvers.getDefault()))
            .as("step 10: who manages a record is its tenant (board Access-People)").isEqualTo("Tenant");
        KnownCapability siteManage = KnownCapabilities.forModel(SiteModel.MODEL_ID).get(0);
        assertThat(siteManage.description()).as("step 10: a level held alone says what it allows").isNotNull();
        assertThat(siteManage.description().tryResolve(LocaleChain.ofTags("nl"), MessageResolvers.getDefault()))
            .as("step 10: in Dutch too").isEqualTo("zijn adressen, instellingen en beschermde paden");

        // 11. Every page of either panel stands under a row its sidebar shows (PanelEntry.standsUnder, or the cluster
        //     it is a member of): no hidden entry's page leaves the sidebar without a marked row.
        for (Panel panel : List.of(admin, manage)) {
            List<String> homeless = new ArrayList<>();
            for (PanelEntry entry : panel.entries()) {
                PanelEntry home = PanelNav.sidebarEntryOf(panel, entry);
                if (!home.showInNav()) {
                    homeless.add(entry.slug());
                }
            }
            assertThat(homeless)
                .as("step 11: every entry of /" + panel.slug() + " marks a sidebar row on its pages")
                .isEmpty();
        }

        // 12. A hidden entry's page title ends with the sidebar row it stands under, never its own list's name.
        Map<String, String> titledUnder = Map.of(
            "instance-quotas", HohenheimSlugs.APPS,
            "dns-peers", HohenheimSlugs.Cluster.DOMAIN_NAMES,
            "spamservice-clients", HohenheimSlugs.Cluster.ACCESS,
            "reconcile-findings", "servers");
        for (Map.Entry<String, String> expected : titledUnder.entrySet()) {
            String sidebarLabel = admin.entryBySlug(expected.getValue()).label()
                .tryResolve(LocaleChain.ofTags("en"), MessageResolvers.getDefault());
            String body = adminGet("/admin/" + expected.getKey()).body();
            String title = body.substring(body.indexOf("<title>") + "<title>".length(), body.indexOf("</title>"));
            assertThat(title)
                .as("step 12: the page of '" + expected.getKey() + "' is titled under its sidebar row")
                .endsWith(" - " + sidebarLabel);
        }

        // 13. Databases and Hosts are rows of every admin while their roles run, empty or not, and each list offers
        //     its create action (D13e: a scratch with roles.databases/instances/stacks off had neither row).
        assertThat(adminGet("/admin/databases").body())
            .as("step 13: the Databases list offers its create action").contains("/admin/databases/new");
        assertThat(adminGet("/admin/servers").body())
            .as("step 13: the Hosts list offers its create action").contains("/admin/servers/new");
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
