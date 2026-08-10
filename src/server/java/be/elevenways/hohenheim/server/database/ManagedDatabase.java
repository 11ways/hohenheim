package be.elevenways.hohenheim.server.database;

import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.hohenheim.model.DatabaseModel;
import be.elevenways.hohenheim.server.docker.ContainerHardening;
import be.elevenways.hohenheim.server.docker.DockerClient;
import be.elevenways.hohenheim.server.runtime.ContainerState;
import be.elevenways.hohenheim.server.runtime.WorkloadLiveness;
import be.elevenways.protoblast.common.util.BlastString;
import be.elevenways.zenit.common.orm.datasource.Row;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * The ENGINE half of the managed-database tier: the engine vocabulary (image, port, data
 * path, hardening, ready/dump/restore commands) and the operations that run a client
 * INSIDE an already-running engine container -- readiness probe, backup, restore.
 *
 * AIDEV-NOTE: this class no longer owns a lifecycle. Provisioning, status and teardown
 * lowered onto the canonical runtime-resource contract in the Phase 7 database wave: a
 * database's engine IS an owned {@code hohenheim:database_container} instance driven by
 * {@link DatabaseInstances} through {@code InstanceService}. What used to be
 * {@code provision}/{@code status}/{@code destroy} here was a SECOND, weaker copy of the
 * instance tier's create-start-verify-teardown discipline -- no fence, no host lease, no
 * capacity booking, no reconciler classification -- and it was deleted rather than
 * wrapped. Every method left takes a container HANDLE, because naming the container is
 * the instance tier's job now.
 *
 * @author  Jelle De Loecker
 * @since   0.1.0
 */
public class ManagedDatabase {

    /** Supported engines with their default image, port, data path, footprint, and env mapping. */
    public enum Engine {
        POSTGRES("postgres:17-alpine", 5432, "/var/lib/postgresql/data", 512),
        MYSQL("mysql:8.0", 3306, "/var/lib/mysql", 1024),
        REDIS("redis:7-alpine", 6379, "/data", 512),
        MONGO("mongo:7", 27017, "/data/db", 1280);

        final String defaultImage;
        final int port;
        final String dataPath;
        final int footprintMb;

        Engine(String defaultImage, int port, String dataPath, int footprintMb) {
            this.defaultImage = defaultImage;
            this.port = port;
            this.dataPath = dataPath;
            this.footprintMb = footprintMb;
        }

        /**
         * This engine's DECLARED memory footprint (MB): both what the capacity ledger
         * books and, because charge == cap, the cgroup ceiling the daemon applies when
         * the operator declares no {@code memory_limit_mb}.
         *
         * Re-measured 2026-08-07 through the product's own funnel, reading the engine
         * cgroup's {@code memory.peak} at several caps each: redis 18 MiB, postgres
         * 117 MiB, mysql 596 MiB, mongo 835 MiB. Each declared footprint leaves its
         * engine at least a fifth of it spare at that peak, which is what
         * {@code EngineMemoryCeilingTest} pins per engine.
         *
         * AIDEV-NOTE: THE RULE these numbers follow, and the mistake that produced the
         * previous ones. A peak read at a cap the workload is PINNED AGAINST is not a
         * peak -- it IS the cap. The 768 MB this table declared for mysql and mongo came
         * from peaks measured at 512, where both were clipped, so the numbers it recorded
         * (620 and 531 MiB) were too low and 768 was still marginal: mongo really wants
         * 835 MiB and ran flush against 768, which is the ECONNREFUSED flake all over
         * again. The peak is bounded, not cap-following -- mongo measured the same 835 MiB
         * at a 1024 cap and at a 1280 cap -- so the headroom rule terminates rather than
         * chasing the ceiling upwards. A measured peak can PROVE a cap is too small; it can
         * never justify LOWERING a cap that must also survive QUERY load, which no startup
         * measurement observes, so redis (18 MiB) and postgres (117 MiB) keep their 512.
         * These are measurements with a date, not assumptions; an operator who knows better
         * has the {@code memory_limit_mb} setting as the escape hatch.
         *
         * AIDEV-NOTE: the ephemeral (tmpfs) shape is what these numbers cover, and its
         * data directory is charged to the SAME cgroup -- ~300 MiB of mongo's 835 is the
         * tmpfs itself. So is the readiness probe: {@code awaitReady} execs a client at
         * 2 Hz INSIDE the container, and a mongosh is a Node process. A persistent
         * database keeps its data in page cache the kernel can drop, so booking the
         * ephemeral shape is the conservative direction.
         */
        public int footprintMb() {
            return this.footprintMb;
        }

