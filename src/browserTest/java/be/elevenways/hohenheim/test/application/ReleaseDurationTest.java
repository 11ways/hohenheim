package be.elevenways.hohenheim.test.application;

import be.elevenways.hohenheim.HohenheimSlugs;
import be.elevenways.hohenheim.model.BuildOperationModel;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.OperationStatus;
import be.elevenways.hohenheim.model.ReleaseOperationModel;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.server.instance.ApplicationKind;
import be.elevenways.hohenheim.test.HardDeletes;
import be.elevenways.hohenheim.test.HohenheimHttpTestBase;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Deploys tab's Took cell of a release covers the whole release, the build that made its image included.
 *
 * AIDEV-NOTE: the release row times only its own switch, so the first static release on Daystrom read "548 ms" for a
 * build that took over a minute (R3b6).
 *
 * @author Jelle De Loecker
 * @since  0.1.0
 */
class ReleaseDurationTest extends HohenheimHttpTestBase {

    private static final Instant T0 = Instant.parse("2026-10-15T10:00:00Z");

    @Test
    void aReleaseTookItsBuildToo() throws Exception {
        int applicationId = application();
        try {
            // A first release built for 60 s and switched in 548 ms; a second built for 20 s and switched in 1 s;
            // then a rollback to the first image, which built nothing and switched in 700 ms.
            build(applicationId, "sha256:aaa", T0, 60_000);
            release(applicationId, ReleaseOperationModel.KIND_RELEASE, "sha256:aaa", T0.plusMillis(60_100), 548);
            build(applicationId, "sha256:bbb", T0.plusSeconds(300), 20_000);
            release(applicationId, ReleaseOperationModel.KIND_RELEASE, "sha256:bbb", T0.plusSeconds(320), 1_000);
            release(applicationId, ReleaseOperationModel.KIND_ROLLBACK, "sha256:aaa", T0.plusSeconds(600), 700);

            String body = adminGet("/admin/instances/" + applicationId + "/page/" + HohenheimSlugs.Tab.DEPLOYMENTS)
                .body();

            // 1. BUG FIXED: the first release took its build plus its switch, never the switch alone.
            assertThat(body).as("step 1: the first release took its build too, a minute in its largest unit")
                .contains(">1 min<")
                .as("step 1: never the switch time alone").doesNotContain(">0.5 s<");

            // 2. The second release took its own build, from the build's start to the switch's end.
            assertThat(body).as("step 2: the second release took 21 s").contains(">21 s<");

            // 3. The rollback built nothing: the first image's build belongs to the release that ran it.
            assertThat(body).as("step 3: the rollback took its switch alone, under a second in seconds")
                .contains(">0.7 s<");
        } finally {
            Models.get(ReleaseOperationModel.class).find()
                .where(ReleaseOperationModel.FOR_ID.eq(applicationId)).delete();
            Models.get(BuildOperationModel.class).find()
                .where(BuildOperationModel.FOR_ID.eq(applicationId)).delete();
            HardDeletes.byId(Models.get(InstanceModel.class), applicationId);
        }
    }

    private static int application() {
        Row row = Models.get(InstanceModel.class).createEmptyRow();
        row.set(InstanceModel.NAME, "release-duration");
        row.set(InstanceModel.KIND, ApplicationKind.ID.toString());
        row.set(InstanceModel.SETTINGS, new LinkedHashMap<>(Map.of("image", "alpine", "tag", "latest")));
        row.set(InstanceModel.STATUS, InstanceModel.STATUS_CREATED);
        row.set(InstanceModel.SERVER_ID, ServerModel.localServerId());
        Models.get(InstanceModel.class).save(row);
        return row.get(InstanceModel.ID);
    }

    private static void build(int applicationId, String image, Instant started, int durationMs) {
        BuildOperationModel model = Models.get(BuildOperationModel.class);
        Row row = model.createEmptyRow();
        row.set(BuildOperationModel.BUILDER_KIND, BuildOperationModel.KIND_STATIC);
        row.set(BuildOperationModel.FOR_MODEL, InstanceModel.MODEL_ID.toString());
        row.set(BuildOperationModel.FOR_ID, applicationId);
        row.set(BuildOperationModel.STATUS, BuildOperationModel.LIFECYCLE.stored(OperationStatus.SUCCEEDED));
        row.set(BuildOperationModel.SOURCE_REF, "0123456789abcdef0123456789abcdef01234567");
        row.set(BuildOperationModel.IMAGE_ID, image);
        row.set(BuildOperationModel.STARTED_AT, started);
        row.set(BuildOperationModel.FINISHED_AT, started.plusMillis(durationMs));
        row.set(BuildOperationModel.DURATION_MS, durationMs);
        model.save(row);
    }

    private static void release(int applicationId, String kind, String image, Instant started, int durationMs) {
        ReleaseOperationModel model = Models.get(ReleaseOperationModel.class);
        Row row = model.createEmptyRow();
        row.set(ReleaseOperationModel.KIND, kind);
        row.set(ReleaseOperationModel.FOR_MODEL, InstanceModel.MODEL_ID.toString());
        row.set(ReleaseOperationModel.FOR_ID, applicationId);
        row.set(ReleaseOperationModel.STATUS, ReleaseOperationModel.LIFECYCLE.stored(OperationStatus.SUCCEEDED));
        row.set(ReleaseOperationModel.IMAGE_ID, image);
        row.set(ReleaseOperationModel.STARTED_AT, started);
        row.set(ReleaseOperationModel.FINISHED_AT, started.plusMillis(durationMs));
        row.set(ReleaseOperationModel.DURATION_MS, durationMs);
        model.save(row);
    }
}
