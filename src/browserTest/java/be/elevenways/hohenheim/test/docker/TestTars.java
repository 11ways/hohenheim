package be.elevenways.hohenheim.test.docker;

import org.checkerframework.checker.nullness.qual.NonNull;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Hand-assembled ustar archives for tests: the entries a hostile container could author
 * (symlinks, hard links, devices, several entries where one is expected), built WITHOUT the
 * production writer so a test of the reader can never share that writer's mistakes.
 *
 * @author  Jelle De Loecker
 * @since   0.1.0
 */
public final class TestTars {

    private final ByteArrayOutputStream out = new ByteArrayOutputStream();

    /** A regular file entry. */
    public @NonNull TestTars file(@NonNull String name, byte @NonNull [] content) {
        header(name, '0', content.length, "", 0644);
        this.out.writeBytes(content);
        pad(content.length);
        return this;
    }

    /** A directory entry. */
    public @NonNull TestTars directory(@NonNull String name) {
        header(name.endsWith("/") ? name : name + "/", '5', 0, "", 0755);
        return this;
    }

    /** A symbolic link entry. */
    public @NonNull TestTars symlink(@NonNull String name, @NonNull String target) {
        header(name, '2', 0, target, 0777);
        return this;
    }

    /** A hard link entry. */
    public @NonNull TestTars hardlink(@NonNull String name, @NonNull String target) {
        header(name, '1', 0, target, 0644);
        return this;
    }

    /** A character device entry. */
    public @NonNull TestTars charDevice(@NonNull String name) {
        header(name, '3', 0, "", 0600);
        return this;
    }

    /** The archive with its end-of-archive marker. */
    public byte @NonNull [] build() {
        ByteArrayOutputStream archive = new ByteArrayOutputStream();
        archive.writeBytes(this.out.toByteArray());
        archive.writeBytes(new byte[1024]);
        return archive.toByteArray();
    }

    private void header(String name, char type, long size, String link, int mode) {
        byte[] block = new byte[512];
        put(block, 0, 100, name);
        octal(block, 100, 8, mode);
        octal(block, 108, 8, 0);
        octal(block, 116, 8, 0);
        octal(block, 124, 12, size);
        octal(block, 136, 12, 0);
        block[156] = (byte) type;
        put(block, 157, 100, link);
        put(block, 257, 6, "ustar");
        block[263] = '0';
        block[264] = '0';
        for (int i = 148; i < 156; i++) {
            block[i] = ' ';
        }
        int sum = 0;
        for (byte b : block) {
            sum += b & 0xFF;
        }
        String digits = String.format("%06o", sum);
        put(block, 148, 6, digits);
        block[154] = 0;
        block[155] = ' ';
        this.out.writeBytes(block);
    }

    private void pad(long size) {
        int padding = (int) ((512 - size % 512) % 512);
        this.out.writeBytes(new byte[padding]);
    }

    private static void put(byte[] block, int offset, int length, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        System.arraycopy(bytes, 0, block, offset, Math.min(bytes.length, length));
    }

    private static void octal(byte[] block, int offset, int length, long value) {
        String digits = String.format("%0" + (length - 1) + "o", value);
        put(block, offset, length - 1, digits);
        block[offset + length - 1] = 0;
    }
}
