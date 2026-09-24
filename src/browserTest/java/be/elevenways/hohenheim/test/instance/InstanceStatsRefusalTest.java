package be.elevenways.hohenheim.test.instance;

import be.elevenways.hohenheim.HohenheimChannels;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.hohenheim.server.instance.InstanceStatsHandler;
import be.elevenways.hohenheim.test.ApiSupport;
import be.elevenways.hohenheim.test.HohenheimTestBase;
import be.elevenways.hohenheim.test.docker.FakeDockerDaemon;
import be.elevenways.zenit.auth.model.GrantSubjectType;
import be.elevenways.zenit.auth.model.UserModel;
import be.elevenways.zenit.auth.model.UserPrincipal;
import be.elevenways.zenit.auth.server.AuthModels;
import be.elevenways.zenit.auth.server.RecordGrants;
import be.elevenways.zenit.common.channel.ChannelException;
import be.elevenways.zenit.common.channel.FakeChannelLink;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * A live-stats link that cannot open tells a DELEGATED viewer only that there is nothing
 * to stream; the daemon's own failure text (socket paths, host names, transport errors)
 * reaches operators alone -- the InstanceOverviewPage install_error rule.
 */
class InstanceStatsRefusalTest extends HohenheimTestBase {

    @Test
    void aDelegatedViewerNeverReadsTheDaemonsFailureText() {
        // AIDEV-NOTE: the workload runs on the hermetic FakeDockerDaemon, whose runtime has
        // no live-stats lane, so opening the stream fails at once with the DRIVER's own text.
        // This used to ask the host's real Docker daemon for a container that never existed:
        // silently non-hermetic, and the daemon's 404 on the stats stream held each open for
        // the full 60s stream timeout (120s per run). The subject -- whose eyes the failure
        // text reaches -- is the same for any failure text.
        FakeDockerDaemon daemon = new FakeDockerDaemon();
        daemon.installContainerKind();
        try {
            refusalJourney();
        } finally {
            FakeDockerDaemon.restore();
            daemon.close();
        }
    }

    private void refusalJourney() {
        // An instance whose workload was never created: the driver has nothing to stream.
        Row instance = Models.get(InstanceModel.class).createEmptyRow();
        instance.set(InstanceModel.NAME, "stats-refusal");
        instance.set(InstanceModel.KIND, "hohenheim:docker_container");
        instance.set(InstanceModel.SETTINGS, new LinkedHashMap<>(
            Map.of("image", "alpine", "tag", "latest", "command", "sleep 300")));
        instance.set(InstanceModel.STATUS, InstanceModel.STATUS_CREATED);
        Models.get(InstanceModel.class).save(instance);
        int instanceId = instance.get(InstanceModel.ID);

        Integer tenantId = ApiSupport.user("stats-refusal-tenant@hohenheim.local",
            "Stats Refusal Tenant");
        try {
            RecordGrants.grant(GrantSubjectType.USER, tenantId, InstanceModel.MODEL_ID,
                instanceId, HohenheimAccess.VIEW, true);

            // 1. A tenant holding VIEW is admitted, and its refusal is the bare sentence.
            FakeChannelLink<Object, Object> tenantLink = new FakeChannelLink<>(
                HohenheimChannels.INSTANCE_STATS, "stats-tenant")
                .principal(new UserPrincipal(tenantId, "Stats Refusal Tenant"));
            Throwable tenantRefusal = catchThrowable(() ->
                new InstanceStatsHandler(tenantLink).onOpen(instanceId));
            assertThat(tenantRefusal)
                .as("step 1: a workload with nothing to stream refuses the link")
                .isInstanceOf(ChannelException.class);
            assertThat(tenantRefusal.getMessage())
                .as("step 1: the delegated viewer reads no daemon text at all")
                .isEqualTo("No live stats");

            // 2. The harness administrator gets the reason, which is what they act on.
            Row adminRow = AuthModels.users().find()
                .where(UserModel.EMAIL.eq("test@hohenheim.local")).first();
            FakeChannelLink<Object, Object> adminLink = new FakeChannelLink<>(
                HohenheimChannels.INSTANCE_STATS, "stats-admin")
                .principal(new UserPrincipal(adminRow.get(UserModel.ID), "Test Admin"));
            Throwable adminRefusal = catchThrowable(() ->
                new InstanceStatsHandler(adminLink).onOpen(instanceId));
            assertThat(adminRefusal)
                .as("step 2: the operator's link is refused too")
                .isInstanceOf(ChannelException.class);
            assertThat(adminRefusal.getMessage())
                .as("step 2: and the operator reads the driver's own reason")
                .startsWith("No live stats: ")
                .contains("has no live-stats lane");
        } finally {
            Models.get(InstanceModel.class).delete(instanceId);
            AuthModels.users().delete(tenantId);
        }
    }
}
