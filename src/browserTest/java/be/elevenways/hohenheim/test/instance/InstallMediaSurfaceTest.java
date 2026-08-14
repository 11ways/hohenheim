package be.elevenways.hohenheim.test.instance;

import be.elevenways.hohenheim.model.InstanceDeviceModel;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.InstanceTemplateModel;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.hohenheim.server.cms.ManageInstanceDeviceResource;
import be.elevenways.hohenheim.server.docker.ContainerHardening;
import be.elevenways.hohenheim.server.docker.OwnerLabels;
import be.elevenways.hohenheim.server.docker.ResourceLimits;
import be.elevenways.hohenheim.server.incus.IncusClient;
import be.elevenways.hohenheim.server.instance.InstanceDevices;
import be.elevenways.hohenheim.server.instance.InstanceKindHandler;
import be.elevenways.hohenheim.server.instance.InstanceKinds;
import be.elevenways.hohenheim.server.instance.InstanceService;
import be.elevenways.hohenheim.server.instance.InstanceTemplateCapture;
import be.elevenways.hohenheim.server.runtime.ContainerState;
import be.elevenways.hohenheim.server.runtime.DeviceAttachSupport;
import be.elevenways.hohenheim.server.runtime.Egress;
import be.elevenways.hohenheim.server.runtime.ImageOrigin;
import be.elevenways.hohenheim.server.runtime.ImagePublishSupport;
import be.elevenways.hohenheim.server.runtime.IncusInstanceRuntime;
import be.elevenways.hohenheim.server.runtime.IncusWorkloadType;
import be.elevenways.hohenheim.server.runtime.InstanceRuntime;
import be.elevenways.hohenheim.server.runtime.InstanceSpec;
import be.elevenways.hohenheim.server.runtime.InstanceStatus;
import be.elevenways.hohenheim.test.HohenheimTestBase;
import be.elevenways.hohenheim.test.TenantConduits;
import be.elevenways.hohenheim.test.host.HostFixtures;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.auth.model.UserModel;
import be.elevenways.zenit.auth.model.UserPrincipal;
import be.elevenways.zenit.auth.server.AuthModels;
import be.elevenways.zenit.auth.server.RecordGrants;
import be.elevenways.zenit.common.edit.submit.SubmittedValueCoercion;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.field.StringField;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.orm.model.Schema;
import be.elevenways.zenit.common.ui.Icon;
import be.elevenways.zenit.common.validation.Violations;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * The interactive-install wave: an {@code install_media} VM is created EMPTY, an
 * operator-published ISO attaches as a cdrom device with the learned boot-order
 * policy, the attach is OPERATOR-ONLY and charges no quota, the tenant form neither
 * offers nor accepts the type, and a stopped instance captures into an UNAPPROVED
 * prepared template. Driver truth rides {@link FakeIncusTransport}; funnel truth
 * rides an in-memory device-capable kind (the InstanceDeviceSurfaceTest shape).
 */
class InstallMediaSurfaceTest extends HohenheimTestBase {

    private static final String PREFIX = "media-surf-";

    /** handle -> the fake daemon's device state for the funnel journeys. */
    private static final Map<String, FakeMediaWorkload> DAEMON = new ConcurrentHashMap<>();

    private static Integer hostId;
    private static Integer tenantId;

    private final List<Integer> instances = new ArrayList<>();

    @BeforeAll
    static void seedFixtures() {
        FakeMediaKind.register();

        Row host = Models.get(ServerModel.class).createEmptyRow();
        host.set(ServerModel.NAME, PREFIX + "host");
        host.set(ServerModel.RUNTIME, ServerModel.RUNTIME_INCUS);
        host.set(ServerModel.MODE, ServerModel.MODE_LOCAL);
        host.set(ServerModel.ADMISSION, ServerModel.ADMISSION_ADMITTED);
        host.set(ServerModel.POSTURE, ServerModel.POSTURE_SHARED_CONTAINER);
        Models.get(ServerModel.class).save(host);
        HostFixtures.acknowledgePosture(host);
        hostId = host.get(ServerModel.ID);

        Row tenant = AuthModels.users().createEmptyRow();
        tenant.set(UserModel.EMAIL, "media-surf-tenant@surface.test");
        tenant.set(UserModel.DISPLAY_NAME, "Media Surface Tenant");
        tenant.set(UserModel.ENABLED, true);
        tenant.set(UserModel.CREATED_AT, Instant.now());
        tenant.set(UserModel.UPDATED_AT, Instant.now());
        AuthModels.users().save(tenant);
        tenantId = tenant.get(UserModel.ID);
    }

