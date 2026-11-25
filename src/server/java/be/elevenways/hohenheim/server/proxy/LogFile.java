package be.elevenways.hohenheim.server.proxy;

import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.protoblast.common.util.Closeables;
import be.elevenways.zenit.common.Zenit;
import be.elevenways.zenit.common.setting.SettingDefinition;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Function;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * One append-only, line-oriented log file that rotates by office day and keeps the days the keeping setting names;
 * best-effort, a write failure never throws.
 *
 * AIDEV-NOTE: ONE writer per file, kept open, serialized by this instance's lock so concurrent completions
 * cannot interleave partial lines. Each line is flushed, so a crash loses nothing. The access log and the fail2ban
 * domain-miss log each own one instance.
 *
 * AIDEV-NOTE: rotation is the app's own (no cron, no logrotate): the first line of a new office day moves the file
 * aside under the day its lines were written on (RotatedLogs names it) and opens a fresh one, inside the same lock
 * every line is written under, so no line lands in a file being moved. A file a restart or an upgrade finds from an
 * earlier day is moved aside the same way before the first line. Compressing the rotated day and deleting days past
 * the keeping period run off the request thread. A file moved away by someone else (an external logrotate left in
 * place) is still followed: before each line the file at the path is compared by file key (inode) with the one the
 * writer holds, and a renamed or deleted file or a changed path reopens it. Such a second rotation is shown on the
 * dashboard (OutsideRotation).
 *
 * @author Jelle De Loecker
 * @since  0.1.0
 */
final class LogFile {

    private final Object lock = new Object();
    private Writer writer;
    private Path writerPath;
    private Object writerFileKey;
    /** The office day of the lines the held file carries. */
    private LocalDate writerDay;
    /** The path whose rotated days were tidied since this process opened it. */
    private Path tidiedPath;
    /** When a line of this log was written, null for a line no clock reads; the tidy splits old files by it. */
    private final Function<String, @Nullable Instant> writtenAt;

    /** @param writtenAt when a line of this log was written, null for a line no clock reads */
    LogFile(@NonNull Function<String, @Nullable Instant> writtenAt) {
        this.writtenAt = writtenAt;
    }

    /** Append one line to the file the path setting names; nothing when it names none. */
    void appendTo(SettingDefinition<String> pathSetting, String line) {
        String path = Zenit.SETTINGS_VALUES.getValue(pathSetting);
        if (path == null || path.isEmpty()) {
            return;
        }
        this.append(Path.of(path), line);
    }

    /** Append one line (a newline is added) to the file at {@code path}, rotating it first on a new office day. */
    void append(Path path, String line) {
        boolean tidy;
        synchronized (this.lock) {
            try {
                LocalDate today = RotatedLogs.today();
                Writer target = writerFor(path, today);
                target.write(line);
                target.write('\n');
                target.flush();
            } catch (IOException failure) {
                // Don't let log writing failures break request handling; reopen next time.
                closeWriter();
            }
            tidy = !path.equals(this.tidiedPath);
            this.tidiedPath = path;
        }
        if (tidy) {
            Integer keepDays = Zenit.SETTINGS_VALUES.getValue(HohenheimSettings.Logging.KEEP_DAYS);
            RotatedLogs.tidySoon(path, keepDays == null ? 0 : keepDays, this.writtenAt);
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

    /**
     * The writer of the file at the path for a line of {@code today}: the held one while it still is that file of
     * that day, else the file reopened, moved aside first when its lines are of an earlier day.
     */
    private Writer writerFor(Path path, LocalDate today) throws IOException {
        Object currentKey = fileKey(path);
        boolean held = this.writer != null && path.equals(this.writerPath) && currentKey != null
            && Objects.equals(currentKey, this.writerFileKey);
        if (held && today.equals(this.writerDay)) {
            return this.writer;
        }
        // The day of the lines already there: the held file's own, else its last write (a restart, an old log).
        LocalDate fileDay = held ? this.writerDay : RotatedLogs.modifiedDay(path);
        closeWriter();
        Path parent = path.getParent();
        if (parent != null && !Files.exists(parent)) {
            Files.createDirectories(parent);
        }
        if (fileDay != null && fileDay.isBefore(today)) {
            RotatedLogs.rotate(path, fileDay);
            // A new day's file: the rotated one is compressed and the days past keeping go at the next line.
            this.tidiedPath = null;
        }
        this.writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8,
            StandardOpenOption.CREATE, StandardOpenOption.APPEND, StandardOpenOption.WRITE);
        this.writerPath = path;
        this.writerFileKey = fileKey(path);
        this.writerDay = today;
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
        Closeables.closeQuietly(this.writer);
        this.writer = null;
        this.writerPath = null;
        this.writerFileKey = null;
        this.writerDay = null;
    }
}
