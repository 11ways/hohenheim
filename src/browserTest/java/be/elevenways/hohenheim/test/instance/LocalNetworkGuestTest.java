package be.elevenways.hohenheim.test.instance;

import be.elevenways.hohenheim.instance.GuestNetworkMode;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.ports.PublishedPort;
import be.elevenways.hohenheim.server.docker.ContainerHardening;
import be.elevenways.hohenheim.server.docker.OwnerLabels;
import be.elevenways.hohenheim.server.docker.ResourceLimits;
import be.elevenways.hohenheim.server.host.PreflightFinding;
import be.elevenways.hohenheim.server.incus.IncusClient;
import be.elevenways.hohenheim.server.incus.IncusKernelIsolation;
import be.elevenways.hohenheim.server.incus.IncusLanParent;
import be.elevenways.hohenheim.server.incus.IncusNetworkPolicy;
import be.elevenways.hohenheim.server.instance.VmKind;
import be.elevenways.hohenheim.server.runtime.Egress;
import be.elevenways.hohenheim.server.runtime.IncusInstanceRuntime;
import be.elevenways.hohenheim.server.runtime.IncusWorkloadType;
import be.elevenways.hohenheim.server.runtime.InstanceSpec;
import be.elevenways.hohenheim.server.runtime.PortPublication;
import be.elevenways.hohenheim.server.security.NftRunner;
import be.elevenways.hohenheim.server.security.TenantNetworkRanges;
import be.elevenways.hohenheim.server.util.Json;
import be.elevenways.hohenheim.test.ApiSupport;
import be.elevenways.hohenheim.test.HardDeletes;
import be.elevenways.hohenheim.test.HohenheimTestBase;
import be.elevenways.hohenheim.test.TenantConduits;
import be.elevenways.zenit.auth.model.UserModel;
import be.elevenways.zenit.auth.model.UserPrincipal;
import be.elevenways.zenit.common.edit.FieldFormEntryRegistry;
import be.elevenways.zenit.common.edit.Select;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.validation.Violations;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * A virtual machine on its host's local network: eth0 joins the host's admitted {@code lan_parent} (macvlan beside a
 * physical uplink, bridged onto a bridge) with no ACL, every extra NIC stays isolated, the kernel verifier judges only
 * the isolated NICs, an import takes the destination's parent, and published ports are refused.
 *
 * @author Jelle De Loecker
 * @since  0.10.0
 */
class LocalNetworkGuestTest extends HohenheimTestBase {

    private static final String PREFIX = "lan-guest-";

    @AfterAll
    static void cleanUp() {
        Model instances = Models.get(InstanceModel.class);
        for (Row row : instances.find().withTrashed().where(InstanceModel.NAME.startsWith(PREFIX)).all()) {
            HardDeletes.byId(instances, row.get(InstanceModel.ID));
        }
    }

