package be.elevenways.hohenheim.test.instance;

import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.hohenheim.model.InstanceDeviceModel;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.server.cms.ManageInstanceDeviceResource;
import be.elevenways.hohenheim.server.docker.ContainerHardening;
import be.elevenways.hohenheim.server.docker.OwnerLabels;
import be.elevenways.hohenheim.server.docker.ResourceLimits;
import be.elevenways.hohenheim.server.instance.InstanceDeviceQuota;
import be.elevenways.hohenheim.server.instance.InstanceKindHandler;
import be.elevenways.hohenheim.server.instance.InstanceKinds;
import be.elevenways.hohenheim.server.runtime.ContainerState;
import be.elevenways.hohenheim.server.runtime.DeviceAttachSupport;
import be.elevenways.hohenheim.server.runtime.InstanceRuntime;
import be.elevenways.hohenheim.server.runtime.InstanceSpec;
import be.elevenways.hohenheim.server.runtime.InstanceStatus;
import be.elevenways.hohenheim.test.HohenheimTestBase;
import be.elevenways.hohenheim.test.TenantConduits;
import be.elevenways.hohenheim.test.host.HostFixtures;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.auth.CapabilityScopes;
import be.elevenways.zenit.auth.model.GrantSubjectType;
import be.elevenways.zenit.auth.model.UserModel;
import be.elevenways.zenit.auth.model.UserPrincipal;
import be.elevenways.zenit.auth.server.ApiKeyService;
import be.elevenways.zenit.auth.server.AuthCookieSupport;
import be.elevenways.zenit.auth.server.AuthModels;
import be.elevenways.zenit.auth.server.RecordGrants;
import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.common.orm.field.StringField;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.orm.model.Schema;
import be.elevenways.zenit.common.orm.quota.Quotas;
import be.elevenways.zenit.common.ui.Icon;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Instant;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.Set;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The operator surface over InstanceDevices -- the thing that did not exist. The
 * mechanism was complete, quota-guarded and live-proven while attachDisk / resizeDisk /
 * attachNic / detach had NO production caller: nothing a human or an API client could
 * reach wrote an {@code instance_devices} row.
 *
 * These journeys assert that the surface is the SAME FUNNEL and not a second one: the
 * form and the API both drive the daemon (not just the row), both refuse by the same
 * NAMED identity, and a refusal -- quota, capability or daemon -- leaves NO partial
 * device row and spends nothing in the reservation ledger.
 *
 * AIDEV-NOTE: the daemon is an in-memory DeviceAttachSupport runtime registered as a
 * fake kind (the InstanceMigrationTest shape). It exists so "did the volume actually get
 * created / resized / deleted" is an ASSERTION rather than an inference from a row.
 */
class InstanceDeviceSurfaceTest extends HohenheimTestBase {

    private static final String NAME_PREFIX = "devsurf-";

    /** handle -> volumes and nics the fake daemon holds. */
    private static final Map<String, FakeWorkload> DAEMON = new ConcurrentHashMap<>();

    /** Flipped to make the next daemon call refuse (the "row must not survive" lane). */
    private static volatile boolean daemonRefuses;

    /** A real delegated tenant plus its API key: the /api/v1 lane speaks keys only. */
    private static Integer tenantId;
    private static String tenantKey;

    /**
     * A SECOND delegate holding only {@code view} on the same instances, with a key whose
     * scopes deliberately include {@code config} its owner does not hold on any record --
     * so key narrowing can never be the thing that refuses and the RECORD-capability gate
     * is what the authorization journey measures.
     */
    private static Integer viewerId;
    private static String viewerKey;

    /** An admitted INCUS host, because the fake device kind declares that runtime. */
    private static Integer hostId;

    private final List<Integer> instances = new ArrayList<>();
    private Integer previousDiskCap;