        /** The largest footprint any engine declares: what an UNRECOGNISED engine books. */
        public static int maxFootprintMb() {
            int max = 0;
            for (Engine engine : values()) {
                max = Math.max(max, engine.footprintMb);
            }
            return max;
        }

        /** The lowercase token this engine is stored as ({@link DatabaseModel#ENGINE}). */
        public String token() {
            return BlastString.lower(name());
        }

        /** The engine's own port INSIDE the container. */
        public int port() {
            return this.port;
        }

        /**
         * This engine's DECLARED container isolation profile.
         *
         * AIDEV-NOTE: measured against the real daemon, not assumed -- every one of the
         * four engine images chowns its data directory and drops root to a service user
         * at entrypoint, and every one of them refuses to start under STRICT. A new
         * engine declares its own value here; it never gets one by resembling another.
         */
        public ContainerHardening.Profile hardening() {
            return switch (this) {
                case POSTGRES, MYSQL, REDIS, MONGO -> ContainerHardening.SERVICE;
            };
        }

        /**
         * The NON-SECRET half of the engine's initialization environment: what may live
         * in the instance settings JSON in the clear.
         */
        public @NonNull Map<String, String> env(@Nullable String user, @Nullable String database) {
            String safeUser = user == null ? "" : user;
            String safeDatabase = database == null ? "" : database;
            Map<String, String> env = new LinkedHashMap<>();
            switch (this) {
                case POSTGRES -> {
                    env.put("POSTGRES_USER", safeUser);
                    env.put("POSTGRES_DB", safeDatabase);
                }
                case MYSQL -> {
                    env.put("MYSQL_DATABASE", safeDatabase);
                    env.put("MYSQL_USER", safeUser);
                }
                case MONGO -> {
                    env.put("MONGO_INITDB_ROOT_USERNAME", safeUser);
                    env.put("MONGO_INITDB_DATABASE", safeDatabase);
                }
                case REDIS -> {
                    // redis takes its password through containerCommandTemplate()
                }
            }
            return env;
        }

        /**
         * The PASSWORD-BEARING half of the engine's environment, written to the instance
         * through the encrypted secret-variable lane and merged into the container
         * environment at deploy -- never into the settings JSON.
         *
         * AIDEV-NOTE: Redis declares {@code REDIS_PASSWORD} even though the engine
         * ignores that variable: the value is consumed by the {@code {{REDIS_PASSWORD}}}
         * placeholder in {@link #containerCommandTemplate()}, which is the only
         * substitution lane a secret can reach. The key is deliberately NOT
         * {@code REDISCLI_AUTH} -- an ambient auth variable would make a bare
         * {@code redis-cli ping} inside the container succeed and turn every
         * authentication negative control vacuous.
         */
        public @NonNull Map<String, String> secretEnv(@NonNull String password) {
            Map<String, String> env = new LinkedHashMap<>();
            switch (this) {
                case POSTGRES -> env.put("POSTGRES_PASSWORD", password);
                case MYSQL -> {
                    env.put("MYSQL_ROOT_PASSWORD", password);
                    env.put("MYSQL_PASSWORD", password);
                }
                case MONGO -> env.put("MONGO_INITDB_ROOT_PASSWORD", password);
                case REDIS -> env.put("REDIS_PASSWORD", password);
            }
            return env;
        }

        /**
         * Container command override with {@code {{KEY}}} placeholders resolved against
         * the instance's variables, or null to keep the image's default.
         */
        public @Nullable String containerCommandTemplate() {
            return this == REDIS ? "redis-server --requirepass {{REDIS_PASSWORD}}" : null;
        }

        /** The tool + args that dump this engine to stdout as text (SQL). */
        List<String> dumpCommand(String user, String database) {
            return switch (this) {
                case POSTGRES -> List.of("pg_dump", "-U", user, "-d", database);
                // --no-tablespaces: MySQL 8 otherwise needs the global PROCESS privilege, which a
                // per-database app user lacks. --single-transaction: consistent InnoDB dump, no locks.
                case MYSQL -> List.of("mysqldump", "--no-tablespaces", "--single-transaction",
                    "-u", user, database);
                case REDIS, MONGO -> throw new UnsupportedOperationException(
                    this + " has no text dump; use backupToFile for its binary dump");
            };
        }