    @Test
    void theWriteRefusesPortsOnALanGuestAndATenantChoosingTheLocalNetwork() {
        Model instances = Models.get(InstanceModel.class);
        Row adminUser = Models.get(UserModel.class).find()
            .where(UserModel.EMAIL.eq("test@hohenheim.local")).first();
        UserPrincipal admin = new UserPrincipal(adminUser.get(UserModel.ID), "Test Admin");
        UUID tenantId = ApiSupport.user("lan-tenant@hohenheim.local", "LAN Tenant");
        UserPrincipal tenant = new UserPrincipal(tenantId, "LAN Tenant");

        // 1. An operator's VM on the local network that also declares published ports is refused at the write, on the
        //    ports, and nothing persists.
        Throwable ported = catchThrowable(() -> TenantConduits.as(admin, () -> {
            Row row = instances.createEmptyRow();
            row.set(InstanceModel.NAME, PREFIX + "ported");
            row.set(InstanceModel.KIND, "hohenheim:vm");
            row.set(InstanceModel.SETTINGS, vmSettings(GuestNetworkMode.LOCAL_NETWORK, true));
            instances.save(row);
        }));
        assertThat(violationKeys(ported)).as("step 1: the ports are refused by name")
            .contains("settings." + PublishedPort.FIELD + "=vm_lan_ports");
        assertThat(instances.find().where(InstanceModel.NAME.eq(PREFIX + "ported")).count())
            .as("step 1: nothing persisted").isZero();

        // 2. POSITIVE ANCHOR: the same ports on a hidden guest are not refused for the mode.
        Throwable hidden = catchThrowable(() -> TenantConduits.as(admin, () -> {
            Row row = instances.createEmptyRow();
            row.set(InstanceModel.NAME, PREFIX + "hidden-ported");
            row.set(InstanceModel.KIND, "hohenheim:vm");
            row.set(InstanceModel.SETTINGS, vmSettings(GuestNetworkMode.HIDDEN, true));
            instances.save(row);
        }));
        assertThat(hidden == null ? "" : violationKeys(hidden)).as("step 2: a hidden guest publishes ports")
            .doesNotContain("vm_lan_ports");

        // 3. A tenant's create choosing the local network is refused by name, whatever else it declares.
        Throwable chosen = catchThrowable(() -> TenantConduits.as(tenant, () -> {
            Row row = instances.createEmptyRow();
            row.set(InstanceModel.NAME, PREFIX + "tenant");
            row.set(InstanceModel.KIND, "hohenheim:vm");
            row.set(InstanceModel.SETTINGS, vmSettings(GuestNetworkMode.LOCAL_NETWORK, false));
            instances.save(row);
        }));
        assertThat(violationKeys(chosen)).as("step 3: only an operator puts a machine on the local network")
            .contains("settings." + GuestNetworkMode.SETTING + "=vm_network_mode_operator");

        // 4. POSITIVE ANCHOR: the tenant's hidden create is not refused for the mode (other gates may still apply).
        Throwable tenantHidden = catchThrowable(() -> TenantConduits.as(tenant, () -> {
            Row row = instances.createEmptyRow();
            row.set(InstanceModel.NAME, PREFIX + "tenant-hidden");
            row.set(InstanceModel.KIND, "hohenheim:vm");
            row.set(InstanceModel.SETTINGS, vmSettings(GuestNetworkMode.HIDDEN, false));
            instances.save(row);
        }));
        assertThat(tenantHidden == null ? "" : violationKeys(tenantHidden)).as("step 4: hidden is a tenant's choice")
            .doesNotContain("vm_network_mode_operator");

        // 5. The mode picker offers no clear (an empty picker would read as HIDDEN while showing nothing), yet the
        //    mode stays optional: an operator's write without it is not refused for it, and reads as HIDDEN.
        Select<?> picker = (Select<?>) FieldFormEntryRegistry.INSTANCE.deriveEntry(VmKind.NETWORK_MODE);
        assertThat(picker.clearable()).as("step 5: the network-mode picker cannot be cleared").isFalse();
        Map<String, Object> modeless = vmSettings(GuestNetworkMode.HIDDEN, false);
        modeless.remove(GuestNetworkMode.SETTING);
        Throwable withoutMode = catchThrowable(() -> TenantConduits.as(admin, () -> {
            Row row = instances.createEmptyRow();
            row.set(InstanceModel.NAME, PREFIX + "modeless");
            row.set(InstanceModel.KIND, "hohenheim:vm");
            row.set(InstanceModel.SETTINGS, modeless);
            instances.save(row);
        }));
        assertThat(withoutMode == null ? "" : violationKeys(withoutMode)).as("step 5: a write without the mode passes")
            .doesNotContain("settings." + GuestNetworkMode.SETTING);
        assertThat(GuestNetworkMode.of(modeless)).as("step 5: a missing mode reads as HIDDEN")
            .isEqualTo(GuestNetworkMode.HIDDEN);
    }

