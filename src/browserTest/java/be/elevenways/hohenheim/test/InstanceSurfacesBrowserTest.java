package be.elevenways.hohenheim.test;

import be.elevenways.hohenheim.HohenheimParams;
import be.elevenways.hohenheim.HohenheimSlugs;
import be.elevenways.hohenheim.instance.InstanceOperations;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.InstanceTemplateModel;
import be.elevenways.hohenheim.HohenheimCapabilities;
import be.elevenways.zenit.auth.model.GrantSubjectType;
import be.elevenways.zenit.auth.model.UserModel;
import be.elevenways.zenit.auth.model.UserPrincipal;
import be.elevenways.zenit.auth.server.RecordGrants;
import be.elevenways.zenit.cms.test.support.PanelSurfaces;
import be.elevenways.zenit.cms.common.render.table.SynthesizedRowActions;
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

import java.util.LinkedHashMap;
import java.util.Map;

import static be.elevenways.hohenheim.HohenheimSlugs.ADMIN;
import static be.elevenways.hohenheim.HohenheimSlugs.MANAGE;

/**
 * The instance entry of stage 5 B14, admin and tenant twin, stored before its verbs and parts move and compared
 * exactly after it.
 *
 * AIDEV-NOTE: the stored set ({@code /panel-surfaces/instances.txt}) is the behaviour captured on a template-linked
 * instance whose install failed, so the install, reinstall, expose, migrate and delete-with-data verbs all show. The
 * accepted difference is the moved invokes' route; a failing comparison is a changed surface, never a file to refresh.
 *
 * AIDEV-NOTE: the failed-install fixture sits on a host that is not admitted, so start, restart, install and reinstall
 * carry {@code disabled=host_not_admitted} (the tenant's start {@code deploy_blocked_delegated}), the overview notice's
 * own reason. The fixture's MANAGE grant implies {@code destroy}, so the tenant's record case offers the verified
 * destroy ({@code verb DELETE} and the delete row) as the operator's does.
 *
 * AIDEV-NOTE: a picker's binding digest hashes its resolver's DRY-serialized configuration, so a change to how
 * protoblast's DRY writes a type name moves the host, runtime-image, repository and branch resolver digests.
 */
class InstanceSurfacesBrowserTest extends HohenheimTestBase {

    private static final String PREFIX = "b14-instances-";
    private static final String INSTANCES = HohenheimSlugs.INSTANCES;

    private static String instanceId;
    private static String templateId;
    private static AccessContext operator;
    private static AccessContext tenant;

    @BeforeAll
    static void seed() throws Exception {
        // The immutable capture assumes this fixture's inventory, not another class's git providers.
        freshSeededDatabase();
        int tenantId = ApiSupport.user(PREFIX + "tenant@hohenheim.local", "B14 Instance Tenant");
        int template = template(PREFIX + "template");
        templateId = String.valueOf(template);
        int instance = instance(PREFIX + "instance", template);
        instanceId = String.valueOf(instance);
        RecordGrants.grant(GrantSubjectType.USER, tenantId, InstanceModel.MODEL_ID, instance,
            HohenheimCapabilities.MANAGE, true);
        operator = access(operatorPrincipal());
        tenant = access(new UserPrincipal(tenantId, "B14 Instance Tenant"));
    }

    @Test
    void theInstanceEntryOffersWhatItOfferedBeforeTheMove() {
        SurfaceBaselines stored = SurfaceBaselines.load(InstanceSurfacesBrowserTest.class,
            "/panel-surfaces/instances.txt")
            // The legacy install-lifecycle and destroy-with-data invokes became placed operations of the same ids, and
            // the generic delete became the verified destroy's operation.
            .placedOperations(PlacedOperationMoves.of(InstanceOperations.INSTALL.id(),
                    InstanceOperations.REINSTALL.id(), InstanceOperations.DESTROY_WITH_DATA.id())
                .synthesized(INSTANCES, SynthesizedRowActions.DELETE, InstanceOperations.DELETE.id()));

        // 1. The operator's entry, record-less and on the failed-install record; a tenant is refused the admin panel.
        stored.check(capture(SurfaceCase.of(ADMIN, INSTANCES, "operator", operator)));
        stored.check(capture(SurfaceCase.of(ADMIN, INSTANCES, "operator", operator)
            .onRecord(instanceId, "failed-install")));
        stored.check(capture(SurfaceCase.of(ADMIN, INSTANCES, "tenant", tenant)
            .refusedFor(ZenitRefusalReason.FORBIDDEN)));

        // 2. The tenant's twin over the instance it manages.
        stored.check(capture(SurfaceCase.of(MANAGE, INSTANCES, "tenant", tenant)));
        stored.check(capture(SurfaceCase.of(MANAGE, INSTANCES, "tenant", tenant)
            .onRecord(instanceId, "failed-install")));

        // 3. Every stored case matched exactly.
        stored.finish();
    }

    private static PanelSurfaces capture(SurfaceCase fixture) {
        return PanelSurfaces.capture(fixture
            .key(INSTANCES, "instance", instanceId)
            .key(HohenheimParams.INSTANCE_ID_PREFILL.getName(), "instance", instanceId)
            .key(HohenheimSlugs.INSTANCE_TEMPLATES, "template", templateId));
    }

    private static AccessContext access(UserPrincipal principal) {
        return AccessContext.of(TenantConduits.stubFor(principal));
    }

    private static UserPrincipal operatorPrincipal() {
        Row admin = Models.get(UserModel.class).find().where(UserModel.EMAIL.eq("test@hohenheim.local")).first();
        return new UserPrincipal(admin.get(UserModel.ID), "Test Admin");
    }

    private static int template(String name) {
        Model templates = Models.get(InstanceTemplateModel.class);
        Row row = templates.createEmptyRow();
        row.set(InstanceTemplateModel.NAME, name);
        row.set(InstanceTemplateModel.DESCRIPTION, "surfaces fixture");
        row.set(InstanceTemplateModel.KIND, "hohenheim:docker_container");
        row.set(InstanceTemplateModel.VERSION, 1);
        templates.save(row);
        return row.get(InstanceTemplateModel.ID);
    }

    private static int instance(String name, int template) {
        Model instances = Models.get(InstanceModel.class);
        Row row = instances.createEmptyRow();
        row.set(InstanceModel.NAME, name);
        row.set(InstanceModel.KIND, "hohenheim:docker_container");
        row.set(InstanceModel.SETTINGS, new LinkedHashMap<>(
            Map.of("image", "alpine", "tag", "latest", "command", "sleep 300")));
        row.set(InstanceModel.STATUS, InstanceModel.STATUS_STOPPED);
        row.set(InstanceModel.TEMPLATE_ID, template);
        row.set(InstanceModel.INSTALL_STATE, InstanceModel.INSTALL_FAILED);
        instances.save(row);
        return row.get(InstanceModel.ID);
    }
}
