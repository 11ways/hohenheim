package be.elevenways.hohenheim.test;

import be.elevenways.hohenheim.HohenheimEndpoints;
import be.elevenways.hohenheim.server.HohenheimDatabase;
import be.elevenways.hohenheim.server.HohenheimSettingsFiles;
import be.elevenways.hohenheim.server.ServerMain;
import be.elevenways.zenit.auth.AuthSettings;
import be.elevenways.zenit.auth.server.ZenitAuth;
import be.elevenways.zenit.cms.common.panel.PanelRegistry;
import be.elevenways.zenit.common.Zenit;
import be.elevenways.zenit.common.websocket.WebSocketHandler;
import be.elevenways.zenit.server.ServerZenitRuntime;
import be.elevenways.zenit.server.setting.ServerSettings;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The window between binding the HTTP listener and installing the host wiring:
 * a request that arrives the instant the listener accepts must already be
 * served by real handlers, panels and WebSocket handler factories.
 *
 * The probe runs in a boot stage at weight 49 -- immediately AFTER the STARTHTTP
 * stage (weight 50) binds and before anything else -- and issues real requests,
 * so it observes exactly what the first client of a fresh process observes.
 * Production used to install all of this after {@code ServerZenitRuntime.main()}
 * returned, i.e. after this point, and the browser suite hid that by wiring in
 * its own safer order.
 */
// Solo: it appends children to the process-wide Zenit.ROOT_STAGE (no removal exists) and
// calls ServerZenitRuntime.stop() in a finally, which shuts the boot executor and the task
// runtime for the rest of the JVM.
@Tag("solo")
class BootWiringWindowTest {

    @Test
    void everyRequestFacingWiringExistsWhenTheListenerAccepts() throws Exception {
        int port = freePort();

        // 1. Exactly ServerMain's pre-boot sequence, up to (not including) the
        //    ServerZenitRuntime call that launches the boot stages.
        File settingsDry = File.createTempFile("hohenheim-boot-window", ".dry");
        settingsDry.delete();
        settingsDry.deleteOnExit();
        System.setProperty("hohenheim.settings", settingsDry.getAbsolutePath());
        HohenheimSettingsFiles.load();

        HohenheimEndpoints.init();
        HohenheimTestRuntime.declareAccessModelsOnce();
        TestDatabases.freshDatabase();

        ZenitAuth.init(HohenheimDatabase.datasource());
        AuthSettings.VALUES.setValue(AuthSettings.CMS_AUTO_PANEL, false);
        ServerMain.installAuthBaselines();

        // 2. Bind for real: this test is about what the listener serves, so the
        //    STARTHTTP stage must actually run. Pinned from a boot stage rather
        //    than here, because init() reloads the settings sources synchronously
        //    before launching any stage.
        Zenit.ROOT_STAGE.addChildStage("boot-window-pin", () -> {
            ServerSettings.VALUES.setValue(ServerSettings.Network.PORT, port);
            ServerSettings.VALUES.setValue(ServerSettings.Network.AUTO_START_HTTP, true);
        }).setWeight(400);

        AtomicReference<String> healthStatus = new AtomicReference<>("<probe never ran>");
        AtomicReference<String> healthBody = new AtomicReference<>("<probe never ran>");
        AtomicReference<String> plainHealthStatus = new AtomicReference<>("<probe never ran>");
        AtomicReference<String> plainHealthBody = new AtomicReference<>("<probe never ran>");
        AtomicReference<String> adminPanel = new AtomicReference<>("<probe never ran>");
        AtomicReference<String> devTunnelHandler = new AtomicReference<>("<probe never ran>");
        AtomicReference<String> managePanel = new AtomicReference<>("<probe never ran>");
        AtomicReference<Throwable> probeFailure = new AtomicReference<>();

        Zenit.ROOT_STAGE.addChildStage("boot-window-probe", () -> {
            try {
                HttpClient client = HttpClient.newBuilder()
                    .followRedirects(HttpClient.Redirect.NEVER)
                    .build();
                HttpResponse<String> health = client.send(
                    HttpRequest.newBuilder().uri(URI.create("http://127.0.0.1:" + port + "/api/health"))
                        .GET().build(),
                    HttpResponse.BodyHandlers.ofString());
                healthStatus.set(String.valueOf(health.statusCode()));
                healthBody.set(health.body());
                // The conventional path a monitor and the deploy probes ask, which must be
                // public too: a 302 to /login here is what every swap script used to read
                // as "not 000, so alive".
                HttpResponse<String> plain = client.send(
                    HttpRequest.newBuilder().uri(URI.create("http://127.0.0.1:" + port + "/health"))
                        .GET().build(),
                    HttpResponse.BodyHandlers.ofString());
                plainHealthStatus.set(String.valueOf(plain.statusCode()));
                plainHealthBody.set(plain.body());

                adminPanel.set(String.valueOf(PanelRegistry.getBySlug("admin")));
                managePanel.set(String.valueOf(PanelRegistry.getBySlug("manage")));

                // The exact condition behind zenit's
                // "WebSocket handler factory returned null" IllegalStateException.
                WebSocketHandler handler = HohenheimEndpoints.DEV_TUNNEL.createHandler(null);
                devTunnelHandler.set(handler == null ? "null" : handler.getClass().getSimpleName());
            } catch (Throwable error) {
                probeFailure.set(error);
            }
        }).setWeight(49);

        try {
            ServerZenitRuntime.init().join();

            assertThat(probeFailure.get())
                .as("the probe itself must not blow up")
                .isNull();

            // 3. A health probe hitting the fresh listener is answered by the real
            //    handler, not by an endpoint whose handler is still unset.
            assertThat(healthStatus.get())
                .as("step 3: GET /api/health at bind time must be handled")
                .isEqualTo("200");
            assertThat(healthBody.get())
                .as("step 3: GET /api/health at bind time must answer the real payload")
                .contains("\"status\"");
            //    The conventional /health alias is the same public handler, never the
            //    login redirect an unauthenticated probe used to get there.
            assertThat(plainHealthStatus.get())
                .as("step 3: GET /health must be public and handled, not a 302 to /login")
                .isEqualTo("200");
            assertThat(plainHealthBody.get())
                .as("step 3: GET /health answers the same payload as /api/health")
                .isEqualTo(healthBody.get());

            // 4. Both CMS panels are registered, so every /{panel}/... route can
            //    resolve to something instead of 404ing.
            assertThat(adminPanel.get())
                .as("step 4: the /admin panel must be registered before the listener accepts")
                .isNotEqualTo("null");
            assertThat(managePanel.get())
                .as("step 4: the /manage panel must be registered before the listener accepts")
                .isNotEqualTo("null");

            // 5. No WebSocket endpoint may still expose its declaration placeholder.
            assertThat(devTunnelHandler.get())
                .as("step 5: the dev-tunnel handler factory must be real, not the null placeholder")
                .isEqualTo("DevTunnelServerHandler");
        } finally {
            ServerZenitRuntime.stop();
        }
    }

    private static int freePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