    @Test
    void aLanGuestJoinsItsHostsParentWithoutAnAclAndTakesTheDestinationsOnImport() throws Exception {
        FakeIncusTransport daemon = daemonWith("ens18", "physical", false, "up");
        IncusInstanceRuntime incus = runtime(daemon, "ens18");
        InstanceSpec machine = lanSpec(PREFIX + "macvlan", null);

        // 1. A physical parent gives a macvlan eth0: a nictype and a parent, no managed network, no ACL, no pinned
        //    address, no proxies. The create returned, so the read-back NIC check passed; the mode is stamped.
        incus.create(machine);
        assertThat(devicesOf(daemon, machine.handle()).get(IncusNetworkPolicy.NIC))
            .as("step 1: eth0 is macvlan on the physical parent")
            .isEqualTo(Map.of("type", "nic", "nictype", "macvlan", "parent", "ens18"));
        assertThat(devicesOf(daemon, machine.handle()).keySet()).as("step 1: and nothing is published")
            .noneMatch(name -> name.startsWith("hohport-"));
        assertThat(configOf(daemon, machine.handle()).get(IncusNetworkPolicy.MODE_KEY))
            .as("step 1: the declared mode is stamped for the kernel verifier").isEqualTo("local_network");

        // 2. A converge keeps it so (eth0 is rewritten wholesale), and an EXTRA NIC still carries the isolation ACL.
        incus.create(machine);
        incus.ensureNic(machine, "eth1");
        assertThat(devicesOf(daemon, machine.handle()).get(IncusNetworkPolicy.NIC))
            .as("step 2: a converge leaves eth0 on the parent")
            .isEqualTo(Map.of("type", "nic", "nictype", "macvlan", "parent", "ens18"));
        assertThat(((Map<?, ?>) devicesOf(daemon, machine.handle()).get("eth1")).get(IncusNetworkPolicy.ACLS_KEY))
            .as("step 2: the extra NIC is isolated as on any guest").isEqualTo(IncusNetworkPolicy.aclName());

        // 3. A bridge parent gives a bridged eth0, the same shape otherwise.
        FakeIncusTransport bridged = daemonWith("br0", "bridge", false, "up");
        InstanceSpec onBridge = lanSpec(PREFIX + "bridged", null);
        runtime(bridged, "br0").create(onBridge);
        assertThat(devicesOf(bridged, onBridge.handle()).get(IncusNetworkPolicy.NIC))
            .as("step 3: eth0 is bridged on the bridge parent")
            .isEqualTo(Map.of("type", "nic", "nictype", "bridged", "parent", "br0"));

        // 4. A macvlan NIC carries no bandwidth limits, so a declared ceiling there is refused by name.
        FakeIncusTransport capped = daemonWith("ens18", "physical", false, "up");
        InstanceSpec limited = lanSpec(PREFIX + "capped", 50);
        assertThat(catchThrowable(() -> runtime(capped, "ens18").create(limited)))
            .as("step 4: a ceiling on a macvlan guest is refused").hasMessageContaining("applies no limits");
        assertThat(capped.instances).as("step 4: and nothing was created").isEmpty();

        // 5. The import lands on THIS host's parent: the export names the source's (ens18), the destination has eno1
        //    only, and the daemon refuses a NIC naming a parent it lacks, so the override is what lets it land.
        Path exported = Files.createTempFile("hohenheim-fake-export", ".json");
        Files.writeString(exported, Json.stringify(daemon.instances.get(machine.handle())));
        try {
            FakeIncusTransport destination = daemonWith("eno1", "physical", false, "up");
            InstanceSpec copy = lanSpec(PREFIX + "copy", null);
            runtime(destination, "eno1").importBackup(copy, exported, Map.of(), true);
            assertThat(devicesOf(destination, copy.handle()).get(IncusNetworkPolicy.NIC))
                .as("step 5: the copy's eth0 is macvlan on the destination's parent")
                .isEqualTo(Map.of("type", "nic", "nictype", "macvlan", "parent", "eno1"));
            assertThat(((Map<?, ?>) devicesOf(destination, copy.handle()).get("eth1")).get(IncusNetworkPolicy.ACLS_KEY))
                .as("step 5: its extra NIC is still isolated").isEqualTo(IncusNetworkPolicy.aclName());

            // 6. An export of a HIDDEN guest (eth0 on the managed bridge with the ACL) restored as a LAN guest drops
            //    the network and the ACL in the import itself: the daemon refuses a nictype beside a network.
            FakeIncusTransport hiddenSource = new FakeIncusTransport();
            InstanceSpec hidden = InstanceSpec.builder(PREFIX + "hidden", "debian/12/cloud", ResourceLimits.none(),
                    new ContainerHardening.Profile("fake", List.of()),
                    OwnerLabels.of(InstanceModel.MODEL_ID, 4711)).build();
            runtime(hiddenSource, null).create(hidden);
            Files.writeString(exported, Json.stringify(hiddenSource.instances.get(hidden.handle())));
            FakeIncusTransport lanDestination = daemonWith("eno1", "physical", false, "up");
            InstanceSpec turned = lanSpec(PREFIX + "turned", null);
            runtime(lanDestination, "eno1").importBackup(turned, exported, Map.of(), false);
            assertThat(devicesOf(lanDestination, turned.handle()).get(IncusNetworkPolicy.NIC))
                .as("step 6: the hidden export lands as a LAN guest")
                .isEqualTo(Map.of("type", "nic", "nictype", "macvlan", "parent", "eno1"));

            // 7. The mirror: an export taken while the guest was on the local network (eth0 macvlan on ens18),
            //    restored into a record now HIDDEN on a host with no LAN parent at all. The import itself resets eth0
            //    to the isolated shape: the daemon refuses a parent it lacks and an ACL beside a nictype.
            Files.writeString(exported, Json.stringify(daemon.instances.get(machine.handle())));
            FakeIncusTransport hiddenDestination = new FakeIncusTransport();
            InstanceSpec rehidden = InstanceSpec.builder(PREFIX + "rehidden", "debian/12/cloud",
                    ResourceLimits.none(), new ContainerHardening.Profile("fake", List.of()),
                    OwnerLabels.of(InstanceModel.MODEL_ID, 4712)).build();
            runtime(hiddenDestination, null).importBackup(rehidden, exported, Map.of(), true);
            Map<?, ?> rehiddenNic = (Map<?, ?>) devicesOf(hiddenDestination, rehidden.handle())
                .get(IncusNetworkPolicy.NIC);
            assertThat(rehiddenNic.keySet().stream().map(String::valueOf).toList())
                .as("step 7: the LAN export lands isolated, on no host interface")
                .doesNotContain("nictype", "parent");
            assertThat(rehiddenNic.get("network")).as("step 7: eth0 is back on the managed bridge")
                .isEqualTo("incusbr0");
            assertThat(rehiddenNic.get(IncusNetworkPolicy.ACLS_KEY)).as("step 7: and carries the isolation ACL")
                .isEqualTo(IncusNetworkPolicy.aclName());
        } finally {
            Files.deleteIfExists(exported);
        }
    }

