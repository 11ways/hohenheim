package be.elevenways.hohenheim.server.proxy;

import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.IntPredicate;
import java.util.zip.GZIPInputStream;

/**
 * THE reader of the access log: one site's lines over a time range, filtered, newest first, a page at a time, from
 * the live file back through the rotated and compressed days.
 *
 * AIDEV-NOTE: bounded by {@link #SCAN_BYTES} of log text per read, whatever the range or the log's size: the live
 * file and a plain day are read backwards from their end, so the newest lines cost nothing more than they are; a
 * compressed day can only be read from its start, so it is read once to count its matches and once more only when
 * the page falls inside it, and it is not started at all when its text (its gzip size field) no longer fits what is
 * left. A read that stops at the bound says how far back it came, and how many matched up to there. A read reaching a
 * line older than the range stops there: the lines of a file are in the order they were written.
 *
 * AIDEV-NOTE: a line written since the site field was added belongs to the site it names; an older line belongs to a
 * site when its hostname is one of the site's exact addresses, the only way it can be told.
 *
 * @author Jelle De Loecker
 * @since  0.1.0
 */
public final class AccessLogReader {

    /** The most log text one read goes through, uncompressed, before it stops. */
    public static final long SCAN_BYTES = 32L * 1024 * 1024;

    private static final int CHUNK = 64 * 1024;

    /**
     * What to read.
     *
     * @param siteId       the site whose lines to read
     * @param hostnames    the site's exact addresses, lower case: what a line without a site field is matched by
     * @param from         the earliest moment, null for no lower end
     * @param to           the latest moment, null for no upper end
     * @param status       which statuses to read
     * @param pathContains text the path holds, null for every path
     * @param visitor      the visitor address, a prefix when it ends in a dot or a colon; null for every visitor
     * @param offset       how many matching lines, newest first, to pass over before the page
     * @param limit        the page's size
     */
    public record Query(int siteId, @NonNull Set<String> hostnames, @Nullable Instant from, @Nullable Instant to,
                        @NonNull IntPredicate status, @Nullable String pathContains, @Nullable String visitor,
                        int offset, int limit) {

        /** @return the same query on another page */
        public @NonNull Query at(int newOffset) {
            return new Query(this.siteId, this.hostnames, this.from, this.to, this.status, this.pathContains,
                this.visitor, newOffset, this.limit);
        }

        boolean matches(@NonNull AccessLogLine line) {
            boolean ours = line.siteId() != null ? line.siteId() == this.siteId
                : this.hostnames.contains(line.host().toLowerCase(Locale.ROOT));
            return ours && this.status.test(line.status())
                && (this.pathContains == null || line.path().contains(this.pathContains))
                && (this.visitor == null || visitorMatches(line.visitor()));
        }

        private boolean visitorMatches(@NonNull String address) {
            boolean prefix = this.visitor.endsWith(".") || this.visitor.endsWith(":");
            return prefix ? address.startsWith(this.visitor) : address.equals(this.visitor);
        }
    }

    /**
     * One page of lines.
     *
     * @param lines      the page, newest first
     * @param offset     how many matching lines come before the page
     * @param matched    how many lines matched in what was read
     * @param complete   whether the whole range was read; false when the read stopped at {@link #SCAN_BYTES}
     * @param readBackTo the oldest moment an incomplete read reached, null when complete
     */
    public record Page(@NonNull List<AccessLogLine> lines, int offset, long matched, boolean complete,
                       @Nullable Instant readBackTo) {
    }

    private AccessLogReader() {
    }

