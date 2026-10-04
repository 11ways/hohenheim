package be.elevenways.hohenheim.test;

import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.hohenheim.server.auth.types.ProteusAuthProviderType;
import be.elevenways.zenit.common.Zenit;
import be.elevenways.zenit.common.security.CallerChannel;
import be.elevenways.zenit.common.security.ExecutionIdentity;
import be.elevenways.zenit.server.net.OutboundUrlGuard;
import be.elevenways.zenit.server.setting.DryFileSource;
import be.elevenways.zenit.server.setting.DrySettingsWriter;
import be.elevenways.zenit.server.setting.PrivateNetworkBoot;
import be.elevenways.zenit.test.support.PrivateNetworkBootFixture;
import be.elevenways.zenit.test.support.TestAccessContexts;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * An installed Hohenheim's stored Proteus realm allowance moves into boot configuration without losing its value.
 *
 * @author Jelle De Loecker
 * @since 0.1.0
 */
class HostNetworkBootMigrationTest {

    @Test
    void storedRealmReachIsCarriedOverAndCannotBeDelegated(@TempDir Path root) {
        var consumer = HohenheimSettings.ProxyAuth.PROTEUS_ALLOW_PRIVATE_NETWORKS;
        try (var boot = new PrivateNetworkBootFixture()) {
            Path local = root.resolve("settings/local.dry");
            new DrySettingsWriter(local).setPath(consumer.configurationPath(), true)
                .setPath("hohenheim.proxy_auth.session_ttl_seconds", 86400).persist();

            // 1. Boot carries the stored true into the host file and the real realm guard reads that fact.
            ExecutionIdentity.runDetachedAsSystem("host boot", () ->
                PrivateNetworkBoot.loadAndMigrate(root, local, new DryFileSource(local)));
            assertThat(ProteusAuthProviderType.realmGuard()).as("step 1: the existing LAN realm remains reachable")
                .isSameAs(OutboundUrlGuard.PRIVATE_NETWORKS);
            assertThat(new DryFileSource(local).snapshot().toString()).as("step 1: operator configuration is preserved")
                .contains("session_ttl_seconds=86400").contains("proteus_allow_private_networks=true");
            assertThat(new DryFileSource(root.resolve(PrivateNetworkBoot.FILE)).snapshot().toString())
                .as("step 1: the stored allowance is retained in host boot configuration")
                .contains("proteus_allow_private_networks=true");

            // 2. A delegated caller cannot change the host declaration or run its migration writer.
            var admin = TestAccessContexts.allAllowed();
            ExecutionIdentity.run(ExecutionIdentity.caller(admin, CallerChannel.of(admin)), () -> {
                assertThatThrownBy(() -> consumer.declareAtBoot(false)).as("step 2: no delegated boot authority")
                    .isInstanceOf(SecurityException.class);
                assertThatThrownBy(() -> PrivateNetworkBoot.loadAndMigrate(root, local))
                    .as("step 2: no delegated boot-file writer").isInstanceOf(SecurityException.class);
            });
            assertThat(ProteusAuthProviderType.realmGuard()).as("step 2: the host fact is unchanged")
                .isSameAs(OutboundUrlGuard.PRIVATE_NETWORKS);
        }
    }
}
