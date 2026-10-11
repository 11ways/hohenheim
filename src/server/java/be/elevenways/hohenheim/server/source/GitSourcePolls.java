package be.elevenways.hohenheim.server.source;

import be.elevenways.hohenheim.HohenheimMicrocopy;
import be.elevenways.hohenheim.HohenheimViolations;
import be.elevenways.hohenheim.model.BuildOperationModel;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.OperationStatus;
import be.elevenways.hohenheim.server.application.ApplicationReleases;
import be.elevenways.hohenheim.server.instance.DeployTrigger;
import be.elevenways.hohenheim.source.GitSourceSchema;
import be.elevenways.protoblast.common.Blast;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.time.Now;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.orm.query.SortOrder;
import be.elevenways.zenit.common.validation.Violations;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * THE scheduled check of every git source that asks its repository for new commits
 * ({@link GitSourceSchema#pollSeconds}): the branch's head, read with {@code git ls-remote} and the source's own
 * credentials, deployed exactly as a push the webhook relays ({@link SourceDeploys}, {@link DeployTrigger#POLL})
 * when it is neither what runs nor what the last build tried.
 *
 * AIDEV-NOTE: the outcome of each check is kept in memory for the source's Deploys tab ({@link #lastCheck}); a restart
 * only forgets when it last asked. A commit is dispatched once per process, by this check or the webhook
 * ({@link SourceDeploys#lastDispatched}): a build that fails is not retried every interval, and the next push (or the
 * Deploy button) is what tries again.
 *
 * @author Jelle De Loecker
 * @since  0.10.0
 */
public final class GitSourcePolls {

    /** A commit id as git prints it, which a build row's source ref holds once a checkout landed. */
    private static final Pattern COMMIT = Pattern.compile("[0-9a-f]{40}");

    private static final Map<Integer, Check> LAST = new ConcurrentHashMap<>();

    private GitSourcePolls() {
    }

    /** What a check that read the branch's head found about it. */
    public enum Finding {

        /** The head is what runs, or what the newest build or a push already tried. */
        CURRENT("poll_current"),

        /** The head is new, and this check started deploying it. */
        DEPLOYING("poll_deploying"),

        /** The head is what the newest build tried, and that build failed: only a push or Deploy now tries again. */
        BUILD_FAILED("poll_build_failed");

        private final String copyKey;

        Finding(@NonNull String copyKey) {
            this.copyKey = copyKey;
        }

        /** @return its words, naming the commit as given */
        public @NonNull Microcopy wording(@NonNull String commit) {
            return HohenheimMicrocopy.DEPLOYMENTS.of(this.copyKey).withArg("commit", commit);
        }
    }

    /**
     * One check's outcome.
     *
     * @param at           when the repository was asked
     * @param remoteCommit the branch's head, null when the check failed
     * @param failure      why the check read no head, worded as its finding; null when it read one
     * @param finding      what it found about the head; null when the check failed
     */
    public record Check(@NonNull Instant at, @Nullable String remoteCommit, @Nullable Microcopy failure,
                        @Nullable Finding finding) {

        /** @return whether this check started a deploy of the head */
        public boolean deploying() {
            return this.finding == Finding.DEPLOYING;
        }

        /** @return whether the check reads as a failure: no head read, or the head's build failed */
        public boolean failed() {
            return this.failure != null || this.finding == Finding.BUILD_FAILED;
        }
    }

    /** @return the source's last check since this process started, null before its first */
    public static @Nullable Check lastCheck(int instanceId) {
        return LAST.get(instanceId);
    }

    /** Checks every source whose interval has passed since its last check. */
    public static void checkDue() {
        Instant now = Now.instant();
        for (Row instance : Models.get(InstanceModel.class).find()
                .where(InstanceModel.KIND.in(SiteSources.sourcedKinds()))
                .where(InstanceModel.liveAuthored())
                .all()) {
            int instanceId = instance.get(InstanceModel.ID);
            Map<String, Object> settings = SiteSources.settingsOfInstance(instance);
            int seconds = settings == null || !SiteSources.hasRepository(settings) ? 0
                : GitSourceSchema.pollSeconds(settings);
            if (seconds == 0) {
                LAST.remove(instanceId);
                continue;
            }
            Check last = LAST.get(instanceId);
            if (last == null || !last.at().plusSeconds(seconds).isAfter(now)) {
                LAST.put(instanceId, check(instance, Objects.requireNonNull(settings), now));
            }
        }
    }

    /** Asks the repository for the branch's head and deploys it when it is new. */
    static @NonNull Check check(@NonNull Row instance, @NonNull Map<String, Object> settings, @NonNull Instant now) {
        int instanceId = instance.get(InstanceModel.ID);
        String branch = GitSourceSchema.declaredBranch(settings);
        GitRepository.GitResult head;
        try {
            head = GitCheckout.repositoryFor(InstanceModel.MODEL_ID, instanceId, branch, settings).remoteHead();
        } catch (Violations refused) {
            return unreadable(now, HohenheimViolations.reasonOf(refused));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return unreadable(now, "interrupted");
        }
        if (!head.success()) {
            return unreadable(now, head.output().trim());
        }
        String remote = head.output();
        if (remote.isEmpty()) {
            return new Check(now, null, HohenheimMicrocopy.DEPLOYMENTS.of("poll_branch_missing")
                .withArg("branch", branch), null);
        }
        if (remote.equals(servingCommit(instance)) || remote.equals(SourceDeploys.lastDispatched(instanceId))) {
            return new Check(now, remote, null, Finding.CURRENT);
        }
        Row newest = newestBuild(instanceId);
        if (remote.equals(builtCommitOf(newest))) {
            Object status = newest.get(BuildOperationModel.STATUS);
            boolean failed = !BuildOperationModel.LIFECYCLE.is(status, OperationStatus.SUCCEEDED)
                && !BuildOperationModel.LIFECYCLE.is(status, OperationStatus.RUNNING);
            return new Check(now, remote, null, failed ? Finding.BUILD_FAILED : Finding.CURRENT);
        }
        Blast.log("GIT POLL: new commit", remote, "on", branch, "of instance", instanceId + "; deploying");
        SourceDeploys.deployInBackground(instance, branch, remote, DeployTrigger.POLL);
        return new Check(now, remote, null, Finding.DEPLOYING);
    }

    /** A check that could not read the branch's head, in git's or the refusal's own words. */
    private static @NonNull Check unreadable(@NonNull Instant now, @NonNull String reason) {
        return new Check(now, null,
            HohenheimMicrocopy.DEPLOYMENTS.of("poll_failed").withArg("reason", Microcopy.literal(reason)), null);
    }

    /** @return the commit an application's serving release was built from, null for a workspace or none */
    private static @Nullable String servingCommit(@NonNull Row instance) {
        Row serving = ApplicationReleases.ownedServing(instance.get(InstanceModel.ID));
        Object commit = serving == null ? null : ApplicationReleases.storedSettings(serving).get("commit_sha");
        return commit == null ? null : commit.toString();
    }

    /** @return the newest build of the record, failed or not; null when it never built */
    private static @Nullable Row newestBuild(int instanceId) {
        return Models.get(BuildOperationModel.class).find()
            .where(BuildOperationModel.FOR_MODEL.eq(InstanceModel.MODEL_ID.toString()))
            .where(BuildOperationModel.FOR_ID.eq(instanceId))
            .orderBy(BuildOperationModel.ID, SortOrder.DESC)
            .first();
    }

    /** @return the commit a build checked out; null for no build, or one whose checkout never landed */
    private static @Nullable String builtCommitOf(@Nullable Row build) {
        String ref = build == null ? null : build.get(BuildOperationModel.SOURCE_REF);
        return ref != null && COMMIT.matcher(ref).matches() ? ref : null;
    }
}
