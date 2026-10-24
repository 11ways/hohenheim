package be.elevenways.hohenheim.server.instance;

import be.elevenways.hohenheim.instance.LiveFigureState;
import be.elevenways.hohenheim.instance.StatusProbe;
import be.elevenways.hohenheim.model.InstanceStatus;
import be.elevenways.hohenheim.server.host.HostShell;
import be.elevenways.hohenheim.test.instance.FakeMinecraftServer;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A declared status probe reads a service's figure through both lanes a published port is reached on: the controller's
 * own socket and a host's shell (the lane of every remote host, here the local machine's own shell), reads an
 * unanswered port as silent rather than as its last answer, and reads a hostile answer as no answer, never a crash.
 */
class StatusProbeLanesTest {

    @Test
    void bothLanesReadTheFigureAndAStoppedServerReadsAsSilent() throws Exception {
        int port;
        try (FakeMinecraftServer server = FakeMinecraftServer.start(8, 20)) {
            port = server.port();

            // 1. The controller's socket reads the server-list answer: 8 of 20 players.
            InstanceFigures.Figure direct = InstanceFigures.read(
                new InstanceFigures.Target(1, StatusProbe.MINECRAFT_JAVA, port, PublishedPortProbe.local(),
                    InstanceStatus.RUNNING));
            assertThat(direct.state()).as("step 1: the direct lane is answered").isEqualTo(LiveFigureState.ANSWERING);
            assertThat(direct.online()).as("step 1: with the players online").isEqualTo(8);
            assertThat(direct.capacity()).as("step 1: and the server's ceiling").isEqualTo(20);

            // 2. A host's shell reads the same answer, though the server keeps its connection open after answering.
            server.players(9);
            InstanceFigures.Figure shell = InstanceFigures.read(
                new InstanceFigures.Target(1, StatusProbe.MINECRAFT_JAVA, port,
                    PublishedPortProbe.over(HostShell.local()), InstanceStatus.RUNNING));
            assertThat(shell.state()).as("step 2: the shell lane is answered").isEqualTo(LiveFigureState.ANSWERING);
            assertThat(shell.online()).as("step 2: with the new count").isEqualTo(9);
            assertThat(server.answered()).as("step 2: one status answer per probe").isEqualTo(2);

            // 3. The answer's declared length ends the read, and bytes after it are no part of the figure.
            byte[] answer = PublishedPortProbe.local().exchange(port, MinecraftStatusPing.request(port),
                MinecraftStatusPing::complete, MinecraftStatusPing.MAX_ANSWER, InstanceFigures.PROBE_WAIT);
            assertThat(MinecraftStatusPing.complete(answer)).as("step 3: the read ends on a whole packet").isTrue();
            byte[] trailing = Arrays.copyOf(answer, answer.length + 3);
            assertThat(MinecraftStatusPing.read(trailing)).as("step 3: trailing bytes are ignored")
                .isEqualTo(new MinecraftStatusPing.Reading(9, 20));
            assertThat(MinecraftStatusPing.read(Arrays.copyOf(answer, answer.length - 1)))
                .as("step 3: a cut answer is no answer").isNull();

            // 4. A hostile server's overflowing length prefix reads as silent through the probe, not as a crash.
            server.rawAnswer(new byte[]{(byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, 0x07, 0x00, 0x01});
            assertThat(InstanceFigures.read(new InstanceFigures.Target(1, StatusProbe.MINECRAFT_JAVA, port,
                    PublishedPortProbe.local(), InstanceStatus.RUNNING)).state())
                .as("step 4: an overflowing length prefix reads as silent").isEqualTo(LiveFigureState.SILENT);
        }

        // 5. The server stopped: both lanes read silent, with no count carried over.
        for (PublishedPortProbe lane : new PublishedPortProbe[]{PublishedPortProbe.local(),
                PublishedPortProbe.over(HostShell.local())}) {
            InstanceFigures.Figure gone = InstanceFigures.read(
                new InstanceFigures.Target(1, StatusProbe.MINECRAFT_JAVA, port, lane, InstanceStatus.RUNNING));
            assertThat(gone.state()).as("step 5: a refused port is silent on " + lane.getClass().getSimpleName())
                .isEqualTo(LiveFigureState.SILENT);
            assertThat(gone.toMap()).as("step 5: and carries no count").doesNotContainKey("online");
        }

        // 6. Never published: nothing is dialled, and the figure is silent.
        assertThat(InstanceFigures.read(new InstanceFigures.Target(1, StatusProbe.MINECRAFT_JAVA, null,
                PublishedPortProbe.local(), InstanceStatus.RUNNING)).state())
            .as("step 6: no published port reads as silent").isEqualTo(LiveFigureState.SILENT);
    }

    @Test
    void aHostileAnswerReadsAsNoAnswerWhateverItsShape() {
        // 1. Lengths whose sum overflows an int: the outer length, then the text length inside a small packet.
        byte[] outer = {(byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, 0x07, 0x00, 0x01, 0x02};
        assertThat(MinecraftStatusPing.read(outer)).as("step 1: an overflowing outer length").isNull();
        assertThat(MinecraftStatusPing.complete(outer)).as("step 1: is a length no answer could have").isTrue();
        byte[] inner = {0x06, 0x00, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, 0x07};
        assertThat(MinecraftStatusPing.read(inner)).as("step 1: an overflowing text length").isNull();

        // 2. A truncated VarInt waits for more; five continuation bytes, or a fifth byte past 32 bits, are refused.
        byte[] truncated = {(byte) 0x80, (byte) 0x80};
        assertThat(MinecraftStatusPing.read(truncated)).as("step 2: a truncated VarInt is no answer").isNull();
        assertThat(MinecraftStatusPing.complete(truncated)).as("step 2: and the read goes on").isFalse();
        byte[] endless = {(byte) 0x80, (byte) 0x80, (byte) 0x80, (byte) 0x80, (byte) 0x80, 0x01};
        assertThat(MinecraftStatusPing.read(endless)).as("step 2: an overlong VarInt is no answer").isNull();
        assertThat(MinecraftStatusPing.complete(endless)).as("step 2: and ends the read").isTrue();
        byte[] wide = {(byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, 0x7F, 0x00};
        assertThat(MinecraftStatusPing.read(wide)).as("step 2: a fifth byte past 32 bits is no answer").isNull();

        // 3. JSON nested far past any parser's depth, inside a well-framed answer, is no answer.
        String nested = "{\"players\":" + "[".repeat(100_000) + "]".repeat(100_000) + "}";
        byte[] deep = FakeMinecraftServer.frame(nested.getBytes(StandardCharsets.UTF_8));
        assertThat(deep.length).as("step 3: the answer fits the read cap").isLessThan(MinecraftStatusPing.MAX_ANSWER);
        assertThat(MinecraftStatusPing.read(deep)).as("step 3: deeply nested JSON is no answer").isNull();

        // 4. Counts are clamped to sane non-negative ints.
        byte[] odd = FakeMinecraftServer.frame("{\"players\":{\"online\":-5,\"max\":1000000000000}}"
            .getBytes(StandardCharsets.UTF_8));
        assertThat(MinecraftStatusPing.read(odd)).as("step 4: a negative count reads as 0, a huge one as the int cap")
            .isEqualTo(new MinecraftStatusPing.Reading(0, Integer.MAX_VALUE));
    }
}
