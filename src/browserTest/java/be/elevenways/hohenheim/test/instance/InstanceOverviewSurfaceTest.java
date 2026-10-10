package be.elevenways.hohenheim.test.instance;

import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.test.HohenheimTestBase;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The record overview's front door in a REAL browser: a widget surface whose gauges read the stored evidence afresh on
 * every load, with no Refresh of its own.
 *
 * AIDEV-NOTE: the overview has no Refresh (a no-op operation; "loading the overview reads the same stored
 * evidence afresh"), so the round trip under test is a LOAD, not a surface action; the
 * surface-action tree swap itself is zenit-cms's SurfaceActionBrowserTest. The proof is still a value that CHANGED
 * between two renders: the disk observation is stamped between the first load and the reload.
 */
class InstanceOverviewSurfaceTest extends HohenheimTestBase {

    private static Integer instanceId;

    private int instance() {
        if (instanceId != null) {
            return instanceId;
        }
        InstanceModel instances = Models.get(InstanceModel.class);
        Row row = instances.createEmptyRow();
        row.set(InstanceModel.NAME, "surface-instance");
        row.set(InstanceModel.KIND, "hohenheim:docker_container");
        row.set(InstanceModel.SETTINGS, Map.of("image", "alpine", "command", "sleep 60"));
        row.set(InstanceModel.STATUS, InstanceModel.STATUS_STOPPED);
        row.set(InstanceModel.SERVER_ID, ServerModel.localServerId());
        instances.save(row);
        instanceId = row.get(InstanceModel.ID);
        return instanceId;
    }

    @Test
    void theFrontDoorReadsTheStoredDiskEvidenceAfreshOnEachLoad() {
        InstanceModel instances = Models.get(InstanceModel.class);
        int id = instance();

        try {
            // 1. The front door renders as a widget surface, and offers no Refresh: loading it is what reads the
            //    stored evidence.
            navigateToApp("/admin/instances/" + id + "/page/overview");
            waitForHydration();

            assertThat(page.locator("zn-widget-surface").count())
                .as("step 1: the record's front door is a widget surface host")
                .isEqualTo(1);
            assertThat(page.locator("[data-surface-action]").count())
                .as("step 1: with no surface action of its own, the no-op Refresh is gone")
                .isEqualTo(0);

            // 2. A stopped Docker workload has no live memory or CPU sample, and Docker measures no root disk, so
            //    the honest first render is three named not-measured gauges -- and emphatically not a bar.
            assertThat(page.locator(".widget-usage-unmeasured").count())
                .as("step 2: memory, disk and CPU render their named not-measured state")
                .isEqualTo(3);
            assertThat(page.locator("pl-usage-bar").count())
                .as("step 2: and never a zero bar, which reads as an empty disk")
                .isEqualTo(0);

            // 3. The sweeper's observation lands while the page is open.
            Row row = instances.findById(id);
            row.set(InstanceModel.DISK_USED_BYTES, 3_221_225_472L);
            row.set(InstanceModel.DISK_LIMIT_BYTES, 4_294_967_296L);
            row.set(InstanceModel.DISK_OBSERVED_AT, Instant.parse("2026-08-19T08:00:00Z"));
            instances.save(row);

            // 4. THE ROUND TRIP: the next load reads the stored observation, so the bar the first render refused
            //    now exists, and only the disk left the not-measured state.
            page.reload();
            waitForHydration();
            waitForSelector("pl-usage-bar");
            assertThat(page.locator("pl-usage-bar").count())
                .as("step 4: the disk renders its one measured bar")
                .isEqualTo(1);
            assertThat(page.locator(".widget-usage-unmeasured").count())
                .as("step 4: memory and CPU stay not measured while the workload is stopped")
                .isEqualTo(2);

            // 5. The operator is still on the record's front door.
            assertThat(page.url())
                .as("step 5: the reload kept the operator on the record's front door")
                .endsWith("/admin/instances/" + id + "/page/overview");
        } finally {
            Row cleared = instances.findById(id);
            cleared.set(InstanceModel.DISK_USED_BYTES, (Long) null);
            cleared.set(InstanceModel.DISK_LIMIT_BYTES, (Long) null);
            cleared.set(InstanceModel.DISK_OBSERVED_AT, (Instant) null);
            instances.save(cleared);
        }
    }
}