    @BeforeAll
    static void registerKind() {
        FakeDeviceKind.register();

        Row host = Models.get(ServerModel.class).createEmptyRow();
        host.set(ServerModel.NAME, NAME_PREFIX + "host");
        host.set(ServerModel.RUNTIME, ServerModel.RUNTIME_INCUS);
        host.set(ServerModel.MODE, ServerModel.MODE_LOCAL);
        host.set(ServerModel.ADMISSION, ServerModel.ADMISSION_ADMITTED);
        host.set(ServerModel.POSTURE, ServerModel.POSTURE_SHARED_CONTAINER);
        Models.get(ServerModel.class).save(host);
        HostFixtures.acknowledgePosture(host);
        hostId = host.get(ServerModel.ID);

        Row user = AuthModels.users().createEmptyRow();
        user.set(UserModel.EMAIL, "devsurf-tenant@surface.test");
        user.set(UserModel.DISPLAY_NAME, "Device Surface Tenant");
        user.set(UserModel.ENABLED, true);
        user.set(UserModel.CREATED_AT, Instant.now());
        user.set(UserModel.UPDATED_AT, Instant.now());
        AuthModels.users().save(user);
        tenantId = user.get(UserModel.ID);
        tenantKey = ApiKeyService.create(tenantId, NAME_PREFIX + "key",
            List.of(CapabilityScopes.format(InstanceModel.MODEL_ID, HohenheimAccess.MANAGE)),
            null).plaintext();

        Row viewer = AuthModels.users().createEmptyRow();
        viewer.set(UserModel.EMAIL, "devsurf-viewer@surface.test");
        viewer.set(UserModel.DISPLAY_NAME, "Device Surface Viewer");
        viewer.set(UserModel.ENABLED, true);
        viewer.set(UserModel.CREATED_AT, Instant.now());
        viewer.set(UserModel.UPDATED_AT, Instant.now());
        AuthModels.users().save(viewer);
        viewerId = viewer.get(UserModel.ID);
        viewerKey = ApiKeyService.create(viewerId, NAME_PREFIX + "viewer-key",
            List.of(CapabilityScopes.format(InstanceModel.MODEL_ID, HohenheimAccess.VIEW),
                CapabilityScopes.format(InstanceModel.MODEL_ID, HohenheimAccess.CONFIG)),
            null).plaintext();
    }

    /** The shared fixture must not leave an admitted incus host behind: InstancePlacement
     *  walks every host row, so a stray one would silently change another class's answers. */
    @AfterAll
    static void removeHost() {
        if (hostId != null) {
            Models.get(ServerModel.class).delete(hostId);
            hostId = null;
        }
    }

    @AfterEach
    void cleanUp() {
        daemonRefuses = false;
        Model devices = Models.get(InstanceDeviceModel.class);
        for (Row row : devices.find()
                .where(InstanceDeviceModel.NAME.startsWith(NAME_PREFIX)).all()) {
            devices.delete(row.get(InstanceDeviceModel.ID));
        }
        for (Integer id : this.instances) {
            Models.get(InstanceModel.class).delete(id);
            DAEMON.remove(handleOf(id));
        }
        this.instances.clear();
        if (this.previousDiskCap != null) {
            HohenheimSettings.VALUES.setValue(HohenheimSettings.Quota.MAX_DISK_GB_PER_OWNER,
                this.previousDiskCap);
            this.previousDiskCap = null;
        }
    }

    private static String handleOf(int instanceId) {
        return "devsurf-instance-" + instanceId;
    }

    private int instanceRecord(String name, String kind) {
        Row row = Models.get(InstanceModel.class).createEmptyRow();
        row.set(InstanceModel.NAME, name);
        row.set(InstanceModel.KIND, kind);
        row.set(InstanceModel.SETTINGS, Map.of("image", "fake/image"));
        if (FakeDeviceKind.ID.toString().equals(kind)) {
            row.set(InstanceModel.SERVER_ID, hostId);
        }
        Models.get(InstanceModel.class).save(row);
        int id = row.get(InstanceModel.ID);
        this.instances.add(id);
        RecordGrants.grant(GrantSubjectType.USER, tenantId, InstanceModel.MODEL_ID, id,
            HohenheimAccess.MANAGE, true);
        return id;
    }

    /** An instance the tenant holds NOTHING on -- the no-existence-oracle lane. */
    private int ungrantedInstance(String name) {
        Row row = Models.get(InstanceModel.class).createEmptyRow();
        row.set(InstanceModel.NAME, name);
        row.set(InstanceModel.KIND, FakeDeviceKind.ID.toString());
        row.set(InstanceModel.SETTINGS, Map.of("image", "fake/image"));
        row.set(InstanceModel.SERVER_ID, hostId);
        Models.get(InstanceModel.class).save(row);
        int id = row.get(InstanceModel.ID);
        this.instances.add(id);
        return id;
    }

    /** A live workload on the fake daemon, so the attach lane is not the ABSENT one. */
    private int deviceCapableInstance(String name) {
        int id = instanceRecord(name, FakeDeviceKind.ID.toString());
        DAEMON.put(handleOf(id), new FakeWorkload());
        return id;
    }

    private static List<Row> deviceRows(int instanceId) {
        return Models.get(InstanceDeviceModel.class).find()
            .where(InstanceDeviceModel.INSTANCE_ID.eq(instanceId)).all();
    }

