package be.elevenways.hohenheim.server.files;

import be.elevenways.domino.common.DominoFile;
import be.elevenways.zenit.common.validation.Violations;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * What the file manager's "upload" and "save" actions WRITE, decided from the submitted
 * form alone.
 *
 * AIDEV-NOTE: the defect: "Upload" pressed with no file selected fell back to a
 * {@code content} field the upload form never sends, so the write carried ZERO bytes and
 * emptied whatever file lived at that path. The refusal must happen here, before
 * {@code InstanceFiles.write} is ever reached, and the "save text" lane must keep working.
 */
class InstanceFileUploadTest {

    /** A submitted multipart part. */
    private record Part(@NonNull String name, byte @NonNull [] bytes) implements DominoFile {
        @Override public @NonNull String getName() { return this.name; }
        @Override public @Nullable String getType() { return "application/octet-stream"; }
        @Override public long getSize() { return this.bytes.length; }
        @Override public long getLastModified() { return 0; }
        @Override public byte[] getBytes() { return this.bytes; }
        @Override public @NonNull String getText() { return new String(this.bytes, StandardCharsets.UTF_8); }
        @Override public @NonNull String getText(String encoding) { return getText(); }
    }

    private static String keyOf(Throwable refused) {
        assertThat(refused).isInstanceOf(Violations.class);
        return ((Violations) refused).all().get(0).message().key();
    }

    @Test
    void anUploadWithoutAChosenFileIsRefusedAndNeverBecomesAnEmptyWrite() {
        // 1. No file part at all (the browser sent only the hidden fields): refused by name.
        assertThat(keyOf(catchThrowable(() -> InstanceFileEndpoints.uploadOf(
                Map.of("action", "upload", "path", "/data/world.dat")))))
            .as("step 1: an upload with no file part is refused, not a zero-byte write")
            .isEqualTo("files_upload_missing");

        // 2. The shape a browser really sends for an untouched file input: a part with an
        //    empty filename and no bytes. Same refusal.
        assertThat(keyOf(catchThrowable(() -> InstanceFileEndpoints.uploadOf(
                Map.of("file", List.of(new Part("", new byte[0])))))))
            .as("step 2: an unselected file input is refused too")
            .isEqualTo("files_upload_missing");

        // 3. A chosen file uploads its bytes -- including a chosen EMPTY file, which is a
        //    legitimate thing to upload and keeps its name.
        assertThat(new String(InstanceFileEndpoints.uploadOf(
                Map.of("file", new Part("world.dat", "payload".getBytes(StandardCharsets.UTF_8)))),
                StandardCharsets.UTF_8))
            .as("step 3: a chosen file's bytes are the write").isEqualTo("payload");
        assertThat(InstanceFileEndpoints.uploadOf(
                Map.of("file", List.of(new Part("empty.txt", new byte[0])))))
            .as("step 3: a chosen empty file is an empty upload, by choice").isEmpty();

        // 4. The "save" lane is untouched: the editor's text is the write, and a file part
        //    with bytes still wins there exactly as before.
        assertThat(new String(InstanceFileEndpoints.contentOf(
                Map.of("action", "save", "content", "edited text")), StandardCharsets.UTF_8))
            .as("step 4: save writes the submitted text").isEqualTo("edited text");
        assertThat(new String(InstanceFileEndpoints.contentOf(Map.of("content", "ignored",
                "file", new Part("x", "from file".getBytes(StandardCharsets.UTF_8)))),
                StandardCharsets.UTF_8))
            .as("step 4: the API write lane still prefers a file part").isEqualTo("from file");
    }
}
