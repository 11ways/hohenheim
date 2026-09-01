package be.elevenways.hohenheim.test;

import be.elevenways.hohenheim.server.cms.HohenheimPanel;
import be.elevenways.hohenheim.server.cms.ManagePanel;
import be.elevenways.protoblast.common.i18n.LocaleChain;
import be.elevenways.protoblast.common.i18n.MessageResolvers;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.zenit.auth.model.UserModel;
import be.elevenways.zenit.auth.server.AuthModels;
import be.elevenways.zenit.auth.model.UserPrincipal;
import be.elevenways.zenit.cms.common.panel.NavGroup;
import be.elevenways.zenit.cms.common.panel.Panel;
import be.elevenways.zenit.cms.common.panel.PanelNav;
import be.elevenways.zenit.cms.common.panel.PanelPeer;
import be.elevenways.zenit.cms.common.panel.PanelRegistry;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.security.AccessContext;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The CURATED admin sidebar, end to end: which entries an operator sees, in which order,
 * that each one explains itself, and that every entry demoted OUT of the sidebar is still
 * reachable at its URL and linked from the surface that adopted it.
 *
 * AIDEV-NOTE: the expected list below is a DELIBERATE inventory, not a snapshot to bless
 * away. It exists because the sidebar grew to 39 schema-shaped entries by accretion -- every
 * new resource simply appeared. A peer added without a nav decision fails HERE, which is the
 * point: adding one costs one line in this test plus the description and order it names.
 */
class AdminNavigationJourneyTest extends HohenheimTestBase {

    /**
     * group id -> the peer slugs it shows, in the order the sidebar renders them.
     *
     * AIDEV-NOTE: the groups are named after the OPERATOR'S JOB, not the code's tiers -- the
     * predecessors (Compute / Proxy / Infrastructure) named subsystems, so an operator had to
     * know how hohenheim is built to guess where a page lives, and "Infrastructure" collected
     * whatever fit neither. Within a group the order is OPERATOR VALUE, never alphabetical:
     * Sites and Instances before the templates they are built from, DNS zones and
     * Certificates before the cooldown that gates a released name.
     */
    private static final List<Map.Entry<String, List<String>>> EXPECTED_SIDEBAR = List.of(
        // The ungrouped top block: what you look at, and what everything runs on.
        Map.entry("default", List.of("dashboard", "servers")),
        Map.entry("deploy", List.of("projects", "sites", "instances", "runtime-images",
            "stacks", "databases", "instance-templates", "git-providers")),
        Map.entry("networking", List.of("dns-zones", "certificates", "access-lists",
            "released-claims")),
        Map.entry("security", List.of("users", "roles", "spamservice", "bans")),
        Map.entry("system", List.of("activity", "inbox", "notifications", "settings", "build-info")));

    /**
     * Every peer demoted out of the sidebar, with the surface that adopted it. showInNav(false)
     * removes an ENTRY, never a route -- if one of these 404s, the demotion silently deleted a
     * feature.
     */
    private static final List<String> DEMOTED_SLUGS = List.of(
        // Instance record tabs (snapshots, backups) and the Instances list header.
        "instance-snapshots", "instance-backups", "backup-targets", "instance-quotas",
        "game-domains",
        // The Sites list header.
        "auth-providers", "previews", "builds", "releases",
        // The Projects, Environments, Servers and DNS zones list headers.
        "environments", "environment-variables", "reconcile-findings", "dns-peers",
        // Security: the abuse-protection overview page.
        "spamservice-installation", "spamservice-clients", "spamservice-samples",
        "spamservice-security-events", "spamservice-words", "spamservice-reputation");

    /** The sub-surfaces the abuse-protection front door must link, now that nav does not. */
    private static final List<String> SPAMSERVICE_SECTIONS = List.of(
        "spamservice-installation", "spamservice-clients", "spamservice-samples",
        "spamservice-security-events", "spamservice-words", "spamservice-reputation");

