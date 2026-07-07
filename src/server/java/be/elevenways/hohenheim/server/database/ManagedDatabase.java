package be.elevenways.hohenheim.server.database;

import be.elevenways.hohenheim.server.docker.DockerClient;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Provisions database engines as managed Docker containers (Phase 3). Each database
 * runs in its own container with a named volume for persistence and an ephemeral
 * 127.0.0.1 published port; connection details are returned to the caller.
 *
 * @author  Jelle De Loecker
 * @since   0.1.0
 */
public class ManagedDatabase {

    /** Supported engines with their default image, port, data path, and env mapping. */
    public enum Engine {
        POSTGRES("postgres:17-alpine", 5432, "/var/lib/postgresql/data"),
        MYSQL("mysql:8.0", 3306, "/var/lib/mysql"),
        REDIS("redis:7-alpine", 6379, "/data"),
        MONGO("mongo:7", 27017, "/data/db");

        final String defaultImage;
        final int port;
        final String dataPath;

        Engine(String defaultImage, int port, String dataPath) {
            this.defaultImage = defaultImage;
            this.port = port;
            this.dataPath = dataPath;
        }

        Map<String, String> env(String user, String password, String database) {
            return switch (this) {
                case POSTGRES -> Map.of(
                    "POSTGRES_USER", user,
                    "POSTGRES_PASSWORD", password,
                    "POSTGRES_DB", database);
                case MYSQL -> Map.of(
                    "MYSQL_ROOT_PASSWORD", password,
                    "MYSQL_DATABASE", database,
                    "MYSQL_USER", user,
                    "MYSQL_PASSWORD", password);
                case MONGO -> Map.of(
                    "MONGO_INITDB_ROOT_USERNAME", user,
                    "MONGO_INITDB_ROOT_PASSWORD", password,
                    "MONGO_INITDB_DATABASE", database);
                case REDIS -> Map.of();   // redis takes its password via containerCommand
            };
        }

        /** Container command override, or null to keep the image's default. */
        @Nullable List<String> containerCommand(String password) {
            if (this == REDIS && password != null && !password.isBlank()) {
                return List.of("redis-server", "--requirepass", password);
            }
            return null;
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
        List<String> readyEnv(String password) {
            return switch (this) {
                case MYSQL -> List.of("MYSQL_PWD=" + password);
                // redis-cli exits non-zero on a NOAUTH error reply, so the probe only
                // passes once the authenticated server is really up.
                case REDIS -> password != null && !password.isBlank()
                    ? List.of("REDISCLI_AUTH=" + password) : List.of();
                default -> List.of();
            };
        }

    }

    /** Connection details for a provisioned database. */
    public record Connection(Engine engine, String host, int port,
                             String user, String password, String database) {}

    private final DockerClient docker;

    public ManagedDatabase(DockerClient docker) {
        this.docker = docker;
    }

    /**
     * Provision (or re-provision) a database container and block until its port accepts
     * connections.
     *
     * @param name      stable database name (container + volume are derived from it)
     * @param engine    database engine
     * @param image     image override, or null for the engine default
     * @param user      application user
     * @param password  application password
     * @param database  initial database name
     * @param ephemeral when true the data directory is a RAM-backed tmpfs mount (fast, no host
     *                  disk I/O, discarded with the container) instead of a persistent named
     *                  volume -- suited to tests, CI, and preview environments
     */
    public Connection provision(String name, Engine engine, String image,
                                String user, String password, String database,
                                boolean ephemeral) throws IOException {
        String containerName = "hohenheim-db-" + name;
        String volumeName = containerName + "-data";
        String imageRef = (image == null || image.isBlank()) ? engine.defaultImage : image;

        docker.ensureImage(imageRef, null);

        // Replace any prior container for this database; a persistent named volume keeps the data.
        try {
            docker.removeContainer(containerName, true);
        } catch (IOException ignored) {
            // nothing to replace
        }

        String id = docker.createContainer(containerName,
            buildSpec(engine, imageRef, volumeName, engine.env(user, password, database),
                engine.containerCommand(password), ephemeral));
        docker.startContainer(id);

        waitForReady(id, engine, user, password, database, 60_000);
        int hostPort = docker.publishedPort(id, engine.port);

        return new Connection(engine, "127.0.0.1", hostPort, user, password, database);
    }

