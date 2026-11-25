package be.elevenways.hohenheim.server.proxy;

import be.elevenways.protoblast.common.Blast;
import be.elevenways.protoblast.common.thread.JobRunner;
import be.elevenways.protoblast.diagnostic.AtomicFiles;
import be.elevenways.protoblast.common.time.Now;
import be.elevenways.zenit.common.time.OfficeZone;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPOutputStream;

/**
 * The days a rotated log keeps beside its live file: {@code access.log.2026-11-24}, a second rotation of one day as
 * {@code access.log.2026-11-24.1}, each compressed to {@code .gz} once rotated, and deleted past the keeping period.
 *
 * AIDEV-NOTE: the ONE home of the naming, so the writer that rotates (LogFile) and the reader that reads the days
 * back (AccessLogReader) cannot disagree about which file holds which day. A day's file holds the lines written on
 * that office day, except the file a restart or the first rotation of an old log finds: it is named for the day of
 * its LAST line (its modification time) and may hold earlier days, which the reader judges by its first line.
 * Compression is staged through AtomicFiles and put in place in one step, so a reader never meets half a compressed
 * day; one tidy runs at a time, off the request thread.
 *
 * @author Jelle De Loecker
 * @since  0.1.0
 */
final class RotatedLogs {

    private static final String COMPRESSED = ".gz";

    /** At most one tidy at once and one waiting behind it: a later rotation's tidy lists the directory again. */
    private static final JobRunner TIDY = JobRunner.createBounded("hohenheim-log-tidy", 1, 2);

    /** One rotated day of a log. */
    record Day(@NonNull Path path, @NonNull LocalDate day, int sequence, boolean compressed) {
    }

    private RotatedLogs() {
    }

    /** @return the office day an instant falls on */
    static @NonNull LocalDate dayOf(@NonNull Instant instant) {
        return instant.atZone(OfficeZone.office()).toLocalDate();
    }

    /** @return today in the office zone */
    static @NonNull LocalDate today() {
        return dayOf(Now.instant());
    }

    /**
     * Moves the live file aside under its day's name, never over a day already rotated.
     *
     * @return the rotated file
     */
    static @NonNull Path rotate(@NonNull Path live, @NonNull LocalDate day) throws IOException {
        Path target = live.resolveSibling(live.getFileName() + "." + day);
        for (int sequence = 1; Files.exists(target) || Files.exists(compressedOf(target)); sequence++) {
            target = live.resolveSibling(live.getFileName() + "." + day + "." + sequence);
        }
        return Files.move(live, target, StandardCopyOption.ATOMIC_MOVE);
    }

    /** @return every rotated day of the live file, newest first */
    static @NonNull List<Day> days(@NonNull Path live) {
        Path directory = live.toAbsolutePath().getParent();
        List<Day> days = new ArrayList<>();
        if (directory == null || !Files.isDirectory(directory)) {
            return days;
        }
        Pattern name = Pattern.compile(Pattern.quote(live.getFileName().toString())
            + "\\.(\\d{4}-\\d{2}-\\d{2})(?:\\.(\\d{1,4}))?(\\.gz)?");
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(directory)) {
            for (Path entry : entries) {
                Matcher matched = name.matcher(entry.getFileName().toString());
                if (!matched.matches()) continue;
                try {
                    days.add(new Day(entry, LocalDate.parse(matched.group(1)),
                        matched.group(2) == null ? 0 : Integer.parseInt(matched.group(2)), matched.group(3) != null));
                } catch (DateTimeParseException notDay) {
                    // A name shaped like a day that is none (2026-13-40) is no rotated day of this log.
                }
            }
        } catch (IOException unreadable) {
            return days;
        }
        // A day rotated and not yet compressed may stand beside its finished .gz for a moment: the plain one is read.
        days.sort(Comparator.comparing(Day::day).thenComparingInt(Day::sequence)
            .thenComparing(Day::compressed).reversed());
        List<Day> unique = new ArrayList<>();
        for (Day day : days) {
            Day previous = unique.isEmpty() ? null : unique.get(unique.size() - 1);
            if (previous == null || !previous.day().equals(day.day()) || previous.sequence() != day.sequence()) {
                unique.add(day);
            } else if (!day.compressed()) {
                unique.set(unique.size() - 1, day);
            }
        }
        return unique;
    }

    /**
     * Compresses every rotated day not compressed yet and deletes every day older than the keeping period, off the
     * caller's thread; a tidy already waiting covers this one.
     *
     * @param keepDays days kept, 0 or less keeps every day
     */
    static void tidySoon(@NonNull Path live, int keepDays) {
        TIDY.tryRunAsync(() -> tidy(live, today(), keepDays));
    }

    /**
     * Compresses and deletes as {@link #tidySoon} does, on the caller's thread.
     *
     * @param today    the office day the keeping period counts back from
     * @param keepDays days kept, 0 or less keeps every day
     */
    static void tidy(@NonNull Path live, @NonNull LocalDate today, int keepDays) {
        for (Day day : days(live)) {
            Path plain = day.compressed() ? plainOf(day.path()) : day.path();
            try {
                // What a tidy ended by a stop or a crash staged is dropped; only one tidy runs at once.
                AtomicFiles.discardStaged(compressedOf(plain));
                if (keepDays > 0 && day.day().isBefore(today.minusDays(keepDays))) {
                    Files.deleteIfExists(plain);
                    Files.deleteIfExists(compressedOf(plain));
                } else if (!day.compressed()) {
                    compress(plain);
                }
            } catch (IOException failed) {
                // The file name only: the lines are visitors' data and never reach a log or a message.
                Blast.log("RotatedLogs: could not tidy", day.path().getFileName(), "-", failed.getClass().getName());
            }
        }
    }

    /** @return the compressed twin of a rotated file */
    static @NonNull Path compressedOf(@NonNull Path rotated) {
        return rotated.resolveSibling(rotated.getFileName() + COMPRESSED);
    }

    /** @return the rotated file a compressed one was made of */
    private static @NonNull Path plainOf(@NonNull Path compressed) {
        String name = compressed.getFileName().toString();
        return compressed.resolveSibling(name.substring(0, name.length() - COMPRESSED.length()));
    }

    /** Writes the .gz beside the day, puts it in place in one step and only then removes the plain day. */
    private static void compress(@NonNull Path rotated) throws IOException {
        try (AtomicFiles.Staged staged = AtomicFiles.stage(compressedOf(rotated))) {
            try (InputStream in = Files.newInputStream(rotated);
                 OutputStream out = new GZIPOutputStream(Files.newOutputStream(staged.path()))) {
                in.transferTo(out);
            } catch (NoSuchFileException gone) {
                return;
            }
            // Put in place durably before the plain day goes, so a power loss cannot lose the day.
            staged.commitDurably();
        }
        Files.deleteIfExists(rotated);
    }

    /** @return the modification day of a file, null when it does not exist */
    static @Nullable LocalDate modifiedDay(@NonNull Path file) {
        try {
            return dayOf(Files.getLastModifiedTime(file).toInstant());
        } catch (IOException missing) {
            return null;
        }
    }
}
