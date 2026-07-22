package be.elevenways.hohenheim.server.security;

import be.elevenways.protoblast.common.Blast;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory per-reporter auto-ban budget: a single (possibly compromised)
 * reporter may trigger at most {@link #MAX_BANS_PER_HOUR} automatic bans per
 * hour. Exceeding suppresses that reporter's further auto-bans until the
 * window resets and slogs {@code security.reporter_ban_budget_exceeded}
 * loudly (once per exceeded window).
 */
public final class ReporterBanBudget {

    public static final int MAX_BANS_PER_HOUR = 20;
    private static final long WINDOW_MS = 3_600_000;

    private static final Map<String, Window> WINDOWS = new ConcurrentHashMap<>();

    private static final class Window {
        long startMs;
        int used;
        boolean warned;
    }

    private ReporterBanBudget() {
    }

    /**
     * @return true when the reporter may trigger another auto-ban (consumes
     *         one budget unit); false when its hourly budget is exhausted
     */
    public static boolean tryConsume(@NonNull String reporter) {
        Window window = WINDOWS.computeIfAbsent(reporter, k -> new Window());
        synchronized (window) {
            long now = System.currentTimeMillis();
            if (now - window.startMs >= WINDOW_MS) {
                window.startMs = now;
                window.used = 0;
                window.warned = false;
            }
            if (window.used < MAX_BANS_PER_HOUR) {
                window.used++;
                return true;
            }
            if (!window.warned) {
                window.warned = true;
                Blast.slog("security.reporter_ban_budget_exceeded", Map.of(
                    "reporter", reporter,
                    "budget", MAX_BANS_PER_HOUR));
                Blast.log("SECURITY: reporter", reporter, "exceeded its auto-ban budget of",
                    MAX_BANS_PER_HOUR, "per hour; further auto-bans from its events are"
                        + " SUPPRESSED until the window resets (possible event poisoning)");
            }
            return false;
        }
    }

    /** Test seam: forget all windows. */
    public static void resetForTests() {
        WINDOWS.clear();
    }
}