    @AfterAll
    static void removeHost() {
        if (hostId != null) {
            Models.get(ServerModel.class).delete(hostId);
            hostId = null;
        }
    }

    @AfterEach
    void cleanUp() {
        Model devices = Models.get(InstanceDeviceModel.class);
        for (Row row : devices.find()
                .where(InstanceDeviceModel.NAME.startsWith(PREFIX)).all()) {
            devices.delete(row.get(InstanceDeviceModel.ID));
        }
        for (Integer id : this.instances) {
            Models.get(InstanceModel.class).delete(id);
            DAEMON.remove(handleOf(id));
        }
        this.instances.clear();
        Model templates = Models.get(InstanceTemplateModel.class);
        for (Row row : templates.find()
                .where(InstanceTemplateModel.NAME.startsWith(PREFIX)).all()) {
            templates.delete(row.get(InstanceTemplateModel.ID));
        }
    }

    // -- driver truth ---------------------------------------------------------

    /**
     * The empty-VM origin: {@code source.type=none} for a VM, a NAMED refusal for a
     * container, and the blank-image gate stepping aside exactly for this origin.
     */
    @Test
    void installMediaOriginCreatesAnEmptyVmAndOnlyAVm() throws Exception {
        // 1. A VM spec with the install_media origin creates from source type=none:
        //    no alias, no protocol, no server -- there is no image anywhere.
        FakeIncusTransport daemon = new FakeIncusTransport();
        IncusInstanceRuntime vm = new IncusInstanceRuntime(new IncusClient(daemon),
            Egress.OPEN, IncusWorkloadType.VIRTUAL_MACHINE, null);
        vm.create(mediaSpec("media-surf-empty"));
        Map<String, Object> source = sourceOf(daemon);
        assertThat(source)
            .as("step 1: an install_media VM creates from source type=none")
            .containsEntry("type", "none")
            .doesNotContainKey("alias")
            .doesNotContainKey("protocol")
            .doesNotContainKey("server")
            .doesNotContainKey("fingerprint");

        // 2. A CONTAINER with the same origin is refused by name before any create:
        //    a shared-kernel workload has no firmware to boot media with.
        FakeIncusTransport containerDaemon = new FakeIncusTransport();
        IncusInstanceRuntime container = new IncusInstanceRuntime(
            new IncusClient(containerDaemon), Egress.OPEN, IncusWorkloadType.CONTAINER, null);
        assertThat(catchThrowable(() -> container.create(mediaSpec("media-surf-ct"))))
            .as("step 2: a container cannot declare install_media")
            .isInstanceOf(IOException.class)
            .hasMessageContaining("install_media");
        assertThat(containerDaemon.lastCreateBody)
            .as("step 2: and the refusal happened before any create reached the daemon")
            .isNull();

        // 3. resolve() allows a BLANK image only for this origin: a real incus_vm
        //    record with install_media resolves, the same record on catalog refuses.
        Row record = Models.get(InstanceModel.class).createEmptyRow();
        record.set(InstanceModel.NAME, PREFIX + "resolve");
        record.set(InstanceModel.KIND, "hohenheim:incus_vm");
        record.set(InstanceModel.SETTINGS,
            Map.of("image_origin", ImageOrigin.INSTALL_MEDIA.key()));
        record.set(InstanceModel.SERVER_ID, hostId);
        Models.get(InstanceModel.class).save(record);
        int recordId = record.get(InstanceModel.ID);
        this.instances.add(recordId);
        InstanceService.Resolved resolved = new InstanceService().resolve(recordId);
        assertThat(resolved.spec().image())
            .as("step 3: an install_media VM resolves with a blank image").isEmpty();
        assertThat(resolved.spec().imageOrigin())
            .as("step 3: and the spec carries the declared origin")
            .isEqualTo(ImageOrigin.INSTALL_MEDIA);

        record.set(InstanceModel.SETTINGS,
            Map.of("image_origin", ImageOrigin.CATALOG.key()));
        Models.get(InstanceModel.class).save(record);
        assertThat(catchThrowable(() -> new InstanceService().resolve(recordId)))
            .as("step 3: the SAME record on catalog keeps the blank-image refusal")
            .isInstanceOf(Violations.class)
            .hasMessageContaining("image");
    }

