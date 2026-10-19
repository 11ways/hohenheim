package be.elevenways.hohenheim.test.host;

import be.elevenways.hohenheim.server.host.HostShell;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link HostShell#quote} is the one shell quoter: it spells every word the way the hand-written copies it replaced
 * did, and a real shell reads each quoted word back as the text it quoted.
 *
 * @author Jelle De Loecker
 * @since  0.9.0
 */
class HostShellQuoteTest {

    @Test
    void aQuotedWordIsOneShellWordThatReadsBackAsItsText() {
        // 1. The spelling the workspace checkout, the git ssh command and the live Incus exec relied on: wrapped in
        //    single quotes, an embedded quote closed, escaped and reopened, the empty text the empty word.
        assertThat(HostShell.quote("/opt/hohenheim/data")).as("step 1: plain text is wrapped")
            .isEqualTo("'/opt/hohenheim/data'");
        assertThat(HostShell.quote("it's")).as("step 1: an embedded quote is closed, escaped and reopened")
            .isEqualTo("'it'\\''s'");
        assertThat(HostShell.quote("")).as("step 1: the empty text is the empty word").isEqualTo("''");
        assertThat(HostShell.quote(null)).as("step 1: and so is null").isEqualTo("''");

        // 2. A real shell reads every quoted word back as exactly its text: nothing expands, splits or ends early.
        for (String text : List.of("refs/heads/main", "two words", "it's", "a'b'c", "$HOME `id` $(id)",
                "*.tar ; rm -rf /tmp/never", "back\\slash \"double\"")) {
            HostShell.Result echoed = HostShell.local().run("printf '%s|' " + HostShell.quote(text));
            assertThat(echoed.ok()).as("step 2: the shell ran for %s", text).isTrue();
            assertThat(echoed.text()).as("step 2: %s reads back as one word", text).isEqualTo(text + "|");
        }
    }
}
