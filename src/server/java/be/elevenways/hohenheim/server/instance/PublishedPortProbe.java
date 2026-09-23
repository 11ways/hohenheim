package be.elevenways.hohenheim.server.instance;

import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.server.host.HostShell;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * THE one way the controller asks whether a workload's published port answers: from the
 * machine the port is published ON.
 *
 * AIDEV-NOTE: a publication binds the WORKLOAD host's loopback, so on a remote host the
 * controller's own 127.0.0.1 is a different machine. The readiness probe connected there
 * and the release probe had a host-aware lane only for HTTP; a remote workload's port
 * readiness therefore either timed out against an empty controller port or, worse, passed
 * against whatever the controller itself happened to listen on. Both probes now take the
 * lane {@link #forServer} picks: a direct socket for the local host, the host's own shell
 * (the pinned ssh lane) for every other one.
 *
 * @author Jelle De Loecker
 * @since  0.1.0
 */
public interface PublishedPortProbe {

    /** @return whether a TCP connect to the port on the workload host's loopback succeeds */
    boolean accepts(int port);

    /**
     * One HTTP GET of {@code path} on the port.
     *
     * @return the status code, or 0 when nothing answered
     * @throws IOException when the probe could not be sent at all
     */
    int httpStatus(int port, @NonNull String path) throws IOException, InterruptedException;

    /** The lane for the host a workload runs on; null and the local host probe directly. */
    static @NonNull PublishedPortProbe forServer(@Nullable Integer serverId) {
        if (serverId == null || serverId == ServerModel.localServerId()) {
            return local();
        }
        Row server = Models.get(ServerModel.class).findById(serverId);
        return server == null ? local() : over(HostShell.forServer(server));
    }

    /** The controller's own loopback. */
    static @NonNull PublishedPortProbe local() {
        return new Local();
    }

    /** The loopback of the machine {@code shell} runs on. */
    static @NonNull PublishedPortProbe over(@NonNull HostShell shell) {
        return new Shell(shell);
    }

    /** The target URL of an HTTP probe; a path without its leading slash gets one. */
    static @NonNull URI targetOf(int port, @NonNull String path) {
        return URI.create("http://127.0.0.1:" + port + (path.startsWith("/") ? path : "/" + path));
    }

    /** Direct socket and HTTP client on this machine. */
    final class Local implements PublishedPortProbe {

        private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2))
            .build();

        @Override
        public boolean accepts(int port) {
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress("127.0.0.1", port), 2000);
                return true;
            } catch (IOException refused) {
                return false;
            }
        }

        @Override
        public int httpStatus(int port, @NonNull String path)
                throws IOException, InterruptedException {
            HttpResponse<Void> response = this.client.send(
                HttpRequest.newBuilder(targetOf(port, path))
                    .timeout(Duration.ofSeconds(5)).GET().build(),
                HttpResponse.BodyHandlers.discarding());
            return response.statusCode();
        }
    }

    /**
     * The workload host's shell: bash's {@code /dev/tcp} for the connect and curl for the
     * HTTP round-trip, both aimed at THAT host's loopback.
     */
    final class Shell implements PublishedPortProbe {

        private final HostShell shell;

        Shell(@NonNull HostShell shell) {
            this.shell = shell;
        }

        @Override
        public boolean accepts(int port) {
            try {
                return this.shell.run("timeout 3 bash -c "
                    + HostShell.quote("exec 3<>/dev/tcp/127.0.0.1/" + port), 10).ok();
            } catch (RuntimeException unanswerable) {
                // An unaddressable host answers nothing, which is "not open", never a crash.
                return false;
            }
        }

        @Override
        public int httpStatus(int port, @NonNull String path) {
            HostShell.Result result;
            try {
                result = this.shell.run(
                    "curl --silent --output /dev/null --write-out '%{http_code}'"
                        + " --connect-timeout 2 --max-time 5 -- "
                        + HostShell.quote(targetOf(port, path).toString()), 10);
            } catch (RuntimeException unanswerable) {
                return 0;
            }
            String code = result.text().trim();
            return result.ok() && code.matches("[0-9]{3}") ? Integer.parseInt(code) : 0;
        }
    }
}
