package be.elevenways.hohenheim.test;

import be.elevenways.hohenheim.HohenheimIds;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.InstanceVolumeModel;
import be.elevenways.hohenheim.model.PreviewDeploymentModel;
import be.elevenways.hohenheim.model.ProjectModel;
import be.elevenways.hohenheim.model.RuntimeImageModel;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.hohenheim.server.cms.HohenheimPanel;
import be.elevenways.hohenheim.server.cms.VolumeParts;
import be.elevenways.hohenheim.server.cms.OperationHistoryParts;
import be.elevenways.hohenheim.server.cms.PreviewParts;
import be.elevenways.hohenheim.server.cms.ReconcileFindingParts;
import be.elevenways.hohenheim.server.cms.ServerParts;
import be.elevenways.hohenheim.server.cms.ProjectParts;
import be.elevenways.hohenheim.server.cms.RuntimeImageParts;
import be.elevenways.hohenheim.instance.VolumeOperations;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.hohenheim.server.project.Projects;
import be.elevenways.protoblast.common.time.Now;
import be.elevenways.zenit.auth.model.GrantSubjectType;
import be.elevenways.zenit.auth.model.UserModel;
import be.elevenways.zenit.auth.model.UserPrincipal;
import be.elevenways.zenit.auth.server.AuthModels;
import be.elevenways.zenit.auth.server.RecordGrants;
import be.elevenways.zenit.cms.test.support.PanelSurfaces;
import be.elevenways.zenit.cms.test.support.SurfaceCase;
import be.elevenways.zenit.cms.test.support.SurfaceBaselines;
import be.elevenways.zenit.cms.test.support.PanelSurfaceComparer;
import be.elevenways.zenit.cms.test.support.PlacedOperationMoves;
import be.elevenways.zenit.cms.test.support.SurfaceFact;
import be.elevenways.zenit.cms.common.action.CmsPlacementSurface;
import be.elevenways.zenit.common.ZenitIds;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.refusal.ZenitRefusalReason;
import be.elevenways.zenit.common.refusal.DomainRefusal;
import be.elevenways.zenit.common.validation.Violations;
import be.elevenways.zenit.server.operation.OperationPipeline;
import be.elevenways.zenit.server.operation.OperationRequest;
import be.elevenways.zenit.common.security.AccessContext;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.nio.charset.StandardCharsets;
import org.assertj.core.api.SoftAssertions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Immutable BEFORE offers for the B15 volume, host and project families, before O2 and any consumer conversion.
 * This is a page-reader capture; it starts neither a browser nor a host probe and invokes no admin action.
 *
 * @author Jelle De Loecker
 * @since 0.1.0
 */
class B15BeforeSurfacesTest {

    private static final String PREFIX = "b15-before-";
    private static final List<SurfaceCase> CASES = new ArrayList<>();
    private static AccessContext operatorAccess;

