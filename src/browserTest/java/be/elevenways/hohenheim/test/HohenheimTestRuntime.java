package be.elevenways.hohenheim.test;

import be.elevenways.zenit.server.ServerZenitRuntime;
import be.elevenways.zenit.server.setting.ServerSettings;

/**
 * Boots the server-side runtime for tests via the real boot path so the MODELS stage
 * (ClassGraph discovery) registers the model singletons -- the same registration production
 * gets from {@code ServerZenitRuntime.main()}. HTTP auto-start is disabled because tests bind
 * their own servers on ephemeral ports.
 */
public final class HohenheimTestRuntime {

    private HohenheimTestRuntime() {
    }

    public static void ensureBooted() {
        ServerSettings.VALUES.setValue(ServerSettings.Network.AUTO_START_HTTP, false);
        ServerZenitRuntime.init().join();
    }
}
