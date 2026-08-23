package be.elevenways.hohenheim.server.docker;

import be.elevenways.hohenheim.server.runtime.ConsoleStream;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * The Docker {@link ConsoleStream}: incremental HTTP response parsing over a
 * {@link DockerStreamConnection}, handling BOTH body shapes the Engine API streams in --
 * chunked transfer encoding (follow-logs, stats: Go's net/http chunks unknown-length
 * HTTP/1.1 responses) and the HIJACKED raw stream (attach, exec start: the daemon stops
 * HTTP framing after the header block) -- plus Docker's stdout/stderr frame multiplexing
 * for non-TTY workloads. Getting the demux wrong silently interleaves the two streams,
 * which is why it lives HERE, once, and never in a consumer.
 *
 * AIDEV-NOTE: raw (hijacked) EOF is reported as ENDED even though a daemon crash also
 * produces EOF -- the wire genuinely cannot tell them apart. A consumer that must know
 * whether the WORKLOAD exited re-inspects the container after ENDED; the console hub
 * does exactly that.
 */
public final class ContainerStream implements ConsoleStream {

    /** Bounds the header wait at open time; a stalled daemon must not pin the opener. */
    private static final ScheduledExecutorService WATCHDOG =
        Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "docker-stream-watchdog");
            thread.setDaemon(true);
            return thread;
        });

    /** Cap on an error-response body read at open time (protects the heap, not UX). */
    private static final int MAX_ERROR_BODY = 64 * 1024;

    /** How long the error-body drain keeps asking a silent connection for more. */
    private static final long ERROR_BODY_TIMEOUT_MS = 2_000;

    /** Pause between zero-byte reads, so "nothing yet" never becomes a busy loop. */
    private static void idle() {
        try {
            Thread.sleep(1);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private final DockerStreamConnection connection;
    private final boolean stdinOpen;

    private final byte[] buffer = new byte[8192];
    private int bufferStart;
    private int bufferEnd;

    private final boolean chunked;

    // Chunked-decoding state.
    private long chunkRemaining;
    private boolean chunkSizeExpected = true;
    private boolean lastChunkSeen;
    private final StringBuilder chunkSizeLine = new StringBuilder();

    // Frame-demux state.
    private final byte[] frameHeader = new byte[8];
    private int frameHeaderFilled;
    private int framePayloadRemaining;
    private boolean frameStderr;
    private boolean rawFallback;

    private volatile boolean consumerClosed;
    private @Nullable Termination termination;
    private @NonNull String detail = "";

    /**
     * Open a stream: send the request, parse the response head (bounded by
     * {@code headerTimeoutMs}), refuse a non-2xx with {@link DockerClient.ApiException}.
     * Low-level: {@link DockerClient#attach}/{@link DockerClient#followLogs} are the
     * normal entry points and own the request shapes.
     *
     * @param stdinOpen whether this endpoint accepts writes after the request (attach)
     */
    public static @NonNull ContainerStream open(@NonNull DockerStreamTransport transport,
                                         byte @NonNull [] request, long headerTimeoutMs,
                                         boolean stdinOpen) throws IOException {
        return open(transport, request, headerTimeoutMs, stdinOpen, false);
    }

    /**
     * {@link #open(DockerStreamTransport, byte[], long, boolean)} for an endpoint whose
     * framing the CALLER knows: a TTY exec is never multiplexed.
     *
     * AIDEV-NOTE: the 4-arg overload DISCOVERS the framing from the first byte (a value
     * above 2 cannot be a Docker frame type, so the stream is raw). That heuristic is
     * right for attach, where the container's TTY-ness is not ours to know, and wrong for
     * a PTY exec we ourselves created with {@code Tty: true}: a first output byte of
     * 0x00-0x02 would be eaten as a frame header and the next four bytes read as a length.
     * Declaring the framing is not an optimization here -- it removes a guess from a lane
     * whose bytes a tenant chooses.
     *
     * @param rawStream true when the response body carries no 8-byte stdout/stderr frames
     */
    public static @NonNull ContainerStream open(@NonNull DockerStreamTransport transport,
                                         byte @NonNull [] request, long headerTimeoutMs,
                                         boolean stdinOpen, boolean rawStream) throws IOException {
        DockerStreamConnection connection = transport.openStream(request, headerTimeoutMs);
        ScheduledFuture<?> watchdog = WATCHDOG.schedule(
            connection::close, headerTimeoutMs, TimeUnit.MILLISECONDS);
        try {
            return new ContainerStream(connection, stdinOpen, rawStream, headerTimeoutMs);
        } catch (IOException e) {
            connection.close();
            String evidence = connection.diagnostics();
            if (evidence.isEmpty()) {
                throw e;
            }
            throw new IOException(e.getMessage() + " (" + evidence + ")", e);
        } finally {
            watchdog.cancel(false);
        }
    }

    private ContainerStream(DockerStreamConnection connection, boolean stdinOpen,
                            boolean rawStream, long headerTimeoutMs) throws IOException {
        this.connection = connection;
        this.stdinOpen = stdinOpen;
        this.rawFallback = rawStream;

        String head = this.readHead(headerTimeoutMs);
        int lineEnd = head.indexOf("\r\n");
        String statusLine = lineEnd < 0 ? head : head.substring(0, lineEnd);
        String[] parts = statusLine.split(" ", 3);
        int status;
        try {
            status = parts.length >= 2 ? Integer.parseInt(parts[1]) : -1;
        } catch (NumberFormatException e) {
            status = -1;
        }
        if (status < 0) {
            throw new IOException("Bad status line from Docker daemon: " + statusLine);
        }
        boolean chunkedHeader = false;
        for (String line : head.split("\r\n")) {
            String lower = line.toLowerCase(java.util.Locale.ROOT);
            if (lower.startsWith("transfer-encoding:") && lower.contains("chunked")) {
                chunkedHeader = true;
            }
        }
        this.chunked = chunkedHeader;

        // 101 = the daemon honored an Upgrade; the raw stream follows either way.
        if ((status < 200 || status >= 300) && status != 101) {
            throw new DockerClient.ApiException(status, "Docker API returned HTTP " + status
                + ": " + this.readErrorBody().trim());
        }
    }

    /**
     * Accumulate bytes until the blank line ending the response head; keep the rest buffered.
     *
     * AIDEV-NOTE: it reads through {@link #readMore}, NOT {@link #fill}, and that is the
     * whole point. {@code fill()} short-circuits while ANY byte is still buffered -- correct
     * for the body loops, fatal here: this loop deliberately keeps the last 3 bytes back (a
     * terminator can straddle two reads), so once a read leaves a tail of 3 or fewer bytes
     * with no terminator in it, {@code consume} is 0, {@code fill()} hands the same 3 bytes
     * back without touching the socket, and the loop spins on the CPU forever. MEASURED
     * 2026-08-23: a shell handshake was found burning a core here for minutes, its stack
     * pointing at this loop's BACKEDGE, whenever the daemon's response head happened to
     * arrive split. It is flaky by nature -- it depends on how the head is chunked -- and it
     * is not new: attach and follow-logs ride the same loop.
     *
     * The deadline is its own second guard: the external close watchdog did not end that
     * spin, because a spinning thread never asks the socket anything.
     */
    private @NonNull String readHead(long timeoutMs) throws IOException {
        long deadline = System.currentTimeMillis() + Math.max(timeoutMs, 1);
        StringBuilder head = new StringBuilder();
        while (true) {
            // Scan the buffered window for the terminator, keeping scanned bytes buffered
            // so body bytes that arrived with the header are not lost.
            for (int i = this.bufferStart; i + 3 < this.bufferEnd; i++) {
                if (this.buffer[i] == '\r' && this.buffer[i + 1] == '\n'
                        && this.buffer[i + 2] == '\r' && this.buffer[i + 3] == '\n') {
                    head.append(new String(this.buffer, this.bufferStart,
                        i - this.bufferStart, StandardCharsets.ISO_8859_1));
                    this.bufferStart = i + 4;
                    return head.toString();
                }
            }
            // No terminator yet: consume all but the last 3 bytes (a split terminator).
            int keep = Math.min(3, this.bufferEnd - this.bufferStart);
            int consume = this.bufferEnd - this.bufferStart - keep;
            if (consume > 0) {
                head.append(new String(this.buffer, this.bufferStart, consume,
                    StandardCharsets.ISO_8859_1));
                this.bufferStart += consume;
            }
            if (head.length() > 64 * 1024) {
                throw new IOException("Docker stream response header exceeded 64KiB");
            }
            int more = this.readMore();
            if (more == -1) {
                throw new IOException("Docker stream closed before a response header arrived");
            }
            if (more == 0) {
                if (System.currentTimeMillis() >= deadline) {
                    throw new IOException("Docker stream produced no response header within "
                        + timeoutMs + "ms");
                }
                idle();
            }
        }
    }

    /**
     * Read ADDITIONAL bytes, keeping whatever is still unconsumed (compacted to the front).
     *
     * @return the number of new bytes, 0 when the read produced none, -1 at EOF
     */
    private int readMore() throws IOException {
        if (this.bufferStart > 0) {
            int kept = this.bufferEnd - this.bufferStart;
            System.arraycopy(this.buffer, this.bufferStart, this.buffer, 0, kept);
            this.bufferStart = 0;
            this.bufferEnd = kept;
        }
        if (this.bufferEnd >= this.buffer.length) {
            // Unreachable for a header (the 64KiB guard fires first), but a full buffer
            // must never be reported as EOF.
            return 0;
        }
        int n = this.connection.read(this.buffer, this.bufferEnd,
            this.buffer.length - this.bufferEnd);
        if (n == -1) {
            return -1;
        }
        this.bufferEnd += n;
        return n;
    }

    /** Drain a (bounded) error body so the ApiException carries the daemon's reason. */
    private @NonNull String readErrorBody() {
        StringBuilder body = new StringBuilder();
        long deadline = System.currentTimeMillis() + ERROR_BODY_TIMEOUT_MS;
        try {
            while (body.length() < MAX_ERROR_BODY) {
                int filled = this.fill();
                if (filled == -1) {
                    break;
                }
                if (filled == 0) {
                    if (System.currentTimeMillis() >= deadline) {
                        break;
                    }
                    idle();
                    continue;
                }
                body.append(new String(this.buffer, this.bufferStart,
                    this.bufferEnd - this.bufferStart, StandardCharsets.UTF_8));
                this.bufferStart = this.bufferEnd;
            }
        } catch (IOException ignored) {
            // whatever was read is the evidence
        }
        // A chunked error body keeps its framing noise; strip the obvious size lines.
        return body.toString();
    }

    /** Ensure at least one unconsumed byte is buffered; -1 at EOF. */
    private int fill() throws IOException {
        if (this.bufferStart < this.bufferEnd) {
            return this.bufferEnd - this.bufferStart;
        }
        this.bufferStart = 0;
        this.bufferEnd = 0;
        int n = this.connection.read(this.buffer, 0, this.buffer.length);
        if (n == -1) {
            return -1;
        }
        this.bufferEnd = n;
        return n;
    }

    @Override
    public @Nullable Chunk next() {
        if (this.termination != null) {
            return null;
        }
        try {
            while (true) {
                Chunk chunk = this.demuxBuffered();
                if (chunk != null) {
                    return chunk;
                }
                int filled = this.fillBody();
                if (filled == -1) {
                    this.settle(this.eofTermination(), this.connection.diagnostics());
                    return null;
                }
                if (filled == 0) {
                    // Nothing on the wire yet. This loop is deliberately unbounded in TIME
                    // (a console or a shell may sit silent for hours) but must never be
                    // unbounded in CPU -- see readHead's note.
                    idle();
                }
            }
        } catch (IOException e) {
            if (this.consumerClosed) {
                this.settle(Termination.CONSUMER_CLOSED, "");
            } else {
                String evidence = this.connection.diagnostics();
                this.settle(Termination.DAEMON_LOST, evidence.isEmpty()
                    ? String.valueOf(e.getMessage()) : e.getMessage() + " (" + evidence + ")");
            }
            return null;
        }
    }

    /** EOF classification: mid-chunk truncation is a lost daemon, everything else ENDED. */
    private @NonNull Termination eofTermination() {
        if (this.consumerClosed) {
            return Termination.CONSUMER_CLOSED;
        }
        if (this.chunked && !this.lastChunkSeen) {
            return Termination.DAEMON_LOST;
        }
        return Termination.ENDED;
    }

    /** Refill the buffer with decoded BODY bytes (dechunking when needed); -1 at end. */
    private int fillBody() throws IOException {
        if (!this.chunked) {
            return this.fill();
        }
        if (this.lastChunkSeen) {
            return -1;
        }
        while (true) {
            int filled = this.fill();
            if (filled == -1) {
                return -1;
            }
            if (this.chunkRemaining > 0) {
                // Deliver up to chunkRemaining bytes as-is; demuxBuffered consumes them.
                return filled;
            }
            // Between chunks: consume framing bytes until real payload starts.
            while (this.bufferStart < this.bufferEnd) {
                byte b = this.buffer[this.bufferStart++];
                if (this.chunkSizeExpected) {
                    if (b == '\n') {
                        String token = this.chunkSizeLine.toString().trim();
                        this.chunkSizeLine.setLength(0);
                        if (token.isEmpty()) {
                            continue;   // the CRLF after a chunk's payload
                        }
                        int semicolon = token.indexOf(';');
                        if (semicolon >= 0) {
                            token = token.substring(0, semicolon);
                        }
                        long size;
                        try {
                            size = Long.parseLong(token, 16);
                        } catch (NumberFormatException e) {
                            throw new IOException(
                                "Malformed chunked stream from Docker daemon: bad chunk size '"
                                    + token + "'");
                        }
                        if (size == 0) {
                            this.lastChunkSeen = true;
                            return -1;
                        }
                        this.chunkRemaining = size;
                        this.chunkSizeExpected = false;
                        break;
                    }
                    if (b != '\r') {
                        this.chunkSizeLine.append((char) (b & 0xFF));
                    }
                }
            }
            if (this.chunkRemaining > 0 && this.bufferStart < this.bufferEnd) {
                return this.bufferEnd - this.bufferStart;
            }
        }
    }

    /** How many buffered bytes belong to the current chunk's payload. */
    private int payloadAvailable() {
        int available = this.bufferEnd - this.bufferStart;
        if (!this.chunked) {
            return available;
        }
        return (int) Math.min(available, this.chunkRemaining);
    }

    private void consumePayload(int count) {
        this.bufferStart += count;
        if (this.chunked) {
            this.chunkRemaining -= count;
            if (this.chunkRemaining == 0) {
                this.chunkSizeExpected = true;
            }
        }
    }

    /** Demultiplex whatever payload is buffered into at most one chunk; null = need more. */
    private @Nullable Chunk demuxBuffered() {
        int available = this.payloadAvailable();
        if (available <= 0) {
            return null;
        }
        if (this.rawFallback) {
            byte[] data = new byte[available];
            System.arraycopy(this.buffer, this.bufferStart, data, 0, available);
            this.consumePayload(available);
            return new Chunk(false, data);
        }
        if (this.framePayloadRemaining > 0) {
            int take = Math.min(available, this.framePayloadRemaining);
            byte[] data = new byte[take];
            System.arraycopy(this.buffer, this.bufferStart, data, 0, take);
            this.consumePayload(take);
            this.framePayloadRemaining -= take;
            return new Chunk(this.frameStderr, data);
        }
        // Accumulate the 8-byte frame header, possibly across reads and chunk borders.
        while (this.frameHeaderFilled < 8 && this.payloadAvailable() > 0) {
            this.frameHeader[this.frameHeaderFilled++] = this.buffer[this.bufferStart];
            this.consumePayload(1);
            if (this.frameHeaderFilled == 1) {
                int streamType = this.frameHeader[0] & 0xFF;
                if (streamType > 2) {
                    // Not a multiplexed stream (TTY workload): raw pass-through from here on.
                    this.rawFallback = true;
                    byte[] first = new byte[] { this.frameHeader[0] };
                    this.frameHeaderFilled = 0;
                    return new Chunk(false, first);
                }
            }
        }
        if (this.frameHeaderFilled < 8) {
            return null;
        }
        this.frameHeaderFilled = 0;
        this.frameStderr = (this.frameHeader[0] & 0xFF) == 2;
        this.framePayloadRemaining = ((this.frameHeader[4] & 0xFF) << 24)
            | ((this.frameHeader[5] & 0xFF) << 16)
            | ((this.frameHeader[6] & 0xFF) << 8)
            | (this.frameHeader[7] & 0xFF);
        if (this.framePayloadRemaining < 0) {
            this.rawFallback = true;
            this.framePayloadRemaining = 0;
        }
        return this.demuxBuffered();
    }

    private void settle(@NonNull Termination termination, @NonNull String detail) {
        this.termination = termination;
        this.detail = detail == null ? "" : detail;
        this.connection.close();
    }

    @Override
    public void writeStdin(byte @NonNull [] data) throws IOException {
        if (!this.stdinOpen) {
            throw new IOException("This Docker stream endpoint does not accept stdin writes");
        }
        if (this.termination != null) {
            throw new IOException("Docker stream is over (" + this.termination + ")");
        }
        this.connection.write(data);
    }

    @Override
    public @NonNull Termination termination() {
        Termination current = this.termination;
        if (current == null) {
            throw new IllegalStateException("Docker stream is still live");
        }
        return current;
    }

    @Override
    public @NonNull String detail() {
        return this.detail;
    }

    @Override
    public void close() {
        this.consumerClosed = true;
        this.connection.close();
    }

    /** The transport pipe is fully released (socket closed / subprocess dead). */
    public boolean isReleased() {
        return this.connection.isReleased();
    }
}
