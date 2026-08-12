package be.elevenways.hohenheim.test.instance;

import be.elevenways.hohenheim.test.live.LiveLane;
import be.elevenways.zenit.common.orm.datasource.Datasources;
import be.elevenways.hohenheim.server.ControllerScope;
import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.hohenheim.model.InstanceDeviceModel;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.InstanceTemplateModel;
import be.elevenways.hohenheim.model.InstanceTemplateVariableModel;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.server.docker.OwnerLabels;
import be.elevenways.hohenheim.server.docker.ServerService;
import be.elevenways.hohenheim.server.incus.IncusClient;
import be.elevenways.hohenheim.server.incus.IncusKernelIsolation;
import be.elevenways.hohenheim.server.incus.IncusNetworkPolicy;
import be.elevenways.hohenheim.server.instance.InstanceDeviceQuota;
import be.elevenways.hohenheim.server.instance.InstanceDevices;
import be.elevenways.hohenheim.server.instance.InstanceService;
import be.elevenways.hohenheim.server.instance.InstanceTemplates;
import be.elevenways.hohenheim.server.instance.InstanceVariables;
import be.elevenways.hohenheim.server.runtime.ContainerState;
import be.elevenways.hohenheim.test.HohenheimTestRuntime;
import be.elevenways.hohenheim.test.host.LiveIncusHost;
import be.elevenways.zenit.common.orm.datasource.Db;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.orm.quota.Quotas;
import be.elevenways.zenit.common.validation.Violations;
import be.elevenways.zenit.server.orm.SqliteDatasource;
import be.elevenways.zenit.server.orm.migration.MigrationRunner;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.junit.jupiter.api.Tag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * Phase 8 slice 1 against the REAL remote Incus daemon: a Linux VM provisioned from
 * CLOUD-INIT through the template mechanism (typed variables, secret lane), deployed
 * through the SAME driver seam as containers, network policy enforced and proven in the
 * DAEMON'S KERNEL as well as its config -- with the boundary deliberately broken and
 * repaired in place, so the isolation assertions are known to be able to fail -- disk and
 * NIC attached and resized UNDER QUOTA, and destroyed with reclaim verified AT THE DAEMON.
 *
 * 512 MiB VM + 128 MiB peer container: the host's 3.9 GiB is the binding limit.
 */
@Tag("slow") // live lane: needs a real daemon/host/image; runs via `zenit-dev test --all`
class IncusVmLiveTest {

    // AIDEV-NOTE: the record name must be UNIQUE across live classes: the product's
    // authorized_keys comment is hohenheim-<name> (HostKeys.rotateIdentity), and
    // LiveIncusHost.authorizeKey sweeps by that comment -- a sibling fork enrolling
    // the same name deletes this fork's live key (observed 2026-08-10: this class
    // and IncusInstanceRuntimeLiveTest both used "live-incus").
    private static final String HOST = "live-incus-vm";

    /** Small cloud-variant VM image (cloud-init + incus agent); RAM rules out fatter ones. */
    private static final String VM_IMAGE = "alpine/3.22/cloud";

    /** The isolation peer: a plain system container on the same daemon. */
    private static final String PEER_IMAGE = "alpine/3.22";

    private static SqliteDatasource datasource;
    private static LiveIncusHost remote;
    private static String enrolledFingerprint;

    /**
     * AIDEV-NOTE: the ssh ADMIN lane is enrolled here on purpose, and it is not a
     * convenience. Without it {@link IncusKernelIsolation#available()} answers false for
     * an https daemon, so the driver's start-time check returns silently and
     * {@code VerifyIncusIsolation} reports the host unverifiable and repairs nothing --
     * the VM tier would run with its kernel-truth mechanism entirely INERT, which is what
     * this class did until 2026-08-06 and is why an unisolated VM could reach a tenant
     * peer with no layer noticing. A VM-tier host without this lane cannot back the Phase
     * 8 claim, so the test host carries the configuration the claim requires.
     */
    @BeforeAll
    static void setUp() throws Exception {
        remote = LiveIncusHost.configured();
        LiveLane.require(LiveLane.Need.INCUS_HOST, remote != null,
            "no live incus host enrolled at " + LiveIncusHost.CONFIG);

        File db = File.createTempFile("hohenheim-incus-vm-live", ".db");
        db.delete();
        db.deleteOnExit();
        datasource = new SqliteDatasource("jdbc:sqlite:" + db.getAbsolutePath());
        new MigrationRunner(datasource).migrate().requireSuccess();
        // ONE database per test class: the controller identity (and therefore every
        // daemon resource name) resolves through the CURRENT datasource, and a Db scope
        // is thread-local -- so a second, unregistered database would hand any
        // thread-hopping work a different controller's token than the records came from.
        Datasources.register(Datasources.DEFAULT, datasource);
        HohenheimTestRuntime.ensureBooted();

        Db.run(datasource, () -> {
            enrolledFingerprint = remote.enrollThroughProduct(HOST, "hohenheim-live-vm");
        });
    }