    /**
     * The whole point of the wave: an operator opens an instance, sees a Devices tab,
     * attaches a disk, grows it, and detaches it -- and every one of those acts lands at
     * the DAEMON, not merely in a row.
     */
    @Test
    void theDevicesTabAttachesResizesAndDetachesAtTheDaemon() throws Exception {
        int instanceId = deviceCapableInstance("devsurf-journey");
        String handle = handleOf(instanceId);
        String device = NAME_PREFIX + "data";

        // 1. The tab EXISTS and offers both attach affordances. Before this wave there
        //    was no page, no link and no route that could write a device row at all.
        HttpResponse<String> tab = get("/admin/instances/" + instanceId + "/page/devices");
        assertThat(tab.statusCode())
            .as("step 1: the Devices tab renders").isEqualTo(200);
        assertThat(tab.body())
            .as("step 1: and it offers attach-disk and attach-NIC, preset to this instance")
            .contains("/admin/instance-devices/new?type=disk&instance_id=" + instanceId)
            .contains("/admin/instance-devices/new?type=nic&instance_id=" + instanceId);

        // 2. Attaching a disk through the form creates the VOLUME at the daemon.
        HttpResponse<String> attached = postForm("/admin/instance-devices/new",
            "instance_id=" + instanceId + "&type=disk&name=" + device + "&size_gb=2");
        assertThat(attached.statusCode())
            .as("step 2: the attach form was accepted").isIn(200, 302, 303);
        assertThat(DAEMON.get(handle).disks.get(device))
            .as("step 2: the DAEMON holds a 2 GB volume -- a form that wrote only the row"
                + " would report success and attach nothing")
            .isEqualTo(2);
        Row row = deviceRows(instanceId).get(0);
        assertThat((Integer) row.get(InstanceDeviceModel.SIZE_GB))
            .as("step 2: and the desired-state row agrees").isEqualTo(2);

        // 3. Editing the size RESIZES the volume at the daemon.
        HttpResponse<String> resized = postForm(
            "/admin/instance-devices/" + row.get(InstanceDeviceModel.ID),
            "instance_id=" + instanceId + "&type=disk&name=" + device + "&size_gb=5");
        assertThat(resized.statusCode())
            .as("step 3: the resize form was accepted").isIn(200, 302, 303);
        assertThat(DAEMON.get(handle).disks.get(device))
            .as("step 3: the daemon volume grew to 5 GB").isEqualTo(5);

        // 4. Attaching an extra NIC is the same door.
        HttpResponse<String> nic = postForm("/admin/instance-devices/new",
            "instance_id=" + instanceId + "&type=nic&name=" + NAME_PREFIX + "net");
        assertThat(nic.statusCode())
            .as("step 4: the NIC attach was accepted").isIn(200, 302, 303);
        assertThat(DAEMON.get(handle).nics)
            .as("step 4: the daemon carries the extra NIC")
            .contains(NAME_PREFIX + "net");

        // 5. Deleting the row DETACHES at the daemon and DELETES the volume -- the
        //    reason this delete is typed-confirmed destructive rather than a row removal.
        HttpResponse<String> detached = postForm(
            "/admin/instance-devices/" + row.get(InstanceDeviceModel.ID) + "/delete", confirmed(""));
        assertThat(detached.statusCode())
            .as("step 5: the detach was accepted").isIn(200, 302, 303);
        assertThat(DAEMON.get(handle).disks)
            .as("step 5: the volume is GONE at the daemon, not merely unlisted")
            .doesNotContainKey(device);
        assertThat(deviceRows(instanceId))
            .as("step 5: and only the NIC row survives").hasSize(1);
    }