    @Test
    void onlyAnUnmanagedInterfaceThatIsUpIsAdmittedAndTheDriverAsksTheSameRule() throws Exception {
        // 1. POSITIVE ANCHOR: an unmanaged physical interface that is up is admitted as macvlan.
        FakeIncusTransport daemon = daemonWith("ens18", "physical", false, "up");
        assertThat(IncusLanParent.admit(new IncusClient(daemon), "ens18"))
            .as("step 1: ens18 is admitted").isEqualTo(new IncusLanParent.Lan("ens18", IncusLanParent.NicType.MACVLAN));

        // 2. A managed network (incusbr0, a NAT bridge) is not the local network.
        assertThat(refusalOf(daemon, "incusbr0")).as("step 2: a managed parent")
            .isEqualTo(PreflightFinding.LAN_PARENT_MANAGED);

        // 3. A missing interface, and one that is down.
        assertThat(refusalOf(daemon, "ens19")).as("step 3: a missing parent")
            .isEqualTo(PreflightFinding.LAN_PARENT_ABSENT);
        daemon.networkStore.put("ens20", Map.of("name", "ens20", "type", "physical", "managed", false));
        daemon.networkStates.put("ens20", "down");
        assertThat(refusalOf(daemon, "ens20")).as("step 3: a parent that is down")
            .isEqualTo(PreflightFinding.LAN_PARENT_DOWN);

        // 4. The driver asks the same rule: a LAN guest on a down parent, or on a host declaring none, is never
        //    created at all.
        assertThat(catchThrowable(() -> runtime(daemon, "ens20").create(lanSpec(PREFIX + "down", null))))
            .as("step 4: the driver refuses the down parent").isInstanceOf(IncusLanParent.Unusable.class);
        assertThat(catchThrowable(() -> runtime(daemon, null).create(lanSpec(PREFIX + "none", null))))
            .as("step 4: and a host declaring no parent").hasMessageContaining("declares no lan_parent");
        assertThat(daemon.instances).as("step 4: nothing was created").isEmpty();

        // 5. Published ports on a LAN guest are refused at deploy (VmKind) and by the driver.
        Map<String, Object> settings = new LinkedHashMap<>();
        settings.put("image", "debian/12/cloud");
        settings.put(GuestNetworkMode.SETTING, GuestNetworkMode.LOCAL_NETWORK.token());
        assertThat(new VmKind().specFor(73, settings).networkMode())
            .as("step 5: the VM setting reaches the spec").isEqualTo(GuestNetworkMode.LOCAL_NETWORK);
        settings.put(PublishedPort.FIELD, Map.of("game", "30001:25565/tcp"));
        Throwable deploy = catchThrowable(() -> new VmKind().specFor(73, settings));
        assertThat(deploy).as("step 5: the deploy refuses the ports").isInstanceOf(Violations.class);
        assertThat(((Violations) deploy).all().get(0).message().key()).isEqualTo("vm_lan_ports");
        InstanceSpec published = InstanceSpec.builder(PREFIX + "ports", "debian/12/cloud", ResourceLimits.none(),
                new ContainerHardening.Profile("fake", List.of()), OwnerLabels.of(InstanceModel.MODEL_ID, 4712))
            .networkMode(GuestNetworkMode.LOCAL_NETWORK)
            .publications(List.of(new PortPublication(25565, "tcp", true, 30001, null)))
            .build();
        assertThat(catchThrowable(() -> runtime(daemonWith("ens18", "physical", false, "up"), "ens18")
                .create(published)))
            .as("step 5: and so does the driver").hasMessageContaining("REFUSED to publish ports");
    }

