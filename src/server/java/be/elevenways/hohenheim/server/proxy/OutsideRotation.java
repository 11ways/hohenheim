package be.elevenways.hohenheim.server.proxy;

import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A second rotation of a log Hohenheim rotates itself: numbered copies ({@code access.log.1},
 * {@code access.log.2.gz}) or logrotate's dated ones ({@code access.log-20261124}) beside the days RotatedLogs names,
 * and the logrotate rule that names the file, so the dashboard can say which file to remove.
 *
 * AIDEV-NOTE: an older install left {@code /etc/logrotate.d/hohenheim} behind on some machines (daily,
 * copytruncate): it copies the live file away and empties it, so the lines it takes never reach a day the Access log
 * tab reads. Hohenheim does not own that file and never edits it; it only sees the conflict. The look runs in the
 * log's tidy (off the request thread, at the first line after a start and at each rotation) and keeps its answer in
 * memory, so the dashboard reads it without touching a disk.
 *
 * @author Jelle De Loecker
 * @since  0.1.0
 */
public final class OutsideRotation {

    /** Where logrotate's rules for single packages live. */
    static final Path RULES = Path.of("/etc/logrotate.d");

    /** The most of one rule read: a rule naming a log is a few lines. */
    private static final int RULE_BYTES = 64 * 1024;

    private static final Map<Path, Finding> FOUND = new ConcurrentHashMap<>();

    /**
     * A log another program rotates too.
     *
     * @param log  the live file Hohenheim writes
     * @param copy the name of one copy the other rotation made
     * @param rule the logrotate rule naming the log, null when none could be read
     */
    public record Finding(@NonNull Path log, @NonNull String copy, @Nullable Path rule) {
    }

    private OutsideRotation() {
    }

    /** @return every log another program rotates too, as the last tidy of each saw it */
    public static @NonNull List<Finding> found() {
        return List.copyOf(FOUND.values());
    }

    /** Looks beside the live file for another rotation's copies and keeps what it finds, or forgets a solved one. */
    static void look(@NonNull Path live, @NonNull Path rules) {
        Path log = live.toAbsolutePath();
        String copy = copyOf(log);
        if (copy == null) {
            FOUND.remove(log);
        } else {
            FOUND.put(log, new Finding(log, copy, ruleOf(log, rules)));
        }
    }

    /** @return the first copy's name another rotation left beside the live file, null when there is none */
    private static @Nullable String copyOf(@NonNull Path log) {
        TreeSet<String> names = new TreeSet<>();
        for (Matcher copy : RotatedLogs.beside(log, "(?:\\.\\d+|-\\d{8})(?:\\.gz)?")) {
            names.add(copy.group());
        }
        return names.isEmpty() ? null : names.first();
    }

    /** @return the first rule by name whose text names the log or a pattern of its folder, null when none does */
    private static @Nullable Path ruleOf(@NonNull Path log, @NonNull Path rules) {
        if (!Files.isDirectory(rules)) {
            return null;
        }
        String folder = String.valueOf(log.getParent()) + "/";
        List<Path> candidates = new ArrayList<>();
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(rules)) {
            for (Path entry : entries) {
                if (Files.isRegularFile(entry)) {
                    candidates.add(entry);
                }
            }
        } catch (IOException unreadable) {
            return null;
        }
        candidates.sort(null);
        for (Path rule : candidates) {
            String text = textOf(rule);
            if (text != null && (names(text, log.toString()) || text.contains(folder + "*"))) {
                return rule;
            }
        }
        return null;
    }

    /** @return whether the rule's text names the path itself, not a longer name it starts ({@code access.log.old}) */
    private static boolean names(@NonNull String text, @NonNull String path) {
        return Pattern.compile(Pattern.quote(path) + "(?![\\w.-])").matcher(text).find();
    }

    /** @return the rule's first bytes as text, null when it cannot be read */
    private static @Nullable String textOf(@NonNull Path rule) {
        try (InputStream in = Files.newInputStream(rule)) {
            return new String(in.readNBytes(RULE_BYTES), StandardCharsets.ISO_8859_1);
        } catch (IOException unreadable) {
            return null;
        }
    }
}
