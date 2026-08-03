package be.elevenways.hohenheim.server.process;

import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * THE owner of the per-node singletons every managed-process site type shares: the port
 * allocator, the socket allocator and the process monitor. Booted once by
 * {@code SiteTypes.boot()} when the {@code processes} role is on, and null on a node
 * where it is off.
 *
 * AIDEV-NOTE: these three used to be static fields on NodeSiteType that JavaSiteType and
 * CommandSiteType BORROWED -- "the Node site type happens to hold the process allocators"
 * is not an ownership statement any of the three site types could act on, and it made the
 * port allocator look like Node's when it is the node's. Nothing here is site-type
 * specific; a new managed-process site type asks this class, never a sibling.
 */
public final class ProcessInfrastructure {

    private static @Nullable PortAllocator portAllocator;
    private static @Nullable SocketAllocator socketAllocator;
    private static @Nullable ProcessMonitor processMonitor;

    private ProcessInfrastructure() {
    }

    /** One-time boot of the shared process-management singletons. */
    public static void init() {
        portAllocator = new PortAllocator();
        socketAllocator = new SocketAllocator();
        processMonitor = new ProcessMonitor();
        processMonitor.start();
    }

    /** Stop the monitor thread; the allocators hold no thread of their own. */
    public static void shutdown() {
        if (processMonitor != null) {
            processMonitor.stop();
        }
    }

    public static @Nullable PortAllocator portAllocator() {
        return portAllocator;
    }

    public static @Nullable SocketAllocator socketAllocator() {
        return socketAllocator;
    }

    public static @Nullable ProcessMonitor processMonitor() {
        return processMonitor;
    }
}
