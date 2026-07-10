package be.elevenways.hohenheim.test;

import be.elevenways.hohenheim.HohenheimEndpoints;
import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.hohenheim.server.HohenheimDatabase;
import be.elevenways.hohenheim.server.proxy.ProxyServer;
import be.elevenways.hohenheim.server.sitetype.SiteTypes;
import be.elevenways.zenit.common.Zenit;
import be.elevenways.zenit.server.ServerZenitRuntime;
import org.junit.jupiter.api.*;
import static org.assertj.core.api.Assertions.*;

import java.io.File;

/**
 * Tests the ProxyServer lifecycle state machine.
 * Does not use Playwright -- pure server-side tests.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ProxyServerStateTest {

    private static boolean initialized = false;

    @BeforeAll
    static void initRuntime() throws Exception {
        if (initialized) return;
        initialized = true;

        File db = File.createTempFile("hohenheim-test", ".db");
        db.delete();
        db.deleteOnExit();
        HohenheimSettings.VALUES.setValue(HohenheimSettings.Database.PATH, db.getAbsolutePath());

        SiteTypes.boot();
        HohenheimEndpoints.init();
        HohenheimDatabase.init();
        ServerZenitRuntime.init();
        Zenit.getHawkeye().setClientScriptLocation("/cms.js");
    }

    @Test
    @Order(1)
    void newProxyServerIsInStoppedState() {
        ProxyServer proxy = new ProxyServer();
        assertThat(proxy.getState()).isEqualTo(ProxyServer.State.STOPPED);
        assertThat(proxy.getFailureReason()).isNull();
    }

    @Test
    @Order(2)
    void startOnPrivilegedPortTransitionsToFailed() {
        // Port 80 requires root -- will fail in non-root test environment
        HohenheimSettings.VALUES.setValue(HohenheimSettings.Proxy.HTTP_PORT, 80);

        ProxyServer proxy = new ProxyServer();
        proxy.start();

        assertThat(proxy.getState()).isEqualTo(ProxyServer.State.FAILED);
        assertThat(proxy.getFailureReason()).isNotNull();
        assertThat(proxy.getFailureReason()).isNotEmpty();
    }

    @Test
    @Order(3)
    void startOnAvailablePortTransitionsToRunning() {
        HohenheimSettings.VALUES.setValue(HohenheimSettings.Proxy.HTTP_PORT, 0);

        ProxyServer proxy = new ProxyServer();
        proxy.start();

        assertThat(proxy.getState()).isEqualTo(ProxyServer.State.RUNNING);
        assertThat(proxy.getFailureReason()).isNull();

        proxy.stop();
    }

    @Test
    @Order(4)
    void stopTransitionsRunningToStopped() {
        HohenheimSettings.VALUES.setValue(HohenheimSettings.Proxy.HTTP_PORT, 0);

        ProxyServer proxy = new ProxyServer();
        proxy.start();
        assertThat(proxy.getState()).isEqualTo(ProxyServer.State.RUNNING);

        proxy.stop();
        assertThat(proxy.getState()).isEqualTo(ProxyServer.State.STOPPED);
        assertThat(proxy.getFailureReason()).isNull();
    }

    @Test
    @Order(5)
    void reloadOnFailedStateAttemptsRestart() {
        // Start on port 80 (fails)
        HohenheimSettings.VALUES.setValue(HohenheimSettings.Proxy.HTTP_PORT, 80);
        ProxyServer proxy = new ProxyServer();
        proxy.start();
        assertThat(proxy.getState()).isEqualTo(ProxyServer.State.FAILED);

        // Change to available port and reload -- should attempt restart
        HohenheimSettings.VALUES.setValue(HohenheimSettings.Proxy.HTTP_PORT, 0);
        proxy.reload();
        assertThat(proxy.getState()).isEqualTo(ProxyServer.State.RUNNING);

        proxy.stop();
    }

    @Test
    @Order(6)
    void failureReasonIsDescriptive() {
        HohenheimSettings.VALUES.setValue(HohenheimSettings.Proxy.HTTP_PORT, 80);

        ProxyServer proxy = new ProxyServer();
        proxy.start();

        assertThat(proxy.getState()).isEqualTo(ProxyServer.State.FAILED);
        // Should contain something about permission or address already in use
        assertThat(proxy.getFailureReason()).isNotBlank();
    }
}
