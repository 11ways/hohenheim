package be.elevenways.hohenheim.test.instance;

import be.elevenways.hohenheim.HohenheimEndpoints;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.HohenheimCapabilities;
import be.elevenways.hohenheim.server.incus.FakeVgaConsoleDaemon;
import be.elevenways.hohenheim.server.incus.IncusClient;
import be.elevenways.hohenheim.server.instance.OwnedInstances;
import be.elevenways.hohenheim.server.instance.VmScreens;
import be.elevenways.hohenheim.server.instance.VmSpice;
import be.elevenways.hohenheim.test.ApiSupport;
import be.elevenways.hohenheim.test.HardDeletes;
import be.elevenways.hohenheim.test.HohenheimTestBase;
import be.elevenways.hohenheim.test.Poll;
import be.elevenways.pepperglass.fixture.ScriptedSpiceServer;
import be.elevenways.pepperglass.link.Capabilities;
import be.elevenways.pepperglass.session.SessionOptions;
import be.elevenways.pepperglass.wire.ChannelType;
import be.elevenways.pepperglass.wire.DisplayMessages;
import be.elevenways.pepperglass.wire.InputsMessages;
import be.elevenways.pepperglass.wire.MainMessages;
import be.elevenways.pepperglass.wire.Rect;
import be.elevenways.pepperglass.wire.WireReader;
import be.elevenways.pepperglass.wire.WireWriter;
import be.elevenways.protoblast.common.thread.ExecutionContext;
import be.elevenways.zenit.auth.model.GrantSubjectType;
import be.elevenways.zenit.auth.model.UserModel;
import be.elevenways.zenit.auth.model.UserPrincipal;
import be.elevenways.zenit.auth.server.RecordGrants;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.kvm.common.ScreenMessage;
import be.elevenways.zenit.kvm.common.ScreenStatus;
import be.elevenways.zenit.kvm.test.support.RecordingScreenSocket;
import be.elevenways.zenit.server.http.SessionCookies;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.Cookie;
import org.junit.jupiter.api.Test;

import java.net.ServerSocket;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A VM's screen through the real console page, the real screen socket and the real SPICE path (Pepperglass against
 * a scripted SPICE server standing in for the VM): the viewer sees the screen and drives it, a second viewer only
 * watches until control is handed over, and a viewer whose grant is revoked loses the view; the socket admits exactly
 * the screen mode's audience; and through a faked Incus daemon, one console operation serves a VM's viewers up to the
 * cap and ends with the last of them or with a source that failed.
 *
 * @author Jelle De Loecker
 * @since 0.2.0
 */
class VmScreenJourneyTest extends HohenheimTestBase {

    private static final Duration WAIT = Duration.ofSeconds(15);

    /** Two production revalidation intervals, so a tick that just passed still gets its next one. */
    private static final double REVOKE_WAIT_MS = 2.5 * HohenheimEndpoints.TERMINAL_REVALIDATION_INTERVAL_MS;

    private static final int SCAN_A = 0x1E;
    private static final int SCAN_B = 0x30;

    private static final String SCREEN = "hh-vm-screen kvm-screen";

    /** The scripted VM: a 64x48 green screen, and every key and button it is sent. */
    private final BlockingQueue<Long> keys = new LinkedBlockingQueue<>();
    private final BlockingQueue<Long> presses = new LinkedBlockingQueue<>();

