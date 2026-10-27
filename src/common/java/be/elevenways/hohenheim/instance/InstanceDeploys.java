package be.elevenways.hohenheim.instance;

import be.elevenways.hawkeye.common.annotation.HawkeyeClass;
import be.elevenways.plumage.component.Pager;
import be.elevenways.zenit.cms.common.render.action.InvokeActionState;
import be.elevenways.zenit.cms.common.render.action.PageFormState;
import be.elevenways.zenit.common.secret.SecretRef;
import be.elevenways.zenit.common.ui.BadgeVariant;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.List;

/**
 * A record's Deploys tab as its live part: the live release band, the history, the previews and where the deploys
 * come from, read in one go for the reader.
 *
 * AIDEV-NOTE: ONE shape for the page render and the live re-read, so a build, release, preview or push write redraws
 * this part in place and never the page around it.
 *
 * @param instanceId the record the tab belongs to
 * @param band       what serves now and the one step to the kept release, null while nothing serves
 * @param history    the deploy history's page
 * @param previews   the previews, null where the record shows none
 * @param source     where the deploys come from, null for a lane with no trigger vocabulary (a workspace)
 * @author Jelle De Loecker
 * @since 0.1.0
 */
@HawkeyeClass
public record InstanceDeploys(int instanceId, @Nullable Band band, @NonNull History history,
                              @Nullable Previews previews, @Nullable Source source) {

    /**
     * The live release band.
     *
     * @param branch        the branch the deploys come from, "" when the source names none
     * @param keepsReleases whether the lane keeps a previous release (an application does, a workspace never)
     * @param keptCommit    the kept release's short commit, "" for none
     * @param liveSinceIso  when the live release started serving, "" when unknown
     * @param rollbackForm  the placed rollback operation's form, set only where its offer stands for this reader
     */
    @HawkeyeClass
    public record Band(@NonNull String currentCommit, @NonNull String branch, @NonNull String keptCommit,
                       @NonNull String liveSinceIso,
                       boolean keepsReleases, boolean keptSameCommit, boolean keptNewer,
                       @Nullable PageFormState rollbackForm) {
    }

    /**
     * One page of deploy history.
     *
     * @param showTrigger whether rows name what started them (the release lane's vocabulary)
     * @param deployForm  the placed deploy operation's form, set only where its offer stands for this reader
     * @param pager       the history's pages, null when one page holds it
     */
    @HawkeyeClass
    public record History(boolean showTrigger, @NonNull List<Deploy> deployments,
                          @Nullable PageFormState deployForm, @Nullable Pager pager) {
    }

    /**
     * One history row: a release or a build that never became one.
     *
     * @param key      unique across both kinds on the page
     * @param commit   the short commit, "" when the row never reached one
     * @param ref      the branch a row that never reached a commit was asked for, "" otherwise
     * @param inFlight whether the row's deploy runs now
     * @param mark     where a succeeded release stands (live, kept, replaced), "" otherwise
     * @param live     the deploy that runs now, with what it wrote before this read; null on every other row
     */
    @HawkeyeClass
    public record Deploy(@NonNull String key, @NonNull Status status, @NonNull String commit, @NonNull String ref,
                         @NonNull String duration, @NonNull String startedAtIso, @NonNull String kindLabel,
                         boolean release, boolean inFlight, @NonNull String trigger, @NonNull String triggerText,
                         @NonNull String mark, @NonNull DeployDetail detail, @Nullable LiveLog live) {

        /** @return this row as the deploy that runs now */
        public @NonNull Deploy withLive(@NonNull LiveLog log) {
            return new Deploy(this.key, this.status, this.commit, this.ref, this.duration, this.startedAtIso,
                this.kindLabel, this.release, this.inFlight, this.trigger, this.triggerText, this.mark, this.detail,
                log);
        }
    }

    /**
     * A row's status with the shared enum facets, so semantic roles and categorical hues survive both lanes.
     *
     * @param label the status in words (a succeeded release's mark), null for an undeclared status
     * @param known whether the status field declares the value; an undeclared one reads as plain text
     */
    @HawkeyeClass
    public record Status(@NonNull String value, @Nullable String label, @Nullable BadgeVariant variant,
                         @Nullable String colorSet, boolean known) {
    }
    /**
     * The seeds of the deploy that runs now: what its build and release steps wrote before this read.
     *
     * @param showsSteps whether this panel shows the release's step log
     */
    @HawkeyeClass
    public record LiveLog(@NonNull String buildSeed, @NonNull String stepSeed, boolean showsSteps) {
    }

    /**
     * The record's previews.
     *
     * @param createUrl where a new preview starts, "" while previews are off
     */
    @HawkeyeClass
    public record Previews(@NonNull List<Preview> rows, @NonNull String createUrl) {
    }

    /**
     * One preview with its own row actions.
     *
     * @param pullRequest the pull request number, "" for none
     * @param liveUrl     the preview's own address, "" before it has a name
     */
    @HawkeyeClass
    public record Preview(@NonNull String ref, @NonNull String pullRequest, @NonNull String hostname,
                          @NonNull String liveUrl, @Nullable String statusLabel, @Nullable BadgeVariant statusVariant,
                          @Nullable String statusColorSet, boolean running, @NonNull String expiresIso,
                          @NonNull String openUrl, @NonNull List<InvokeActionState> invokes) {
    }

    /**
     * Where the deploys come from and what the last pushes did.
     *
     * @param pollMinutes    how often the scheduled check asks, 0 for never
     * @param pollCheckedIso when it last asked since this process started, "" for not yet
     * @param webhook        the push address and secret, set only for the operator
     */
    @HawkeyeClass
    public record Source(@NonNull String repository, @NonNull String branch, boolean autoDeploy, int pollMinutes,
                         @NonNull String pollCheckedIso, boolean pollFailed, @NonNull String pollFinding,
                         @NonNull List<Push> pushes, @Nullable Webhook webhook) {
    }

    /** One received push and its outcome in words. */
    @HawkeyeClass
    public record Push(@NonNull String event, @NonNull String outcome, boolean acted, @NonNull String receivedIso) {
    }

    /**
     * The operator-only push settings.
     *
     * @param secretReveal the stored secret's reveal, null while none is stored; the secret never enters the page
     */
    @HawkeyeClass
    public record Webhook(@NonNull String url, @Nullable SecretRef secretReveal, boolean autoDeploy) {
    }
}
