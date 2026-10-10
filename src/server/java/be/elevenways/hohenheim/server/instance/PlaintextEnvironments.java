package be.elevenways.hohenheim.server.instance;

import be.elevenways.hohenheim.HohenheimViolations;
import be.elevenways.hohenheim.instance.InstanceKindFields;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.StoredRows;
import be.elevenways.protoblast.common.Blast;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.orm.query.criteria.Criteria;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * THE boot backfill that moves the plaintext environment an older controller stored in a generated instance's
 * settings into that instance's SECRET variables, and strips it: database engines, stack services and previews.
 *
 * AIDEV-NOTE: a boot reconcile and not a FrozenModel migration, for one reason: the rewrite of
 * {@code instances.settings}, a kind-DISCRIMINATED field whose sub-schema lives in server code, which a common-side
 * migration can neither reference nor reproduce without risking a re-shaped blob. Running on every boot also seals a
 * row an older controller wrote during a rolling upgrade. Idempotent: a sealed row carries no environment and is
 * skipped. Trashed rows are included, because their settings are credentials at rest too.
 *
 * @author Jelle De Loecker
 * @since 0.1.0
 */
public final class PlaintextEnvironments {

    private PlaintextEnvironments() {
    }

    /**
     * Seal every generated instance the criteria select.
     *
     * @param tag              the log prefix of the owning lane
     * @param noun             what the sealed instances are, for the summary line
     * @param source           the generated-row source the owning lane writes under
     * @param ownerModel       the model every owning record must be, null to accept the one each row names
     * @param storedSecretsWin whether secret rows the instance already holds win over the settings copy, as they do at
     *                         its deploy (a database engine's passwords); otherwise the settings copy is the whole set
     * @return how many instances were sealed in this pass
     */
    public static int seal(@NonNull String tag, @NonNull String noun, @NonNull Criteria instances,
                           @NonNull String source, @Nullable Identifier ownerModel, boolean storedSecretsWin) {
        int sealed = 0;
        for (Row instance : Models.get(InstanceModel.class).find().withTrashed().where(instances).all()) {
            Map<String, Object> settings = new LinkedHashMap<>();
            if (instance.get(InstanceModel.SETTINGS) instanceof Map<?, ?> stored) {
                stored.forEach((key, value) -> settings.put(String.valueOf(key), value));
            }
            Integer instanceId = instance.get(InstanceModel.ID);
            if (instanceId == null || !settings.containsKey(InstanceKindFields.ENVIRONMENT_VARIABLES)) {
                continue;
            }
            String ownerToken = instance.get(InstanceModel.GENERATED_FOR_MODEL);
            Identifier owner = ownerToken == null ? null : Identifier.tryParse(ownerToken);
            Integer ownerId = instance.get(InstanceModel.GENERATED_FOR_ID);
            if (owner == null || ownerId == null || (ownerModel != null && !ownerModel.equals(owner))) {
                Blast.log(tag + ": instance", instanceId, "carries a plaintext environment but names no owning"
                    + " record; left for an operator");
                continue;
            }
            try {
                OwnedInstances.inScope(source, owner, ownerId, () -> {
                    InstanceVariables variables = new InstanceVariables();
                    Map<String, String> environment =
                        new LinkedHashMap<>(InstanceVariables.detachEnvironment(settings));
                    if (storedSecretsWin) {
                        environment.putAll(variables.valuesFor(instanceId));
                    }
                    variables.storeSecretEnvironment(instanceId, environment);
                    // A configuration save: the operation-owned columns stay as stored.
                    Row fresh = StoredRows.byId(Models.get(InstanceModel.class), instanceId);
                    if (fresh != null) {
                        fresh.set(InstanceModel.SETTINGS, settings);
                        InstanceModel.saveConfiguration(fresh);
                    }
                    return null;
                });
                sealed++;
            } catch (Exception failed) {
                Blast.log(tag + ": could not move the plaintext environment of instance", instanceId,
                    "into secret variables; retried at the next boot -", HohenheimViolations.reasonOf(failed));
            }
        }
        if (sealed > 0) {
            Blast.log(tag + ": moved the plaintext environment of", sealed, noun + " into encrypted secret variables");
        }
        return sealed;
    }
}
