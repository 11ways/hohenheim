package be.elevenways.hohenheim.server.proxy;

import be.elevenways.zenit.common.Zenit;
import be.elevenways.zenit.common.setting.SettingDefinition;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Locale;
import java.util.Objects;

/**
 * One append-only, line-oriented log file that survives logrotate; best-effort, a write failure never throws.
 *
 * AIDEV-NOTE: ONE writer per file, kept open, serialized by this instance's lock so concurrent completions
 * cannot interleave partial lines. Rotation-safe: before each line the file at the configured path is compared
 * (by file key, i.e. inode) with the one the writer holds, and a renamed or deleted file (logrotate) or a changed
 * path reopens it. Each line is flushed, so a crash loses nothing. The access log and the fail2ban domain-miss
 * log each own one instance.
 *
 * @author Jelle De Loecker
 * @since  0.1.0
 */
final class LogFile {

    private final Object lock = new Object();
    private Writer writer;
    private Path writerPath;
    private Object writerFileKey;

    /** Whether a log's on/off setting is on. */
    static boolean enabled(SettingDefinition<Boolean> setting) {
        return Boolean.TRUE.equals(Zenit.SETTINGS_VALUES.getValue(setting));
    }

    /** Append one line to the file the path setting names; nothing when it names none. */
    void appendTo(SettingDefinition<String> pathSetting, String line) {
        String path = Zenit.SETTINGS_VALUES.getValue(pathSetting);
        if (path == null || path.isEmpty()) {
            return;
        }
        this.append(Path.of(path), line);
    }

    /** Append one line (a newline is added) to the file at {@code path}. */
    void append(Path path, String line) {
        synchronized (this.lock) {
            try {
                Writer target = writerFor(path);
                target.write(line);
                target.write('\n');
                target.flush();
            } catch (IOException failure) {
                // Don't let log writing failures break request handling; reopen next time.
                closeWriter();
            }
        }
    }

    /**
     * Backslash, double-quote and every control character escaped; null becomes "-".
     *
     * AIDEV-NOTE: the PATH is logged decoded, where %0a IS a newline, so one request could forge whole log
     * lines. Escaping every client-controlled field here is the rule that does not depend on knowing which
     * field can carry what.
     */
    static String escape(String value) {
        if (value == null) {
            return "-";
        }
        StringBuilder out = null;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            String replacement = null;
            if (c == '\\') {
                replacement = "\\\\";
            } else if (c == '"') {
                replacement = "\\\"";
            } else if (c < 0x20 || c == 0x7f) {
                replacement = String.format(Locale.ROOT, "\\x%02x", (int) c);
            }
            if (replacement != null && out == null) {
                out = new StringBuilder(value.length() + 8).append(value, 0, i);
            }
            if (out != null) {
                if (replacement != null) {
                    out.append(replacement);
                } else {
                    out.append(c);
                }
            }
        }
        return out != null ? out.toString() : value;
    }

    /** An escaped quoted-field value; null or empty becomes "-". */
    static String quote(String value) {
        if (value == null || value.isEmpty()) {
            return "-";
        }
        return escape(value);
    }

    private Writer writerFor(Path path) throws IOException {
        Object currentKey = fileKey(path);
        if (this.writer != null && path.equals(this.writerPath) && currentKey != null
                && Objects.equals(currentKey, this.writerFileKey)) {
            return this.writer;
        }
        closeWriter();
        Path parent = path.getParent();
        if (parent != null && !Files.exists(parent)) {
            Files.createDirectories(parent);
        }
        this.writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8,
            StandardOpenOption.CREATE, StandardOpenOption.APPEND, StandardOpenOption.WRITE);
        this.writerPath = path;
        this.writerFileKey = fileKey(path);
        return this.writer;
    }

    private static Object fileKey(Path path) {
        try {
            return Files.readAttributes(path, BasicFileAttributes.class).fileKey();
        } catch (IOException missing) {
            return null;
        }
    }

    private void closeWriter() {
        if (this.writer != null) {
            try {
                this.writer.close();
            } catch (IOException ignored) {
                // best effort
            }
        }
        this.writer = null;
        this.writerPath = null;
        this.writerFileKey = null;
    }
}