    /**
     * The cdrom device at the daemon: attaches only a present ISO volume, carries the
     * learned boot-order policy (root above media), and detaching never deletes the
     * shared medium.
     */
    @Test
    void cdromAttachesPresentIsoMediaWithRootAboveIt() throws Exception {
        FakeIncusTransport daemon = new FakeIncusTransport();
        IncusClient client = new IncusClient(daemon);
        IncusInstanceRuntime vm = new IncusInstanceRuntime(client, Egress.OPEN,
            IncusWorkloadType.VIRTUAL_MACHINE, null);
        InstanceSpec spec = mediaSpec("media-surf-cdrom");
        vm.create(spec);

        // 1. Absent media is refused by name, pointing the operator at the media surface.
        assertThat(catchThrowable(() -> vm.ensureCdrom(spec, "install", "win-iso")))
            .as("step 1: an absent ISO volume refuses by name")
            .isInstanceOf(IOException.class)
            .hasMessageContaining("win-iso")
            .hasMessageContaining("import the ISO");

        // 2. The ISO import lane places an iso-typed volume in the managed pool.
        java.nio.file.Path iso = java.nio.file.Files.createTempFile("media-surf", ".iso");
        java.nio.file.Files.writeString(iso, "fake-iso-bytes");
        try {
            client.importIsoVolume("default-pool", "win-iso", iso);
        } finally {
            java.nio.file.Files.deleteIfExists(iso);
        }
        assertThat(daemon.customVolumes.get("win-iso"))
            .as("step 2: the imported medium is an ISO volume on the managed pool")
            .containsEntry("content_type", "iso");

        // 3. A non-ISO volume is refused: a block volume in the drive is not media.
        Map<String, Object> block = new LinkedHashMap<>();
        block.put("name", "data-vol");
        client.createCustomVolume("default-pool", block);
        assertThat(catchThrowable(() -> vm.ensureCdrom(spec, "install", "data-vol")))
            .as("step 3: a non-iso volume is refused as install media")
            .isInstanceOf(IOException.class)
            .hasMessageContaining("not an ISO volume");

        // 4. The attach lands the cdrom WITH the boot-order policy: media at 5, the
        //    root disk raised ABOVE it at 10 (the prepare-windows-template finding).
        vm.ensureCdrom(spec, "install", "win-iso");
        Map<String, Object> devices = devicesOf(daemon, spec.handle());
        assertThat(devices).as("step 4: the cdrom device exists").containsKey("install");
        @SuppressWarnings("unchecked")
        Map<String, Object> cdrom = (Map<String, Object>) devices.get("install");
        assertThat(cdrom)
            .as("step 4: it references the ISO volume with the media boot priority")
            .containsEntry("source", "win-iso")
            .containsEntry("boot.priority", "5");
        @SuppressWarnings("unchecked")
        Map<String, Object> root = (Map<String, Object>) devices.get("root");
        assertThat(root)
            .as("step 4: and the ROOT disk holds the HIGHER priority, so a bootable"
                + " disk stops re-entering the installer")
            .containsEntry("boot.priority", "10");

        // 5. Detaching removes the DEVICE and keeps the shared medium: the volume is
        //    operator-published state other instances may still install from.
        vm.removeDevice(spec, "install", false);
        assertThat(devicesOf(daemon, spec.handle()))
            .as("step 5: the cdrom device is gone").doesNotContainKey("install");
        assertThat(daemon.customVolumes)
            .as("step 5: the ISO volume survives the detach").containsKey("win-iso");

        // 6. The container flavour refuses ensureCdrom outright.
        IncusInstanceRuntime container = new IncusInstanceRuntime(client, Egress.OPEN,
            IncusWorkloadType.CONTAINER, null);
        assertThat(catchThrowable(() -> container.ensureCdrom(spec, "install", "win-iso")))
            .as("step 6: only a virtual machine boots install media")
            .isInstanceOf(IOException.class)
            .hasMessageContaining("virtual machine");
    }

