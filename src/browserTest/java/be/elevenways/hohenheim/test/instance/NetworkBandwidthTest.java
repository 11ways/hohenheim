package be.elevenways.hohenheim.test.instance;

import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.server.docker.ContainerHardening;
import be.elevenways.hohenheim.server.docker.DockerClient;
import be.elevenways.hohenheim.server.docker.OwnerLabels;
import be.elevenways.hohenheim.server.docker.ResourceLimits;
import be.elevenways.hohenheim.server.incus.IncusClient;
import be.elevenways.hohenheim.server.incus.IncusNetworkPolicy;
import be.elevenways.hohenheim.server.instance.DockerContainerKind;
import be.elevenways.hohenheim.server.instance.IncusContainerKind;
import be.elevenways.hohenheim.server.instance.IncusVmKind;
import be.elevenways.hohenheim.server.instance.NetworkBandwidth;
import be.elevenways.hohenheim.server.runtime.DockerInstanceRuntime;
import be.elevenways.hohenheim.server.runtime.Egress;
import be.elevenways.hohenheim.server.runtime.ImageOrigin;
import be.elevenways.hohenheim.server.runtime.IncusInstanceRuntime;
import be.elevenways.hohenheim.server.runtime.IncusWorkloadType;
import be.elevenways.hohenheim.server.runtime.InstanceSpec;
import be.elevenways.hohenheim.server.security.WorkloadNetworkPolicy;
import be.elevenways.hohenheim.test.HohenheimTestBase;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * The per-workload bandwidth ceiling, daemon-free: which tiers OFFER it, what the Incus
 * driver puts on the wire, that a converge RE-ASSERTS it instead of losing it, that an
 * extra NIC cannot be a hole in it, and that a tier which could not enforce it refuses
 * BY NAME instead of accepting a number and shaping nothing.
 *
 * The gap this closes is not "a missing feature". The driver owns the whole
 * {@code limits.} config namespace AND rewrites the managed NIC device wholesale on every
 * converge, so an operator who hand-set a rate had it erased on the next deploy with no
 * workaround anywhere -- a control plane actively removing the operator's configuration.
 * The rate is a DECLARATION now, so the same rewrite re-asserts the operator's own number.
 *
 * AIDEV-NOTE: every claim here is asserted against what the fake daemon RECEIVED and
 * ECHOES, never against the spec object the test itself built -- a driver that computed
 * the right value and never sent it would pass the latter. Real-iron proof that Incus
 * honours the key belongs in the live lane; what needs no iron is the whole contract
 * around it, and that is what this class owns.
 */
class NetworkBandwidthTest extends HohenheimTestBase {

    private static final String PREFIX = "netlimit-";

    @Test
    void onlyTiersThatCanEnforceARateOfferOneAndTheDeclarationReachesTheWire()
            throws Exception {
        // 1. Declaring the field IS the capability declaration -- the RootDisk doctrine.
        //    Only the Incus kinds can hand a rate to a real limiter, so only they ask.
        assertThat(IncusContainerKind.SETTINGS_SCHEMA.getField(NetworkBandwidth.SETTING))
            .as("step 1: the Incus container kind offers a network limit").isNotNull();
        assertThat(IncusVmKind.SETTINGS_SCHEMA.getField(NetworkBandwidth.SETTING))
            .as("step 1: and so does the Incus VM kind").isNotNull();
        assertThat(new DockerContainerKind().getSchema().getField(NetworkBandwidth.SETTING))
            .as("step 1: the Docker kind offers NO network limit -- there is no HostConfig"
                + " key for one, so an offered number could only ever shape nothing")
            .isNull();

        // 2. The declaration survives the settings-to-spec derivation, and a blank one
        //    stays UNLIMITED rather than becoming a zero-rate cap.
        assertThat(new IncusContainerKind().specFor(71, settings(100)).networkLimitMbit())
            .as("step 2: a declared ceiling reaches the driver").isEqualTo(100);
        assertThat(new IncusContainerKind().specFor(71, settings(null)).networkLimitMbit())
            .as("step 2: a blank declaration leaves the wire unshaped, never capped at 0")
            .isNull();
        assertThat(new IncusVmKind().specFor(72, settings(250)).networkLimitMbit())
            .as("step 2: the VM kind carries it too").isEqualTo(250);

        // 3. THE WIRE: the create body's NIC carries the rate in BOTH directions, beside
        //    the isolation ACL rather than instead of it.
        FakeIncusTransport daemon = new FakeIncusTransport();
        runtime(daemon).create(spec(PREFIX + "capped", 100));
        Map<String, Object> nic = nicOf(createBodyDevices(daemon));
        assertThat(nic.get("security.acls"))
            .as("step 3: the isolation ACL is still on the device")
            .isEqualTo(IncusNetworkPolicy.aclName());
        assertThat(Map.of("in", String.valueOf(nic.get("limits.ingress")),
                "out", String.valueOf(nic.get("limits.egress"))))
            .as("step 3: and the declared ceiling is on it, in the daemon's own spelling,"
                + " in both directions")
            .isEqualTo(Map.of("in", "100Mbit", "out", "100Mbit"));

        // 4. POSITIVE ANCHOR, and the blast-radius claim: a workload that declares
        //    NOTHING gets no limit key at all. Without this, step 3 could be passing on a
        //    driver that shapes every workload at some default -- and every already
        //    deployed instance would converge into a cap nobody asked for.
        FakeIncusTransport unshaped = new FakeIncusTransport();
        runtime(unshaped).create(spec(PREFIX + "unshaped", null));
        assertThat(nicOf(createBodyDevices(unshaped)))
            .as("step 4: no declaration, no limit keys -- an existing workload's NIC is"
                + " byte-identical to what it was before this knob existed")
            .doesNotContainKeys("limits.ingress", "limits.egress");

        // 5. The Docker driver REFUSES a spec carrying one, by name, saying what would
        //    otherwise happen. The nft runner would throw if the refusal reached it, so
        //    this also proves nothing touches the host first.
        DockerInstanceRuntime docker = new DockerInstanceRuntime(new DockerClient(),
            new WorkloadNetworkPolicy((args, stdin) -> {
                throw new IllegalStateException("the refusal must not reach nft");
            }, () -> true));
        assertThat(catchThrowable(() -> docker.create(spec(PREFIX + "docker", 100))))
            .as("step 5: docker refuses a bandwidth declaration BY NAME")
            .hasMessageContaining("cannot deliver the 100 Mbit/s network limit")
            .hasMessageContaining("incus capability")
            .hasMessageContaining("shape nothing");
    }