    @Test
    void theKernelVerifierJudgesOnlyTheIsolatedNicsOfALanGuestAndRepairsThose() throws Exception {
        FakeIncusTransport daemon = new FakeIncusTransport();
        String handle = PREFIX + "kernel";
        Map<String, Object> config = new LinkedHashMap<>();
        config.put(IncusNetworkPolicy.MODE_KEY, GuestNetworkMode.LOCAL_NETWORK.token());
        config.put("volatile.eth0.host_name", "macf00");
        config.put("volatile.eth1.host_name", "tapb1");
        Map<String, Object> devices = new LinkedHashMap<>();
        devices.put("eth0", lanNic());
        devices.put("eth1", isolatedNic("hhx-test"));
        daemon.instances.put(handle, new LinkedHashMap<>(Map.of("name", handle, "type", "virtual-machine",
            "config", config, "devices", devices)));

        // The kernel holds the rules for the extra NIC only once its device was reloaded (the repair's lever).
        AtomicInteger listings = new AtomicInteger();
        NftRunner nft = (args, stdin) -> {
            listings.incrementAndGet();
            boolean reloaded = devicesOf(daemon, handle).get("eth1") instanceof Map<?, ?> eth1
                && eth1.containsKey(IncusKernelIsolation.REAPPLY_KEY);
            return new NftRunner.Result(0, reloaded ? rulesFor("tapb1") : "", "");
        };
        IncusKernelIsolation kernel = new IncusKernelIsolation(new IncusClient(daemon), nft);

        // 1. Only the extra NIC's tap is judged; the macvlan eth0 carries no isolation rules by design.
        IncusKernelIsolation.Divergence before = kernel.inspect(handle);
        assertThat(before.taps()).as("step 1: the LAN eth0 is not judged").containsExactly("tapb1");
        assertThat(before.enforced()).as("step 1: the extra NIC's rules are missing").isFalse();

        // 2. The repair reloads the isolated NIC only, and the re-read passes.
        kernel.enforce(handle);
        assertThat(((Map<?, ?>) devicesOf(daemon, handle).get("eth1")).containsKey(IncusKernelIsolation.REAPPLY_KEY))
            .as("step 2: eth1 was toggled").isTrue();
        assertThat(devicesOf(daemon, handle).get("eth0")).as("step 2: eth0 was left alone").isEqualTo(lanNic());

        // 3. A guest whose only NIC is on the local network has nothing to judge: no kernel read at all.
        devices.remove("eth1");
        config.remove("volatile.eth1.host_name");
        daemon.instances.put(handle, new LinkedHashMap<>(Map.of("name", handle, "type", "virtual-machine",
            "config", config, "devices", devices)));
        int asked = listings.get();
        assertThat(kernel.inspect(handle).enforced()).as("step 3: a LAN-only guest passes").isTrue();
        assertThat(listings.get()).as("step 3: without asking the kernel").isEqualTo(asked);

        // 4. The stamp alone never exempts: an eth0 still on the bridge is judged although the stamp says LAN.
        devices.put("eth0", isolatedNic("incusbr0"));
        daemon.instances.put(handle, new LinkedHashMap<>(Map.of("name", handle, "type", "virtual-machine",
            "config", config, "devices", devices)));
        assertThat(kernel.inspect(handle).taps()).as("step 4: a bridged eth0 is judged").containsExactly("macf00");

        // 5. Nor does the device alone: a LAN-shaped eth0 under a hidden stamp is judged.
        devices.put("eth0", lanNic());
        config.put(IncusNetworkPolicy.MODE_KEY, GuestNetworkMode.HIDDEN.token());
        daemon.instances.put(handle, new LinkedHashMap<>(Map.of("name", handle, "type", "virtual-machine",
            "config", config, "devices", devices)));
        assertThat(kernel.inspect(handle).taps()).as("step 5: an unstamped LAN eth0 is judged")
            .containsExactly("macf00");
    }

