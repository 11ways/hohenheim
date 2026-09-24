package be.elevenways.hohenheim.test;

import be.elevenways.hohenheim.model.ReconcileFindingModel;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.Models;
import org.junit.jupiter.api.Test;

import java.net.URLEncoder;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The findings list as the triage queue it is: the bucket and kind axes as a counted facet
 * rail beside the card, two tiles above it counting what the CURRENT filters still hold that
 * needs a decision (orphans, collisions), and the narrowed list leaving as CSV.
 *
 * @author Jelle De Loecker
 * @since  0.1.0
 */
class ReconcileFindingTriageTest extends HohenheimTestBase {

    /** Seeded host name, distinct enough that a sibling suite's findings cannot collide. */
    private static final String HOST = "triage-host-a";

    private static final String QUERY = "?q=" + URLEncoder.encode("server_name = \"" + HOST + "\"",
        StandardCharsets.UTF_8);

    private static Row finding(String name, String kind, String bucket) {
        Model findings = Models.get(ReconcileFindingModel.class);
        Row row = findings.createEmptyRow();
        row.set(ReconcileFindingModel.SERVER_NAME, HOST);
        row.set(ReconcileFindingModel.KIND, kind);
        row.set(ReconcileFindingModel.RESOURCE_NAME, name);
        row.set(ReconcileFindingModel.BUCKET, bucket);
        row.set(ReconcileFindingModel.EVIDENCE, "name");
        findings.save(row);
        return row;
    }

    private String tile(String label) {
        return page.locator("[data-cms-list-widgets] pl-stat-card:has(.label:text-is('" + label + "')) .value")
            .textContent().trim();
    }

    @Test
    void theQueueRailsItsAxesCountsItsNarrowingAndExportsIt() throws Exception {
        List<Row> seeded = new ArrayList<>();
        try {
            // 1. One host's findings: two orphans, one collision, one owned resource.
            seeded.add(finding("triage-orphan-container", "container", ReconcileFindingModel.BUCKET_ORPHANED));
            seeded.add(finding("triage-orphan-volume", "volume", ReconcileFindingModel.BUCKET_ORPHANED));
            seeded.add(finding("triage-colliding", "container", ReconcileFindingModel.BUCKET_FOREIGN_COLLIDING));
            seeded.add(finding("triage-owned", "network", ReconcileFindingModel.BUCKET_OWNED));

            // 2. Narrowed to that host, the rail offers both axes counted, and the tiles count
            //    the orphans and the collisions the narrowing holds.
            page.setViewportSize(1400, 900);
            navigateToApp("/admin/reconcile-findings" + QUERY);
            waitForHydration();
            assertCount("[data-cms-facet-rail] pl-facet", 2);
            assertThat(page.locator("[data-cms-facet-rail] pl-facet[data-filter-name='bucket']"
                    + " [data-facet-value='orphaned'] .pl-facet-option-count [aria-hidden='true']")
                .textContent().trim()).as("step 2: the rail counts this host's orphans").isEqualTo("2");
            assertThat(tile("Orphaned under these filters")).as("step 2: two orphans in the narrowing").isEqualTo("2");
            assertThat(tile("Colliding under these filters")).as("step 2: one collision").isEqualTo("1");

            // 3. Ticking the container kind in the rail narrows the list AND the tiles with it.
            click("[data-cms-facet-rail] pl-facet[data-filter-name='kind'] [data-facet-value='container']"
                + " .pl-facet-option-text");
            waitForCondition("window.location.search.indexOf('filter.kind=container') !== -1");
            waitForReactiveIdle();
            String orphanTile = "[data-cms-list-widgets] pl-stat-card:has(.label:text-is('Orphaned under these filters'))"
                + " .value";
            waitForTextEquals(orphanTile, "1");
            assertThat(tile("Orphaned under these filters")).as("step 3: the tile follows the rail tick: one"
                + " container orphan").isEqualTo("1");
            assertThat(tile("Colliding under these filters")).as("step 3: the collision is a container").isEqualTo("1");

            // 4. A typed search narrows the ROWS, never the tiles: they count under the filters, as worded.
            navigateToApp("/admin/reconcile-findings" + QUERY + "&search=triage-colliding");
            waitForHydration();
            waitForCount("pl-table-body pl-table-row[data-row-key]", 1);
            assertThat(page.locator("pl-table-body pl-table-row[data-row-key]").count())
                .as("step 4: the search narrows the rows to the one match").isEqualTo(1);
            assertThat(tile("Orphaned under these filters")).as("step 4: the search leaves the tile's count")
                .isEqualTo("2");

            // 5. The same narrowing leaves as CSV: this host's two container findings and nothing else.
            HttpResponse<String> csv = adminGet("/admin/reconcile-findings/export.csv" + QUERY
                + "&filter.kind=container");
            assertThat(csv.statusCode()).as("step 5: the export answers").isEqualTo(200);
            assertThat(csv.headers().firstValue("Content-Type").orElse("")).as("step 5: as CSV")
                .startsWith("text/csv");
            assertThat(csv.headers().firstValue("Content-Disposition").orElse("")).as("step 5: as an attachment")
                .startsWith("attachment; filename=\"reconcile-findings-");
            String[] lines = csv.body().split("\r\n", -1);
            assertThat(lines).as("step 5: a header, two findings and the final terminator").hasSize(4);
            assertThat(csv.body()).as("step 5: both container findings")
                .contains("triage-orphan-container").contains("triage-colliding")
                .doesNotContain("triage-orphan-volume").doesNotContain("triage-owned");
        } finally {
            Model findings = Models.get(ReconcileFindingModel.class);
            for (Row row : seeded) {
                findings.delete(row.get(ReconcileFindingModel.ID));
            }
        }
    }
}