    /**
     * Every refusal lane the surface can hit, and the one property that matters for all
     * of them: NOTHING is left half-attached. A quota refusal, a capability refusal and
     * a daemon refusal each leave zero device rows and an untouched ledger.
     */
    @Test
    void everyRefusalIsNamedAndLeavesNoPartialDeviceRow() throws Exception {
        String bucket = InstanceDeviceQuota.diskBucketOf("");
        int instanceId = deviceCapableInstance("devsurf-refusals");
        String handle = handleOf(instanceId);
        long usedBefore = Quotas.usedOf(bucket);

        // 1. A driver WITHOUT DeviceAttachSupport refuses by name before anything is
        //    written. This is the assertion that proves the form goes through
        //    InstanceDevices at all: a plain row write would have succeeded here.
        int dockerId = instanceRecord("devsurf-docker", "hohenheim:docker_container");
        HttpResponse<String> unsupported = apiPost("/api/v1/instances/" + dockerId
            + "/devices", "type=disk&name=" + NAME_PREFIX + "x&size_gb=1");
        assertThat(unsupported.statusCode())
            .as("step 1: a device on a runtime that cannot carry one is refused")
            .isEqualTo(422);
        assertThat(unsupported.body())
            .as("step 1: BY NAME -- devices_unsupported, not a bare status")
            .contains("devices_unsupported");
        assertThat(deviceRows(dockerId))
            .as("step 1: and no row was written for a driver that could never honour it")
            .isEmpty();

        // 1b. The SURFACE must agree with that refusal instead of advertising against it.
        //     A Docker instance used to render the Devices tab plus Add-disk and Add-NIC
        //     buttons whose every POST could only ever answer devices_unsupported -- an
        //     affordance that can only refuse, which reads as a broken product rather than
        //     as a tier without the feature. The tab now hides AND 404s by kind, and this
        //     assertion sits beside the refusal on purpose: the DECLARATION
        //     (InstanceKindHandler.supportsDevices) and the ENFORCEMENT
        //     (InstanceDevices.requireSupport) are pinned as one answer, not two.
        HttpResponse<String> dockerTab =
            get("/admin/instances/" + dockerId + "/page/devices");
        assertThat(dockerTab.statusCode())
            .as("step 1b: the devices tab of a Docker instance does not exist")
            .isEqualTo(404);
        HttpResponse<String> dockerRecord = get("/admin/instances/" + dockerId);
        assertThat(dockerRecord.statusCode())
            .as("step 1b: while the record itself opens fine -- the tab is what is gone")
            .isEqualTo(200);
        assertThat(dockerRecord.body())
            .as("step 1b: and its tab strip offers no devices link either")
            .doesNotContain("/instances/" + dockerId + "/page/devices");

        // 2. Over quota: the named refusal, no row, nothing spent.
        this.previousDiskCap = HohenheimSettings.VALUES.getValue(
            HohenheimSettings.Quota.MAX_DISK_GB_PER_OWNER);
        HohenheimSettings.VALUES.setValue(HohenheimSettings.Quota.MAX_DISK_GB_PER_OWNER,
            (int) usedBefore + 1);
        HttpResponse<String> overQuota = apiPost("/api/v1/instances/" + instanceId
            + "/devices", "type=disk&name=" + NAME_PREFIX + "big&size_gb=9");
        assertThat(overQuota.statusCode())
            .as("step 2: an over-cap attach is refused").isEqualTo(422);
        assertThat(overQuota.body())
            .as("step 2: by the SAME named identity the ledger raises")
            .contains("disk_quota_reached");
        assertThat(deviceRows(instanceId))
            .as("step 2: a refused attach leaves NO device row").isEmpty();
        assertThat(Quotas.usedOf(bucket))
            .as("step 2: and spends nothing").isEqualTo(usedBefore);
        assertThat(DAEMON.get(handle).disks)
            .as("step 2: the daemon was never contacted").isEmpty();
        HohenheimSettings.VALUES.setValue(HohenheimSettings.Quota.MAX_DISK_GB_PER_OWNER,
            this.previousDiskCap);

        // 3. The DAEMON refuses after the row was written: the row must be reverted, or
        //    the ledger would count a disk that does not exist. This is the lane that
        //    cannot be checked by looking at a happy path.
        daemonRefuses = true;
        HttpResponse<String> daemonRefused = apiPost("/api/v1/instances/" + instanceId
            + "/devices", "type=disk&name=" + NAME_PREFIX + "ghost&size_gb=1");
        daemonRefuses = false;
        assertThat(daemonRefused.statusCode())
            .as("step 3: a daemon refusal is a refusal, not a success")
            .isEqualTo(422);
        assertThat(daemonRefused.body())
            .as("step 3: named device_attach_failed, carrying the daemon's own words")
            .contains("device_attach_failed");
        assertThat(deviceRows(instanceId))
            .as("step 3: the row written before the daemon call was REVERTED")
            .isEmpty();
        assertThat(Quotas.usedOf(bucket))
            .as("step 3: so the ledger does not count a disk the daemon never made")
            .isEqualTo(usedBefore);

        // 4. Resizing something that is not there, and a disk-only operation on a NIC,
        //    are each their own named refusal rather than a generic failure.
        HttpResponse<String> missing = apiPost("/api/v1/instances/" + instanceId
            + "/devices/resize", "name=" + NAME_PREFIX + "nope&size_gb=3");
        assertThat(missing.statusCode()).as("step 4: resizing an absent device refuses")
            .isEqualTo(422);
        assertThat(missing.body())
            .as("step 4: by name").contains("device_not_found");

        HttpResponse<String> unknownType = apiPost("/api/v1/instances/" + instanceId
            + "/devices", "type=gpu&name=" + NAME_PREFIX + "gpu");
        assertThat(unknownType.statusCode())
            .as("step 4: the device vocabulary is closed at disk and nic").isEqualTo(422);
        assertThat(unknownType.body())
            .as("step 4: and says which value it rejected")
            .contains("device_type_unknown");
        assertThat(deviceRows(instanceId))
            .as("step 4: none of the refusals left anything behind").isEmpty();
    }

