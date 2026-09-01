package be.elevenways.hohenheim.test.database;

import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.hohenheim.server.database.DatabaseService;
import be.elevenways.hohenheim.server.database.ManagedDatabase;
import be.elevenways.hohenheim.server.docker.DockerClient;
import be.elevenways.hohenheim.server.docker.DockerStreamConnection;
import be.elevenways.hohenheim.server.docker.DockerStreamTransport;
import be.elevenways.hohenheim.server.docker.DockerTransport;
import be.elevenways.hohenheim.server.runtime.ContainerState;
import be.elevenways.hohenheim.server.util.Http11;
import be.elevenways.hohenheim.server.runtime.WorkloadLiveness;
import be.elevenways.hohenheim.server.task.BackupDatabases;
import be.elevenways.hohenheim.test.HohenheimTestRuntime;
import be.elevenways.hohenheim.test.live.LiveLane;
import be.elevenways.hohenheim.test.network.PrivateNetns;
import be.elevenways.zenit.common.orm.datasource.Db;
import be.elevenways.zenit.server.orm.SqliteDatasource;
import be.elevenways.zenit.server.orm.migration.MigrationRunner;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * Streamed database dumps: the transfer cap is enforced ON the wire (naming its setting,
 * never shipping a partial file), and one database's failure never costs the others their
 * nightly backup -- the 2026-08-31 OOM incident's two observable halves.
 */
@Tag("slow") // live lane: needs a real daemon/host/image; runs via `zenit-dev test --all`
class StreamingBackupTest {

    private static PrivateNetns netns;

    @BeforeAll
    static void enforcePolicy() throws IOException {
        netns = PrivateNetns.installEnforcing();
        LiveLane.require(LiveLane.Need.NETNS, netns != null,
            "no private netns: record-backed provisioning refuses without an enforceable policy");
    }

    @AfterAll
    static void restorePolicy() {
        PrivateNetns.uninstall(netns);
        netns = null;
    }

    private static final Path SOCKET = Path.of(DockerClient.DEFAULT_SOCKET);
    private static final String PG_IMAGE = "postgres:17-alpine";

    /**
     * A dump larger than {@code database.max_dump_mb} is refused DURING the transfer,
     * the refusal names the setting, and no file (whole or partial) is left behind --
     * a partial dump passing for a backup is worse than no dump.
     */
    @Test
    void aDumpLargerThanTheCapIsRefusedNamingTheSettingAndLeavesNoFile() throws IOException {
        LiveLane.require(LiveLane.Need.DOCKER_SOCKET, Files.exists(SOCKET),
            "Docker socket not present");
        DockerClient docker = new DockerClient();
        LiveLane.requireImage(docker, PG_IMAGE);

        SqliteDatasource datasource = freshDatasource();
        DatabaseService service = new DatabaseService(datasource);
        String name = "cap" + System.nanoTime();
        Path dir = Files.createTempDirectory("hohenheim-cap-bk");
        Integer originalCap = HohenheimSettings.VALUES.getValue(
            HohenheimSettings.Database.MAX_DUMP_MB);
        try {
            service.create(name, ManagedDatabase.Engine.POSTGRES, PG_IMAGE,
                "appuser", "secret123", "appdb", true);   // ephemeral: tmpfs
            String handle = Db.supply(datasource, () -> EngineHandles.of(name));

            // 1. Seed well past 1 MB so the 1 MB cap must fire mid-transfer.
            DockerClient.ExecResult seed = docker.exec(handle,
                List.of("psql", "-U", "appuser", "-d", "appdb", "-c",
                    "CREATE TABLE bulk AS SELECT g AS id, repeat('x', 200) AS filler"
                        + " FROM generate_series(1, 20000) g;"),
                List.of("PGPASSWORD=secret123"));
            assertThat(seed.exitCode())
                .withFailMessage("step 1: seed failed: %s", seed.stderr()).isZero();

            HohenheimSettings.VALUES.setValue(HohenheimSettings.Database.MAX_DUMP_MB, 1);

            // 2. The over-cap dump is refused, naming the setting an operator would raise.
            Throwable refusal = catchThrowable(() -> service.backupToFile(name, dir, "snap"));
            assertThat(refusal)
                .as("step 2: an over-cap dump is refused, not silently shipped")
                .isInstanceOf(IOException.class)
                .hasMessageContaining("max_dump_mb");

            // 3. Nothing that could pass for a backup remains, partial file included.
            try (Stream<Path> files = Files.list(dir)) {
                assertThat(files.toList())
                    .as("step 3: no whole or partial dump file remains after the refusal")
                    .isEmpty();
            }

            // 4. With the cap restored, the SAME database dumps completely: the seeded
            //    first and last rows are both in the file, so nothing was truncated.
            HohenheimSettings.VALUES.setValue(HohenheimSettings.Database.MAX_DUMP_MB, originalCap);
            Path dump = service.backupToFile(name, dir, "full");
            assertThat(Files.size(dump))
                .as("step 4: the full dump is larger than the cap that refused it")
                .isGreaterThan(1024L * 1024);
            String content = Files.readString(dump);
            assertThat(content)
                .as("step 4: first and last seeded rows are present, so the stream is complete")
                .contains("CREATE TABLE")
                .contains("\n1\t").contains("\n20000\t");
        } finally {
            HohenheimSettings.VALUES.setValue(HohenheimSettings.Database.MAX_DUMP_MB, originalCap);
            try {
                service.destroy(name, true);
            } catch (IOException ignored) {
                // best effort
            }
            deleteRecursively(dir);
        }
    }