    @Test
    void aViewerSeesAndDrivesTheScreenAWatcherWaitsForControlAndRevocationEndsTheView() throws Exception {
        int instanceId = vm("screen-journey-vm", InstanceModel.STATUS_RUNNING);
        int tenantId = ApiSupport.user("screen-tenant@hohenheim.local", "Screen tenant");
        RecordGrants.grant(GrantSubjectType.USER, tenantId, InstanceModel.MODEL_ID, instanceId,
            HohenheimCapabilities.CONSOLE, true);
        VmSpice previous = VmSpice.SEAM.installed();
        BrowserContext tenantContext = null;
        try (ScriptedSpiceServer spice = this.spice()) {
            VmSpice.SEAM.install((serverName, handle, takeOver) -> VmSpice.Link.direct(SessionOptions.builder("127.0.0.1",
                spice.port())));

            // 1. The operator opens the screen mode: the VM's screen is drawn, and as its first viewer she drives it.
            navigateToApp("/admin/instances/" + instanceId + "/page/framebuffer");
            awaitRole(page, "controller");
            awaitGreen(page, "step 1: the VM's green screen is drawn in the operator's viewer");

            // 2. Her click and her key reach the VM.
            canvas(page).click();
            page.keyboard().press("KeyA");
            assertThat(this.presses.poll(WAIT.toSeconds(), TimeUnit.SECONDS))
                .as("step 2: the click pressed the left button in the VM").isEqualTo(1L);
            assertThat(this.awaitKey(SCAN_A)).as("step 2: the key A reached the VM").isTrue();

            // 3. A console delegate joins from the manage panel: he sees the same screen, but only watches, so his
            //    key never reaches the VM; he is offered to ask for control.
            tenantContext = browser.newContext();
            tenantContext.addCookies(List.of(new Cookie(SessionCookies.name(),
                sessionFor(tenantId).token()).setDomain("localhost").setPath("/")));
            Page tenant = tenantContext.newPage();
            tenant.navigate(baseUrl() + "/manage/instances/" + instanceId + "/page/framebuffer");
            awaitRole(tenant, "watcher");
            awaitGreen(tenant, "step 3: the watcher sees the VM's screen");
            canvas(tenant).click();
            tenant.keyboard().press("KeyB");
            Poll.never("step 3: a watcher's key reached the VM", Duration.ofSeconds(1),
                () -> this.keys.contains((long) SCAN_B));
            assertThat(tenant.locator("hh-vm-screen .hh-vm-screen-take").isVisible())
                .as("step 3: the watcher is offered to ask for control").isTrue();
            assertThat(tenant.locator("hh-vm-screen .hh-vm-screen-release").isVisible())
                .as("step 3: and has nothing to give back").isFalse();

            // 4. He asks; the operator is told and gives control back, and now his key reaches the VM.
            tenant.locator("hh-vm-screen .hh-vm-screen-take").click();
            awaitStatus(page, ScreenStatus.CONTROL_REQUESTED);
            page.locator("hh-vm-screen .hh-vm-screen-release").click();
            awaitRole(tenant, "controller");
            awaitRole(page, "watcher");
            canvas(tenant).click();
            tenant.keyboard().press("KeyB");
            assertThat(this.awaitKey(SCAN_B)).as("step 4: the new controller's key reached the VM").isTrue();

            // 5. His grant is revoked: the next revalidation closes his view, which says so.
            RecordGrants.revoke(GrantSubjectType.USER, tenantId, InstanceModel.MODEL_ID, instanceId,
                HohenheimCapabilities.CONSOLE);
            tenant.waitForFunction("([selector, token]) => document.querySelector(selector)"
                    + ".getAttribute('data-screen-status') === token",
                List.of(SCREEN, ScreenStatus.ACCESS_REVOKED.token()),
                new Page.WaitForFunctionOptions().setTimeout(REVOKE_WAIT_MS));
            awaitRole(page, "watcher");
        } finally {
            if (tenantContext != null) {
                tenantContext.close();
            }
            VmSpice.SEAM.restoreInstalled(previous);
            RecordGrants.revoke(GrantSubjectType.USER, tenantId, InstanceModel.MODEL_ID, instanceId,
                HohenheimCapabilities.CONSOLE);
            HardDeletes.byId(Models.get(InstanceModel.class), instanceId);
            Models.get(UserModel.class).delete(tenantId);
        }
    }

