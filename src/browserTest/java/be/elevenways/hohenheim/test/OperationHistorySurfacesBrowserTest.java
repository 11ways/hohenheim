package be.elevenways.hohenheim.test;

import be.elevenways.hohenheim.HohenheimSlugs;
import be.elevenways.hohenheim.model.BuildOperationModel;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.ReleaseOperationModel;
import be.elevenways.hohenheim.server.cms.OperationHistoryParts;
import be.elevenways.zenit.auth.model.UserModel;
import be.elevenways.zenit.auth.model.UserPrincipal;
import be.elevenways.zenit.auth.server.AuthModels;
import be.elevenways.zenit.cms.test.support.PanelSurfaces;
import be.elevenways.zenit.cms.test.support.SurfaceBaselines;
import be.elevenways.zenit.cms.test.support.SurfaceCase;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.refusal.ZenitRefusalReason;
import be.elevenways.zenit.common.security.AccessContext;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

/**
 * The build and release histories, stored before they moved off the legacy OperationHistoryResource base onto
 * OperationHistoryParts and compared exactly after it: no declared difference.
 *
 * AIDEV-NOTE: the stored set ({@code /panel-surfaces/operation-history.txt}) is the behaviour captured before the
 * move. A failing comparison is a changed surface, never a file to refresh.
 */
class OperationHistorySurfacesBrowserTest extends HohenheimTestBase {

    private static final String PREFIX = "batch7-history-";
    private static final String ADMIN = HohenheimSlugs.ADMIN;
    private static final String BUILDS = OperationHistoryParts.BUILDS;
    private static final String RELEASES = OperationHistoryParts.RELEASES;

    private static String buildId;
    private static String releaseId;
    private static AccessContext operator;
    private static AccessContext tenant;

    @BeforeAll
    static void seed() {
        int tenantId = ApiSupport.user(PREFIX + "tenant@hohenheim.local", "Batch7 History Tenant");
        buildId = String.valueOf(build());
        releaseId = String.valueOf(release());
        operator = access(operatorPrincipal());
        tenant = access(new UserPrincipal(tenantId, "Batch7 History Tenant"));
    }

    @Test
    void theOperationHistoriesOfferWhatTheyOfferedBeforeTheMove() {
        SurfaceBaselines stored = SurfaceBaselines.load(OperationHistorySurfacesBrowserTest.class,
            "/panel-surfaces/operation-history.txt");

        // 1. Each history for the operator, record-less and on one record; a tenant is refused the admin panel.
        for (String entry : List.of(BUILDS, RELEASES)) {
            stored.check(capture(SurfaceCase.of(ADMIN, entry, "operator", operator)));
            stored.check(capture(SurfaceCase.of(ADMIN, entry, "tenant", tenant)
                .refusedFor(ZenitRefusalReason.FORBIDDEN)));
        }
        stored.check(capture(SurfaceCase.of(ADMIN, BUILDS, "operator", operator).onRecord(buildId, "build")));
        stored.check(capture(SurfaceCase.of(ADMIN, RELEASES, "operator", operator).onRecord(releaseId, "release")));

        // 2. Every stored case matched exactly.
        stored.finish();
    }

    /** A capture with every generated fixture id declared at the bindings a destination carries it. */
    private static PanelSurfaces capture(SurfaceCase fixture) {
        return PanelSurfaces.capture(fixture
            .key(BUILDS, "build", buildId)
            .key(RELEASES, "release", releaseId));
    }

    private static AccessContext access(UserPrincipal principal) {
        return AccessContext.of(TenantConduits.stubFor(principal));
    }

    private static UserPrincipal operatorPrincipal() {
        Row admin = AuthModels.users().find().where(UserModel.EMAIL.eq("test@hohenheim.local")).first();
        return new UserPrincipal(admin.get(UserModel.ID), "Test Admin");
    }

    private static int build() {
        Model builds = Models.get(BuildOperationModel.class);
        Row row = builds.createEmptyRow();
        row.set(BuildOperationModel.BUILDER_KIND, BuildOperationModel.KIND_DOCKERFILE);
        row.set(BuildOperationModel.FOR_MODEL, InstanceModel.MODEL_ID.toString());
        row.set(BuildOperationModel.FOR_ID, 1);
        row.set(BuildOperationModel.STATUS, BuildOperationModel.STATUS_SUCCEEDED);
        row.set(BuildOperationModel.SOURCE_REF, PREFIX + "ref");
        row.set(BuildOperationModel.IMAGE_ID, "sha256:" + PREFIX + "image");
        builds.save(row);
        return row.get(BuildOperationModel.ID);
    }

    private static int release() {
        Model releases = Models.get(ReleaseOperationModel.class);
        Row row = releases.createEmptyRow();
        row.set(ReleaseOperationModel.KIND, ReleaseOperationModel.KIND_RELEASE);
        row.set(ReleaseOperationModel.FOR_MODEL, InstanceModel.MODEL_ID.toString());
        row.set(ReleaseOperationModel.FOR_ID, 1);
        row.set(ReleaseOperationModel.STATUS, ReleaseOperationModel.STATUS_FAILED);
        row.set(ReleaseOperationModel.IMAGE_ID, "sha256:" + PREFIX + "image");
        row.set(ReleaseOperationModel.FAILURE_REASON, PREFIX + "probe failed");
        releases.save(row);
        return row.get(ReleaseOperationModel.ID);
    }
}
