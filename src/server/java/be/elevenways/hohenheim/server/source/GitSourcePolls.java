package be.elevenways.hohenheim.server.source;

import be.elevenways.hohenheim.HohenheimMicrocopy;
import be.elevenways.hohenheim.HohenheimViolations;
import be.elevenways.hohenheim.model.BuildOperationModel;
import be.elevenways.hohenheim.model.InstanceModel;
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

    /**
     * One check's outcome.
     *
     * @param at           when the repository was asked
     * @param remoteCommit the branch's head, null when the check failed
     * @param failure      why the check found no commit, worded as its finding; null when it found one
     * @param deploying    whether this check started a deploy of the head
     */
    public record Check(@NonNull Instant at, @Nullable String remoteCommit, @Nullable Microcopy failure,
                        boolean deploying) {
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
                .withArg("branch", branch), false);
        }
        if (remote.equals(servingCommit(instance)) || remote.equals(lastBuiltCommit(instanceId))
                || remote.equals(SourceDeploys.lastDispatched(instanceId))) {
            return new Check(now, remote, null, false);
        }
        Blast.log("GIT POLL: new commit", remote, "on", branch, "of instance", instanceId + "; deploying");
        SourceDeploys.deployInBackground(instance, branch, remote, DeployTrigger.POLL);
        return new Check(now, remote, null, true);
    }

    /** A check that could not read the branch's head, in git's or the refusal's own words. */
    private static @NonNull Check unreadable(@NonNull Instant now, @NonNull String reason) {
        return new Check(now, null,
            HohenheimMicrocopy.DEPLOYMENTS.of("poll_failed").withArg("reason", Microcopy.literal(reason)), false);
    }

    /** @return the commit an application's serving release was built from, null for a workspace or none */
    private static @Nullable String servingCommit(@NonNull Row instance) {
        Row serving = ApplicationReleases.ownedServing(instance.get(InstanceModel.ID));
        Object commit = serving == null ? null : ApplicationReleases.storedSettings(serving).get("commit_sha");
        return commit == null ? null : commit.toString();
    }

    /** @return the commit the newest build of the record checked out, failed or not; null when none named one */
    private static @Nullable String lastBuiltCommit(int instanceId) {
        Row newest = Models.get(BuildOperationModel.class).find()
            .where(BuildOperationModel.FOR_MODEL.eq(InstanceModel.MODEL_ID.toString()))
            .where(BuildOperationModel.FOR_ID.eq(instanceId))
            .orderBy(BuildOperationModel.ID, SortOrder.DESC)
            .first();
        String ref = newest == null ? null : newest.get(BuildOperationModel.SOURCE_REF);
        return ref != null && COMMIT.matcher(ref).matches() ? ref : null;
    }
}
