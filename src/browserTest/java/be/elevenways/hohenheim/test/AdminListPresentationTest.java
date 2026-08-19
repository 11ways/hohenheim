package be.elevenways.hohenheim.test;

import be.elevenways.hohenheim.model.AccessListModel;
import be.elevenways.hohenheim.model.AccessRuleModel;
import be.elevenways.hohenheim.model.BanModel;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.server.cms.ManagePanel;
import be.elevenways.zenit.cms.common.panel.Panel;
import be.elevenways.zenit.cms.common.panel.PanelPeer;
import be.elevenways.zenit.cms.common.panel.PanelRegistry;
import be.elevenways.zenit.cms.common.resource.Resource;
import be.elevenways.zenit.cms.common.schema.ColumnSpec;
import be.elevenways.zenit.cms.common.schema.TableSpec;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.field.Field;
import be.elevenways.zenit.common.orm.field.TextSearchable;
import be.elevenways.zenit.common.orm.model.Models;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The list SURFACE of both panels: that a declared search actually narrows and fails closed,
 * that the richer cells render both halves, that a copy chip carries the raw stored value,
 * and that every declaration in the app survives panel registration.
 *
 * AIDEV-NOTE: the registration journey is driven from the PANELS' OWN peer lists, never from
 * a hand-written list of resources. A declaration added tomorrow -- a subtext naming a column
 * that is not there, a secret field named as searchable -- fails HERE rather than at the boot
 * of whatever host registers it next.
 */
class AdminListPresentationTest extends HohenheimTestBase {

    /**
     * The admin peers that MUST offer a search box. A pin, not a snapshot: this wave exists
     * because the framework grew the feature and exactly one resource ever declared it, and
     * the way that happens again is a resource quietly losing its declaration.
     */
    private static final Set<String> SEARCHABLE_ADMIN_SLUGS = Set.of(
        "sites", "instances", "servers", "databases", "certificates", "projects",
        "environments", "environment-variables", "stacks", "instance-templates",
        "git-providers", "notifications", "dns-peers", "dns-records", "previews",
        "access-lists", "dns-zones", "bans", "domains", "instance-snapshots",
        "instance-template-variables", "instance-quotas", "instance-databases",
        "reconcile-findings", "builds", "releases", "instance-schedule-runs",
        "released-claims", "instance-schedules", "backup-targets",
        "instance-template-files", "instance-files", "stack-services", "stack-files",
        "auth-providers", "activity");

    /**
     * Step 1-4: every resource of both panels registers, and every declaration it makes about
     * its own list is one the framework can honour.
     */
    /** The peers this wave gave an explicit spec where the derived one used to serve. */
    private static final List<String> NEW_TABLE_SPEC_SLUGS =
        List.of("access-lists", "auth-providers", "notifications", "bans");