    @Test
    void theScreenSocketAdmitsExactlyTheScreenModesAudience() throws Exception {
        int viewerId = ApiSupport.user("screen-viewer@hohenheim.local", "Screen viewer");
        int consoleId = ApiSupport.user("screen-console@hohenheim.local", "Screen console");
        int running = vm("screen-gate-vm", InstanceModel.STATUS_RUNNING);
        int stopped = vm("screen-gate-stopped", InstanceModel.STATUS_STOPPED);
        int container = instance("screen-gate-container", "hohenheim:docker_container",
            InstanceModel.STATUS_RUNNING);
        int[] generated = new int[1];
        OwnedInstances.inScopeUnchecked("site", SiteModel.MODEL_ID, 424243,
            () -> generated[0] = vm("screen-gate-generated", InstanceModel.STATUS_RUNNING));
        List<int[]> grants = new ArrayList<>();
        VmSpice previous = VmSpice.SEAM.installed();
        try (ScriptedSpiceServer spice = this.spice()) {
            VmSpice.SEAM.install((serverName, handle, takeOver) -> VmSpice.Link.direct(SessionOptions.builder("127.0.0.1",
                spice.port())));
            for (int instanceId : new int[] {running, stopped, container, generated[0]}) {
                grants.add(new int[] {consoleId, instanceId});
                RecordGrants.grant(GrantSubjectType.USER, consoleId, InstanceModel.MODEL_ID, instanceId,
                    HohenheimCapabilities.CONSOLE, true);
            }
            grants.add(new int[] {viewerId, running});
            RecordGrants.grant(GrantSubjectType.USER, viewerId, InstanceModel.MODEL_ID, running,
                HohenheimCapabilities.VIEW, true);

            // 1. VIEW reaches the record but not its screen: refused as policy, without a frame.
            RecordingScreenSocket viewer = open(viewerId, running);
            assertThat(viewer.awaitClose()).as("step 1: a VIEW-only grantee is refused").isEqualTo(1008);
            assertThat(viewer.received()).as("step 1: without a single message").isEmpty();

            // 2. A container has no screen, and a product-generated VM's screen is its product's: both are refused
            //    exactly like a missing grant.
            assertThat(open(consoleId, container).awaitClose()).as("step 2: a container is refused").isEqualTo(1008);
            assertThat(open(consoleId, generated[0]).awaitClose()).as("step 2: a generated VM is refused")
                .isEqualTo(1008);

            // 3. A stopped VM is admitted but has no screen to show: it reads as unavailable and closes normally.
            RecordingScreenSocket idle = open(consoleId, stopped);
            idle.await(ScreenMessage.Status.class, status -> status.status() == ScreenStatus.UNAVAILABLE);
            assertThat(idle.awaitClose()).as("step 3: a normal close").isEqualTo(1000);

            // 4. CONSOLE alone opens a running VM's screen, which is drawn; revoked, the next revalidation refuses.
            RecordingScreenSocket console = open(consoleId, running);
            console.await(ScreenMessage.FrameEnd.class, end -> true);
            assertThat(console.picture().pixel(4, 4)).as("step 4: the VM's green screen").isEqualTo(0x00FF00);
            assertThat(console.handler().revalidate()).as("step 4: still granted").isTrue();
            RecordGrants.revoke(GrantSubjectType.USER, consoleId, InstanceModel.MODEL_ID, running,
                HohenheimCapabilities.CONSOLE);
            assertThat(console.handler().revalidate()).as("step 4: revoked, refused").isFalse();
            console.disconnect();
        } finally {
            VmSpice.SEAM.restoreInstalled(previous);
            for (int[] grant : grants) {
                RecordGrants.revoke(GrantSubjectType.USER, grant[0], InstanceModel.MODEL_ID, grant[1],
                    grant[0] == viewerId ? HohenheimCapabilities.VIEW : HohenheimCapabilities.CONSOLE);
            }
            for (int instanceId : new int[] {running, stopped, container}) {
                HardDeletes.byId(Models.get(InstanceModel.class), instanceId);
            }
            OwnedInstances.inScopeUnchecked("site", SiteModel.MODEL_ID, 424243,
                () -> HardDeletes.byId(Models.get(InstanceModel.class), generated[0]));
            Models.get(UserModel.class).delete(viewerId);
            Models.get(UserModel.class).delete(consoleId);
        }
    }