    @Test
    void adminSidebarIsCuratedAndEveryDemotedPeerStaysReachable() throws Exception {
        Panel admin = PanelRegistry.getBySlug("admin");
        assertThat(admin).as("the admin panel is registered").isNotNull();
        AccessContext operator = adminContext();

        // 1. The sidebar is exactly the curated inventory: the groups an operator sees, in
        //    weight order, each holding exactly the entries it should, in navOrder order.
        List<PanelNav.Section> sections = PanelNav.sections(admin, operator);
        List<String> groupIds = sections.stream().map(section -> section.group().id()).toList();
        assertThat(groupIds)
            .as("step 1: the sidebar groups, in weight order")
            .containsExactlyElementsOf(EXPECTED_SIDEBAR.stream().map(Map.Entry::getKey).toList());

        for (int i = 0; i < sections.size(); i++) {
            PanelNav.Section section = sections.get(i);
            List<String> slugs = section.peers().stream().map(PanelPeer::slug).toList();
            assertThat(slugs)
                .as("step 1: the entries of the '" + section.group().id() + "' group, in order")
                .containsExactlyElementsOf(EXPECTED_SIDEBAR.get(i).getValue());
        }

        int visible = sections.stream().mapToInt(section -> section.peers().size()).sum();
        assertThat(visible)
            .as("step 1: the whole sidebar stays scannable (it was 39)")
            .isEqualTo(23);

        // 2. Every visible entry explains itself, and no two entries of one group share a
        //    navOrder -- a tie makes the rendered order depend on declaration order, which is
        //    exactly how this panel's ordering drifted before.
        for (PanelNav.Section section : sections) {
            Set<Integer> orders = new HashSet<>();
            for (PanelPeer peer : section.peers()) {
                Microcopy description = peer.description();
                assertThat(description)
                    .as("step 2: '" + peer.slug() + "' declares a description")
                    .isNotNull();
                // BOTH shipped locales: a description authored in English only degrades to
                // the raw key for a Dutch operator, which is worse than no tooltip at all.
                for (String tag : List.of("en", "nl")) {
                    String resolved = description.tryResolve(
                        LocaleChain.ofTags(tag), MessageResolvers.getDefault());
                    assertThat(resolved)
                        .as("step 2: '" + peer.slug() + "' has a " + tag + " description"
                            + " (key '" + description.key() + "')")
                        .isNotNull()
                        .isNotBlank()
                        .isNotEqualTo(description.key());
                }
                assertThat(orders.add(peer.navOrder()))
                    .as("step 2: '" + peer.slug() + "' has a navOrder no sibling already claims")
                    .isTrue();
            }
        }

        // 3. Section shape: the top block is a bare run of items (a heading over "Dashboard,
        //    Servers" would have to invent a word for "the two things that are always
        //    there"), the Security group opens the administration tail with the sidebar's
        //    ONE separator, and every group hohenheim itself declares carries an icon -- a
        //    bare word is not a landmark.
        NavGroup top = sections.get(0).group();
        assertThat(top.labelled())
            .as("step 3: the ungrouped top block renders without a heading")
            .isFalse();
        assertThat(sections.get(0).peers())
            .as("step 3: and holds both always-there entries, so it reads as a block")
            .hasSize(2);

        List<String> separated = sections.stream()
            .map(PanelNav.Section::group)
            .filter(NavGroup::separatorBefore)
            .map(NavGroup::id).toList();
        assertThat(separated)
            .as("step 3: exactly one rule, where operating ends and administering begins")
            .containsExactly("security");

        for (NavGroup group : List.of(HohenheimPanel.DEPLOY_GROUP,
                HohenheimPanel.NETWORK_GROUP, HohenheimPanel.SECURITY_GROUP)) {
            assertThat(group.icon().name())
                .as("step 3: the '" + group.id() + "' group carries an icon")
                .isNotBlank();
            // A heading is the landmark an operator scans for, so an unresolved key here is
            // worse than a missing description: it is the WORD the section is known by.
            for (String tag : List.of("en", "nl")) {
                assertThat(group.label().tryResolve(
                        LocaleChain.ofTags(tag), MessageResolvers.getDefault()))
                    .as("step 3: the '" + group.id() + "' heading has " + tag + " copy")
                    .isNotNull()
                    .isNotBlank()
                    .isNotEqualTo(group.label().key());
            }
        }
        // AIDEV-NOTE: the System group is deliberately absent from that loop. It is
        // zenit-cms's own NavGroup.SYSTEM constant, which ships Icon.NONE; hohenheim adding
        // one would mean re-declaring the id here, and PanelNav keeps whichever instance a
        // peer happens to name FIRST. The nav reserves the icon gutter for labelled groups,
        // so the heading still aligns. Giving it an icon is a zenit-cms change.

        // 4. Demotion removed ENTRIES, not routes: every demoted peer still answers on its
        //    own URL. This is the guard that a showInNav(false) is never a silent delete.
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

        // 5. And each demoted peer is REACHABLE BY CLICKING, not only by typing: the
        //    abuse-protection front door links every spamservice sub-surface it swallowed.
        String overview = adminGet("/admin/spamservice").body();
        for (String slug : SPAMSERVICE_SECTIONS) {
            assertThat(overview)
                .as("step 5: the abuse-protection overview links /admin/" + slug)
                .contains("/admin/" + slug);
        }

        // 6. The sibling catalogs demoted onto a parent list are linked from that list's
        //    header, so an operator standing on Instances/Sites/Projects/DNS zones can still
        //    walk to them.
        // Build and release history moved WITH the release engine's re-keying: they
        // are the instance tier's siblings now, and a site heads only to what a
        // hostname owns (auth providers, previews).
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
            .as("step 6: the Servers list heads to the reconciler's findings")
            .contains("/admin/reconcile-findings");
        assertThat(adminGet("/admin/dns-zones").body())
            .as("step 6: the DNS zones list heads to the federation peers")
            .contains("/admin/dns-peers");

        // 7. The delegated panel keeps its own nav: unique orders, a description per entry,
        //    and NONE of the operator-only header links the shared resource superclasses
        //    declare (they point at /admin, which a tenant may not open).
        Panel manage = PanelRegistry.getBySlug(ManagePanel.SLUG);
        assertThat(manage).as("step 7: the manage panel is registered").isNotNull();
        for (PanelNav.Section section : PanelNav.sections(manage, operator)) {
            Set<Integer> orders = new HashSet<>();
            for (PanelPeer peer : section.peers()) {
                assertThat(peer.description())
                    .as("step 7: manage entry '" + peer.slug() + "' declares a description")
                    .isNotNull();
                assertThat(orders.add(peer.navOrder()))
                    .as("step 7: manage entry '" + peer.slug() + "' has a unique navOrder")
                    .isTrue();
            }
        }
        assertThat(adminGet("/manage/sites").body())
            .as("step 7: the delegated Sites list offers no /admin sibling links")
            .doesNotContain("/admin/auth-providers")
            .doesNotContain("/admin/builds");
    }

    /** The seeded admin as a production-shaped context (see {@link TestAccessContexts}). */
    private static AccessContext adminContext() {
        Row user = AuthModels.users().find()
            .where(UserModel.EMAIL.eq("test@hohenheim.local")).first();
        assertThat(user).as("the harness seeded its admin").isNotNull();
        return TestAccessContexts.contextFor(new UserPrincipal(
            ((Integer) user.get(UserModel.ID)).longValue(), user.get(UserModel.DISPLAY_NAME)));
    }
}