    /** Live container state for a managed database: running plus its published host port. */
    public record LiveStatus(boolean running, Integer port) {}

    /** Best-effort live status of a database's container; (false, null) if absent or unreachable. */
    public LiveStatus status(String name, Engine engine) {
        String containerName = "hohenheim-db-" + name;
        try {
            Object state = docker.inspectContainer(containerName).get("State");
            boolean running = state instanceof Map<?, ?> s && Boolean.TRUE.equals(s.get("Running"));
            Integer port = null;
            if (running) {
                try {
                    port = docker.publishedPort(containerName, engine.port);
                } catch (IOException ignored) {
                    // not published yet / not ready
                }
            }
            return new LiveStatus(running, port);
        } catch (IOException e) {
            return new LiveStatus(false, null);   // no such container or daemon unreachable
        }
    }

    /** Stop and remove the database container; optionally delete its data volume. */
    public void destroy(String name, boolean removeData) throws IOException {
        String containerName = "hohenheim-db-" + name;
        try {
            docker.stopContainer(containerName, 10);
        } catch (IOException ignored) {
            // proceed to remove
        }
        try {
            docker.removeContainer(containerName, true);
        } catch (IOException ignored) {
            // already gone
        }
        if (removeData) {
            try {
                docker.removeVolume(containerName + "-data", true);
            } catch (IOException ignored) {
                // already gone
            }
        }
    }