        /** Env for {@link #dumpCommand}/{@link #restoreCommand} so the password never appears
         *  in argv or captured stdout. */
        List<String> dumpEnv(String password) {
            return switch (this) {
                case POSTGRES -> List.of("PGPASSWORD=" + password);
                case MYSQL -> List.of("MYSQL_PWD=" + password);
                case REDIS, MONGO -> List.of();
            };
        }

        /** File extension for this engine's dump artifact. */
        String dumpExtension() {
            return switch (this) {
                case POSTGRES, MYSQL -> "sql";
                case REDIS -> "rdb";
                case MONGO -> "archive";
            };
        }

        /** MIME type of this engine's dump artifact. */
        public String dumpContentType() {
            return switch (this) {
                case POSTGRES, MYSQL -> "application/sql";
                case REDIS, MONGO -> "application/octet-stream";
            };
        }

        /** The tool + args that load a dump file (already inside the container) back in. */
        List<String> restoreCommand(String user, String password, String database, String filePath) {
            return switch (this) {
                // ON_ERROR_STOP makes a failed statement abort with non-zero (no silent half-restore).
                case POSTGRES -> List.of("psql", "-v", "ON_ERROR_STOP=1", "-U", user, "-d", database,
                    "-f", filePath);
                // mysql's `source` builtin reads the file -- avoids a shell redirect.
                case MYSQL -> List.of("mysql", "-u", user, database, "-e", "source " + filePath);
                // --drop replaces existing collections; the archive carries its own db name.
                case MONGO -> List.of("mongorestore", "--username", user, "--password", password,
                    "--authenticationDatabase", "admin", "--drop", "--archive=" + filePath);
                case REDIS -> throw new UnsupportedOperationException(
                    "redis restore goes through restoreFromFile (RDB swap + restart), not a client command");
            };
        }

        // AIDEV-NOTE: Probe over TCP (-h 127.0.0.1), not the unix socket. Postgres/MySQL run a
        // socket-only temporary server during init, so only the real server binds TCP. Mongo's
        // init server DOES bind TCP, so its probe must authenticate to confirm the real (auth-
        // enabled) server with the root user is up -- an unauthenticated ping passes too early.
        List<String> readyCommand(String user, String password, String database) {
            return switch (this) {
                case POSTGRES -> List.of("pg_isready", "-h", "127.0.0.1", "-p", String.valueOf(port),
                    "-U", user, "-d", database);
                case MYSQL -> List.of("mysqladmin", "ping", "-h", "127.0.0.1", "-P", String.valueOf(port),
                    "-u", user);
                case REDIS -> List.of("redis-cli", "-p", String.valueOf(port), "ping");
                case MONGO -> List.of("mongosh", "--host", "127.0.0.1", "--port", String.valueOf(port),
                    "--username", user, "--password", password, "--authenticationDatabase", "admin",
                    "--quiet", "--eval", "db.runCommand({ ping: 1 }).ok");
            };
        }

        /** Env for {@link #readyCommand} (auth where the probe requires it). */
        public List<String> readyEnv(String password) {
            return switch (this) {
                case MYSQL -> List.of("MYSQL_PWD=" + password);
                case REDIS -> password != null && !password.isBlank()
                    ? List.of("REDISCLI_AUTH=" + password) : List.of();
                default -> List.of();
            };
        }

        /**
         * The STDOUT text a successful readiness probe must contain, or null when this
         * engine's probe reports honestly through its exit code alone.
         *
         * AIDEV-NOTE: redis is the trap this exists for. {@code redis-cli} exits ZERO
         * while printing {@code NOAUTH Authentication required.} to stdout, so an
         * exit-code-only probe passes against a server that answered nothing -- and the
         * old docblock here claimed the opposite ("redis-cli exits non-zero on a NOAUTH
         * error reply"). Measured against redis:7-alpine, it does not. The probe now
         * demands the POSITIVE anchor {@code PONG}.
         */
        @Nullable String readyStdoutContains() {
            return this == REDIS ? "PONG" : null;
        }
    }

    /** Connection details for a provisioned database. */
    public record Connection(Engine engine, String host, int port,
                             String user, String password, String database) {}

