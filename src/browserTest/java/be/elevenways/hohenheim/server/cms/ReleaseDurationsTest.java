package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.model.BuildOperationModel;
import be.elevenways.hohenheim.model.OperationStatus;
import be.elevenways.hohenheim.model.ReleaseOperationModel;
import be.elevenways.zenit.common.orm.datasource.Row;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The time a deploy took, as the Deploys tab reads it from walk-site's stored history on Starfleet: each release paired
 * with the build it ran, whichever of the two rows came first.
 *
 * @author Jelle De Loecker
 * @since 0.1.0
 */
class ReleaseDurationsTest {

    private static final String FIRST_IMAGE = "sha256:d645511f2708";
    private static final String SECOND_IMAGE = "sha256:ba379510ae33";

    @Test
    void eachReleaseTakesTheTimeOfTheBuildItRan() {
        // 1. walk-site's history, newest first, timestamps as stored: a first release handed a build made just before
        //    it, a failed release, then forward releases whose rows start a few milliseconds BEFORE the build they
        //    run, and a rollback that builds nothing.
        Row first = release(5, "release", "succeeded", FIRST_IMAGE, "2026-10-11T23:01:17.765069Z",
            "2026-10-11T23:01:19.921608Z", 2156);
        Row failed = release(6, "release", "failed", null, "2026-10-12T00:41:08.770351Z",
            "2026-10-12T00:41:33.612854Z", 24842);
        Row second = release(7, "release", "succeeded", FIRST_IMAGE, "2026-10-12T01:00:10.897069Z",
            "2026-10-12T01:00:55.071414Z", 44174);
        Row third = release(9, "release", "succeeded", SECOND_IMAGE, "2026-10-12T01:06:01.936801Z",
            "2026-10-12T01:06:44.934646Z", 42998);
        Row rollback = release(10, "rollback", "succeeded", FIRST_IMAGE, "2026-10-12T01:07:16.532127Z",
            "2026-10-12T01:07:36.974804Z", 20442);
        Row fourth = release(11, "release", "succeeded", SECOND_IMAGE, "2026-10-12T01:17:33.825902Z",
            "2026-10-12T01:18:17.432805Z", 43607);
        Row fifth = release(12, "release", "succeeded", SECOND_IMAGE, "2026-10-12T01:40:05.436214Z",
            "2026-10-12T01:40:54.146674Z", 48710);
        Row live = release(13, "release", "succeeded", SECOND_IMAGE, "2026-10-12T02:38:21.645634Z",
            "2026-10-12T02:39:06.086769Z", 44441);
        List<Row> releases = List.of(live, fifth, fourth, rollback, third, second, failed, first);
        List<Row> builds = List.of(
            build(23, SECOND_IMAGE, "2026-10-12T02:38:21.677773Z", "2026-10-12T02:38:46.684666Z"),
            build(22, SECOND_IMAGE, "2026-10-12T01:40:05.463671Z", "2026-10-12T01:40:32.934160Z"),
            build(21, SECOND_IMAGE, "2026-10-12T01:17:33.843444Z", "2026-10-12T01:17:56.370104Z"),
            build(20, SECOND_IMAGE, "2026-10-12T01:06:01.947296Z", "2026-10-12T01:06:24.427258Z"),
            build(18, FIRST_IMAGE, "2026-10-12T01:00:10.917624Z", "2026-10-12T01:00:36.042164Z"),
            build(17, FIRST_IMAGE, "2026-10-12T00:41:08.786455Z", "2026-10-12T00:41:33.559345Z"),
            build(16, FIRST_IMAGE, "2026-10-11T23:00:57.309147Z", "2026-10-11T23:01:17.756162Z"));
        Map<Object, Row> ran = InstanceDeploymentsPage.buildsRun(releases, builds);

        // 2. The live release ran its build inside its own span: it took its own 44 s, never the hour since the
        //    previous release's build of the same image (it read "59 min 1 s").
        assertThat(ran.get(13).get(BuildOperationModel.ID)).as("step 2: the live release ran build 23").isEqualTo(23);
        assertThat(InstanceDeploymentsPage.releaseDurationMs(live, ran.get(13)))
            .as("step 2: THE ASSERTION -- it took its own time").isEqualTo(44441);

        // 3. Every forward release reads its own time, its build inside it.
        for (Row forward : List.of(second, third, fourth, fifth)) {
            Object id = forward.get(ReleaseOperationModel.ID);
            assertThat(InstanceDeploymentsPage.releaseDurationMs(forward, ran.get(id)))
                .as("step 3: release " + id + " took its own time")
                .isEqualTo(forward.get(ReleaseOperationModel.DURATION_MS));
        }

        // 4. The first release was handed the build made just before it: it took from that build's start to its end.
        assertThat(ran.get(5).get(BuildOperationModel.ID)).as("step 4: the first release ran build 16").isEqualTo(16);
        assertThat(InstanceDeploymentsPage.releaseDurationMs(first, ran.get(5)))
            .as("step 4: build and switch together").isEqualTo(22612);

        // 5. The rollback ran no build: the builds of its image were the releases' that ran them.
        assertThat(ran.get(10)).as("step 5: the rollback ran no build").isNull();
        assertThat(InstanceDeploymentsPage.releaseDurationMs(rollback, null)).as("step 5: its own time")
            .isEqualTo(20442);
    }

    private static Row release(int id, String kind, String status, String image, String started, String finished,
                               int durationMs) {
        Row row = new Row();
        row.set(ReleaseOperationModel.ID, id);
        row.set(ReleaseOperationModel.KIND, kind);
        row.set(ReleaseOperationModel.STATUS, status);
        row.set(ReleaseOperationModel.IMAGE_ID, image);
        row.set(ReleaseOperationModel.STARTED_AT, Instant.parse(started));
        row.set(ReleaseOperationModel.FINISHED_AT, Instant.parse(finished));
        row.set(ReleaseOperationModel.DURATION_MS, durationMs);
        return row;
    }

    private static Row build(int id, String image, String started, String finished) {
        Row row = new Row();
        row.set(BuildOperationModel.ID, id);
        row.set(BuildOperationModel.STATUS, BuildOperationModel.LIFECYCLE.stored(OperationStatus.SUCCEEDED));
        row.set(BuildOperationModel.IMAGE_ID, image);
        row.set(BuildOperationModel.STARTED_AT, Instant.parse(started));
        row.set(BuildOperationModel.FINISHED_AT, Instant.parse(finished));
        return row;
    }
}
