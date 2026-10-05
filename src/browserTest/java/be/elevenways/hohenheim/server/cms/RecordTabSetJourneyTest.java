package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.InstanceSnapshotModel;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.test.HardDeletes;
import be.elevenways.hohenheim.test.HohenheimTestBase;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * An app's record keeps the board's handful of tabs in view (overview, console, files, metrics, backups) and folds
 * the rest into "More"; its console is one tab whose modes switch inside it, and its backups, snapshots and schedules
 * are sections of one Backups tab.
 *
 * @author Jelle De Loecker
 * @since  0.2.0
 */
class RecordTabSetJourneyTest extends HohenheimTestBase {

    @Test
    void anInstanceKeepsTheBoardsTabsInViewAndItsConsoleSwitchesModesInOneTab() throws Exception {
        Row instance = instance("tab-set-workload");
        int id = instance.get(InstanceModel.ID);
        String record = "/admin/instances/" + id;
        Row snapshot = snapshot(id, "before the tab-set move");
        try {
            // 1. The strip keeps the board's daily tabs in view, in order, and folds the housekeeping into More.
            String overview = adminGet(record + "/page/overview").body();
            int more = overview.indexOf("cms-record-tabs-more");
            assertThat(more).as("step 1: the strip renders its More menu").isGreaterThan(-1);
            String strip = overview.substring(overview.indexOf("cms-record-tabs"), more);
            assertThat(strip).as("step 1: overview, console, files, metrics and backups stay on the strip")
                .containsSubsequence(record + "/page/overview", record + "/page/console", record + "/page/files",
                    record + "/page/stats", record + "/page/backups");
            assertThat(strip).as("step 1: the Metrics tab is called that")
                .contains("Metrics");
            assertThat(overview.substring(more)).as("step 1: provisioning sits in the More menu")
                .contains(record + "/page/provisioning");

            // 2. The console's other modes are not tabs: neither the strip nor its More menu offers them.
            assertThat(overview).as("step 2: the shell is a mode of the console, not a tab")
                .doesNotContain(record + "/page/shell");
            assertThat(overview).as("step 2: and so is the one-off command")
                .doesNotContain(record + "/page/exec");

            // 3. The Console tab opens on the live console and offers its other modes in one switch, the active one
            //    marked and explained.
            String console = adminGet(record + "/page/console").body();
            assertThat(console).as("step 3: the console offers its mode switch")
                .contains("data-console-mode=\"console\"")
                .contains("data-console-mode=\"shell\"")
                .contains("data-console-mode=\"exec\"");
            assertThat(console).as("step 3: a container has no screen mode")
                .doesNotContain("data-console-mode=\"framebuffer\"");
            assertThat(console).as("step 3: the active mode says what it is for")
                .contains("The workload's own output, live");

            // 4. A mode stands under the Console tab: the strip marks Console active, the switch marks the mode.
            String shell = adminGet(record + "/page/shell").body();
            Pattern consoleActive = Pattern.compile(
                "href=\"" + Pattern.quote(record + "/page/console") + "\"[^>]*aria-current=\"page\"");
            assertThat(consoleActive.matcher(shell).find())
                .as("step 4: on the shell route the strip marks the Console tab active")
                .isTrue();
            assertThat(shell).as("step 4: and the switch explains the shell")
                .contains("A terminal inside the workload");

            // 5. The one-off command keeps its own route and form, under the same switch.
            String command = adminGet(record + "/page/exec").body();
            assertThat(command).as("step 5: the command mode renders with the switch")
                .contains("data-console-mode=\"exec\"")
                .contains("Runs one command inside the workload");

            // 6. Backups holds the snapshots as a section, with the row's own record link; the old routes are retired.
            String backups = adminGet(record + "/page/backups").body();
            assertThat(backups).as("step 6: the Backups tab lists the snapshot")
                .contains("before the tab-set move")
                .contains("/admin/instance-snapshots/" + snapshot.get(InstanceSnapshotModel.ID));
            assertThat(adminGet(record + "/page/snapshots").statusCode())
                .as("step 6: the snapshots route is retired").isEqualTo(404);
            assertThat(adminGet(record + "/page/schedules").statusCode())
                .as("step 6: and so is the schedules route").isEqualTo(404);
        } finally {
            HardDeletes.row(Models.get(InstanceSnapshotModel.class), snapshot);
            HardDeletes.row(Models.get(InstanceModel.class), instance);
        }
    }

    @Test
    void theDelegatedConsoleHasNoOneOffCommand() {
        // 1. The operator's console offers the command mode; the delegated one never does (exec is ADMIN-sensitivity).
        assertThat(ConsoleModes.operator().tabs()).as("step 1: the operator console carries the command mode")
            .anyMatch(tab -> InstanceExecPage.SLUG.equals(tab.slug()));
        assertThat(ConsoleModes.delegated().tabs()).as("step 1: the delegated console does not")
            .noneMatch(tab -> InstanceExecPage.SLUG.equals(tab.slug()));

        // 2. Exactly one console tab sits in the strip on each panel: the hub; every other mode stays out of it.
        assertThat(ConsoleModes.operator().tabs()).as("step 2: only the hub is in the strip")
            .filteredOn(tab -> tab.inTabs())
            .singleElement().matches(tab -> InstanceConsolePage.SLUG.equals(tab.slug()));
    }

    private static Row instance(String name) {
        var instances = Models.get(InstanceModel.class);
        Row row = instances.createEmptyRow();
        row.set(InstanceModel.NAME, name);
        row.set(InstanceModel.KIND, "hohenheim:docker_container");
        row.set(InstanceModel.SETTINGS, Map.of("image", "alpine", "command", "sleep 60"));
        row.set(InstanceModel.STATUS, InstanceModel.STATUS_STOPPED);
        row.set(InstanceModel.SERVER_ID, ServerModel.localServerId());
        instances.save(row);
        return row;
    }

    private static Row snapshot(int instanceId, String note) {
        var snapshots = Models.get(InstanceSnapshotModel.class);
        Row row = snapshots.createEmptyRow();
        row.set(InstanceSnapshotModel.INSTANCE_ID, instanceId);
        row.set(InstanceSnapshotModel.STATUS, InstanceSnapshotModel.STATUS_COMPLETE);
        row.set(InstanceSnapshotModel.NOTE, note);
        row.set(InstanceSnapshotModel.TOTAL_BYTES, 1_024L);
        row.set(InstanceSnapshotModel.CREATED_AT, Instant.parse("2026-10-05T12:00:00Z"));
        snapshots.save(row);
        return row;
    }
}