    /**
     * The API lane is the same funnel as the panel: an automation client attaches,
     * lists, resizes and detaches, and the list projection carries no column that was
     * not enumerated.
     */
    @Test
    void theApiLaneDrivesTheSameDevicesAsThePanel() throws Exception {
        int instanceId = deviceCapableInstance("devsurf-api");
        String handle = handleOf(instanceId);
        String device = NAME_PREFIX + "apidisk";

        // 1. Attach through the API: the daemon carries it.
        HttpResponse<String> attach = apiPost("/api/v1/instances/" + instanceId
            + "/devices", "type=disk&name=" + device + "&size_gb=3");
        assertThat(attach.statusCode()).as("step 1: the API attach succeeded")
            .isEqualTo(200);
        assertThat(DAEMON.get(handle).disks.get(device))
            .as("step 1: with a real 3 GB volume at the daemon").isEqualTo(3);

        // 2. It reads back through the API, and the projection is a whitelist: the
        //    quota bucket a tenant must never see is absent BY NAME.
        HttpResponse<String> listed = apiGet("/api/v1/instances/" + instanceId + "/devices");
        assertThat(listed.statusCode()).as("step 2: the device list answers").isEqualTo(200);
        assertThat(listed.body())
            .as("step 2: naming the device and its size")
            .contains(device).contains("\"size_gb\"");
        assertThat(listed.body())
            .as("step 2: and never the internal ledger bucket")
            .doesNotContain("quota_bucket");

        // 3. Resize and detach through the API reach the daemon too.
        HttpResponse<String> resize = apiPost("/api/v1/instances/" + instanceId
            + "/devices/resize", "name=" + device + "&size_gb=6");
        assertThat(resize.statusCode()).as("step 3: the API resize succeeded").isEqualTo(200);
        assertThat(DAEMON.get(handle).disks.get(device))
            .as("step 3: the daemon volume is 6 GB").isEqualTo(6);

        HttpResponse<String> detach = apiPost("/api/v1/instances/" + instanceId
            + "/devices/detach", "name=" + device);
        assertThat(detach.statusCode()).as("step 4: the API detach succeeded").isEqualTo(200);
        assertThat(DAEMON.get(handle).disks)
            .as("step 4: and the volume is gone at the daemon").isEmpty();
        assertThat(deviceRows(instanceId))
            .as("step 4: with no row left over").isEmpty();

        // 5. An instance this tenant holds nothing on is INVISIBLE, not forbidden: the
        //    device lane inherits the no-existence-oracle rule the rest of /api/v1 keeps.
        int foreign = ungrantedInstance("devsurf-foreign");
        DAEMON.put(handleOf(foreign), new FakeWorkload());
        assertThat(apiGet("/api/v1/instances/" + foreign + "/devices").statusCode())
            .as("step 5: listing another tenant's devices reads as MISSING")
            .isEqualTo(404);
        HttpResponse<String> forced = apiPost("/api/v1/instances/" + foreign
            + "/devices", "type=disk&name=" + NAME_PREFIX + "steal&size_gb=1");
        assertThat(forced.statusCode())
            .as("step 5: and so does attaching to it").isEqualTo(404);
        assertThat(DAEMON.get(handleOf(foreign)).disks)
            .as("step 5: with nothing created on the foreign workload").isEmpty();
        assertThat(deviceRows(foreign))
            .as("step 5: and no row minted for it").isEmpty();
    }