    /**
     * Live container state for a managed database, plus the published host port when
     * running and whether the ENGINE inside that container is still alive.
     *
     * AIDEV-NOTE: {@code running()} deliberately still means "the container runs". The
     * OOM case is a second question ({@link WorkloadLiveness}) precisely because a
     * container whose engine was OOM-killed answers "running" to every daemon while
     * refusing every connection -- that gap is what {@link #workloadDead()} names.
     */
    public record LiveStatus(ContainerState state, Integer port, WorkloadLiveness liveness) {

        public LiveStatus(ContainerState state, Integer port) {
            this(state, port, state == ContainerState.RUNNING
                ? WorkloadLiveness.SERVING : WorkloadLiveness.UNKNOWN);
        }

        public boolean running() {
            return state == ContainerState.RUNNING;
        }

        /** The container runs but the kernel killed the engine inside it. */
        public boolean workloadDead() {
            return running() && liveness == WorkloadLiveness.WORKLOAD_DEAD;
        }
    }

    /**
     * The engine a database record declares.
     *
     * @throws IllegalArgumentException when the stored token names no known engine
     */
    public static @NonNull Engine engineOf(@NonNull Row database) {
        return Engine.valueOf(BlastString.upper(
            String.valueOf((Object) database.get(DatabaseModel.ENGINE))));
    }

    private final DockerClient docker;

    /** @param docker a client aimed at the host the engine container runs on */
    public ManagedDatabase(DockerClient docker) {
        this.docker = docker;
    }

    /**
     * Back up a running database by running the engine's dump tool inside its container,
     * returning the dump as text (SQL for Postgres/MySQL). The whole dump is held in memory;
     * a streaming-to-file variant is a follow-up for large databases.
     *
     * @param handle the engine container's handle (the instance handle)
     * @throws IOException                   if the dump command exits non-zero
     * @throws UnsupportedOperationException if the engine has no text dump (Redis/Mongo)
     */
    public String backup(String handle, Engine engine, String user, String password,
                         String database) throws IOException {
        DockerClient.ExecResult result = docker.exec(handle,
            engine.dumpCommand(user, database), engine.dumpEnv(password));
        if (result.exitCode() != 0) {
            throw new IOException("Database backup of '" + handle + "' failed (exit "
                + result.exitCode() + "): " + result.stderr().trim());
        }
        return result.stdout();
    }

    /**
     * The heap guard on a binary dump fetch: the archive API buffers the whole response
     * through controller memory, so an unbounded read here is an OOM waiting for a big
     * enough database.
     */
    private static long maxDumpBytes() {
        Integer megabytes = HohenheimSettings.VALUES.getValue(HohenheimSettings.Database.MAX_DUMP_MB);
        return (megabytes == null || megabytes <= 0 ? 2048L : megabytes.longValue()) * 1024 * 1024;
    }

    /**
     * Back up any engine to a file: SQL text for Postgres/MySQL, the engine's native binary dump
     * for Redis (RDB snapshot) and Mongo (mongodump archive). Binary dumps are produced inside
     * the container, then fetched out via the archive API.
     */
    // AIDEV-NOTE: Binary dumps must land in the container's writable layer (/tmp), not on a
    // mount: the Docker archive API (getArchiveFile) cannot read files inside a tmpfs (or
    // volume) mount. So redis dumps via `--rdb /tmp/...` (not SAVE, which writes to the /data
    // mount) and mongodump targets /tmp.
    public void backupToFile(String handle, Engine engine, String user, String password,
                             String database, Path target) throws IOException {
        switch (engine) {
            case POSTGRES, MYSQL ->
                Files.writeString(target, backup(handle, engine, user, password, database),
                    StandardCharsets.UTF_8);
            case REDIS -> {
                String rdbPath = "/tmp/hohenheim-dump.rdb";
                DockerClient.ExecResult save = docker.exec(handle,
                    List.of("redis-cli", "--rdb", rdbPath), engine.readyEnv(password));
                if (save.exitCode() != 0) {
                    throw new IOException("redis dump failed for '" + handle + "': " + save.stderr().trim());
                }
                Files.write(target, docker.getArchiveFile(handle, rdbPath, maxDumpBytes()));
                // The exit code alone is a liar: redis-cli has shipped exit 0 while
                // writing an error line (NOAUTH) into the dump file. The restore path
                // checks the RDB magic; a backup that would fail that check is not a
                // backup and must fail NOW, at capture, not months later at restore.
                requireRdbMagic(target, "redis dump of '" + handle
                    + "' (redis-cli exited 0 but produced no RDB; an error reply such as"
                    + " NOAUTH may have landed in the dump file)");
            }
            case MONGO -> {
                String archivePath = "/tmp/hohenheim-dump.archive";
                DockerClient.ExecResult dump = docker.exec(handle, List.of("mongodump",
                    "--username", user, "--password", password, "--authenticationDatabase", "admin",
                    "--db", database, "--archive=" + archivePath));
                if (dump.exitCode() != 0) {
                    throw new IOException("mongodump failed for '" + handle + "': " + dump.stderr().trim());
                }
                Files.write(target, docker.getArchiveFile(handle, archivePath, maxDumpBytes()));
            }
        }
    }

