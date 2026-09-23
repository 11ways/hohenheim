package be.elevenways.hohenheim.server.source;

import be.elevenways.hohenheim.server.SystemUsers;
import be.elevenways.hohenheim.server.process.ProcessGroupSupport;

import be.elevenways.hohenheim.source.GitRefNames;

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
 *
 * AIDEV-NOTE: the repository URL and the branch are somebody else's text (a tenant's
 * setting, a forge payload), so the constructor refuses anything git could read as an
 * option or as a local source the caller may not reach, every positional rides behind
 * {@code --}, and a REMOTE-only repository runs with {@code GIT_ALLOW_PROTOCOL} plus
 * {@code protocol.file.allow=never} so not even a submodule can name a controller path.
 * The {@code -c} is the top-level one, which git never persists into the new repository.
 */
public class GitRepository {

    private static final long DEFAULT_TIMEOUT_MILLIS = TimeUnit.MINUTES.toMillis(5);
    private static final int OUTPUT_CAP = 1_000_000;
    private static final Pattern URI_USER_INFO = Pattern.compile(
        "(?i)([a-z][a-z0-9+.-]*://)[^\\s/@]+@");
    private static final Pattern URI_AUTHORITY = Pattern.compile(
        "(?i)^([a-z][a-z0-9+.-]*)://([^/?#]*)");

    /** The transports {@code git clone} speaks over an authority: scheme://[user-info@]host[/path]. */
    private static final Pattern CLONE_URL_SCHEME = Pattern.compile(
        "(?i)^(?:https?|ssh|git\\+ssh|git|ftps?)://[^\\s/?#]+(?:/\\S*)?$");

    /** The explicit spelling of a local clone: {@code file:///srv/repo.git}. */
    private static final Pattern CLONE_URL_FILE = Pattern.compile(
        "(?i)^file://(?:localhost)?/\\S*$");

    private static final Pattern CLONE_URL_WHITESPACE = Pattern.compile("\\s");

    /** scp-style {@code [user@]host:path}, the spelling every ssh remote is pasted as. */
    private static final Pattern CLONE_URL_SCP = Pattern.compile(
        "^(?<user>[^\\s/:@]+@)?(?<host>[^\\s/:@]+):(?!//)\\S+$");

    /** The authority of a scheme URL: neither its user-info nor its host may read as an option. */
    private static final Pattern CLONE_URL_AUTHORITY = Pattern.compile(
        "(?i)^[a-z][a-z0-9+.-]*://(?<authority>[^\\s/?#]*)");

    /** git's {@code <transport>::<address>} remote-helper spelling ({@code ext::} runs a command). */
    private static final Pattern CLONE_URL_HELPER = Pattern.compile("^[A-Za-z0-9+.-]*::");

    /**
     * The protocols a remote-only clone may speak, in {@code GIT_ALLOW_PROTOCOL} spelling:
     * exactly the schemes {@link #CLONE_URL_SCHEME} accepts ({@code git+ssh} is ssh).
     */
    private static final String REMOTE_PROTOCOLS = "https:http:ssh:git:ftps:ftp";

    private final String repositoryUrl;
    private final String branch;
    private final boolean shallow;
    private final boolean submodules;
    private final SystemUsers.@Nullable RunAsUser runAs;
    private final long timeoutMillis;

    /** Whether the source may be a controller path; false confines git to remote protocols. */
    private final boolean localSourcesAllowed;

    /**
     * Per-operation credential environment (evaluated fresh so a minted short-lived
     * token is current), or null for credential-less operation. Values go into the git
     * process ENVIRONMENT only -- never onto the command line, never into the URL.
     */
    private volatile java.util.function.@Nullable Supplier<@Nullable Map<String, String>>
        credentialEnv;

    /** A REMOTE-only repository: local paths and {@code file://} are refused. */
    public GitRepository(String repositoryUrl, String branch, boolean shallow,
                          boolean submodules, SystemUsers.@Nullable RunAsUser runAs) {
        this(repositoryUrl, branch, shallow, submodules, runAs, false, DEFAULT_TIMEOUT_MILLIS);
    }

    /**
     * @param localSourcesAllowed whether the URL may name a controller path, which only an
     *                            operator-owned source may ({@link SourceOwnership})
     * @throws IllegalArgumentException for a credentialed or unusable URL, or an unsafe branch
     */
    public GitRepository(String repositoryUrl, String branch, boolean shallow,
                         boolean submodules, SystemUsers.@Nullable RunAsUser runAs,
                         boolean localSourcesAllowed) {
        this(repositoryUrl, branch, shallow, submodules, runAs, localSourcesAllowed,
            DEFAULT_TIMEOUT_MILLIS);
    }

    GitRepository(String repositoryUrl, String branch, boolean shallow,
                  boolean submodules, SystemUsers.@Nullable RunAsUser runAs,
                  boolean localSourcesAllowed, long timeoutMillis) {
        rejectEmbeddedCredentials(repositoryUrl);
        if (!isRemoteCloneUrl(repositoryUrl)
                && !(localSourcesAllowed && isLocalCloneUrl(repositoryUrl))) {
            throw new IllegalArgumentException(
                "Repository URL is not a " + (localSourcesAllowed ? "" : "remote ")
                    + "source git may clone");
        }
        String ref = branch != null && !branch.isEmpty() ? branch : "main";
        if (!GitRefNames.isValid(ref)) {
            throw new IllegalArgumentException("Branch is not a valid git ref name");
        }
        this.repositoryUrl = repositoryUrl;
        this.branch = ref;
        this.shallow = shallow;
        this.submodules = submodules;
        this.runAs = runAs;
        this.localSourcesAllowed = localSourcesAllowed;
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
        List<String> cmd = git("clone", "--branch", branch);
        if (shallow) {
            cmd.add("--depth");
            cmd.add("1");
        }
        if (submodules) {
            cmd.add("--recurse-submodules");
        }
        cmd.add("--");
        cmd.add(repositoryUrl);
        cmd.add(directory.getAbsolutePath());

        return execute(cmd, directory.getParentFile(), remoteEnv());
    }