    /** @return the page the query asks of the log whose live file is at {@code live} */
    public static @NonNull Page read(@NonNull Path live, @NonNull Query query) {
        Scan scan = new Scan(query);
        LocalDate fromDay = query.from() == null ? null : RotatedLogs.dayOf(query.from());
        LocalDate toDay = query.to() == null ? null : RotatedLogs.dayOf(query.to());
        // Listed BEFORE the live file is opened: a rotation between the two leaves one day out of this one read
        // instead of reading it twice (the open live file being the day moved aside).
        List<RotatedLogs.Day> days = RotatedLogs.days(live);
        try {
            // A file whose first line is after the range holds nothing of it: it is not read, nor charged.
            if (Files.isRegularFile(live) && !startsAfter(live, false, query.to())) {
                readBackwards(live, scan);
            }
            for (RotatedLogs.Day day : days) {
                if (scan.ended()) break;
                // A day's last line is of that day: a day before the range's first holds nothing of it, nor does
                // any older one.
                if (fromDay != null && day.day().isBefore(fromDay)) break;
                if (toDay != null && day.day().isAfter(toDay)
                        && startsAfter(day.path(), day.compressed(), query.to())) {
                    continue;
                }
                read(day, scan);
            }
        } catch (IOException unreadable) {
            // What was read stands; the rest is said as not read.
            scan.exhausted = true;
        }
        return scan.page();
    }

    /** One day, plain backwards or compressed forwards; a plain day compressed meanwhile is read compressed. */
    private static void read(RotatedLogs.@NonNull Day day, @NonNull Scan scan) throws IOException {
        if (!day.compressed()) {
            try {
                readBackwards(day.path(), scan);
                return;
            } catch (NoSuchFileException compressedMeanwhile) {
                day = new RotatedLogs.Day(RotatedLogs.compressedOf(day.path()), day.day(), day.sequence(), true);
            }
        }
        readCompressed(day.path(), scan);
    }

    /** Hands the file's lines to the scan from its last line back to its first, until the scan has enough. */
    private static void readBackwards(@NonNull Path file, @NonNull Scan scan) throws IOException {
        try (RandomAccessFile in = new RandomAccessFile(file.toFile(), "r")) {
            long position = in.length();
            byte[] chunk = new byte[CHUNK];
            byte[] pending = new byte[0];
            while (position > 0) {
                int size = (int) Math.min(CHUNK, position);
                position -= size;
                in.seek(position);
                in.readFully(chunk, 0, size);
                byte[] data = Arrays.copyOf(chunk, size + pending.length);
                System.arraycopy(pending, 0, data, size, pending.length);
                int end = data.length;
                for (int i = data.length - 1; i >= 0; i--) {
                    if (data[i] != '\n') continue;
                    if (!scan.offer(new String(data, i + 1, end - i - 1, StandardCharsets.UTF_8), end - i)) return;
                    end = i;
                }
                pending = Arrays.copyOf(data, end);
            }
            scan.offer(new String(pending, StandardCharsets.UTF_8), pending.length);
        } catch (IOException missing) {
            if (!Files.exists(file)) throw new NoSuchFileException(file.toString());
            throw missing;
        }
    }

    /**
     * Counts a compressed day's matches, then takes the page's lines from it in a second pass when the page falls
     * inside it; neither starts when the day's text no longer fits the bound.
     */
    private static void readCompressed(@NonNull Path file, @NonNull Scan scan) throws IOException {
        long size = uncompressedSize(file);
        // A second pass reads the day again whenever the page is not complete before it.
        boolean pageOpen = scan.matched < (long) scan.query.offset() + scan.query.limit();
        if (size * (pageOpen ? 2 : 1) > scan.budget) {
            scan.exhausted = true;
            return;
        }
        long before = scan.matched;
        Instant[] first = new Instant[1];
        forEachLine(file, line -> {
            Instant at = AccessLogLine.timeOf(line);
            if (first[0] == null) first[0] = at;
            if (scan.inRange(at) && AccessLogLine.parse(line) instanceof AccessLogLine read
                    && scan.query.matches(read)) {
                scan.matched++;
            }
        });
        scan.budget -= size;
        long count = scan.matched - before;
        if (scan.overlapsPage(before, count)) {
            List<AccessLogLine> taken = new ArrayList<>();
            long[] forward = new long[1];
            forEachLine(file, line -> {
                if (!(AccessLogLine.parse(line) instanceof AccessLogLine read) || !scan.inRange(read.at())
                        || !scan.query.matches(read)) {
                    return;
                }
                long newestFirst = before + count - 1 - forward[0]++;
                if (scan.wants(newestFirst)) taken.add(read);
            });
            scan.budget -= size;
            for (int i = taken.size() - 1; i >= 0; i--) scan.lines.add(taken.get(i));
        }
        if (first[0] != null) {
            scan.oldest = first[0];
            if (scan.query.from() != null && first[0].isBefore(scan.query.from())) scan.reachedStart = true;
        }
    }