    /**
     * Back up a provisioned database by running the engine's dump tool inside its container,
     * returning the dump as text (SQL for Postgres/MySQL). The whole dump is held in memory;
     * a streaming-to-file variant is a follow-up for large databases.
     *
     * @throws IOException                   if the dump command exits non-zero
     * @throws UnsupportedOperationException if the engine has no text dump (Redis/Mongo)
     */
    public String backup(String name, Engine engine, String user, String password,
                         String database) throws IOException {
        String containerName = "hohenheim-db-" + name;
        DockerClient.ExecResult result = docker.exec(containerName,
            engine.dumpCommand(user, database), engine.dumpEnv(password));
        if (result.exitCode() != 0) {
            throw new IOException("Database backup of '" + name + "' failed (exit "
                + result.exitCode() + "): " + result.stderr().trim());
        }
        return result.stdout();
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
    public void backupToFile(String name, Engine engine, String user, String password,
                             String database, Path target) throws IOException {
        String containerName = "hohenheim-db-" + name;
        switch (engine) {
            case POSTGRES, MYSQL ->
                Files.writeString(target, backup(name, engine, user, password, database), StandardCharsets.UTF_8);
            case REDIS -> {
                String rdbPath = "/tmp/hohenheim-dump.rdb";
                DockerClient.ExecResult save = docker.exec(containerName,
                    List.of("redis-cli", "--rdb", rdbPath), engine.readyEnv(password));
                if (save.exitCode() != 0) {
                    throw new IOException("redis dump failed for '" + name + "': " + save.stderr().trim());
                }
                Files.write(target, docker.getArchiveFile(containerName, rdbPath));
            }
            case MONGO -> {
                String archivePath = "/tmp/hohenheim-dump.archive";
                DockerClient.ExecResult dump = docker.exec(containerName, List.of("mongodump",
                    "--username", user, "--password", password, "--authenticationDatabase", "admin",
                    "--db", database, "--archive=" + archivePath));
                if (dump.exitCode() != 0) {
                    throw new IOException("mongodump failed for '" + name + "': " + dump.stderr().trim());
                }
                Files.write(target, docker.getArchiveFile(containerName, archivePath));
            }
        }
    }

    /**
     * Restore a text dump into a provisioned database: upload it into the container and load it
     * with the engine's client. The dump must match the engine (e.g. {@code pg_dump} output for
     * Postgres). Restoring into a non-empty database may conflict; restore into a fresh one.
     *
     * @throws IOException if the upload or load command fails
     */
    public void restore(String name, Engine engine, String user, String password,
                        String database, String dump) throws IOException {
        Path tempFile = Files.createTempFile("hohenheim-restore", "." + engine.dumpExtension());
        try {
            Files.writeString(tempFile, dump, StandardCharsets.UTF_8);
            restoreFromFile(name, engine, user, password, database, tempFile);
        } finally {
            Files.deleteIfExists(tempFile);
        }
    }

    /**
     * Restore a dump file into a provisioned database: push it into the container and load it with
     * the engine's client (binary-safe, so it handles SQL text and the Mongo archive alike).
     * Redis is restored by swapping its RDB and restarting the container.
     *
     * @throws UnsupportedOperationException for an ephemeral Redis (its tmpfs data dir is wiped
     *                                       by the restart the restore requires)
     */
    public void restoreFromFile(String name, Engine engine, String user, String password,
                                String database, Path source) throws IOException {
        if (engine == Engine.REDIS) {
            restoreRedis(name, user, password, database, source);
            return;
        }
        String containerName = "hohenheim-db-" + name;
        String fileName = "hohenheim-restore." + engine.dumpExtension();
        // Resolve the restore command first so an unsupported engine fails before any upload.
        List<String> command = engine.restoreCommand(user, password, database, "/tmp/" + fileName);

        Path tempDir = Files.createTempDirectory("hohenheim-restore");
        try {
            Files.copy(source, tempDir.resolve(fileName));
            docker.putArchiveFromDirectory(containerName, "/tmp", tempDir);

            DockerClient.ExecResult result = docker.exec(containerName, command, engine.dumpEnv(password));
            if (result.exitCode() != 0) {
                throw new IOException("Database restore of '" + name + "' failed (exit "
                    + result.exitCode() + "): " + result.stderr().trim());
            }
        } finally {
            Files.deleteIfExists(tempDir.resolve(fileName));
            Files.deleteIfExists(tempDir);
        }
    }

    /** ASCII magic at the start of every RDB file ("REDIS" + 4-digit version). */
    private static final String REDIS_RDB_MAGIC = "REDIS";

    // AIDEV-NOTE: Redis only reads its RDB at startup, so there is no live restore. Instead the
    // dump is copied into the data volume (via exec -- the archive API cannot write into mounts)
    // and the server is restarted around it: SHUTDOWN NOSAVE stops the container without saving
    // over the new file, and the restart loads it. A scheduled bgsave between the copy and the
    // shutdown could still overwrite it; that window is milliseconds and a retry recovers.
    private void restoreRedis(String name, String user, String password, String database,
                              Path source) throws IOException {
        byte[] header = new byte[REDIS_RDB_MAGIC.length()];
        try (var in = Files.newInputStream(source)) {
            if (in.read(header) != header.length
                || !REDIS_RDB_MAGIC.equals(new String(header, StandardCharsets.US_ASCII))) {
                throw new IOException("Not a redis RDB dump (missing REDIS magic): " + source);
            }
        }

        String containerName = "hohenheim-db-" + name;
        requirePersistentData(containerName, Engine.REDIS.dataPath);

        String fileName = "hohenheim-restore.rdb";
        Path tempDir = Files.createTempDirectory("hohenheim-restore");
        try {
            Files.copy(source, tempDir.resolve(fileName));
            docker.putArchiveFromDirectory(containerName, "/tmp", tempDir);
        } finally {
            Files.deleteIfExists(tempDir.resolve(fileName));
            Files.deleteIfExists(tempDir);
        }

        // "dump.rdb" in the data dir is the stock image's dbfilename/dir.
        DockerClient.ExecResult copy = docker.exec(containerName,
            List.of("cp", "/tmp/" + fileName, Engine.REDIS.dataPath + "/dump.rdb"));
        if (copy.exitCode() != 0) {
            throw new IOException("redis restore of '" + name + "' failed copying the RDB into place: "
                + copy.stderr().trim());
        }

        // The server exits without replying, so the exec result is unreliable; the stopped-state
        // poll below is the real confirmation.
        docker.exec(containerName, List.of("redis-cli", "SHUTDOWN", "NOSAVE"),
            Engine.REDIS.readyEnv(password));
        waitForStopped(containerName, 10_000);
        docker.startContainer(containerName);
        waitForReady(containerName, Engine.REDIS, user, password, database, 60_000);
    }

    /** Reject restore-by-restart when the data dir is a tmpfs mount (wiped on restart). */
    private void requirePersistentData(String containerName, String dataPath) throws IOException {
        Object mounts = docker.inspectContainer(containerName).get("Mounts");
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

    private void waitForStopped(String containerName, long timeoutMillis) throws IOException {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            Object state = docker.inspectContainer(containerName).get("State");
            if (state instanceof Map<?, ?> s && !Boolean.TRUE.equals(s.get("Running"))) {
                return;
            }
            try {
                Thread.sleep(200);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted waiting for '" + containerName + "' to stop");
            }
        }
        throw new IOException("Timed out waiting for '" + containerName + "' to stop for restore");
    }

    /** Size cap for an ephemeral (tmpfs) data mount: 1 GiB -- generous for tests and small
     *  preview databases, while bounding RAM use (tmpfs only consumes RAM for live data). */
    private static final long EPHEMERAL_DATA_SIZE_BYTES = 1024L * 1024 * 1024;

    private static Map<String, Object> buildSpec(Engine engine, String imageRef, String volumeName,
                                                 Map<String, String> env, @Nullable List<String> command,
                                                 boolean ephemeral) {
        String portKey = engine.port + "/tcp";
        List<String> envList = new ArrayList<>();
        env.forEach((key, value) -> envList.add(key + "=" + value));

        // Ephemeral data lives in a RAM-backed tmpfs mount: no host disk I/O at all (no btrfs
        // fsync storms from initdb), freed when the container is removed. Persistent data lives
        // in a named volume that survives re-provisioning.
        Map<String, Object> dataMount = ephemeral
            ? Map.of("Type", "tmpfs", "Target", engine.dataPath,
                     "TmpfsOptions", Map.of("SizeBytes", EPHEMERAL_DATA_SIZE_BYTES))
            : Map.of("Type", "volume", "Source", volumeName, "Target", engine.dataPath);

        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("Image", imageRef);
        if (!envList.isEmpty()) {
            spec.put("Env", envList);
        }
        if (command != null) {
            spec.put("Cmd", command);
        }
        spec.put("ExposedPorts", Map.of(portKey, Map.of()));
        spec.put("HostConfig", Map.of(
            "PortBindings", Map.of(portKey, List.of(Map.of("HostIp", "127.0.0.1", "HostPort", ""))),
            "Mounts", List.of(dataMount)
        ));
        return spec;
    }

    // AIDEV-NOTE: A docker-published port accepts connections via docker-proxy the instant the
    // container starts, well before the DB can serve queries -- so we probe the engine itself
    // (over TCP, inside the container) until it reports ready.
    private void waitForReady(String containerId, Engine engine, String user, String password,
                             String database, long timeoutMillis) throws IOException {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        List<String> command = engine.readyCommand(user, password, database);
        List<String> env = engine.readyEnv(password);
        IOException last = null;
        while (System.currentTimeMillis() < deadline) {
            try {
                if (docker.exec(containerId, command, env).exitCode() == 0) {
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
            + (last != null ? " (" + last.getMessage() + ")" : ""));
    }
}
