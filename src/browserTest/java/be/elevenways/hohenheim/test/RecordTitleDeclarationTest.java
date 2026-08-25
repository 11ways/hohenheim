package be.elevenways.hohenheim.test;

import be.elevenways.hohenheim.model.AccessListModel;
import be.elevenways.hohenheim.model.AccessRuleModel;
import be.elevenways.hohenheim.model.CertificateModel;
import be.elevenways.hohenheim.model.DnsZoneModel;
import be.elevenways.hohenheim.server.cms.ManagePanel;
import be.elevenways.zenit.cms.common.panel.Panel;
import be.elevenways.zenit.cms.common.panel.PanelPeer;
import be.elevenways.zenit.cms.common.panel.PanelRegistry;
import be.elevenways.zenit.cms.common.resource.Resource;
import be.elevenways.zenit.cms.common.resource.RowResource;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.Models;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every record page of both panels is headed by something a human wrote down, never by
 * the class name the framework falls back to.
 *
 * AIDEV-NOTE: the fallback this guards against is {@code Model.getDisplayTitle}'s last
 * resort, {@code getModelName() + " #" + pk} -- which is why the reported symptom was
 * literally "DnsZone #1" on a page whose own tabs said "starfleet.life". It is invisible
 * to every other test: the page renders, returns 200, and simply says the wrong thing.
 * The walk is driven off the PANELS, so a resource added tomorrow is covered without
 * anyone extending a list here.
 */
class RecordTitleDeclarationTest extends HohenheimTestBase {

    /**
     * Peers whose records legitimately have no title of their own AND no relation to
     * borrow one from.
     *
     * AIDEV-NOTE: an allowlist, kept empty by intent -- every entry is a record page an
     * operator can still reach and read a class name on. It exists so the walk can stay
     * exhaustive rather than being narrowed to "the resources we already fixed".
     */
    private static final List<String> WITHOUT_A_TITLE = List.of();

    /**
     * Step 1-3: every model-backed resource of both panels can title one of its records,
     * either from its schema's display fields or from its own override.
     */
    @Test
    void everyResourceCanTitleItsOwnRecords() throws Exception {
        List<Resource<?>> resources = new ArrayList<>();
        for (String slug : List.of("admin", ManagePanel.SLUG)) {
            Panel panel = PanelRegistry.getBySlug(slug);
            assertThat(panel).as("step 1: the '" + slug + "' panel is registered").isNotNull();
            for (PanelPeer peer : panel.peers()) {
                if (peer instanceof Resource<?> resource) {
                    resources.add(resource);
                }
            }
        }
        assertThat(resources).as("step 1: both panels expose their resources")
            .hasSizeGreaterThan(30);

        List<String> untitled = new ArrayList<>();
        int walked = 0;
        for (Resource<?> resource : resources) {
            Model model = resource.model();
            if (model == null || WITHOUT_A_TITLE.contains(resource.slug())) {
                // A model-independent resource titles its own rows through recordTitle,
                // which has no schema to read and is checked by its own tests.
                continue;
            }
            walked++;
            boolean declares = !model.requireSchema().getDisplayFields().isEmpty()
                || overridesRecordTitle(resource);
            if (!declares) {
                untitled.add(resource.slug() + " -> " + model.getModelName()
                    + " (no display fields, no recordTitle override)");
            }
        }

        // 2. The walk cannot be allowed to empty itself: a filter that skipped everything
        //    would report green about nothing at all.
        assertThat(walked).as("step 2: model-backed resources actually walked")
            .isGreaterThan(25);

        // 3. And the finding itself.
        assertThat(untitled)
            .as("step 3: every record page is headed by a value, not by a class name")
            .isEmpty();
    }

