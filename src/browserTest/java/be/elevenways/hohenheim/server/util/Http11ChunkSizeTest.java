package be.elevenways.hohenheim.server.util;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * An HTTP/1.1 chunk size is bare ASCII hex (RFC 9112 section 7.1): a sign is malformed framing in both the
 * buffered and the streaming decoder, never a size, and a size past what the decoder can hold is refused.
 *
 * @author Jelle De Loecker
 * @since 0.1.0
 */
class Http11ChunkSizeTest {

    private static final String HEAD = "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n";

    private static byte[] response(String chunkedBody) {
        return (HEAD + chunkedBody).getBytes(StandardCharsets.ISO_8859_1);
    }

    private static String parsed(String chunkedBody) throws IOException {
        return new String(Http11.parse(response(chunkedBody), "test").body(), StandardCharsets.ISO_8859_1);
    }

    private static String streamed(String chunkedBody) throws IOException {
        InputStream in = new ByteArrayInputStream(response(chunkedBody));
        Http11.Head head = Http11.readHead(in, "test");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Http11.copyBody(in, head, out, Long.MAX_VALUE, "test");
        return out.toString(StandardCharsets.ISO_8859_1);
    }

    @Test
    void aChunkSizeIsBareAsciiHexInBothDecoders() throws IOException {
        // 1. Hex sizes in either case, with a chunk extension, decode in both lanes.
        String wellFormed = "3;name=value\r\nabc\r\nA\r\n0123456789\r\n0\r\n\r\n";
        assertThat(parsed(wellFormed)).as("step 1: the buffered decoder").isEqualTo("abc0123456789");
        assertThat(streamed(wellFormed)).as("step 1: the streaming decoder").isEqualTo("abc0123456789");

        // 2. A leading plus is no hex digit: Integer.parseInt and Long.parseLong used to read "+3" as three.
        String plus = "+3\r\nabc\r\n0\r\n\r\n";
        assertThat(catchThrowable(() -> parsed(plus))).as("step 2: buffered refuses '+3'")
            .isInstanceOf(IOException.class).hasMessageContaining("bad chunk size");
        assertThat(catchThrowable(() -> streamed(plus))).as("step 2: streaming refuses '+3'")
            .isInstanceOf(IOException.class).hasMessageContaining("bad chunk size");

        // 3. "-0" used to read as the terminating chunk and end the body early; a negative size is framing garbage.
        String minusZero = "-0\r\n\r\n";
        assertThat(catchThrowable(() -> parsed(minusZero))).as("step 3: buffered refuses '-0'")
            .isInstanceOf(IOException.class).hasMessageContaining("bad chunk size");
        assertThat(catchThrowable(() -> streamed(minusZero))).as("step 3: streaming refuses '-0'")
            .isInstanceOf(IOException.class).hasMessageContaining("bad chunk size");
        assertThat(catchThrowable(() -> parsed("-1\r\nabc\r\n0\r\n\r\n"))).as("step 3: buffered refuses '-1'")
            .isInstanceOf(IOException.class).hasMessageContaining("bad chunk size");

        // 4. An empty size and a size past what the decoder can hold are refused, never wrapped around.
        assertThat(catchThrowable(() -> parsed("\r\nabc\r\n0\r\n\r\n"))).as("step 4: buffered refuses no digits")
            .isInstanceOf(IOException.class).hasMessageContaining("bad chunk size");
        assertThat(catchThrowable(() -> parsed("80000000\r\nabc\r\n0\r\n\r\n")))
            .as("step 4: buffered refuses a size past Integer.MAX_VALUE")
            .isInstanceOf(IOException.class).hasMessageContaining("bad chunk size");
        assertThat(catchThrowable(() -> streamed("10000000000000000\r\nabc\r\n0\r\n\r\n")))
            .as("step 4: streaming refuses a size past Long.MAX_VALUE")
            .isInstanceOf(IOException.class).hasMessageContaining("bad chunk size");
    }
}
