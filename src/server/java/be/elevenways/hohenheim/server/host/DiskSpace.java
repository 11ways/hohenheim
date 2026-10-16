package be.elevenways.hohenheim.server.host;

/**
 * One filesystem's size and what is still free on it, in bytes.
 *
 * @param totalBytes the filesystem's size
 * @param freeBytes  what an unprivileged writer may still use, never more than the size
 * @author Jelle De Loecker
 * @since  0.10.0
 */
public record DiskSpace(long totalBytes, long freeBytes) {

    public DiskSpace {
        if (totalBytes <= 0 || freeBytes < 0 || freeBytes > totalBytes) {
            throw new IllegalArgumentException("Not a filesystem's space: " + freeBytes + " free of " + totalBytes);
        }
    }

    /** @return the bytes in use */
    public long usedBytes() {
        return this.totalBytes - this.freeBytes;
    }
}
