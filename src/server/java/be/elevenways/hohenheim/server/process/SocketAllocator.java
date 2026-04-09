package be.elevenways.hohenheim.server.process;

import java.io.File;
import java.nio.file.Path;

/**
 * Allocates Unix socket paths for managed child processes.
 */
public class SocketAllocator {

    private static final String SOCKET_DIR = "/tmp/hohenheim";

    public SocketAllocator() {
        new File(SOCKET_DIR).mkdirs();
    }

    /**
     * Allocate a socket path for the given site and process.
     */
    public String allocate(int siteId, long pid) {
        return Path.of(SOCKET_DIR, "site-" + siteId + "-" + pid + ".sock").toString();
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