    /**
     * AUTHORIZATION, which is the lane the refusal journey above does NOT cover: a
     * delegate who can SEE an instance but does not hold {@code config} cannot touch its
     * devices -- and the one that matters most is DETACH, which deletes a backing volume
     * at the daemon.
     *
     * AIDEV-NOTE: written 2026-08-13 because there was none, while
     * docs/proxmox-use-inventory.md claimed the surface test asserted "every refusal lane
     * (capability, quota, daemon)". Step 1 of
     * {@link #everyRefusalIsNamedAndLeavesNoPartialDeviceRow} is {@code devices_unsupported},
     * which is a DRIVER capability on a docker kind -- not an authorization decision -- so
     * {@code requireOperationCapability(instanceId, CONFIG)} at InstanceDevices :53/:89/
     * :129/:161 had zero coverage. Nothing was exploitable (the check is present at all
     * four mutators); this is regression risk, and the surface makes it real:
     * ManageInstanceDeviceResource scopes its READ predicate to {@code view}, so a
     * view-only delegate really is shown the device list and the edit route, and the
     * mutator gate is the only thing between it and a deleted volume.
     */
    @Test
    void aViewOnlyDelegateIsShownTheDevicesAndCanChangeNoneOfThem() throws Exception {
        int instanceId = deviceCapableInstance("devsurf-authz");
        String handle = handleOf(instanceId);
        String device = NAME_PREFIX + "authz-disk";

        // 1. The operator-tier holder attaches a real disk, so every refusal below has
        //    something to actually destroy.
        assertThat(apiPost("/api/v1/instances/" + instanceId + "/devices",
                "type=disk&name=" + device + "&size_gb=4").statusCode())
            .as("step 1: the fixture disk was attached").isEqualTo(200);
        assertThat(DAEMON.get(handle).disks.get(device))
            .as("step 1: and the daemon really holds it at 4 GB").isEqualTo(4);

        // 2. THE PREMISE: a VIEW-only delegate can SEE the instance and its devices.
        //    Without this the refusals below would be indistinguishable from the
        //    no-existence-oracle 404 the foreign-instance journey already pins.
        RecordGrants.grant(GrantSubjectType.USER, viewerId, InstanceModel.MODEL_ID, instanceId,
            HohenheimAccess.VIEW, true);
        HttpResponse<String> listed = keyGet(viewerKey,
            "/api/v1/instances/" + instanceId + "/devices");
        assertThat(listed.statusCode())
            .as("step 2: a view delegate really can list this instance's devices")
            .isEqualTo(200);
        assertThat(listed.body())
            .as("step 2: naming the very device it must not be able to remove")
            .contains(device);

        // 3. THE ATTACK, all four mutators. Each is refused by the CAPABILITY gate's own
        //    named violation -- not 404 (the record is visible), not devices_unsupported
        //    (the driver carries devices), not a quota key.
        record Attempt(String route, String body, String what) {}
        for (Attempt attempt : List.of(
                new Attempt("/devices", "type=disk&name=" + NAME_PREFIX + "steal&size_gb=1",
                    "attaching a disk"),
                new Attempt("/devices", "type=nic&name=" + NAME_PREFIX + "stealnic",
                    "attaching a NIC"),
                new Attempt("/devices/resize", "name=" + device + "&size_gb=99",
                    "resizing someone else's disk"),
                new Attempt("/devices/detach", "name=" + device,
                    "DETACHING it, which deletes the backing volume"))) {
            HttpResponse<String> refused = keyPost(viewerKey,
                "/api/v1/instances/" + instanceId + attempt.route(), attempt.body());
            assertThat(refused.statusCode())
                .as("step 3: " + attempt.what() + " is refused, typed")
                .isEqualTo(422);
            assertThat(refused.body())
                .as("step 3: " + attempt.what() + " is refused BY THE CAPABILITY GATE,"
                    + " named -- a bare 422 cannot tell config from quota or from a"
                    + " driver that has no devices")
                .contains("instance_not_permitted");
        }

        // 4. STATE, not just status: nothing moved. A refusal that already wrote the row
        //    or already spoke to the daemon would pass every assertion above.
        assertThat(DAEMON.get(handle).disks)
            .as("step 4: the daemon still holds exactly the original volume, unresized")
            .isEqualTo(Map.of(device, 4));
        assertThat(DAEMON.get(handle).nics)
            .as("step 4: and no NIC was attached").isEmpty();
        assertThat(deviceRows(instanceId))
            .as("step 4: with exactly the one row the operator created").hasSize(1);

        // 5. POSITIVE ANCHOR: the SAME detach through a holder of the capability lands,
        //    at the daemon. Without it, step 3 would pass on a device lane that refuses
        //    everyone -- which is precisely how an untested gate rots into a broken one.
        assertThat(apiPost("/api/v1/instances/" + instanceId + "/devices/detach",
                "name=" + device).statusCode())
            .as("step 5: the config holder's detach is accepted").isEqualTo(200);
        assertThat(DAEMON.get(handle).disks)
            .as("step 5: and the volume is gone at the daemon, so the gate refused"
                + " AUTHORITY in step 3 and nothing else")
            .isEmpty();
    }

