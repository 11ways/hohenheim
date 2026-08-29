package be.elevenways.hohenheim.server.stack;

import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.StackDeploymentModel;
import be.elevenways.hohenheim.model.StackFileModel;
import be.elevenways.hohenheim.model.StackModel;
import be.elevenways.hohenheim.model.StackServiceModel;
import be.elevenways.hohenheim.server.cms.CmsSupport;
import be.elevenways.hohenheim.server.orm.PendingDeletes;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.datasource.context.RemoveFromDatasource;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.validation.Violations;
import org.checkerframework.checker.nullness.qual.NonNull;

/**
 * A stack's rows cannot outlive it: deleting a stack takes its services (and their config
 * files) and its deployment history; deleting a service takes its files. A service whose
 * lowered workload is still LIVE refuses to go, on every delete lane.
 *
 * AIDEV-NOTE: the same policy split as the rest of the repo, applied one tier down. The
 * rows are CASCADED because a service, a file or a deployment snapshot is meaningless
 * without its stack (the access-list rule): the files carry encrypted credential material
 * and the snapshots embed registry credentials, so leaving them behind keeps secrets in a
 * table no surface can reach. The workload is REFUSED because a container is not a row
 * (the project/host rule): the daemon-side teardown is {@code StackRuntime.destroy} /
 * {@code StackInstances.destroyFor}, which soft-delete the owned instance FIRST, so on the
 * CMS lane this refusal never fires -- it exists for a direct {@code model.delete}, which
 * would otherwise leave a running container attributed to a record that no longer exists.
 *
 * AIDEV-NOTE: {@code StackResource.deleteRow} used to carry this cascade by hand, inside its
 * own transaction and reachable from that one button alone; the funnel is what makes a
 * criteria delete, the API and a test do the same thing. The resource keeps the transaction
 * so the stack row never survives its children or the reverse.
 */
public final class StackCascades {

    private static volatile boolean installed;

    private StackCascades() {
    }

    /** Install the cascade hooks; idempotent, called at the MODULES boot stage. */
    public static synchronized void install() {
        if (installed) {
            return;
        }
        installed = true;

        StackModel.SCHEMA.addBeforeRemoveHook(context -> {
            PendingDeletes.deleteDependents(Models.get(StackDeploymentModel.class),
                StackDeploymentModel.STACK, context);
            // The service delete fires the service hook below: files and the refusal.
            PendingDeletes.deleteDependents(Models.get(StackServiceModel.class),
                StackServiceModel.STACK, context);
        });

        StackServiceModel.SCHEMA.addBeforeRemoveHook(context -> {
            refuseWhileRunning(context);
            PendingDeletes.deleteDependents(Models.get(StackFileModel.class),
                StackFileModel.SERVICE, context);
        });
    }

    /**
     * @throws Violations {@code stack_service_running} naming the service whose lowered
     *         workload is still live
     */
    private static void refuseWhileRunning(@NonNull RemoveFromDatasource context) {
        Row running = Models.get(InstanceModel.class).find()
            .where(InstanceModel.GENERATED_FOR_MODEL.eq(StackServiceModel.MODEL_ID.toString()))
            .where(InstanceModel.DELETED_AT.isNull())
            .where(PendingDeletes.dependents(InstanceModel.OWNING_STACK_SERVICE, context))
            .first();
        if (running == null) {
            return;
        }
        Row service = running.get(InstanceModel.OWNING_STACK_SERVICE);
        String name = service != null
            ? String.valueOf((Object) service.get(StackServiceModel.NAME))
            : String.valueOf((Object) running.get(InstanceModel.NAME));
        throw Violations.ofForm(CmsSupport.violationText("stack_service_running")
            .withArg("service", name));
    }
}
