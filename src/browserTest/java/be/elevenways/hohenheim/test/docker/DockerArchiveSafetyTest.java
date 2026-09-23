package be.elevenways.hohenheim.test.docker;

import be.elevenways.hohenheim.server.docker.DockerClient;
import be.elevenways.hohenheim.server.docker.ProcessDockerTransport;
import be.elevenways.hohenheim.server.util.Http11;
import be.elevenways.hohenheim.server.util.Tar;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * The controller's side of the Docker archive and request lanes, hermetically: what a
 * container hands back is PARSED and only a regular file is ever read, what the controller
 * pushes carries names as DATA, request targets are encoded and a head byte that would
 * split a request is refused, and a response is complete or it is an error.
 *
 * AIDEV-NOTE: the first journey is the regression proof for a CRITICAL: the old lane ran
 * {@code tar -xf} on the daemon's archive and then read the "extracted file" with
 * {@code Files.readAllBytes}/{@code Files.move}, both of which FOLLOW a symlink entry -- so a
 * container that planted {@code dump.rdb -> /var/lib/hohenheim/hohenheim.db} got the
 * controller's own database copied into its backup. The counterfactual here is a real
 * controller-side secret file the planted link names; the assertion is on the bytes, not
 * on an exception type.
 */
class DockerArchiveSafetyTest {

    private static final String HANDLE = "hohenheim-test-instance-1";

    private static FakeContainerFiles fake() {
        return new FakeContainerFiles(HANDLE, Map.of())
            .directory("/data")
            .file("/data/dump.rdb", "REDIS0011 real dump bytes");
    }

    @Test
    void anArchiveEntryThatIsALinkIsRefusedAndOnlyARegularFileIsEverRead(@TempDir Path tmp)
            throws IOException {
        Path secret = tmp.resolve("hohenheim.db");
        Files.writeString(secret, "CONTROLLER-SECRET database bytes");
        Path out = tmp.resolve("backup.rdb");
        FakeContainerFiles daemon = fake();
        DockerClient docker = new DockerClient(daemon);

        // 1. The regular file arrives byte for byte, on both lanes.
        assertThat(new String(docker.getArchiveFile(HANDLE, "/data/dump.rdb", 1_000_000),
                StandardCharsets.UTF_8))
            .as("step 1: a regular file entry is read back exactly")
            .isEqualTo("REDIS0011 real dump bytes");
        assertThat(docker.getArchiveFileTo(HANDLE, "/data/dump.rdb", out, 1_000_000))
            .as("step 1: the streamed lane reports the file's size").isEqualTo(25);
        assertThat(Files.readString(out)).as("step 1: and writes exactly its bytes")
            .isEqualTo("REDIS0011 real dump bytes");
        Files.delete(out);

        // 2. THE ATTACK: the container answers the dump read with a SYMLINK entry naming a
        //    controller file. It is refused by name, and not one byte of the secret lands
        //    anywhere -- the old extract-then-read lane copied it into the backup.
        daemon.answerArchive("/data/dump.rdb",
            new TestTars().symlink("dump.rdb", secret.toString()).build());
        Throwable symlink = catchThrowable(() ->
            docker.getArchiveFileTo(HANDLE, "/data/dump.rdb", out, 1_000_000));
        assertThat(symlink).as("step 2: a symlink entry is a refusal")
            .isInstanceOf(IOException.class).hasMessageContaining("symlink");
        assertThat(Files.exists(out)).as("step 2: no output file is left behind").isFalse();

        daemon.answerArchive("/data/dump.rdb",
            new TestTars().symlink("dump.rdb", secret.toString()).build());
        Throwable inMemory = catchThrowable(() ->
            docker.getArchiveFile(HANDLE, "/data/dump.rdb", 1_000_000));
        assertThat(inMemory).as("step 2: the in-memory lane refuses the same entry")
            .isInstanceOf(IOException.class).hasMessageContaining("symlink");

        // 3. A HARD link to the same host path, a device, a directory and a second entry
        //    are each refused: exactly one REGULAR entry is the only acceptable envelope.
        List<byte[]> hostile = List.of(
            new TestTars().hardlink("dump.rdb", secret.toString()).build(),
            new TestTars().charDevice("dump.rdb").build(),
            new TestTars().directory("dump.rdb").build(),
            new TestTars().file("dump.rdb", "a".getBytes(StandardCharsets.UTF_8))
                .symlink("second", secret.toString()).build());
        List<String> refusals = new ArrayList<>();
        for (byte[] tar : hostile) {
            daemon.answerArchive("/data/dump.rdb", tar);
            Throwable refused = catchThrowable(() ->
                docker.getArchiveFileTo(HANDLE, "/data/dump.rdb", out, 1_000_000));
            refusals.add(refused == null ? "ACCEPTED" : refused.getMessage());
            assertThat(Files.exists(out))
                .as("step 3: no output file survives a refused envelope").isFalse();
        }
        assertThat(refusals).as("step 3: every hostile envelope is refused, none accepted")
            .hasSize(4).noneMatch("ACCEPTED"::equals);
        assertThat(refusals.get(0)).as("step 3: the hard link is named")
            .contains("hardlink");
        assertThat(refusals.get(3)).as("step 3: a second entry is named")
            .contains("more than one entry");

        // 4. The secret file itself was never read into anything we returned or wrote.
        assertThat(Files.readString(secret)).as("step 4: the controller file is untouched")
            .isEqualTo("CONTROLLER-SECRET database bytes");
    }