    /** Publishing captures ONLY a stopped workload, and registers the alias. */
    @Test
    void publishImageDemandsAStoppedWorkload() throws Exception {
        FakeIncusTransport daemon = new FakeIncusTransport();
        IncusInstanceRuntime vm = new IncusInstanceRuntime(new IncusClient(daemon),
            Egress.OPEN, IncusWorkloadType.VIRTUAL_MACHINE, null);
        InstanceSpec spec = mediaSpec("media-surf-publish");
        vm.create(spec);

        daemon.instanceStatus = "Running";
        assertThat(catchThrowable(() -> vm.publishImage(spec, "tpl-test", null)))
            .as("step 1: a running workload cannot be published (torn filesystem)")
            .isInstanceOf(IOException.class)
            .hasMessageContaining("STOPPED");

        daemon.instanceStatus = "Stopped";
        String fingerprint = vm.publishImage(spec, "tpl-test", "captured for the test");
        assertThat(daemon.lastPublishedAlias)
            .as("step 2: the publish carried the alias").isEqualTo("tpl-test");
        assertThat(fingerprint)
            .as("step 2: and the returned fingerprint is the alias's resolution")
            .isEqualTo("fp-published-tpl-test");
    }

    // -- funnel truth ---------------------------------------------------------

    /**
     * The cdrom attach through {@link InstanceDevices}: operator work lands at the
     * daemon and charges NO quota; a tenant delegate -- CONFIG included -- gets the
     * tier's uniform refusal and leaves no row.
     */
    @Test
    void cdromAttachIsOperatorOnlyAndChargesNothing() {
        int instanceId = mediaCapableInstance("media-surf-funnel");
        String handle = handleOf(instanceId);
        InstanceDevices devices = new InstanceDevices();

        // 1. Operator/system work attaches: row + daemon device, no quota stamp.
        devices.attachCdrom(instanceId, PREFIX + "install", "win-iso");
        Row row = deviceRows(instanceId).get(0);
        assertThat((String) row.get(InstanceDeviceModel.TYPE))
            .as("step 1: the desired-state row is a cdrom")
            .isEqualTo(InstanceDeviceModel.TYPE_CDROM);
        assertThat((String) row.get(InstanceDeviceModel.SOURCE_MEDIA))
            .as("step 1: naming its medium").isEqualTo("win-iso");
        assertThat((String) row.get(InstanceDeviceModel.QUOTA_BUCKET))
            .as("step 1: a cdrom charges NO quota bucket -- it references shared"
                + " operator media, it allocates nothing")
            .isNull();
        assertThat(DAEMON.get(handle).cdroms)
            .as("step 1: and the DAEMON holds the cdrom")
            .containsEntry(PREFIX + "install", "win-iso");

        // 2. A tenant holding CONFIG on the instance is refused with the tier's
        //    UNIFORM refusal (media provenance is arbitrary bootable code), and no
        //    second row appears.
        RecordGrants.grant("user", tenantId, InstanceModel.MODEL_ID, instanceId,
            HohenheimAccess.CONFIG, true);
        Throwable refused = catchThrowableInTenantScope(() ->
            devices.attachCdrom(instanceId, PREFIX + "tenant-cd", "win-iso"));
        assertThat(refused)
            .as("step 2: a CONFIG-holding tenant is refused")
            .isInstanceOf(Violations.class)
            .hasMessageContaining("instance_not_permitted");
        assertThat(deviceRows(instanceId))
            .as("step 2: and no row was left behind").hasSize(1);

        // 3. The POSITIVE anchor for the same tenant scope: a disk attach still lands,
        //    so step 2 measured the operator gate and not a broken tenant lane.
        TenantConduits.as(new UserPrincipal(tenantId, "Media Surface Tenant"), () ->
            devices.attachDisk(instanceId, PREFIX + "disk", 2));
        assertThat(DAEMON.get(handle).disks)
            .as("step 3: the same tenant's disk attach works")
            .containsEntry(PREFIX + "disk", 2);

        // 4. Detach removes the cdrom at the daemon and its row.
        devices.detach(instanceId, PREFIX + "install");
        assertThat(DAEMON.get(handle).cdroms)
            .as("step 4: the daemon cdrom is gone").isEmpty();
    }