    /**
     * Fetch and reset the repo to match the remote branch.
     *
     * AIDEV-NOTE: {@code reset} takes no {@code --} here on purpose: after the commit it
     * would start a PATHSPEC. The branch is validated not to start with {@code -}, so
     * {@code origin/<branch>} can never read as an option.
     */
    public GitResult fetchAndReset(File directory) throws InterruptedException {
        GitResult fetch = execute(git("fetch", "--", "origin", branch),
            directory, remoteEnv());
        if (!fetch.success()) return fetch;

        return execute(git("reset", "--hard", "origin/" + branch), directory, null);
    }

    /**
     * Get the current HEAD commit SHA.
     */
    public String getCurrentCommit(File directory) {
        try {
            GitResult result = execute(git("rev-parse", "HEAD"), directory, null);
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
            GitResult lsRemote = execute(git("ls-remote", "--", "origin", branch),
                directory, remoteEnv());
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
            GitResult result = execute(git("remote", "get-url", "origin"), directory, null);
            return result.success() && result.output().trim().equals(repositoryUrl);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /**
     * A git argv: the protocol confinement first for a remote-only repository, then the
     * subcommand and its arguments.
     */
    private List<String> git(String... arguments) {
        List<String> command = new ArrayList<>();
        command.add("git");
        if (!localSourcesAllowed) {
            command.add("-c");
            command.add("protocol.file.allow=never");
        }
        command.addAll(List.of(arguments));
        return command;
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
            if (!localSourcesAllowed) {
                // Set AFTER the caller's environment so nothing can widen it.
                environment.put("GIT_ALLOW_PROTOCOL", REMOTE_PROTOCOLS);
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

    /**
     * THE git-clone-URL grammar, with one home: exactly the forms {@link #clone} can hand
     * to git and nothing more.
     *
     * AIDEV-NOTE: NOT {@code UrlPolicy}, for the same reasons {@link #embeddedCredential}
     * spells out -- a clone URL is routinely {@code ssh://}, {@code git://} or the
     * scp-style {@code git@host:org/repo.git}, none of which an http(s) URL validator
     * accepts. A RELATIVE path is refused deliberately: git would resolve it against the
     * checkout's parent directory, an internal path nobody typing a repository URL knows
     * about, so {@code not-a-url} can only be a typo -- while an ABSOLUTE path is a
     * genuine local clone source and stays accepted by the GRAMMAR. Whether a given record
     * may USE a local source is a separate question ({@link #isRemoteCloneUrl},
     * {@link SourceOwnership#localSourcesAllowed}). An scp-style target must carry a
     * {@code user@} or a dotted host, which is what tells {@code git@host:org/repo.git}
     * apart from a mistyped {@code http:/x} -- git reads THAT as the host "http" too, and
     * then spends the deploy resolving it.
     *
     * @return whether git could clone this, on grammar alone (it says nothing about the
     *         host existing, nor about {@link #embeddedCredential})
     */
    public static boolean isSupportedCloneUrl(@Nullable String repositoryUrl) {
        return isRemoteCloneUrl(repositoryUrl) || isLocalCloneUrl(repositoryUrl);
    }

    /**
     * Whether the URL names a REMOTE repository: a scheme URL over an authority, or the
     * scp-style spelling -- never a controller path, never a remote helper
     * ({@code ext::}), and never anything git could read as an option.
     */
    public static boolean isRemoteCloneUrl(@Nullable String repositoryUrl) {

        String url = cleaned(repositoryUrl);

        if (url == null || CLONE_URL_HELPER.matcher(url).find()) {
            return false;
        }

        if (CLONE_URL_SCHEME.matcher(url).matches()) {
            var authority = CLONE_URL_AUTHORITY.matcher(url);
            if (!authority.find()) {
                return false;
            }
            String text = authority.group("authority");
            String host = text.substring(text.lastIndexOf('@') + 1);
            return !text.startsWith("-") && !host.startsWith("-");
        }

        var scp = CLONE_URL_SCP.matcher(url);

        if (scp.matches()) {
            String user = scp.group("user");
            String host = scp.group("host");
            if (host.startsWith("-") || (user != null && user.startsWith("-"))) {
                return false;
            }
            return user != null || host.contains(".");
        }

        return false;
    }

    /** Whether the URL names a path on the controller: {@code file://} or an absolute path. */
    public static boolean isLocalCloneUrl(@Nullable String repositoryUrl) {
        String url = cleaned(repositoryUrl);
        return url != null && (CLONE_URL_FILE.matcher(url).matches() || url.startsWith("/"));
    }

    /**
     * The value trimmed, or null when it can never be a clone URL: empty, carrying whitespace
     * INSIDE it (git takes the URL as one argv entry, so a space is only ever a typo), or
     * starting with {@code -}.
     */
    private static @Nullable String cleaned(@Nullable String repositoryUrl) {
        if (repositoryUrl == null) {
            return null;
        }
        String url = repositoryUrl.trim();
        if (url.isEmpty() || url.startsWith("-") || CLONE_URL_WHITESPACE.matcher(url).find()) {
            return null;
        }
        return url;
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