    @Test
    void pushedFileNamesAreArchiveDataNeverArgumentsAndNeverEscapeTheStagingRoot(
            @TempDir Path staging) throws IOException {
        FakeContainerFiles daemon = fake();
        DockerClient docker = new DockerClient(daemon);
        Files.writeString(staging.resolve("-rf"), "dash");
        Files.writeString(staging.resolve("--checkpoint-action=exec=touch pwned"), "option");

        // 1. Names that a tar ARGV would have parsed as options arrive as entry names, with
        //    their bytes, and nothing else is in the archive.
        docker.putArchiveFiles(HANDLE, "/data", staging,
            List.of("-rf", "--checkpoint-action=exec=touch pwned"));
        assertThat(daemon.lastPutEntries())
            .as("step 1: the entries are exactly the two names, as data")
            .containsExactly("-rf", "--checkpoint-action=exec=touch pwned");
        assertThat(daemon.text("/data/-rf")).as("step 1: the dash-named file landed")
            .isEqualTo("dash");
        assertThat(daemon.text("/data/--checkpoint-action=exec=touch pwned"))
            .as("step 1: the option-named file landed as a file").isEqualTo("option");

        // 2. A relative name that climbs out of the staging root, or an absolute one, is
        //    refused BEFORE anything is sent.
        int puts = daemon.calls().size();
        for (String escaping : List.of("../etc/passwd", "/etc/passwd", "a/../../b", "")) {
            assertThat(catchThrowable(() ->
                    docker.putArchiveFiles(HANDLE, "/data", staging, List.of(escaping))))
                .as("step 2: '" + escaping + "' is refused")
                .isInstanceOf(IOException.class);
        }
        assertThat(daemon.calls()).as("step 2: nothing was pushed for a refused name")
            .hasSize(puts);

        // 3. A symlink in the pushed tree travels as a LINK: the file it names on the
        //    controller is never read into the archive.
        Path secret = staging.resolveSibling(staging.getFileName() + "-secret");
        Files.writeString(secret, "CONTROLLER-SECRET");
        Path tree = Files.createDirectory(staging.resolve("tree"));
        Files.createSymbolicLink(tree.resolve("leak"), secret);
        ByteArrayOutputStream archive = new ByteArrayOutputStream();
        Tar.Writer writer = new Tar.Writer(archive);
        writer.add(tree, "tree");
        writer.finish();
        assertThat(archive.toString(StandardCharsets.ISO_8859_1))
            .as("step 3: the secret's bytes are not in the archive")
            .doesNotContain("CONTROLLER-SECRET");
        Tar.Reader reader = new Tar.Reader(new ByteArrayInputStream(archive.toByteArray()));
        List<String> entries = new ArrayList<>();
        Tar.Entry entry;
        while ((entry = reader.next()) != null) {
            entries.add(entry.name() + ":" + entry.kind() + ":" + entry.linkName());
        }
        assertThat(entries).as("step 3: the link is stored as a link")
            .containsExactly("tree/:DIRECTORY:", "tree/leak:SYMLINK:" + secret);
        Files.delete(secret);
    }

