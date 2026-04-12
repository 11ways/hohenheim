package be.elevenways.hohenheim.server.source;

import be.elevenways.hohenheim.server.sitetype.SiteHealth;
import be.elevenways.hohenheim.server.sitetype.SiteRequestHandler;
import be.elevenways.hohenheim.server.sitetype.SiteTypeHandler;
import be.elevenways.protoblast.common.Blast;
import be.elevenways.zenit.common.orm.datasource.Row;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Orchestrates a single deploy attempt using the dual-slot strategy.
 */
public class GitDeployment {

    private final int siteId;
    private final Row site;
    private final SiteTypeHandler typeHandler;
    private final Map<String, Object> typeSettings;
    private final Map<String, Object> sourceSettings;
    private final GitRepository gitRepo;
    private final File siteDir;

    public GitDeployment(int siteId, Row site, SiteTypeHandler typeHandler,
                         Map<String, Object> typeSettings, Map<String, Object> sourceSettings,
                         GitRepository gitRepo, File siteDir) {
        this.siteId = siteId;
        this.site = site;
        this.typeHandler = typeHandler;
        this.typeSettings = typeSettings;
        this.sourceSettings = sourceSettings;
        this.gitRepo = gitRepo;
        this.siteDir = siteDir;
    }

    /**
     * Execute the deploy. Returns the new inner handler on success, null on failure.
     * The caller is responsible for swapping the handler and managing the old one.
     */
    public DeployResult execute(SiteRequestHandler oldHandler) {
        try {
            // Determine target slot at execution time
            String activeSlot = readActiveSlot();
            String targetSlot = "a".equals(activeSlot) ? "b" : "a";
            File targetDir = new File(siteDir, targetSlot);

            Blast.log("GIT: site", siteId, "deploying to slot", targetSlot,
                "(active:", activeSlot + ")");

            // Step 1: Prepare standby slot
            if (Thread.interrupted()) throw new InterruptedException();
            if (!prepareSlot(targetDir)) {
                return DeployResult.failure("Failed to prepare slot " + targetSlot);
            }

            // Step 2: Record commit SHA
            String commitSha = gitRepo.getCurrentCommit(targetDir);
            Blast.log("GIT: site", siteId, "at commit", commitSha);

            // Step 3: Run build command
            if (Thread.interrupted()) throw new InterruptedException();
            String buildCommand = (String) sourceSettings.get("build_command");
            if (buildCommand != null && !buildCommand.isEmpty()) {
                if (!runBuild(targetDir, buildCommand)) {
                    return DeployResult.failure("Build command failed");
                }
            }

            // Step 4: Create new inner handler with adjusted paths
            if (Thread.interrupted()) throw new InterruptedException();
            Map<String, Object> adjustedSettings = adjustPaths(targetDir);
            SiteRequestHandler newHandler = typeHandler.createHandler(site, adjustedSettings);

            // Step 4b: Wait for process modes to become healthy before swapping
            if (newHandler.getHealth() != SiteHealth.UP) {
                Blast.log("GIT: site", siteId, "waiting for new handler to become healthy");
                long deadline = System.currentTimeMillis() + 60_000; // 60s max wait
                while (newHandler.getHealth() != SiteHealth.UP) {
                    if (Thread.interrupted()) {
                        newHandler.destroy();
                        throw new InterruptedException();
                    }
                    if (System.currentTimeMillis() > deadline) {
                        Blast.log("GIT: site", siteId, "new handler did not become healthy in 60s");
                        newHandler.destroy();
                        return DeployResult.failure("New handler did not become healthy within 60 seconds");
                    }
                    Thread.sleep(500);
                }
            }

            // Step 5: Flip symlink atomically BEFORE returning
            flipSymlink(targetSlot);

            return DeployResult.success(newHandler, commitSha, targetSlot);

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return DeployResult.failure("Deploy cancelled");
        } catch (Exception e) {
            Blast.log("GIT: deploy error for site", siteId, "-", e.getMessage());
            return DeployResult.failure(e.getMessage());
        }
    }

    /**
     * Reconnect: create a handler from the existing live slot without cloning or building.
     */
    public SiteRequestHandler reconnect() {
        String activeSlot = readActiveSlot();
        if (activeSlot == null) return null;

        File activeDir = new File(siteDir, activeSlot);
        if (!activeDir.isDirectory() || !gitRepo.isMatchingRepo(activeDir)) {
            return null;
        }

        Map<String, Object> adjustedSettings = adjustPaths(activeDir);
        return typeHandler.createHandler(site, adjustedSettings);
    }

    // -----------------------------------------------------------------------

    private boolean prepareSlot(File targetDir) throws InterruptedException {
        if (targetDir.isDirectory() && gitRepo.isMatchingRepo(targetDir)) {
            // Existing matching repo: fetch + reset
            Blast.log("GIT: site", siteId, "updating existing slot", targetDir.getName());
            GitRepository.GitResult result = gitRepo.fetchAndReset(targetDir);
            if (!result.success()) {
                Blast.log("GIT: fetch+reset failed, trying fresh clone:", result.output());
                deleteDirectory(targetDir);
                return freshClone(targetDir);
            }
            return true;
        } else {
            // Fresh clone
            if (targetDir.exists()) deleteDirectory(targetDir);
            return freshClone(targetDir);
        }
    }

    private boolean freshClone(File targetDir) throws InterruptedException {
        targetDir.getParentFile().mkdirs();
        GitRepository.GitResult result = gitRepo.clone(targetDir);
        if (!result.success()) {
            Blast.log("GIT: clone failed for site", siteId, "-", result.output());
            return false;
        }
        return true;
    }