    @BeforeAll
    static void seed() throws Exception {
        TestDatabases.freshBootedDatasource();
        HohenheimTestBase.seedAuthenticatedAdmin();
        Row admin = AuthModels.users().find().where(UserModel.EMAIL.eq("test@hohenheim.local")).first();
        AccessContext operator = access(admin.get(UserModel.ID), "Test Admin");
        operatorAccess = operator;
        int tenantId = ApiSupport.user(PREFIX + "member@capture.test", "B15 Member");
        int outsiderId = ApiSupport.user(PREFIX + "outsider@capture.test", "B15 Outsider");
        AccessContext tenant = access(tenantId, "B15 Member");
        AccessContext outsider = access(outsiderId, "B15 Outsider");

        Model projects = Models.get(ProjectModel.class);
        Row project = projects.createEmptyRow();
        project.set(ProjectModel.NAME, PREFIX + "project");
        project.set(ProjectModel.DESCRIPTION, "B15 project surface");
        projects.save(project);
        Projects.addMember(project, tenantId);
        String projectKey = String.valueOf(project.get(ProjectModel.ID));

        int docker = host(PREFIX + "docker", ServerModel.RUNTIME_DOCKER);
        int incus = host(PREFIX + "incus", ServerModel.RUNTIME_INCUS);
        int inUse = host(PREFIX + "in-use", ServerModel.RUNTIME_DOCKER);
        int instance = instance(inUse);
        RecordGrants.grant(GrantSubjectType.USER, tenantId, InstanceModel.MODEL_ID, instance, HohenheimAccess.MANAGE, true);

        Model volumes = Models.get(InstanceVolumeModel.class);
        Row volume = volumes.createEmptyRow();
        volume.set(InstanceVolumeModel.INSTANCE_ID, instance);
        volume.set(InstanceVolumeModel.NAME, PREFIX + "data");
        volume.set(InstanceVolumeModel.CONTAINER_PATH, "/data");
        volume.set(InstanceVolumeModel.HOST_PATH, "/fixture/b15/data");
        volume.set(InstanceVolumeModel.QUOTA_BYTES, 64L * 1024L * 1024L);
        volumes.save(volume);

        Model images = Models.get(RuntimeImageModel.class);
        Row image = images.createEmptyRow();
        image.set(RuntimeImageModel.NAME, PREFIX + "runtime");
        image.set(RuntimeImageModel.DESCRIPTION, "B15 runtime surface");
        image.set(RuntimeImageModel.DOCKER_IMAGE, "fixture/b15:1");
        image.set(RuntimeImageModel.BUILD_CONTEXT, "images/node-22");
        images.save(image);
        Row builtin = images.find().where(RuntimeImageModel.BUILTIN.eq(true)).first();
        assertThat(builtin).as("a built-in runtime fixture exists before capture").isNotNull();

        Model previews = Models.get(PreviewDeploymentModel.class);
        Row preview = previews.createEmptyRow();
        preview.set(PreviewDeploymentModel.APPLICATION_ID, instance);
        preview.set(PreviewDeploymentModel.REF, "capture-ref");
        preview.set(PreviewDeploymentModel.HOSTNAME, "b15-preview.capture.test");
        preview.set(PreviewDeploymentModel.STATUS, PreviewDeploymentModel.STATUS_RUNNING);
        preview.set(PreviewDeploymentModel.EXPIRES_AT, Now.instant().plusSeconds(3600));
        previews.save(preview);

        List<String> adminEntries = List.of("servers", "projects", VolumeParts.SLUG,
            RuntimeImageParts.SLUG, PreviewParts.SLUG, ReconcileFindingParts.SLUG, OperationHistoryParts.BUILDS);
        for (String entry : adminEntries) {
            assertThat(HohenheimPanel.SLUG).isEqualTo("admin");
            CASES.add(SurfaceCase.of("admin", entry, "operator", operator));
            CASES.add(SurfaceCase.of("admin", entry, "tenant", tenant).refusedFor(ZenitRefusalReason.FORBIDDEN));
        }
        Row local = Models.get(ServerModel.class).findById(ServerModel.localServerId());
        onRecord("admin", "servers", "operator", operator, local.get(ServerModel.ID), "local");
        onRecord("admin", "servers", "operator", operator, docker, "docker");
        onRecord("admin", "servers", "operator", operator, incus, "incus");
        onRecord("admin", "servers", "operator", operator, inUse, "in-use");
        onRecord("admin", "projects", "operator", operator, projectKey, "project");
        onRecord("admin", VolumeParts.SLUG, "operator", operator,
            volume.get(InstanceVolumeModel.ID), "volume");
        onRecord("admin", RuntimeImageParts.SLUG, "operator", operator, image.get(RuntimeImageModel.ID), "custom");
        onRecord("admin", RuntimeImageParts.SLUG, "operator", operator, builtin.get(RuntimeImageModel.ID), "builtin");
        onRecord("admin", PreviewParts.SLUG, "operator", operator,
            preview.get(PreviewDeploymentModel.ID), "preview");

        for (String entry : List.of("projects", "project-members", PreviewParts.SLUG)) {
            CASES.add(SurfaceCase.of("manage", entry, "operator", operator));
            CASES.add(SurfaceCase.of("manage", entry, "member", tenant));
            CASES.add(SurfaceCase.of("manage", entry, "outsider", outsider).refusedFor(ZenitRefusalReason.FORBIDDEN));
        }
        onRecord("manage", "projects", "member", tenant, projectKey, "project");
        onRecord("manage", "project-members", "member", tenant, projectKey + ":user:" + tenantId, "membership");
        onRecord("manage", PreviewParts.SLUG, "member", tenant,
            preview.get(PreviewDeploymentModel.ID), "preview");
    }