    /**
     * Restore a text dump into a running database: upload it into the container and load it
     * with the engine's client. The dump must match the engine (e.g. {@code pg_dump} output for
     * Postgres). Restoring into a non-empty database may conflict; restore into a fresh one.
     *
     * @throws IOException if the upload or load command fails
     */
    public void restore(String handle, Engine engine, String user, String password,
                        String database, String dump) throws IOException {
        Path tempFile = Files.createTempFile("hohenheim-restore", "." + engine.dumpExtension());
        try {
            Files.writeString(tempFile, dump, StandardCharsets.UTF_8);
            restoreFromFile(handle, engine, user, password, database, tempFile);
        } finally {
            Files.deleteIfExists(tempFile);
        }
    }

    /**
     * Restore a dump file into a running database: push it into the container and load it with
     * the engine's client (binary-safe, so it handles SQL text and the Mongo archive alike).
     * Redis is restored by swapping its RDB and restarting the container.
     *
     * @throws UnsupportedOperationException for an ephemeral Redis (its tmpfs data dir is wiped
     *                                       by the restart the restore requires)
     */
    public void restoreFromFile(String handle, Engine engine, String user, String password,
                                String database, Path source) throws IOException {
        if (engine == Engine.REDIS) {
            restoreRedis(handle, user, password, database, source);
            return;
        }
        String fileName = "hohenheim-restore." + engine.dumpExtension();
        // Resolve the restore command first so an unsupported engine fails before any upload.
        List<String> command = engine.restoreCommand(user, password, database, "/tmp/" + fileName);

        Path tempDir = Files.createTempDirectory("hohenheim-restore");
        try {
            Files.copy(source, tempDir.resolve(fileName));
            docker.putArchiveFromDirectory(handle, "/tmp", tempDir);

            DockerClient.ExecResult result = docker.exec(handle, command, engine.dumpEnv(password));
            if (result.exitCode() != 0) {
                throw new IOException("Database restore of '" + handle + "' failed (exit "
                    + result.exitCode() + "): " + result.stderr().trim());
            }
        } finally {
            Files.deleteIfExists(tempDir.resolve(fileName));
            Files.deleteIfExists(tempDir);
        }
    }

    /** ASCII magic at the start of every RDB file ("REDIS" + 4-digit version). */
    private static final String REDIS_RDB_MAGIC = "REDIS";

    /**
     * ONE magic check for both directions: capture asserts what it produced, restore
     * asserts what it was handed.
     *
     * @throws IOException naming {@code what} when the file does not start with REDIS
     */
    static void requireRdbMagic(Path file, String what) throws IOException {
        byte[] header = new byte[REDIS_RDB_MAGIC.length()];
        try (var in = Files.newInputStream(file)) {
            if (in.read(header) != header.length
                || !REDIS_RDB_MAGIC.equals(new String(header, StandardCharsets.US_ASCII))) {
                throw new IOException("Not a redis RDB dump (missing REDIS magic): " + what);
            }
        }
    }