    @Test
    void oneConsoleServesAVmsViewersUpToItsCapAndEndsWithTheLastOneOrAFailedSource() throws Exception {
        int consoleId = ApiSupport.user("screen-console-op@hohenheim.local", "Screen console op");
        int instanceId = vm("screen-console-vm", InstanceModel.STATUS_RUNNING);
        RecordGrants.grant(GrantSubjectType.USER, consoleId, InstanceModel.MODEL_ID, instanceId,
            HohenheimCapabilities.CONSOLE, true);
        VmSpice previous = VmSpice.SEAM.installed();
        List<RecordingScreenSocket> viewers = new ArrayList<>();
        try (ScriptedSpiceServer spice = this.spice()) {
            FakeVgaConsoleDaemon daemon = new FakeVgaConsoleDaemon(spice.port());
            VmSpice.SEAM.install((serverName, handle, takeOver) -> VmSpice.incus(new IncusClient(daemon), handle, takeOver));

            // 1. Two viewers open the VM's screen at the same moment: both are drawn from ONE console operation,
            //    whose control websocket holds it open.
            CountDownLatch go = new CountDownLatch(1);
            List<Thread> openers = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                openers.add(Thread.ofPlatform().start(ExecutionContext.wrap(() -> {
                    try {
                        go.await();
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    RecordingScreenSocket opened = open(consoleId, instanceId);
                    synchronized (viewers) {
                        viewers.add(opened);
                    }
                })));
            }
            go.countDown();
            for (Thread opener : openers) {
                opener.join(WAIT.toMillis());
            }
            assertThat(viewers).as("step 1: both viewers opened").hasSize(2);
            for (RecordingScreenSocket viewer : viewers) {
                viewer.await(ScreenMessage.FrameEnd.class, end -> true);
                assertThat(viewer.picture().pixel(4, 4)).as("step 1: the VM's green screen").isEqualTo(0x00FF00);
            }
            assertThat(daemon.started()).as("step 1: one console for both viewers").isEqualTo(1);
            assertThat(daemon.controlLinked()).as("step 1: its control websocket linked")
                .containsExactly("/1.0/operations/op-vga-1");

            // 2. The display channel announced MJPEG and no codec a viewer would need WebCodecs for.
            Capabilities display = daemon.links().stream()
                .filter(link -> link.type() == ChannelType.DISPLAY).findFirst().orElseThrow().channelCapabilities();
            assertThat(display.has(Capabilities.DISPLAY_CODEC_MJPEG)).as("step 2: MJPEG announced").isTrue();
            assertThat(display.has(Capabilities.DISPLAY_CODEC_VP8) || display.has(Capabilities.DISPLAY_CODEC_VP9)
                || display.has(Capabilities.DISPLAY_CODEC_H264) || display.has(Capabilities.DISPLAY_CODEC_H265))
                .as("step 2: no WebCodecs codec announced").isFalse();

            // 3. The screen fills up to its cap; the next viewer reads FULL, is closed with 1013, and no second
            //    console is forced over the first.
            while (viewers.size() < VmScreens.MAX_VIEWERS) {
                RecordingScreenSocket more = open(consoleId, instanceId);
                more.await(ScreenMessage.Role.class, role -> true);
                viewers.add(more);
            }
            RecordingScreenSocket turnedAway = open(consoleId, instanceId);
            turnedAway.await(ScreenMessage.Status.class, status -> status.status() == ScreenStatus.FULL);
            assertThat(turnedAway.awaitClose()).as("step 3: closed as try again later").isEqualTo(1013);
            assertThat(daemon.started()).as("step 3: still one console").isEqualTo(1);

            // 4. The last viewer leaving ends the session, which closes the console's control websocket and
            //    cancels its operation: nothing is left running on the host.
            for (RecordingScreenSocket viewer : viewers) {
                viewer.disconnect();
            }
            Poll.until("step 4: the console operation ended with the last viewer", WAIT,
                () -> daemon.running().isEmpty());
            assertThat(daemon.requests).as("step 4: its operation was cancelled")
                .contains("DELETE /1.0/operations/op-vga-1 null");
        } finally {
            VmSpice.SEAM.restoreInstalled(previous);
        }

        int closedPort;
        try (ServerSocket probe = new ServerSocket(0)) {
            closedPort = probe.getLocalPort();
        }
        try {
            // 5. A console whose SPICE server cannot be reached fails its source: the viewer reads the screen as
            //    unavailable, and the console it started is ended all the same.
            FakeVgaConsoleDaemon unreachable = new FakeVgaConsoleDaemon(closedPort);
            VmSpice.SEAM.install((serverName, handle, takeOver) -> VmSpice.incus(new IncusClient(unreachable), handle, takeOver));
            RecordingScreenSocket failed = open(consoleId, instanceId);
            failed.await(ScreenMessage.Status.class, status -> status.status() == ScreenStatus.UNAVAILABLE);
            Poll.until("step 5: the failed console operation ended", WAIT, () -> unreachable.running().isEmpty());
            // A source that failed before the viewer joined is looked up once more, so one or two consoles.
            assertThat(unreachable.started()).as("step 5: its console had been started").isBetween(1, 2);
            assertThat(unreachable.controlLinked()).as("step 5: each held by its control websocket")
                .hasSize(unreachable.started());
        } finally {
            VmSpice.SEAM.restoreInstalled(previous);
            RecordGrants.revoke(GrantSubjectType.USER, consoleId, InstanceModel.MODEL_ID, instanceId,
                HohenheimCapabilities.CONSOLE);
            HardDeletes.byId(Models.get(InstanceModel.class), instanceId);
            Models.get(UserModel.class).delete(consoleId);
        }
    }