    @Test
    void compareB15OffersAfterTheMove() throws Exception {
        StringBuilder text = new StringBuilder();
        Map<String, PanelSurfaces> before = new LinkedHashMap<>();
        try (var input = getClass().getResourceAsStream("/panel-surfaces/hohenheim-b15-before.txt")) {
            assertThat(input).as("immutable BEFORE checkpoint is present").isNotNull();
            for (PanelSurfaces capture : SurfaceBaselines.parseAll(new String(input.readAllBytes(), StandardCharsets.UTF_8))) {
                before.put(capture.caseName(), capture);
            }
        }
        SoftAssertions differences = new SoftAssertions();
        int captured = 0;
        // 1. Real admitted and refused requests, including record-specific delete and action states.
        for (SurfaceCase fixture : CASES) {
            PanelSurfaces capture = PanelSurfaces.capture(fixture);
            if (fixture.entrySlug().equals("project-members") && fixture.recordKey() != null) {
                // AIDEV-NOTE: the immutable companion BEFORE error records the missing legacy PK parser.
                // This is an intentional repair, not a claim that the formerly broken detail was identical.
                differences.assertThat(capture.text()).as("typed membership detail repairs the legacy parser defect")
                    .contains("record - found=true").contains("control EDIT%20project");
                text.append(capture.text()).append('\n');
                continue;
            }
            if (fixture.recordKey() != null) {
                assertThat(capture.text()).as("record fixture is loaded for " + fixture.name())
                    .contains("record - found=true");
            }
            PanelSurfaces stored = before.remove(fixture.name());
            assertThat(stored).as("BEFORE covers " + fixture.name()).isNotNull();
            PlacedOperationMoves moves = PlacedOperationMoves.NONE;
            if (fixture.recordKey() != null && fixture.entrySlug().equals(ServerParts.SLUG)) {
                var placed = ServerParts.admin().actions().stream().map(action -> action.id()).toList();
                moves = PlacedOperationMoves.of(stored.factsOf(SurfaceFact.Kind.ROW)
                    .stream().map(fact -> Identifier.tryParse(fact.name())).filter(placed::contains).toArray(Identifier[]::new))
                    .synthesized(ServerParts.SLUG, ZenitIds.id("delete"), ServerParts.DELETE.id())
                    // AIDEV-NOTE: intended difference, W1b (2026-10-05): Admit and Preflight became ONE check_host
                    // verb. It takes Admit's inline place exactly (band, style, offer state), and Preflight's overflow
                    // fact is the retired duplicate of it.
                    .split(HohenheimIds.id("admit_server"), Map.of(fixture.name(), HohenheimIds.id("check_host")))
                    .retired(ServerParts.SLUG, HohenheimIds.id("preflight_server"), HohenheimIds.id("check_host"));
            }
            if (fixture.recordKey() != null && fixture.entrySlug().equals("instance-volumes")) {
                moves = PlacedOperationMoves.of(VolumeOperations.DESTROY.id());
            } else if (fixture.recordKey() != null && fixture.panelSlug().equals("admin")
                    && fixture.entrySlug().equals("projects")) {
                moves = moves.synthesized(ProjectParts.SLUG, ZenitIds.id("delete"), ProjectParts.DELETE.id());
            } else if (fixture.recordKey() != null && fixture.entrySlug().equals("runtime-images")
                    && stored.text().contains("row zenit:delete")) {
                moves = moves.synthesized(RuntimeImageParts.SLUG, ZenitIds.id("delete"), RuntimeImageParts.DELETE.id());
            }
            PlacedOperationMoves correspondence = moves;
            LinkedHashSet<Identifier> applied = new LinkedHashSet<>();
            differences.assertThatCode(() -> PanelSurfaceComparer.assertSame(stored, capture, correspondence, applied))
                .as("exact B15 correspondence for " + fixture.name()).doesNotThrowAnyException();
            text.append(capture.text()).append('\n');
            captured++;
        }
        // 2. The complete AFTER artifact is separate; the committed BEFORE captures are never refreshed.
        Path output = Path.of("build", "panel-surfaces", "hohenheim-b15-after.txt");
        Files.createDirectories(output.getParent());
        Files.writeString(output, text);
        assertThat(CASES).hasSize(35);
        assertThat(captured).as("all formerly renderable cases are compared").isEqualTo(34);
        assertThat(before).as("every stored BEFORE case is compared").isEmpty();
        differences.assertAll();
    }

