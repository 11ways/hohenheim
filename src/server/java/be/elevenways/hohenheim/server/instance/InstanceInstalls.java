package be.elevenways.hohenheim.server.instance;

import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.InstanceTemplateModel;
import be.elevenways.hohenheim.server.host.HostAdmission;
import be.elevenways.hohenheim.server.instance.InstanceService.Resolved;
import be.elevenways.hohenheim.server.runtime.ContainerState;
import be.elevenways.hohenheim.server.runtime.InstallSupport;
import be.elevenways.hohenheim.server.runtime.InstanceStatus;
import be.elevenways.hohenheim.server.runtime.VolumeSnapshotSupport;
import be.elevenways.hohenheim.server.util.EnvVars;
import be.elevenways.protoblast.common.Blast;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.validation.Violations;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The DURABLE install/reinstall operation of the template lifecycle. Durability
 * contract: the operation's state lives on the record ({@code install_state}, fenced
 * writes), an interrupted run leaves {@code installing} behind as visible evidence and
 * a leftover owned install container that the next attempt replaces (resume), a failed
 * run stamps {@code failed} with the output tail (rollback to a retryable state) --
 * and NO path here ever touches the instance's VARIABLE ROWS. Losing a tenant's only
 * copy of variables/credentials to an interrupted install is the failure class this
 * class exists to make impossible.
 *
 * AIDEV-NOTE: corrected 2026-08-08 -- this docblock used to say no path touches
 * "variable rows or volumes, except the volume wipe" a clear policy demanded, and to
 * credit a typed confirmation. Both understated it. The {@code clear} branch DESTROYS
 * THE WORKLOAD and then removes its volumes (see the inline comments at the branch),
 * and the typed confirmation is a property of the CALLER
 * ({@code InstanceResource#reinstallAction}), not of this class -- nothing here checks
 * for one, which is why every future caller must supply its own interlock.
 */
public final class InstanceInstalls {

    /** Hard wall-clock cap on one install run (downloads included). */
    static final long INSTALL_TIMEOUT_MS = 10 * 60 * 1000;

    private final @NonNull InstanceService instances;
    private final @NonNull InstanceVariables variables = new InstanceVariables();

    /** Test seam: runs after the installing stamp, before the install container works. */
    private final @NonNull Runnable beforeInstallRun;

    public InstanceInstalls() {
        this(new InstanceService(), () -> {});
    }

    /** Test seam: the pause hook is the injectable crash between the durable stamp and the run. */
    public InstanceInstalls(@NonNull InstanceService instances, @NonNull Runnable beforeInstallRun) {
        this.instances = instances;
        this.beforeInstallRun = beforeInstallRun;
    }

    /**
     * Run (or resume/retry) the template's install step. No-op with a stamped
     * {@code none} when the template declares no step.
     *
     * @throws Violations naming every refusal or failure; the record survives all of them
     */
    public void install(int instanceId) {
        Resolved resolved = this.instances.resolve(instanceId);
        InstanceOperationGuard.requireOperable(resolved.row());
        Row template = requireTemplate(resolved.row());

        String script = template.get(InstanceTemplateModel.INSTALL_SCRIPT);
        long fence = this.instances.leases().requireFence(resolved.serverId());
        if (script == null || script.isBlank()) {
            InstanceOperationGuard.stampInstall(this.instances.leases(), instanceId,
                resolved.serverId(), fence, InstanceModel.INSTALL_NONE, null,
                resolved.row().get(InstanceModel.NAME));
            return;
        }
        runInstallStep(resolved, template, fence);
    }

    /**
     * Reinstall per the template's EXPLICIT data policy: {@code preserve} keeps the
     * volumes and re-runs the step over them; {@code clear} first removes the workload
     * and its (owner-verified) volumes. The typed confirmation for {@code clear} lives
     * at the action layer -- this method enforces the POLICY, the dialog enforces the
     * human.
     */
    public void reinstall(int instanceId) {
        Resolved resolved = this.instances.resolve(instanceId);
        InstanceOperationGuard.requireOperable(resolved.row());
        Row template = requireTemplate(resolved.row());
        String script = template.get(InstanceTemplateModel.INSTALL_SCRIPT);
        if (script == null || script.isBlank()) {
            throw refusal("reinstall_no_step", resolved.row(), null);
        }
        requireNotRunning(resolved);
        // The honest refusal for a driver that cannot run install steps at all comes
        // FIRST: without this, a reinstall with a clear policy on such a driver would
        // name missing VOLUME support instead of the install capability it lacks.
        if (!(resolved.runtime() instanceof InstallSupport)) {
            throw refusal("install_unsupported", resolved.row(), null);
        }
        long fence = this.instances.leases().requireFence(resolved.serverId());

        if (InstanceTemplateModel.REINSTALL_CLEAR
                .equals(template.get(InstanceTemplateModel.REINSTALL_POLICY))) {
            // A driver with named volumes must be able to wipe them owner-verified; a
            // rootfs-stateful driver (incus) has none -- destroying the workload IS the
            // wipe there, and the install re-creates the rootfs from the image.
            Map<String, String> logical = logicalVolumes(resolved);
            if (!logical.isEmpty()
                    && !(resolved.runtime() instanceof VolumeSnapshotSupport)) {
                throw refusal("snapshots_unsupported", resolved.row(), null);
            }
            try {
                // Remove the workload first so no container holds the volumes, then the
                // volumes themselves -- refused per volume unless the daemon attributes
                // them to THIS record (never a stranger's data over a name collision).
                resolved.runtime().destroy(resolved.spec().handle());
                if (!logical.isEmpty()
                        && resolved.runtime() instanceof VolumeSnapshotSupport volumes) {
                    volumes.removeVolumesForRestore(resolved.spec(), logical, logical.keySet());
                }
            } catch (IOException error) {
                InstanceOperationGuard.stampInstall(this.instances.leases(), instanceId,
                    resolved.serverId(), fence, InstanceModel.INSTALL_FAILED, describe(error),
                    resolved.row().get(InstanceModel.NAME));
                throw refusal("reinstall_clear_failed", resolved.row(), error);
            }
        }
        runInstallStep(resolved, template, fence);
    }

    // -- the one install runner -----------------------------------------------

    private void runInstallStep(@NonNull Resolved resolved, @NonNull Row template, long fence) {
        int instanceId = resolved.row().get(InstanceModel.ID);
        // The vocabulary gate's install-time lane, BEFORE any daemon contact: a script
        // that sources the community function library and calls a helper the library
        // does not implement is refused by name -- never run into a silent 127.
        CommunityScripts.requireVocabularyImplemented(
            template.get(InstanceTemplateModel.INSTALL_SCRIPT), "install script");
        requireNotRunning(resolved);
        if (!(resolved.runtime() instanceof InstallSupport support)) {
            throw refusal("install_unsupported", resolved.row(), null);
        }
        HostAdmission.requireInstancePlacement(resolved.serverId());

        String installImage = template.get(InstanceTemplateModel.INSTALL_IMAGE);
        if (installImage == null || installImage.isBlank()) {
            // The workload's own image is a legitimate install environment default.
            installImage = resolved.spec().image();
        }
        String script = template.get(InstanceTemplateModel.INSTALL_SCRIPT);

        // The durable "in flight" mark lands BEFORE any daemon work (fenced): a crash
        // anywhere after leaves visible evidence, never a clean-looking record.
        InstanceOperationGuard.stampInstall(this.instances.leases(), instanceId,
            resolved.serverId(), fence, InstanceModel.INSTALL_INSTALLING, null,
            resolved.row().get(InstanceModel.NAME));
        this.beforeInstallRun.run();

        Map<String, String> env = new LinkedHashMap<>(resolved.spec().env());
        env.putAll(resolved.variables());
        if (CommunityScripts.requiresFunctionLibrary(script)) {
            // The one injection point of the pinned shim library: the script's
            // `source /dev/stdin <<<"$FUNCTIONS_FILE_PATH"` runs verbatim against it.
            env.put(CommunityScripts.LIBRARY_MARKER, CommunityScripts.functionsLibrary());
            env.put("APPLICATION",
                String.valueOf((Object) resolved.row().get(InstanceModel.NAME)));
        }
        try {
            InstallSupport.InstallOutcome outcome = support.runInstall(resolved.spec(),
                installImage.trim(), script, env, INSTALL_TIMEOUT_MS);
            if (outcome.succeeded()) {
                InstanceOperationGuard.stampInstall(this.instances.leases(), instanceId,
                    resolved.serverId(), fence, InstanceModel.INSTALL_INSTALLED, null,
                    resolved.row().get(InstanceModel.NAME));
                Blast.log("INSTANCE: install completed for", resolved.spec().handle());
                return;
            }
            InstanceOperationGuard.stampInstall(this.instances.leases(), instanceId,
                resolved.serverId(), fence, InstanceModel.INSTALL_FAILED,
                "exit " + outcome.exitCode() + "\n" + outcome.outputTail(),
                resolved.row().get(InstanceModel.NAME));
            throw refusal("install_failed", resolved.row(), null);
        } catch (IOException error) {
            InstanceOperationGuard.stampInstall(this.instances.leases(), instanceId,
                resolved.serverId(), fence, InstanceModel.INSTALL_FAILED, describe(error),
                resolved.row().get(InstanceModel.NAME));
            throw refusal("install_failed", resolved.row(), error);
        }
    }

    // -- plumbing -------------------------------------------------------------

    /**
     * @throws Violations {@code install_needs_template} for a template-less instance
     */
    private static @NonNull Row requireTemplate(@NonNull Row instance) {
        Object templateId = instance.get(InstanceModel.TEMPLATE_ID);
        Row template = templateId instanceof Integer id
            ? Models.get(InstanceTemplateModel.class).findById(id) : null;
        if (template == null) {
            throw refusal("install_needs_template", instance, null);
        }
        return template;
    }

    /**
     * @throws Violations {@code install_requires_stopped} while the workload runs
     */
    private static void requireNotRunning(@NonNull Resolved resolved) {
        InstanceStatus live = resolved.runtime().status(resolved.spec().handle());
        if (live.state() == ContainerState.RUNNING) {
            throw refusal("install_requires_stopped", resolved.row(), null);
        }
        if (live.state() == ContainerState.UNREACHABLE) {
            throw refusal("instance_unreachable", resolved.row(), null);
        }
    }

    /** The instance's LOGICAL volumes (settings keys), the vocabulary the driver speaks. */
    private static @NonNull Map<String, String> logicalVolumes(@NonNull Resolved resolved) {
        Object volumes = resolved.row().get(InstanceModel.SETTINGS) instanceof Map<?, ?> settings
            ? settings.get("volumes") : null;
        Map<String, String> logical = new LinkedHashMap<>();
        EnvVars.toMap(volumes).forEach((name, path) -> {
            if (path != null && !path.isBlank()) {
                logical.put(name, path);
            }
        });
        return logical;
    }

    private static @NonNull String describe(@NonNull IOException error) {
        return error.getMessage() != null ? error.getMessage() : error.toString();
    }

    private static Violations refusal(String key, Row row, @Nullable IOException cause) {
        Microcopy text = Microcopy.of(key).withFilter("scope", "violations")
            .withArg("name", String.valueOf((Object) row.get(InstanceModel.NAME)));
        if (cause != null) {
            text = text.withArg("reason",
                cause.getMessage() != null ? cause.getMessage() : cause.toString());
        }
        return Violations.ofForm(text);
    }
}
