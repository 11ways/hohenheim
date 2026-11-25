package be.elevenways.hohenheim.server.host;

import be.elevenways.hohenheim.model.HostMode;
import be.elevenways.hohenheim.model.HostModel;
import be.elevenways.protoblast.common.Blast;
import be.elevenways.zenit.common.orm.datasource.Row;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * A host's memory as its kernel sees it: what is still available for new work and how full its swap is, read from
 * {@code /proc/meminfo} over the host's own shell and stored as the {@link HostFact#MEM_AVAILABLE},
 * {@link HostFact#SWAP_TOTAL} and {@link HostFact#SWAP_FREE} facts beside its memory total.
 *
 * AIDEV-NOTE: the daemon's {@code /info} gives only the total, which is the budget apps are booked against; whether
 * the host is really short of memory only its kernel says. A host whose shell does not reach that machine (an Incus
 * host reached over its API alone) is not measured: its facts stay absent, which every reader takes as no news.
 *
 * @author Jelle De Loecker
 * @since  0.10.0
 */
public final class HostMemory {

    /** The {@code /proc/meminfo} lines a reading needs; their values are kB. */
    static final String READ = "grep -E '^(MemTotal|MemAvailable|SwapTotal|SwapFree):' /proc/meminfo";

    /**
     * One reading.
     *
     * @param measuredAt when it was measured, null for a reading not stored yet
     */
    public record Reading(long totalBytes, long availableBytes, long swapTotalBytes, long swapFreeBytes,
                          @Nullable Instant measuredAt) {

        /** @return the swap in use */
        public long swapUsedBytes() {
            return Math.max(0, this.swapTotalBytes - this.swapFreeBytes);
        }
    }

    private HostMemory() {
    }

    /** @return whether the host's shell runs on that host itself, so its kernel can be read */
    public static boolean measurable(@NonNull Row server) {
        return HostMode.LOCAL.declaredBy(server) || HostModel.hasSshLane(server);
    }

    /** @return the host's memory read now over {@code shell}, null when it would not answer */
    public static @Nullable Reading measure(@NonNull Row server, @NonNull HostShell shell) {
        HostShell.Result result = shell.run(READ);
        Reading reading = result.ok() ? parse(result.text()) : null;
        if (reading == null) {
            Blast.log("HOST MEMORY: could not read the memory of", server.get(HostModel.NAME), "-", result.text());
        }
        return reading;
    }

    /** @return the reading in a {@code /proc/meminfo} body, null when one of its lines is missing */
    public static @Nullable Reading parse(@NonNull String meminfo) {
        Map<String, Long> kilobytes = new HashMap<>();
        for (String line : meminfo.split("\n")) {
            String[] parts = line.trim().split("\\s+");
            if (parts.length >= 2 && parts[0].endsWith(":")) {
                try {
                    kilobytes.put(parts[0].substring(0, parts[0].length() - 1), Long.parseLong(parts[1]));
                } catch (NumberFormatException unreadable) {
                    // not a figure: the line is not one a reading uses
                }
            }
        }
        Long total = kilobytes.get("MemTotal");
        Long available = kilobytes.get("MemAvailable");
        Long swapTotal = kilobytes.get("SwapTotal");
        Long swapFree = kilobytes.get("SwapFree");
        if (total == null || available == null || swapTotal == null || swapFree == null || total <= 0) {
            return null;
        }
        return new Reading(total * 1024, available * 1024, swapTotal * 1024, swapFree * 1024, null);
    }

    /** Records a reading on the row, stamped {@code at}, without saving it. */
    public static void record(@NonNull Row server, @NonNull Reading reading, @NonNull Instant at) {
        HostPreflight.recordReadings(server, Map.of(HostFact.MEM_AVAILABLE, reading.availableBytes(),
            HostFact.SWAP_TOTAL, reading.swapTotalBytes(), HostFact.SWAP_FREE, reading.swapFreeBytes()), at);
    }

    /** @return the host's stored reading against its stored memory total, null when it was never measured */
    public static @Nullable Reading storedOf(@NonNull Row server) {
        if (!(server.get(HostModel.CAPABILITIES) instanceof Map<?, ?> capabilities)
                || !(capabilities.get(HostFact.MEM_TOTAL.token()) instanceof Number total)
                || !(capabilities.get(HostFact.MEM_AVAILABLE.token()) instanceof Number available)
                || !(capabilities.get(HostFact.SWAP_TOTAL.token()) instanceof Number swapTotal)
                || !(capabilities.get(HostFact.SWAP_FREE.token()) instanceof Number swapFree)
                || total.longValue() <= 0) {
            return null;
        }
        return new Reading(total.longValue(), available.longValue(), swapTotal.longValue(), swapFree.longValue(),
            HostPreflight.factMeasuredAt(server, HostFact.MEM_AVAILABLE.token()));
    }
}
