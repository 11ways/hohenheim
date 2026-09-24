package be.elevenways.hohenheim.test.database;

import be.elevenways.hohenheim.server.HandlerSupport;
import be.elevenways.hohenheim.test.HohenheimTestBase;
import be.elevenways.hohenheim.test.Poll;
import be.elevenways.protoblast.common.http.HttpMethod;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.common.routing.Endpoint;
import be.elevenways.zenit.common.routing.EndpointRoute;
import be.elevenways.zenit.server.http.ServeStreamResult;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * THE download lane the database backup rides ({@code HandlerSupport.downloadStream}): the body
 * is streamed from the open source, never buffered, with a sanitized attachment filename and a
 * size that may exceed 2 GiB, and the source is always closed.
 *
 * AIDEV-NOTE: the backup endpoint itself needs a running engine to dump (see the live
 * BinaryBackupTest); what it hands this helper is DatabaseService.backupStream's open stream
 * and exact size, which is exactly what this fixture endpoint hands it.
 *
 * @author Jelle De Loecker <jelle@elevenways.be>
 * @since 0.1.0
 */
class StreamedDownloadTest extends HohenheimTestBase {

    private static final String BODY = "-- dump line one\n-- dump line two\n";

    private static final AtomicBoolean CLOSED = new AtomicBoolean();

    @SuppressWarnings("unused")
    private static final Endpoint<Object> DOWNLOAD_FIXTURE = Endpoint.<Object>builder()
        .identifier(Identifier.of("hohenheimtest", "streamed_download_fixture"))
        .addRoute(EndpointRoute.builder().setMethod(HttpMethod.GET)
            .addStatic("streamed-download-fixture").build())
        .requiresLogin()
        .handler(conduit -> {
            byte[] bytes = BODY.getBytes(StandardCharsets.UTF_8);
            InputStream source = new ByteArrayInputStream(bytes) {
                @Override
                public void close() throws IOException {
                    CLOSED.set(true);
                    super.close();
                }
            };
            return HandlerSupport.downloadStream("application/sql", "db \"prod\"/../x.sql",
                source, bytes.length);
        })
        .build();

    @Test
    void aDumpIsStreamedAsASanitizedAttachmentAndItsSourceClosed() throws Exception {
        // 1. The helper hands zenit's streaming result the OPEN source, not a copy of it.
        InputStream open = new ByteArrayInputStream(new byte[0]);
        var result = HandlerSupport.downloadStream("application/octet-stream", "a.rdb", open, 0);
        assertThat(result).as("step 1: a streaming result").isInstanceOf(ServeStreamResult.class);
        assertThat(result.get()).as("step 1: carrying the source itself, never buffered").isSameAs(open);

        // 2. A size past 2 GiB is a size, not an overflow: the backup of a large database.
        assertThatCode(() -> HandlerSupport.downloadStream("application/octet-stream", "big.rdb",
                new ByteArrayInputStream(new byte[0]), 3L * 1024 * 1024 * 1024))
            .as("step 2: a > 2 GiB dump is accepted").doesNotThrowAnyException();

        // 3. Over HTTP: the bytes arrive whole, as an attachment whose filename cannot break
        //    out of the header, uncached, and the source is closed afterwards.
        CLOSED.set(false);
        HttpResponse<String> response = adminGet("/streamed-download-fixture");
        assertThat(response.statusCode()).as("step 3: served").isEqualTo(200);
        assertThat(response.body()).as("step 3: the dump arrives byte for byte").isEqualTo(BODY);
        assertThat(response.headers().firstValue("Content-Disposition").orElse(""))
            .as("step 3: a sanitized attachment filename")
            .isEqualTo("attachment; filename=\"db__prod__.._x.sql\"");
        assertThat(response.headers().firstValue("Cache-Control").orElse(""))
            .as("step 3: a dump is never cached").contains("no-store");
        // The close follows the last write, so it may land a moment after the client read it.
        Poll.until("step 3: the source was closed after serving", Duration.ofSeconds(5), CLOSED::get);
    }
}