    @Test
    void reviewedVolumeEditsAndDeleteRefusalsShareTheDomainPipeline() {
        Model instances = Models.get(InstanceModel.class);
        Row owner = instances.createEmptyRow();
        owner.set(InstanceModel.NAME, PREFIX + "volume-journey");
        owner.set(InstanceModel.KIND, "hohenheim:application");
        owner.set(InstanceModel.SETTINGS, Map.of());
        owner.set(InstanceModel.STATUS, InstanceModel.STATUS_CREATED);
        owner.set(InstanceModel.SERVER_ID, ServerModel.localServerId());
        instances.save(owner);
        int volumeOwner = owner.get(InstanceModel.ID);
        // 1. Create through the form coercion and parent-capability gate, not a hand-written row insert.
        var created = OperationPipeline.invoke(OperationRequest.of(VolumeOperations.CREATE, CmsPlacementSurface.ADMIN_ACTION)
            .caller(operatorAccess).form(Map.of("instance_id", volumeOwner, "name", PREFIX + "journey",
                "container_path", "/journey", "quota_mb", 64, "exclusive", false)));
        Model volumes = Models.get(InstanceVolumeModel.class);
        Row row = volumes.findById(created.value());
        assertThat(row.get(InstanceVolumeModel.QUOTA_BYTES)).as("created declaration stores the MB quota as bytes")
            .isEqualTo(64L * 1024L * 1024L);
        int reviewed = row.get(InstanceVolumeModel.VERSION);
        String key = String.valueOf(created.value());

        // 2. Usage observations are not declaration edits and must not invalidate a reviewed form.
        volumes.find().where(InstanceVolumeModel.ID.eq(created.value()))
            .assign(InstanceVolumeModel.USED_BYTES, 17L)
            .assign(InstanceVolumeModel.OBSERVED_AT, Now.instant()).updateAll();
        assertThat(volumes.findById(created.value()).get(InstanceVolumeModel.VERSION))
            .as("bookkeeping leaves the declaration version alone").isEqualTo(reviewed);

        // 3. A partial operation update preserves immutable identity and the other form entries.
        OperationPipeline.invoke(OperationRequest.of(VolumeOperations.UPDATE, CmsPlacementSurface.ADMIN_ACTION)
            .caller(operatorAccess).subjectKeys(List.of(key)).expectedVersion(reviewed).patch(Map.of("quota_mb", 128)));
        Row changed = volumes.findById(created.value());
        assertThat(changed.get(InstanceVolumeModel.QUOTA_BYTES)).as("partial quota edit reaches the domain writer")
            .isEqualTo(128L * 1024L * 1024L);
        assertThat(changed.get(InstanceVolumeModel.CONTAINER_PATH)).as("partial edit keeps the mount path")
            .isEqualTo("/journey");
        assertThat(changed.get(InstanceVolumeModel.USED_BYTES)).as("declaration edit preserves usage bookkeeping")
            .isEqualTo(17L);

        // 4. Reusing the earlier review is stale at the service's actual guarded save, not a refreshed review.
        assertThatThrownBy(() -> OperationPipeline.invoke(OperationRequest.of(VolumeOperations.UPDATE,
                CmsPlacementSurface.ADMIN_ACTION).caller(operatorAccess).subjectKeys(List.of(key))
            .expectedVersion(reviewed).patch(Map.of("quota_mb", 256))))
            .as("stale declaration cannot overwrite the newer quota").isInstanceOf(DomainRefusal.class)
            .satisfies(error -> assertThat(((DomainRefusal) error).reason()).isEqualTo(ZenitRefusalReason.STALE));
        assertThat(volumes.findById(created.value()).get(InstanceVolumeModel.QUOTA_BYTES))
            .as("stale refusal preserves the winning edit").isEqualTo(128L * 1024L * 1024L);

        // 5. A fresh review still cannot rename the directory identity.
        assertThatThrownBy(() -> OperationPipeline.invoke(OperationRequest.of(VolumeOperations.UPDATE,
                CmsPlacementSurface.ADMIN_ACTION).caller(operatorAccess).subjectKeys(List.of(key))
            .expectedVersion(changed.get(InstanceVolumeModel.VERSION)).patch(Map.of("name", "renamed"))))
            .as("the operation keeps the domain's immutable volume name").isInstanceOf(Violations.class);

        // 6. O2's disabled local-host delete is also refused on direct invocation.
        assertThatThrownBy(() -> OperationPipeline.invoke(OperationRequest.of(ServerParts.DELETE,
                CmsPlacementSurface.ADMIN_ACTION).caller(operatorAccess)
            .subjectKeys(List.of(String.valueOf(ServerModel.localServerId())))))
            .as("local host delete availability is enforced before its handler").isInstanceOf(DomainRefusal.class);
        assertThat(Models.get(ServerModel.class).findById(ServerModel.localServerId()))
            .as("the refused operation leaves the local host intact").isNotNull();

        // 7. A code-owned runtime cannot bypass the catalog's hidden delete through the invoke route.
        Row builtin = Models.get(RuntimeImageModel.class).find().where(RuntimeImageModel.BUILTIN.eq(true)).first();
        assertThatThrownBy(() -> OperationPipeline.invoke(OperationRequest.of(RuntimeImageParts.DELETE,
                CmsPlacementSurface.ADMIN_ACTION).caller(operatorAccess)
            .subjectKeys(List.of(String.valueOf(builtin.get(RuntimeImageModel.ID))))))
            .as("built-in runtime applicability is enforced on direct invocation").isInstanceOf(DomainRefusal.class);
    }

