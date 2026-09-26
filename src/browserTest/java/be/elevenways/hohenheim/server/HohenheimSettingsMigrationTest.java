package be.elevenways.hohenheim.server;

import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.zenit.server.setting.DryFileSource;
import be.elevenways.zenit.server.setting.RetiredConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * An existing deployment's settings/hohenheim.dry moves into settings/local.dry under hohenheim.* on the first boot,
 * leaving a backup, and a HOHENHEIM__* variable refuses the boot naming its ZENIT__HOHENHEIM__* spelling.
 *
 * @author Jelle De Loecker
 * @since  0.1.0
 */
class HohenheimSettingsMigrationTest {

    @TempDir
    Path root;

    @Test
    void theOldFileMovesIntoTheFrameworkFileAndTheOldPrefixIsRefused() throws Exception {
        // 1. An existing deployment: its own file with group-relative keys, and a framework file beside it.
        Path old = this.root.resolve("settings/hohenheim.dry");
        Path local = this.root.resolve("settings/local.dry");
        Files.createDirectories(old.getParent());
        String oldContents = "{\"proxy\":{\"http_port\":8080,\"trusted_proxy_keys\":[\"key-one\"]},"
            + "\"ssl\":{\"letsencrypt_enabled\":false}}";
        Files.writeString(old, oldContents, StandardCharsets.UTF_8);
        Files.writeString(local, "{\"network\":{\"port\":2999}}", StandardCharsets.UTF_8);

        // 2. The boot's adoption moves every key under hohenheim.* in the framework file and keeps the rest.
        RetiredConfiguration.adopt(this.root, local);
        Map<String, Object> merged = new DryFileSource(local).snapshot();
        assertThat(merged.get("network")).as("step 2: the framework keys stay").isEqualTo(Map.of("port", 2999));
        assertThat(merged.get("hohenheim")).as("step 2: the old keys live under hohenheim.* now")
            .isEqualTo(Map.of("proxy", Map.of("http_port", 8080, "trusted_proxy_keys", List.of("key-one")),
                "ssl", Map.of("letsencrypt_enabled", false)));

        // 3. The old file is gone, kept verbatim as a dated backup beside it.
        assertThat(Files.exists(old)).as("step 3: the old file is moved away").isFalse();
        try (var siblings = Files.list(old.getParent())) {
            List<Path> backups = siblings.filter(path -> path.getFileName().toString()
                .startsWith("hohenheim.dry.bak-")).toList();
            assertThat(backups).as("step 3: one backup").hasSize(1);
            assertThat(Files.readString(backups.get(0), StandardCharsets.UTF_8)).as("step 3: verbatim")
                .isEqualTo(oldContents);
        }

        // 4. Each moved key sits exactly where the framework chain reads Hohenheim's own definition.
        assertThat(HohenheimSettings.Proxy.HTTP_PORT.getPath()).as("step 4: the port's path")
            .isEqualTo("hohenheim.proxy.http_port");
        assertThat(HohenheimSettings.Ssl.LETSENCRYPT_ENABLED.getPath()).as("step 4: the toggle's path")
            .isEqualTo("hohenheim.ssl.letsencrypt_enabled");

        // 5. The old environment spelling refuses the boot, naming its replacement and never the value.
        assertThatThrownBy(() -> RetiredConfiguration.refuse(this.root,
                Map.of("HOHENHEIM__PROXY__HTTP_PORT", "8443")))
            .as("step 5: a HOHENHEIM__ variable refuses the boot")
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("'HOHENHEIM__*' in the environment")
            .hasMessageContaining("hohenheim.* in settings/local.dry")
            .hasMessageContaining("ZENIT__HOHENHEIM__*")
            .hasMessageNotContaining("8443");
    }
}