    /** Hands every line of a compressed file to the consumer, first to last. */
    private static void forEachLine(@NonNull Path file, @NonNull Consumer<String> consumer) throws IOException {
        try (InputStream raw = Files.newInputStream(file);
             BufferedReader lines = new BufferedReader(new InputStreamReader(new GZIPInputStream(raw, CHUNK),
                 StandardCharsets.UTF_8))) {
            for (String line = lines.readLine(); line != null; line = lines.readLine()) {
                if (!line.isEmpty()) consumer.accept(line);
            }
        }
    }

    /** @return the uncompressed size a gzip file records in its last four bytes (modulo 4 GiB, as gzip keeps it) */
    private static long uncompressedSize(@NonNull Path file) throws IOException {
        try (RandomAccessFile in = new RandomAccessFile(file.toFile(), "r")) {
            if (in.length() < 4) return 0;
            in.seek(in.length() - 4);
            byte[] trailer = new byte[4];
            in.readFully(trailer);
            return (trailer[0] & 0xFFL) | (trailer[1] & 0xFFL) << 8 | (trailer[2] & 0xFFL) << 16
                | (trailer[3] & 0xFFL) << 24;
        }
    }

    /** @return whether a file's first line is after {@code to}, so it holds nothing up to it; false for no end */
    private static boolean startsAfter(@NonNull Path file, boolean compressed, @Nullable Instant to) {
        if (to == null) {
            return false;
        }
        try (InputStream raw = Files.newInputStream(file);
             BufferedReader lines = new BufferedReader(new InputStreamReader(
                 compressed ? new GZIPInputStream(raw) : raw, StandardCharsets.UTF_8))) {
            String line = lines.readLine();
            Instant first = line == null ? null : AccessLogLine.timeOf(line);
            return first == null || first.isAfter(to);
        } catch (IOException unreadable) {
            return false;
        }
    }

    /** One read in progress: the budget left, the matches counted and the page taken. */
    private static final class Scan {
        private final @NonNull Query query;
        private final List<AccessLogLine> lines = new ArrayList<>();
        private long budget = SCAN_BYTES;
        private long matched;
        private @Nullable Instant oldest;
        private boolean exhausted;
        private boolean reachedStart;

        private Scan(@NonNull Query query) {
            this.query = query;
        }

        /** @return whether the read is over: the range's start reached or the bound spent */
        boolean ended() {
            return this.exhausted || this.reachedStart;
        }

        /** @return whether the n-th match, newest first, is on the page */
        boolean wants(long newestFirst) {
            return newestFirst >= this.query.offset() && newestFirst < (long) this.query.offset() + this.query.limit();
        }

        /** @return whether matches {@code before} to {@code before + count} hold a line of the page */
        boolean overlapsPage(long before, long count) {
            return count > 0 && before < (long) this.query.offset() + this.query.limit()
                && before + count > this.query.offset();
        }

        boolean inRange(@Nullable Instant at) {
            return at != null && (this.query.from() == null || !at.isBefore(this.query.from()))
                && (this.query.to() == null || !at.isAfter(this.query.to()));
        }

        /**
         * One line, newest first, read from a plain file.
         *
         * @return false once the scan should read no further
         */
        boolean offer(@NonNull String raw, long bytes) {
            this.budget -= bytes;
            Instant at = raw.isEmpty() ? null : AccessLogLine.timeOf(raw);
            if (at != null) {
                this.oldest = at;
                if (this.query.from() != null && at.isBefore(this.query.from())) {
                    this.reachedStart = true;
                    return false;
                }
                if (this.inRange(at) && AccessLogLine.parse(raw) instanceof AccessLogLine line
                        && this.query.matches(line)) {
                    if (this.wants(this.matched)) this.lines.add(line);
                    this.matched++;
                }
            }
            if (this.budget <= 0) {
                this.exhausted = true;
                return false;
            }
            return true;
        }

        @NonNull Page page() {
            boolean complete = !this.exhausted;
            return new Page(List.copyOf(this.lines), this.query.offset(), this.matched, complete,
                complete ? null : this.oldest);
        }
    }
}
