package be.elevenways.hohenheim.test.docker;

import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.server.docker.ContainerHardening;
import be.elevenways.hohenheim.server.docker.OwnerLabels;
import be.elevenways.hohenheim.server.docker.DockerClient;
import be.elevenways.hohenheim.server.docker.DockerTransport;
import be.elevenways.hohenheim.server.instance.DockerContainerKind;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The HostConfig keys {@code ContainerHardening} PERMITS, pinned at the WIRE with no
 * daemon: a spec carrying anything else never reaches {@code POST /containers/create}.
 *
 * The live sibling ({@code ContainerHardeningTest}) proves the same refusals against a
 * real socket and reads the surviving container's kernel state back, which is the
 * stronger claim -- and it is slow-tagged, so on an ordinary run NOTHING guarded this
 * gate. That is the gap this class closes: the OWNERSHIP half needs no daemon, so it
 * belongs in the default lane where a careless edit to the list is caught the same day.
 *
 * AIDEV-NOTE: the gate was a DENY-list until 2026-08-17 and this test was its mirror --
 * it asserted the deny-list entry by entry, which by construction could never notice the
 * keys the deny-list had never heard of. It now drives off {@link #REFUSED_PROBES}, which
 * deliberately includes keys that USED TO PASS ({@code VolumesFrom}, {@code Runtime},
 * {@code GroupAdd}, {@code Ulimits}, ...) and a made-up key standing in for whatever the
 * next Docker API version adds. Step 3 is the other half: every PERMITTED key must still
 * get through, so the inversion cannot be "tightened" into breaking a real caller.
 */
class ContainerEscapeKeyTest {

    /**
     * A plausible value per key a caller may not set: a refusal must not depend on the
     * value's shape.
     *
     * AIDEV-NOTE: this is TEST DATA, not a second vocabulary -- the gate is
     * {@link ContainerHardening#PERMITTED_KEYS} and nothing here needs to stay in sync
     * with it. What these entries prove is that the refusal covers the documented escapes
     * ({@code Privileged}, host binds, device access), the keys the policy stamps itself
     * ({@code CapDrop} and friends), the escape-capable keys the old deny-list MISSED, and
     * a key nobody has ever heard of.
     */
    private static final Map<String, Object> REFUSED_PROBES = refusedProbes();

    @Test
    void onlyPermittedHostConfigKeysReachTheDaemon() throws Exception {
        RecordingTransport transport = new RecordingTransport();
        DockerClient docker = new DockerClient(transport);

        // 1. The positive anchor first: an ordinary create really does reach the daemon
        //    through this transport, so every "nothing was sent" below means something.
        docker.createContainer("escape-anchor", spec(), DockerContainerKind.HARDENING);
        assertThat(transport.lastCreateBody)
            .as("step 1: a clean spec reaches the daemon, so the transport is live")
            .isNotNull();
        assertThat(transport.lastCreateBody)
            .as("step 1: and the policy stamped its own baseline onto it")
            .contains("\"CapDrop\":[\"ALL\"]")
            .contains("no-new-privileges");

        // 2. THE OWNERSHIP CLAIM. Every probe key is refused BY NAME and the daemon is
        //    never contacted -- including the ten that the deny-list era waved through
        //    and the invented key standing in for a future API addition.
        for (Map.Entry<String, Object> probe : REFUSED_PROBES.entrySet()) {
            String key = probe.getKey();
            assertThat(ContainerHardening.PERMITTED_KEYS)
                .as("step 2: probe '" + key + "' must be a key the policy does NOT permit")
                .doesNotContainKey(key);
            Map<String, Object> carrying = spec();
            carrying.put("HostConfig", new LinkedHashMap<>(Map.of(key, probe.getValue())));
            transport.lastCreateBody = null;
            assertThatThrownBy(() -> docker.createContainer("escape-" + key.toLowerCase(Locale.ROOT),
                    carrying, DockerContainerKind.HARDENING))
                .as("step 2: HostConfig." + key + " is not permitted and must be refused,"
                    + " not silently forwarded")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("HostConfig." + key);
            assertThat(transport.lastCreateBody)
                .as("step 2: STATE, not just the throw -- HostConfig." + key + " must not"
                    + " have reached the daemon")
                .isNull();
        }

        // 3. THE OTHER HALF: an allow-list that refuses a legitimate caller is a broken
        //    product, so every permitted key -- carried together, as the instance runtime
        //    actually carries them -- still gets through, and arrives unmangled.
        Map<String, Object> legitimate = spec();
        Map<String, Object> hostConfig = new LinkedHashMap<>();
        hostConfig.put("NetworkMode", "hohenheim-wl-1");
        hostConfig.put("Mounts", List.of(Map.of(
            "Type", "volume", "Source", "vol-1", "Target", "/data")));
        hostConfig.put("PortBindings", Map.of("80/tcp",
            List.of(Map.of("HostIp", "127.0.0.1", "HostPort", "8081"))));
        hostConfig.put("Memory", 512L * 1024 * 1024);
        hostConfig.put("NanoCpus", 1_500_000_000L);
        legitimate.put("HostConfig", hostConfig);
        assertThat(hostConfig.keySet())
            .as("step 3: and this spec really does exercise EVERY permitted key")
            .containsExactlyInAnyOrderElementsOf(ContainerHardening.PERMITTED_KEYS.keySet());
        transport.lastCreateBody = null;
        docker.createContainer("permitted-all", legitimate, DockerContainerKind.HARDENING);
        assertThat(transport.lastCreateBody)
            .as("step 3: every permitted key survives the funnel with its value intact")
            .isNotNull()
            .contains("hohenheim-wl-1")
            .contains("\"Memory\":536870912")
            .contains("\"NanoCpus\":1500000000")
            .contains("8081");

        // 4. READ-ONLY ROOTFS, the recorded decision. The Phase 3 clause offers it "where
        //    the template allows"; no template in this tier allows it (published images
        //    chown, write pid files and unpack into their own root), so the policy does
        //    not ship it -- and it is refused rather than forwarded, which is the honest
        //    form of "we decided not to".
        Map<String, Object> plain = new LinkedHashMap<>();
        plain.put("Image", "alpine:latest");
        ContainerHardening.applyTo(plain, DockerContainerKind.HARDENING);
        assertThat(plain.get("HostConfig"))
            .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
            .as("step 4: the policy asserts NOTHING about the rootfs itself -- a"
                + " ReadonlyRootfs:false would be a claim we do not make either")
            .doesNotContainKey("ReadonlyRootfs");

        // 5. And the refusal is a property of the FUNNEL, not of a profile: the tightest
        //    profile there is refuses exactly the same key.
        Map<String, Object> underStrict = spec();
        underStrict.put("HostConfig",
            new LinkedHashMap<>(Map.of("ReadonlyRootfs", Boolean.TRUE)));
        transport.lastCreateBody = null;
        assertThatThrownBy(() -> docker.createContainer("escape-strict", underStrict,
                ContainerHardening.STRICT))
            .as("step 5: no profile is a way around an unpermitted key")
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("HostConfig.ReadonlyRootfs");
        assertThat(transport.lastCreateBody)
            .as("step 5: and nothing reached the daemon under STRICT either").isNull();

        // 6. THE CASE FOLD. Docker unmarshals HostConfig with Go's encoding/json, which
        //    prefers an exact field match but ACCEPTS a case-insensitive one -- so
        //    "privileged" and "capAdd" are honoured by the daemon exactly like their
        //    canonical spellings. A case-sensitive gate would therefore see an unknown key
        //    and, under an allow-list, refuse it anyway; what the fold protects is the
        //    PERMITTED side, where "networkmode" must be recognised as the key it is
        //    rather than refused. Both directions are probed.
        for (String key : REFUSED_PROBES.keySet()) {
            for (String spelling : List.of(key.toLowerCase(Locale.ROOT),
                    Character.toLowerCase(key.charAt(0)) + key.substring(1))) {
                if (spelling.equals(key)) {
                    continue;
                }
                Map<String, Object> carrying = spec();
                carrying.put("HostConfig",
                    new LinkedHashMap<>(Map.of(spelling, REFUSED_PROBES.get(key))));
                transport.lastCreateBody = null;
                assertThatThrownBy(() -> docker.createContainer("escape-fold",
                        carrying, DockerContainerKind.HARDENING))
                    .as("step 6: HostConfig." + spelling + " is the SAME field to the daemon"
                        + " as " + key + ", so the funnel must refuse it too")
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("HostConfig." + spelling);
                assertThat(transport.lastCreateBody)
                    .as("step 6: STATE -- the mixed-case " + spelling + " must not have"
                        + " reached the daemon")
                    .isNull();
            }
        }
        for (String key : ContainerHardening.PERMITTED_KEYS.keySet()) {
            Map<String, Object> folded = spec();
            folded.put("HostConfig", new LinkedHashMap<>(
                Map.of(key.toLowerCase(Locale.ROOT), "none")));
            transport.lastCreateBody = null;
            docker.createContainer("permitted-fold", folded, DockerContainerKind.HARDENING);
            assertThat(transport.lastCreateBody)
                .as("step 6: a lowercase " + key + " is the same permitted field to the"
                    + " daemon, so the fold must recognise it instead of refusing it")
                .isNotNull();
        }

        // 7. The same fold covers the namespace VALUES on the one namespace key that is
        //    permitted ("HOST" is the same string to the daemon's mode parser), and the
        //    bind-mount refusal inside the permitted Mounts, whose Type and Source members
        //    are decoded by the same case-insensitive unmarshaller.
        Map<String, Object> lowercaseNamespace = spec();
        lowercaseNamespace.put("HostConfig",
            new LinkedHashMap<>(Map.of("networkMode", "HOST")));
        transport.lastCreateBody = null;
        assertThatThrownBy(() -> docker.createContainer("escape-ns", lowercaseNamespace,
                DockerContainerKind.HARDENING))
            .as("step 7: a lowercase networkMode carrying an uppercase HOST is still a"
                + " host-namespace share, and the key itself is permitted so only the"
                + " value check can catch it")
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("HostConfig.NetworkMode");

        Map<String, Object> joiningNamespace = spec();
        joiningNamespace.put("HostConfig",
            new LinkedHashMap<>(Map.of("networkMode", "Container:abc123")));
        assertThatThrownBy(() -> docker.createContainer("escape-join", joiningNamespace,
                DockerContainerKind.HARDENING))
            .as("step 7: and a capitalised Container: prefix still joins another"
                + " container's namespace")
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("HostConfig.NetworkMode");

        Map<String, Object> lowercaseMounts = spec();
        lowercaseMounts.put("HostConfig", new LinkedHashMap<>(Map.of(
            "mounts", List.of(Map.of("type", "Bind", "source", "/")))));
        transport.lastCreateBody = null;
        assertThatThrownBy(() -> docker.createContainer("escape-mount", lowercaseMounts,
                DockerContainerKind.HARDENING))
            .as("step 7: a lowercase mounts entry with a capitalised Bind type is the same"
                + " host bind to the daemon")
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("bind mount");
        assertThat(transport.lastCreateBody)
            .as("step 7: STATE -- no folded escape reached the daemon at all").isNull();
    }

    /**
     * The TOP-LEVEL create-body keys the policy permits, pinned at the wire the same way
     * -- and the {@code NetworkingConfig} bypass it now constrains structurally.
     *
     * AIDEV-NOTE: until 2026-08-23 this level was not gated at all. Only
     * {@code spec.get("HostConfig")} was hardened and the REST of the body was forwarded
     * verbatim, so the sibling claim ("a new instance kind cannot lose isolation by
     * accident") held for exactly one key of the body. The proven bypass is step 4: a
     * SECOND {@code EndpointsConfig} entry attaches the container to any existing network
     * at create time, and the per-workload nft policy is keyed on ONE subnet, so nothing
     * that isolates the workload matches the addresses it gets on the other network.
     */
    @Test
    void onlyPermittedCreateBodyKeysReachTheDaemon() throws Exception {
        RecordingTransport transport = new RecordingTransport();
        DockerClient docker = new DockerClient(transport);

        // 1. The positive anchor: the body the instance runtime really sends -- EVERY
        //    permitted top-level key at once -- reaches the daemon intact.
        Map<String, Object> legitimate = new LinkedHashMap<>();
        legitimate.put("Image", "alpine:latest");
        legitimate.put("Labels", instanceLabels(41));
        legitimate.put("Cmd", List.of("sleep", "30"));
        legitimate.put("Env", List.of("HOME=/home/site"));
        legitimate.put("User", "10041");
        legitimate.put("ExposedPorts", Map.of("80/tcp", Map.of()));
        legitimate.put("Healthcheck", Map.of("Test", List.of("CMD-SHELL", "true")));
        legitimate.put("OpenStdin", Boolean.TRUE);
        legitimate.put("StdinOnce", Boolean.FALSE);
        legitimate.put("NetworkingConfig",
            Map.of("EndpointsConfig", Map.of("hohenheim-wl-41", Map.of())));
        legitimate.put("HostConfig",
            new LinkedHashMap<>(Map.of("NetworkMode", "hohenheim-wl-41")));
        assertThat(legitimate.keySet())
            .as("step 1: this body really does exercise EVERY permitted create-body key")
            .containsExactlyInAnyOrderElementsOf(
                ContainerHardening.PERMITTED_BODY_KEYS.keySet());
        transport.lastCreateBody = null;
        docker.createContainer("body-permitted-all", legitimate, DockerContainerKind.HARDENING);
        assertThat(transport.lastCreateBody)
            .as("step 1: every permitted body key survives the funnel with its value intact")
            .isNotNull()
            .contains("\"User\":\"10041\"")
            .contains("hohenheim-wl-41")
            .contains("CMD-SHELL")
            .contains("no-new-privileges");

        // 2. THE OWNERSHIP CLAIM at this level: every other body key is refused BY NAME
        //    and never reaches the daemon -- including a made-up one standing in for
        //    whatever the next API version adds.
        Map<String, Object> refused = new LinkedHashMap<>();
        refused.put("Entrypoint", List.of("/bin/sh"));
        refused.put("MacAddress", "02:42:ac:11:00:02");
        refused.put("Hostname", "impersonator");
        refused.put("Domainname", "internal");
        refused.put("NetworkDisabled", Boolean.TRUE);
        refused.put("WorkingDir", "/");
        refused.put("StopSignal", "SIGKILL");
        refused.put("StopTimeout", 0);
        refused.put("Shell", List.of("/bin/sh", "-c"));
        refused.put("Volumes", Map.of("/anonymous", Map.of()));
        refused.put("Tty", Boolean.TRUE);
        refused.put("AttachStdin", Boolean.TRUE);
        refused.put("OnBuild", List.of("RUN true"));
        refused.put("ArgsEscaped", Boolean.TRUE);
        refused.put("SomeFutureDockerBodyKey", "whatever the next API version adds");

        for (Map.Entry<String, Object> probe : refused.entrySet()) {
            String key = probe.getKey();
            assertThat(ContainerHardening.PERMITTED_BODY_KEYS)
                .as("step 2: probe '" + key + "' must be a body key the policy does NOT permit")
                .doesNotContainKey(key);
            Map<String, Object> carrying = spec();
            carrying.put(key, probe.getValue());
            transport.lastCreateBody = null;
            assertThatThrownBy(() -> docker.createContainer("body-" + key.toLowerCase(Locale.ROOT),
                    carrying, DockerContainerKind.HARDENING))
                .as("step 2: create-body key " + key + " is not permitted and must be"
                    + " refused, not silently forwarded")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(key);
            assertThat(transport.lastCreateBody)
                .as("step 2: STATE, not just the throw -- " + key + " must not have reached"
                    + " the daemon")
                .isNull();
        }

        // 3. THE CASE FOLD, both directions: Go decodes the create body the same way it
        //    decodes HostConfig, so "entrypoint" is the same field and "image" is still
        //    the permitted one.
        Map<String, Object> foldedRefusal = spec();
        foldedRefusal.put("entrypoint", List.of("/bin/sh"));
        transport.lastCreateBody = null;
        assertThatThrownBy(() -> docker.createContainer("body-fold-refused", foldedRefusal,
                DockerContainerKind.HARDENING))
            .as("step 3: a lowercase entrypoint is the same field to the daemon")
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("entrypoint");
        assertThat(transport.lastCreateBody)
            .as("step 3: STATE -- the folded body escape never reached the daemon").isNull();

        Map<String, Object> foldedPermitted = new LinkedHashMap<>();
        foldedPermitted.put("image", "alpine:latest");
        foldedPermitted.put("cmd", List.of("true"));
        transport.lastCreateBody = null;
        docker.createContainer("body-fold-permitted", foldedPermitted,
            DockerContainerKind.HARDENING);
        assertThat(transport.lastCreateBody)
            .as("step 3: and a lowercase permitted key is recognised, never refused")
            .isNotNull();

        // 4. THE PROVEN BYPASS. A second EndpointsConfig entry attached the container to
        //    any existing network -- another tenant's per-workload network included -- and
        //    passed this funnel with no refusal at all before 2026-08-23.
        Map<String, Object> secondNetwork = spec();
        secondNetwork.put("HostConfig",
            new LinkedHashMap<>(Map.of("NetworkMode", "hohenheim-wl-41")));
        Map<String, Object> endpoints = new LinkedHashMap<>();
        endpoints.put("hohenheim-wl-41", Map.of());
        endpoints.put("hohenheim-wl-99", Map.of());
        secondNetwork.put("NetworkingConfig", Map.of("EndpointsConfig", endpoints));
        transport.lastCreateBody = null;
        assertThatThrownBy(() -> docker.createContainer("body-second-network", secondNetwork,
                DockerContainerKind.HARDENING))
            .as("step 4: a second network endpoint is the per-workload isolation bypassed"
                + " by a map entry, and must be refused")
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("EXACTLY")
            .hasMessageContaining("ONE network");
        assertThat(transport.lastCreateBody)
            .as("step 4: STATE -- the second attachment never reached the daemon").isNull();

        // 5. And the one endpoint it may carry is TIED to its own NetworkMode: attaching
        //    to somebody else's network instead is the same bypass wearing one entry.
        Map<String, Object> foreignNetwork = spec();
        foreignNetwork.put("HostConfig",
            new LinkedHashMap<>(Map.of("NetworkMode", "hohenheim-wl-41")));
        foreignNetwork.put("NetworkingConfig",
            Map.of("EndpointsConfig", Map.of("hohenheim-wl-99", Map.of())));
        transport.lastCreateBody = null;
        assertThatThrownBy(() -> docker.createContainer("body-foreign-network", foreignNetwork,
                DockerContainerKind.HARDENING))
            .as("step 5: the create-body attachment may only be the workload's OWN network")
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("hohenheim-wl-99");

        Map<String, Object> noMode = spec();
        noMode.put("NetworkingConfig",
            Map.of("EndpointsConfig", Map.of("hohenheim-wl-99", Map.of())));
        assertThatThrownBy(() -> docker.createContainer("body-no-mode", noMode,
                DockerContainerKind.HARDENING))
            .as("step 5: a container naming no network mode may attach to nothing at all")
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("no network at all");

        // 6. Endpoint OPTIONS are unreviewed and refused: a workload choosing its own
        //    address inside a subnet-keyed policy is the policy deciding nothing.
        Map<String, Object> staticAddress = spec();
        staticAddress.put("HostConfig",
            new LinkedHashMap<>(Map.of("NetworkMode", "hohenheim-wl-41")));
        staticAddress.put("NetworkingConfig", Map.of("EndpointsConfig",
            Map.of("hohenheim-wl-41", Map.of("IPAMConfig",
                Map.of("IPv4Address", "172.31.5.99")))));
        transport.lastCreateBody = null;
        assertThatThrownBy(() -> docker.createContainer("body-static-ip", staticAddress,
                DockerContainerKind.HARDENING))
            .as("step 6: endpoint options are not reviewed here and must be refused")
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("is not empty");
        assertThat(transport.lastCreateBody)
            .as("step 6: STATE -- no endpoint option reached the daemon").isNull();

        // 7. The fold again, on the structural rule this time: NetworkingConfig and
        //    EndpointsConfig are decoded case-insensitively like everything else.
        Map<String, Object> foldedEndpoints = spec();
        foldedEndpoints.put("HostConfig",
            new LinkedHashMap<>(Map.of("networkMode", "hohenheim-wl-41")));
        Map<String, Object> twoFolded = new LinkedHashMap<>();
        twoFolded.put("hohenheim-wl-41", Map.of());
        twoFolded.put("hohenheim-wl-99", Map.of());
        foldedEndpoints.put("networkingConfig", Map.of("endpointsConfig", twoFolded));
        transport.lastCreateBody = null;
        assertThatThrownBy(() -> docker.createContainer("body-fold-endpoints", foldedEndpoints,
                DockerContainerKind.HARDENING))
            .as("step 7: a folded NetworkingConfig is the same attachment to the daemon")
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("ONE network");
        assertThat(transport.lastCreateBody)
            .as("step 7: STATE -- no folded attachment reached the daemon").isNull();
    }

    /** Owner labels without a controller identity, which needs no datasource to PARSE. */
    private static Map<String, String> instanceLabels(int instanceId) {
        return Map.of(OwnerLabels.MODEL, InstanceModel.MODEL_ID.toString(),
            OwnerLabels.ID, String.valueOf(instanceId));
    }

    private static Map<String, Object> refusedProbes() {
        Map<String, Object> values = new LinkedHashMap<>();
        // The documented escapes the deny-list already named.
        values.put("Privileged", Boolean.TRUE);
        values.put("UsernsMode", "host");
        values.put("Devices", List.of());
        values.put("DeviceCgroupRules", List.of("c *:* rwm"));
        values.put("DeviceRequests", List.of());
        values.put("CgroupParent", "/");
        values.put("Sysctls", Map.of("kernel.shmmax", "1"));
        values.put("Binds", List.of("/:/host"));
        values.put("ReadonlyPaths", List.of());
        values.put("MaskedPaths", List.of());
        values.put("ReadonlyRootfs", Boolean.TRUE);
        // The keys the policy stamps itself: owned, therefore not a caller's to pass.
        values.put("CapAdd", List.of("SYS_ADMIN"));
        values.put("CapDrop", List.of());
        values.put("SecurityOpt", List.of("seccomp=unconfined"));
        values.put("PidsLimit", 0);
        values.put("LogConfig", Map.of("Type", "json-file", "Config", Map.of()));
        // The escape-capable keys the deny-list never named, which is why it was inverted.
        values.put("VolumesFrom", List.of("other-container"));
        values.put("Runtime", "runc-unconfined");
        values.put("GroupAdd", List.of("docker"));
        values.put("Tmpfs", Map.of("/scratch", "size=64g"));
        values.put("Ulimits", List.of(Map.of("Name", "nproc", "Soft", 1 << 20, "Hard", 1 << 20)));
        values.put("OomScoreAdj", -1000);
        values.put("ShmSize", 64L * 1024 * 1024 * 1024);
        values.put("StorageOpt", Map.of("size", "500G"));
        values.put("Isolation", "process");
        values.put("Annotations", Map.of("io.kubernetes.cri.untrusted-workload", "false"));
        // The whole point of an allow-list: a key nobody has heard of yet is refused too.
        values.put("SomeFutureDockerKey", "whatever the next API version adds");
        // Namespace keys that used to need their own value check and are now refused outright.
        values.put("PidMode", "host");
        values.put("IpcMode", "container:abc123");
        values.put("UTSMode", "host");
        values.put("CgroupnsMode", "host");
        return values;
    }

    private static Map<String, Object> spec() {
        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("Image", "alpine:latest");
        return spec;
    }

    /** Answers every request with a minimal create result and keeps the body it was sent. */
    private static final class RecordingTransport implements DockerTransport {

        private @Nullable String lastCreateBody;

        @Override
        public byte[] roundTrip(byte[] request, long timeoutMs) {
            return roundTrip(request, timeoutMs, Long.MAX_VALUE);
        }

        @Override
        public byte[] roundTrip(byte[] request, long timeoutMs, long maxResponseBytes) {
            String text = new String(request, StandardCharsets.ISO_8859_1);
            int headEnd = text.indexOf("\r\n\r\n");
            if (text.startsWith("POST /containers/create") && headEnd >= 0) {
                this.lastCreateBody = text.substring(headEnd + 4);
            }
            String body = "{\"Id\":\"fake-container\"}";
            return ("HTTP/1.1 201 Created\r\nContent-Type: application/json\r\n"
                + "Content-Length: " + body.length() + "\r\n\r\n" + body)
                .getBytes(StandardCharsets.ISO_8859_1);
        }
    }
}
