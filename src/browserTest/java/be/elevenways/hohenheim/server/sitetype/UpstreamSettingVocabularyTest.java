package be.elevenways.hohenheim.server.sitetype;

import be.elevenways.hohenheim.server.upstream.kinds.AddressUpstreamKind;
import be.elevenways.hohenheim.server.upstream.kinds.RedirectUpstreamKind;
import be.elevenways.hohenheim.server.upstream.kinds.StaticUpstreamKind;
import be.elevenways.hohenheim.server.upstream.kinds.UpstreamSettings;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The upstream settings several kinds share have one declaring home: the protocol tokens are
 * the enum's, an unknown one fails closed while every stored production value still parses,
 * and the delay every kind offers is the one field the dispatcher reads by the one key.
 */
class UpstreamSettingVocabularyTest {

    @Test
    void protocolTokensParseFromTheirHomeAndUnknownOnesFailClosed() {
        // Step 1: every value production can hold still parses: absent, blank and each token.
        assertThat(UpstreamProtocol.fromSetting(null)).as("step 1: absent is HTTP/1.1")
            .isEqualTo(UpstreamProtocol.HTTP1);
        assertThat(UpstreamProtocol.fromSetting(" ")).as("step 1: blank is HTTP/1.1")
            .isEqualTo(UpstreamProtocol.HTTP1);
        for (UpstreamProtocol protocol : UpstreamProtocol.values()) {
            assertThat(UpstreamProtocol.fromSetting(protocol.token()))
                .as("step 1: the stored token of %s round-trips", protocol).isEqualTo(protocol);
        }
        assertThat(UpstreamProtocol.HTTP1.token()).as("step 1: the stored spelling is unchanged")
            .isEqualTo("http1");
        assertThat(UpstreamProtocol.H2.token()).as("step 1: the stored spelling is unchanged")
            .isEqualTo("h2");

        // Step 2: a token this build does not know is refused, never folded into HTTP/1.1.
        assertThatThrownBy(() -> UpstreamProtocol.fromSetting("h3"))
            .as("step 2: an unknown protocol fails closed")
            .isInstanceOf(IllegalArgumentException.class);

        // Step 3: the address kind's choice offers exactly the enum's tokens.
        assertThat(AddressUpstreamKind.UPSTREAM_PROTOCOL.getValues().keySet())
            .as("step 3: the form's options derive from the enum")
            .containsExactlyElementsOf(Arrays.stream(UpstreamProtocol.values())
                .map(UpstreamProtocol::token).toList());
    }

    @Test
    void theDelayIsOneSettingUnderOneKey() {
        // Step 1: every kind that offers a delay stores it under the key the dispatcher reads.
        assertThat(StaticUpstreamKind.DELAY.getName()).as("step 1: static").isEqualTo(UpstreamSettings.DELAY);
        assertThat(RedirectUpstreamKind.DELAY.getName()).as("step 1: redirect").isEqualTo(UpstreamSettings.DELAY);
        assertThat(AddressUpstreamKind.DELAY.getName()).as("step 1: address").isEqualTo(UpstreamSettings.DELAY);
    }
}
