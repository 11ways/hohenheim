package be.elevenways.hohenheim.server.proxy;

import be.elevenways.protoblast.common.Blast;
import be.elevenways.protoblast.common.thread.JobRunner;
import be.elevenways.protoblast.common.util.Closeables;
import be.elevenways.protoblast.diagnostic.AtomicFiles;
import be.elevenways.protoblast.common.time.Now;
import be.elevenways.zenit.common.time.OfficeZone;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
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
import java.util.function.Function;
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
 * its LAST line (its modification time) and may hold earlier days. The tidy splits such a file once into one file per
 * day its lines were written on ({@link #split}), so an old single log of months becomes days the reader can read
 * within its bound. Compression is staged through AtomicFiles and put in place in one step, so a reader never meets
 * half a compressed day; one tidy runs at a time, off the request thread.
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
        return Files.move(live, freeName(live, day), StandardCopyOption.ATOMIC_MOVE);
    }

    /** @return the first name of the day that neither a plain nor a compressed rotated file holds */
    private static @NonNull Path freeName(@NonNull Path live, @NonNull LocalDate day) {
        Path target = live.resolveSibling(live.getFileName() + "." + day);
        for (int sequence = 1; Files.exists(target) || Files.exists(compressedOf(target)); sequence++) {
            target = live.resolveSibling(live.getFileName() + "." + day + "." + sequence);
        }
        return target;
    }

    /** @return every rotated day of the live file, newest first */
    static @NonNull List<Day> days(@NonNull Path live) {
        List<Day> days = new ArrayList<>();
        Path directory = live.toAbsolutePath().getParent();
        for (Matcher matched : beside(live, "\\.(\\d{4}-\\d{2}-\\d{2})(?:\\.(\\d{1,4}))?(\\.gz)?")) {
            try {
                days.add(new Day(directory.resolve(matched.group()), LocalDate.parse(matched.group(1)),
                    matched.group(2) == null ? 0 : Integer.parseInt(matched.group(2)), matched.group(3) != null));
            } catch (DateTimeParseException notDay) {
                // A name shaped like a day that is none (2026-13-40) is no rotated day of this log.
            }
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
     * @param suffix what follows the live file's name, as a regular expression
     * @return the matched names of the files beside the live one named so; none when its folder cannot be listed
     */
    static @NonNull List<Matcher> beside(@NonNull Path live, @NonNull String suffix) {
        Path directory = live.toAbsolutePath().getParent();
        List<Matcher> found = new ArrayList<>();
        if (directory == null || !Files.isDirectory(directory)) {
            return found;
        }
        Pattern name = Pattern.compile(Pattern.quote(live.getFileName().toString()) + suffix);
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(directory)) {
            for (Path entry : entries) {
                Matcher matched = name.matcher(entry.getFileName().toString());
                if (matched.matches()) found.add(matched);
            }
        } catch (IOException unreadable) {
            return found;
        }
        return found;
    }

    /**
     * Splits, compresses and deletes rotated days as {@link #tidy} does, off the caller's thread; a tidy already
     * waiting covers this one.
     *
     * @param keepDays  days kept, 0 or less keeps every day
     * @param writtenAt when a line of this log was written, null for a line no clock reads
     */
    static void tidySoon(@NonNull Path live, int keepDays, @NonNull Function<String, @Nullable Instant> writtenAt) {
        TIDY.tryRunAsync(() -> tidy(live, today(), keepDays, writtenAt));
    }

    /**
     * Deletes every rotated day older than the keeping period, splits a rotated file holding earlier days into its
     * days, compresses every other day not compressed yet, and notes an outside rotation of the same file
     * ({@link OutsideRotation}), on the caller's thread.
     *
     * @param today     the office day the keeping period counts back from
     * @param keepDays  days kept, 0 or less keeps every day
     * @param writtenAt when a line of this log was written, null for a line no clock reads
     */
    static void tidy(@NonNull Path live, @NonNull LocalDate today, int keepDays,
                     @NonNull Function<String, @Nullable Instant> writtenAt) {
        for (Day day : days(live)) {
            Path plain = day.compressed() ? plainOf(day.path()) : day.path();
            try {
                // What a tidy ended by a stop or a crash staged is dropped; only one tidy runs at once.
                AtomicFiles.discardStaged(compressedOf(plain));
                if (keepDays > 0 && day.day().isBefore(today.minusDays(keepDays))) {
                    Files.deleteIfExists(plain);
                    Files.deleteIfExists(compressedOf(plain));
                } else if (!day.compressed() && !split(live, day, today, keepDays, writtenAt)) {
                    compress(plain);
                }
            } catch (IOException failed) {
                // The file name only: the lines are visitors' data and never reach a log or a message.
                Blast.log("RotatedLogs: could not tidy", day.path().getFileName(), "-", failed.getClass().getName());
            }
        }
        OutsideRotation.look(live, OutsideRotation.RULES);
    }

    /**
     * Splits a rotated file holding lines of days before the one it is named for into one compressed file per day
     * its lines were written on, then removes it; a line no clock reads (torn, another program's text) stays in the
     * day of the line before it, those before the first dated line in that line's day, and the days past the keeping
     * period are not kept.
     *
     * AIDEV-NOTE: memory stays one line whatever the file's size: one pass finds the first dated line, a second
     * streams every line into its day's staged file, opening the next day's when a later day starts (a line dated
     * before the day being written, a request that crossed midnight while waiting for the lock, stays in it). Bytes
     * pass as ISO-8859-1, so every line is copied byte for byte whatever it holds; only its end is written as one
     * newline, which is how LogFile ends every line.
     * place durably before the file goes, so a stop loses nothing; a crash among those last renames splits the file
     * again at the next tidy, and the days already in place then stand twice (as a second sequence of their day).
     *
     * @return whether the file held earlier days and was split; false leaves it as it was
     */
    static boolean split(@NonNull Path live, @NonNull Day rotated, @NonNull LocalDate today, int keepDays,
                         @NonNull Function<String, @Nullable Instant> writtenAt) throws IOException {
        LocalDate current = firstDay(rotated.path(), writtenAt);
        if (current == null || !current.isBefore(rotated.day())) {
            return false;
        }
        List<AtomicFiles.Staged> parts = new ArrayList<>();
        Writer out = null;
        try (BufferedReader in = Files.newBufferedReader(rotated.path(), StandardCharsets.ISO_8859_1)) {
            out = part(live, rotated, current, today, keepDays, parts);
            for (String line = in.readLine(); line != null; line = in.readLine()) {
                Instant at = writtenAt.apply(line);
                LocalDate lineDay = at == null ? current : dayOf(at);
                if (lineDay.isAfter(current)) {
                    current = lineDay;
                    if (out != null) {
                        out.close();
                    }
                    out = part(live, rotated, current, today, keepDays, parts);
                }
                if (out != null) {
                    out.write(line);
                    out.write('\n');
                }
            }
            if (out != null) {
                out.close();
            }
            for (AtomicFiles.Staged staged : parts) {
                staged.commitDurably();
            }
            Files.delete(rotated.path());
            return true;
        } finally {
            Closeables.closeQuietly(out);
            for (AtomicFiles.Staged staged : parts) {
                Closeables.closeQuietly(staged);
            }
        }
    }

    /** @return the office day of the file's first line a clock reads, null when none does */
    private static @Nullable LocalDate firstDay(@NonNull Path file,
                                               @NonNull Function<String, @Nullable Instant> writtenAt)
            throws IOException {
        try (BufferedReader in = Files.newBufferedReader(file, StandardCharsets.ISO_8859_1)) {
            for (String line = in.readLine(); line != null; line = in.readLine()) {
                Instant at = writtenAt.apply(line);
                if (at != null) {
                    return dayOf(at);
                }
            }
        }
        return null;
    }

    /**
     * Stages the compressed file of one day a split writes: the split file's own name for its own day (a .gz there
     * can only be a compression of the split file itself, which a stop interrupted, so it is replaced), else the
     * day's first free name.
     *
     * @return the writer of the day's lines, null for a day past the keeping period, whose lines are dropped
     */
    private static @Nullable Writer part(@NonNull Path live, @NonNull Day rotated, @NonNull LocalDate day,
                                         @NonNull LocalDate today, int keepDays,
                                         @NonNull List<AtomicFiles.Staged> parts) throws IOException {
        if (keepDays > 0 && day.isBefore(today.minusDays(keepDays))) {
            return null;
        }
        Path target = compressedOf(day.equals(rotated.day()) ? rotated.path() : freeName(live, day));
        // What a split a crash ended staged for this day is dropped: this split writes the day again.
        AtomicFiles.discardStaged(target);
        AtomicFiles.Staged staged = AtomicFiles.stage(target);
        parts.add(staged);
        OutputStream file = Files.newOutputStream(staged.path());
        try {
            return new BufferedWriter(new OutputStreamWriter(new GZIPOutputStream(file), StandardCharsets.ISO_8859_1));
        } catch (IOException failed) {
            file.close();
            throw failed;
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
