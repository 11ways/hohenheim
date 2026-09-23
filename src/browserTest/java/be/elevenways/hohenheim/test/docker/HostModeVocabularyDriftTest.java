package be.elevenways.hohenheim.test.docker;

import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.server.docker.HostMode;
import be.elevenways.hohenheim.server.docker.ServerService;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * Binds {@link HostMode} to the stored vocabulary it reads ({@link ServerModel#MODE}) and
 * proves an unknown mode FAILS CLOSED instead of addressing the local daemon.
 *
 * AIDEV-NOTE: the drift binding is what makes "add a mode" one edit or a red build: a value
 * the EnumField gains without a HostMode member (or the reverse) fails step 1, and the
 * transport switch over HostMode is exhaustive with no default, so a new member does not
 * compile until it names its transport.
 */
class HostModeVocabularyDriftTest {

    @Test
    void theModeVocabularyHasOneHomeAndAnUnknownModeIsRefused() {
        // 1. The enum and the stored EnumField declare the same tokens, nothing more.
        Set<String> tokens = new LinkedHashSet<>();
        Arrays.stream(HostMode.values()).forEach(mode -> tokens.add(mode.token()));
        assertThat(tokens).as("step 1: HostMode covers exactly the stored mode values")
            .containsExactlyInAnyOrderElementsOf(ServerModel.MODE.getValues().keySet());

        // 2. The tokens production rows carry keep parsing to the member they always meant.
        assertThat(HostMode.forToken("local")).as("step 2: 'local' is the local daemon")
            .isEqualTo(HostMode.LOCAL);
        assertThat(HostMode.forToken("ssh")).as("step 2: 'ssh' is a remote daemon")
            .isEqualTo(HostMode.SSH);
        assertThat(HostMode.SSH.remote()).as("step 2: ssh is remote").isTrue();
        assertThat(HostMode.LOCAL.remote()).as("step 2: local is not").isFalse();

        // 3. Anything else -- a typo, a blank, a missing value -- is REFUSED. It used to
        //    fall through to the local unix socket: a wrong-host operation, silently.
        for (String unknown : new String[] {"SSH", "sshh", "", " ", "incus", null}) {
            assertThat(catchThrowable(() -> HostMode.forToken(unknown)))
                .as("step 3: mode '" + unknown + "' is refused, never read as local")
                .isInstanceOf(IllegalArgumentException.class);
        }

        // 4. The local HOST NAME is its own fact, spelled once, and it is not a mode lookup.
        assertThat(ServerService.LOCAL_HOST_NAME)
            .as("step 4: the implicit local host row keeps its stored name")
            .isEqualTo("local");
    }
}
