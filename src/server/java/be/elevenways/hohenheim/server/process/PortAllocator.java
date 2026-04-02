package be.elevenways.hohenheim.server.process;

import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Allocates TCP ports for managed child processes.
 * Ports are probed (actual OS-level bind test) before assignment.
 */
public class PortAllocator {

    private static final int PORT_RANGE = 5000;

    private final ConcurrentHashMap<Integer, Integer> reservedPorts = new ConcurrentHashMap<>(); // port -> siteId

    /**
     * Allocate a free port for the given site.
     * @throws RuntimeException if no port is available
     */
    public int allocate(int siteId) {
        int firstPort = getFirstPort();
        int lastPort = firstPort + PORT_RANGE;

        for (int port = firstPort; port < lastPort; port++) {
            if (reservedPorts.containsKey(port)) continue;

            if (isPortFree(port)) {
                reservedPorts.put(port, siteId);
                return port;
            }
        }

        throw new RuntimeException("No free port in range " + firstPort + "-" + lastPort);
    }

    /**
     * Release a previously allocated port.
     */
    public void release(int port) {
        reservedPorts.remove(port);
    }

    /**
     * Check if a port is free by attempting to bind to it.
     */
    private boolean isPortFree(int port) {
        try (ServerSocket ss = new ServerSocket()) {
            ss.setReuseAddress(true);
            ss.bind(new InetSocketAddress("0.0.0.0", port));
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private int getFirstPort() {
        // Could be configurable via settings in the future
        return 4748;
    }

    public int getReservedCount() {
        return reservedPorts.size();
    }
}
