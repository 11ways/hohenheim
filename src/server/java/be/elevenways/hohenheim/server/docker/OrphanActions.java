package be.elevenways.hohenheim.server.docker;

import be.elevenways.hohenheim.model.ReconcileFindingModel;
import be.elevenways.protoblast.common.Blast;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.validation.Violations;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.io.IOException;
import java.util.Map;

/**
 * THE explicit authority over what a dead record (or a stale controller) left on a
 * daemon -- the operator action the reconciler deliberately refuses to take as a
 * classification side effect. Every removal RE-VERIFIES live truth at the daemon
 * first: the stored finding is a report, and acting on a stale report would be the
 * reports-success defect with a delete button.
 *
 * AIDEV-NOTE: volumes are REFUSED here permanently (the one unrecoverable resource,
 * the DockerReclaim rule); label ADOPTION is still not implemented because Docker
 * cannot relabel a container or volume in place -- adoption means recreate-under-
 * labels, which only the owning tier can do correctly.
 */
public final class OrphanActions {

    private OrphanActions() {
    }

    /**
     * Stop-and-remove one ORPHANED container or network, re-verified against the
     * daemon at call time.
     *
     * @throws Violations when the finding is not an orphan, is a volume, or live
     *         re-classification no longer agrees it is ours-with-no-record
     */
    public static void removeOrphan(@NonNull Row finding) {
        String serverName = finding.get(ReconcileFindingModel.SERVER_NAME);
        String kind = finding.get(ReconcileFindingModel.KIND);
        String name = finding.get(ReconcileFindingModel.RESOURCE_NAME);
        if (!ReconcileFindingModel.BUCKET_ORPHANED.equals(finding.get(ReconcileFindingModel.BUCKET))) {
            throw refusal("orphan_not_orphaned", name);
        }
        if (DockerReconciler.KIND_VOLUME.equals(kind)) {
            throw refusal("orphan_volume_refused", name);
        }

        DockerClient docker = new ServerService().clientFor(serverName);
        DockerReconciler.Finding live = reclassify(docker, kind, name);
        if (live == null) {
            // Already gone at the daemon: the finding is stale; drop it.
            deleteFinding(finding);
            return;
        }
        if (live.bucket() != DockerReconciler.Bucket.ORPHANED) {
            throw refusal("orphan_reclassified", name);
        }

        try {
            if (DockerReconciler.KIND_CONTAINER.equals(kind)) {
                try {
                    docker.stopContainer(name, 10);
                } catch (IOException ignored) {
                    // stop is a courtesy; the remove below is the authority
                }
                docker.removeContainer(name, true);
            } else if (DockerReconciler.KIND_NETWORK.equals(kind)) {
                docker.removeNetwork(name);
            } else {
                throw refusal("orphan_kind_unknown", name);
            }
        } catch (DockerClient.ApiException e) {
            if (!e.isNotFound()) {
                throw refusal("orphan_remove_failed", name + ": " + e.getMessage());
            }
        } catch (IOException e) {
            throw refusal("orphan_remove_failed", name + ": " + e.getMessage());
        }
        deleteFinding(finding);
        Blast.log("DOCKER RECONCILE: operator removed orphaned", kind, name,
            "on", serverName);
    }

    /** Live re-classification of one resource, or null when the daemon no longer has it. */
    private static DockerReconciler.@Nullable Finding reclassify(DockerClient docker,
                                                                 String kind, String name) {
        try {
            Map<String, Object> inspected;
            Object labels;
            if (DockerReconciler.KIND_CONTAINER.equals(kind)) {
                inspected = docker.inspectContainer(name);
                labels = inspected.get("Config") instanceof Map<?, ?> config
                    ? config.get("Labels") : null;
            } else if (DockerReconciler.KIND_NETWORK.equals(kind)) {
                inspected = docker.inspectNetwork(name);
                labels = inspected.get("Labels");
            } else {
                return null;
            }
            return DockerReconciler.classify(kind, name,
                labels instanceof Map<?, ?> map ? map : null,
                new DockerReconciler.ModelRecords());
        } catch (DockerClient.ApiException e) {
            if (e.isNotFound()) {
                return null;
            }
            throw refusal("orphan_remove_failed", name + ": " + e.getMessage());
        } catch (IOException e) {
            throw refusal("orphan_remove_failed", name + ": " + e.getMessage());
        }
    }

    private static void deleteFinding(Row finding) {
        Models.get(ReconcileFindingModel.class)
            .delete(finding.get(ReconcileFindingModel.ID));
    }

    private static Violations refusal(String key, String detail) {
        return Violations.ofForm(Microcopy.of(key)
            .withFilter("scope", "violations")
            .withArg("name", detail));
    }
}