    /**
     * Give back both working credentials this class borrowed from a real machine, and
     * PRINT the outcome: a cleanup that fails silently leaves root access behind.
     */
    @AfterAll
    static void tearDown() {
        if (remote == null) {
            return;
        }
        System.out.println("=== cleanup: shared objects -> "
            + remote.releaseControllerSharedObjects());
        System.out.println("=== cleanup: authorized_keys -> "
            + remote.releaseAuthorizedKeys());
        if (enrolledFingerprint != null) {
            try {
                remote.removeTrustEntry(enrolledFingerprint);
            } catch (IOException ignored) {
                // nothing enrolled, nothing to remove
            }
        }
    }

    @Test
    void cloudInitVmJourneyWithQuotedDevicesAndVerifiedReclaim() {
        Db.run(datasource, () -> {
            Row host = Models.get(ServerModel.class).findByName(HOST);
            int hostId = host.get(ServerModel.ID);
            IncusClient incus = new ServerService().incusClientFor(HOST);
            InstanceService service = new InstanceService();
            InstanceDevices devices = new InstanceDevices(service);

            String diskBucket = InstanceDeviceQuota.diskBucketOf("");
            String nicBucket = InstanceDeviceQuota.nicBucketOf("");
            long diskUsedBefore = Quotas.usedOf(diskBucket);
            long nicUsedBefore = Quotas.usedOf(nicBucket);
            Integer previousDiskCap = HohenheimSettings.VALUES.getValue(
                HohenheimSettings.Quota.MAX_DISK_GB_PER_OWNER);
            Integer previousNicCap = HohenheimSettings.VALUES.getValue(
                HohenheimSettings.Quota.MAX_EXTRA_NICS_PER_OWNER);

            // 1. The TEMPLATE is the provisioning vocabulary: cloud-init user-data with
            //    a plain {{MARK}} and a generated secret {{VM_TOKEN}} placeholder, an
            //    approved operator catalog entry -- no second provisioning mechanism.
            int templateId = vmTemplate();
            Row template = Models.get(InstanceTemplateModel.class).findById(templateId);
            int id = new InstanceTemplates().createFromTemplate(template, "vm-journey",
                hostId, Map.of("MARK", "vm-live-proof"), null);
            String handle = ControllerScope.handle(ControllerScope.KIND_INSTANCE, id);
            String volumeName = handle + "-data";

            int peerId = peerRecord(hostId);
            String peerHandle = ControllerScope.handle(ControllerScope.KIND_INSTANCE, peerId);

            try {
                Map<String, String> variables = new InstanceVariables().valuesFor(id);
                String token = variables.get("VM_TOKEN");
                assertThat(token)
                    .as("step 1: the blank secret variable was generated").isNotBlank();

                // 2. Deploy through the product funnel: same seam, kind=vm.
                assertThat(service.deploy(id).state())
                    .as("step 2: deploy reports RUNNING").isEqualTo(ContainerState.RUNNING);
                Map<String, Object> definition = instanceOf(incus, handle);
                assertThat(String.valueOf(definition.get("type")))
                    .as("step 2: the daemon holds a VIRTUAL MACHINE, not a container")
                    .isEqualTo("virtual-machine");
                Map<?, ?> config = (Map<?, ?>) definition.get("config");
                assertThat(config.get("security.secureboot"))
                    .as("step 2: secure boot is declared off (unsigned images)")
                    .isEqualTo("false");
                assertThat(String.valueOf(config.get("cloud-init.user-data")))
                    .as("step 2: cloud-init user-data landed with {{MARK}} SUBSTITUTED")
                    .contains("vm-live-proof").doesNotContain("{{MARK}}");
                assertThat(String.valueOf(config.get("cloud-init.user-data")))
                    .as("step 2: and the secret variable substituted too")
                    .contains(token);
                assertThat(config.get("limits.memory"))
                    .as("step 2: the memory cap landed").isEqualTo("512MiB");
                OwnerLabels.of(InstanceModel.MODEL_ID, id).forEach((key, value) ->
                    assertThat(config.get("user." + key))
                        .as("step 2: owner label %s stamped at create", key)
                        .isEqualTo(value));
                assertThat(infoOf(handle))
                    .as("step 2: the host's own CLI sees it RUNNING").contains("RUNNING");

                // 3. Image identity: the record pins the RESOLVED fingerprint (the
                //    daemon's volatile.base_image), never the mutable alias.
                String daemonFingerprint = String.valueOf(config.get("volatile.base_image"));
                String pinned = Models.get(InstanceModel.class).findById(id)
                    .get(InstanceModel.IMAGE_FINGERPRINT);
                assertThat(pinned)
                    .as("step 3: the record pins the daemon's resolved fingerprint")
                    .isEqualTo(daemonFingerprint)
                    .matches("[0-9a-f]{64}");

                // 4. Network policy AT THE DAEMON: the NIC references the shared ACL,
                //    the ACL carries every tenant-range reject, and the kernel ruleset
                //    (nftables, incus's own table) materializes the metadata-range
                //    reject -- three independent layers of the same fact.
                Map<?, ?> nic = (Map<?, ?>) ((Map<?, ?>) definition.get("devices")).get("eth0");
                assertThat(nic.get("security.acls"))
                    .as("step 4: the VM's NIC carries the isolation ACL")
                    .isEqualTo(IncusNetworkPolicy.aclName());
                String acl = query("/1.0/network-acls/" + IncusNetworkPolicy.aclName());
                assertThat(acl)
                    .as("step 4: the daemon's ACL rejects the private+metadata ranges")
                    .contains("10.0.0.0/8").contains("169.254.0.0/16")
                    .contains("192.168.0.0/16").contains("fc00::/7");
                assertThat(hostCommand("nft", "list", "ruleset"))
                    .as("step 4: the kernel ruleset carries the metadata-range reject")
                    .contains("169.254.0.0/16");

                // 5. Cloud-init actually RAN inside the guest (agent exec, bounded wait
                //    for the agent + first boot; blank-at-t0 is the documented trap).
                // TRAP: cloud-init status exits 2 for done-with-warnings (this image
                // warns about unavailable ssh host key types), and the ssh helper
                // throws on any nonzero exit -- without the `|| true` the probe reads
                // "" forever while the daemon happily answers "status: done".
                awaitTrue("VM agent up and cloud-init finished", 600_000, () ->
                    execQuietly(handle, "cloud-init status || true").contains("done"));
                assertThat(exec(handle, "cat /root/hohenheim-mark"))
                    .as("step 5: write_files wrote the substituted plain variable")
                    .isEqualTo("vm-live-proof");
                assertThat(exec(handle, "cat /root/hohenheim-secret"))
                    .as("step 5: the generated secret landed via the secret lane")
                    .isEqualTo(token);
                assertThat(exec(handle, "ls /root/"))
                    .as("step 5: runcmd ran").contains("runcmd-ran");

                // 6. IPv4 egress proven with an ADDRESS LITERAL (never a hostname:
                //    Docker blackholes forwarded IPv4 while IPv6 works, so a hostname
                //    probe can lie).
                assertThat(exec(handle,
                    "wget -q -O- --timeout=8 http://1.1.1.1/ >/dev/null 2>&1"
                        + " && echo IPV4-OK || echo IPV4-DEAD"))
                    .as("step 6: the VM reaches the v4 internet by address literal")
                    .isEqualTo("IPV4-OK");

                // 7. ISOLATION, with the failing probe AND its positive anchor: deploy
                //    a peer container, wait for its v4 address, prove the HOST can ping
                //    it (the peer is really up) while the SAME probe from the VM FAILS
                //    (the ACL rejects the private ranges).
                assertThat(service.deploy(peerId).state())
                    .as("step 7: the peer container runs")
                    .isEqualTo(ContainerState.RUNNING);
                String[] peerIp = new String[1];
                awaitTrue("peer container IPv4 present", 60_000, () -> {
                    peerIp[0] = ipv4Of(incus, peerHandle);
                    return peerIp[0] != null;
                });
                assertThat(hostCommand("ping", "-c", "1", "-W", "2", peerIp[0]))
                    .as("step 7 anchor: the HOST reaches the peer at %s", peerIp[0])
                    .contains("1 received");
                assertThat(exec(handle, "ping -c 1 -W 2 " + peerIp[0]
                        + " >/dev/null 2>&1 && echo REACHED || echo ISOLATED"))
                    .as("step 7: the SAME probe from the VM is refused by the ACL")
                    .isEqualTo("ISOLATED");

                // 7b. The CONTROL-PLANE probe. The peer above proves tenant-to-tenant; the
                //     daemon host's own bridge address is what an unisolated tenant reaches
                //     the machine running the control plane through, and no second workload
                //     is involved in it at all.
                //
                //     AIDEV-NOTE: both probes measure the VM's OWN egress rules and nothing
                //     else, and that is a property of how this product configures the NIC,
                //     not of Incus. IncusNetworkPolicy.nicDevice sets
                //     security.acls.default.ingress.action=allow, so a peer's chain does NOT
                //     block the inbound leg -- verified on daystrom 2026-08-06, where a NIC
                //     left at the daemon's own default-deny ingress kept answering ISOLATED
                //     with the sender completely unfiltered, and the product-configured one
                //     answered REACHED. Never re-derive "the peer's chain protects it": if
                //     that ingress default ever changes, step 7d is what fails.
                String gateway = gatewayOf(incus, definition);
                assertThat(exec(handle, "ping -c 1 -W 2 " + gateway
                        + " >/dev/null 2>&1 && echo REACHED || echo ISOLATED"))
                    .as("step 7b: the VM cannot reach the daemon host at %s either", gateway)
                    .isEqualTo("ISOLATED");

                // 7c. KERNEL truth for the VM's LIVE tap, read through the product's own
                //     ssh lane -- the only layer that sees the upstream teardown race, in
                //     which incusd's postStop returns on a failed interface removal and
                //     never removes the filters, leaving chains that name a DEAD tap while
                //     the restarted VM's new tap matches nothing. The daemon's config and
                //     its ACL both read back perfectly in that state.
                IncusKernelIsolation kernel = IncusKernelIsolation.forServer(
                    Models.get(ServerModel.class).findByName(HOST));
                assertThat(kernel.available())
                    .as("step 7c: this host's kernel is readable, so the VM tier's"
                        + " isolation is verified and not merely configured")
                    .isTrue();
                assertThat(missingKernelRules(kernel, handle))
                    .as("step 7c: and the kernel carries every tenant-range block for the"
                        + " VM's live tap")
                    .isEmpty();

                // 7d. THE COUNTERFACTUAL, run in place: break the boundary exactly the way
                //     incusd breaks it, and require every probe above to NOTICE. An
                //     isolation assertion that has never been shown to fail is not a
                //     boundary test, and this is the one shape it must catch -- the
                //     kernel losing the rules while the daemon keeps reporting them.
                dropVmChains(handle);
                assertThat(exec(handle, "ping -c 1 -W 2 " + peerIp[0]
                        + " >/dev/null 2>&1 && echo REACHED || echo ISOLATED"))
                    .as("step 7d: step 7's own probe catches a real isolation loss")
                    .isEqualTo("REACHED");
                assertThat(exec(handle, "ping -c 1 -W 2 " + gateway
                        + " >/dev/null 2>&1 && echo REACHED || echo ISOLATED"))
                    .as("step 7d: and so does the control-plane probe")
                    .isEqualTo("REACHED");
                assertThat(missingKernelRules(kernel, handle))
                    .as("step 7d: and so does kernel truth, naming the live tap")
                    .isNotEmpty();
                assertThat(String.valueOf(instanceOf(incus, handle).get("devices")))
                    .as("step 7d: while the daemon's own config still claims isolation --"
                        + " the divergence this whole mechanism exists for")
                    .contains(IncusNetworkPolicy.aclName());

                // 7e. Repaired through the product's own per-instance lever, and the
                //     boundary is measurably back on both probes.
                enforceKernel(kernel, handle);
                assertThat(missingKernelRules(kernel, handle))
                    .as("step 7e: the repair restored the kernel rules").isEmpty();
                assertThat(exec(handle, "ping -c 1 -W 2 " + gateway
                        + " >/dev/null 2>&1 && echo REACHED || echo ISOLATED"))
                    .as("step 7e: and the VM is refused by the daemon host again")
                    .isEqualTo("ISOLATED");
                assertThat(exec(handle,
                    "wget -q -O- --timeout=8 http://1.1.1.1/ >/dev/null 2>&1"
                        + " && echo IPV4-OK || echo IPV4-DEAD"))
                    .as("step 7e: the repair did not sever the NIC")
                    .isEqualTo("IPV4-OK");

                // 8. DISK under quota: exactly 2 GB of operator headroom.
                HohenheimSettings.VALUES.setValue(
                    HohenheimSettings.Quota.MAX_DISK_GB_PER_OWNER,
                    (int) diskUsedBefore + 2);
                HohenheimSettings.VALUES.setValue(
                    HohenheimSettings.Quota.MAX_EXTRA_NICS_PER_OWNER,
                    (int) nicUsedBefore + 1);

                devices.attachDisk(id, "data", 1);
                assertThat(Quotas.usedOf(diskBucket))
                    .as("step 8: the attach charged 1 GB").isEqualTo(diskUsedBefore + 1);
                String volume = query("/1.0/storage-pools/default/volumes/custom/" + volumeName);
                assertThat(volume)
                    .as("step 8: the daemon holds the 1GiB block volume")
                    .contains("\"1GiB\"").contains("block");
                assertThat(instanceOf(incus, handle).get("devices").toString())
                    .as("step 8: the disk device is attached").contains("data");
                awaitTrue("guest sees the hotplugged disk", 30_000, () ->
                    execQuietly(handle, "ls /sys/block").contains("sdb"));

                // 9. Resize while RUNNING is the daemon's own refusal, surfaced by
                //    name, ledger and daemon both untouched.
                Throwable running = catchThrowable(() -> devices.resizeDisk(id, "data", 2));
                assertThat(running).isInstanceOfSatisfying(Violations.class, refused ->
                    assertThat(refused.all()).anySatisfy(violation -> {
                        assertThat(violation.message().key()).isEqualTo("device_resize_failed");
                        assertThat(String.valueOf(violation.message().args().get("reason")))
                            .as("step 9: and the reason is the daemon's own 'in use'")
                            .containsIgnoringCase("in use");
                    }));
                assertThat(Quotas.usedOf(diskBucket))
                    .as("step 9: the refused resize spent nothing")
                    .isEqualTo(diskUsedBefore + 1);
                assertThat(query("/1.0/storage-pools/default/volumes/custom/" + volumeName))
                    .as("step 9: the daemon volume is still 1GiB").contains("\"1GiB\"");

                // 10. Stopped, the resize lands: daemon size 2GiB, ledger 2 GB, and
                //     the next GB is the NAMED quota refusal.
                service.stop(id);
                devices.resizeDisk(id, "data", 2);
                assertThat(query("/1.0/storage-pools/default/volumes/custom/" + volumeName))
                    .as("step 10: the daemon really resized to 2GiB").contains("\"2GiB\"");
                assertThat(Quotas.usedOf(diskBucket))
                    .as("step 10: the ledger counts 2 GB").isEqualTo(diskUsedBefore + 2);
                Throwable overCap = catchThrowable(() -> devices.resizeDisk(id, "data", 3));
                assertThat(violationKeyOf(overCap))
                    .as("step 10: growing past the cap is the named quota refusal")
                    .isEqualTo("disk_quota_reached");
                Throwable secondDisk = catchThrowable(() -> devices.attachDisk(id, "more", 1));
                assertThat(violationKeyOf(secondDisk))
                    .as("step 10: a second disk past the cap is refused too")
                    .isEqualTo("disk_quota_reached");

                // 11. NIC under quota: the extra NIC lands on the managed secondary
                //     bridge WITH the ACL (read back), the second one is refused.
                devices.attachNic(id, "extra");
                Map<?, ?> extraNic = (Map<?, ?>) ((Map<?, ?>) instanceOf(incus, handle)
                    .get("devices")).get("extra");
                assertThat(extraNic)
                    .as("step 11: the extra NIC device exists at the daemon").isNotNull();
                assertThat(extraNic.get("security.acls"))
                    .as("step 11: and it carries the isolation ACL -- no hole")
                    .isEqualTo(IncusNetworkPolicy.aclName());
                assertThat(extraNic.get("network"))
                    .as("step 11: on the managed secondary bridge")
                    .isEqualTo(IncusNetworkPolicy.extraNetwork());
                assertThat(Quotas.usedOf(nicBucket))
                    .as("step 11: the NIC slot is counted").isEqualTo(nicUsedBefore + 1);
                Throwable secondNic = catchThrowable(() -> devices.attachNic(id, "extrb"));
                assertThat(violationKeyOf(secondNic))
                    .as("step 11: a second NIC past the cap is the named refusal")
                    .isEqualTo("nic_quota_reached");

                // 12. Redeploy CONVERGES: the rootfs, the devices and the pin survive,
                //     and the guest sees both NICs.
                assertThat(service.deploy(id).state())
                    .as("step 12: redeploy runs").isEqualTo(ContainerState.RUNNING);
                Map<String, Object> converged = instanceOf(incus, handle);
                assertThat(((Map<?, ?>) converged.get("devices")).keySet().toString())
                    .as("step 12: disk and NIC survived the converge")
                    .contains("data").contains("extra");
                assertThat((String) Models.get(InstanceModel.class).findById(id)
                        .get(InstanceModel.IMAGE_FINGERPRINT))
                    .as("step 12: the pin is unchanged").isEqualTo(pinned);
                awaitTrue("guest sees the second NIC", 600_000, () ->
                    execQuietly(handle, "ls /sys/class/net").contains("eth1"));

                // 13. DESTROY with verified reclaim AT THE DAEMON: instance absent,
                //     volume absent, device rows gone, every reservation released,
                //     record soft-deleted.
                service.destroy(id);
                assertThat(catchThrowable(() -> incus.instance(handle)))
                    .as("step 13: the VM is ABSENT at the daemon")
                    .isInstanceOfSatisfying(IncusClient.ApiException.class,
                        e -> assertThat(e.isNotFound()).isTrue());
                assertThat(query("/1.0/storage-pools/default/volumes/custom"))
                    .as("step 13: the data volume is gone from the pool")
                    .doesNotContain(volumeName);
                assertThat(Models.get(InstanceDeviceModel.class).find()
                        .where(InstanceDeviceModel.INSTANCE_ID.eq(id)).all())
                    .as("step 13: the device rows died with the record").isEmpty();
                assertThat(Quotas.usedOf(diskBucket))
                    .as("step 13: the disk reservation came back").isEqualTo(diskUsedBefore);
                assertThat(Quotas.usedOf(nicBucket))
                    .as("step 13: the NIC reservation came back").isEqualTo(nicUsedBefore);
                assertThat((Object) Models.get(InstanceModel.class).findById(id)
                        .get(InstanceModel.DELETED_AT))
                    .as("step 13: the record is soft-deleted, not erased").isNotNull();
                service.destroy(peerId);
                assertThat(catchThrowable(() -> incus.instance(peerHandle)))
                    .as("step 13: the peer is gone too")
                    .isInstanceOfSatisfying(IncusClient.ApiException.class,
                        e -> assertThat(e.isNotFound()).isTrue());
            } finally {
                HohenheimSettings.VALUES.setValue(
                    HohenheimSettings.Quota.MAX_DISK_GB_PER_OWNER,
                    previousDiskCap == null ? 0 : previousDiskCap);
                HohenheimSettings.VALUES.setValue(
                    HohenheimSettings.Quota.MAX_EXTRA_NICS_PER_OWNER,
                    previousNicCap == null ? 0 : previousNicCap);
                remote.forceDelete(handle);
                remote.forceDelete(peerHandle);
                cleanupDaemonLeftovers(incus, volumeName);
            }
        });
    }

