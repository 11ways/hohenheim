package be.elevenways.hohenheim.test;

import be.elevenways.hohenheim.model.OperationStatus;
import be.elevenways.hohenheim.HohenheimParams;
import be.elevenways.hohenheim.HohenheimSlugs;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.model.StackDeploymentModel;
import be.elevenways.hohenheim.model.StackFileModel;
import be.elevenways.hohenheim.model.StackModel;
import be.elevenways.hohenheim.model.StackServiceModel;
import be.elevenways.zenit.auth.model.UserModel;
import be.elevenways.zenit.auth.model.UserPrincipal;
import be.elevenways.hohenheim.server.cms.StackOperations;
import be.elevenways.hohenheim.server.cms.StackParts;
import be.elevenways.zenit.cms.common.render.table.SynthesizedRowActions;
import be.elevenways.zenit.cms.test.support.PanelSurfaces;
import be.elevenways.zenit.cms.test.support.PlacedOperationMoves;
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

import static be.elevenways.hohenheim.HohenheimSlugs.ADMIN;

/**
 * The stack, stack service and stack file entries, stored before they move off the legacy ValidatedRowResource base
 * onto shared parts and compared exactly after it.
 *
 * AIDEV-NOTE: the stored set ({@code /panel-surfaces/stacks.txt}) is the behaviour captured before the legacy
 * StackResource, StackServiceResource and StackFileResource moved onto StackParts. The accepted differences are the
 * declared placed operation moves: each legacy action kept its id and moved only its route. The active stack carries a
 * successful deployment, so it is offered stop and rollback; the inactive one is offered neither. A failing comparison
 * is a changed surface, never a file to refresh.
 */
class StackSurfacesBrowserTest extends HohenheimTestBase {

    private static final String PREFIX = "batch7-surf-";
    private static final String STACKS = HohenheimSlugs.STACKS;
    private static final String SERVICES = HohenheimSlugs.STACK_SERVICES;
    private static final String FILES = HohenheimSlugs.STACK_FILES;

    private static String activeStackId;
    private static String inactiveStackId;
    private static String serviceId;
    private static String fileId;
    private static AccessContext operator;
    private static AccessContext tenant;

    @BeforeAll
    static void seed() {
        int tenantId = ApiSupport.user(PREFIX + "tenant@hohenheim.local", "Batch7 Stack Tenant");
        int active = stack(PREFIX + "active", StackModel.STATUS_ACTIVE);
        activeStackId = String.valueOf(active);
        deployment(active);
        inactiveStackId = String.valueOf(stack(PREFIX + "inactive", StackModel.STATUS_INACTIVE));
        int service = service(active, "web");
        serviceId = String.valueOf(service);
        fileId = String.valueOf(file(service, "/etc/batch7/app.conf"));
        operator = access(operatorPrincipal());
        tenant = access(new UserPrincipal(tenantId, "Batch7 Stack Tenant"));
    }

