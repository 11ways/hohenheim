package be.elevenways.hohenheim.test;

import be.elevenways.hohenheim.model.CertificateModel;
import be.elevenways.hohenheim.server.cms.CertificateResource;
import be.elevenways.hohenheim.server.cms.DnsRecordResource;
import be.elevenways.hohenheim.server.cms.DnsZoneResource;
import be.elevenways.zenit.cms.common.action.RowAction;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.security.AccessContext;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Which row affordances earn a place in the row itself: editing and deleting a record do,
 * a credential chore and a PEM export do not -- they live in the row's overflow menu.
 */
class RowActionPlacementTest extends HohenheimTestBase {

    @Test
    void chorePlacementIsPerActionAndRealAffordancesStayInline() {
        // 1. DNS records: minting and revoking a dyndns credential are rare per row.
        Map<String, RowAction<Row>> records = byPath(new DnsRecordResource().rowActions());
        assertThat(records).as("step 1: both dyndns actions are still declared")
            .containsKeys("dyndns_token", "dyndns_revoke");
        assertThat(records.get("dyndns_token").inlineInRow())
            .as("step 1: minting a token is an overflow action").isFalse();
        assertThat(records.get("dyndns_revoke").inlineInRow())
            .as("step 1: so is revoking one").isFalse();

        // 2. Certificates: downloading the PEM is an export, not a per-row affordance.
        Map<String, RowAction<Row>> certificates = byPath(new CertificateResource().rowActions());
        assertThat(certificates.get("download_certificate"))
            .as("step 2: the download action is still declared").isNotNull();
        assertThat(certificates.get("download_certificate").inlineInRow())
            .as("step 2: and it overflows").isFalse();

        // 2b. Re-issuing is an overflow chore too, and it is OFFERED ONLY where it could
        //     work: a manual upload has no ACME order to repeat. (Visibility is not
        //     authorization -- the handler and the service refuse such a row as well.)
        RowAction<Row> reissue = certificates.get("reissue_certificate");
        assertThat(reissue).as("step 2b: the re-issue action is declared").isNotNull();
        assertThat(reissue.inlineInRow())
            .as("step 2b: and it overflows").isFalse();
        assertThat(reissue.visibleFor()).as("step 2b: it declares a visibility rule").isNotNull();
        assertThat(reissue.isVisibleFor(certificateRow(CertificateModel.PROVIDER_LETSENCRYPT),
                AccessContext.anonymous()))
            .as("step 2b: shown for a Let's Encrypt certificate").isTrue();
        assertThat(reissue.isVisibleFor(certificateRow(CertificateModel.PROVIDER_CUSTOM),
                AccessContext.anonymous()))
            .as("step 2b: hidden for a manual upload").isFalse();

        // 3. FALSIFICATION: an action that IS a per-row affordance keeps the row, so this
        //    is a per-action declaration and not a blanket demotion.
        Map<String, RowAction<Row>> zones = byPath(new DnsZoneResource().rowActions());
        assertThat(zones.get("dns_records")).as("step 3: the records link exists").isNotNull();
        assertThat(zones.get("dns_records").inlineInRow())
            .as("step 3: opening a zone's records stays inline").isTrue();
    }

    private static Row certificateRow(String provider) {
        Row row = Models.get(CertificateModel.class).createEmptyRow();
        row.set(CertificateModel.PROVIDER, provider);
        return row;
    }

    private static Map<String, RowAction<Row>> byPath(List<RowAction<Row>> actions) {
        Map<String, RowAction<Row>> map = new LinkedHashMap<>();
        for (RowAction<Row> action : actions) {
            map.put(action.id().getPath(), action);
        }
        return map;
    }
}