    @Test
    void aConvergeReAssertsTheRateAndAnExtraNicCannotBeAHoleInIt() throws Exception {
        FakeIncusTransport daemon = new FakeIncusTransport();
        IncusInstanceRuntime incus = runtime(daemon);
        String handle = PREFIX + "converge";

        // 1. The workload exists with its declared ceiling.
        incus.create(spec(handle, 40));
        assertThat(storedNic(daemon, handle).get("limits.egress"))
            .as("step 1: the daemon carries the declared rate").isEqualTo("40Mbit");

        // 2. DRIFT: something on the daemon side loses the limit -- a manual edit, a
        //    restore, a daemon upgrade. This is also the exact state the product was in
        //    before the knob existed: a rate on the NIC and nothing declaring it.
        storedNic(daemon, handle).remove("limits.ingress");
        storedNic(daemon, handle).remove("limits.egress");
        assertThat(storedNic(daemon, handle))
            .as("step 2: the fixture really did drop the cap")
            .doesNotContainKey("limits.egress");

        // 3. THE POINT: the converge REPAIRS it, the same way it repairs a dropped ACL.
        //    Before the declaration existed this rewrite was what ERASED an operator's
        //    hand-set rate, with no product spelling to put it back.
        incus.create(spec(handle, 40));
        assertThat(Map.of("in", String.valueOf(storedNic(daemon, handle).get("limits.ingress")),
                "out", String.valueOf(storedNic(daemon, handle).get("limits.egress"))))
            .as("step 3: a converge re-asserts the DECLARED rate rather than losing it")
            .isEqualTo(Map.of("in", "40Mbit", "out", "40Mbit"));

        // 4. A CHANGED declaration converges too -- the cap follows the record, so an
        //    operator lowering it does not have to destroy the workload.
        incus.create(spec(handle, 10));
        assertThat(storedNic(daemon, handle).get("limits.egress"))
            .as("step 4: the daemon carries the new number after a plain redeploy")
            .isEqualTo("10Mbit");

        // 5. An EXTRA NIC carries the same ceiling. An interface without it would be a
        //    hole in the cap exactly as an interface without the ACL is a hole in the
        //    isolation -- attach one and the limit is simply gone.
        incus.ensureNic(spec(handle, 10), "eth1");
        Map<?, ?> extra = (Map<?, ?>) devicesOf(daemon, handle).get("eth1");
        assertThat(extra).as("step 5: the extra NIC landed").isNotNull();
        assertThat(Map.of("acl", String.valueOf(extra.get("security.acls")),
                "out", String.valueOf(extra.get("limits.egress"))))
            .as("step 5: carrying the isolation ACL AND the workload's own ceiling")
            .isEqualTo(Map.of("acl", IncusNetworkPolicy.aclName(), "out", "10Mbit"));
    }