    @Test
    void everyResourceDeclarationSurvivesRegistration() throws Exception {
        List<Resource<?>> resources = new ArrayList<>();
        for (String slug : List.of("admin", ManagePanel.SLUG)) {
            Panel panel = PanelRegistry.getBySlug(slug);
            assertThat(panel).as("the '" + slug + "' panel is registered").isNotNull();
            for (PanelPeer peer : panel.peers()) {
                if (peer instanceof Resource<?> resource) {
                    resources.add(resource);
                }
            }
        }
        assertThat(resources).as("step 1: both panels expose their resources").hasSizeGreaterThan(30);

        Set<String> offering = new TreeSet<>();
        for (Resource<?> resource : resources) {
            String who = resource.id() + " (" + resource.slug() + ")";

            // 1. The framework's own registration check: search fields that are neither
            //    secret nor localized, plus the record-page and quick-add declarations.
            resource.validateDeclarations();

            TableSpec<?> spec = resource.tableSpec();

            // 2. Every subtext names a column of the SAME spec. TableSpec.build already
            //    refuses otherwise, so reaching every spec through the registered peer is
            //    what makes that refusal reachable from a test.
            for (ColumnSpec column : spec.columns()) {
                if (column.subtext() != null) {
                    assertThat(spec.column(column.subtext()))
                        .as("step 2: " + who + " column '" + column.name()
                            + "' subtexts a declared column")
                        .isNotNull();
                    assertThat(column.renderer())
                        .as("step 2: " + who + " column '" + column.name()
                            + "' does not hand its whole cell to a renderer")
                        .isNull();
                }
                if (column.copyable()) {
                    assertThat(column.renderer())
                        .as("step 2: " + who + " copyable column '" + column.name()
                            + "' does not hand its whole cell to a renderer")
                        .isNull();
                }
            }

            // 3. A declared search field must be one the query layer can actually match:
            //    RecordSource.search throws on a field that is neither text-searchable nor
            //    coercible from the term, which is a 500 on the chooser, not a bad result.
            for (Field<?, ?> field : resource.searchFields()) {
                assertThat(field.isSecret())
                    .as("step 3: " + who + " search field '" + field.getName() + "' is not secret")
                    .isFalse();
                assertThat(field.isLocalized())
                    .as("step 3: " + who + " search field '" + field.getName() + "' is not localized")
                    .isFalse();
                assertThat(field)
                    .as("step 3: " + who + " search field '" + field.getName() + "' is text-searchable")
                    .isInstanceOf(TextSearchable.class);
            }

            if (!resource.searchFields().isEmpty() || !resource.searchColumns().isEmpty()) {
                assertThat(resource.searchOffered())
                    .as("step 3: " + who + " renders the box it declared fields for")
                    .isTrue();
                offering.add(resource.slug());
            }
        }

        // 4. And the inventory itself: every peer this wave gave a search box still has one.
        assertThat(offering)
            .as("step 4: the admin peers that offer a search box")
            .containsAll(SEARCHABLE_ADMIN_SLUGS);

        // 5. A spec that registers can still fail at RENDER (a column the row cannot
        //    answer, a label with no copy). Every list that traded its derived spec for a
        //    declared one is fetched once.
        for (String slug : NEW_TABLE_SPEC_SLUGS) {
            assertThat(adminGet("/admin/" + slug).statusCode())
                .as("step 5: /admin/" + slug + " renders its declared table spec")
                .isEqualTo(200);
        }
    }

    /**
     * Step 1-6: a declared search narrows the list, a term nothing matches returns NO rows
     * (never the whole list), and the richer cells render what they promised.
     */
    @Test
    void searchNarrowsAndCellsCarryTheirSecondHalf() throws Exception {
        seed();

        // 1. The plainest case: the name column.
        String byName = adminGet("/admin/sites?search=wavea-alpha").body();
        assertThat(byName).as("step 1: the site whose NAME matches is listed")
            .contains("wavea-alpha-site");
        assertThat(byName).as("step 1: and the one that does not is gone")
            .doesNotContain("wavea-beta-site");

        // 2. A field that is searchable but is NOT a visible column of its own: the slug
        //    rides as the name's subtext, and searching it still works.
        String bySlug = adminGet("/admin/sites?search=wavea-beta-slug").body();
        assertThat(bySlug).as("step 2: the site is found by the slug under its name")
            .contains("wavea-beta-site");
        assertThat(bySlug).as("step 2: and the other site is not")
            .doesNotContain("wavea-alpha-site");

        // 3. Fail CLOSED. A term no declared field can match must return NOTHING; returning
        //    the un-narrowed list would read as "everything matches".
        String noMatch = adminGet("/admin/sites?search=wavea-no-such-site-anywhere").body();
        assertThat(noMatch).as("step 3: a term nothing matches lists no seeded site")
            .doesNotContain("wavea-alpha-site")
            .doesNotContain("wavea-beta-site");

        // 4. The search this wave exists for: "which access list holds 10.77.0.5" was
        //    previously only answerable by opening every record. The rules are their own
        //    records now, so the question is asked of them -- and each answer names the
        //    list it belongs to.
        String byRule = adminGet("/admin/access-rules?search=10.77.0.5").body();
        assertThat(byRule).as("step 4: the rule holding the address names its list")
            .contains("wavea-allow-list");
        assertThat(byRule).as("step 4: and not the list that does not hold it")
            .doesNotContain("wavea-other-list");

        // 5. A host is found by an address that was stored and, before this wave, rendered
        //    nowhere at all.
        String byIp = adminGet("/admin/servers?search=198.51.100.44").body();
        assertThat(byIp).as("step 5: the host is found by its public address")
            .contains("wavea-host");

        // 6. And that address is what now reads under the host's name.
        assertThat(byIp).as("step 6: the name cell renders as two lines")
            .contains("cms-cell-composite")
            .contains("cms-cell-subtext");
        assertThat(byIp).as("step 6: whose second half is the public address")
            .contains("198.51.100.44");
    }

