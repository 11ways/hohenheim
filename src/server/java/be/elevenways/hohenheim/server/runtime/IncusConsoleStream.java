package be.elevenways.hohenheim.server.runtime;

import be.elevenways.hohenheim.server.incus.IncusWebSocket;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * {@link ConsoleStream} over an Incus console websocket. A console is one merged TTY
 * stream, so every chunk reports as stdout; the daemon closing the socket in order is
 * ENDED (console detached / workload gone), a mid-stream transport failure is
 * DAEMON_LOST.
 */
final class IncusConsoleStream implements ConsoleStream {

    private final @NonNull IncusWebSocket socket;
    private final AtomicBoolean consumerClosed = new AtomicBoolean();
    private volatile @Nullable Termination termination;
    private volatile @NonNull String detail = "";

    IncusConsoleStream(@NonNull IncusWebSocket socket) {
        this.socket = socket;
    }

    @Override
    public @Nullable Chunk next() {
        if (this.termination != null) {
            return null;
        }
        byte[] message;
        try {
            message = this.socket.receive();
        } catch (IOException lost) {
            settle(this.consumerClosed.get() ? Termination.CONSUMER_CLOSED
                : Termination.DAEMON_LOST, String.valueOf(lost.getMessage()));
            return null;
        }
        if (message == null) {
            settle(this.consumerClosed.get() ? Termination.CONSUMER_CLOSED
                : Termination.ENDED, "");
            return null;
        }
        return new Chunk(false, message);
    }

    @Override
    public void writeStdin(byte @NonNull [] data) throws IOException {
        if (this.termination != null) {
            throw new IOException("console stream is over (" + this.termination + ")");
        }
        this.socket.send(data);
    }

    @Override
    public @NonNull Termination termination() {
        Termination settled = this.termination;
        if (settled == null) {
            throw new IllegalStateException("the console stream is still live");
        }
        return settled;
    }

    @Override
    public @NonNull String detail() {
        return this.detail;
    }

    @Override
    public void close() {
        this.consumerClosed.set(true);
        this.socket.close();
    }

    private void settle(@NonNull Termination termination, @NonNull String detail) {
        if (this.termination == null) {
            this.termination = termination;
            this.detail = detail;
        }
    }
}