    // -----------------------------------------------------------------------

    /** A SPICE server offering a display and inputs: a 64x48 primary surface filled green, its input recorded. */
    private ScriptedSpiceServer spice() throws Exception {
        return ScriptedSpiceServer.builder()
            .channel(ChannelType.MAIN, channel -> {
                channel.send(MainMessages.INIT, ScriptedSpiceServer.mainInit(7));
                channel.expect(MainMessages.CLIENT_ATTACH_CHANNELS, WAIT, new ArrayList<>());
                channel.send(MainMessages.CHANNELS_LIST, new WireWriter().u32(2)
                    .u8(ChannelType.DISPLAY.wire()).u8(0).u8(ChannelType.INPUTS.wire()).u8(0).toByteArray());
                channel.drainUntilClosed();
            })
            .channel(ChannelType.DISPLAY, channel -> {
                channel.expect(DisplayMessages.CLIENT_INIT, WAIT, new ArrayList<>());
                channel.send(DisplayMessages.SURFACE_CREATE, new WireWriter().u32(0).u32(64).u32(48).u32(32).u32(1)
                    .toByteArray());
                WireWriter fill = new WireWriter().u32(0);
                new Rect(0, 0, 64, 48).write(fill);
                fill.u8(0).u8(1).u32(0x00FF00).u16(1 << 3).u8(0).u32(0).u32(0).u32(0);
                channel.send(DisplayMessages.DRAW_FILL, fill.toByteArray());
                channel.send(DisplayMessages.MARK, new byte[0]);
                channel.drainUntilClosed();
            })
            .channel(ChannelType.INPUTS, channel -> channel.untilClosed(frame -> {
                WireReader body = new WireReader(frame.data(), frame.offset(), frame.length());
                if (frame.type() == InputsMessages.CLIENT_KEY_DOWN) {
                    this.keys.add(body.u32());
                } else if (frame.type() == InputsMessages.CLIENT_MOUSE_PRESS) {
                    this.presses.add((long) body.u8());
                }
            }))
            .start();
    }

    private boolean awaitKey(int scancode) throws InterruptedException {
        long deadline = System.nanoTime() + WAIT.toNanos();
        while (System.nanoTime() < deadline) {
            Long key = this.keys.poll(100, TimeUnit.MILLISECONDS);
            if (key != null && key == scancode) {
                return true;
            }
        }
        return false;
    }

    private static RecordingScreenSocket open(int userId, int instanceId) {
        return RecordingScreenSocket.as(new UserPrincipal(userId, "Screen user " + userId)).autoAck(true)
            .with(HohenheimEndpoints.INSTANCE_ID, instanceId).open(HohenheimEndpoints.VM_FRAMEBUFFER);
    }

    private static Locator canvas(Page on) {
        return on.locator(SCREEN + " .pl-framebuffer-surface > canvas").first();
    }

    private static void awaitRole(Page on, String role) {
        on.waitForFunction("([selector, role]) => document.querySelector(selector)?.getAttribute('data-screen-role')"
            + " === role", List.of(SCREEN, role));
    }

    private static void awaitStatus(Page on, ScreenStatus status) {
        on.waitForFunction("([selector, token]) => document.querySelector(selector)"
            + "?.getAttribute('data-screen-status') === token", List.of(SCREEN, status.token()));
    }

    private static void awaitGreen(Page on, String message) {
        Object green = on.waitForFunction("selector => { const c = document.querySelector(selector"
            + " + ' .pl-framebuffer-surface > canvas'); if (!c || c.width < 8) return false;"
            + " const d = c.getContext('2d').getImageData(4, 4, 1, 1).data;"
            + " return d[0] < 30 && d[1] > 220 && d[2] < 30; }", SCREEN).jsonValue();
        assertThat(green).as(message).isEqualTo(true);
    }

    private static int vm(String name, String status) {
        return instance(name, "hohenheim:vm", status);
    }

    private static int instance(String name, String kind, String status) {
        Model instances = Models.get(InstanceModel.class);
        Row row = instances.createEmptyRow();
        row.set(InstanceModel.NAME, name);
        row.set(InstanceModel.KIND, kind);
        row.set(InstanceModel.SETTINGS, new LinkedHashMap<>(Map.of("image", "alpine/3.22/cloud")));
        row.set(InstanceModel.STATUS, status);
        instances.save(row);
        return row.get(InstanceModel.ID);
    }
}
