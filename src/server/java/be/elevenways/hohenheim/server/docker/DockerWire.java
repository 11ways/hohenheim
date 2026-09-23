package be.elevenways.hohenheim.server.docker;

import be.elevenways.hohenheim.server.util.Http11;
import be.elevenways.hohenheim.server.util.Watchdog;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ScheduledFuture;

/**
 * The STREAMED exchanges of {@link DockerClient}, over the {@link DockerStreamTransport}
 * lane: a response body copied straight to disk, and a request body produced straight onto
 * the wire, so neither a volume snapshot nor an image tar nor a build context ever sits in
 * controller heap.
 *
 * @author  Jelle De Loecker
 * @since   0.1.0
 */
final class DockerWire {

    static final String PEER = "Docker daemon";

    /** Cap on an error body read for its evidence (protects the heap, not UX). */
    static final int MAX_ERROR_BODY = 64 * 1024;

    private DockerWire() {
    }

    /** Produces a streamed request body; must not close the stream it is handed. */
    interface BodyProducer {
        void writeTo(@NonNull OutputStream body) throws IOException;
    }

    /**
     * Stream one GET response's body straight to {@code outFile}, with the cap enforced on
     * the wire. A failed or over-cap read deletes the partial file before rethrowing, so a
     * truncated download can never pass for the payload.
     *
     * @param deadlineMs whole-exchange deadline, enforced by closing the connection
     * @return the number of bytes written
     */
    static long downloadToFile(@NonNull DockerStreamTransport transport, long connectTimeoutMs,
                               long deadlineMs, @NonNull String path, @NonNull Path outFile,
                               long maxBytes) throws IOException {
        byte[] request = Http11.request("GET", path, "docker", null, null, null);
        DockerStreamConnection connection = transport.openStream(request, connectTimeoutMs);
        ScheduledFuture<?> watchdog = Watchdog.schedule(connection::close, deadlineMs);
        try (InputStream in = new ConnectionInputStream(connection)) {
            Http11.Head head = Http11.readHead(in, PEER);
            if ((head.status() < 200 || head.status() >= 300) && head.status() != 304) {
                throw apiError(head.status(), errorBody(in, head));
            }
            try (OutputStream file = Files.newOutputStream(outFile, StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING)) {
                return Http11.copyBody(in, head, file, maxBytes, PEER);
            } catch (IOException e) {
                Files.deleteIfExists(outFile);
                throw e;
            }
        } catch (IOException e) {
            if (watchdog.isDone()) {
                throw new IOException("Docker archive download timed out after "
                    + deadlineMs + "ms", e);
            }
            throw e;
        } finally {
            watchdog.cancel(false);
            connection.close();
        }
    }

