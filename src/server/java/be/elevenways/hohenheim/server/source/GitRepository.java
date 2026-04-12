package be.elevenways.hohenheim.server.source;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Thin wrapper around the git CLI.
 */
public class GitRepository {

    private final String repositoryUrl;
    private final String branch;
    private final boolean shallow;
    private final boolean submodules;
    private final int uid;

    public GitRepository(String repositoryUrl, String branch, boolean shallow,
                         boolean submodules, int uid) {
        this.repositoryUrl = repositoryUrl;
        this.branch = branch != null && !branch.isEmpty() ? branch : "main";
        this.shallow = shallow;
        this.submodules = submodules;
        this.uid = uid;
    }

    /**
     * Clone the repository into the given directory.
     */
    public GitResult clone(File directory) throws InterruptedException {
        List<String> cmd = new ArrayList<>();
        cmd.add("git");
        cmd.add("clone");
        cmd.add("--branch");
        cmd.add(branch);
        if (shallow) {
            cmd.add("--depth");
            cmd.add("1");
        }
        if (submodules) {
            cmd.add("--recurse-submodules");
        }
        cmd.add(repositoryUrl);
        cmd.add(directory.getAbsolutePath());

        return execute(cmd, directory.getParentFile(), null);
    }

    /**
     * Fetch and reset the repo to match the remote branch.
     */
    public GitResult fetchAndReset(File directory) throws InterruptedException {
        GitResult fetch = execute(List.of("git", "fetch", "origin", branch), directory, null);
        if (!fetch.success()) return fetch;

        return execute(
            List.of("git", "reset", "--hard", "origin/" + branch),
            directory, null
        );
    }

    /**
     * Get the current HEAD commit SHA.
     */
    public String getCurrentCommit(File directory) {
        try {
            GitResult result = execute(
                List.of("git", "rev-parse", "HEAD"),
                directory, null
            );
            return result.success() ? result.output().trim() : null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    /**
     * Check if the remote has new commits compared to local HEAD.
     */
    public boolean hasNewCommits(File directory) {
        try {
            GitResult lsRemote = execute(
                List.of("git", "ls-remote", "origin", branch),
                directory, null
            );
            if (!lsRemote.success()) return false;

            String remoteOutput = lsRemote.output().trim();
            if (remoteOutput.isEmpty()) return false;
            String remoteSha = remoteOutput.split("\\s+")[0];

            String localSha = getCurrentCommit(directory);
            if (localSha == null) return true;

            return !remoteSha.equals(localSha);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /**
     * Check if a directory contains a git repo with the expected remote URL.
     */
    public boolean isMatchingRepo(File directory) {
        if (!new File(directory, ".git").isDirectory()) return false;

        try {
            GitResult result = execute(
                List.of("git", "remote", "get-url", "origin"),
                directory, null
            );
            return result.success() && result.output().trim().equals(repositoryUrl);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private GitResult execute(List<String> command, File workDir,
                              Map<String, String> extraEnv) throws InterruptedException {
        List<String> fullCmd;
        if (uid > 0) {
            fullCmd = new ArrayList<>();
            fullCmd.add("sudo");
            fullCmd.add("-u");
            fullCmd.add("#" + uid);
            fullCmd.addAll(command);
        } else {
            fullCmd = command;
        }

        try {
            ProcessBuilder pb = new ProcessBuilder(fullCmd);
            if (workDir != null && workDir.isDirectory()) {
                pb.directory(workDir);
            }
            pb.redirectErrorStream(true);

            if (extraEnv != null) {
                pb.environment().putAll(extraEnv);
            }

            Process process = pb.start();
            StringBuilder output = new StringBuilder();
            try (var reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append("\n");
                }
            }

            boolean finished = process.waitFor(300, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return new GitResult(false, "Git command timed out after 300 seconds", -1);
            }

            int exitCode = process.exitValue();
            return new GitResult(exitCode == 0, output.toString(), exitCode);
        } catch (InterruptedException e) {
            throw e;
        } catch (Exception e) {
            return new GitResult(false, "Failed to execute git: " + e.getMessage(), -1);
        }
    }

    public record GitResult(boolean success, String output, int exitCode) {}
}