    /**
     * The ROOT disk knob against real iron: the size the product declares is the size
     * the hypervisor gives the guest, growing works only while stopped, and shrinking is
     * refused. Its own instance rather than a step in the journey above, because that
     * journey pins the disk-GB cap to exactly its attached disk's headroom and a root
     * charge would move the arithmetic under it.
     *
     * AIDEV-NOTE: the assertion that matters is the GUEST'S OWN block device size, read
     * from /sys/block/sda/size inside the VM. The daemon's own read-backs are NOT
     * independent evidence here: measured 2026-08-07 on Incus 7.3 + btrfs, growing a
     * RUNNING VM's root device updates the config and does nothing, and from then on
     * GET /1.0/instances/x, its /state disk.root.total AND `storage volume info` all
     * echo the config value while the backing file stays at the old size. Everything the
     * API can tell you agrees with itself and is wrong; only the guest disagrees.
     */
    @Test
    void aDeclaredRootDiskIsTheSizeTheGuestActuallyGets() {
        Db.run(datasource, () -> {
            Row host = Models.get(ServerModel.class).findByName(HOST);
            int hostId = host.get(ServerModel.ID);
            IncusClient incus = new ServerService().incusClientFor(HOST);
            InstanceService service = new InstanceService();

            String diskBucket = InstanceDeviceQuota.diskBucketOf("");
            long diskUsedBefore = Quotas.usedOf(diskBucket);

            // 1. A VM record DECLARING a 6 GB root (the image's own volume is 4 GiB,
            //    which the daemon refuses to go under -- a real constraint, not ours).
            Row row = Models.get(InstanceModel.class).createEmptyRow();
            row.set(InstanceModel.NAME, "vm-root-disk");
            row.set(InstanceModel.KIND, "hohenheim:incus_vm");
            row.set(InstanceModel.SETTINGS, new LinkedHashMap<>(Map.of(
                "image", VM_IMAGE, "memory_limit_mb", 512, "root_disk_gb", 6)));
            row.set(InstanceModel.SERVER_ID, hostId);
            Models.get(InstanceModel.class).save(row);
            int id = row.get(InstanceModel.ID);
            String handle = ControllerScope.handle(ControllerScope.KIND_INSTANCE, id);

            assertThat(Quotas.usedOf(diskBucket))
                .as("step 1: the declared root disk charged the owner's disk cap")
                .isEqualTo(diskUsedBefore + 6);

            try {
                // 2. Deployed, the DAEMON holds a root device at the declared size.
                assertThat(service.deploy(id).state())
                    .as("step 2: the VM runs").isEqualTo(ContainerState.RUNNING);
                Map<?, ?> rootDevice = (Map<?, ?>) ((Map<?, ?>) instanceOf(incus, handle)
                    .get("devices")).get("root");
                assertThat(rootDevice)
                    .as("step 2: the daemon holds an explicit root device").isNotNull();
                assertThat(rootDevice.get("size"))
                    .as("step 2: at the declared size").isEqualTo("6GiB");

                // 3. THE ONLY INDEPENDENT CHECK: the GUEST's own block device is 6 GiB.
                //    /sys/block/sda/size is in 512-byte sectors.
                awaitTrue("VM agent up", 600_000, () ->
                    execQuietly(handle, "cat /sys/block/sda/size").trim().matches("\\d+"));
                assertThat(Long.parseLong(exec(handle, "cat /sys/block/sda/size").trim()) * 512L)
                    .as("step 3: the guest really sees a 6 GiB disk -- the hypervisor"
                        + " enforced it, not our bookkeeping")
                    .isEqualTo(6L * 1024 * 1024 * 1024);

                // 4. Growing while RUNNING is OUR refusal, by name, BEFORE the daemon
                //    gets a chance to accept it and do nothing (the measured trap).
                Row growing = Models.get(InstanceModel.class).findById(id);
                growing.set(InstanceModel.SETTINGS, new LinkedHashMap<>(Map.of(
                    "image", VM_IMAGE, "memory_limit_mb", 512, "root_disk_gb", 8)));
                Models.get(InstanceModel.class).save(growing);
                assertThat(catchThrowable(() -> service.deploy(id)))
                    .as("step 4: a running root grow is refused, naming the reason")
                    .isInstanceOfSatisfying(Violations.class, refused ->
                        assertThat(refused.all()).anySatisfy(violation -> {
                            assertThat(violation.message().key())
                                .isEqualTo("instance_deploy_failed");
                            assertThat(String.valueOf(violation.message().args().get("reason")))
                                .as("step 4: and the reason names the stopped-only rule")
                                .contains("STOPPED");
                        }));
                assertThat(Long.parseLong(exec(handle, "cat /sys/block/sda/size").trim()) * 512L)
                    .as("step 4: and the guest's disk is untouched by the refusal")
                    .isEqualTo(6L * 1024 * 1024 * 1024);

                // 5. Stopped, the SAME deploy lands -- and the guest sees the new size.
                service.stop(id);
                assertThat(service.deploy(id).state())
                    .as("step 5: the stopped grow deploys").isEqualTo(ContainerState.RUNNING);
                Map<?, ?> grownDevice = (Map<?, ?>) ((Map<?, ?>) instanceOf(incus, handle)
                    .get("devices")).get("root");
                assertThat(grownDevice.get("size"))
                    .as("step 5: the daemon declares 8GiB").isEqualTo("8GiB");
                awaitTrue("guest sees the grown disk", 600_000, () ->
                    execQuietly(handle, "cat /sys/block/sda/size").trim().matches("\\d+"));
                assertThat(Long.parseLong(exec(handle, "cat /sys/block/sda/size").trim()) * 512L)
                    .as("step 5: and the GUEST really got the extra 2 GiB")
                    .isEqualTo(8L * 1024 * 1024 * 1024);
                assertThat(Quotas.usedOf(diskBucket))
                    .as("step 5: the grow charged only the 2 GB delta")
                    .isEqualTo(diskUsedBefore + 8);

                // 6. Shrinking is refused at the WRITE, by name -- the storage the
                //    daemon already handed out cannot be given back, so neither can the
                //    reservation. The daemon is untouched and still holds 8GiB.
                service.stop(id);
                Row shrinking = Models.get(InstanceModel.class).findById(id);
                shrinking.set(InstanceModel.SETTINGS, new LinkedHashMap<>(Map.of(
                    "image", VM_IMAGE, "memory_limit_mb", 512, "root_disk_gb", 5)));
                assertThat(violationKeyOf(catchThrowable(() ->
                    Models.get(InstanceModel.class).save(shrinking))))
                    .as("step 6: a shrink is refused by name")
                    .isEqualTo("root_disk_shrink");
                assertThat(((Map<?, ?>) ((Map<?, ?>) instanceOf(incus, handle)
                        .get("devices")).get("root")).get("size"))
                    .as("step 6: and the daemon still holds 8GiB")
                    .isEqualTo("8GiB");
                assertThat(Quotas.usedOf(diskBucket))
                    .as("step 6: the refused shrink released nothing")
                    .isEqualTo(diskUsedBefore + 8);

                // 7. Destroy releases the root-disk reservation like any other.
                service.destroy(id);
                assertThat(Quotas.usedOf(diskBucket))
                    .as("step 7: destroying handed the root disk's 8 GB back")
                    .isEqualTo(diskUsedBefore);
            } finally {
                remote.forceDelete(handle);
            }
        });
    }