    /**
     * One database failing with a RUNTIME exception (the class the old IOException-only
     * catch let escape and abort the loop) must not cost the next database its backup.
     */
    @Test
    void oneFailingDatabaseDoesNotAbortTheNightlyLoop() throws IOException {
        Path backupRoot = Files.createTempDirectory("hohenheim-loop-bk");
        String originalPath = HohenheimSettings.VALUES.getValue(
            HohenheimSettings.Database.BACKUP_PATH);
        HohenheimSettings.VALUES.setValue(
            HohenheimSettings.Database.BACKUP_PATH, backupRoot.toString());
        try {
            DatabaseService twoDatabases = new DatabaseService() {

                @Override
                public List<Summary> summaries() {
                    return List.of(summary("broken"), summary("healthy"));
                }

                @Override
                public Path backupToFile(String name, Path directory, String baseName)
                        throws IOException {
                    if ("broken".equals(name)) {
                        throw new IllegalStateException("boom: not an IOException");
                    }
                    Files.createDirectories(directory);
                    Path dump = directory.resolve(baseName + ".sql");
                    Files.writeString(dump, "-- dump of " + name);
                    return dump;
                }
            };

            // 1. The loop survives the runtime failure instead of propagating it.
            BackupDatabases.Outcome[] captured = new BackupDatabases.Outcome[1];
            Throwable escaped = catchThrowable(
                () -> captured[0] = BackupDatabases.backupAll(twoDatabases));
            assertThat(escaped)
                .as("step 1: a per-database failure never escapes the loop")
                .isNull();

            // 2. The database AFTER the failing one still got its backup.
            try (Stream<Path> files = Files.list(backupRoot.resolve("healthy"))) {
                assertThat(files.toList())
                    .as("step 2: the healthy database was still backed up")
                    .hasSize(1);
            }

            // 3. The outcome is an honest partial: one dumped, one named failure -- the
            //    executor turns a non-empty failure list into a FAILED history row.
            assertThat(captured[0].backedUp())
                .as("step 3: exactly the healthy database counted").isEqualTo(1);
            assertThat(captured[0].failures())
                .as("step 3: the failure names the database and the reason")
                .singleElement().asString().startsWith("broken: ").contains("boom");
        } finally {
            HohenheimSettings.VALUES.setValue(
                HohenheimSettings.Database.BACKUP_PATH, originalPath);
            deleteRecursively(backupRoot);
        }
    }

    /**
     * MECHANISM proof over a scripted transport, no daemon: the exec streaming lane
     * demultiplexes stdout/stderr frames arriving at hostile read boundaries (splits
     * inside the response head, a frame header and a payload) straight to the caller's
     * stream, keeps stderr for the error report, and reads the exit code afterwards.
     */
    @Test
    void execStdoutFramesDemuxToTheTargetStreamAcrossHostileBoundaries() throws IOException {
        byte[] head = ("HTTP/1.1 200 OK\r\n"
            + "Content-Type: application/vnd.docker.raw-stream\r\n\r\n")
            .getBytes(StandardCharsets.ISO_8859_1);
        byte[] body = concat(frame(1, "hello "), frame(2, "noise\n"), frame(1, "world"));
        List<byte[]> reads = List.of(
            slice(head, 0, 9), slice(head, 9, head.length),
            slice(body, 0, 3), slice(body, 3, 12), slice(body, 12, 20),
            slice(body, 20, body.length));

        DockerClient docker = new DockerClient(new ScriptedExecTransport(reads, 0));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        DockerClient.ExecStreamResult result = docker.execStreamed(
            "c1", List.of("dump"), List.of(), out, 1024);

        assertThat(out.toString(StandardCharsets.UTF_8))
            .as("stdout frames land on the target stream, reassembled")
            .isEqualTo("hello world");
        assertThat(result.stderr())
            .as("stderr frames are kept for the error report")
            .isEqualTo("noise\n");
        assertThat(result.exitCode()).isZero();
        assertThat(result.stdoutBytes()).isEqualTo(11);
    }

