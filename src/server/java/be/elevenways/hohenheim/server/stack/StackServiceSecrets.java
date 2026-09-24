package be.elevenways.hohenheim.server.stack;

import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.StackServiceModel;
import be.elevenways.hohenheim.model.StoredRows;
import be.elevenways.hohenheim.server.instance.InstanceVariables;
import be.elevenways.hohenheim.server.instance.OwnedInstances;
import be.elevenways.protoblast.common.Blast;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;

import java.util.Map;

/**
 * The backfill half of a stack service's SECRET environment: the deploy lane
 * ({@link StackInstances#deploy}) already writes the service's env as secret variable rows
 * of its owned instance and never into {@code instances.settings}; this seals the rows an
 * older controller wrote with a plaintext copy.
 *
 * AIDEV-NOTE: the author of the values is {@code stack_services.environment}, an encrypted
 * column; the instance settings map is a plain JSON column (zenit refuses encryption inside
 * a JSON sub-schema), so a copy there stored every credential of every stack service in the
 * clear. The release lane already detached its environment into secret variables
 * (ReleaseEngine.newInstanceRow); the stack tier now rides the same
 * {@code InstanceVariables.storeSecretEnvironment}, whose REPLACE semantics make the
 * service's env the whole variable set of its generated instance -- so a key dropped from
 * the service stops reaching the workload.
 *
 * @author Jelle De Loecker
 * @since  0.1.0
 */
final class StackServiceSecrets {

    private StackServiceSecrets() {
    }

    /**
     * The production backfill: every stack-service instance (trashed ones included -- their
     * settings are credentials at rest too) whose stored settings still carry a plaintext
     * environment gets it moved into secret variable rows, and the plaintext stripped.
     * Idempotent: a sealed row carries no environment and is skipped.
     *
     * AIDEV-NOTE: a boot reconcile and not a FrozenModel migration, for one reason: the
     * rewrite of {@code instances.settings}. That column is a kind-DISCRIMINATED SchemaField
     * ({@code schemaFrom("kind")}) whose sub-schema lives in server code (this package), and a
     * common-side migration can neither reference it nor reproduce its serialization from a
     * frozen shape without risking a subtly re-shaped blob on every stack instance. The
     * encryption itself would have been fine there (secret_value is column-bound, and the
     * keyring loads from ServerSettings in any server process); the settings rewrite is what
     * belongs to the live model. Running on every boot also seals a row an older controller
     * wrote during a rolling upgrade.
     *
     * @return how many instances were sealed in this pass
     */
    static int sealPlaintext() {
        int sealed = 0;
        for (Row instance : Models.get(InstanceModel.class).find().withTrashed()
                .where(InstanceModel.KIND.eq(StackServiceKind.ID.toString()))
                .all()) {
            Map<String, Object> settings = StackInstances.settingsOf(instance);
            if (!settings.containsKey(StackServiceKind.ENVIRONMENT_VARIABLES.getName())) {
                continue;
            }
            Integer instanceId = instance.get(InstanceModel.ID);
            Integer serviceId = instance.get(InstanceModel.GENERATED_FOR_ID);
            if (instanceId == null || serviceId == null || !StackServiceModel.MODEL_ID.toString()
                    .equals(instance.get(InstanceModel.GENERATED_FOR_MODEL))) {
                Blast.log("STACK: instance", instanceId, "carries a plaintext environment but"
                    + " names no owning stack service; left for an operator");
                continue;
            }
            try {
                OwnedInstances.inScope(StackInstances.SOURCE, StackServiceModel.MODEL_ID, serviceId,
                    () -> {
                        Map<String, String> environment =
                            InstanceVariables.detachEnvironment(settings);
                        new InstanceVariables().storeSecretEnvironment(instanceId, environment);
                        // Re-read right before the whole-row save: the row above may be
                        // stale by now, and a save writes every column it carries.
                        Row fresh = StoredRows.byId(Models.get(InstanceModel.class), instanceId);
                        if (fresh != null) {
                            fresh.set(InstanceModel.SETTINGS, settings);
                            Models.get(InstanceModel.class).save(fresh);
                        }
                        return null;
                    });
                sealed++;
            } catch (Exception failed) {
                Blast.log("STACK: could not move the plaintext environment of instance",
                    instanceId, "into secret variables; retried at the next boot -",
                    failed.getMessage());
            }
        }
        if (sealed > 0) {
            Blast.log("STACK: moved the plaintext environment of", sealed,
                "stack service instance(s) into encrypted secret variables");
        }
        return sealed;
    }
}
