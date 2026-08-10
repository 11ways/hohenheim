package be.elevenways.hohenheim.test.instance;

import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.server.docker.ContainerHardening;
import be.elevenways.hohenheim.server.docker.DockerClient;
import be.elevenways.hohenheim.server.docker.OwnerLabels;
import be.elevenways.hohenheim.server.docker.ResourceLimits;
import be.elevenways.hohenheim.server.incus.ControllerPresence;
import be.elevenways.hohenheim.server.incus.IncusClient;
import be.elevenways.hohenheim.server.instance.DockerContainerKind;
import be.elevenways.hohenheim.server.instance.InstanceDeviceQuota;
import be.elevenways.hohenheim.server.instance.IncusContainerKind;
import be.elevenways.hohenheim.server.instance.IncusVmKind;
import be.elevenways.hohenheim.server.instance.RootDisk;
import be.elevenways.hohenheim.server.runtime.DockerInstanceRuntime;
import be.elevenways.hohenheim.server.runtime.Egress;
import be.elevenways.hohenheim.server.runtime.ImageOrigin;
import be.elevenways.hohenheim.server.runtime.IncusInstanceRuntime;
import be.elevenways.hohenheim.server.runtime.IncusWorkloadType;
import be.elevenways.hohenheim.server.runtime.InstanceSpec;
import be.elevenways.hohenheim.server.security.NftRunner;
import be.elevenways.hohenheim.server.security.WorkloadNetworkPolicy;
import be.elevenways.hohenheim.test.HohenheimTestBase;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.orm.quota.Quotas;
import be.elevenways.zenit.common.validation.Violations;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * The root-disk knob, daemon-free: which tiers OFFER it, what the Incus driver puts on
 * the wire, which tiers refuse it BY NAME, and the charge into the owner disk-GB
 * bucket. The daemon-side proof that Incus really enforces the number lives in
 * {@code IncusVmLiveTest}; this class pins everything that does not need real iron.
 *
 * AIDEV-NOTE: every refusal assertion here is paired with a POSITIVE ANCHOR at the same
 * layer -- a refusal test with nothing that succeeds cannot tell "refuses the wrong
 * thing" from "refuses everything".
 */
class RootDiskSizeTest extends HohenheimTestBase {

    private static final String PREFIX = "rootdisk-";
    private static final String DISK_BUCKET = InstanceDeviceQuota.diskBucketOf("");

    private Integer previousDiskCap;

    @AfterEach
    void cleanUp() {
        Model instances = Models.get(InstanceModel.class);
        for (Row row : instances.find().withTrashed()
                .where(InstanceModel.NAME.startsWith(PREFIX)).all()) {
            instances.delete(row.get(InstanceModel.ID));
        }
        if (this.previousDiskCap != null) {
            HohenheimSettings.VALUES.setValue(HohenheimSettings.Quota.MAX_DISK_GB_PER_OWNER,
                this.previousDiskCap);
            this.previousDiskCap = null;
        }
    }

