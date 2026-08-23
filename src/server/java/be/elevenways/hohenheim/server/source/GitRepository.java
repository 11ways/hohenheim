package be.elevenways.hohenheim.server.source;

import be.elevenways.hohenheim.server.SystemUsers;
import be.elevenways.hohenheim.server.process.ProcessGroupSupport;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Thin wrapper around the git CLI.
 */
public class GitRepository {

    private static final long DEFAULT_TIMEOUT_MILLIS = TimeUnit.MINUTES.toMillis(5);
    private static final int OUTPUT_CAP = 1_000_000;
    private static final Pattern URI_USER_INFO = Pattern.compile(
        "(?i)([a-z][a-z0-9+.-]*://)[^\\s/@]+@");
    private static final Pattern URI_AUTHORITY = Pattern.compile(
        "(?i)^([a-z][a-z0-9+.-]*)://([^/?#]*)");

    private final String repositoryUrl;
    private final String branch;
    private final boolean shallow;
    private final boolean submodules;
    private final SystemUsers.@Nullable RunAsUser runAs;
    private final long timeoutMillis;

    /**
     * Per-operation credential environment (evaluated fresh so a minted short-lived
     * token is current), or null for credential-less operation. Values go into the git
     * process ENVIRONMENT only -- never onto the command line, never into the URL.
     */
    private volatile java.util.function.@Nullable Supplier<@Nullable Map<String, String>>
        credentialEnv;

    public GitRepository(String repositoryUrl, String branch, boolean shallow,
                          boolean submodules, SystemUsers.@Nullable RunAsUser runAs) {
        this(repositoryUrl, branch, shallow, submodules, runAs, DEFAULT_TIMEOUT_MILLIS);
    }

    GitRepository(String repositoryUrl, String branch, boolean shallow,
                  boolean submodules, SystemUsers.@Nullable RunAsUser runAs,
                  long timeoutMillis) {
        rejectEmbeddedCredentials(repositoryUrl);
        this.repositoryUrl = repositoryUrl;
        this.branch = branch != null && !branch.isEmpty() ? branch : "main";
        this.shallow = shallow;
        this.submodules = submodules;
        this.runAs = runAs;
        this.timeoutMillis = timeoutMillis;
    }

    /** Install the credential-environment supplier (provider-bound sites). */
    public void setCredentialEnv(
            java.util.function.@Nullable Supplier<@Nullable Map<String, String>> supplier) {
        this.credentialEnv = supplier;
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

        return execute(cmd, directory.getParentFile(), remoteEnv());
    }

    /**
     * Fetch and reset the repo to match the remote branch.
     */
    public GitResult fetchAndReset(File directory) throws InterruptedException {
        GitResult fetch = execute(List.of("git", "fetch", "origin", branch),
            directory, remoteEnv());
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
                directory, remoteEnv()
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

    /** The credential environment for remote-touching commands; null when unbound. */
    private @Nullable Map<String, String> remoteEnv() {
        var supplier = this.credentialEnv;
        return supplier != null ? supplier.get() : null;
    }

    GitResult execute(List<String> command, File workDir,
                      Map<String, String> extraEnv) throws InterruptedException {
        Map<String, String> environment = SystemUsers.safeEnvironment(
            runAs != null ? runAs.home() : System.getProperty("user.home"));

        try {
            if (extraEnv != null) {
                environment.putAll(extraEnv);
            }
            ProcessBuilder pb = SystemUsers.executionBuilder(runAs, environment, command, true);
            if (workDir != null && workDir.isDirectory()) {
                pb.directory(workDir);
            }
            pb.redirectErrorStream(true);

            Process process = pb.start();
            ProcessGroupSupport.OutputCapture output = ProcessGroupSupport.drain(
                process.getInputStream(), "git-output-" + process.pid(), OUTPUT_CAP);
            boolean finished;
            try {
                finished = process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                ProcessGroupSupport.terminate(process, runAs, ProcessGroupSupport.GRACEFUL_TERM_MS);
                output.finish();
                throw e;
            }
            if (!finished) {
                ProcessGroupSupport.TerminationResult termination =
                    ProcessGroupSupport.terminate(process, runAs, ProcessGroupSupport.GRACEFUL_TERM_MS);
                output.finish();
                String message = "Git command timed out after "
                    + TimeUnit.MILLISECONDS.toSeconds(timeoutMillis) + " seconds";
                if (!termination.successful()) {
                    message += "; process group survived cleanup (" + termination.finalGroupState() + ")";
                }
                return new GitResult(false, message, -1);
            }

            output.finish();
            int exitCode = process.exitValue();
            // Success output is sanitized too: a repo's own insteadOf/submodule
            // config can echo a credentialed URL even when the command succeeds.
            return new GitResult(exitCode == 0, sanitizeOutput(output.output()), exitCode);
        } catch (InterruptedException e) {
            throw e;
        } catch (Exception e) {
            return new GitResult(false,
                sanitizeOutput("Failed to execute git: " + e.getMessage()), -1);
        }
    }

    /**
     * THE question "does this repository URL carry a credential", with one home.
     *
     * AIDEV-NOTE: a bare {@code ssh://user@host} username is deliberately NOT a
     * credential -- it is how every ssh clone URL is spelled -- while anything else in
     * the user-info (a password, a token, an encoded colon) is. {@code UrlPolicy} in
     * zenit core answers the same question for an ordinary URL, but its
     * {@code allowUserInfo} is all-or-nothing and it additionally demands an http(s)
     * scheme and a parseable authority, which would refuse both that ssh spelling and
     * the scp-style {@code git@host:org/repo.git} this has always accepted.
     *
     * @return the offending user-info, or null when the URL embeds no credential
     */
    public static @Nullable String embeddedCredential(@Nullable String repositoryUrl) {
        if (repositoryUrl == null) {
            return null;
        }
        var matcher = URI_AUTHORITY.matcher(repositoryUrl);
        if (!matcher.find()) {
            return null;
        }
        String authority = matcher.group(2);
        int at = authority.lastIndexOf('@');
        if (at < 0) {
            return null;
        }
        String scheme = matcher.group(1);
        String userInfo = authority.substring(0, at);
        boolean sshUsername = (scheme.equalsIgnoreCase("ssh")
            || scheme.equalsIgnoreCase("git+ssh"))
            && !userInfo.contains(":")
            && !userInfo.toLowerCase(Locale.ROOT).contains("%3a");
        return sshUsername ? null : userInfo;
    }

    private static void rejectEmbeddedCredentials(String repositoryUrl) {
        if (embeddedCredential(repositoryUrl) != null) {
            throw new IllegalArgumentException(
                "Repository URL must not contain embedded user-info credentials");
        }
    }

    /** Redacts URI user-info credentials from git output (applied to success and failure alike). */
    static String sanitizeOutput(String output) {
        if (output == null) {
            return "";
        }
        return URI_USER_INFO.matcher(output).replaceAll("$1[REDACTED]@");
    }

    public record GitResult(boolean success, String output, int exitCode) {}
}
