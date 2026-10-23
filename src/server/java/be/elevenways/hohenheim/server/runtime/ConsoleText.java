package be.elevenways.hohenheim.server.runtime;

import be.elevenways.protoblast.server.process.TextSink;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/**
 * THE UTF-8 text of one {@link ConsoleStream}'s frames, decoded by one stateful decoder per demultiplexed stream.
 *
 * AIDEV-NOTE: a frame boundary is the daemon's, not a character boundary: decoding each frame on its own turns a
 * multi-byte character split across two frames into U+FFFD. Each stream keeps its own split bytes, so a stderr frame
 * between two halves of a stdout character does not break it. A sequence the stream's end split reads as U+FFFD in
 * {@link #finish()}. One reader thread per instance; it is not thread-safe.
 *
 * @author Jelle De Loecker
 * @since  0.10.0
 */
public final class ConsoleText {

    private final StringBuilder decoded = new StringBuilder();
    private final TextSink stdout = TextSink.chunks(StandardCharsets.UTF_8, this.decoded::append);
    private final TextSink stderr = TextSink.chunks(StandardCharsets.UTF_8, this.decoded::append);

    /** @return the text this frame completes; empty while a split character waits for its next bytes */
    public @NonNull String decode(ConsoleStream.@NonNull Chunk chunk) {
        try {
            (chunk.stderr() ? this.stderr : this.stdout).write(chunk.data());
        } catch (IOException impossible) {
            // TextSink only fails when its consumer throws, and appending to a StringBuilder does not.
            throw new UncheckedIOException(impossible);
        }
        return this.taken();
    }

    /** Ends both streams. @return the text their ends completed */
    public @NonNull String finish() {
        try {
            this.stdout.flush();
            this.stderr.flush();
        } catch (IOException impossible) {
            throw new UncheckedIOException(impossible);
        }
        return this.taken();
    }

    private @NonNull String taken() {
        String text = this.decoded.toString();
        this.decoded.setLength(0);
        return text;
    }
}