    // -- fixtures -------------------------------------------------------------

    private static int vmTemplate() {
        Map<String, Object> settings = new LinkedHashMap<>();
        settings.put("image", VM_IMAGE);
        settings.put("memory_limit_mb", 512);
        settings.put("cloud_init", """
            #cloud-config
            write_files:
              - path: /root/hohenheim-mark
                content: "{{MARK}}"
              - path: /root/hohenheim-secret
                content: "{{VM_TOKEN}}"
                permissions: "0600"
            runcmd:
              - touch /root/runcmd-ran
            """);

        Row template = Models.get(InstanceTemplateModel.class).createEmptyRow();
        template.set(InstanceTemplateModel.NAME, "vm-live-fixture");
        template.set(InstanceTemplateModel.KIND, "hohenheim:incus_vm");
        template.set(InstanceTemplateModel.SETTINGS, settings);
        template.set(InstanceTemplateModel.REINSTALL_POLICY,
            InstanceTemplateModel.REINSTALL_PRESERVE);
        template.set(InstanceTemplateModel.APPROVED_AT, Instant.now());
        Models.get(InstanceTemplateModel.class).save(template);
        int templateId = template.get(InstanceTemplateModel.ID);

        Row mark = Models.get(InstanceTemplateVariableModel.class).createEmptyRow();
        mark.set(InstanceTemplateVariableModel.TEMPLATE_ID, templateId);
        mark.set(InstanceTemplateVariableModel.KEY, "MARK");
        mark.set(InstanceTemplateVariableModel.TYPE, "hohenheim:string");
        mark.set(InstanceTemplateVariableModel.REQUIRED, true);
        mark.set(InstanceTemplateVariableModel.SETTINGS, Map.of());
        Models.get(InstanceTemplateVariableModel.class).save(mark);

        Row token = Models.get(InstanceTemplateVariableModel.class).createEmptyRow();
        token.set(InstanceTemplateVariableModel.TEMPLATE_ID, templateId);
        token.set(InstanceTemplateVariableModel.KEY, "VM_TOKEN");
        token.set(InstanceTemplateVariableModel.TYPE, "hohenheim:secret");
        token.set(InstanceTemplateVariableModel.SETTINGS,
            Map.of("generate", true, "generate_bytes", 18));
        Models.get(InstanceTemplateVariableModel.class).save(token);
        return templateId;
    }