    /** The tenant device form neither OFFERS cdrom nor ACCEPTS a hand-posted one. */
    @Test
    void theTenantDeviceFormNeitherOffersNorAcceptsCdrom() {
        int instanceId = mediaCapableInstance("media-surf-form");
        ManageInstanceDeviceResource resource = new ManageInstanceDeviceResource();

        Map<String, Object> submitted = new LinkedHashMap<>();
        submitted.put("instance_id", instanceId);
        submitted.put("type", InstanceDeviceModel.TYPE_CDROM);
        submitted.put("name", PREFIX + "sneak");
        assertThat(catchThrowable(() -> SubmittedValueCoercion
                .coerceFormOrThrow(resource.formSpec(), submitted)))
            .as("a hand-posted type=cdrom fails the tenant form's own coercion --"
                + " the select declares disk and nic only")
            .isInstanceOf(Violations.class);

        Map<String, Object> disk = new LinkedHashMap<>();
        disk.put("instance_id", instanceId);
        disk.put("type", InstanceDeviceModel.TYPE_DISK);
        disk.put("name", PREFIX + "ok");
        disk.put("size_gb", 1);
        Map<String, Object> coerced = SubmittedValueCoercion
            .coerceFormOrThrow(resource.formSpec(), disk);
        assertThat(coerced)
            .as("the positive anchor: a disk submit coerces through the same spec")
            .containsEntry("type", InstanceDeviceModel.TYPE_DISK);
    }

    // -- capture --------------------------------------------------------------

    /**
     * Template capture: a STOPPED instance publishes and mints an UNAPPROVED prepared
     * template; running and tenant-originated calls refuse; the record settles back.
     */
    @Test
    void captureMintsAnUnapprovedPreparedTemplate() {
        int instanceId = mediaCapableInstance("media-surf-capture");
        Row instance = Models.get(InstanceModel.class).findById(instanceId);

        // 1. A RUNNING record refuses by name before anything is stamped.
        stampStatus(instanceId, InstanceModel.STATUS_RUNNING);
        assertThat(catchThrowable(() -> new InstanceTemplateCapture().capture(instanceId)))
            .as("step 1: a running instance cannot be captured")
            .isInstanceOf(Violations.class)
            .hasMessageContaining("template_capture_requires_stopped");

        // 2. A tenant-originated call is refused with the tier's uniform refusal.
        stampStatus(instanceId, InstanceModel.STATUS_STOPPED);
        RecordGrants.grant("user", tenantId, InstanceModel.MODEL_ID, instanceId,
            HohenheimAccess.MANAGE, true);
        Throwable refused = catchThrowableInTenantScope(() ->
            new InstanceTemplateCapture().capture(instanceId));
        assertThat(refused)
            .as("step 2: capture is operator-only even for a manage holder")
            .isInstanceOf(Violations.class)
            .hasMessageContaining("instance_not_permitted");

        // 3. The operator capture publishes and mints the template.
        int templateId = new InstanceTemplateCapture().capture(instanceId);
        Row template = Models.get(InstanceTemplateModel.class).findById(templateId);
        assertThat(template).as("step 3: the template row exists").isNotNull();
        assertThat((Object) template.get(InstanceTemplateModel.APPROVED_AT))
            .as("step 3: it is UNAPPROVED -- capture and approval are two acts")
            .isNull();
        assertThat((Object) template.get(InstanceTemplateModel.KIND))
            .as("step 3: it keeps the instance's kind")
            .isEqualTo(instance.get(InstanceModel.KIND));
        Map<?, ?> settings = (Map<?, ?>) template.get(InstanceTemplateModel.SETTINGS);
        assertThat(String.valueOf(settings.get("image")))
            .as("step 3: the settings image is the published alias")
            .startsWith("tpl-media-surf-capture");
        assertThat(settings.get("image_origin"))
            .as("step 3: declared PREPARED -- resolved in the daemon's own store")
            .isEqualTo(ImageOrigin.PREPARED.key());
        assertThat(String.valueOf((Object) template.get(InstanceTemplateModel.SOURCE)))
            .as("step 3: the source records where it came from")
            .contains("captured from instance #" + instanceId);
        assertThat(FakeMediaRuntime.publishedAliases)
            .as("step 3: and the DAEMON really published that alias")
            .contains(String.valueOf(settings.get("image")));

        // 4. The record settled back to STOPPED, not stuck capturing.
        Row after = Models.get(InstanceModel.class).findById(instanceId);
        assertThat((String) after.get(InstanceModel.STATUS))
            .as("step 4: the record is back at stopped after the capture")
            .isEqualTo(InstanceModel.STATUS_STOPPED);
    }

