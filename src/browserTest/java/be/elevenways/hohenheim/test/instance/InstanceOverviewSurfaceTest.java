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
 * The record dashboard's surface-action round trip, in a REAL browser: a widget-native
 * button on an instance's front door dispatches to {@code cms:{panel}/{resource}/{id}},
 * the page's own {@code onSurfaceAction} answers with a freshly built tree, and the host
 * element swaps its rendering in place.
 *
 * AIDEV-NOTE: the proof is a value that CHANGED between the first render and the click.
 * Asserting "the button exists and nothing exploded" would pass against a handler that
 * refused, against a host that ignored the outcome, and against a full page reload -- so
 * the disk observation is stamped BETWEEN the two renders, and a marker on window is
 * checked afterwards to prove the document was never reloaded.
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
    void aWidgetNativeButtonReRendersTheRecordTreeWithoutLeavingThePage() {
        InstanceModel instances = Models.get(InstanceModel.class);
        int id = instance();

        try {
            // 1. The front door renders as a widget surface, with the page's own
            //    widget-native button in it. A record dashboard that is not hosted by
            //    zn-widget-surface can never dispatch anything.
            navigateToApp("/admin/instances/" + id + "/page/overview");
            waitForHydration();

            assertThat(page.locator("zn-widget-surface").count())
                .as("step 1: the record's front door is a widget surface host")
                .isEqualTo(1);
            assertThat(page.locator("[data-surface-action=\"refresh\"]").count())
                .as("step 1: carrying the page's widget-native refresh button")
                .isEqualTo(1);

            // 2. Docker measures no root disk, so the honest first render is the
            //    not-measured state -- and emphatically not a bar.
            assertThat(page.locator(".widget-usage-unmeasured").count())
                .as("step 2: an unmeasured disk renders its named state")
                .isEqualTo(1);
            assertThat(page.locator("pl-usage-bar").count())
                .as("step 2: and never a zero bar, which reads as an empty disk")
                .isEqualTo(0);

            // 3. The sweeper's observation lands while the page is open. Nothing pushes
            //    it: a data-carrying widget re-renders only on SSR, soft-nav, or a
            //    tree-replace outcome -- which is exactly what the button asks for.
            Row row = instances.findById(id);
            row.set(InstanceModel.DISK_USED_BYTES, 3_221_225_472L);
            row.set(InstanceModel.DISK_LIMIT_BYTES, 4_294_967_296L);
            row.set(InstanceModel.DISK_OBSERVED_AT, Instant.parse("2026-08-19T08:00:00Z"));
            instances.save(row);

            executeScript("window.__hhSurfaceMarker = 'kept'");
            click("[data-surface-action=\"refresh\"]");

            // 4. THE ROUND TRIP: the click dispatched to the record surface, the page's
            //    onSurfaceAction rebuilt the tree from the re-loaded record, and the host
            //    swapped its rendering -- so the bar the first render refused now exists.
            waitForSelector("pl-usage-bar");
            assertThat(page.locator(".widget-usage-unmeasured").count())
                .as("step 4: the not-measured state is gone with the reading it denied")
                .isEqualTo(0);

            // 5. IN PLACE: no navigation, no reload. A full page load would answer step 4
            //    just as well and prove nothing about the surface lane.
            assertThat(executeScript("window.__hhSurfaceMarker"))
                .as("step 5: the document was never reloaded -- the tree was replaced")
                .isEqualTo("kept");
            assertThat(page.url())
                .as("step 5: and the operator is still on the record's front door")
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
