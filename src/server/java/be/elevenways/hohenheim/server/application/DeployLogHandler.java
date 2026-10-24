package be.elevenways.hohenheim.server.application;

import be.elevenways.hohenheim.HohenheimChannels;
import be.elevenways.hohenheim.HohenheimDeployFunctions;
import be.elevenways.hohenheim.HohenheimDeployFunctions.Source;
import be.elevenways.hohenheim.HohenheimSources;
import be.elevenways.hohenheim.model.BuildOperationModel;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.ReleaseOperationModel;
import be.elevenways.hohenheim.server.instance.InstanceViewLinks;
import be.elevenways.hohenheim.server.build.BuildLog;
import be.elevenways.hohenheim.server.build.LiveBuildLogs;
import be.elevenways.hohenheim.server.util.SerialOutbox;
import be.elevenways.protoblast.common.holder.Subscription;
import be.elevenways.zenit.common.channel.ChannelException;
import be.elevenways.zenit.common.channel.ChannelHandler;
import be.elevenways.zenit.common.channel.ChannelLink;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.common.security.Principal;
import be.elevenways.zenit.common.websocket.WebSocketSession;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Streams one instance's deploy log to a viewer: the build's output as the build writes it and, for an operator, the
 * release's step log as the engine writes it (the {@code InstanceStatsHandler} shape, one channel over).
 *
 * AIDEV-NOTE: every source opens with a SNAPSHOT (its text so far) and then appends, so a re-opened link replaces
 * rather than doubles. The build snapshot is the running build's ({@link BuildLog#subscribe}: replay and attach under
 * the log's monitor), else the newest finished build's stored log unless a release in flight started after it -- that
 * release's build has not begun, and the previous build's output would read as its own. A build that ends while the
 * viewer follows it closes with a snapshot of its final text, the text its operation row stores.
 *
 * AIDEV-NOTE: the step log is the engine's daemon-worded diagnostics, which the delegated panel withholds
 * (WithheldFailure.operatorOnly); a channel has no panel, so the PRINCIPAL decides here, at open and at every
 * revalidation: an operator hears steps, a tenant hears the build alone, and a demoted operator stops hearing them.
 * The text itself is redacted where it is stored (BuildLog, ReleaseEngine.staged), so nothing reaches this handler
 * that the stored log would not hold.
 *
 * AIDEV-NOTE: LOCK ORDER, which keeps a closing link and a publishing build or release from deadlocking. The link
 * monitor (zenit's ServerChannelLink holds it across onClose) comes first; then this handler's {@code state}; then a
 * KeyedListenerTable lock (protoblast's consumer table, a leaf) or a BuildLog monitor; then the outbox's leaf lock.
 * Nothing is ever delivered under any of them: every frame is QUEUED (SerialOutbox.offer, safe under all of them) and
 * delivered by {@link SerialOutbox#drain}, called holding no lock, one thread at a time, in queue order. So the only
 * thread that waits on the link monitor holds nothing, and onClose (holding the link monitor) only flips
 * {@code closed}, closes the outbox and detaches its registrations, none of which waits on a delivery.
 *
 * @author Jelle De Loecker
 * @since 0.10.0
 */
public final class DeployLogHandler implements ChannelHandler<Object, Object> {

    private final @NonNull ChannelLink<Object, Object> link;
    private final @NonNull SerialOutbox<Object> outbox;
    private final LiveBuildLogs.@NonNull Watcher builds = new LiveBuildLogs.Watcher() {
        @Override
        public void started(@NonNull BuildLog log) {
            DeployLogHandler.this.started(log);
        }

        @Override
        public void ended(@NonNull BuildLog log) {
            DeployLogHandler.this.ended(log);
        }
    };

    /** Guards every field below; held to decide and queue, never to deliver. */
    private final Object state = new Object();

    private @Nullable Integer instanceId;
    private boolean closed;
    private @Nullable Subscription buildWatch;
    private @Nullable Subscription stepWatch;
    private @Nullable BuildLog tapped;
    private @Nullable Subscription tap;
    private @Nullable BuildLog lastEnded;
    private boolean buildHeard;
    private boolean stepsHeard;

    public DeployLogHandler(@NonNull ChannelLink<Object, Object> link) {
        this.link = link;
        this.outbox = new SerialOutbox<>(this::submit);
    }

    /** Install the factory; called at the MODULES boot stage. */
    public static void init() {
        HohenheimChannels.DEPLOY_LOG.setHandlerFactory(DeployLogHandler::new);
    }

    @Override
    public void onOpen(@Nullable Object openData) {
        int instanceId = InstanceViewLinks.admitted(this.link, openData);
        synchronized (this.state) {
            this.instanceId = instanceId;
        }
        try {
            if (this.isOperator()) {
                this.watchSteps(instanceId);
            }
            this.watchBuilds(instanceId);
        } catch (RuntimeException failed) {
            // A refused open is never closed by the gateway: what this open registered is released here.
            this.onClose();
            throw failed;
        }
    }

    @Override
    public boolean revalidate(@NonNull WebSocketSession session) {
        return this.readmitted();
    }

    /** The open's admission again, and the operator's step log added or dropped to match who the viewer is now. */
    boolean readmitted() {
        Integer instanceId;
        synchronized (this.state) {
            instanceId = this.closed ? null : this.instanceId;
        }
        if (instanceId == null || !InstanceViewLinks.views(this.link, instanceId)) {
            return false;
        }
        if (this.isOperator()) {
            this.watchSteps(instanceId);
        } else {
            this.unwatchSteps();
        }
        return true;
    }

    @Override
    public void onClose() {
        List<Subscription> ending = new ArrayList<>();
        synchronized (this.state) {
            if (this.closed) {
                return;
            }
            this.closed = true;
            for (Subscription registration : new Subscription[]{
                    this.buildWatch, this.stepWatch, this.tap}) {
                if (registration != null) {
                    ending.add(registration);
                }
            }
            this.buildWatch = null;
            this.stepWatch = null;
            this.tap = null;
            this.tapped = null;
        }
        this.outbox.close();
        for (Subscription registration : ending) {
            registration.unsubscribe();
        }
    }

    /** Hear the release step log: the stored one now unless a publish beats it, then every publish. */
    private void watchSteps(int instanceId) {
        synchronized (this.state) {
            if (this.closed || this.stepWatch != null) {
                return;
            }
        }
        Subscription watch = DeployProgress.watch(instanceId, this::stepsPublished);
        boolean kept;
        synchronized (this.state) {
            kept = !this.closed && this.stepWatch == null;
            if (kept) {
                this.stepWatch = watch;
                this.stepsHeard = false;
            }
        }
        if (!kept) {
            watch.unsubscribe();
            return;
        }
        // Read outside every lock: a publish that lands meanwhile is as new as this read or newer, so it wins.
        String stored = DeployProgress.newestStepLog(instanceId);
        synchronized (this.state) {
            if (this.stepWatch == watch && !this.stepsHeard) {
                this.stepsHeard = true;
                this.queue(Source.STEP, stored, true);
            }
        }
        this.outbox.drain();
    }

    private void unwatchSteps() {
        Subscription ending;
        synchronized (this.state) {
            ending = this.stepWatch;
            this.stepWatch = null;
        }
        if (ending != null) {
            ending.unsubscribe();
        }
    }

    private void stepsPublished(@NonNull String steps) {
        synchronized (this.state) {
            // Unwatched (a demoted viewer) or not yet kept: a publish before the keep is covered by the stored read.
            if (this.closed || this.stepWatch == null) {
                return;
            }
            this.stepsHeard = true;
            this.queue(Source.STEP, steps, true);
        }
        this.outbox.drain();
    }

    /** Hear the instance's builds: the running one, else the newest finished one unless a build beats the read. */
    private void watchBuilds(int instanceId) {
        Subscription watch = LiveBuildLogs.watch(InstanceModel.MODEL_ID, instanceId, this.builds);
        boolean kept;
        synchronized (this.state) {
            kept = !this.closed;
            if (kept) {
                this.buildWatch = watch;
            }
        }
        if (!kept) {
            watch.unsubscribe();
            return;
        }
        BuildLog running = LiveBuildLogs.of(InstanceModel.MODEL_ID, instanceId);
        if (running != null) {
            this.started(running);
            return;
        }
        // Read outside every lock: a build heard meanwhile (its start or its end) is newer, so it wins.
        String finished = finishedBuildLog(instanceId);
        synchronized (this.state) {
            if (!this.closed && !this.buildHeard) {
                this.buildHeard = true;
                this.queue(Source.BUILD, finished, true);
            }
        }
        this.outbox.drain();
    }

    /** Follow a build that runs for this instance: its text so far replaces what the viewer holds, then it appends. */
    private void started(@NonNull BuildLog log) {
        synchronized (this.state) {
            // The same build heard twice (its start and LiveBuildLogs.of), or one that already ended: nothing new.
            if (this.closed || log == this.tapped || log == this.lastEnded) {
                return;
            }
            if (this.tap != null) {
                this.tap.unsubscribe();
            }
            this.tapped = log;
            this.buildHeard = true;
            boolean[] first = {true};
            this.tap = log.subscribe(text -> {
                this.queue(Source.BUILD, text, first[0]);
                first[0] = false;
            }, this.outbox::drain);
        }
        this.outbox.drain();
    }

    /** The followed build ended: drop the tap and replace what the viewer holds with the final text. */
    private void ended(@NonNull BuildLog log) {
        synchronized (this.state) {
            if (this.closed || this.tapped != null && this.tapped != log) {
                return;
            }
            if (this.tap != null) {
                this.tap.unsubscribe();
                this.tap = null;
            }
            this.tapped = null;
            this.lastEnded = log;
            this.buildHeard = true;
            this.queue(Source.BUILD, log.text(), true);
        }
        this.outbox.drain();
    }

    /** Queue one frame (safe under any lock); an empty append is not worth a frame. */
    private void queue(@NonNull Source source, @NonNull String text, boolean snapshot) {
        if (snapshot || !text.isEmpty()) {
            this.outbox.offer(HohenheimDeployFunctions.frame(source, text, snapshot));
        }
    }

    /** The outbox's sink, called holding no lock. */
    private void submit(@NonNull Object frame) {
        // AIDEV-NOTE: no isOpen() guard: inside onOpen the link is OPENING and what it submits is queued until the
        // client hears the open (the InstanceStatsHandler note); only a closed link drops it.
        try {
            if (!this.link.isClosed()) {
                this.link.submit(frame);
            }
        } catch (ChannelException closedMeanwhile) {
            // The link closed between the check and the submit: there is no viewer left to deliver to.
        }
    }

    /** @return the newest finished build's stored log, or "" when a release in flight started after it */
    private static @NonNull String finishedBuildLog(int instanceId) {
        String owner = InstanceModel.MODEL_ID.toString();
        List<Row> newest = Models.get(BuildOperationModel.class).findForOwner(owner, instanceId, 1);
        if (newest.isEmpty()) {
            return "";
        }
        Row build = newest.get(0);
        Instant built = build.get(BuildOperationModel.STARTED_AT);
        for (Row release : Models.get(ReleaseOperationModel.class).findInFlight(owner, instanceId)) {
            Instant started = release.get(ReleaseOperationModel.STARTED_AT);
            if (built == null || started != null && started.isAfter(built)) {
                return "";
            }
        }
        String log = build.get(BuildOperationModel.LOG);
        return log == null ? "" : log;
    }

    /** Whether the viewer is an operator, who may read the release's daemon-worded step log. */
    private boolean isOperator() {
        Principal principal = this.link.getPrincipal();
        return principal != null && AccessContext.detached(principal).hasPermission(HohenheimSources.ADMIN_ACCESS);
    }
}
