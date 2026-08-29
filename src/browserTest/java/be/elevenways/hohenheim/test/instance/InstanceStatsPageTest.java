package be.elevenways.hohenheim.test.instance;

import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.test.HohenheimTestBase;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.Models;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.http.HttpResponse;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Stats tab renders for a RUNNING instance on every runtime.
 *
 * Pinned defect (QA 2026-08-29, F2): the tab was a 500 for every running instance --
 * the template handed the ref {@code InstanceStats.series} returns straight to the
 * sparkline's List property, and the only render test ever written asked a STOPPED
 * instance, whose branch never touches the charts. The Incus host below is the case the
 * finding named; the Docker one proves the fix is not per runtime.
 */
class InstanceStatsPageTest extends HohenheimTestBase {

    private static final String PREFIX = "statspage-";

    private static Integer incusHostId;
    private static Integer incusInstanceId;
    private static Integer dockerInstanceId;
    private static Integer stoppedInstanceId;

    /**
     * AIDEV-NOTE: deliberately NO {@code HostFixtures.admitLocal()} here. The stats page
     * reads the host's RUNTIME only, and admitting the shared local host would leak into
     * every class in this JVM (ProjectOwnershipTest expects it unadmitted, so a tenant
     * deploy is refused by PLACEMENT rather than reaching a daemon). The Incus host is a
     * private row that is deleted again below.
     */
    @BeforeAll
    static void seed() {
        Model servers = Models.get(ServerModel.class);
        Row incus = servers.createEmptyRow();
        incus.set(ServerModel.NAME, PREFIX + "incus");
        incus.set(ServerModel.RUNTIME, ServerModel.RUNTIME_INCUS);
        incus.set(ServerModel.MODE, ServerModel.MODE_LOCAL);
        servers.save(incus);
        incusHostId = incus.get(ServerModel.ID);

        incusInstanceId = instance(PREFIX + "vm", "hohenheim:system_container", incusHostId,
            InstanceModel.STATUS_RUNNING);
        dockerInstanceId = instance(PREFIX + "web", "hohenheim:docker_container",
            ServerModel.localServerId(), InstanceModel.STATUS_RUNNING);
        stoppedInstanceId = instance(PREFIX + "off", "hohenheim:docker_container",
            ServerModel.localServerId(), InstanceModel.STATUS_STOPPED);
    }

    @AfterAll
    static void cleanUp() {
        for (Integer id : new Integer[] {incusInstanceId, dockerInstanceId, stoppedInstanceId}) {
            if (id != null) {
                Models.get(InstanceModel.class).delete(id);
            }
        }
        if (incusHostId != null) {
            Models.get(ServerModel.class).delete(incusHostId);
        }
    }

    private static int instance(String name, String kind, Integer serverId, String status) {
        Model instances = Models.get(InstanceModel.class);
        Row row = instances.createEmptyRow();
        row.set(InstanceModel.NAME, name);
        row.set(InstanceModel.KIND, kind);
        row.set(InstanceModel.SERVER_ID, serverId);
        row.set(InstanceModel.SETTINGS, Map.of("image", "alpine", "tag", "latest"));
        row.set(InstanceModel.STATUS, status);
        instances.save(row);
        return row.get(InstanceModel.ID);
    }

    @Test
    void statsTabRendersItsChartsForARunningInstanceOnEveryRuntime() throws Exception {
        // 1. The Incus-hosted instance: the tab renders (nobody is watching it, so the
        //    seeds are empty series) with every chart present.
        HttpResponse<String> incus = adminGet("/admin/instances/" + incusInstanceId + "/page/stats");
        assertThat(incus.statusCode())
            .as("step 1: the Stats tab of a running Incus-hosted instance renders")
            .isEqualTo(200);
        assertThat(incus.body())
            .as("step 1: the charts are on the page, seeded from an empty ring")
            .contains("data-stats-contract")
            .contains("<pl-sparkline");
        assertThat(countOf(incus.body(), "<pl-sparkline"))
            .as("step 1: cpu, memory, received and transmitted -- four series")
            .isEqualTo(4);

        // 2. The same page on a Docker-hosted running instance: nothing is per runtime.
        HttpResponse<String> docker = adminGet("/admin/instances/" + dockerInstanceId + "/page/stats");
        assertThat(docker.statusCode())
            .as("step 2: the Docker-hosted instance renders the same charts")
            .isEqualTo(200);
        assertThat(countOf(docker.body(), "<pl-sparkline")).isEqualTo(4);

        // 3. A stopped instance keeps its honest empty state instead of empty charts.
        HttpResponse<String> stopped = adminGet("/admin/instances/" + stoppedInstanceId + "/page/stats");
        assertThat(stopped.statusCode()).isEqualTo(200);
        assertThat(stopped.body())
            .as("step 3: a stopped instance says so rather than drawing nothing")
            .contains("This instance is not running")
            .doesNotContain("<pl-sparkline");
    }

    private static int countOf(String haystack, String needle) {
        int count = 0;
        int at = haystack.indexOf(needle);
        while (at >= 0) {
            count++;
            at = haystack.indexOf(needle, at + needle.length());
        }
        return count;
    }
}
