package be.elevenways.hohenheim.server.source;

import be.elevenways.hohenheim.model.BuildOperationModel;
import be.elevenways.hohenheim.model.GitProviderModel;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.server.instance.InstanceOperationLock;
import be.elevenways.hohenheim.model.OperationStatus;
import be.elevenways.hohenheim.source.GitSourceSchema;
import be.elevenways.hohenheim.test.HohenheimTestBase;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.server.process.Subprocess;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * How a source reaches its repository beyond a forge's token: an SSH key that exists on disk for one command only,
 * the remote asked for its branches and head without a checkout, a repository named the same however it is spelled,
 * and the scheduled check's interval and findings.
 *
 * @author Jelle De Loecker
 * @since  0.10.0
 */
class GitRemoteAccessTest extends HohenheimTestBase {

    private static final String KEY = "-----BEGIN OPENSSH PRIVATE KEY-----\r\nb3BlbnNzaC1rZXktdjEAAAAA\r\n"
        + "-----END OPENSSH PRIVATE KEY-----";

    /**
     * A pasted key becomes a private file for one command and is gone after it; a connection without a usable key
     * is refused before git runs.
     */
    @Test
    void anSshKeyLivesOnDiskForOneCommandOnly() throws Exception {

        // 1. The pasted text is what ssh reads: one line ending kind and a final newline.
        assertThat(SshProviderClient.KeyAccess.normalized(KEY))
            .as("step 1: CRLF becomes LF and the key ends in a newline")
            .doesNotContain("\r").endsWith("-----END OPENSSH PRIVATE KEY-----\n");

        // 2. No key, or text that is no private key, is refused by name.
        assertThat(catchThrowable(() -> SshProviderClient.KeyAccess.normalized("  ")))
            .as("step 2: a blank key is refused").isInstanceOf(IOException.class)
            .hasMessageContaining("holds no private key");
        assertThat(catchThrowable(() -> SshProviderClient.KeyAccess.normalized("ssh-ed25519 AAAA me@host")))
            .as("step 2: a public key is no private key").isInstanceOf(IOException.class)
            .hasMessageContaining("not an OpenSSH or PEM private key");

        // 3. Opened, the key is a 0600 file in a 0700 directory that git's ssh command names, with the host keys
        //    pinned per connection; it cannot travel into another process namespace.
        Path file;
        Path directory;
        try (SshProviderClient.KeyAccess access = SshProviderClient.KeyAccess.open(41, KEY)) {
            String command = access.environment().get("GIT_SSH_COMMAND");
            assertThat(command).as("step 3: git runs ssh with exactly this key, never an agent's")
                .contains("-o IdentitiesOnly=yes").contains("-o IdentityAgent=none").contains("-o BatchMode=yes")
                .contains("StrictHostKeyChecking=accept-new");
            assertThat(command).as("step 3: the host keys are this connection's own")
                .contains("known_hosts/41'");
            file = Path.of(command.substring(command.indexOf("-i '") + 4, command.indexOf("' ")));
            directory = file.getParent();
            assertThat(Files.readString(file)).as("step 3: the file holds the normalized key")
                .isEqualTo(SshProviderClient.KeyAccess.normalized(KEY));
            assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(file)))
                .as("step 3: only the controller's user reads the key").isEqualTo("rw-------");
            assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(directory)))
                .as("step 3: nor lists its directory").isEqualTo("rwx------");
            assertThat(access.portable()).as("step 3: a key file never travels into a container").isFalse();
        }

        // 4. Closed, nothing of it is left.
        assertThat(directory).as("step 4: the key's directory is removed once git ran").doesNotExist();

        // 5. A stored SSH connection reads its key decrypted, and one without a key is refused before git runs.
        int keyed = sshProvider("ssh-keyed", KEY);
        Map<String, Object> bound = new LinkedHashMap<>();
        bound.put(GitSourceSchema.PROVIDER_ID, keyed);
        bound.put(GitSourceSchema.REPOSITORY, "git@git.example.test:acme/app.git");
        try (GitAccess access = GitProviders.access(bound)) {
            assertThat(access).as("step 5: a bound source clones with the connection's key").isNotNull();
            assertThat(access.portable()).as("step 5: as a file, not an environment").isFalse();
        }
        assertThat(GitProviders.clientFor(keyed).cloneUrl(" git@git.example.test:acme/app.git "))
            .as("step 5: the repository is its own clone URL").isEqualTo("git@git.example.test:acme/app.git");
        int keyless = sshProvider("ssh-keyless", null);
        assertThat(catchThrowable(() -> GitProviders.clientFor(keyless).listRepositories()))
            .as("step 5: testing a connection without a key names what is missing")
            .isInstanceOf(IOException.class).hasMessageContaining("holds no private key");
    }

    /** The remote is asked for its branches and a branch's head without a checkout. */
    @Test
    void theRemoteNamesItsBranchesAndHeadWithoutACheckout() throws Exception {
        Path upstream = Files.createTempDirectory("hohenheim-remote-upstream");
        String commit = commit(upstream, "one");
        git(upstream, "branch", "feature/login");

        GitRepository main = new GitRepository(upstream.toString(), "main", true, false, null, true);

        // 1. Every branch, by name.
        assertThat(main.remoteBranches()).as("step 1: both branches are listed")
            .containsExactlyInAnyOrder("main", "feature/login");

        // 2. The branch's head is the bare commit id.
        GitRepository.GitResult head = main.remoteHead();
        assertThat(head.success()).as("step 2: the remote answered").isTrue();
        assertThat(head.output()).as("step 2: with the branch's commit").isEqualTo(commit);

        // 3. A new commit is the new head; a branch the remote lacks answers empty, not a failure.
        String second = commit(upstream, "two");
        assertThat(main.remoteHead().output()).as("step 3: the head moved").isEqualTo(second);
        GitRepository.GitResult missing = new GitRepository(upstream.toString(), "gone", true, false, null, true)
            .remoteHead();
        assertThat(missing.success()).as("step 3: a missing branch is still an answer").isTrue();
        assertThat(missing.output()).as("step 3: an empty one").isEmpty();

        // 4. An unreachable remote fails in git's words.
        assertThat(catchThrowable(() -> new GitRepository(upstream.resolve("nowhere").toString(), "main", true,
                false, null, true).remoteBranches()))
            .as("step 4: listing an unreachable remote is a named failure").isInstanceOf(IOException.class);
    }

    /** A webhook names a repository as its forge does, whichever way the source spelled it. */
    @Test
    void aRepositoryIsNamedTheSameHoweverItIsSpelled() {
        for (String spelled : new String[] {"https://git.example.test/acme/app.git", "https://git.example.test/acme/app",
                "ssh://git@git.example.test/acme/app.git", "git@git.example.test:acme/app.git", "acme/app",
                "/acme/app.git"}) {
            assertThat(GitRepository.pathOf(spelled)).as(spelled + " names acme/app").isEqualTo("acme/app");
        }
        assertThat(GitRepository.pathOf("https://git.example.test")).as("a host alone names no path").isNull();
        assertThat(GitRepository.pathOf("  ")).as("blank names nothing").isNull();

        Map<String, Object> bySshUrl = new LinkedHashMap<>();
        bySshUrl.put(GitSourceSchema.REPOSITORY_URL, "git@git.example.test:acme/app.git");
        assertThat(GitWebhookHandler.boundRepositoryOf(bySshUrl))
            .as("an scp-style source URL binds a push to acme/app").isEqualTo("acme/app");
        Map<String, Object> bySshProvider = new LinkedHashMap<>();
        bySshProvider.put(GitSourceSchema.REPOSITORY, "ssh://git@git.example.test/acme/app.git");
        assertThat(GitWebhookHandler.boundRepositoryOf(bySshProvider))
            .as("an SSH connection's repository binds the same way").isEqualTo("acme/app");
    }

    /**
     * How often a source asks its repository, and what one check reports when the branch is gone or the remote
     * cannot be reached: a finding, never a deploy.
     */
    @Test
    void theScheduledCheckKeepsItsIntervalAndReportsWhatItFound() throws Exception {

        // 1. A new source asks every five minutes while it auto-deploys; 0 or auto-deploy off never asks, and an
        //    interval below the floor is raised to it.
        assertThat(GitSourceSchema.pollSeconds(null)).as("step 1: a new source asks every 300 s").isEqualTo(300);
        assertThat(GitSourceSchema.pollSeconds(Map.of(GitSourceSchema.POLL_INTERVAL, 0)))
            .as("step 1: 0 never asks").isZero();
        assertThat(GitSourceSchema.pollSeconds(Map.of(GitSourceSchema.POLL_INTERVAL, 600,
                GitSourceSchema.AUTO_DEPLOY, false)))
            .as("step 1: auto-deploy off never asks").isZero();
        assertThat(GitSourceSchema.pollSeconds(Map.of(GitSourceSchema.POLL_INTERVAL, 5)))
            .as("step 1: below the floor reads the floor").isEqualTo(GitSourceSchema.POLL_INTERVAL_FLOOR);
        assertThat(GitSourceSchema.pollSeconds(Map.of(GitSourceSchema.POLL_INTERVAL, "120")))
            .as("step 1: a stored text number reads as its number").isEqualTo(120);
        assertThat(GitSourceSchema.pollSeconds(Map.of(GitSourceSchema.POLL_INTERVAL, "soon")))
            .as("step 1: text that is no number never asks").isZero();

        // 2. A branch the repository lacks is a finding naming it, and starts nothing.
        Path upstream = Files.createTempDirectory("hohenheim-poll-upstream");
        commit(upstream, "one");
        Map<String, Object> settings = new LinkedHashMap<>();
        settings.put(GitSourceSchema.REPOSITORY_URL, upstream.toString());
        settings.put(GitSourceSchema.BRANCH, "release");
        Row application = application("poll-app", settings);
        Instant now = Instant.parse("2026-10-11T12:00:00Z");
        GitSourcePolls.Check gone = GitSourcePolls.check(application, settings, now);
        assertThat(gone.deploying()).as("step 2: nothing deploys").isFalse();
        assertThat(gone.remoteCommit()).as("step 2: no commit was seen").isNull();
        Microcopy failure = gone.failure();
        assertThat(failure).as("step 2: the finding is the missing branch").isNotNull();
        assertThat(failure.key()).isEqualTo("poll_branch_missing");
        assertThat(gone.at()).as("step 2: stamped with when it asked").isEqualTo(now);

        // 3. A repository that cannot be reached is git's own words, and starts nothing either.
        settings.put(GitSourceSchema.REPOSITORY_URL, upstream.resolve("nowhere").toString());
        settings.put(GitSourceSchema.BRANCH, "main");
        GitSourcePolls.Check unreachable = GitSourcePolls.check(application, settings, now);
        Microcopy unread = unreachable.failure();
        assertThat(unread).as("step 3: an unreachable repository is a finding").isNotNull();
        assertThat(unread.key()).as("step 3: that it could not be read").isEqualTo("poll_failed");
        assertThat(unreachable.deploying()).as("step 3: and nothing deploys").isFalse();

        // 4. A head whose newest build failed is not retried each interval: the finding says so, and how to retry.
        settings.put(GitSourceSchema.REPOSITORY_URL, upstream.toString());
        String head = commit(upstream, "two");
        var builds = Models.get(BuildOperationModel.class);
        Row failedBuild = builds.createEmptyRow();
        failedBuild.set(BuildOperationModel.FOR_MODEL, InstanceModel.MODEL_ID.toString());
        failedBuild.set(BuildOperationModel.FOR_ID, application.get(InstanceModel.ID));
        failedBuild.set(BuildOperationModel.BUILDER_KIND, BuildOperationModel.KIND_DOCKERFILE);
        failedBuild.set(BuildOperationModel.STATUS, BuildOperationModel.LIFECYCLE.stored(OperationStatus.FAILED));
        failedBuild.set(BuildOperationModel.SOURCE_REF, head);
        builds.save(failedBuild);
        GitSourcePolls.Check failed = GitSourcePolls.check(application, settings, now);
        assertThat(failed.finding()).as("step 4: the finding is the failed build")
            .isEqualTo(GitSourcePolls.Finding.BUILD_FAILED);
        assertThat(failed.failed()).as("step 4: which reads as a failure").isTrue();
        assertThat(failed.deploying()).as("step 4: and nothing deploys again").isFalse();
        assertThat(failed.remoteCommit()).as("step 4: naming the head it saw").isEqualTo(head);
    }

    /**
     * A new head found while an operation holds the record is not deployed beside it: the check reports the record
     * busy, dispatches nothing, and asks again at its next interval.
     *
     * AIDEV-NOTE: run 1862 (saved run 89): the check found a workspace's first commit while the record's own deploy
     * was still converting its image, queued a second deploy of the same commit behind it, and that queued deploy
     * then held the record when the operator's destroy came, refused as instance_operation_in_progress.
     */
    @Test
    void aNewHeadIsNotDeployedBesideAnOperationThatHoldsTheRecord() throws Exception {
        Path upstream = Files.createTempDirectory("hohenheim-poll-busy");
        String head = commit(upstream, "one");
        Map<String, Object> settings = new LinkedHashMap<>();
        settings.put(GitSourceSchema.REPOSITORY_URL, upstream.toString());
        settings.put(GitSourceSchema.BRANCH, "main");
        Row application = application("poll-busy-app", settings);
        int id = application.get(InstanceModel.ID);
        Instant now = Instant.parse("2026-10-13T12:00:00Z");

        // 1. An operation holds the record while the check reads a head nothing built yet.
        GitSourcePolls.Check busy = InstanceOperationLock.production().exclusive(id,
            InstanceOperationLock.Contention.REFUSE, () -> GitSourcePolls.check(application, settings, now));

        // 2. The check saw the head, reports the record busy and started nothing.
        assertThat(busy.remoteCommit()).as("step 2: the head was read").isEqualTo(head);
        assertThat(busy.finding()).as("step 2: the finding is the busy record")
            .isEqualTo(GitSourcePolls.Finding.BUSY);
        assertThat(busy.deploying()).as("step 2: nothing deploys beside the running operation").isFalse();
        assertThat(busy.failed()).as("step 2: a busy record is no failure").isFalse();
        assertThat(SourceDeploys.lastDispatched(id))
            .as("step 2: the head is not marked dispatched, so the next interval asks again").isNull();
    }

    private static Row application(String name, Map<String, Object> settings) {
        var instances = Models.get(InstanceModel.class);
        Row application = instances.createEmptyRow();
        application.set(InstanceModel.NAME, name);
        application.set(InstanceModel.KIND, "hohenheim:application");
        application.set(InstanceModel.SETTINGS, new LinkedHashMap<>(settings));
        instances.save(application);
        return application;
    }

    private static int sshProvider(String name, String key) {
        var providers = Models.get(GitProviderModel.class);
        Row row = providers.createEmptyRow();
        row.set(GitProviderModel.NAME, name);
        row.set(GitProviderModel.KIND, SshProviderKind.ID.toString());
        row.set(GitProviderModel.SETTINGS, new LinkedHashMap<>(Map.of(SshProviderKind.REPOSITORY.getName(),
            "git@git.example.test:acme/app.git")));
        row.set(GitProviderModel.SHARED, false);
        row.set(GitProviderModel.SSH_PRIVATE_KEY, key);
        providers.save(row);
        return row.get(GitProviderModel.ID);
    }

    /** Commits one file on main, creating the repository first; @return the new commit. */
    private static String commit(Path repo, String content) throws Exception {
        if (!Files.isDirectory(repo.resolve(".git"))) {
            git(repo, "init", "-q", "-b", "main");
            git(repo, "config", "user.email", "test@example.com");
            git(repo, "config", "user.name", "Test");
        }
        Files.writeString(repo.resolve("index.html"), content);
        git(repo, "add", ".");
        git(repo, "commit", "-q", "-m", content);
        var outcome = Subprocess.of("git", "rev-parse", "HEAD").directory(repo).timeout(Duration.ofSeconds(30)).run();
        return outcome.stdout().text().trim();
    }

    private static void git(Path repo, String... args) throws Exception {
        var outcome = Subprocess.of("git", args).directory(repo).timeout(Duration.ofSeconds(30)).mergeStderr().run();
        assertThat(outcome.succeeded()).as("fixture git command: %s", outcome.describe()).isTrue();
    }
}