    @Test
    void requestTargetsAreEncodedAndAHeadByteThatWouldSplitARequestIsRefused() {
        FakeContainerFiles daemon = fake();
        DockerClient docker = new DockerClient(daemon);

        // 1. A container name carrying query/fragment/space/CRLF bytes is ONE encoded path
        //    segment: the request line the daemon receives is still a single request.
        catchThrowable(() -> docker.inspectContainer("a b?c#d\r\nHost: evil"));
        assertThat(daemon.targets()).as("step 1: the target is encoded, CRLF included")
            .last().isEqualTo("/containers/a%20b%3Fc%23d%0D%0AHost:%20evil/json");

        // 2. A '..' segment is refused outright: the router would clean it to another route.
        assertThat(catchThrowable(() -> docker.inspectContainer("..")))
            .as("step 2: a '..' container name never reaches the wire")
            .isInstanceOf(IllegalArgumentException.class);

        // 3. An image reference keeps its '/' and ':' but nothing that could escape them.
        catchThrowable(() -> docker.removeImage("registry:5000/team/app:v1 x", false));
        assertThat(daemon.targets()).as("step 3: the image path is encoded segment-wise")
            .last().isEqualTo("/images/registry:5000/team/app:v1%20x");
        assertThat(catchThrowable(() -> docker.removeImage("team/../../containers/x", false)))
            .as("step 3: an image reference with a '..' segment is refused")
            .isInstanceOf(IllegalArgumentException.class);

        // 4. The codec itself refuses a CR, LF or NUL anywhere in the head -- the last line
        //    of defence for every caller that builds a target or header by hand.
        assertThat(catchThrowable(() -> Http11.request("GET", "/a\r\nX: y", "docker", null,
                null, null)))
            .as("step 4: CRLF in the target").isInstanceOf(IllegalArgumentException.class);
        assertThat(catchThrowable(() -> Http11.request("GET", "/ok", "docker", null, null,
                Map.of("X-Registry-Auth", "abc\r\nHost: evil"))))
            .as("step 4: CRLF in a header value").isInstanceOf(IllegalArgumentException.class);
        assertThat(catchThrowable(() -> Http11.request("GET", "/ok", "dock\0er", null, null,
                null)))
            .as("step 4: NUL in the host").isInstanceOf(IllegalArgumentException.class);
        assertThat(catchThrowable(() -> Http11.request("GE T", "/ok", "docker", null, null,
                null)))
            .as("step 4: a space in the method").isInstanceOf(IllegalArgumentException.class);
        assertThat(new String(Http11.request("GET", "/ok?x=1", "docker", null, null,
                Map.of("X-Test", "v")), StandardCharsets.ISO_8859_1))
            .as("step 4: an ordinary request is unchanged")
            .startsWith("GET /ok?x=1 HTTP/1.1\r\nHost: docker\r\n");
    }

    @Test
    void aResponseIsCompleteOrItIsAnErrorNeverAShortSuccess() throws IOException {
        // 1. A body shorter than its Content-Length is a truncation, not a smaller body.
        byte[] truncated = "HTTP/1.1 200 OK\r\nContent-Length: 10\r\n\r\nabc"
            .getBytes(StandardCharsets.ISO_8859_1);
        assertThat(catchThrowable(() -> Http11.parse(truncated, "test")))
            .as("step 1: a short body is refused").isInstanceOf(IOException.class)
            .hasMessageContaining("truncated");
        assertThat(catchThrowable(() -> Http11.parse(
                "HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\nabc"
                    .getBytes(StandardCharsets.ISO_8859_1), "test")))
            .as("step 1: bytes past the declared length are refused too")
            .isInstanceOf(IOException.class);
        assertThat(new String(Http11.parse("HTTP/1.1 200 OK\r\nContent-Length: 3\r\n\r\nabc"
                .getBytes(StandardCharsets.ISO_8859_1), "test").body(), StandardCharsets.UTF_8))
            .as("step 1: an exact body parses").isEqualTo("abc");
        assertThat(Http11.parse(truncated, "test", true).status())
            .as("step 1: a HEAD answer's length describes no body").isEqualTo(200);
        assertThat(Http11.parse("HTTP/1.1 304 Not Modified\r\nContent-Length: 10\r\n\r\n"
                .getBytes(StandardCharsets.ISO_8859_1), "test").status())
            .as("step 1: a 304 carries no body whatever its headers say").isEqualTo(304);

        // 2. A process transport whose watchdog KILLS the child mid-response: the kill ends
        //    stdout, so the read returns normally with the partial bytes. That must be the
        //    timeout it is, never a response (it used to be returned as one).
        ProcessDockerTransport slow = new ProcessDockerTransport(List.of("sh", "-c",
            "printf 'HTTP/1.1 200 OK\\r\\n\\r\\npartial'; exec sleep 30"));
        byte[] request = Http11.request("GET", "/_ping", "docker", null, null, null);
        Throwable timedOut = catchThrowable(() -> slow.roundTrip(request, 700));
        assertThat(timedOut).as("step 2: a killed exchange is a timeout, not a short answer")
            .isInstanceOf(IOException.class).hasMessageContaining("timed out");
    }
}