    // -- the media surface ----------------------------------------------------

    /**
     * The Install media tab is INCUS-only and admin-panel-gated, its endpoints refuse
     * a non-admin, and deleting a referenced medium is refused BY NAME before any
     * daemon contact -- the reconcile would otherwise fail later, which is the worse
     * place to find out.
     */
    @Test
    void theServerMediaTabIsAdminAndIncusOnlyAndProtectsReferencedMedia() throws Exception {
        // 1. The tab renders for an admin on an INCUS host. The fixture daemon is
        //    unreachable, so the page states that by name instead of failing.
        var tab = adminGet("/admin/servers/" + hostId + "/page/install-media");
        assertThat(tab.statusCode())
            .as("step 1: the Install media tab renders on an incus host").isEqualTo(200);

        // 2. A DOCKER host does not offer the tab at all: its daemon has no ISO
        //    volume to hold (hide AND 404, the devices-tab shape).
        Row docker = Models.get(ServerModel.class).createEmptyRow();
        docker.set(ServerModel.NAME, PREFIX + "docker-host");
        docker.set(ServerModel.RUNTIME, ServerModel.RUNTIME_DOCKER);
        docker.set(ServerModel.MODE, ServerModel.MODE_LOCAL);
        Models.get(ServerModel.class).save(docker);
        Integer dockerId = docker.get(ServerModel.ID);
        try {
            assertThat(adminGet("/admin/servers/" + dockerId
                    + "/page/install-media").statusCode())
                .as("step 2: a docker host answers 404 for the media tab").isEqualTo(404);
        } finally {
            Models.get(ServerModel.class).delete(dockerId);
        }

        // 3. The fetch endpoint refuses a NON-admin outright (the endpoint declares
        //    the admin permission), so media provenance stays an operator act.
        TestSession tenant = sessionFor(tenantId);
        var refused = httpPostForm("/servers/" + hostId + "/media/fetch",
            "name=sneaky&url=https://example.test/x.iso", tenant.token(), tenant.csrf());
        assertThat(refused.statusCode())
            .as("step 3: a tenant cannot reach the media fetch endpoint")
            .isIn(401, 403, 404);

        // 4. An admin fetch with a NON-http URL is refused by the URL policy before
        //    any download or daemon contact, and lands back on the tab with the error.
        var badUrl = httpPostForm("/servers/" + hostId + "/media/fetch",
            "name=media-surf-iso&url=file:///etc/passwd", sessionToken, csrfToken);
        assertThat(badUrl.statusCode())
            .as("step 4: the refusal is a redirect back to the tab").isIn(302, 303);
        var flash = popFlash();
        assertThat(flash)
            .as("step 4: a refusal flash was stashed").isNotNull();
        assertThat(flash.message().key())
            .as("step 4: and it names the URL policy refusal")
            .isEqualTo("media_url_invalid");

        // 5. Deleting a medium a cdrom row still references is refused BY NAME,
        //    naming the instance, before any daemon contact.
        int instanceId = mediaCapableInstance("media-surf-holder");
        new InstanceDevices().attachCdrom(instanceId, PREFIX + "cd", "held-iso");
        var inUse = httpPostForm("/servers/" + hostId + "/media/delete",
            "name=held-iso", sessionToken, csrfToken);
        assertThat(inUse.statusCode())
            .as("step 5: the in-use refusal redirects back to the tab").isIn(302, 303);
        var inUseFlash = popFlash();
        assertThat(inUseFlash)
            .as("step 5: an in-use refusal flash was stashed").isNotNull();
        assertThat(inUseFlash.message().key())
            .as("step 5: and it is the named in-use refusal, decided from the rows"
                + " BEFORE any daemon contact")
            .isEqualTo("media_in_use");
    }