    /**
     * The affordance half of the journey above: the /manage device surface must OFFER
     * edit and detach exactly to the principals whose POST it would accept.
     *
     * This is the other end of the same defect. Everything was already refused correctly,
     * but the list gated its synthesized edit link and its destructive detach button on
     * the TYPE-level update/delete permission, which is a NAME and therefore blind to
     * "does this principal hold CONFIG on THIS device's instance". A view-only delegate
     * was shown a detach button that could only 422. zenit-cms grew
     * {@code updatableBy}/{@code deletableBy} for it and this resource answers them with
     * the very capability {@code InstanceDevices} demands.
     */
    @Test
    void theDeviceSurfaceOffersWritesOnlyToPrincipalsWhoseWritesWouldLand() throws Exception {
        int instanceId = deviceCapableInstance("devsurf-affordance");
        String device = NAME_PREFIX + "affordance-disk";
        assertThat(apiPost("/api/v1/instances/" + instanceId + "/devices",
                "type=disk&name=" + device + "&size_gb=4").statusCode())
            .as("step 1: the fixture disk was attached").isEqualTo(200);
        Row row = deviceRows(instanceId).get(0);

        ManageInstanceDeviceResource resource = new ManageInstanceDeviceResource();
        AccessContext viewer = AccessContext.of(TenantConduits.stubFor(
            new UserPrincipal(viewerId, "Device Surface Viewer")));
        AccessContext operator = AccessContext.of(TenantConduits.stubFor(
            new UserPrincipal(tenantId, "Device Surface Tenant")));

        // 2. THE PREMISE, again: view-only really can SEE this device, so an absent
        //    affordance below is a WRITE decision and not the row being invisible.
        RecordGrants.grant(GrantSubjectType.USER, viewerId, InstanceModel.MODEL_ID, instanceId,
            HohenheimAccess.VIEW, true);
        assertThat(resource.accessFunction().decide(viewer).isDenied())
            .as("step 2: the view delegate's read scope is an allow, not a deny")
            .isFalse();
        assertThat(HohenheimAccess.hasInstanceCapability(viewer, instanceId,
                HohenheimAccess.VIEW))
            .as("step 2: and it really holds view on this instance").isTrue();

        // 3. The affordances are WITHHELD from it -- both of them, and the destructive
        //    one is the whole point: a detach button deletes a tenant's volume.
        assertThat(resource.updatableBy(row, viewer))
            .as("step 3: a view-only delegate is offered no edit affordance").isFalse();
        assertThat(resource.deletableBy(row, viewer))
            .as("step 3: nor a detach button that could only be refused").isFalse();

        // 4. And they are OFFERED to the config holder, so step 3 measured AUTHORITY and
        //    not a surface that refuses everyone -- the way an untested gate rots.
        RecordGrants.grant(GrantSubjectType.USER, tenantId, InstanceModel.MODEL_ID, instanceId,
            HohenheimAccess.CONFIG, true);
        assertThat(resource.updatableBy(row, operator))
            .as("step 4: a config holder keeps its edit affordance").isTrue();
        assertThat(resource.deletableBy(row, operator))
            .as("step 4: and its detach button").isTrue();

        // 5. Revoking the capability takes the affordances away again, so the answer
        //    tracks the live grant graph rather than anything cached at wiring time.
        RecordGrants.grant(GrantSubjectType.USER, tenantId, InstanceModel.MODEL_ID, instanceId,
            HohenheimAccess.CONFIG, false);
        AccessContext revoked = AccessContext.of(TenantConduits.stubFor(
            new UserPrincipal(tenantId, "Device Surface Tenant")));
        assertThat(resource.updatableBy(row, revoked))
            .as("step 5: a revoked capability withdraws the edit affordance").isFalse();
        assertThat(resource.deletableBy(row, revoked))
            .as("step 5: and the detach button").isFalse();
    }

    // -- transport --------------------------------------------------------------

    private HttpResponse<String> get(String path) throws Exception {
        return send(HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:" + getServerPort() + path))
            .header("Cookie", AuthCookieSupport.sessionCookieName() + "=" + sessionToken)
            .GET());
    }

    private HttpResponse<String> postForm(String path, String body) throws Exception {
        return send(HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:" + getServerPort() + path))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .header("Cookie", AuthCookieSupport.sessionCookieName() + "=" + sessionToken)
            .header("X-Csrf-Token", csrfToken)
            .POST(HttpRequest.BodyPublishers.ofString(body)));
    }