    @Test
    void onlyTiersThatCanEnforceARootQuotaOfferOne() throws Exception {
        // 1. The tiers whose driver really enforces a root quota DECLARE the field.
        //    Declaring it IS the capability declaration -- there is no second boolean
        //    that could disagree with it.
        assertThat(IncusVmKind.SETTINGS_SCHEMA.getField(RootDisk.SETTING))
            .as("step 1: the Incus VM kind offers a root disk size").isNotNull();
        assertThat(IncusContainerKind.SETTINGS_SCHEMA.getField(RootDisk.SETTING))
            .as("step 1: the Incus container kind offers one too").isNotNull();

        // 2. THE POINT OF THE ITEM: the Docker tier does NOT offer a number it would
        //    ignore. Measured on daystrom 2026-08-07: docker run --storage-opt size=2G
        //    on overlayfs/ext4 exits 0 and then accepts a 2.5GB write into the root.
        assertThat(new DockerContainerKind().getSchema().getField(RootDisk.SETTING))
            .as("step 2: the Docker kind offers NO root disk size -- it could not enforce it")
            .isNull();

        // 3. The Incus VM kind carries a declared size onto the spec the driver sees.
        InstanceSpec declared = new IncusVmKind().specFor(41, settings("alpine/3.22", 20));
        assertThat(declared.rootDiskGb())
            .as("step 3: the declared size reaches the driver").isEqualTo(20);
        InstanceSpec blank = new IncusVmKind().specFor(41, settings("alpine/3.22", null));
        assertThat(blank.rootDiskGb())
            .as("step 3: a blank declaration inherits the image, it does not become 0")
            .isNull();

        // 4. The Incus driver puts the root device in the CREATE body -- so the
        //    workload never exists at an unquotaed size -- on the default profile's
        //    own pool, beside the isolating NIC rather than instead of it.
        FakeIncusTransport daemon = new FakeIncusTransport();
        IncusInstanceRuntime incus = new IncusInstanceRuntime(new IncusClient(daemon),
            Egress.OPEN, IncusWorkloadType.VIRTUAL_MACHINE, null);
        incus.create(spec(PREFIX + "vm", 20));
        Map<String, Object> devices = devicesOf(daemon);
        assertThat(devices).as("step 4: the NIC override is still there").containsKey("eth0");
        assertThat(devices.get("root"))
            .as("step 4: a root disk device is in the create body")
            .isInstanceOfSatisfying(Map.class, root -> {
                assertThat(root.get("type")).as("step 4: it is a disk").isEqualTo("disk");
                assertThat(root.get("path")).as("step 4: mounted at /").isEqualTo("/");
                assertThat(root.get("pool"))
                    .as("step 4: on the default profile's own pool, never a guessed name")
                    .isEqualTo("default-pool");
                assertThat(root.get("size"))
                    .as("step 4: carrying the declared size").isEqualTo("20GiB");
            });

        // 4b. The deploy left this controller ATTRIBUTABLE on the shared daemon: the
        //     presence marker rides the same deploy that mints the isolation ACL, so an
        //     ephemeral controller's shared leftovers can later be judged departed
        //     instead of staying UNSTAMPED (never reaped) forever -- the daystrom
        //     91-ACL accumulation.
        assertThat(daemon.aclStore)
            .as("step 4b: a deploy stamps the controller presence marker beside the ACL")
            .containsKey(ControllerPresence.aclName());

        // 5. POSITIVE ANCHOR for step 6: an UNDECLARED root disk leaves the daemon's
        //    devices alone entirely, so step 6 cannot be passing because the driver
        //    writes a root device unconditionally.
        FakeIncusTransport plain = new FakeIncusTransport();
        new IncusInstanceRuntime(new IncusClient(plain), Egress.OPEN,
            IncusWorkloadType.VIRTUAL_MACHINE, null).create(spec(PREFIX + "plain", null));
        assertThat(devicesOf(plain))
            .as("step 5: no declaration, no root device -- the image default stands")
            .doesNotContainKey("root");

        // 6. The Docker driver REFUSES a spec that declares one, by name, naming the
        //    size and saying what would otherwise happen. It never reaches the daemon:
        //    this DockerClient has no reachable socket and the refusal throws first.
        // A network policy over an nft runner that would THROW if called: the refusal
        // must land before anything touches the host, and this proves it does.
        DockerInstanceRuntime docker = new DockerInstanceRuntime(new DockerClient(),
            new WorkloadNetworkPolicy((args, stdin) -> {
                throw new IllegalStateException("the refusal must not reach nft");
            }, () -> true));
        assertThat(catchThrowable(() -> docker.create(spec(PREFIX + "docker", 20))))
            .as("step 6: docker refuses a root-disk declaration BY NAME")
            .hasMessageContaining("cannot deliver the 20GB root disk")
            .hasMessageContaining("incus capability")
            .hasMessageContaining("accept the size and enforce nothing");
    }