    // -- plumbing -------------------------------------------------------------

    /** Run the body inside a tenant request scope and return what it threw. */
    private Throwable catchThrowableInTenantScope(@NonNull Runnable body) {
        Throwable[] thrown = new Throwable[1];
        TenantConduits.as(new UserPrincipal(tenantId, "Media Surface Tenant"), () -> {
            thrown[0] = catchThrowable(body::run);
        });
        return thrown[0];
    }

    private static void stampStatus(int instanceId, @NonNull String status) {
        Row row = Models.get(InstanceModel.class).findById(instanceId);
        row.set(InstanceModel.STATUS, status);
        Models.get(InstanceModel.class).save(row);
    }

    private static String handleOf(int instanceId) {
        return "media-surf-instance-" + instanceId;
    }

    private int mediaCapableInstance(String name) {
        Row row = Models.get(InstanceModel.class).createEmptyRow();
        row.set(InstanceModel.NAME, name);
        row.set(InstanceModel.KIND, FakeMediaKind.ID.toString());
        row.set(InstanceModel.SETTINGS, Map.of("image", "fake/image"));
        row.set(InstanceModel.SERVER_ID, hostId);
        Models.get(InstanceModel.class).save(row);
        int id = row.get(InstanceModel.ID);
        this.instances.add(id);
        DAEMON.put(handleOf(id), new FakeMediaWorkload());
        return id;
    }

    private static List<Row> deviceRows(int instanceId) {
        return Models.get(InstanceDeviceModel.class).find()
            .where(InstanceDeviceModel.INSTANCE_ID.eq(instanceId)).all();
    }

