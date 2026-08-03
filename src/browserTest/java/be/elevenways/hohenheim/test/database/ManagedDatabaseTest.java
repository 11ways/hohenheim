package be.elevenways.hohenheim.test.database;

import be.elevenways.hohenheim.model.DatabaseModel;
import be.elevenways.hohenheim.server.database.ManagedDatabase;
import be.elevenways.hohenheim.server.docker.DockerClient;
import be.elevenways.hohenheim.server.docker.OwnerLabels;
import be.elevenways.hohenheim.server.docker.ResourceLimits;
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
    private static final String MYSQL_IMAGE = "mysql:8.0";

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
            // recordId 4242 -> the container must be born carrying its owner claim.
            ManagedDatabase.Connection conn = databases.provision(
                name, ManagedDatabase.Engine.POSTGRES, PG_IMAGE,
                "appuser", "secret123", "appdb", true, ResourceLimits.none(), 4242);

            assertThat(conn.host()).isEqualTo("127.0.0.1");
            assertThat(conn.port()).isGreaterThan(0);
            assertThat(conn.database()).isEqualTo("appdb");

            // provision() waits for readiness; the container is running and the port is open.
            Map<String, Object> info = docker.inspectContainer(containerName);
            assertThat(((Map<String, Object>) info.get("State")).get("Running")).isEqualTo(Boolean.TRUE);

            // The record's owner labels landed at container-create time.
            Map<String, Object> config = (Map<String, Object>) info.get("Config");
            OwnerLabels.Owner owner = OwnerLabels.parse((Map<?, ?>) config.get("Labels"));
            assertThat(owner).as("db container carries owner labels").isNotNull();
            assertThat(owner.model()).isEqualTo(DatabaseModel.MODEL_ID);
            assertThat(owner.id()).isEqualTo("4242");
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

    /**
     * The replace path must never destroy what it cannot attribute: a same-named
     * container WITHOUT this record's owner labels is a loud, named refusal, and the
     * foreign container survives untouched on the host.
     */
    @Test
    void provisionRefusesToReplaceAForeignSameNamedContainer() throws IOException {
        assumeTrue(Files.exists(SOCKET), "Docker socket not present");
        DockerClient docker = new DockerClient();
        assumeTrue(imagePresent(docker, PG_IMAGE), PG_IMAGE + " not present locally");
        assumeTrue(imagePresent(docker, "alpine:latest"), "alpine:latest not present locally");

        ManagedDatabase databases = new ManagedDatabase(docker);
        String name = "foreign" + System.nanoTime();
        String containerName = "hohenheim-db-" + name;
        // 1. Plant an UNLABELLED container squatting on the name (what the legacy path
        //    would have force-removed without a second thought).
        docker.createContainer(containerName, Map.of(
            "Image", "alpine:latest", "Cmd", List.of("sleep", "300")));
        try {
            // 2. Provisioning over it is a named refusal, not a silent replace.
            try {
                databases.provision(name, ManagedDatabase.Engine.POSTGRES, PG_IMAGE,
                    "appuser", "secret123", "appdb", true, ResourceLimits.none(), 777);
                throw new AssertionError("step 2: provision over a foreign container must refuse");
            } catch (IOException expected) {
                assertThat(expected.getMessage())
                    .as("step 2: the refusal is loud and names the attribution failure")
                    .contains("not attributably ours");
            }
            // 3. HOST state: the foreign container survives, unlabelled and unharmed.
            Map<String, Object> survivor = docker.inspectContainer(containerName);
            assertThat(survivor).as("step 3: the foreign container still exists").isNotNull();
        } finally {
            docker.removeContainer(containerName, true);
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

    @Test
    void backupRestoreRoundTripsPostgres() throws IOException {
        assumeTrue(Files.exists(SOCKET), "Docker socket not present");
        DockerClient docker = new DockerClient();
        assumeTrue(imagePresent(docker, PG_IMAGE), PG_IMAGE + " not present locally");

        ManagedDatabase databases = new ManagedDatabase(docker);
        String name = "rttest" + System.nanoTime();
        String containerName = "hohenheim-db-" + name;
        List<String> env = List.of("PGPASSWORD=secret123");
        try {
            databases.provision(name, ManagedDatabase.Engine.POSTGRES, PG_IMAGE,
                "appuser", "secret123", "appdb", true);   // ephemeral: tmpfs, no btrfs I/O

            psql(docker, containerName, env, "CREATE TABLE notes (id int); INSERT INTO notes VALUES (7);");
            String dump = databases.backup(name, ManagedDatabase.Engine.POSTGRES,
                "appuser", "secret123", "appdb");

            psql(docker, containerName, env, "DROP TABLE notes;");   // wipe what the dump holds

            databases.restore(name, ManagedDatabase.Engine.POSTGRES,
                "appuser", "secret123", "appdb", dump);

            DockerClient.ExecResult check = docker.exec(containerName,
                List.of("psql", "-U", "appuser", "-d", "appdb", "-tAc", "SELECT count(*) FROM notes WHERE id=7"),
                env);
            assertThat(check.exitCode()).withFailMessage("query failed: %s", check.stderr()).isZero();
            assertThat(check.stdout().trim()).isEqualTo("1");   // row survived the round-trip
        } finally {
            databases.destroy(name, true);
        }
    }

    private static void psql(DockerClient docker, String containerName, List<String> env, String sql)
            throws IOException {
        DockerClient.ExecResult result = docker.exec(containerName,
            List.of("psql", "-v", "ON_ERROR_STOP=1", "-U", "appuser", "-d", "appdb", "-c", sql), env);
        assertThat(result.exitCode()).withFailMessage("psql failed: %s", result.stderr()).isZero();
    }

    @Test
    void backupRestoreRoundTripsMysql() throws IOException {
        assumeTrue(Files.exists(SOCKET), "Docker socket not present");
        DockerClient docker = new DockerClient();
        assumeTrue(imagePresent(docker, MYSQL_IMAGE), MYSQL_IMAGE + " not present locally");

        ManagedDatabase databases = new ManagedDatabase(docker);
        String name = "mytest" + System.nanoTime();
        String containerName = "hohenheim-db-" + name;
        List<String> env = List.of("MYSQL_PWD=secret123");
        try {
            databases.provision(name, ManagedDatabase.Engine.MYSQL, MYSQL_IMAGE,
                "appuser", "secret123", "appdb", true);   // ephemeral: tmpfs, no btrfs I/O

            mysql(docker, containerName, env, "CREATE TABLE notes (id int); INSERT INTO notes VALUES (7);");
            String dump = databases.backup(name, ManagedDatabase.Engine.MYSQL,
                "appuser", "secret123", "appdb");

            mysql(docker, containerName, env, "DROP TABLE notes;");

            databases.restore(name, ManagedDatabase.Engine.MYSQL,
                "appuser", "secret123", "appdb", dump);

            DockerClient.ExecResult check = docker.exec(containerName,
                List.of("mysql", "-u", "appuser", "appdb", "-N", "-B", "-e", "SELECT count(*) FROM notes WHERE id=7"),
                env);
            assertThat(check.exitCode()).withFailMessage("query failed: %s", check.stderr()).isZero();
            assertThat(check.stdout().trim()).isEqualTo("1");   // row survived the round-trip
        } finally {
            databases.destroy(name, true);
        }
    }

    private static void mysql(DockerClient docker, String containerName, List<String> env, String sql)
            throws IOException {
        DockerClient.ExecResult result = docker.exec(containerName,
            List.of("mysql", "-u", "appuser", "appdb", "-e", sql), env);
        assertThat(result.exitCode()).withFailMessage("mysql failed: %s", result.stderr()).isZero();
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
