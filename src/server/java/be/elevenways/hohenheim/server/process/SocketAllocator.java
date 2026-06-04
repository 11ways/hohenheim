package be.elevenways.hohenheim.server.process;

import java.io.File;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Allocates Unix socket paths for managed child processes.
 */
public class SocketAllocator {

    private static final String SOCKET_DIR = "/tmp/hohenheim";
    private static final AtomicLong SEQUENCE = new AtomicLong();

    public SocketAllocator() {
        new File(SOCKET_DIR).mkdirs();
    }

    /**
     * Allocate a unique socket path for the given site. Counter-based (not pid-based) so the path
     * is known before {@code ProcessBuilder.start()} and can be passed to the child via env.
     */
    public String allocate(int siteId) {
        return Path.of(SOCKET_DIR, "site-" + siteId + "-" + SEQUENCE.incrementAndGet() + ".sock").toString();
    }

    /**
     * Release (delete) a socket file.
     */
    public void release(String socketPath) {
        if (socketPath != null) {
            new File(socketPath).delete();
        }
    }
}