    private static int peerRecord(int hostId) {
        Row row = Models.get(InstanceModel.class).createEmptyRow();
        row.set(InstanceModel.NAME, "vm-isolation-peer");
        row.set(InstanceModel.KIND, "hohenheim:incus_container");
        row.set(InstanceModel.SETTINGS, new LinkedHashMap<>(
            Map.of("image", PEER_IMAGE, "memory_limit_mb", 128)));
        row.set(InstanceModel.SERVER_ID, hostId);
        Models.get(InstanceModel.class).save(row);
        return row.get(InstanceModel.ID);
    }

    // -- plumbing -------------------------------------------------------------

    private static String violationKeyOf(Throwable thrown) {
        assertThat(thrown).isInstanceOf(Violations.class);
        return ((Violations) thrown).all().get(0).message().key();
    }

    /** Bounded poll: never assert a fresh workload's state with zero retry. */
    private static void awaitTrue(String what, long timeoutMs, Supplier<Boolean> probe) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (Boolean.TRUE.equals(probe.get())) {
                return;
            }
            try {
                Thread.sleep(2000);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        throw new AssertionError("timed out after " + timeoutMs + "ms waiting for: " + what);
    }

    private static String exec(String handle, String command) {
        try {
            return remote.exec(handle, command);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    /** Exec that answers "" instead of throwing (poll probes while the agent is down). */
    private static String execQuietly(String handle, String command) {
        try {
            return remote.exec(handle, command);
        } catch (IOException notReady) {
            return "";
        }
    }

    private static String hostCommand(String... command) {
        try {
            return remote.hostCommand(command);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static String query(String path) {
        try {
            return remote.query(path);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static String infoOf(String handle) {
        try {
            return remote.instanceInfoOrError(handle);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static Map<String, Object> instanceOf(IncusClient incus, String handle) {
        try {
            return incus.instance(handle);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * The daemon bridge's OWN address, derived from the NIC's declared network -- the
     * one-sided probe target. Never a hardcoded subnet: the managed bridge's range is
     * auto-assigned by the daemon and differs per host.
     */
    private static String gatewayOf(IncusClient incus, Map<String, Object> definition) {
        Map<?, ?> nic = (Map<?, ?>) ((Map<?, ?>) definition.get("devices")).get("eth0");
        String network = String.valueOf(nic.get("network"));
        Map<String, Object> bridge;
        try {
            bridge = incus.network(network);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        assertThat(bridge).as("the daemon knows the NIC's network '%s'", network).isNotNull();
        String cidr = String.valueOf(((Map<?, ?>) bridge.get("config")).get("ipv4.address"));
        assertThat(cidr).as("bridge '%s' has an IPv4 subnet", network).contains("/");
        return cidr.substring(0, cidr.indexOf('/'));
    }

    /** Kernel truth for one workload, or the verifier's own refusal as a failure. */
    private static List<String> missingKernelRules(IncusKernelIsolation kernel, String handle) {
        try {
            return kernel.inspect(handle).missing();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    /** The product's verify-repair-reverify lever; a refusal fails the journey. */
    private static void enforceKernel(IncusKernelIsolation kernel, String handle) {
        try {
            kernel.enforce(handle);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Break the boundary the way incusd breaks it: remove the instance's bridge-filter
     * chains while it runs, leaving every daemon-side fact intact.
     */
    private static void dropVmChains(String handle) {
        for (String hook : List.of("in", "fwd", "out")) {
            try {
                remote.hostCommand("nft", "delete", "chain", "bridge",
                    IncusKernelIsolation.TABLE, hook + "." + handle + "."
                        + IncusNetworkPolicy.NIC);
            } catch (IOException absent) {
                // a chain the daemon never made is not an error; 7d measures the outcome
            }
        }
    }

    /** The workload's global IPv4 on eth0, or null while DHCP has not answered yet. */
    private static String ipv4Of(IncusClient incus, String handle) {
        try {
            Map<String, Object> state = incus.instanceState(handle);
            if (state.get("network") instanceof Map<?, ?> network
                    && network.get("eth0") instanceof Map<?, ?> eth0
                    && eth0.get("addresses") instanceof List<?> addresses) {
                for (Object entry : addresses) {
                    if (entry instanceof Map<?, ?> address
                            && "inet".equals(address.get("family"))
                            && "global".equals(address.get("scope"))
                            && address.get("address") instanceof String ip) {
                        return ip;
                    }
                }
            }
            return null;
        } catch (IOException e) {
            return null;
        }
    }

    /** Best-effort daemon cleanup so the class leaves the daemon EMPTY of its resources. */
    private static void cleanupDaemonLeftovers(IncusClient incus, String volumeName) {
        try {
            incus.deleteCustomVolume("default", volumeName);
        } catch (IOException ignored) {
            // already gone (the journey's own destroy is the verified path)
        }
    }
}
