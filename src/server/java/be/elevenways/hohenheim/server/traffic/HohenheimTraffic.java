package be.elevenways.hohenheim.server.traffic;

import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.hohenheim.server.DataDirectory;
import be.elevenways.zenit.common.time.OfficeZone;
import be.elevenways.zenit.traffic.common.TrafficHit;
import be.elevenways.zenit.traffic.server.TrafficStore;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReferenceArray;

/**
 * Hohenheim's two traffic stores: the proxy's, one source per site plus one for names nobody serves, and the DNS
 * server's, one source per zone; both under the data path, on the office clock.
 *
 * AIDEV-NOTE: a store opens at its first hit or read, not at boot, so a role that never sees traffic keeps no files
 * and boot waits for nothing. Counting a hit takes no lock: the open store is read from one volatile slot and checked
 * against the data path as the setting names it now; only a store's first open, and its move after the data path
 * setting changed (the old store writes what it counted where it was, the new one counts on from what the new place
 * holds), take the lock, on the thread that counts AFTER its answer was sent (the proxy's completion listener, the
 * DNS server's thread past its reply), so no answer waits for it. A move away from a place that cannot be written
 * loses what that store held since its last write, which its write problem was telling the dashboard. Between the
 * stop step and the next start nothing opens a store: a request finishing then is dropped and counted, never written
 * after the files' process wrote its last; a hit offered to a store in the very moment it is retired is dropped but
 * missing from the dashboard's count, a race of one hit. A site's source is its id, which never changes and is
 * never reused, so renaming a site keeps its totals.
 *
 * @author Jelle De Loecker
 * @since  0.1.0
 */
public final class HohenheimTraffic {

    /** The proxy's source for a hostname no site serves. */
    public static final String UNSERVED = "unserved";

    /** Hits waiting to be counted before one is dropped and the dashboard says so. */
    public static final int QUEUE_CAPACITY = 10_000;

    /** What a store counts; its key names the store's directory under {@code traffic/}. */
    public enum Counted {
        WEB("web"),
        DNS("dns");

        private final @NonNull String directory;

        Counted(@NonNull String directory) {
            this.directory = directory;
        }
    }

    /** An open store and the data path it was opened under. */
    private record Open(@NonNull Path dataPath, @NonNull TrafficStore store) {
    }

    private static final Object LOCK = new Object();
    private static final AtomicReferenceArray<Open> OPEN = new AtomicReferenceArray<>(Counted.values().length);
    /** Hits dropped by stores closed since (moved or stopped), so the dashboard's count never goes back. */
    private static final AtomicLong CLOSED_DROPS = new AtomicLong();
    private static volatile boolean stopped;

    private HohenheimTraffic() {
    }

    /** Counts the hit in the store; never blocks past a store's first open and never throws. */
    public static void count(@NonNull Counted counted, @NonNull TrafficHit hit) {
        TrafficStore store = store(counted);
        if (store == null) {
            CLOSED_DROPS.incrementAndGet();
            return;
        }
        store.offer(hit);
    }

    /** @return the store under the data path the setting names now, opened at the first call; null when stopped */
    public static @Nullable TrafficStore store(@NonNull Counted counted) {
        Path dataPath = DataDirectory.path();
        Open open = OPEN.get(counted.ordinal());
        if (!stopped && open != null && open.dataPath().equals(dataPath)) {
            return open.store();
        }
        synchronized (LOCK) {
            if (stopped) {
                return null;
            }
            open = OPEN.get(counted.ordinal());
            if (open != null && open.dataPath().equals(dataPath)) {
                return open.store();
            }
            if (open != null) {
                retire(open.store());
            }
            TrafficStore store = TrafficStore.open(TrafficStore.Config.of(
                dataPath.resolve(Path.of("traffic", counted.directory)), OfficeZone.office(),
                HohenheimSettings.Traffic.SETTINGS, QUEUE_CAPACITY, null));
            store.start();
            OPEN.set(counted.ordinal(), new Open(dataPath, store));
            return store;
        }
    }

    /** @return the source key of a site's traffic, {@link #UNSERVED} for a name no site serves (AccessLog.NO_SITE) */
    public static @NonNull String sourceOf(int siteId) {
        return siteId > 0 ? String.valueOf(siteId) : UNSERVED;
    }

    /** @return the source key of a DNS zone's queries */
    public static @NonNull String zoneSource(int zoneId) {
        return String.valueOf(zoneId);
    }

    /** @return hits dropped since this process started: no room in a queue, or counted while stopped */
    public static long droppedHits() {
        long dropped = CLOSED_DROPS.get();
        for (int at = 0; at < OPEN.length(); at++) {
            Open open = OPEN.get(at);
            if (open != null) {
                dropped += open.store().droppedHits();
            }
        }
        return dropped;
    }

    /** @return why an open store's latest write failed, null while every write succeeds */
    public static @Nullable String writeProblem() {
        for (int at = 0; at < OPEN.length(); at++) {
            Open open = OPEN.get(at);
            String problem = open == null ? null : open.store().writeProblem();
            if (problem != null) {
                return problem;
            }
        }
        return null;
    }

    /** Lets the next hit open the stores again; the start of the process, and of one after a stop step. */
    public static void start() {
        synchronized (LOCK) {
            stopped = false;
        }
    }

    /** Writes what each store counted and stops counting until {@link #start()}. */
    public static void stop() {
        synchronized (LOCK) {
            stopped = true;
            for (int at = 0; at < OPEN.length(); at++) {
                Open open = OPEN.getAndSet(at, null);
                if (open != null) {
                    retire(open.store());
                }
            }
        }
    }

    /** Closes a store, keeping its drops in the process's count. */
    private static void retire(@NonNull TrafficStore store) {
        store.close();
        CLOSED_DROPS.addAndGet(store.droppedHits());
    }
}