    /**
     * One request whose body is PRODUCED onto the wire (chunked), with the daemon's answer
     * read CONCURRENTLY on its own thread.
     *
     * AIDEV-NOTE: the concurrent read is load-bearing twice. A daemon that REFUSES the
     * request (a 404 for an archive target that does not exist) answers and stops reading
     * long before the body is sent: the write then dies with a broken pipe while the real
     * reason is already on the socket, and it is that reason which is thrown. And a daemon
     * that streams progress while it consumes the body (image load) could otherwise fill
     * the socket buffer and stall the upload until the deadline. A failure of the PRODUCER
     * itself (a local file vanished mid-archive) closes the connection with the chunked body
     * unterminated, so the daemon discards it rather than acting on a truncated payload.
     *
     * @return the answer body (small; capped at {@code maxAnswerBytes})
     * @throws DockerClient.ApiException for a non-2xx answer (304 counts as success)
     */
    static @NonNull String upload(@NonNull DockerStreamTransport transport, long connectTimeoutMs,
                                  long deadlineMs, @NonNull String method, @NonNull String path,
                                  @NonNull String contentType, @NonNull BodyProducer producer,
                                  long maxAnswerBytes) throws IOException {
        byte[] head = Http11.chunkedRequestHead(method, path, "docker", contentType, null);
        DockerStreamConnection connection = transport.openStream(head, connectTimeoutMs);
        ScheduledFuture<?> watchdog = Watchdog.schedule(connection::close, deadlineMs);
        ConnectionOutputStream wire = new ConnectionOutputStream(connection);
        InputStream in = new ConnectionInputStream(connection);
        CompletableFuture<Answer> answer = new CompletableFuture<>();
        Thread reader = Thread.ofPlatform().daemon().name("docker-upload-answer").start(() -> {
            try {
                answer.complete(readAnswer(in, maxAnswerBytes));
            } catch (Throwable failure) {
                answer.completeExceptionally(failure);
            }
        });
        try {
            IOException sendFailure = null;
            Http11.ChunkedOutputStream chunked = new Http11.ChunkedOutputStream(wire);
            try {
                producer.writeTo(chunked);
                chunked.finish();
            } catch (IOException failed) {
                if (!wire.failed()) {
                    // The PRODUCER failed: never complete the body, and never wait for an
                    // answer to a request the daemon must discard.
                    connection.close();
                    throw failed;
                }
                sendFailure = failed;
            }
            Answer result;
            try {
                result = awaitAnswer(answer);
            } catch (IOException noAnswer) {
                if (sendFailure != null) {
                    sendFailure.addSuppressed(noAnswer);
                    throw sendFailure;
                }
                throw noAnswer;
            }
            if ((result.status() < 200 || result.status() >= 300) && result.status() != 304) {
                throw apiError(result.status(), result.body());
            }
            if (sendFailure != null) {
                throw sendFailure;
            }
            return result.body();
        } catch (IOException e) {
            if (watchdog.isDone()) {
                throw new IOException("Docker upload " + method + " timed out after "
                    + deadlineMs + "ms", e);
            }
            throw e;
        } finally {
            watchdog.cancel(false);
            connection.close();
            try {
                reader.join(2_000);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private record Answer(int status, @NonNull String body) {}

    private static @NonNull Answer readAnswer(@NonNull InputStream in, long maxAnswerBytes)
            throws IOException {
        Http11.Head head = Http11.readHead(in, PEER);
        boolean success = (head.status() >= 200 && head.status() < 300) || head.status() == 304;
        if (!success) {
            return new Answer(head.status(), errorBody(in, head));
        }
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        Http11.copyBody(in, head, body, maxAnswerBytes, PEER);
        return new Answer(head.status(), body.toString(StandardCharsets.UTF_8));
    }

    private static @NonNull Answer awaitAnswer(@NonNull CompletableFuture<Answer> answer)
            throws IOException {
        try {
            return answer.get();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while waiting for the Docker daemon's answer");
        } catch (ExecutionException failed) {
            Throwable cause = failed.getCause();
            if (cause instanceof IOException io) {
                throw io;
            }
            throw new IOException("Reading the Docker daemon's answer failed: " + cause, cause);
        }
    }

    /** Whatever of an error body arrives, as text: the daemon's reason is the evidence. */
    static @NonNull String errorBody(@NonNull InputStream in, Http11.@NonNull Head head) {
        ByteArrayOutputStream error = new ByteArrayOutputStream();
        try {
            Http11.copyBody(in, head, error, MAX_ERROR_BODY, PEER);
        } catch (IOException partial) {
            // whatever was read is the evidence
        }
        return error.toString(StandardCharsets.UTF_8);
    }

    static DockerClient.@NonNull ApiException apiError(int status, @NonNull String body) {
        return new DockerClient.ApiException(status, "Docker API returned HTTP " + status + ": "
            + body.trim());
    }

    /**
     * Blocking InputStream over a {@link DockerStreamConnection}. A connection may answer a
     * read with 0 bytes ("nothing yet"); InputStream's contract forbids returning 0 for a
     * positive length, so this retries with a 1ms pause -- bounded in CPU, unbounded in time
     * (the lane watchdog owns the deadline by closing the connection).
     */
    static final class ConnectionInputStream extends InputStream {

        private final DockerStreamConnection connection;

        ConnectionInputStream(@NonNull DockerStreamConnection connection) {
            this.connection = connection;
        }

        @Override
        public int read() throws IOException {
            byte[] one = new byte[1];
            int n = this.read(one, 0, 1);
            return n < 0 ? -1 : one[0] & 0xFF;
        }

        @Override
        public int read(byte @NonNull [] buffer, int offset, int length) throws IOException {
            if (length == 0) {
                return 0;
            }
            while (true) {
                int n = this.connection.read(buffer, offset, length);
                if (n != 0) {
                    return n;
                }
                try {
                    Thread.sleep(1);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Docker stream read interrupted");
                }
            }
        }

        @Override
        public void close() {
            this.connection.close();
        }
    }

    /**
     * OutputStream over a {@link DockerStreamConnection} that REMEMBERS whether the wire
     * itself refused a write -- the one fact that tells a daemon that stopped reading apart
     * from a local producer failure.
     */
    static final class ConnectionOutputStream extends OutputStream {

        private final DockerStreamConnection connection;
        private volatile boolean failed;

        ConnectionOutputStream(@NonNull DockerStreamConnection connection) {
            this.connection = connection;
        }

        boolean failed() {
            return this.failed;
        }

        @Override
        public void write(int value) throws IOException {
            this.write(new byte[] { (byte) value }, 0, 1);
        }

        @Override
        public void write(byte @NonNull [] data, int offset, int length) throws IOException {
            if (length == 0) {
                return;
            }
            byte[] slice = offset == 0 && length == data.length
                ? data : Arrays.copyOfRange(data, offset, offset + length);
            try {
                this.connection.write(slice);
            } catch (IOException | RuntimeException e) {
                this.failed = true;
                throw e instanceof IOException io ? io : new IOException(e);
            }
        }
    }

    /**
     * Incremental stdout/stderr frame demultiplexer AS an OutputStream, so
     * {@link Http11#copyBody} feeds it whatever body shape the daemon chose (raw hijack or
     * chunked). Stdout payload goes to the target stream under its own byte cap; stderr is
     * kept up to 64KiB for the error report and counted beyond it. Exec streams are created
     * {@code Tty: false}, so an unframed byte sequence here is a protocol violation, not a
     * TTY fallback.
     */
    static final class FrameDemuxStream extends OutputStream {

        private final OutputStream stdout;
        private final long maxStdoutBytes;
        private final ByteArrayOutputStream stderr = new ByteArrayOutputStream();

        private final byte[] header = new byte[8];
        private int headerFilled;
        private int payloadRemaining;
        private boolean currentStderr;
        private long stdoutWritten;

        FrameDemuxStream(@NonNull OutputStream stdout, long maxStdoutBytes) {
            this.stdout = stdout;
            this.maxStdoutBytes = maxStdoutBytes;
        }

        @Override
        public void write(int value) throws IOException {
            this.write(new byte[] { (byte) value }, 0, 1);
        }

        @Override
        public void write(byte @NonNull [] data, int offset, int length) throws IOException {
            int pos = offset;
            int end = offset + length;
            while (pos < end) {
                if (this.payloadRemaining > 0) {
                    int take = Math.min(end - pos, this.payloadRemaining);
                    if (this.currentStderr) {
                        if (this.stderr.size() < 64 * 1024) {
                            this.stderr.write(data, pos,
                                Math.min(take, 64 * 1024 - this.stderr.size()));
                        }
                    } else {
                        this.stdoutWritten += take;
                        if (this.stdoutWritten > this.maxStdoutBytes) {
                            throw new Http11.BodyCapExceededException("Exec stdout exceeds the "
                                + this.maxStdoutBytes + "-byte cap");
                        }
                        this.stdout.write(data, pos, take);
                    }
                    this.payloadRemaining -= take;
                    pos += take;
                    continue;
                }
                this.header[this.headerFilled++] = data[pos++];
                if (this.headerFilled < 8) {
                    continue;
                }
                this.headerFilled = 0;
                int streamType = this.header[0] & 0xFF;
                if (streamType > 2 || this.header[1] != 0 || this.header[2] != 0
                        || this.header[3] != 0) {
                    throw new IOException("Docker exec stream is not frame-multiplexed"
                        + " (first header byte " + streamType + "); a Tty:false exec"
                        + " must be framed");
                }
                this.currentStderr = streamType == 2;
                this.payloadRemaining = ((this.header[4] & 0xFF) << 24)
                    | ((this.header[5] & 0xFF) << 16)
                    | ((this.header[6] & 0xFF) << 8)
                    | (this.header[7] & 0xFF);
                if (this.payloadRemaining < 0) {
                    throw new IOException("Docker exec stream frame declares a negative size");
                }
            }
        }

        /** @throws IOException when the stream ended inside a frame (truncated dump) */
        void finish() throws IOException {
            if (this.headerFilled != 0 || this.payloadRemaining != 0) {
                throw new IOException("Docker exec stream ended mid-frame ("
                    + this.payloadRemaining + " payload bytes missing): truncated output");
            }
            this.stdout.flush();
        }

        @NonNull String stderrText() {
            return this.stderr.toString(StandardCharsets.UTF_8);
        }

        long stdoutBytes() {
            return this.stdoutWritten;
        }
    }
}