    /**
     * Step 1-3: the three record pages the finding named say what the record IS -- the
     * zone's origin, the certificate's name, and what the access rule decides.
     */
    @Test
    void theReportedRecordPagesAreHeadedByTheirOwnValue() throws Exception {
        // 1. A zone is its origin, which its child tabs already said.
        Row zone = Models.get(DnsZoneModel.class).createEmptyRow();
        zone.set(DnsZoneModel.ORIGIN, "titles.example");
        Models.get(DnsZoneModel.class).save(zone);
        String zonePage = adminGet("/admin/dns-zones/" + zone.get(DnsZoneModel.ID)).body();
        assertThat(zonePage).as("step 1: the zone page is headed by the origin")
            .contains("titles.example");
        assertThat(zonePage).as("step 1: and never by the model class name")
            .doesNotContain("DnsZone #");

        // 2. A certificate is the name its operator gave it.
        Row certificate = Models.get(CertificateModel.class).createEmptyRow();
        certificate.set(CertificateModel.NICE_NAME, "Titles wildcard");
        certificate.set(CertificateModel.DOMAIN_NAMES_TEXT, "*.titles.example");
        Models.get(CertificateModel.class).save(certificate);
        String certPage = adminGet("/admin/certificates/"
            + certificate.get(CertificateModel.ID)).body();
        assertThat(certPage).as("step 2: the certificate page is headed by its name")
            .contains("Titles wildcard");
        assertThat(certPage).as("step 2: and never by the model class name")
            .doesNotContain("Certificate #");

        // 3. A rule has no name at all: it is what it decides, in the reader's words --
        //    the type's DECLARED label plus the summary the Rules tab renders, never the
        //    stored search text ("ip_allow 203.0.113.0/24") that used to head this page.
        Row list = Models.get(AccessListModel.class).createEmptyRow();
        list.set(AccessListModel.NAME, "Titles list");
        list.set(AccessListModel.SATISFY, AccessListModel.SATISFY_ALL);
        Models.get(AccessListModel.class).save(list);

        Row rule = Models.get(AccessRuleModel.class).createEmptyRow();
        rule.set(AccessRuleModel.ACCESS_LIST_ID, list.get(AccessListModel.ID));
        rule.set(AccessRuleModel.TYPE, AccessRuleModel.TYPE_IP_ALLOW);
        rule.set(AccessRuleModel.DATA, Map.of(
            AccessRuleModel.NETWORK.getName(), "203.0.113.0/24"));
        rule.set(AccessRuleModel.ENABLED, true);
        Models.get(AccessRuleModel.class).save(rule);

        String rulePage = adminGet("/admin/access-rules/"
            + rule.get(AccessRuleModel.ID)).body();
        assertThat(rulePage).as("step 3: the rule page names the kind in words")
            .contains("Allowed network");
        assertThat(rulePage).as("step 3: and what it decides")
            .contains("203.0.113.0/24");
        assertThat(rulePage).as("step 3: never the class-name fallback")
            .doesNotContain("AccessRule #");

        // 4. The same words head the LIST's name cell, which rendered the raw stored
        //    search text before -- type token included.
        String ruleList = adminGet("/admin/access-rules?search=203.0.113.0").body();
        assertThat(ruleList).as("step 4: the list cell reads as the rule, not as its index")
            .contains("Allowed network");
        assertThat(ruleList).as("step 4: the raw type token is gone from the cell")
            .doesNotContain("ip_allow 203.0.113.0/24");
    }

    /** @return whether this resource declares a record title of its own */
    private static boolean overridesRecordTitle(Resource<?> resource) throws Exception {
        Method declared = resource.getClass().getMethod("recordTitle", Object.class);
        Class<?> owner = declared.getDeclaringClass();
        if (!Resource.class.equals(owner) && !RowResource.class.equals(owner)) {
            return true;
        }
        // A RowResource's bridge method hides the Row-typed override; ask for that too.
        try {
            return !RowResource.class.equals(
                resource.getClass().getMethod("recordTitle", Row.class).getDeclaringClass());
        } catch (NoSuchMethodException notRowBacked) {
            return false;
        }
    }
}