    // AIDEV-NOTE: Redis only reads its RDB at startup, so there is no live restore. Instead the
    // dump is copied into the data volume (via exec -- the archive API cannot write into mounts)
    // and the server is restarted around it: SHUTDOWN NOSAVE stops the container without saving
    // over the new file, and the restart loads it. A scheduled bgsave between the copy and the
    // shutdown could still overwrite it; that window is milliseconds and a retry recovers.
    private void restoreRedis(String handle, String user, String password, String database,
                              Path source) throws IOException {
        requireRdbMagic(source, String.valueOf(source));

        requirePersistentData(handle, Engine.REDIS.dataPath);

        String fileName = "hohenheim-restore.rdb";
        Path tempDir = Files.createTempDirectory("hohenheim-restore");
        try {
            Files.copy(source, tempDir.resolve(fileName));
            docker.putArchiveFromDirectory(handle, "/tmp", tempDir);
        } finally {
            Files.deleteIfExists(tempDir.resolve(fileName));
            Files.deleteIfExists(tempDir);
        }

        // "dump.rdb" in the data dir is the stock image's dbfilename/dir.
        DockerClient.ExecResult copy = docker.exec(handle,
            List.of("cp", "/tmp/" + fileName, Engine.REDIS.dataPath + "/dump.rdb"));
        if (copy.exitCode() != 0) {
            throw new IOException("redis restore of '" + handle + "' failed copying the RDB into place: "
                + copy.stderr().trim());
        }

        // The server exits without replying, so the exec result is unreliable; the stopped-state
        // poll below is the real confirmation.
        docker.exec(handle, List.of("redis-cli", "SHUTDOWN", "NOSAVE"),
            Engine.REDIS.readyEnv(password));
        waitForStopped(handle, 10_000);
        docker.startContainer(handle);
        awaitReady(docker, handle, Engine.REDIS, user, password, database, 60_000);
    }

    /** Reject restore-by-restart when the data dir is a tmpfs mount (wiped on restart). */
    private void requirePersistentData(String handle, String dataPath) throws IOException {
        Object mounts = docker.inspectContainer(handle).get("Mounts");
        if (mounts instanceof List<?> list) {
            for (Object mount : list) {
                if (mount instanceof Map<?, ?> m && dataPath.equals(m.get("Destination"))) {
                    if ("tmpfs".equals(m.get("Type"))) {
                        throw new UnsupportedOperationException("redis restore needs a persistent data"
                            + " volume; an ephemeral (tmpfs) data dir is wiped by the required restart");
                    }
                    return;
                }
            }
        }
        throw new UnsupportedOperationException(
            "redis restore needs a persistent data volume; none is mounted at " + dataPath);
    }

    private void waitForStopped(String handle, long timeoutMillis) throws IOException {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            Object state = docker.inspectContainer(handle).get("State");
            if (state instanceof Map<?, ?> s && !Boolean.TRUE.equals(s.get("Running"))) {
                return;
            }
            try {
                Thread.sleep(200);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted waiting for '" + handle + "' to stop");
            }
        }
        throw new IOException("Timed out waiting for '" + handle + "' to stop for restore");
    }

    /**
     * Block until the ENGINE reports it can serve queries, probing inside the container.
     *
     * AIDEV-NOTE: a docker-published port accepts connections via docker-proxy the instant
     * the container starts, well before the engine can serve anything -- so the probe runs
     * the engine's own client over TCP inside the container, and (where the engine's client
     * lies with its exit code) demands a POSITIVE anchor in stdout as well. This is a
     * PRODUCT-tier gate on purpose: the instance contract answers "is the workload
     * running", never "can this engine serve queries" -- the same split that keeps the site
     * tier's HTTP health probe in SiteReleases rather than in InstanceService.
     *
     * @throws IOException naming the engine when it never became ready
     */
    public static void awaitReady(DockerClient docker, String handle, Engine engine, String user,
                                  String password, String database, long timeoutMillis)
            throws IOException {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        List<String> command = engine.readyCommand(user, password, database);
        List<String> env = engine.readyEnv(password);
        String anchor = engine.readyStdoutContains();
        String lastOutput = null;
        IOException last = null;
        while (System.currentTimeMillis() < deadline) {
            try {
                DockerClient.ExecResult probe = docker.exec(handle, command, env);
                lastOutput = probe.stdout();
                if (probe.exitCode() == 0
                        && (anchor == null || probe.stdout().contains(anchor))) {
                    return;   // engine reports ready
                }
            } catch (IOException e) {
                last = e;   // exec can race container startup; retry until the deadline
            }
            try {
                Thread.sleep(500);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted waiting for " + engine + " readiness");
            }
        }
        throw new IOException("Timed out waiting for " + engine + " '" + database + "' to become ready"
            + (last != null ? " (" + last.getMessage() + ")" : "")
            + (lastOutput != null && !lastOutput.isBlank()
                ? " [last probe output: " + lastOutput.trim() + "]" : ""));
    }
}