    @Test
    void theRootDiskIsChargedToTheOwnerDiskCapLikeAnAttachedOne() {
        this.previousDiskCap = HohenheimSettings.VALUES.getValue(
            HohenheimSettings.Quota.MAX_DISK_GB_PER_OWNER);
        long usedBefore = Quotas.usedOf(DISK_BUCKET);

        // 1. Creating a VM with a 10 GB root charges the SAME bucket an attached disk
        //    would -- the root disk is not a hole in the owner's disk cap.
        Row row = instanceRow(PREFIX + "charged", "hohenheim:incus_vm", 10);
        Models.get(InstanceModel.class).save(row);
        int id = row.get(InstanceModel.ID);
        assertThat(Quotas.usedOf(DISK_BUCKET))
            .as("step 1: the root disk spends 10 GB of the owner's disk cap")
            .isEqualTo(usedBefore + 10);
        assertThat((String) Models.get(InstanceModel.class).findById(id)
                .get(InstanceModel.ROOT_DISK_BUCKET))
            .as("step 1: the charged bucket is stamped on the row")
            .isEqualTo(DISK_BUCKET);

        // 2. Growing the declaration charges only the DELTA.
        Row grown = Models.get(InstanceModel.class).findById(id);
        grown.set(InstanceModel.SETTINGS, settings("alpine/3.22", 14));
        Models.get(InstanceModel.class).save(grown);
        assertThat(Quotas.usedOf(DISK_BUCKET))
            .as("step 2: growing 10 -> 14 charges 4 more, not 14 more")
            .isEqualTo(usedBefore + 14);

        // 3. Shrinking the DECLARATION is refused at the write, not just at deploy:
        //    the volume stays 14 GB either way, so releasing the difference would let
        //    the owner's cap permit storage the host has already handed out.
        Row shrunk = Models.get(InstanceModel.class).findById(id);
        shrunk.set(InstanceModel.SETTINGS, settings("alpine/3.22", 6));
        assertThat(violationKeyOf(catchThrowable(() ->
            Models.get(InstanceModel.class).save(shrunk))))
            .as("step 3: a shrink is refused by name").isEqualTo("root_disk_shrink");
        Row cleared = Models.get(InstanceModel.class).findById(id);
        cleared.set(InstanceModel.SETTINGS, settings("alpine/3.22", null));
        assertThat(violationKeyOf(catchThrowable(() ->
            Models.get(InstanceModel.class).save(cleared))))
            .as("step 3: and so is CLEARING it -- a blank field frees no storage")
            .isEqualTo("root_disk_shrink");
        assertThat(Quotas.usedOf(DISK_BUCKET))
            .as("step 3: neither refusal moved the charge").isEqualTo(usedBefore + 14);

        // 4. An edit that carries no settings at all must not read as "cleared" and
        //    hand the whole charge back (the partial-CMS-update trap).
        Row renamed = Models.get(InstanceModel.class).createEmptyRow();
        renamed.set(InstanceModel.ID, id);
        renamed.set(InstanceModel.NAME, PREFIX + "charged-renamed");
        Models.get(InstanceModel.class).save(renamed);
        assertThat(Quotas.usedOf(DISK_BUCKET))
            .as("step 4: a settings-less edit leaves the charge exactly where it was")
            .isEqualTo(usedBefore + 14);

        // 5. Over the cap is the NAMED refusal, and the refused write spends nothing.
        HohenheimSettings.VALUES.setValue(HohenheimSettings.Quota.MAX_DISK_GB_PER_OWNER,
            (int) Quotas.usedOf(DISK_BUCKET) + 2);
        Row over = Models.get(InstanceModel.class).findById(id);
        over.set(InstanceModel.SETTINGS, settings("alpine/3.22", 40));
        assertThat(violationKeyOf(catchThrowable(() ->
            Models.get(InstanceModel.class).save(over))))
            .as("step 5: over-cap is the same named refusal an attached disk gets")
            .isEqualTo("disk_quota_reached");
        assertThat(Quotas.usedOf(DISK_BUCKET))
            .as("step 5: the refused write spent nothing").isEqualTo(usedBefore + 14);
        HohenheimSettings.VALUES.setValue(HohenheimSettings.Quota.MAX_DISK_GB_PER_OWNER,
            this.previousDiskCap == null ? 0 : this.previousDiskCap);

        // 6. The soft-delete transition hands the charge back -- InstanceService.destroy
        //    soft-deletes through save(), so the remove hooks never fire there.
        Models.get(InstanceModel.class).delete(id);
        assertThat(Quotas.usedOf(DISK_BUCKET))
            .as("step 6: destroying the instance released its root disk")
            .isEqualTo(usedBefore);
    }

