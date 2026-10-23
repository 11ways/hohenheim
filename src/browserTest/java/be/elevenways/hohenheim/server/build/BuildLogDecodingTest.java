package be.elevenways.hohenheim.server.build;

import be.elevenways.hohenheim.server.runtime.ConsoleStream;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A build's output reads back as the builder printed it, whatever byte the daemon's frames happen to split at.
 *
 * @author Jelle De Loecker
 * @since  0.10.0
 */
class BuildLogDecodingTest {

    @Test
    void aCharacterSplitAcrossFramesReadsWholeInTheBuildLog() throws InterruptedException {
        FramedStream stream = new FramedStream();
        // 1. U+00E9 (C3 A9) split between two stdout frames.
        stream.frame(false, bytes("caf"), new byte[] {(byte) 0xC3});
        stream.frame(false, new byte[] {(byte) 0xA9}, bytes(" ok\n"));
        // 2. U+20AC (E2 82 AC) split in stdout, with a stderr frame between its halves.
        stream.frame(false, bytes("5 "), new byte[] {(byte) 0xE2});
        stream.frame(true, bytes("warn\n"));
        stream.frame(false, new byte[] {(byte) 0x82, (byte) 0xAC}, bytes("\n"));
        // 3. The stream ends inside a sequence: the cut character reads as one replacement, never as lost text.
        stream.frame(false, bytes("end "), new byte[] {(byte) 0xF0, (byte) 0x9F});

        BuildLog log = new BuildLog(64 * 1024);
        Thread reader = BuildSandbox.drainInto(stream, log, "decoding-test");
        reader.join(5000);
        assertThat(reader.isAlive()).as("the drain ends with the stream").isFalse();
        log.flush();

        // 4. THE ASSERTION: the stored (and live) text holds the characters, not U+FFFD per half.
        assertThat(log.text()).as("step 4: the build log text").isEqualTo("caf\u00e9 ok\n5 warn\n\u20ac\nend \uFFFD");
    }

    private static byte @NonNull [] bytes(@NonNull String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    /** A finished attach stream that hands out the frames it was given, then ends. */
    private static final class FramedStream implements ConsoleStream {

        private final Deque<Chunk> frames = new ArrayDeque<>();

        void frame(boolean stderr, byte[]... parts) {
            ByteArrayOutputStream joined = new ByteArrayOutputStream();
            for (byte[] part : parts) {
                joined.writeBytes(part);
            }
            this.frames.add(new Chunk(stderr, joined.toByteArray()));
        }

        @Override
        public synchronized @Nullable Chunk next() {
            return this.frames.poll();
        }

        @Override
        public void writeStdin(byte @NonNull [] data) {
            throw new UnsupportedOperationException("a build attach takes no input");
        }

        @Override
        public @NonNull Termination termination() {
            return Termination.ENDED;
        }

        @Override
        public @NonNull String detail() {
            return "";
        }

        @Override
        public void close() {
        }
    }
}
