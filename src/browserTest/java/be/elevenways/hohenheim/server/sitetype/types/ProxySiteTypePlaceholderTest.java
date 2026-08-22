package be.elevenways.hohenheim.server.upstream.kinds;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Finding 3: socket-path placeholders are substituted from regex-host capture groups whose
 * values originate in the untrusted Host header. A substituted value must not be able to escape
 * the socket path with a separator or {@code ..}.
 */
class ProxySiteTypePlaceholderTest {

    @Test
    void substitutesASafeCaptureValue() {
        assertThat(AddressUpstreamKind.substitutePlaceholders("/run/{project}.sock",
            Map.of("project", "alpha")))
            .isEqualTo("/run/alpha.sock");
    }

    @Test
    void rejectsAPathSeparatorInACaptureValue() {
        assertThatThrownBy(() -> AddressUpstreamKind.substitutePlaceholders("/run/{project}.sock",
            Map.of("project", "../../etc/passwd")))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsADotDotCaptureValue() {
        assertThatThrownBy(() -> AddressUpstreamKind.substitutePlaceholders("/run/{project}.sock",
            Map.of("project", "..")))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsABackslashInACaptureValue() {
        assertThatThrownBy(() -> AddressUpstreamKind.substitutePlaceholders("/run/{project}.sock",
            Map.of("project", "a\\b")))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
