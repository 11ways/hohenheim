package be.elevenways.hohenheim.server.task;

import be.elevenways.protoblast.common.Blast;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Stream;

/**
 * Discovers installed Node.js versions from common locations.
 * Checks: system paths, n version manager, nvm, and fnm.
 */
public class UpdateNodeVersions implements Runnable {

    public record NodeVersion(String version, String path) {}

    private static volatile List<NodeVersion> nodeVersions = List.of();

    private static final String[] SYSTEM_PATHS = {
        "/usr/bin/node",
        "/usr/local/bin/node",
        "/usr/bin/nodejs"
    };

    @Override
    public void run() {
        List<NodeVersion> versions = new ArrayList<>();

        // Check system paths
        for (String path : SYSTEM_PATHS) {
            if (new File(path).canExecute()) {
                String version = queryVersion(path);
                if (version != null) {
                    versions.add(new NodeVersion(version, path));
                }
            }
        }

        // Check n version manager (~/.n/versions/node/*)
        Path nVersionsDir = Path.of("/usr/local/n/versions/node");
        discoverFromDirectory(nVersionsDir, versions);

        // Check nvm (~/.nvm/versions/node/*)
        String home = System.getProperty("user.home");
        if (home != null) {
            discoverFromDirectory(Path.of(home, ".nvm", "versions", "node"), versions);
            discoverFromDirectory(Path.of(home, ".local", "share", "fnm", "node-versions"), versions);
        }

        // Deduplicate by path
        Set<String> seen = new HashSet<>();
        versions.removeIf(v -> !seen.add(v.path()));

        nodeVersions = List.copyOf(versions);
        Blast.log("TASK: UpdateNodeVersions found", versions.size(), "versions");
    }

    private void discoverFromDirectory(Path dir, List<NodeVersion> versions) {
        if (!Files.isDirectory(dir)) return;

        try (Stream<Path> entries = Files.list(dir)) {
            entries.filter(Files::isDirectory).forEach(versionDir -> {
                Path nodeBin = versionDir.resolve("bin").resolve("node");
                if (Files.isExecutable(nodeBin)) {
                    String version = versionDir.getFileName().toString();
                    // Strip leading 'v' if present
                    if (version.startsWith("v")) version = version.substring(1);
                    versions.add(new NodeVersion(version, nodeBin.toString()));
                }
            });
        } catch (Exception e) {
            // Skip directories we can't read
        }
    }

    private String queryVersion(String nodePath) {
        try {
            Process proc = new ProcessBuilder(nodePath, "--version")
                .redirectErrorStream(true)
                .start();
            String output = new String(proc.getInputStream().readAllBytes()).trim();
            if (proc.waitFor() == 0 && output.startsWith("v")) {
                return output.substring(1);
            }
        } catch (Exception e) {
            // Skip if we can't query
        }
        return null;
    }

    public static List<NodeVersion> getNodeVersions() {
        return nodeVersions;
    }
}
