package be.elevenways.hohenheim.test.docker;

import be.elevenways.hohenheim.server.docker.ContainerHardening;
import be.elevenways.hohenheim.server.docker.DockerClient;
import be.elevenways.hohenheim.server.docker.DockerTransport;
import be.elevenways.hohenheim.server.instance.DockerContainerKind;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The keys {@code ContainerHardening} OWNS, pinned at the WIRE with no daemon: a spec
 * carrying one never reaches {@code POST /containers/create}.
 *
 * The live sibling ({@code ContainerHardeningTest}) proves the same refusals against a
 * real socket and reads the surviving container's kernel state back, which is the
 * stronger claim -- and it is slow-tagged, so on an ordinary run NOTHING guarded this
 * list. That is the gap this class closes: the OWNERSHIP half needs no daemon, so it
 * belongs in the default lane where a careless edit to the list is caught the same day.
 *
 * AIDEV-NOTE: the list is asserted ENTRY BY ENTRY from the constant itself rather than
 * from a copy, so adding a key without a value shape here fails loudly instead of going
 * unexercised. {@code ReadonlyRootfs} carries the extra assertion, because it is the one
 * entry that is a RECORDED DECISION rather than a refused escape -- see the note on
 * ESCAPE_KEYS and the struck clause in docs/instance-tier-plan.md.
 */
class ContainerEscapeKeyTest {

    /** A plausible value per owned key: a refusal must not depend on the value's shape. */
    private static final Map<String, Object> SMUGGLED = smuggled();

