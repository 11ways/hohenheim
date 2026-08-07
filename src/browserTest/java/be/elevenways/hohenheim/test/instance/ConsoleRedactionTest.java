package be.elevenways.hohenheim.test.instance;

import be.elevenways.hohenheim.server.instance.ConsoleRedaction;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The streaming half of console redaction, with no daemon and no database: what the
 * redactor does to a chunk sequence. The live half (a real secret echoed by a real workload
 * into a real viewer) is {@link InstanceConsoleRedactionLiveTest}.
 */
class ConsoleRedactionTest {

    private static final String SECRET = "s3cr3t-forwarding-key-9f2b";

    private static Set<String> secrets(String... values) {
        return new LinkedHashSet<>(Set.of(values));
    }

    @Test
    void aSecretIsRedactedHoweverItIsChunkedAndOrdinaryOutputIsUntouched() {
        // 1. A secret arriving whole inside a bigger line is replaced, and every character
        //    around it survives verbatim -- redaction must not mangle legitimate output.
        ConsoleRedaction.Redactor whole = ConsoleRedaction.redactorOf(secrets(SECRET));
        String line = whole.feed("token=" + SECRET + " connected\n") + whole.flush();
        assertThat(line)
            .as("step 1: the value is gone and its surroundings are byte-for-byte intact")
            .isEqualTo("token=" + ConsoleRedaction.PLACEHOLDER + " connected\n");

        // 2. THE CHUNK BOUNDARY. The same secret split across three feeds -- the shape a
        //    websocket frame boundary produces -- must still never be emitted. A naive
        //    per-chunk matcher passes step 1 and fails here.
        ConsoleRedaction.Redactor split = ConsoleRedaction.redactorOf(secrets(SECRET));
        StringBuilder seen = new StringBuilder();
        seen.append(split.feed("token=" + SECRET.substring(0, 6)));
        seen.append(split.feed(SECRET.substring(6, 15)));
        seen.append(split.feed(SECRET.substring(15) + " connected\n"));
        seen.append(split.flush());
        assertThat(seen.toString())
            .as("step 2: a secret split across three chunks is still redacted")
            .isEqualTo("token=" + ConsoleRedaction.PLACEHOLDER + " connected\n");

        // 3. Ordinary output is never held back: text that cannot become a secret is
        //    emitted by the very feed that produced it, so an interactive prompt without a
        //    newline still reaches the viewer.
        ConsoleRedaction.Redactor live = ConsoleRedaction.redactorOf(secrets(SECRET));
        assertThat(live.feed("welcome, please log in: "))
            .as("step 3: plain output passes through the same feed, nothing buffered")
            .isEqualTo("welcome, please log in: ");

        // 4. A near-miss is not swallowed: a tail that LOOKS like the start of a secret is
        //    held, then released as the ordinary text it turned out to be.
        ConsoleRedaction.Redactor nearMiss = ConsoleRedaction.redactorOf(secrets(SECRET));
        String held = nearMiss.feed("done s3cr3t-");
        assertThat(held).as("step 4: the possible prefix is held back").isEqualTo("done ");
        assertThat(held + nearMiss.feed("NOPE\n") + nearMiss.flush())
            .as("step 4: and released verbatim once it cannot be the secret")
            .isEqualTo("done s3cr3t-NOPE\n");

        // 5. The MINIMUM LENGTH rule, stated as behaviour: a value too short to be a secret
        //    is left alone rather than shredding every coincidental occurrence of it.
        ConsoleRedaction.Redactor tiny = ConsoleRedaction.redactorOf(secrets("abc"));
        assertThat(tiny.feed("abcdef the alphabet") + tiny.flush())
            .as("step 5: a 3-character value is not redacted (see MIN_SECRET_LENGTH)")
            .isEqualTo("abcdef the alphabet");

        // 6. A secret declared AFTER the session opened starts being redacted immediately.
        ConsoleRedaction.Redactor late = ConsoleRedaction.redactorOf(Set.of());
        assertThat(late.feed("before " + SECRET + "\n"))
            .as("step 6: unknown values are not redacted -- this is what the wiring fixes")
            .contains(SECRET);
        late.addSecret(SECRET);
        assertThat(late.feed("after " + SECRET + "\n") + late.flush())
            .as("step 6: once declared, the same value is redacted on the live stream")
            .isEqualTo("after " + ConsoleRedaction.PLACEHOLDER + "\n");

        // 7. Overlapping secrets: the longer value wins, so a shorter one contained in it
        //    cannot leave a readable remainder behind.
        ConsoleRedaction.Redactor nested =
            ConsoleRedaction.redactorOf(secrets("password1234", "password1234-extended"));
        assertThat(nested.feed("v=password1234-extended;") + nested.flush())
            .as("step 7: the longest match is replaced whole, leaving no readable tail")
            .isEqualTo("v=" + ConsoleRedaction.PLACEHOLDER + ";");
    }
}
