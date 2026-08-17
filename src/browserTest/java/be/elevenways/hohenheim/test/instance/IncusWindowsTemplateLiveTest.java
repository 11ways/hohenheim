package be.elevenways.hohenheim.test.instance;

import be.elevenways.hohenheim.server.ControllerScope;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.server.HohenheimDatabase;
import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.hohenheim.server.docker.OwnerLabels;
import be.elevenways.hohenheim.server.docker.ServerService;
import be.elevenways.hohenheim.server.incus.IncusClient;
import be.elevenways.hohenheim.server.incus.IncusKernelIsolation;
import be.elevenways.hohenheim.server.incus.IncusNetworkPolicy;
import be.elevenways.hohenheim.server.instance.IncusVmKind;
import be.elevenways.hohenheim.server.instance.InstanceService;
import be.elevenways.hohenheim.server.runtime.ContainerState;
import be.elevenways.hohenheim.server.runtime.InstallSupport;
import be.elevenways.hohenheim.server.runtime.InstanceSpec;
import be.elevenways.hohenheim.test.HohenheimTestBase;
import be.elevenways.hohenheim.test.host.LiveIncusHost;
import be.elevenways.hohenheim.test.live.LiveLane;
import be.elevenways.zenit.auth.AuthKeys;
import be.elevenways.zenit.auth.model.GrantSubjectType;
import be.elevenways.zenit.auth.model.UserModel;
import be.elevenways.zenit.auth.server.AuthCookieSupport;
import be.elevenways.zenit.auth.server.AuthModels;
import be.elevenways.zenit.auth.server.RecordGrants;
import be.elevenways.zenit.common.Zenit;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.session.Session;
import be.elevenways.zenit.common.validation.Violations;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.junit.jupiter.api.Tag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * The PREPARED-TEMPLATE origin end to end against a real Windows Server 2025 guest on a
 * live Incus host: an absent alias refused before the daemon sees a create, a Windows VM
 * provisioned from an operator-published image with Secure Boot ON and no cloud-init
 * lane, the hypervisor-side framebuffer rescue console attached WHILE THE GUEST IS STILL
 * BOOTING, the guest-agent capability honoured as an exec refusal, the guest's real boot
 * proven from the HOST (DHCP then RDP), isolation asserted in the host KERNEL with its
 * counterfactual, and destroy with verified reclaim.
 *
 * The console step deliberately precedes the boot wait. A rescue console asserted only
 * after the guest has booted and taken an address demonstrates nothing about the case it
 * exists for, so the ORDER is part of the claim.
 *
 * Two gotchas shape the whole class. There is NO incus guest agent for Windows, so
 * {@code incus exec} is impossible here forever: every probe comes from the daemon HOST
 * or from a product lane, never from inside the guest -- which is also why there is no
 * guest-side egress probe in step 8 and why kernel truth carries that clause instead.
 * And the ssh admin lane is enrolled deliberately: without it
 * {@link IncusKernelIsolation#available()} answers false for an https daemon and every
 * kernel-truth assertion below would run INERT (the 2026-08-06 inert-mechanism finding).
 *
 * SKIPS, never fails, when no live host is configured OR when the prepared image is not
 * published on it: the image is an operator fixture this repo cannot mint for itself.
 * See docs/prepare-windows-template.md.
 */
@Tag("slow") // live lane: needs a real daemon/host/image; runs via `zenit-dev test --all`
class IncusWindowsTemplateLiveTest extends HohenheimTestBase {

    private static final String HOST = "live-incus-windows";

    /** The operator-published prepared image (docs/prepare-windows-template.md). */
    private static final String PREPARED_ALIAS = "win2025-core";

    private static LiveIncusHost remote;
    private static String enrolledFingerprint;

    /**
     * AIDEV-NOTE: the ssh ADMIN lane is enrolled here on purpose (see the class
     * docblock): without it every kernel-truth assertion in step 8 runs INERT.
     */
    @BeforeAll
    static void setUp() {
        remote = LiveIncusHost.configured();
        LiveLane.require(LiveLane.Need.INCUS_HOST, remote != null,
            "no live incus host enrolled at " + LiveIncusHost.CONFIG);
        LiveLane.require(LiveLane.Need.INCUS_HOST, preparedImagePublished(),
            "prepared image '" + PREPARED_ALIAS + "' is not published on the live host;"
            + " build it with docs/prepare-windows-template.md");

        // AIDEV-NOTE: shared-server classes share ONE database per JVM fork, so ids
        // restart at 1 only per FORK, not per class -- but a PARALLEL fork's own fresh
        // database also restarts at 1, and both forks would mint "hohenheim-instance-1"
        // on the SAME live daemon. VmFramebufferConsoleLiveTest omits this call and can
        // collide; this class does not repeat that.

        enrolledFingerprint = remote.enrollThroughProduct(HOST, "hohenheim-live-win");
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
                System.out.println("=== cleanup: trust entry removed");
            } catch (IOException ignored) {
                // nothing enrolled, nothing to remove
            }
        }
    }

    @Test
    void windowsFromAPreparedTemplateThroughTheSameDriver() throws Exception {
        Row host = Models.get(ServerModel.class).findByName(HOST);
        int hostId = host.get(ServerModel.ID);

        IncusClient incus = new ServerService().incusClientFor(HOST);
        InstanceService service = new InstanceService();

        // AIDEV-NOTE: only ONE of these three ever runs; the other two exist to carry
        // settings (an absent alias refused before create, an agent-less spec built but
        // never deployed). They are sized small ON PURPOSE, because host capacity is
        // booked on a record's EXISTENCE rather than on its running state -- a stopped
        // guest can be started without asking anyone, so a control plane that only booked
        // running workloads would happily accept guests it then could not start. Three
        // 2 GB records on this 3907 MB host is real over-subscription and the ledger
        // refuses it correctly (observed: host_capacity_reached needed=2048 free=1280).
        int absentId = windowsRecord("win-absent-alias", hostId, "no-such-prepared-alias", 512);
        int id = windowsRecord("win-prepared", hostId, PREPARED_ALIAS);
        int agentlessId = windowsRecord("win-agentless", hostId, PREPARED_ALIAS, 512);
        String absentHandle = ControllerScope.handle(ControllerScope.KIND_INSTANCE, absentId);
        String handle = ControllerScope.handle(ControllerScope.KIND_INSTANCE, id);
        String agentlessHandle = ControllerScope.handle(ControllerScope.KIND_INSTANCE, agentlessId);
        int userId = user("win-live");
        WebSocket ws = null;

        try {
            // 1. An absent PREPARED alias is refused against the REAL image store, by
            //    name, BEFORE the daemon is asked to create anything. Publishing is per
            //    daemon, so "the alias is simply not on this host" is the common case and
            //    a generic create failure would hide it.
            Throwable absent = catchThrowable(() -> service.deploy(absentId));
            assertThat(absent)
                .as("step 1: an absent prepared alias is refused, naming alias and host")
                .isInstanceOf(Violations.class)
                .hasMessageContaining("no-such-prepared-alias")
                .hasMessageContaining(HOST)
                .hasMessageContaining("never fetched");
            assertThat(remote.instanceInfoOrError(absentHandle))
                .as("step 1: and the daemon holds NO workload under that handle")
                .contains("ERROR");

            // 2. The real prepared Windows image, through the ordinary product funnel --
            //    the SAME driver and the same deploy seam the cloud-init Linux tier uses.
            assertThat(service.deploy(id).state())
                .as("step 2: the Windows VM deploys and reports RUNNING")
                .isEqualTo(ContainerState.RUNNING);
            Map<String, Object> definition = instanceOf(incus, handle);
            assertThat(String.valueOf(definition.get("type")))
                .as("step 2: the daemon holds a VIRTUAL MACHINE")
                .isEqualTo("virtual-machine");

            // 3. The DECLARED capabilities landed at the daemon. secure_boot is the one
            //    that would read "false" if the old hardcoded value had survived, and
            //    Microsoft-signed media is exactly the case that value was wrong for.
            Map<?, ?> config = (Map<?, ?>) definition.get("config");
            assertThat(config.get("security.secureboot"))
                .as("step 3: the image's OWN Secure Boot declaration landed, not the"
                    + " catalog default")
                .isEqualTo("true");
            assertThat(config.get("cloud-init.user-data"))
                .as("step 3: a prepared image declares no cloud-init lane, so the driver"
                    + " wrote none")
                .isNull();
            OwnerLabels.of(InstanceModel.MODEL_ID, id).forEach((key, value) ->
                assertThat(config.get("user." + key))
                    .as("step 3: owner label %s stamped at create", key)
                    .isEqualTo(value));

            // 4. Image identity: the record pins the daemon's RESOLVED fingerprint, and it
            //    is the prepared alias's own target -- which also exercises the new
            //    IncusClient image-alias lookup against a real daemon.
            String aliasTarget = incus.imageFingerprintForAlias(PREPARED_ALIAS);
            assertThat(aliasTarget)
                .as("step 4: the daemon resolves the prepared alias")
                .isNotNull().matches("[0-9a-f]{64}");
            assertThat(Models.get(InstanceModel.class).findById(id)
                    .get(InstanceModel.IMAGE_FINGERPRINT))
                .as("step 4: the record pins the prepared image's resolved fingerprint")
                .isEqualTo(String.valueOf(config.get("volatile.base_image")))
                .isEqualTo(aliasTarget);

            // 5. THE FRAMEBUFFER RESCUE CONSOLE, ATTACHED WHILE WINDOWS IS STILL BOOTING.
            //    It is asserted HERE, before step 7 waits for a DHCP lease, and the order
            //    is the claim: a console that only works once the guest has finished
            //    booting and taken an address proves nothing about the case it exists for.
            //    Hypervisor-side by construction, and the ONLY way in when a Windows guest
            //    has no drivers, no network or a failed boot -- exactly what RDP cannot
            //    observe.
            RecordGrants.grant(GrantSubjectType.USER, userId, InstanceModel.MODEL_ID, id,
                HohenheimAccess.MANAGE, true);
            RecordingClient client = new RecordingClient();
            ws = HttpClient.newHttpClient().newWebSocketBuilder()
                .header("Cookie", AuthCookieSupport.sessionCookieName() + "=" + sessionFor(userId).token())
                .buildAsync(URI.create("ws://localhost:" + getServerPort()
                    + "/ws/instance-framebuffer/" + id), client)
                .join();
            assertThat(ipv4Of(incus, handle))
                .as("step 5: the guest has NOT taken an address yet, so this frame cannot"
                    + " be coming from a booted guest")
                .isNull();
            assertThat(client.binaryArrived.await(60, TimeUnit.SECONDS))
                .as("step 5: a VGA framebuffer frame reaches the granted tenant")
                .isTrue();
            byte[] frame = client.lastBinary.get();
            assertThat(frame).as("step 5: the frame carries bytes").isNotNull().isNotEmpty();
            assertThat(new String(frame, 1, 3, StandardCharsets.US_ASCII))
                .as("step 5: and it is a PNG image").isEqualTo("PNG");

            // 6. THE GUEST-AGENT CAPABILITY IS HONOURED LIVE: an exec-driven install
            //    REFUSES BY NAME rather than burning the 600s agent-ready window and
            //    reporting a timeout as if the guest were broken. Driven through the
            //    runtime the KIND ITSELF builds, with the spec the kind produces from
            //    these settings. Its own record, never deployed, so the
            //    refusal-before-create claim is measurable.
            IncusVmKind kind = new IncusVmKind();
            InstanceSpec agentless = kind.specFor(agentlessId, windowsSettings(PREPARED_ALIAS, 512));
            InstallSupport installSupport = (InstallSupport) kind.runtimeFor(HOST);
            assertThat(agentless.guestAgent())
                .as("step 6: the kind read guest_agent=false off the settings").isFalse();
            assertThat(catchThrowable(() -> installSupport.runInstall(agentless,
                    agentless.image(), "echo hi", Map.of(), 5_000)))
                .as("step 6: an agent-less image refuses the exec-driven install by name")
                .isInstanceOf(IOException.class)
                .hasMessageContaining(agentlessHandle)
                .hasMessageContaining("guest_agent=false");
            assertThat(remote.instanceInfoOrError(agentlessHandle))
                .as("step 6: and refused BEFORE create -- no second workload was born to"
                    + " be torn down again")
                .contains("ERROR");

            // 7. THE GUEST REALLY BOOTED, proven ENTIRELY from the host because this
            //    tier has no exec. A DHCP lease means Windows came up and bound the
            //    injected virtio NIC; RDP accepting a connection is the prepared
            //    template's own DECLARED readiness (RDP pre-enabled).
            String[] guestIp = new String[1];
            awaitTrue("the Windows guest takes a DHCP lease", 600_000, () -> {
                guestIp[0] = ipv4Of(incus, handle);
                return guestIp[0] != null;
            });
            awaitTrue("the Windows guest accepts RDP on 3389", 600_000,
                () -> tcpOpen(guestIp[0], 3389));
            assertThat(tcpOpen(guestIp[0], 3389))
                .as("step 7: the prepared template's RDP is reachable at %s", guestIp[0])
                .isTrue();
            // AIDEV-NOTE: RDP is GUEST-side readiness and is NOT a substitute for the
            // hypervisor-side rescue console -- step 5 covers the case where the guest
            // has no drivers/network/boot at all, which RDP cannot observe.

            // 8. ISOLATION IN THE KERNEL on the host it landed on, with its counterfactual
            //    run in place.
            //
            //    AIDEV-NOTE: there is deliberately NO guest-side egress probe here, and
            //    that is a consequence of the declared capability rather than an
            //    oversight. Isolation is EGRESS-only and one-sided (IncusNetworkPolicy
            //    sets the ingress default to allow), so every existing VM-tier probe
            //    pings FROM the guest -- which needs the agent this tier declares absent.
            //    Kernel truth is precisely the layer that does not need the guest.
            Map<?, ?> nic = (Map<?, ?>) ((Map<?, ?>) definition.get("devices")).get("eth0");
            assertThat(nic.get("security.acls"))
                .as("step 8: the Windows VM's NIC carries the shared isolation ACL")
                .isEqualTo(IncusNetworkPolicy.aclName());
            assertThat(remote.query("/1.0/network-acls/" + IncusNetworkPolicy.aclName()))
                .as("step 8: the daemon's ACL rejects the private and metadata ranges")
                .contains("10.0.0.0/8").contains("169.254.0.0/16")
                .contains("192.168.0.0/16").contains("fc00::/7");

            IncusKernelIsolation kernel = IncusKernelIsolation.forServer(
                Models.get(ServerModel.class).findByName(HOST));
            assertThat(kernel.available())
                .as("step 8: this host's kernel is readable, so the Windows tier's"
                    + " isolation is VERIFIED and not merely configured")
                .isTrue();
            assertThat(missingKernelRules(kernel, handle))
                .as("step 8: the kernel carries every tenant-range block for the live tap")
                .isEmpty();

            // 8b. The counterfactual: break it exactly the way incusd breaks it and
            //     require the verifier to notice while every daemon-side fact still reads
            //     back perfectly.
            dropVmChains(handle);
            assertThat(missingKernelRules(kernel, handle))
                .as("step 8b: kernel truth catches a real isolation loss, naming the tap")
                .isNotEmpty();
            assertThat(String.valueOf(instanceOf(incus, handle).get("devices")))
                .as("step 8b: while the daemon's own config STILL claims isolation -- the"
                    + " divergence this mechanism exists for")
                .contains(IncusNetworkPolicy.aclName());

            // 8c. Repaired through the product's own per-instance lever.
            enforceKernel(kernel, handle);
            assertThat(missingKernelRules(kernel, handle))
                .as("step 8c: the repair restored the kernel rules for the live tap")
                .isEmpty();

            // 9. Destroy, with the daemon asked whether the workload is really gone.
            service.destroy(id);
            awaitTrue("the daemon forgets the destroyed handle", 120_000,
                () -> instanceInfoOrError(handle).contains("ERROR"));
            assertThat(instanceInfoOrError(handle))
                .as("step 9: the daemon no longer knows the handle after destroy")
                .contains("ERROR");
        } finally {
            if (ws != null) {
                ws.abort();
            }
            for (int record : List.of(id, agentlessId, absentId)) {
                try {
                    service.destroy(record);
                } catch (RuntimeException ignored) {
                    // best effort; forceDelete below is the daemon-truth cleanup
                }
            }
            remote.forceDelete(handle);
            remote.forceDelete(agentlessHandle);
            remote.forceDelete(absentHandle);
            RecordGrants.revoke(GrantSubjectType.USER, userId, InstanceModel.MODEL_ID, id,
                HohenheimAccess.MANAGE);
            for (int record : List.of(id, agentlessId, absentId)) {
                Models.get(InstanceModel.class).delete(record);
            }
            AuthModels.users().delete(userId);
            // Give back both working credentials borrowed from a real machine, and PRINT
            // the outcome: a cleanup that fails silently leaves root access behind.
            System.out.println("=== cleanup: shared objects -> "
                + remote.releaseControllerSharedObjects());
            System.out.println("=== cleanup: authorized_keys -> "
                + remote.releaseAuthorizedKeys());
            try {
                remote.removeTrustEntry(enrolledFingerprint);
                System.out.println("=== cleanup: trust entry removed");
            } catch (IOException ignored) {
                // nothing enrolled, nothing to remove
            }
        }
    }

    // -- plumbing -------------------------------------------------------------

    /**
     * Whether the operator fixture exists, read over the host's OWN cli before anything
     * is enrolled -- so a missing image SKIPS without leaving trust behind.
     */
    private static boolean preparedImagePublished() {
        try {
            return remote.query("/1.0/images/aliases/" + PREPARED_ALIAS).contains("target");
        } catch (IOException absent) {
            return false;
        }
    }

    private static Map<String, Object> windowsSettings(String alias) {
        return windowsSettings(alias, 2048);
    }

    /**
     * @param memoryMb what this record is BOOKED at on the host's memory budget, which is
     *        also the cap the daemon applies
     */
    private static Map<String, Object> windowsSettings(String alias, int memoryMb) {
        Map<String, Object> settings = new LinkedHashMap<>();
        settings.put("image", alias);
        settings.put("image_origin", "prepared");
        settings.put("secure_boot", true);
        settings.put("guest_agent", false);
        settings.put("memory_limit_mb", memoryMb);
        return settings;
    }

    private static int windowsRecord(String name, int hostId, String alias) {
        return windowsRecord(name, hostId, alias, 2048);
    }

    private static int windowsRecord(String name, int hostId, String alias, int memoryMb) {
        Model instances = Models.get(InstanceModel.class);
        Row row = instances.createEmptyRow();
        row.set(InstanceModel.NAME, name);
        row.set(InstanceModel.KIND, IncusVmKind.ID.toString());
        row.set(InstanceModel.SETTINGS, windowsSettings(alias, memoryMb));
        row.set(InstanceModel.SERVER_ID, hostId);
        instances.save(row);
        return row.get(InstanceModel.ID);
    }

    private static int user(String label) {
        Row row = AuthModels.users().createEmptyRow();
        row.set(UserModel.EMAIL, label + "@hohenheim.local");
        row.set(UserModel.DISPLAY_NAME, "Windows " + label);
        row.set(UserModel.ENABLED, true);
        row.set(UserModel.CREATED_AT, Instant.now());
        row.set(UserModel.UPDATED_AT, Instant.now());
        AuthModels.users().save(row);
        return row.get(UserModel.ID);
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

    /**
     * Whether a TCP connect from the DAEMON HOST succeeds. The only reachability shape
     * available to this tier: there is no exec into a Windows guest.
     */
    private static boolean tcpOpen(String ip, int port) {
        try {
            remote.hostCommand("timeout", "3", "bash", "-c",
                "'</dev/tcp/" + ip + "/" + port + "'");
            return true;
        } catch (IOException refused) {
            return false;
        }
    }

    private static Map<String, Object> instanceOf(IncusClient incus, String handle) {
        try {
            return incus.instance(handle);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static String instanceInfoOrError(String handle) {
        try {
            return remote.instanceInfoOrError(handle);
        } catch (IOException e) {
            return "ERROR: " + e.getMessage();
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
        } catch (IOException notYet) {
            return null;
        }
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
                // a chain the daemon never made is not an error; 8b measures the outcome
            }
        }
    }

    /** Records the framebuffer frames a real viewer socket receives. */
    private static final class RecordingClient implements WebSocket.Listener {

        private final CountDownLatch binaryArrived = new CountDownLatch(1);
        private final AtomicReference<byte[]> lastBinary = new AtomicReference<>();
        private final AtomicInteger closeCode = new AtomicInteger(-1);

        @Override
        public void onOpen(WebSocket webSocket) {
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onBinary(WebSocket webSocket, ByteBuffer data, boolean last) {
            byte[] bytes = new byte[data.remaining()];
            data.get(bytes);
            this.lastBinary.set(bytes);
            this.binaryArrived.countDown();
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            this.closeCode.set(statusCode);
            return null;
        }
    }
}