    /**
     * Step 1-3: the copy chip carries the RAW stored value of its own column, never the
     * rendered cell -- which for a subtexted column is a two-part composite state.
     */
    @Test
    void copyChipCarriesTheRawValueOfItsOwnColumn() throws Exception {
        seed();

        String body = adminGet("/admin/bans?search=203.0.113.201").body();

        // 1. The cell IS a composite: the address with the reason under it.
        assertThat(body).as("step 1: the address cell renders both halves")
            .contains("cms-cell-composite")
            .contains("cms-cell-subtext")
            .contains("wavea ban reason");

        // 2. The chip exists on that column.
        assertThat(body).as("step 2: the address column offers a copy chip")
            .contains("cms-cell-copy");

        // 3. And it carries the bare address -- not the composite, not the reason.
        assertThat(body)
            .as("step 3: the chip copies the raw stored address")
            .contains("value=\"203.0.113.201\"");
        int chip = body.indexOf("cms-cell-copy");
        assertThat(body.substring(chip, Math.min(body.length(), chip + 400)))
            .as("step 3: and nothing of the rendered second line rides along with it")
            .doesNotContain("wavea ban reason");
    }

    /**
     * Step 1-6: the per-resource list CHROME an operator actually sees, and the one relation
     * pick that must not offer to mint a host inline.
     *
     * AIDEV-NOTE: asserted on the SSR markers the framework templates carry
     * (data-cms-advanced-toggle / data-cms-views-toggle / data-cms-columns-toggle, and
     * zenit-forms' data-zf-chooser-dialog), never on visible label text -- a microcopy edit
     * must not silently turn this into an assertion about nothing. All four are rendered
     * only when their knob is on, so absence IS the assertion.
     */
    @Test
    void listChromeIsDeclaredPerResourceAndHostPicksRefuseInlineCreate() throws Exception {
        seed();

        // 1. Hosts: the ruling that started this wave. A handful of servers never needs a
        //    rule builder, saved views or a column gear.
        String hosts = adminGet("/admin/servers").body();
        assertThat(hosts).as("step 1: the hosts list renders").contains("cms-list-card");
        assertThat(hosts).as("step 1: hosts offer no advanced filter")
            .doesNotContain("data-cms-advanced-toggle");
        assertThat(hosts).as("step 1: hosts offer no saved views")
            .doesNotContain("data-cms-views-toggle");
        assertThat(hosts).as("step 1: hosts offer no column picker")
            .doesNotContain("data-cms-columns-toggle");

        // 2. But the search box a previous wave deliberately gave them STAYS: finding a host
        //    by the public address under its name is what that declaration exists for.
        assertThat(hosts).as("step 2: hosts keep their search box")
            .contains("cms-list-search");

        // 3. Certificates: the other named ruling -- no saved views on a list read by expiry.
        //    Only the views/advanced knobs are asserted here: the column gear lives in the
        //    TABLE's header cell, which an empty list replaces with the empty state.
        String certificates = adminGet("/admin/certificates").body();
        assertThat(certificates).as("step 3: certificates offer no saved views")
            .doesNotContain("data-cms-views-toggle");

        // 4. The positive control: sites keep all four, so steps 1 and 3 are proving a
        //    per-resource DECLARATION and not that the framework stopped rendering chrome.
        String sites = adminGet("/admin/sites").body();
        assertThat(sites).as("step 4: sites keep the advanced filter")
            .contains("data-cms-advanced-toggle");
        assertThat(sites).as("step 4: sites keep saved views")
            .contains("data-cms-views-toggle");
        assertThat(sites).as("step 4: sites keep the column picker")
            .contains("data-cms-columns-toggle");

        // 5. The instance create form's HOST pick offers no inline create: a host is
        //    admitted, preflighted and trusted, never minted from inside another form.
        String instanceForm = adminGet("/admin/instances/new").body();
        assertThat(instanceForm).as("step 5: the instance create form renders its host pick")
            .contains("name=\"server_id\"");
        assertThat(instanceForm).as("step 5: and that pick has no create dialog")
            .doesNotContain("data-zf-chooser-dialog=\"server_id\"");

        // 6. A pick left alone in the SAME form still offers it, so step 5 proves the
        //    per-entry off-switch rather than a form that lost inline create wholesale.
        assertThat(instanceForm).as("step 6: the environment pick keeps inline create")
            .contains("data-zf-chooser-dialog=\"environment_id\"");
    }