    @Test
    void everyKeyThePolicyOwnsIsRefusedAtTheFunnelAndNeverReachesTheDaemon() throws Exception {
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

        // 2. THE OWNERSHIP CLAIM: every key in the declared list is refused BY NAME, and
        //    the daemon is never contacted. Driven off the constant, so a key added
        //    without a case here is a failure rather than a silent omission.
        for (String key : ContainerHardening.ESCAPE_KEYS) {
            Object value = SMUGGLED.get(key);
            assertThat(value)
                .as("step 2: ESCAPE_KEYS entry '" + key + "' has no probe value in this"
                    + " test -- add one so the new key is actually exercised")
                .isNotNull();
            Map<String, Object> carrying = spec();
            carrying.put("HostConfig", new LinkedHashMap<>(Map.of(key, value)));
            transport.lastCreateBody = null;
            assertThatThrownBy(() -> docker.createContainer("escape-" + key.toLowerCase(),
                    carrying, DockerContainerKind.HARDENING))
                .as("step 2: HostConfig." + key + " is owned by the policy and must be"
                    + " refused, not silently overwritten")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("HostConfig." + key);
            assertThat(transport.lastCreateBody)
                .as("step 2: STATE, not just the throw -- HostConfig." + key + " must not"
                    + " have reached the daemon")
                .isNull();
        }

        // 3. READ-ONLY ROOTFS, the recorded decision. The Phase 3 clause offers it "where
        //    the template allows"; no template in this tier allows it (published images
        //    chown, write pid files and unpack into their own root), so the policy does
        //    not ship it -- and the honest form of "we decided not to" is OWNING the key
        //    rather than leaving it unmentioned for a later caller to set.
        assertThat(ContainerHardening.ESCAPE_KEYS)
            .as("step 3: ReadonlyRootfs is owned, so the single-funnel doctrine covers it")
            .contains("ReadonlyRootfs");
        Map<String, Object> plain = new LinkedHashMap<>();
        plain.put("Image", "alpine:latest");
        ContainerHardening.applyTo(plain, DockerContainerKind.HARDENING);
        assertThat(plain.get("HostConfig"))
            .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
            .as("step 3: and the policy asserts NOTHING about the rootfs itself -- a"
                + " ReadonlyRootfs:false would be a claim we do not make either")
            .doesNotContainKey("ReadonlyRootfs");

        // 4. And the refusal is a property of the FUNNEL, not of a profile: the tightest
        //    profile there is refuses exactly the same key.
        Map<String, Object> underStrict = spec();
        underStrict.put("HostConfig",
            new LinkedHashMap<>(Map.of("ReadonlyRootfs", Boolean.TRUE)));
        transport.lastCreateBody = null;
        assertThatThrownBy(() -> docker.createContainer("escape-strict", underStrict,
                ContainerHardening.STRICT))
            .as("step 4: no profile is a way around an owned key")
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("HostConfig.ReadonlyRootfs");
        assertThat(transport.lastCreateBody)
            .as("step 4: and nothing reached the daemon under STRICT either").isNull();

        // 5. THE CASE FOLD. Docker unmarshals HostConfig with Go's encoding/json, which
        //    prefers an exact field match but ACCEPTS a case-insensitive one -- so
        //    "privileged" and "capAdd" are honoured by the daemon exactly like their
        //    canonical spellings. A case-sensitive guard therefore sees an unknown key and
        //    waves the escape straight through. Every owned key is probed in two
        //    non-canonical spellings.
        for (String key : ContainerHardening.ESCAPE_KEYS) {
            for (String spelling : java.util.List.of(key.toLowerCase(java.util.Locale.ROOT),
                    Character.toLowerCase(key.charAt(0)) + key.substring(1))) {
                if (spelling.equals(key)) {
                    continue;
                }
                Map<String, Object> carrying = spec();
                carrying.put("HostConfig",
                    new LinkedHashMap<>(Map.of(spelling, SMUGGLED.get(key))));
                transport.lastCreateBody = null;
                assertThatThrownBy(() -> docker.createContainer("escape-fold",
                        carrying, DockerContainerKind.HARDENING))
                    .as("step 5: HostConfig." + spelling + " is the SAME field to the daemon"
                        + " as " + key + ", so the funnel must refuse it too")
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("HostConfig." + key);
                assertThat(transport.lastCreateBody)
                    .as("step 5: STATE -- the mixed-case " + spelling + " must not have"
                        + " reached the daemon")
                    .isNull();
            }
        }

        // 6. The same fold covers the namespace keys and their VALUES ("HOST" is the same
        //    string to the daemon's mode parser), and the bind-mount refusal, whose Type
        //    and Source members are decoded by the same case-insensitive unmarshaller.
        Map<String, Object> lowercaseNamespace = spec();
        lowercaseNamespace.put("HostConfig",
            new LinkedHashMap<>(Map.of("networkMode", "HOST")));
        transport.lastCreateBody = null;
        assertThatThrownBy(() -> docker.createContainer("escape-ns", lowercaseNamespace,
                DockerContainerKind.HARDENING))
            .as("step 6: a lowercase networkMode carrying an uppercase HOST is still a"
                + " host-namespace share")
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("HostConfig.NetworkMode");

        Map<String, Object> joiningNamespace = spec();
        joiningNamespace.put("HostConfig",
            new LinkedHashMap<>(Map.of("pidMode", "Container:abc123")));
        assertThatThrownBy(() -> docker.createContainer("escape-join", joiningNamespace,
                DockerContainerKind.HARDENING))
            .as("step 6: and a capitalised Container: prefix still joins another"
                + " container's namespace")
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("HostConfig.PidMode");

        Map<String, Object> lowercaseMounts = spec();
        lowercaseMounts.put("HostConfig", new LinkedHashMap<>(Map.of(
            "mounts", java.util.List.of(Map.of("type", "Bind", "source", "/")))));
        transport.lastCreateBody = null;
        assertThatThrownBy(() -> docker.createContainer("escape-mount", lowercaseMounts,
                DockerContainerKind.HARDENING))
            .as("step 6: a lowercase mounts entry with a capitalised Bind type is the same"
                + " host bind to the daemon")
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("bind mount");
        assertThat(transport.lastCreateBody)
            .as("step 6: STATE -- no folded escape reached the daemon at all").isNull();
    }

    private static Map<String, Object> smuggled() {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("Privileged", Boolean.TRUE);
        values.put("CapAdd", java.util.List.of("SYS_ADMIN"));
        values.put("CapDrop", java.util.List.of());
        values.put("SecurityOpt", java.util.List.of("seccomp=unconfined"));
        values.put("PidsLimit", 0);
        values.put("UsernsMode", "host");
        values.put("Devices", java.util.List.of());
        values.put("DeviceCgroupRules", java.util.List.of("c *:* rwm"));
        values.put("DeviceRequests", java.util.List.of());
        values.put("CgroupParent", "/");
        values.put("Sysctls", Map.of("kernel.shmmax", "1"));
        values.put("Binds", java.util.List.of("/:/host"));
        values.put("ReadonlyPaths", java.util.List.of());
        values.put("MaskedPaths", java.util.List.of());
        values.put("ReadonlyRootfs", Boolean.TRUE);
        values.put("LogConfig", Map.of("Type", "json-file", "Config", Map.of()));
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
