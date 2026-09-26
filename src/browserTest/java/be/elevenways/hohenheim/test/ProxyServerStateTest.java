package be.elevenways.hohenheim.test;

import be.elevenways.hohenheim.HohenheimEndpoints;
import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.hohenheim.server.HohenheimDatabase;
import be.elevenways.hohenheim.server.proxy.ProxyServer;
import be.elevenways.zenit.common.Zenit;
import be.elevenways.zenit.server.ServerZenitRuntime;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

import java.io.File;

/**
 * Tests the ProxyServer lifecycle state machine.
 * Does not use Playwright -- pure server-side tests.
 */
class ProxyServerStateTest {

    private static boolean initialized = false;

    @BeforeAll
    static void initRuntime() throws Exception {
        if (initialized) return;
        initialized = true;

        HohenheimEndpoints.init();
        TestDatabases.freshDatabase();
        // Declare the full-node role set: this class boots the runtime without the
        // HohenheimTestRuntime funnel, and the TASKS stage reads the role snapshot.
        be.elevenways.hohenheim.server.HohenheimRoles.capture();
        ServerZenitRuntime.init();
        Zenit.getHawkeye().setClientScriptLocation("/cms.js");
    }

    @Test
    void newProxyServerIsInStoppedState() {
        ProxyServer proxy = new ProxyServer();
        assertThat(proxy.getState()).isEqualTo(ProxyServer.State.STOPPED);
        assertThat(proxy.getFailureReason()).isNull();
    }

    @Test
    void startOnPrivilegedPortTransitionsToFailed() {
        // Port 80 requires root -- will fail in non-root test environment
        Zenit.SETTINGS_VALUES.setValue(HohenheimSettings.Proxy.HTTP_PORT, 80);

        ProxyServer proxy = new ProxyServer();
        proxy.start();

        assertThat(proxy.getState()).isEqualTo(ProxyServer.State.FAILED);
        assertThat(proxy.getFailureReason()).isNotNull();
        assertThat(proxy.getFailureReason()).isNotEmpty();
    }

    @Test
    void startOnAvailablePortTransitionsToRunning() {
        Zenit.SETTINGS_VALUES.setValue(HohenheimSettings.Proxy.HTTP_PORT, 0);

        ProxyServer proxy = new ProxyServer();
        proxy.start();

        assertThat(proxy.getState()).isEqualTo(ProxyServer.State.RUNNING);
        assertThat(proxy.getFailureReason()).isNull();

        proxy.stop();
    }

    @Test
    void stopTransitionsRunningToStopped() {
        Zenit.SETTINGS_VALUES.setValue(HohenheimSettings.Proxy.HTTP_PORT, 0);

        ProxyServer proxy = new ProxyServer();
        proxy.start();
        assertThat(proxy.getState()).isEqualTo(ProxyServer.State.RUNNING);

        proxy.stop();
        assertThat(proxy.getState()).isEqualTo(ProxyServer.State.STOPPED);
        assertThat(proxy.getFailureReason()).isNull();
    }

    @Test
    void reloadOnFailedStateAttemptsRestart() {
        // Start on port 80 (fails)
        Zenit.SETTINGS_VALUES.setValue(HohenheimSettings.Proxy.HTTP_PORT, 80);
        ProxyServer proxy = new ProxyServer();
        proxy.start();
        assertThat(proxy.getState()).isEqualTo(ProxyServer.State.FAILED);

        // Change to available port and reload -- should attempt restart
        Zenit.SETTINGS_VALUES.setValue(HohenheimSettings.Proxy.HTTP_PORT, 0);
        proxy.reload();
        assertThat(proxy.getState()).isEqualTo(ProxyServer.State.RUNNING);

        proxy.stop();
    }

    @Test
    void failureReasonIsDescriptive() {
        Zenit.SETTINGS_VALUES.setValue(HohenheimSettings.Proxy.HTTP_PORT, 80);

        ProxyServer proxy = new ProxyServer();
        proxy.start();

        assertThat(proxy.getState()).isEqualTo(ProxyServer.State.FAILED);
        // Should contain something about permission or address already in use
        assertThat(proxy.getFailureReason()).isNotBlank();
    }
}
