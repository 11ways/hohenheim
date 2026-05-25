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
                case REDIS -> Map.of();   // first slice: no auth (follow-up: --requirepass)
            };
        }

        /** The tool + args that dump this engine to stdout as text (SQL). */
        List<String> dumpCommand(String user, String database) {
            return switch (this) {
                case POSTGRES -> List.of("pg_dump", "-U", user, "-d", database);
                case MYSQL -> List.of("mysqldump", "-u", user, database);
                case REDIS, MONGO -> throw new UnsupportedOperationException(
                    this + " backup needs binary streaming, not a text dump (follow-up)");
            };
        }

        /** Env for {@link #dumpCommand} so the password never appears in the captured stdout. */
        List<String> dumpEnv(String password) {
            return switch (this) {
                case POSTGRES -> List.of("PGPASSWORD=" + password);
                case MYSQL -> List.of("MYSQL_PWD=" + password);
                case REDIS, MONGO -> List.of();
            };
        }

        // AIDEV-NOTE: Probe over TCP (-h 127.0.0.1), not the unix socket: Postgres/MySQL run a
        // socket-only temporary server during init, so a socket probe reports ready too early.
        // Only the real server binds TCP. (Only POSTGRES is integration-tested today.)
        List<String> readyCommand(String user, String database) {
            return switch (this) {
                case POSTGRES -> List.of("pg_isready", "-h", "127.0.0.1", "-p", String.valueOf(port),
                    "-U", user, "-d", database);
                case MYSQL -> List.of("mysqladmin", "ping", "-h", "127.0.0.1", "-P", String.valueOf(port),
                    "-u", user);
                case REDIS -> List.of("redis-cli", "-p", String.valueOf(port), "ping");
                case MONGO -> List.of("mongosh", "--host", "127.0.0.1", "--port", String.valueOf(port),
                    "--quiet", "--eval", "db.runCommand({ ping: 1 }).ok");
            };
        }

        /** Env for {@link #readyCommand} (auth where the probe requires it). */
        List<String> readyEnv(String password) {
            return this == MYSQL ? List.of("MYSQL_PWD=" + password) : List.of();
        }
    }

    /** Connection details for a provisioned database. */
    public record Connection(Engine engine, String host, int port,
                             String user, String password, String database) {}

    private final DockerClient docker;

    public ManagedDatabase() {
        this(new DockerClient());
    }

    public ManagedDatabase(DockerClient docker) {
        this.docker = docker;
    }

    /**
     * Provision a persistent database (data survives re-provisioning in a named volume);
     * see {@link #provision(String, Engine, String, String, String, String, boolean)}.
     */
    public Connection provision(String name, Engine engine, String image,
                                String user, String password, String database) throws IOException {
        return provision(name, engine, image, user, password, database, false);
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
            buildSpec(engine, imageRef, volumeName, engine.env(user, password, database), ephemeral));
        docker.startContainer(id);

        waitForReady(id, engine, user, password, database, 60_000);
        int hostPort = docker.publishedPort(id, engine.port);

        return new Connection(engine, "127.0.0.1", hostPort, user, password, database);
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

    /** Back up a database to a UTF-8 file; see {@link #backup}. */
    public void backupToFile(String name, Engine engine, String user, String password,
                             String database, Path target) throws IOException {
        Files.writeString(target, backup(name, engine, user, password, database),
            StandardCharsets.UTF_8);
    }

    /** Size cap for an ephemeral (tmpfs) data mount: 1 GiB -- generous for tests and small
     *  preview databases, while bounding RAM use (tmpfs only consumes RAM for live data). */
    private static final long EPHEMERAL_DATA_SIZE_BYTES = 1024L * 1024 * 1024;

    private static Map<String, Object> buildSpec(Engine engine, String imageRef, String volumeName,
                                                 Map<String, String> env, boolean ephemeral) {
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
        List<String> command = engine.readyCommand(user, database);
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
