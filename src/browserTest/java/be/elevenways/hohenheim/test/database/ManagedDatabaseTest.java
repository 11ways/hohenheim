package be.elevenways.hohenheim.test.database;

import be.elevenways.hohenheim.server.database.ManagedDatabase;
import be.elevenways.hohenheim.server.docker.DockerClient;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Integration test for {@link ManagedDatabase} against a real Docker daemon.
 * Skipped when the daemon socket or the postgres image is absent.
 */
class ManagedDatabaseTest {

    private static final Path SOCKET = Path.of(DockerClient.DEFAULT_SOCKET);
    private static final String PG_IMAGE = "postgres:17-alpine";

    @Test
    @SuppressWarnings("unchecked")
    void provisionsPostgresAndAcceptsConnections() throws IOException {
        assumeTrue(Files.exists(SOCKET), "Docker socket not present");
        DockerClient docker = new DockerClient();
        assumeTrue(imagePresent(docker, PG_IMAGE), PG_IMAGE + " not present locally");

        ManagedDatabase databases = new ManagedDatabase(docker);
        String name = "test" + System.nanoTime();
        String containerName = "hohenheim-db-" + name;
        try {
            // ephemeral=true -> data dir is a RAM tmpfs mount, so Postgres initdb never
            // fsync-storms the btrfs root (which previously stalled it for minutes).
            ManagedDatabase.Connection conn = databases.provision(
                name, ManagedDatabase.Engine.POSTGRES, PG_IMAGE,
                "appuser", "secret123", "appdb", true);

            assertThat(conn.host()).isEqualTo("127.0.0.1");
            assertThat(conn.port()).isGreaterThan(0);
            assertThat(conn.database()).isEqualTo("appdb");

            // provision() waits for readiness; the container is running and the port is open.
            Map<String, Object> info = docker.inspectContainer(containerName);
            assertThat(((Map<String, Object>) info.get("State")).get("Running")).isEqualTo(Boolean.TRUE);
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress(conn.host(), conn.port()), 2000);
            }
        } finally {
            databases.destroy(name, true);
        }

        // After destroy the container is gone.
        try {
            docker.inspectContainer(containerName);
            throw new AssertionError("expected inspect of removed db container to fail");
        } catch (IOException expected) {
            // 404 -> IOException, as intended
        }
    }

    @Test
    void backsUpPostgresAsSql() throws IOException {
        assumeTrue(Files.exists(SOCKET), "Docker socket not present");
        DockerClient docker = new DockerClient();
        assumeTrue(imagePresent(docker, PG_IMAGE), PG_IMAGE + " not present locally");

        ManagedDatabase databases = new ManagedDatabase(docker);
        String name = "buptest" + System.nanoTime();
        String containerName = "hohenheim-db-" + name;
        try {
            databases.provision(name, ManagedDatabase.Engine.POSTGRES, PG_IMAGE,
                "appuser", "secret123", "appdb", true);   // ephemeral: tmpfs, no btrfs I/O

            // Seed a table so the dump has identifiable content (also exercises exec-with-env).
            DockerClient.ExecResult create = docker.exec(containerName,
                List.of("psql", "-U", "appuser", "-d", "appdb", "-c", "CREATE TABLE widgets (id int);"),
                List.of("PGPASSWORD=secret123"));
            assertThat(create.exitCode()).withFailMessage("seed failed: %s", create.stderr()).isZero();

            String dump = databases.backup(name, ManagedDatabase.Engine.POSTGRES,
                "appuser", "secret123", "appdb");
            assertThat(dump).contains("CREATE TABLE");
            assertThat(dump).contains("widgets");
        } finally {
            databases.destroy(name, true);
        }
    }

    private static boolean imagePresent(DockerClient docker, String tag) throws IOException {
        for (Object image : docker.listImages()) {
            Object repoTags = ((Map<?, ?>) image).get("RepoTags");
            if (repoTags instanceof List<?> tags && tags.contains(tag)) {
                return true;
            }
        }
        return false;
    }
}
