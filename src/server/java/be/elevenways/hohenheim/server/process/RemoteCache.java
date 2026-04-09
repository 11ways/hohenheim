package be.elevenways.hohenheim.server.process;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-site shared cache with TTL, accessible by child processes via IPC.
 * Equivalent to the original Node.js remcache.
 */
public class RemoteCache {

    private final ConcurrentHashMap<String, CacheEntry> entries = new ConcurrentHashMap<>();

    private record CacheEntry(Object value, long expiresAt) {
        boolean isExpired() {
            return expiresAt > 0 && System.currentTimeMillis() > expiresAt;
        }
    }

    public void set(String key, Object value, long maxAgeMs) {
        long expiresAt = maxAgeMs > 0 ? System.currentTimeMillis() + maxAgeMs : 0;
        entries.put(key, new CacheEntry(value, expiresAt));
    }

    public Object get(String key) {
        CacheEntry entry = entries.get(key);
        if (entry == null || entry.isExpired()) {
            if (entry != null && entry.isExpired()) entries.remove(key);
            return null;
        }
        return entry.value();
    }

    public Object peek(String key) {
        CacheEntry entry = entries.get(key);
        if (entry == null || entry.isExpired()) {
            if (entry != null && entry.isExpired()) entries.remove(key);
            return null;
        }
        return entry.value();
    }

    public void remove(String key) {
        entries.remove(key);
    }

    public void clear() {
        entries.clear();
    }
}
