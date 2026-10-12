package be.elevenways.hohenheim.test;

import be.elevenways.hohenheim.model.AccessListModel;
import be.elevenways.hohenheim.model.AccessRuleModel;
import be.elevenways.hohenheim.model.CertificateModel;
import be.elevenways.hohenheim.model.DnsZoneModel;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The record pages the finding named are headed by something a human wrote down, never by the class name the
 * framework falls back to.
 *
 * AIDEV-NOTE: the fallback this guards against is {@code Model.getDisplayTitle}'s last
 * resort, {@code getModelName() + " #" + pk} -- which is why the reported symptom was
 * literally "DnsZone #1" on a page whose own tabs said "starfleet.life". That every resource of every panel CAN title
 * its records is no longer walked here: zenit-cms's panel walk (FieldLabelAudit, RecordTitleSources) refuses an
 * untitled resource and a title mapping that answers null in every test lane.
 */
class RecordTitleDeclarationTest extends HohenheimTestBase {

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
        String ruleList = adminGet("/admin/access-rules?text=203.0.113.0").body();
        assertThat(ruleList).as("step 4: the list cell reads as the rule, not as its index")
            .contains("Allowed network");
        assertThat(ruleList).as("step 4: the raw type token is gone from the cell")
            .doesNotContain("ip_allow 203.0.113.0/24");
    }

}
