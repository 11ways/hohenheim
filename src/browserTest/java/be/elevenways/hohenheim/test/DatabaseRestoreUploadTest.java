package be.elevenways.hohenheim.test;

import be.elevenways.hohenheim.model.DatabaseModel;
import be.elevenways.hohenheim.server.database.DatabaseInstances;
import be.elevenways.hohenheim.server.database.DatabaseService;
import be.elevenways.hohenheim.server.database.ManagedDatabase;
import be.elevenways.hohenheim.server.docker.DockerClient;
import be.elevenways.hohenheim.test.live.LiveLane;
import be.elevenways.hohenheim.test.network.PrivateNetns;
import be.elevenways.zenit.auth.server.AuthCookieSupport;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import org.junit.jupiter.api.*;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end test for the admin-UI restore upload: a multipart POST to
 * /databases/:name/restore loads the dump into the live container.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class DatabaseRestoreUploadTest extends HohenheimTestBase {

    private static final Path SOCKET = Path.of(DockerClient.DEFAULT_SOCKET);
    private static final String POSTGRES_IMAGE = "postgres:17-alpine";
    private static final String BOUNDARY = "HohenheimRestoreTestBoundary";

    private String baseUrl() {
        return "http://localhost:" + getServerPort();
    }

    @Test
    @Order(1)
    void restoreWithoutFileRedirectsWithError() throws Exception {
        HttpResponse<String> response = postForm("/databases/whatever/restore", "unused=1");
        assertThat(response.statusCode()).isEqualTo(302);
        // Unknown database: the redirect falls back to the admin list with an error.
        assertThat(response.headers().firstValue("Location").orElse(""))
            .startsWith("/admin/databases");
    }

    @Test
    @Order(2)
    void uploadedDumpRestoresIntoTheLiveDatabase() throws Exception {
        LiveLane.require(LiveLane.Need.DOCKER_SOCKET, Files.exists(SOCKET),
            "Docker socket not present");
        DockerClient docker = new DockerClient();
        LiveLane.requireImage(docker, POSTGRES_IMAGE);

        String name = "restoreui" + System.nanoTime();
        DatabaseService databases = new DatabaseService();
        DatabaseModel model = Models.get(DatabaseModel.class);
        PrivateNetns netns = PrivateNetns.installEnforcing();
        LiveLane.require(LiveLane.Need.NETNS, netns != null,
            "no private netns: a record-backed database refuses without an enforceable policy");
        try {
            // The record AND its owned engine instance in one call; the restore handler
            // resolves the container from the record's owned instance.
            databases.create(name, ManagedDatabase.Engine.POSTGRES, POSTGRES_IMAGE,
                "appuser", "secret123", "appdb", true);
            Row row = model.findByName(name);
            String container = DatabaseInstances.handleOf(row.get(DatabaseModel.ID));
            assertThat(container).as("the database owns an engine instance").isNotNull();

            String dump = "CREATE TABLE things (x integer);\nINSERT INTO things VALUES (7);\n";
            HttpResponse<String> response = postMultipartFile(
                "/databases/" + name + "/restore", "dump", name + ".sql", dump);
            assertThat(response.statusCode()).isEqualTo(302);
            assertThat(response.headers().firstValue("Location").orElse(""))
                .contains("restored=1");

            DockerClient.ExecResult result = docker.exec(container,
                List.of("psql", "-U", "appuser", "-d", "appdb", "-tAc", "SELECT x FROM things"),
                List.of("PGPASSWORD=secret123"));
            assertThat(result.exitCode()).withFailMessage("psql failed: %s", result.stderr()).isZero();
            assertThat(result.stdout().trim()).isEqualTo("7");   // dump landed in the live database
        } finally {
            try {
                databases.destroy(name, true);
            } catch (IOException ignored) {
                // best effort
            }
            model.find().where(DatabaseModel.NAME.eq(name)).delete();
            PrivateNetns.uninstall(netns);
        }
    }

    private HttpResponse<String> postForm(String path, String body) throws Exception {
        HttpRequest request = requestBuilder(path)
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build();
        return send(request);
    }

    private HttpResponse<String> postMultipartFile(String path, String field, String filename,
                                                   String content) throws Exception {
        String body = "--" + BOUNDARY + "\r\n"
            + "Content-Disposition: form-data; name=\"" + field + "\"; filename=\"" + filename + "\"\r\n"
            + "Content-Type: application/octet-stream\r\n\r\n"
            + content + "\r\n"
            + "--" + BOUNDARY + "--\r\n";
        HttpRequest request = requestBuilder(path)
            .header("Content-Type", "multipart/form-data; boundary=" + BOUNDARY)
            .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
            .build();
        return send(request);
    }

    private HttpRequest.Builder requestBuilder(String path) {
        return HttpRequest.newBuilder()
            .uri(URI.create(baseUrl() + path))
            .header("Cookie", AuthCookieSupport.sessionCookieName() + "=" + sessionToken)
            .header("X-Csrf-Token", csrfToken);
    }

    private static HttpResponse<String> send(HttpRequest request) throws Exception {
        HttpClient client = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