    private boolean runBuild(File targetDir, String buildCommand) throws InterruptedException {
        String buildDir = (String) sourceSettings.get("build_directory");
        File workDir = targetDir;
        if (buildDir != null && !buildDir.isEmpty()) {
            workDir = new File(targetDir, buildDir);
        }

        Object timeoutObj = sourceSettings.get("build_timeout");
        int timeoutSec = timeoutObj instanceof Integer t && t > 0 ? t : 600;

        Blast.log("GIT: site", siteId, "running build:", buildCommand);

        try {
            ProcessBuilder pb = new ProcessBuilder("sh", "-c", buildCommand);
            pb.directory(workDir);
            pb.redirectErrorStream(true);

            // Merge type environment variables + build-only environment variables
            Map<String, String> env = pb.environment();
            mergeEnvVars(env, typeSettings.get("environment_variables"));
            mergeEnvVars(env, sourceSettings.get("build_environment_variables"));

            Process process = pb.start();

            // Capture output (capped)
            StringBuilder output = new StringBuilder();
            int lineCount = 0;
            try (var reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (lineCount < 10_000) {
                        output.append(line).append("\n");
                    }
                    lineCount++;
                }
            }

            boolean finished = process.waitFor(timeoutSec, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                Blast.log("GIT: build timed out after", timeoutSec, "seconds for site", siteId);
                return false;
            }

            int exitCode = process.exitValue();
            if (exitCode != 0) {
                Blast.log("GIT: build failed (exit", exitCode + ") for site", siteId);
                // Log last 50 lines of output
                String[] lines = output.toString().split("\n");
                int start = Math.max(0, lines.length - 50);
                for (int i = start; i < lines.length; i++) {
                    Blast.log("  BUILD:", lines[i]);
                }
                return false;
            }

            Blast.log("GIT: build succeeded for site", siteId);
            return true;

        } catch (InterruptedException e) {
            throw e;
        } catch (Exception e) {
            Blast.log("GIT: build error for site", siteId, "-", e.getMessage());
            return false;
        }
    }

    private void mergeEnvVars(Map<String, String> env, Object envVarsObj) {
        if (!(envVarsObj instanceof List<?> list)) return;
        for (Object item : list) {
            if (item instanceof Map<?, ?> map) {
                Object nameObj = map.get("name");
                Object valueObj = map.get("value");
                if (nameObj instanceof String name && !name.isEmpty()) {
                    env.put(name, valueObj instanceof String v ? v : "");
                }
            }
        }
    }

    /**
     * Adjust type settings by resolving relative paths against the target slot.
     */
    private Map<String, Object> adjustPaths(File slotDir) {
        Map<String, Object> adjusted = new HashMap<>(typeSettings);

        String buildDir = (String) sourceSettings.get("build_directory");
        File baseDir = slotDir;
        if (buildDir != null && !buildDir.isEmpty()) {
            baseDir = new File(slotDir, buildDir);
        }

        // Resolve known path fields
        resolvePathField(adjusted, "script", baseDir);
        resolvePathField(adjusted, "root_path", baseDir);

        // For CommandSiteType: set working_directory to the base dir if not explicitly set
        String workDirValue = (String) adjusted.get("working_directory");
        if (workDirValue == null || workDirValue.isEmpty()) {
            adjusted.put("working_directory", baseDir.getAbsolutePath());
        } else {
            adjusted.put("working_directory", new File(baseDir, workDirValue).getAbsolutePath());
        }

        return adjusted;
    }

    private void resolvePathField(Map<String, Object> settings, String fieldName, File baseDir) {
        Object value = settings.get(fieldName);
        if (value instanceof String path && !path.isEmpty() && !path.startsWith("/")) {
            settings.put(fieldName, new File(baseDir, path).getAbsolutePath());
        }
    }

    private String readActiveSlot() {
        Path symlink = siteDir.toPath().resolve("active");
        try {
            if (Files.isSymbolicLink(symlink)) {
                Path target = Files.readSymbolicLink(symlink);
                String name = target.getFileName().toString();
                if ("a".equals(name) || "b".equals(name)) return name;
            }
        } catch (Exception ignored) {}
        return null;
    }

    private void flipSymlink(String targetSlot) {
        Path activePath = siteDir.toPath().resolve("active");
        try {
            // Atomic symlink replacement: create temp, then rename over
            Path temp = siteDir.toPath().resolve("active.tmp." + System.nanoTime());
            Files.createSymbolicLink(temp, Path.of(targetSlot));
            Files.move(temp, activePath, StandardCopyOption.ATOMIC_MOVE);
        } catch (Exception e) {
            Blast.log("GIT: failed to flip symlink for site", siteId, "-", e.getMessage());
            // Fallback: delete + create (non-atomic)
            try {
                Files.deleteIfExists(activePath);
                Files.createSymbolicLink(activePath, Path.of(targetSlot));
            } catch (Exception e2) {
                Blast.log("GIT: symlink fallback also failed for site", siteId);
            }
        }
    }

    private static void deleteDirectory(File dir) {
        if (dir == null || !dir.exists()) return;
        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) {
                if (f.isDirectory()) deleteDirectory(f);
                else f.delete();
            }
        }
        dir.delete();
    }

    // -----------------------------------------------------------------------

    public record DeployResult(boolean success, SiteRequestHandler handler,
                               String commitSha, String slot, String error) {

        static DeployResult success(SiteRequestHandler handler, String commitSha, String slot) {
            return new DeployResult(true, handler, commitSha, slot, null);
        }

        static DeployResult failure(String error) {
            return new DeployResult(false, null, null, null, error);
        }
    }
}