    @Test
    void theStackEntriesOfferWhatTheyOfferedBeforeTheMove() {
        // The legacy row actions are the placed operations of the same ids, the daemon-wide reclaim the header
        // placement of its operation, and the stack and service deletes their delete operations; nothing else moved.
        SurfaceBaselines stored = SurfaceBaselines.load(StackSurfacesBrowserTest.class, "/panel-surfaces/stacks.txt")
            .placedOperations(PlacedOperationMoves.of(StackOperations.DEPLOY.id(), StackOperations.STOP.id(),
                    StackOperations.ROLLBACK.id(), StackOperations.REFRESH.id(), StackOperations.PURGE_VOLUMES.id())
                .header(STACKS, StackOperations.RECLAIM_IMAGES.id(), StackOperations.RECLAIM_IMAGES.id())
                .synthesized(STACKS, SynthesizedRowActions.DELETE, StackOperations.DELETE_STACK.id())
                .synthesized(SERVICES, SynthesizedRowActions.DELETE, StackOperations.DELETE_SERVICE.id()));

        // 1. Every entry for the operator, record-less; a tenant is refused the admin panel.
        for (String entry : List.of(STACKS, SERVICES, FILES)) {
            stored.check(capture(SurfaceCase.of(ADMIN, entry, "operator", operator)));
            stored.check(capture(SurfaceCase.of(ADMIN, entry, "tenant", tenant)
                .refusedFor(ZenitRefusalReason.FORBIDDEN)));
        }

        // 2. Each entry on its records: the active stack is offered stop and rollback, the inactive one neither.
        stored.check(capture(SurfaceCase.of(ADMIN, STACKS, "operator", operator).onRecord(activeStackId, "active")));
        stored.check(capture(SurfaceCase.of(ADMIN, STACKS, "operator", operator)
            .onRecord(inactiveStackId, "inactive")));
        stored.check(capture(SurfaceCase.of(ADMIN, SERVICES, "operator", operator).onRecord(serviceId, "service")));
        stored.check(capture(SurfaceCase.of(ADMIN, FILES, "operator", operator).onRecord(fileId, "file")));

        // 3. The parent prefills the service and file create forms carry from the Services tab.
        stored.check(capture(SurfaceCase.of(ADMIN, SERVICES, "operator", operator).named(ADMIN + "." + SERVICES
            + ".operator.prefill").withParameter(HohenheimParams.STACK_ID_PREFILL.getName(), activeStackId)));
        stored.check(capture(SurfaceCase.of(ADMIN, FILES, "operator", operator).named(ADMIN + "." + FILES
            + ".operator.prefill").withParameter(HohenheimParams.STACK_SERVICE_ID_PREFILL.getName(), serviceId)));

        // 4. Every stored case matched exactly.
        stored.finish();
    }

    /** A capture with every generated fixture id declared at the bindings a destination carries it. */
    private static PanelSurfaces capture(SurfaceCase fixture) {
        return PanelSurfaces.capture(fixture
            .key(STACKS, "active_stack", activeStackId)
            .key(STACKS, "inactive_stack", inactiveStackId)
            .key(SERVICES, "service", serviceId)
            .key(FILES, "file", fileId)
            .key(HohenheimParams.STACK_ID_PREFILL.getName(), "active_stack", activeStackId)
            .key(HohenheimParams.STACK_SERVICE_ID_PREFILL.getName(), "service", serviceId)
            .key("parent", "active_stack", activeStackId));
    }

    private static AccessContext access(UserPrincipal principal) {
        return AccessContext.of(TenantConduits.stubFor(principal));
    }

    private static UserPrincipal operatorPrincipal() {
        Row admin = Models.get(UserModel.class).find().where(UserModel.EMAIL.eq("test@hohenheim.local")).first();
        return new UserPrincipal(admin.get(UserModel.ID), "Test Admin");
    }

    private static int stack(String name, String status) {
        Model stacks = Models.get(StackModel.class);
        Row row = stacks.createEmptyRow();
        row.set(StackModel.NAME, name);
        row.set(StackModel.ENABLED, false);
        row.set(StackModel.SERVER_ID, ServerModel.localServerId());
        row.set(StackModel.STATUS, status);
        stacks.save(row);
        return row.get(StackModel.ID);
    }

    private static void deployment(int stack) {
        Model deployments = Models.get(StackDeploymentModel.class);
        Row row = deployments.createEmptyRow();
        row.set(StackDeploymentModel.STACK_ID, stack);
        row.set(StackDeploymentModel.STATUS, StackDeploymentModel.LIFECYCLE.stored(OperationStatus.SUCCEEDED));
        row.set(StackDeploymentModel.REASON, "manual");
        row.set(StackDeploymentModel.SPEC, "{}");
        deployments.save(row);
    }

    private static int service(int stack, String name) {
        Model services = Models.get(StackServiceModel.class);
        Row row = services.createEmptyRow();
        row.set(StackServiceModel.STACK_ID, stack);
        row.set(StackServiceModel.NAME, name);
        row.set(StackServiceModel.ENABLED, true);
        row.set(StackServiceModel.IMAGE, "alpine:latest");
        services.save(row);
        return row.get(StackServiceModel.ID);
    }

    private static int file(int service, String path) {
        Model files = Models.get(StackFileModel.class);
        Row row = files.createEmptyRow();
        row.set(StackFileModel.STACK_SERVICE_ID, service);
        row.set(StackFileModel.CONTAINER_PATH, path);
        row.set(StackFileModel.CONTENT, "listen=80");
        row.set(StackFileModel.MODE, "0644");
        files.save(row);
        return row.get(StackFileModel.ID);
    }
}
