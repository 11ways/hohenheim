package be.elevenways.hohenheim.test;

import be.elevenways.hohenheim.AttentionItem;
import be.elevenways.hohenheim.model.ReconcileFindingModel;
import be.elevenways.hohenheim.server.cms.AttentionCollector;
import be.elevenways.hohenheim.server.cms.ReconcileFindingResource;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.Models;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The dashboard row for unmanaged Docker resources must land the operator on the rows it
 * counted. It used to link to the bare findings list: every bucket of every host, so the
 * number read on the dashboard was never the number on screen, the sentence "not created by
 * Hohenheim" sat above rows Hohenheim created, and one of those rows carried a DESTRUCTIVE
 * remove action. The sentence also named a page ("Reconcile findings") that no surface in
 * the product carries.
 */
class ForeignResourceAttentionTest extends HohenheimTestBase {

    /** Seeded host names, distinct enough that a sibling suite's findings cannot collide. */
    private static final String HOST = "attref-host-a";
    private static final String OTHER_HOST = "attref-host-b";

    private static Row finding(String server, String name, String bucket) {
        Model findings = Models.get(ReconcileFindingModel.class);
        Row row = findings.createEmptyRow();
        row.set(ReconcileFindingModel.SERVER_NAME, server);
        row.set(ReconcileFindingModel.KIND, "container");
        row.set(ReconcileFindingModel.RESOURCE_NAME, name);
        row.set(ReconcileFindingModel.BUCKET, bucket);
        row.set(ReconcileFindingModel.EVIDENCE, "name");
        findings.save(row);
        return row;
    }

    private static AttentionItem itemFor(List<AttentionItem> items, String server) {
        for (AttentionItem candidate : items) {
            if (server.equals(candidate.title().args().get("server"))) {
                return candidate;
            }
        }
        return null;
    }

    @Test
    void theForeignResourceRowNamesItsPageAndLandsOnExactlyTheRowsItCounted() throws Exception {
        List<Row> seeded = new ArrayList<>();
        try {
            // 1. One host carrying every bucket the reconciler writes, and a second host
            //    with a foreign resource of its own, so "per host" is really per host.
            seeded.add(finding(HOST, "attref-foreign-known",
                ReconcileFindingModel.BUCKET_FOREIGN_KNOWN));
            seeded.add(finding(HOST, "attref-foreign-unrelated",
                ReconcileFindingModel.BUCKET_FOREIGN_UNRELATED));
            seeded.add(finding(HOST, "attref-owned", ReconcileFindingModel.BUCKET_OWNED));
            seeded.add(finding(HOST, "attref-orphaned", ReconcileFindingModel.BUCKET_ORPHANED));
            seeded.add(finding(HOST, "attref-colliding",
                ReconcileFindingModel.BUCKET_FOREIGN_COLLIDING));
            seeded.add(finding(OTHER_HOST, "attref-elsewhere",
                ReconcileFindingModel.BUCKET_FOREIGN_UNRELATED));

            List<AttentionItem> items = new ArrayList<>();
            AttentionCollector.dockerForeignResources(items);

            // 2. The row counts only the two buckets that mean "not ours, left alone":
            //    owned, orphaned and colliding are somebody else's item.
            AttentionItem row = itemFor(items, HOST);
            assertThat(row).as("step 2: the host with foreign resources gets a row").isNotNull();
            assertThat(row.severity()).as("step 2: foreign resources inform, never warn")
                .isEqualTo("info");
            assertThat(row.detail()).isNotNull();
            assertThat(row.detail().args().get("count"))
                .as("step 2: the count is the two foreign rows, not all five findings")
                .isEqualTo(2);

            // 3. The sentence names the page by the label that page actually carries --
            //    one declaring home, resolved in the reader's own locale.
            assertThat(row.detail().args().get("page"))
                .as("step 3: the detail names the target page by its own declared label")
                .isEqualTo(ReconcileFindingResource.LABEL);

            // 4. The link is narrowed, and readably so: the expression names the host and
            //    both counted buckets rather than being an opaque blob.
            String url = row.target().toUrl();
            assertThat(url).as("step 4: the link still points at the findings list")
                .contains("/admin/reconcile-findings");
            assertThat(url).as("step 4: narrowed to this host and the two foreign buckets")
                .contains(HOST)
                .contains(ReconcileFindingModel.BUCKET_FOREIGN_KNOWN)
                .contains(ReconcileFindingModel.BUCKET_FOREIGN_UNRELATED);

            // 5. And the page the operator lands on shows EXACTLY those rows: the whole
            //    point, and the step that binds our parameter spelling to what the CMS
            //    list page really reads.
            String landed = adminGet(url).body();
            assertThat(landed).as("step 5: both counted resources are listed")
                .contains("attref-foreign-known")
                .contains("attref-foreign-unrelated");
            assertThat(landed).as("step 5: and nothing the row did not count")
                .doesNotContain("attref-owned")
                .doesNotContain("attref-orphaned")
                .doesNotContain("attref-colliding")
                .doesNotContain("attref-elsewhere");

            // 6. The other host's row is its own, narrowed to its own resource.
            AttentionItem other = itemFor(items, OTHER_HOST);
            assertThat(other).as("step 6: the second host gets its own row").isNotNull();
            assertThat(other.detail().args().get("count"))
                .as("step 6: counting only its own foreign resource").isEqualTo(1);
            String elsewhere = adminGet(other.target().toUrl()).body();
            assertThat(elsewhere).as("step 6: which lands on that host's resource only")
                .contains("attref-elsewhere")
                .doesNotContain("attref-foreign-known");
        } finally {
            Model findings = Models.get(ReconcileFindingModel.class);
            for (Row row : seeded) {
                findings.delete(row.get(ReconcileFindingModel.ID));
            }
        }
    }
}
