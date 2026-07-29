package be.elevenways.hohenheim.server.process;

import be.elevenways.protoblast.common.Blast;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;

/**
 * Lazily extracts the Node IPC fork-wrapper (hohenheim-child-wrapper.js) from
 * the server JAR classpath to a stable temp path, so Node child processes can
 * be launched as:
 *     node /tmp/.../hohenheim-child-wrapper.js --hh-exec <site-script> [...]
 *
 * The wrapper fork()s the real script (giving it Node's native IPC channel)
 * and bridges HOHENHEIM_IPC_PORT messages into/out of that fork so legacy
 * alchemy installs get janeway_propose_geometry without having to upgrade.
 */
public final class ChildWrapper {

    private static final String RESOURCE = "hohenheim-child-wrapper.js";
    private static volatile Path cachedPath;

    private ChildWrapper() {}

    /**
     * Extract the wrapper to a stable temp file if not already present and
     * return its path. The file survives for the JVM lifetime; a dedicated
     * file each run avoids cross-JVM races in /tmp.
     */
    public static synchronized Path path() {
        if (cachedPath != null && Files.exists(cachedPath)) return cachedPath;

        try (InputStream in = ChildWrapper.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IOException("classpath resource not found: " + RESOURCE);
            }
            Path dir = Files.createTempDirectory("hohenheim-wrapper-");
            Path file = dir.resolve(RESOURCE);
            Files.copy(in, file, StandardCopyOption.REPLACE_EXISTING);
            // createTempDirectory yields 0700 owned by the daemon, which a
            // privilege-dropped (sudo -u) child cannot traverse; the wrapper holds
            // no secrets (the token rides the environment), so world-readable is fine.
            Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwxr-xr-x"));
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-r--r--"));
            file.toFile().deleteOnExit();
            dir.toFile().deleteOnExit();
            cachedPath = file;
            return file;
        } catch (IOException e) {
            Blast.log("PROCESS: failed to extract child wrapper:", e.getMessage());
            throw new RuntimeException(e);
        }
    }
}
