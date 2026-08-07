package be.elevenways.hohenheim.server.instance;

import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.InstanceTemplateModel;
import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.hohenheim.server.instance.InstanceService.Resolved;
import be.elevenways.hohenheim.server.runtime.AppUpdateSupport;
import be.elevenways.hohenheim.server.runtime.InstallSupport;
import be.elevenways.protoblast.common.Blast;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.zenit.common.orm.activity.ActivityLog;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.validation.Violations;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The in-place app update of a template-created instance: the template's declared
 * {@code update_script} runs INSIDE the running workload through the same function-
 * library lane as the install (the community-scripts update_script() capability,
 * adopted as a first-class action). Rides the {@code manage} capability funnel like
 * every other instance operation.
 */
public final class InstanceAppUpdates {

    /** Hard wall-clock cap on one update run (downloads included). */
    static final long UPDATE_TIMEOUT_MS = 10 * 60 * 1000;

    private final @NonNull InstanceService instances;

    public InstanceAppUpdates() {
        this(new InstanceService());
    }

    public InstanceAppUpdates(@NonNull InstanceService instances) {
        this.instances = instances;
    }

    /**
     * Run the template's update script inside the RUNNING workload.
     *
     * @return the run's bounded output (shown to the operator)
     * @throws Violations {@code app_update_no_script}, {@code app_update_unsupported},
     *         {@code app_update_requires_running}, {@code helper_not_implemented},
     *         {@code app_update_failed}
     */
    public @NonNull String update(int instanceId) {
        HohenheimAccess.requireOperationCapability(instanceId, HohenheimAccess.CONFIG);
        Resolved resolved = this.instances.resolve(instanceId);
        InstanceOperationGuard.requireOperable(resolved.row());

        String script = updateScriptOf(resolved.row());
        if (script == null) {
            throw refusal("app_update_no_script", resolved.row(), null);
        }
        CommunityScripts.requireVocabularyImplemented(script, "update script");
        if (!(resolved.runtime() instanceof AppUpdateSupport support)) {
            throw refusal("app_update_unsupported", resolved.row(), null);
        }
        if (!resolved.runtime().status(resolved.spec().handle()).running()) {
            throw refusal("app_update_requires_running", resolved.row(), null);
        }

        Map<String, String> env = new LinkedHashMap<>(resolved.spec().env());
        env.putAll(resolved.variables());
        if (CommunityScripts.requiresFunctionLibrary(script)
                || CommunityScripts.requiresFunctionLibrary(
                    templateInstallScript(resolved.row()))) {
            // The update body of a ct script does not repeat the source line -- the
            // library is part of its ambient contract, so it is prepended here.
            env.put(CommunityScripts.LIBRARY_MARKER, CommunityScripts.functionsLibrary());
            env.put("APPLICATION",
                String.valueOf((Object) resolved.row().get(InstanceModel.NAME)));
            script = "source /dev/stdin <<<\"$" + CommunityScripts.LIBRARY_MARKER + "\"\n"
                + "color\ncatch_errors\n" + script;
        }

        try {
            InstallSupport.InstallOutcome outcome = support.runAppUpdate(resolved.spec(),
                script, env, UPDATE_TIMEOUT_MS);
            if (!outcome.succeeded()) {
                ActivityLog.record(Models.get(InstanceModel.class), instanceId,
                    "app_update_failed", "exit " + outcome.exitCode());
                throw refusal("app_update_failed", resolved.row(),
                    new IOException("exit " + outcome.exitCode() + "\n" + outcome.outputTail()));
            }
            ActivityLog.record(Models.get(InstanceModel.class), instanceId,
                "app_updated", "in-place update script completed");
            Blast.log("INSTANCE: app update completed for", resolved.spec().handle());
            return outcome.outputTail();
        } catch (IOException error) {
            ActivityLog.record(Models.get(InstanceModel.class), instanceId,
                "app_update_failed", String.valueOf(error.getMessage()));
            throw refusal("app_update_failed", resolved.row(), error);
        }
    }

    /** Whether this instance's template declares an update script. */
    public static boolean hasUpdateScript(@NonNull Row instance) {
        return updateScriptOf(instance) != null;
    }

    private static @Nullable String updateScriptOf(@NonNull Row instance) {
        Row template = templateOf(instance);
        if (template == null) {
            return null;
        }
        String script = template.get(InstanceTemplateModel.UPDATE_SCRIPT);
        return script == null || script.isBlank() ? null : script;
    }

    private static @Nullable String templateInstallScript(@NonNull Row instance) {
        Row template = templateOf(instance);
        return template == null ? null : template.get(InstanceTemplateModel.INSTALL_SCRIPT);
    }

    private static @Nullable Row templateOf(@NonNull Row instance) {
        Object templateId = instance.get(InstanceModel.TEMPLATE_ID);
        return templateId instanceof Integer id
            ? Models.get(InstanceTemplateModel.class).findById(id) : null;
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
