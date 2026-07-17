package be.elevenways.hohenheim.server.devtunnel;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * The in-memory registry of live dev-site leases, keyed by namespace site id
 * plus claimed name. Registration replaces an existing lease for the same name
 * (the newest dev server wins); the replaced connection is told and closed.
 */
public final class DevLeases {

    private static final Map<String, DevLease> LEASES = new ConcurrentHashMap<>();

    private DevLeases() {}

    private static String key(int siteId, String name) {
        return siteId + "|" + name;
    }

    public static @Nullable DevLease find(int siteId, String name) {
        return LEASES.get(key(siteId, name));
    }

    /**
     * Install a new lease, returning the lease it replaced (already removed
     * from the registry) or null. The caller notifies and closes the loser.
     */
    static @Nullable DevLease claim(DevLease lease) {
        return LEASES.put(key(lease.getSiteId(), lease.getName()), lease);
    }

    /** Remove the lease, but only when this exact lease still owns the name. */
    static void release(DevLease lease) {
        LEASES.remove(key(lease.getSiteId(), lease.getName()), lease);
    }

    public static List<DevLease> forSite(int siteId) {
        List<DevLease> result = new ArrayList<>();
        for (DevLease lease : LEASES.values()) {
            if (lease.getSiteId() == siteId) {
                result.add(lease);
            }
        }
        result.sort(java.util.Comparator.comparing(DevLease::getName));
        return result;
    }

    public static int count() {
        return LEASES.size();
    }
}