    @Test
    void anUnusableOrUnenforceableDeclarationIsRefusedByName() {
        // 1. POSITIVE ANCHOR: a valid declaration on a tier that CAN enforce it is
        //    accepted, so the refusals below are refusing something specific.
        Row good = instanceRow(PREFIX + "ok", "hohenheim:incus_vm", 5);
        Models.get(InstanceModel.class).save(good);
        assertThat((Integer) good.get(InstanceModel.ID))
            .as("step 1: an enforceable declaration is accepted").isNotNull();

        // 2. Zero is not "inherit the image", it is a mistake, and it is named.
        Row zero = instanceRow(PREFIX + "zero", "hohenheim:incus_vm", 0);
        assertThat(violationKeyOf(catchThrowable(() ->
            Models.get(InstanceModel.class).save(zero))))
            .as("step 2: a non-positive size is refused by name")
            .isEqualTo("root_disk_invalid");

        // 3. So is a value that is not a number at all.
        Row bogus = instanceRow(PREFIX + "bogus", "hohenheim:incus_vm", null);
        Map<String, Object> settings = new LinkedHashMap<>(settings("alpine/3.22", null));
        settings.put(RootDisk.SETTING, "as big as possible");
        bogus.set(InstanceModel.SETTINGS, settings);
        assertThat(violationKeyOf(catchThrowable(() ->
            Models.get(InstanceModel.class).save(bogus))))
            .as("step 3: an unparseable size is refused by name")
            .isEqualTo("root_disk_invalid");

        // 4. THE ITEM'S CORE REFUSAL: a kind whose driver cannot enforce a root quota
        //    may not carry the number at all -- not even programmatically. Charging an
        //    owner for a limit nothing applies is the paper limit in ledger form.
        Row docker = instanceRow(PREFIX + "docker", "hohenheim:docker_container", 20);
        assertThat(violationKeyOf(catchThrowable(() ->
            Models.get(InstanceModel.class).save(docker))))
            .as("step 4: a tier that cannot enforce a root quota refuses the declaration")
            .isEqualTo("root_disk_unsupported");

        // 5. And the same Docker instance WITHOUT the declaration saves fine -- the
        //    refusal is about the root disk, not about docker instances.
        Row plainDocker = instanceRow(PREFIX + "docker-plain", "hohenheim:docker_container", null);
        Models.get(InstanceModel.class).save(plainDocker);
        assertThat((Integer) plainDocker.get(InstanceModel.ID))
            .as("step 5: a docker instance without a root disk is untouched by any of this")
            .isNotNull();
    }

    // -- helpers --------------------------------------------------------------

    private static Map<String, Object> settings(String image, Integer rootDiskGb) {
        Map<String, Object> settings = new LinkedHashMap<>();
        settings.put("image", image);
        if (rootDiskGb != null) {
            settings.put(RootDisk.SETTING, rootDiskGb);
        }
        return settings;
    }

    private static Row instanceRow(String name, String kind, Integer rootDiskGb) {
        Row row = Models.get(InstanceModel.class).createEmptyRow();
        row.set(InstanceModel.NAME, name);
        row.set(InstanceModel.KIND, kind);
        row.set(InstanceModel.SETTINGS, settings("alpine/3.22", rootDiskGb));
        return row;
    }

    private static InstanceSpec spec(String handle, Integer rootDiskGb) {
        return new InstanceSpec(handle, "alpine/3.22", null, Map.of(), Map.of(), null,
            ResourceLimits.none(), new ContainerHardening.Profile("fake", List.of()),
            OwnerLabels.of(InstanceModel.MODEL_ID, Math.abs(handle.hashCode())), null, null,
            ImageOrigin.CATALOG, false, true, Map.of(), rootDiskGb);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> devicesOf(FakeIncusTransport daemon) {
        assertThat(daemon.lastCreateBody).as("a create reached the daemon").isNotNull();
        return (Map<String, Object>) daemon.lastCreateBody.get("devices");
    }

    private static String violationKeyOf(Throwable thrown) {
        assertThat(thrown)
            .as("the write was refused with Violations (a write that SUCCEEDS here means"
                + " the root-disk gate let it through)")
            .isInstanceOf(Violations.class);
        return ((Violations) thrown).all().get(0).message().key();
    }
}