    /**
     * COUNTERFACTUAL for the read-back: a daemon that accepts the value and does not
     * apply it must be a refusal, not a success.
     *
     * A cap that reports success while shaping nothing is the paper limit this whole knob
     * exists to avoid, and "the API returned 200" and "the daemon is enforcing it" are
     * independent facts here exactly as they are for the isolation ACL.
     */
    @Test
    void aRateTheDaemonDidNotApplyIsARefusalAndAnUndeclaredOneIsNeverChecked() {
        IncusNetworkPolicy policy = new IncusNetworkPolicy(
            new IncusClient(new FakeIncusTransport()));
        String handle = PREFIX + "verify";

        // 1. POSITIVE ANCHOR: a NIC that really carries the rate passes.
        Map<String, Object> honest = instanceWithNic(nicCarrying("25Mbit"));
        assertThat(catchThrowable(() -> policy.verifyBandwidth(handle, honest, 25)))
            .as("step 1: the value the daemon carries is the value declared").isNull();

        // 2. THE PAPER LIMIT: the key is gone from the read-back. Refuse by name.
        Map<String, Object> dropped = instanceWithNic(nicCarrying(null));
        assertThat(catchThrowable(() -> policy.verifyBandwidth(handle, dropped, 25)))
            .as("step 2: a declared cap the daemon did not apply is a refusal")
            .hasMessageContaining("bandwidth ceiling")
            .hasMessageContaining("shapes nothing");

        // 3. A DIFFERENT value is a refusal too -- silently rounding a tenant's cap up is
        //    the same lie in a smaller font.
        Map<String, Object> wrong = instanceWithNic(nicCarrying("1000Mbit"));
        assertThat(catchThrowable(() -> policy.verifyBandwidth(handle, wrong, 25)))
            .as("step 3: a rate that is not the declared one is refused")
            .hasMessageContaining("1000Mbit");

        // 4. THE BLAST-RADIUS BOUND, and it is load-bearing: a workload that declared no
        //    ceiling is never checked at all, so no existing deploy can start failing on
        //    a verification it never asked for.
        assertThat(catchThrowable(() -> policy.verifyBandwidth(handle, dropped, null)))
            .as("step 4: no declaration, no check -- the same NIC that fails step 2 passes")
            .isNull();
    }

    // -- helpers --------------------------------------------------------------

    private static IncusInstanceRuntime runtime(FakeIncusTransport daemon) {
        return new IncusInstanceRuntime(new IncusClient(daemon), Egress.OPEN,
            IncusWorkloadType.CONTAINER, null);
    }

    private static Map<String, Object> settings(Integer limitMbit) {
        Map<String, Object> settings = new LinkedHashMap<>();
        settings.put("image", "alpine/3.22");
        if (limitMbit != null) {
            settings.put(NetworkBandwidth.SETTING, limitMbit);
        }
        return settings;
    }

    private static InstanceSpec spec(String handle, Integer limitMbit) {
        return new InstanceSpec(handle, "alpine/3.22", null, Map.of(), Map.of(), null,
            ResourceLimits.none(), new ContainerHardening.Profile("fake", List.of()),
            OwnerLabels.of(InstanceModel.MODEL_ID, Math.abs(handle.hashCode())), null, null,
            ImageOrigin.CATALOG, false, true, Map.of(), null, limitMbit);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> createBodyDevices(FakeIncusTransport daemon) {
        assertThat(daemon.lastCreateBody).as("a create reached the daemon").isNotNull();
        return (Map<String, Object>) daemon.lastCreateBody.get("devices");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> devicesOf(FakeIncusTransport daemon, String handle) {
        Map<String, Object> instance = daemon.instances.get(handle);
        assertThat(instance).as("the daemon holds instance %s", handle).isNotNull();
        return (Map<String, Object>) instance.get("devices");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> storedNic(FakeIncusTransport daemon, String handle) {
        return (Map<String, Object>) devicesOf(daemon, handle).get(IncusNetworkPolicy.NIC);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> nicOf(Map<String, Object> devices) {
        Map<String, Object> nic = (Map<String, Object>) devices.get(IncusNetworkPolicy.NIC);
        assertThat(nic).as("the isolating NIC override is present").isNotNull();
        return nic;
    }

    /** One NIC device as a daemon read-back would render it. */
    private static Map<String, Object> nicCarrying(String rate) {
        Map<String, Object> nic = new LinkedHashMap<>();
        nic.put("type", "nic");
        nic.put("network", "incusbr0");
        nic.put("security.acls", IncusNetworkPolicy.aclName());
        if (rate != null) {
            nic.put("limits.ingress", rate);
            nic.put("limits.egress", rate);
        }
        return nic;
    }

    private static Map<String, Object> instanceWithNic(Map<String, Object> nic) {
        return Map.of("devices", Map.of(IncusNetworkPolicy.NIC, nic));
    }
}
