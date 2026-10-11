package be.elevenways.hohenheim.server.source;

import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.server.HandlerSupport;
import be.elevenways.hohenheim.server.application.ApplicationDeploys;
import be.elevenways.hohenheim.server.instance.DeployTrigger;
import be.elevenways.hohenheim.server.instance.WorkspaceBuilds;
import be.elevenways.hohenheim.server.instance.WorkspaceKind;
import be.elevenways.zenit.common.orm.datasource.Row;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * THE deploy of a source's production branch that someone else's push asked for, learned from a webhook or from the
 * scheduled check: on the record kind's own verb, on a thread of its own, since a build takes minutes.
 *
 * @author Jelle De Loecker
 * @since  0.10.0
 */
public final class SourceDeploys {

    /** Per record, the commit the newest deploy here was started for, so a webhook and the check never both start it. */
    private static final Map<Integer, String> DISPATCHED = new ConcurrentHashMap<>();

    private SourceDeploys() {
    }

    /** @return the commit the newest deploy started here named, null before one named a commit in this process */
    public static @Nullable String lastDispatched(int instanceId) {
        return DISPATCHED.get(instanceId);
    }

    /**
     * Deploys the branch in the background.
     *
     * AIDEV-NOTE: which verb depends on WHAT the record is: an application converges a release, a workspace checks
     * out and builds inside its own container. The branch is on the KIND, not on a name -- a third source-driven kind
     * wires itself here.
     *
     * @param pushedSha the commit the push named, which the forge hears about until a checkout lands its own
     */
    public static void deployInBackground(@NonNull Row instance, @NonNull String branch, @Nullable String pushedSha,
                                          @NonNull DeployTrigger trigger) {
        int instanceId = instance.get(InstanceModel.ID);
        if (pushedSha != null) {
            DISPATCHED.put(instanceId, pushedSha);
        }
        boolean workspace = WorkspaceKind.ID.toString().equals(instance.get(InstanceModel.KIND));
        HandlerSupport.inBackgroundLogged(trigger.word() + ": deploy of instance " + instanceId, () -> {
            if (workspace) {
                new WorkspaceBuilds().deploy(instanceId, branch, pushedSha, trigger);
            } else {
                ApplicationDeploys.deploy(instanceId, branch, pushedSha, trigger);
            }
        });
    }
}
