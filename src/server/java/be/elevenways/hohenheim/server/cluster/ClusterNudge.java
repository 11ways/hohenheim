package be.elevenways.hohenheim.server.cluster;

import be.elevenways.hohenheim.model.LinkedServerModel;
import be.elevenways.protoblast.common.Blast;
import be.elevenways.protoblast.common.thread.JobRunnerGroup;
import be.elevenways.zenit.common.bootstrap.RuntimeJobs;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.peer.SharedModels;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Tells this server's master at once that a kind it keeps a record of changed here: after the commit, in the
 * background, at most one call in flight and one waiting.
 *
 * AIDEV-NOTE: the call carries nothing but "ask me": the master answers it by syncing this server through the reads
 * only its master may make. A master that does not answer is not asked again; its minute check heals it. A server with
 * no master sends nothing, whatever it writes. Installed once per process, by ClusterHandlers.
 *
 * @author Jelle De Loecker
 * @since  0.10.0
 */
public final class ClusterNudge {

    private static final JobRunnerGroup.Member NUDGES =
        RuntimeJobs.GROUP.member("hh-cluster-nudges", JobRunnerGroup.StopMode.DRAIN, 1);

    private static final Coalesced NUDGE = new Coalesced(NUDGES, ClusterNudge::tellMaster);

    private ClusterNudge() {
    }

    private static final AtomicBoolean INSTALLED = new AtomicBoolean();

    /** Makes this process hear every committed write of a shared kind, once however often the handlers install. */
    static void install() {
        if (INSTALLED.compareAndSet(false, true)) {
            SharedModels.listenWritten(written -> NUDGE.ask());
        }
    }

    /**
     * Waits for the nudges this process asked to be sent, for tests.
     *
     * @return false when one was still on its way after the timeout
     */
    public static boolean awaitIdle(@NonNull Duration timeout) {
        return NUDGES.awaitIdle(timeout);
    }

    private static void tellMaster() {
        Row master = LinkedServers.master();
        if (master == null || LinkedServers.stopsSending(master)) {
            return;
        }
        LinkCalls.Answer answer = LinkCalls.changed(master.get(LinkedServerModel.BASE_URL),
            master.get(LinkedServerModel.LINK_KEY));
        if (!(answer instanceof LinkCalls.Answered answered) || answered.status() != 200) {
            Blast.log("CLUSTER: the master was not told of a change; its minute check takes it");
        }
    }
}
