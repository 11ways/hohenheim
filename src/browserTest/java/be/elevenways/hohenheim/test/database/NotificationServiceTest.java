package be.elevenways.hohenheim.test.database;

import be.elevenways.hohenheim.migration.M019_CreateNotificationChannels;
import be.elevenways.hohenheim.server.notification.NotificationService;
import be.elevenways.hohenheim.test.HohenheimTestRuntime;
import be.elevenways.zenit.common.orm.migration.MigrationCapableDatasource;
import be.elevenways.zenit.server.orm.migration.MigrationRunner;
import be.elevenways.zenit.server.orm.SqliteDatasource;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test for {@link NotificationService}: spins up an in-process HTTP receiver and
 * asserts the service POSTs the right per-format body shape and reports delivery.
 */
class NotificationServiceTest {

    @Test
    void sendsSlackShapedBodyAndReportsDelivery() throws Exception {
        Receiver receiver = Receiver.start();
        try {
            NotificationService service = freshService();
            service.add("ops", NotificationService.FORMAT_SLACK, receiver.url("/slack"));

            int delivered = service.send("Outage", "DB ed-1 is down");

            assertThat(delivered).isEqualTo(1);
            assertThat(receiver.lastPath.get()).isEqualTo("/slack");
            assertThat(receiver.lastBody.get())
                .contains("\"text\":\"Outage\\n\\nDB ed-1 is down\"");
        } finally {
            receiver.stop();
        }
    }

    @Test
    void discordAndGenericFormatsUseDifferentEnvelopes() throws Exception {
        Receiver receiver = Receiver.start();
        try {
            NotificationService service = freshService();
            service.add("disc", NotificationService.FORMAT_DISCORD, receiver.url("/d"));
            assertThat(service.sendTo("disc", "Hi", "world")).isTrue();
            assertThat(receiver.lastBody.get()).contains("\"content\":\"Hi\\n\\nworld\"");

            service.add("gen", NotificationService.FORMAT_GENERIC, receiver.url("/g"));
            assertThat(service.sendTo("gen", "Hi", "world")).isTrue();
            assertThat(receiver.lastBody.get())
                .contains("\"subject\":\"Hi\"")
                .contains("\"message\":\"world\"");
        } finally {
            receiver.stop();
        }
    }

    private static NotificationService freshService() throws IOException {
        File db = File.createTempFile("hohenheim-notify-test", ".db");
        db.delete();
        db.deleteOnExit();
        SqliteDatasource ds = new SqliteDatasource("jdbc:sqlite:" + db.getAbsolutePath());
        new MigrationRunner((MigrationCapableDatasource) ds,
            List.of(M019_CreateNotificationChannels::new)).migrate();
        HohenheimTestRuntime.ensureBooted();
        return new NotificationService(ds);
    }

    /** Tiny loopback HTTP server that records the last POST. */
    private static final class Receiver {
        final HttpServer server;
        final int port;
        final AtomicReference<String> lastPath = new AtomicReference<>();
        final AtomicReference<String> lastBody = new AtomicReference<>();

        private Receiver(HttpServer server, int port) {
            this.server = server;
            this.port = port;
        }

        static Receiver start() throws IOException {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            Receiver receiver = new Receiver(server, server.getAddress().getPort());
            server.createContext("/", exchange -> {
                receiver.lastPath.set(exchange.getRequestURI().getPath());
                ByteArrayOutputStream buf = new ByteArrayOutputStream();
                exchange.getRequestBody().transferTo(buf);
                receiver.lastBody.set(buf.toString());
                exchange.sendResponseHeaders(200, -1);
                exchange.close();
            });
            server.start();
            return receiver;
        }

        String url(String path) {
            return "http://127.0.0.1:" + port + path;
        }

        void stop() {
            server.stop(0);
        }
    }
}