    /** Idempotent fixtures: this class shares its server, so every seed is find-or-create. */
    private static void seed() {
        site("wavea-alpha-site", "wavea-alpha-slug");
        site("wavea-beta-site", "wavea-beta-slug");

        var servers = Models.get(ServerModel.class);
        if (servers.find().where(ServerModel.NAME.eq("wavea-host")).first() == null) {
            Row row = servers.createEmptyRow();
            row.set(ServerModel.NAME, "wavea-host");
            row.set(ServerModel.MODE, ServerModel.MODE_SSH);
            row.set(ServerModel.RUNTIME, ServerModel.RUNTIME_DOCKER);
            row.set(ServerModel.SSH_TARGET, "root@wavea-host.test");
            row.set(ServerModel.PUBLIC_IPV4, "198.51.100.44");
            servers.save(row);
        }

        accessList("wavea-allow-list", "10.77.0.5", "10.77.0.6");
        accessList("wavea-other-list", "10.99.0.1");

        var bans = Models.get(BanModel.class);
        if (bans.find().where(BanModel.IP.eq("203.0.113.201")).first() == null) {
            Row row = bans.createEmptyRow();
            row.set(BanModel.IP, "203.0.113.201");
            row.set(BanModel.REASON, "wavea ban reason");
            row.set(BanModel.SOURCE, BanModel.SOURCE_MANUAL);
            row.set(BanModel.ACTIVE, true);
            row.set(BanModel.CREATED_AT, Instant.now());
            bans.save(row);
        }
    }

    private static void site(String name, String slug) {
        var model = Models.get(SiteModel.class);
        if (model.find().where(SiteModel.NAME.eq(name)).first() != null) {
            return;
        }
        Row row = model.createEmptyRow();
        row.set(SiteModel.NAME, name);
        row.set(SiteModel.SLUG, slug);
        row.set(SiteModel.SITE_TYPE, "hohenheim:static");
        row.set(SiteModel.SETTINGS, Map.of("root_path", "/tmp"));
        row.set(SiteModel.STATUS, SiteModel.STATUS_ACTIVE);
        row.set(SiteModel.ENABLED, true);
        model.save(row);
    }

    private static void accessList(String name, String... allowed) {
        var model = Models.get(AccessListModel.class);
        if (model.find().where(AccessListModel.NAME.eq(name)).first() != null) {
            return;
        }
        Row row = model.createEmptyRow();
        row.set(AccessListModel.NAME, name);
        row.set(AccessListModel.SATISFY, AccessListModel.SATISFY_ANY);
        model.save(row);

        // The addresses live in the list's rule tree now, not in a column on the list.
        var rules = Models.get(AccessRuleModel.class);
        for (String network : allowed) {
            Row rule = rules.createEmptyRow();
            rule.set(AccessRuleModel.ACCESS_LIST_ID, row.get(AccessListModel.ID));
            rule.set(AccessRuleModel.TYPE, AccessRuleModel.TYPE_IP_ALLOW);
            rule.set(AccessRuleModel.DATA, Map.of("network", network));
            rule.set(AccessRuleModel.ENABLED, true);
            rules.save(rule);
        }
    }
}
