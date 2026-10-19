package be.elevenways.hohenheim.server.source;

import be.elevenways.hohenheim.server.DataDirectory;
import be.elevenways.hohenheim.server.host.HostShell;
import be.elevenways.hohenheim.server.util.FileTrees;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Map;

/**
 * The client of an SSH key connection ({@link SshProviderKind}): git itself is the API. The repository list is the one
 * repository the connection names, proven reachable by listing its branches with the key; a clone or fetch reads the
 * key from a file that exists for that one command.
 *
 * AIDEV-NOTE: the host key is trusted on first use and pinned per connection
 * ({@code <data>/git-ssh/known_hosts/<id>}): a host whose key changes afterwards is refused by ssh, never re-trusted
 * silently. The key file is written 0600 under a 0700 directory and removed once git ran ({@link GitAccess#close}).
 *
 * @author Jelle De Loecker
 * @since  0.10.0
 */
final class SshProviderClient implements GitProviderClient {

    /** A branch name {@link GitRepository} accepts; listing the remote reads no branch. */
    private static final String ANY_BRANCH = "main";

    private final int providerId;
    private final @NonNull String repository;
    private final @Nullable String privateKey;

    SshProviderClient(int providerId, @NonNull String repository, @Nullable String privateKey) {
        this.providerId = providerId;
        this.repository = repository;
        this.privateKey = privateKey;
    }

    /** @throws IOException naming git's own words when the key does not reach the repository */
    @Override
    public @NonNull List<RepoRef> listRepositories() throws IOException {
        if (this.repository.isEmpty()) {
            throw new IOException("This connection names no repository");
        }
        List<String> branches = this.listBranches(this.repository);
        String defaultBranch = branches.contains(ANY_BRANCH) ? ANY_BRANCH : branches.isEmpty() ? null : branches.get(0);
        return List.of(new RepoRef(this.repository, this.repository, defaultBranch));
    }

    @Override
    public @NonNull List<String> listBranches(@NonNull String repository) throws IOException {
        GitRepository remote = new GitRepository(this.cloneUrl(repository), ANY_BRANCH, true, false, null);
        remote.setAccess(this::keyAccess);
        try {
            return remote.remoteBranches();
        } catch (UncheckedIOException unusable) {
            throw unusable.getCause();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while listing the repository's branches", interrupted);
        }
    }

    /** The repository is its own clone URL: an SSH connection has no host to derive one from. */
    @Override
    public @NonNull String cloneUrl(@NonNull String repository) {
        return repository.trim();
    }

    /** @throws IOException always: an SSH connection clones with its key ({@link #cloneAccess}), never a token */
    @Override
    public @NonNull Credential cloneCredential(@NonNull String repository) throws IOException {
        throw new IOException("An SSH key connection clones with its key, not a token");
    }

    @Override
    public @NonNull GitAccess cloneAccess(@NonNull String repository) throws IOException {
        return KeyAccess.open(this.providerId, this.privateKey);
    }

    /** A bare git host has no deployment status to report onto. */
    @Override
    public void reportStatus(@NonNull String repository, @NonNull String commitSha, @NonNull StatusState state,
                             @NonNull String context, @NonNull String description, @Nullable String targetUrl) {
    }

    /** The key access for the supplier lane, its refusal carried unchecked to {@link #listBranches}. */
    private @NonNull GitAccess keyAccess() {
        try {
            return this.cloneAccess(this.repository);
        } catch (IOException unusable) {
            throw new UncheckedIOException(unusable);
        }
    }

    /** The key as a file for one command, and the pinned host keys beside it. */
    static final class KeyAccess implements GitAccess {

        private final @NonNull Path directory;
        private final @NonNull Map<String, String> environment;

        private KeyAccess(@NonNull Path directory, @NonNull Map<String, String> environment) {
            this.directory = directory;
            this.environment = environment;
        }

        /**
         * Writes the key for one command.
         *
         * @throws IOException when the connection holds no key, the text is no private key, or it cannot be written
         */
        static @NonNull KeyAccess open(int providerId, @Nullable String privateKey) throws IOException {
            String key = normalized(privateKey);
            Path root = DataDirectory.resolve("git-ssh");
            Path keys = privateDirectory(root.resolve("keys"));
            Path pinned = privateDirectory(root.resolve("known_hosts")).resolve(String.valueOf(providerId));
            Path directory = Files.createTempDirectory(keys, "key-",
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
            Path file = Files.createFile(directory.resolve("id"),
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
            Files.writeString(file, key, StandardCharsets.UTF_8);
            // LogLevel=ERROR keeps ssh's own notices (a host key pinned on first use) out of the reason a refusal shows.
            String command = "ssh -i " + HostShell.quote(file.toAbsolutePath().toString())
                + " -o IdentitiesOnly=yes -o IdentityAgent=none"
                + " -o BatchMode=yes -o ConnectTimeout=20 -o StrictHostKeyChecking=accept-new -o LogLevel=ERROR"
                + " -o UserKnownHostsFile=" + HostShell.quote(pinned.toAbsolutePath().toString());
            return new KeyAccess(directory, Map.of("GIT_SSH_COMMAND", command, "GIT_TERMINAL_PROMPT", "0"));
        }

        @Override
        public @NonNull Map<String, String> environment() {
            return this.environment;
        }

        /** The key names a file on the controller, which another process namespace cannot read. */
        @Override
        public boolean portable() {
            return false;
        }

        @Override
        public void close() {
            FileTrees.delete(this.directory);
        }

        /**
         * The pasted key as ssh reads it: line ends unified and a final newline, which OpenSSH refuses a key without.
         *
         * @throws IOException when there is no key, or the text is no private key
         */
        static @NonNull String normalized(@Nullable String pasted) throws IOException {
            String key = pasted == null ? "" : pasted.replace("\r\n", "\n").replace('\r', '\n').strip();
            if (key.isEmpty()) {
                throw new IOException("This connection holds no private key");
            }
            if (!key.startsWith("-----BEGIN ") || !key.contains("PRIVATE KEY-----")) {
                throw new IOException("The private key is not an OpenSSH or PEM private key");
            }
            return key + "\n";
        }

        private static @NonNull Path privateDirectory(@NonNull Path directory) throws IOException {
            Files.createDirectories(directory);
            Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwx------"));
            return directory;
        }
    }
}