    private static AccessContext access(int id, String name) {
        return AccessContext.of(TenantConduits.stubFor(new UserPrincipal(id, name)));
    }

    private static void onRecord(String panel, String entry, String principal, AccessContext access, Object id, String role) {
        CASES.add(SurfaceCase.of(panel, entry, principal, access).onRecord(String.valueOf(id), role));
    }

    private static int host(String name, String runtime) {
        Model hosts = Models.get(ServerModel.class);
        Row row = hosts.createEmptyRow();
        row.set(ServerModel.NAME, name);
        row.set(ServerModel.RUNTIME, runtime);
        row.set(ServerModel.MODE, ServerModel.MODE_SSH);
        row.set(ServerModel.SSH_TARGET, "operator@" + name + ".capture.test");
        if (ServerModel.RUNTIME_INCUS.equals(runtime)) {
            row.set(ServerModel.INCUS_URL, "https://" + name + ".capture.test:8443");
        }
        row.set(ServerModel.ADMISSION, ServerModel.ADMISSION_BLOCKED);
        row.set(ServerModel.POSTURE, ServerModel.POSTURE_SHARED_CONTAINER);
        hosts.save(row);
        return row.get(ServerModel.ID);
    }

    private static int instance(int serverId) {
        Model instances = Models.get(InstanceModel.class);
        Row row = instances.createEmptyRow();
        row.set(InstanceModel.NAME, PREFIX + "instance");
        row.set(InstanceModel.KIND, "hohenheim:docker_container");
        row.set(InstanceModel.SETTINGS, Map.of("image", "alpine", "tag", "latest", "command", "sleep 300"));
        row.set(InstanceModel.STATUS, InstanceModel.STATUS_CREATED);
        row.set(InstanceModel.SERVER_ID, serverId);
        instances.save(row);
        return row.get(InstanceModel.ID);
    }
}