    private static InstanceSpec mediaSpec(String handle) {
        return InstanceSpec.builder(handle, "",
                new ResourceLimits(1024, null),
                new ContainerHardening.Profile("incus-vm", List.of()),
                OwnerLabels.of(InstanceModel.MODEL_ID, 9377))
            .imageOrigin(ImageOrigin.INSTALL_MEDIA)
            .rootDiskGb(null)
            .build();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> sourceOf(FakeIncusTransport daemon) {
        return (Map<String, Object>) daemon.lastCreateBody.get("source");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> devicesOf(FakeIncusTransport daemon, String handle) {
        return (Map<String, Object>) daemon.instances.get(handle).get("devices");
    }

    // -- the in-memory media-capable kind -------------------------------------

    private static final class FakeMediaWorkload {
        final Map<String, Integer> disks = new LinkedHashMap<>();
        final Map<String, String> cdroms = new LinkedHashMap<>();
    }

    private static final class FakeMediaRuntime
            implements InstanceRuntime, DeviceAttachSupport, ImagePublishSupport {

        static final List<String> publishedAliases = new ArrayList<>();

        @Override
        public @NonNull String create(@NonNull InstanceSpec spec) {
            DAEMON.computeIfAbsent(spec.handle(), handle -> new FakeMediaWorkload());
            return spec.handle();
        }

        @Override public void start(@NonNull String handle) {}

        @Override public void stop(@NonNull String handle, int graceSeconds) {}

        @Override public void destroy(@NonNull String handle) { DAEMON.remove(handle); }

        @Override
        public @NonNull InstanceStatus status(@NonNull String handle) {
            return new InstanceStatus(DAEMON.containsKey(handle)
                ? ContainerState.RUNNING : ContainerState.ABSENT, null);
        }

        private @NonNull FakeMediaWorkload require(InstanceSpec spec) throws IOException {
            FakeMediaWorkload workload = DAEMON.get(spec.handle());
            if (workload == null) {
                throw new IOException("no workload " + spec.handle());
            }
            return workload;
        }

        @Override
        public void ensureDisk(@NonNull InstanceSpec spec, @NonNull String name, int sizeGb)
                throws IOException {
            require(spec).disks.put(name, sizeGb);
        }

        @Override
        public Integer diskSizeGb(@NonNull InstanceSpec spec, @NonNull String name)
                throws IOException {
            return require(spec).disks.get(name);
        }

        @Override
        public void resizeDisk(@NonNull InstanceSpec spec, @NonNull String name, int sizeGb)
                throws IOException {
            require(spec).disks.put(name, sizeGb);
        }

        @Override
        public void ensureNic(@NonNull InstanceSpec spec, @NonNull String name) {
        }

        @Override
        public void ensureCdrom(@NonNull InstanceSpec spec, @NonNull String name,
                                @NonNull String mediaVolume) throws IOException {
            require(spec).cdroms.put(name, mediaVolume);
        }

        @Override
        public void removeDevice(@NonNull InstanceSpec spec, @NonNull String name,
                                 boolean disk) throws IOException {
            FakeMediaWorkload workload = require(spec);
            workload.disks.remove(name);
            workload.cdroms.remove(name);
        }

        @Override
        public void deleteVolumes(@NonNull InstanceSpec spec, @NonNull List<String> names) {
        }

        @Override
        public @NonNull String publishImage(@NonNull InstanceSpec spec, @NonNull String alias,
                                            String description) {
            publishedAliases.add(alias);
            return "fp-published-" + alias;
        }
    }

    /** An incus-runtime kind whose runtime carries devices, media AND publish. */
    private static final class FakeMediaKind implements InstanceKindHandler {

        static final Identifier ID = Identifier.of("hohenheim", "fake_media_capable");
        static final Schema SETTINGS_SCHEMA = new Schema();
        static final StringField IMAGE = SETTINGS_SCHEMA.addField(
            StringField.builder().name("image").build());
        private static boolean registered;

        static void register() {
            if (!registered) {
                registered = true;
                InstanceKinds.register(new FakeMediaKind());
            }
        }

        @Override public @NonNull Identifier typeId() { return ID; }

        @Override public @NonNull String getDisplayName() { return "Fake media-capable"; }

        @Override
        public @NonNull Microcopy getLabel() {
            return Microcopy.of("fake_media_capable").withFilter("scope", "instance_kind");
        }

        @Override public String getDescription() { return "in-memory media-capable test kind"; }

        @Override public Icon getIcon() { return Icon.of("flask"); }

        @Override public String getColor() { return "gray"; }

        @Override public Schema getSchema() { return SETTINGS_SCHEMA; }

        @Override public @NonNull String requiredRuntime() { return ServerModel.RUNTIME_INCUS; }

        @Override public boolean supportsDevices() { return true; }

        @Override public boolean supportsInstallMedia() { return true; }

        @Override public boolean supportsTemplateCapture() { return true; }

        @Override
        public @NonNull InstanceRuntime runtimeFor(@NonNull String serverName) {
            return new FakeMediaRuntime();
        }

        @Override
        public @NonNull InstanceSpec specFor(int instanceId,
                                             @NonNull Map<String, Object> settings) {
            return InstanceSpec.builder("media-surf-instance-" + instanceId,
                    String.valueOf(settings.getOrDefault("image", "fake/image")),
                    new ResourceLimits(512, null),
                    new ContainerHardening.Profile("fake-media", List.of()),
                    OwnerLabels.of(InstanceModel.MODEL_ID, instanceId))
                .build();
        }

        @Override
        public int defaultFootprintMb(@NonNull Map<String, Object> settings) {
            return 512;
        }
    }
}