    /** Stdout past the declared cap is refused mid-stream with the TYPED exception. */
    @Test
    void anOverCapExecStdoutIsRefusedWithTheTypedException() {
        byte[] head = "HTTP/1.1 200 OK\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1);
        List<byte[]> reads = List.of(head, frame(1, "0123456789ABCDEF"));

        DockerClient docker = new DockerClient(new ScriptedExecTransport(reads, 0));
        Throwable refusal = catchThrowable(() -> docker.execStreamed(
            "c1", List.of("dump"), List.of(), new ByteArrayOutputStream(), 8));
        assertThat(refusal)
            .as("an over-cap stdout stream is refused with the typed cap exception")
            .isInstanceOf(Http11.BodyCapExceededException.class);
    }

    private static byte[] slice(byte[] source, int from, int to) {
        return Arrays.copyOfRange(source, from, to);
    }

    /** Docker's multiplexed frame: [type,0,0,0,size(4be)] + payload. */
    private static byte[] frame(int type, String payload) {
        byte[] body = payload.getBytes(StandardCharsets.UTF_8);
        byte[] framed = new byte[8 + body.length];
        framed[0] = (byte) type;
        framed[4] = (byte) (body.length >>> 24);
        framed[5] = (byte) (body.length >>> 16);
        framed[6] = (byte) (body.length >>> 8);
        framed[7] = (byte) body.length;
        System.arraycopy(body, 0, framed, 8, body.length);
        return framed;
    }

    private static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] part : parts) {
            out.writeBytes(part);
        }
        return out.toByteArray();
    }

    /**
     * Both transport faces scripted: roundTrip answers the exec bookkeeping (create,
     * exit code), openStream replays the canned reads -- honouring the REQUESTED length,
     * because the head parser reads one byte at a time.
     */
    private static final class ScriptedExecTransport
            implements DockerTransport, DockerStreamTransport {

        private final List<byte[]> reads;
        private final int exitCode;

        ScriptedExecTransport(List<byte[]> reads, int exitCode) {
            this.reads = reads;
            this.exitCode = exitCode;
        }

        @Override
        public byte[] roundTrip(byte[] request, long timeoutMs) throws IOException {
            return roundTrip(request, timeoutMs, Long.MAX_VALUE);
        }

        @Override
        public byte[] roundTrip(byte[] request, long timeoutMs, long maxResponseBytes)
                throws IOException {
            String text = new String(request, StandardCharsets.ISO_8859_1);
            String payload;
            if (text.startsWith("POST /containers/")) {
                payload = "{\"Id\":\"e1\"}";
            } else if (text.startsWith("GET /exec/e1/json")) {
                payload = "{\"ExitCode\":" + this.exitCode + "}";
            } else {
                throw new IOException("unexpected request: " + text.split("\r\n")[0]);
            }
            String head = "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n"
                + "Content-Length: " + payload.length() + "\r\nConnection: close\r\n\r\n";
            return (head + payload).getBytes(StandardCharsets.ISO_8859_1);
        }

        @Override
        public DockerStreamConnection openStream(byte[] request, long connectTimeoutMs) {
            ArrayDeque<byte[]> queue = new ArrayDeque<>(this.reads);
            return new DockerStreamConnection() {

                private volatile boolean closed;

                @Override
                public int read(byte[] buffer, int offset, int length) throws IOException {
                    if (this.closed) {
                        throw new IOException("connection closed");
                    }
                    byte[] step = queue.poll();
                    if (step == null) {
                        return -1;
                    }
                    if (step.length > length) {
                        queue.addFirst(Arrays.copyOfRange(step, length, step.length));
                        step = Arrays.copyOfRange(step, 0, length);
                    }
                    System.arraycopy(step, 0, buffer, offset, step.length);
                    return step.length;
                }

                @Override
                public void write(byte[] data) {
                }

                @Override
                public void close() {
                    this.closed = true;
                }

                @Override
                public boolean isReleased() {
                    return this.closed;
                }

                @Override
                public String diagnostics() {
                    return "";
                }
            };
        }
    }

    private static DatabaseService.Summary summary(String name) {
        return new DatabaseService.Summary(name, "postgres", "postgres:17-alpine", "appdb",
            "appuser", false, "local", "active", true, ContainerState.RUNNING, 5432,
            WorkloadLiveness.SERVING);
    }

    private static SqliteDatasource freshDatasource() throws IOException {
        File db = File.createTempFile("hohenheim-streaming-bk", ".db");
        db.delete();
        db.deleteOnExit();
        SqliteDatasource ds = new SqliteDatasource("jdbc:sqlite:" + db.getAbsolutePath());
        new MigrationRunner(ds).migrate().requireSuccess();
        HohenheimTestRuntime.ensureBooted();
        return ds;
    }

    private static void deleteRecursively(Path root) {
        try (Stream<Path> paths = Files.walk(root)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // best effort
                }
            });
        } catch (IOException ignored) {
            // best effort
        }
    }
}