    // -- helpers --------------------------------------------------------------

    private static Map<String, Object> vmSettings(GuestNetworkMode mode, boolean ports) {
        Map<String, Object> settings = new LinkedHashMap<>();
        settings.put("image", "debian/12/cloud");
        settings.put(GuestNetworkMode.SETTING, mode.token());
        if (ports) {
            settings.put(PublishedPort.FIELD, new LinkedHashMap<>(Map.of("game", "30001:25565/tcp")));
        }
        return settings;
    }

    private static String violationKeys(Throwable thrown) {
        assertThat(thrown).isInstanceOf(Violations.class);
        StringBuilder keys = new StringBuilder();
        for (var violation : ((Violations) thrown).all()) {
            keys.append(violation.fieldName()).append('=').append(violation.message().key()).append(' ');
        }
        return keys.toString();
    }

    private static FakeIncusTransport daemonWith(String parent, String type, boolean managed, String state) {
        FakeIncusTransport daemon = new FakeIncusTransport();
        daemon.networkStore.put(parent, Map.of("name", parent, "type", type, "managed", managed));
        daemon.networkStates.put(parent, state);
        return daemon;
    }

    private static IncusInstanceRuntime runtime(FakeIncusTransport daemon, String lanParent) {
        return new IncusInstanceRuntime(new IncusClient(daemon), Egress.OPEN, IncusWorkloadType.VIRTUAL_MACHINE,
            null, lanParent);
    }

    private static InstanceSpec lanSpec(String handle, Integer limitMbit) {
        return InstanceSpec.builder(handle, "debian/12/cloud", ResourceLimits.none(),
                new ContainerHardening.Profile("fake", List.of()),
                OwnerLabels.of(InstanceModel.MODEL_ID, Math.abs(handle.hashCode())))
            .networkMode(GuestNetworkMode.LOCAL_NETWORK)
            .networkLimitMbit(limitMbit)
            .build();
    }

    private static PreflightFinding refusalOf(FakeIncusTransport daemon, String parent) {
        Throwable refused = catchThrowable(() -> IncusLanParent.admit(new IncusClient(daemon), parent));
        assertThat(refused).as("%s is refused", parent).isInstanceOf(IncusLanParent.Unusable.class);
        return ((IncusLanParent.Unusable) refused).found().finding();
    }

    private static Map<String, Object> lanNic() {
        return Map.of("type", "nic", "nictype", "macvlan", "parent", "ens18");
    }

    private static Map<String, Object> isolatedNic(String network) {
        return Map.of("type", "nic", "network", network, IncusNetworkPolicy.ACLS_KEY, IncusNetworkPolicy.aclName());
    }

    /** A ruleset carrying every tenant-range drop for {@code tap}, as the verifier reads it. */
    private static String rulesFor(String tap) {
        List<String> lines = new ArrayList<>();
        for (String range : TenantNetworkRanges.DENIED_V4) {
            lines.add("iifname \"" + tap + "\" ip daddr " + range + " drop");
        }
        for (String range : TenantNetworkRanges.DENIED_V6) {
            lines.add("iifname \"" + tap + "\" ip6 daddr " + range + " drop");
        }
        return String.join("\n", lines);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> devicesOf(FakeIncusTransport daemon, String handle) {
        return (Map<String, Object>) daemon.instances.get(handle).get("devices");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> configOf(FakeIncusTransport daemon, String handle) {
        return (Map<String, Object>) daemon.instances.get(handle).get("config");
    }
}
