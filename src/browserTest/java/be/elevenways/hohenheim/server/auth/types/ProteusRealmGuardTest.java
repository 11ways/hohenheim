package be.elevenways.hohenheim.server.auth.types;

import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.hohenheim.test.HohenheimTestRuntime;
import be.elevenways.hohenheim.test.TestDatabases;
import be.elevenways.zenit.common.Zenit;
import be.elevenways.zenit.server.net.OutboundUrlGuard;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The guard a site auth provider's Proteus realm calls ride: the public internet, or the private networks on the
 * operator's explicit opt-in; this host and the link-local metadata address are refused either way.
 *
 * @author Jelle De Loecker
 * @since 0.1.0
 */
class ProteusRealmGuardTest {

    private static boolean initialized = false;

    @BeforeAll
    static void boot() throws Exception {
        if (initialized) return;
        initialized = true;
        TestDatabases.freshDatabase();
        HohenheimTestRuntime.ensureBooted();
    }

    @AfterEach
    void reset() {
        Zenit.SETTINGS_VALUES.setValue(HohenheimSettings.ProxyAuth.PROTEUS_ALLOW_PRIVATE_NETWORKS, false);
    }

    @Test
    void aProviderRealmRidesThePublicInternetUnlessTheOperatorAdmitsPrivateNetworks() {
        // 1. By default the realm guard is the public internet: loopback, metadata and the LAN are refused.
        OutboundUrlGuard guard = ProteusAuthProviderType.realmGuard();
        assertThat(guard).as("step 1: the default guard").isSameAs(OutboundUrlGuard.PUBLIC_INTERNET);
        assertThat(guard.problemOf("http://127.0.0.1:8080/")).as("step 1: loopback refused").isNotNull();
        assertThat(guard.problemOf("http://169.254.169.254/")).as("step 1: metadata refused").isNotNull();
        assertThat(guard.problemOf("http://10.0.0.5/")).as("step 1: a private network refused").isNotNull();

        // 2. The operator's opt-in admits the private networks, and nothing more.
        Zenit.SETTINGS_VALUES.setValue(HohenheimSettings.ProxyAuth.PROTEUS_ALLOW_PRIVATE_NETWORKS, true);
        guard = ProteusAuthProviderType.realmGuard();
        assertThat(guard).as("step 2: the opted-in guard").isSameAs(OutboundUrlGuard.PRIVATE_NETWORKS);
        assertThat(guard.problemOf("http://10.0.0.5/")).as("step 2: a private network admitted").isNull();
        assertThat(guard.problemOf("http://127.0.0.1:8080/")).as("step 2: loopback still refused").isNotNull();
        assertThat(guard.problemOf("http://169.254.169.254/")).as("step 2: metadata still refused").isNotNull();
    }
}
