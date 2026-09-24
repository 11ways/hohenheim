package be.elevenways.hohenheim.server.proxy;

import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.hohenheim.test.Poll;
import be.elevenways.hohenheim.test.ProxyTestSupport;
import be.elevenways.zenit.common.orm.datasource.Row;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The access log escapes every control character a field could carry, logs the path the client
 * sent (not the stripped one), and keeps one writer that follows a rotated file instead of
 * opening a file per line.
 */
class AccessLogTest {

    private static ProxyServer proxy;
    private static HttpServer upstream;

    @BeforeAll
    static void boot() throws Exception {
        ProxyTestSupport.bootRuntime();
        upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        upstream.createContext("/", ex -> {
            byte[] body = "logged".getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        upstream.start();
    }

    @AfterAll
    static void stop() {
        HohenheimSettings.VALUES.setValue(HohenheimSettings.Logging.ACCESS_TO_FILE, false);
        if (proxy != null) {
            proxy.stop();
            proxy = null;
        }
        if (upstream != null) {
            upstream.stop(0);
            upstream = null;
        }
    }

    @Test
    void everyControlCharacterIsEscaped() {
        // Step 1: a newline, a quote and a backslash can neither end a field nor start a line.
        assertThat(AccessLog.escape("a\nb\"c\\d\re\u0000f\u007f"))
            .as("step 1: control characters become \\xNN, quote and backslash are escaped")
            .isEqualTo("a\\x0ab\\\"c\\\\d\\x0de\\x00f\\x7f");

        // Step 2: a clean value is returned as-is.
        assertThat(AccessLog.escape("/plain/path")).as("step 2: nothing to escape").isEqualTo("/plain/path");
    }

    @Test
    @Timeout(60)
    void theLogFollowsARotatedFileAndRecordsTheClientPath() throws Exception {
        Path directory = Files.createTempDirectory("hh-access-log");
        Path logFile = directory.resolve("access.log");
        HohenheimSettings.VALUES.setValue(HohenheimSettings.Logging.ACCESS_PATH, logFile.toString());
        HohenheimSettings.VALUES.setValue(HohenheimSettings.Logging.ACCESS_TO_FILE, true);

        Row site = ProxyTestSupport.setupSite("hohenheim:address", "Access Log Site", "access-log-site",
            Map.of("forward_host", "127.0.0.1", "forward_port", upstream.getAddress().getPort()));
        ProxyTestSupport.addDomain(site, "log.access.test", "exact", "/app", true);
        proxy = ProxyTestSupport.startProxy();
        int port = ProxyTestSupport.httpPort(proxy);

        // Step 1: two requests land as two lines, each naming the path the CLIENT sent.
        ProxyTestSupport.rawRequest(port, "log.access.test", "/app/one");
        ProxyTestSupport.rawRequest(port, "log.access.test", "/app/two%20x");
        List<String> first = awaitLines(logFile, 2);
        assertThat(first).as("step 1: the unstripped client path is logged")
            .anyMatch(line -> line.contains("\"GET /app/one "));
        assertThat(first).as("step 1: the path is logged in its raw encoding")
            .anyMatch(line -> line.contains("\"GET /app/two%20x "));

        // Step 2: logrotate moves the file away; the next line starts a fresh file at the path
        // instead of vanishing into the moved one.
        Path rotated = directory.resolve("access.log.1");
        Files.move(logFile, rotated);
        ProxyTestSupport.rawRequest(port, "log.access.test", "/app/three");
        List<String> fresh = awaitLines(logFile, 1);
        assertThat(fresh).as("step 2: the rotated path holds only the new line").hasSize(1);
        assertThat(fresh.get(0)).contains("/app/three");
        assertThat(Files.readAllLines(rotated))
            .as("step 2: the rotated file kept exactly its two lines").hasSize(2);
    }

    /** The log's lines once it holds at least {@code count}; the line lands after the response. */
    private static List<String> awaitLines(Path file, int count) {
        return Poll.value("the access log holds " + count + " line(s)", Duration.ofSeconds(5), () -> {
            if (!Files.exists(file)) {
                return null;
            }
            try {
                List<String> lines = Files.readAllLines(file);
                return lines.size() >= count ? lines : null;
            } catch (IOException unreadable) {
                throw new UncheckedIOException(unreadable);
            }
        });
    }
}
