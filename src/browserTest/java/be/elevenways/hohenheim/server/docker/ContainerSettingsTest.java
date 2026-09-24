package be.elevenways.hohenheim.server.docker;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** The one reading of the image, tag and command settings both container kinds share. */
class ContainerSettingsTest {

    @Test
    void imageTagAndCommandReadTheSameForEveryContainerKind() {
        // 1. A tag joins a bare image; an image that already names one keeps it.
        assertThat(ContainerSettings.imageReference(Map.of("image", "nginx", "tag", "1.27")))
            .as("step 1: image plus tag").isEqualTo("nginx:1.27");
        assertThat(ContainerSettings.imageReference(Map.of("image", "nginx:1.25", "tag", "1.27")))
            .as("step 1: an explicit tag in the image wins").isEqualTo("nginx:1.25");
        assertThat(ContainerSettings.imageReference(Map.of("image", " nginx ")))
            .as("step 1: no tag, trimmed").isEqualTo("nginx");

        // 2. The command splits on whitespace; none set means the image's own command.
        assertThat(ContainerSettings.commandLine(Map.of("command", "node  server.js --port 80")))
            .as("step 2: split on runs of whitespace")
            .isEqualTo(List.of("node", "server.js", "--port", "80"));
        assertThat(ContainerSettings.commandLine(Map.of("command", "  ")))
            .as("step 2: a blank command is none").isNull();
        assertThat(ContainerSettings.commandLine(Map.of())).as("step 2: an absent command is none").isNull();
    }
}