    /** The API lane authenticates by KEY only; this is a real delegated tenant's key. */
    private HttpResponse<String> apiGet(String path) throws Exception {
        return send(HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:" + getServerPort() + path))
            .header("X-Api-Key", tenantKey)
            .GET());
    }

    private HttpResponse<String> apiPost(String path, String body) throws Exception {
        return keyPost(tenantKey, path, body);
    }

    private static HttpResponse<String> send(HttpRequest.Builder builder) throws Exception {
        HttpClient client = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER).build();
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    // -- the in-memory device-capable runtime -----------------------------------

    private static final class FakeWorkload {
        final Map<String, Integer> disks = new LinkedHashMap<>();
        final List<String> nics = new ArrayList<>();
        final Map<String, String> cdroms = new LinkedHashMap<>();
    }

    private static final class FakeDeviceRuntime
            implements InstanceRuntime, DeviceAttachSupport {

        @Override
        public @NonNull String create(@NonNull InstanceSpec spec) {
            DAEMON.computeIfAbsent(spec.handle(), handle -> new FakeWorkload());
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

        private @NonNull FakeWorkload require(InstanceSpec spec) throws IOException {
            if (daemonRefuses) {
                throw new IOException("the fake daemon refuses this device");
            }
            FakeWorkload workload = DAEMON.get(spec.handle());
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
        public void ensureNic(@NonNull InstanceSpec spec, @NonNull String name)
                throws IOException {
            require(spec).nics.add(name);
        }

        @Override
        public void ensureCdrom(@NonNull InstanceSpec spec, @NonNull String name,
                                @NonNull String mediaVolume) throws IOException {
            require(spec).cdroms.put(name, mediaVolume);
        }

        @Override
        public void removeDevice(@NonNull InstanceSpec spec, @NonNull String name,
                                 boolean disk) throws IOException {
            FakeWorkload workload = require(spec);
            if (disk) {
                workload.disks.remove(name);
            } else {
                workload.nics.remove(name);
                workload.cdroms.remove(name);
            }
        }

        @Override
        public void deleteVolumes(@NonNull InstanceSpec spec, @NonNull List<String> names)
                throws IOException {
            FakeWorkload workload = require(spec);
            names.forEach(workload.disks::remove);
        }
    }

    /** An incus-runtime kind whose driver DOES carry devices, with no daemon behind it. */
    private static final class FakeDeviceKind implements InstanceKindHandler {

        static final Identifier ID = Identifier.of("hohenheim", "fake_device_capable");
        static final Schema SETTINGS_SCHEMA = new Schema();
        static final StringField IMAGE = SETTINGS_SCHEMA.addField(
            StringField.builder().name("image").build());
        private static boolean registered;

        static void register() {
            if (!registered) {
                registered = true;
                InstanceKinds.register(new FakeDeviceKind());
            }
        }

        @Override public @NonNull Identifier typeId() { return ID; }

        @Override public @NonNull String getDisplayName() { return "Fake device-capable"; }

        @Override
        public @NonNull Microcopy getLabel() {
            return Microcopy.of("fake_device_capable").withFilter("scope", "instance_kind");
        }

        @Override public @NonNull Microcopy getDescription() {
        return Microcopy.of("fake_device_capable").withFilter("scope", "instance_kind_description");
    }

        @Override public Icon getIcon() { return Icon.of("flask"); }

        @Override public String getColor() { return "gray"; }

        @Override public Schema getSchema() { return SETTINGS_SCHEMA; }

        @Override public @NonNull Set<String> supportedRuntimes() { return Set.of(ServerModel.RUNTIME_INCUS); }

        /** Its runtime really does implement DeviceAttachSupport, so it declares it. */
        @Override public boolean supportsDevices() { return true; }

        /** Cdrom journeys ride this kind too; the fake runtime honours ensureCdrom. */
        @Override public boolean supportsInstallMedia() { return true; }

        @Override
        public @NonNull InstanceRuntime runtimeFor(@NonNull String serverName) {
            return new FakeDeviceRuntime();
        }

        @Override
        public @NonNull InstanceSpec specFor(int instanceId,
                                             @NonNull Map<String, Object> settings) {
            return InstanceSpec.builder("devsurf-instance-" + instanceId,
                String.valueOf(settings.getOrDefault("image", "fake/image")),
                ResourceLimits.none(),
                new ContainerHardening.Profile("fake", List.of()),
                OwnerLabels.of(InstanceModel.MODEL_ID, instanceId)).build();
        }

        /** Test kinds declare a footprint like any other: the interface has no default. */
        @Override
        public int defaultFootprintMb(@NonNull Map<String, Object> settings) {
            return 128;
        }
    }
}
