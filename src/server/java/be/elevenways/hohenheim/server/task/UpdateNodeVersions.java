package be.elevenways.hohenheim.server.task;

import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.hohenheim.model.NodeVersionModel;
import be.elevenways.hohenheim.server.HohenheimDatabase;
import be.elevenways.protoblast.common.Blast;
import be.elevenways.zenit.common.orm.datasource.Row;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.stream.Stream;

/**
 * Discovers installed Node.js versions from common locations and reconciles them
 * with the node_versions table. Removed binaries get marked obsolete rather than
 * deleted so existing site references still resolve.
 */
public class UpdateNodeVersions implements Runnable {

    /** Record used while scanning the filesystem — flushed to the DB at the end. */
    private record ParsedVersion(String version, String path, String source) {}

    private static final String[] SYSTEM_PATHS = {
        "/usr/bin/node",
        "/usr/local/bin/node",
        "/usr/bin/nodejs"
    };

    @Override
    public void run() {
        List<ParsedVersion> parsed = discoverAll();

        NodeVersionModel model = Models.get(NodeVersionModel.class);
        Instant now = Instant.now();
        Set<String> seenPaths = new HashSet<>();

        for (ParsedVersion pv : parsed) {
            seenPaths.add(pv.path());
            Row existing = model.find().where(NodeVersionModel.PATH.eq(pv.path())).first();
            if (existing == null) {
                Row row = model.createEmptyRow();
                row.set(NodeVersionModel.VERSION, pv.version());
                row.set(NodeVersionModel.PATH, pv.path());
                row.set(NodeVersionModel.SOURCE, pv.source());
                row.set(NodeVersionModel.OBSOLETE, false);
                row.set(NodeVersionModel.LAST_SEEN_AT, now);
                model.save(row);
            } else {
                existing.set(NodeVersionModel.VERSION, pv.version());
                existing.set(NodeVersionModel.SOURCE, pv.source());
                existing.set(NodeVersionModel.OBSOLETE, false);
                existing.set(NodeVersionModel.LAST_SEEN_AT, now);
                model.save(existing);
            }
        }

        // Mark any previously-known versions that weren't seen this run as obsolete.
        for (Row row : model.find().where(NodeVersionModel.OBSOLETE.eq(false)).all()) {
            String path = row.get(NodeVersionModel.PATH);
            if (!seenPaths.contains(path)) {
                row.set(NodeVersionModel.OBSOLETE, true);
                model.save(row);
            }
        }

        Blast.log("TASK: UpdateNodeVersions reconciled", parsed.size(), "versions");
    }

    private List<ParsedVersion> discoverAll() {
        List<ParsedVersion> versions = new ArrayList<>();
        Set<String> dedup = new HashSet<>();

        for (String path : SYSTEM_PATHS) {
            if (new File(path).canExecute() && dedup.add(path)) {
                String version = queryVersion(path);
                if (version != null) {
                    versions.add(new ParsedVersion(version, path, "system"));
                }
            }
        }

        discoverFromDirectory(Path.of("/usr/local/n/versions/node"), "n", versions, dedup);

        String home = System.getProperty("user.home");
        if (home != null) {
            discoverFromDirectory(Path.of(home, ".nvm", "versions", "node"), "nvm", versions, dedup);
            discoverFromDirectory(Path.of(home, ".local", "share", "fnm", "node-versions"), "fnm", versions, dedup);
        }

        return versions;
    }

    private void discoverFromDirectory(Path dir, String source,
                                       List<ParsedVersion> versions, Set<String> dedup) {
        if (!Files.isDirectory(dir)) return;
        try (Stream<Path> entries = Files.list(dir)) {
            entries.filter(Files::isDirectory).forEach(versionDir -> {
                Path nodeBin = versionDir.resolve("bin").resolve("node");
                if (!Files.isExecutable(nodeBin)) return;
                String pathStr = nodeBin.toString();
                if (!dedup.add(pathStr)) return;
                String version = versionDir.getFileName().toString();
                if (version.startsWith("v")) version = version.substring(1);
                versions.add(new ParsedVersion(version, pathStr, source));
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
}
