package be.elevenways.hohenheim.server.instance;

import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.InstanceTemplateVariableModel;
import be.elevenways.hohenheim.model.InstanceVariableModel;
import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.hohenheim.server.instance.variable.SecretVariableType;
import be.elevenways.hohenheim.server.instance.variable.VariableTypeHandler;
import be.elevenways.hohenheim.server.instance.variable.VariableTypes;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.validation.Violations;
import be.elevenways.zenit.server.security.SecureTokens;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads and writes the table-backed variable VALUES of an instance. Secret values ride
 * the statically declared encrypted column ONLY (the model's carrier invariant); a
 * declared-but-blank secret with {@code generate} on mints a SecureTokens value at
 * write time, so the Velocity forwarding-secret shape needs no operator-invented
 * entropy.
 */
public final class InstanceVariables {

    /**
     * Persist one value per declared template variable from an already-COERCED submit
     * map (typed coercion/validation happened against the template's variable form).
     * Existing rows for the same key are replaced -- reinstall and re-create flows must
     * never end with two rows answering for one key.
     */
    public void writeForInstance(int instanceId, @NonNull List<Row> declaredVariables,
                                 @NonNull Map<String, Object> coercedValues) {
        InstanceVariableModel model = Models.get(InstanceVariableModel.class);
        for (Row declared : declaredVariables) {
            String key = declared.get(InstanceTemplateVariableModel.KEY);
            VariableTypeHandler handler = handlerOf(declared);
            Map<String, Object> settings = settingsOf(declared);
            String value = handler.toStoredString(coercedValues.get(key));

            if (handler.isSecretValue() && (value == null || value.isEmpty())
                    && SecretVariableType.generates(settings)) {
                value = SecureTokens.randomToken(SecretVariableType.generateBytes(settings));
            }
            if (value == null || value.isEmpty()) {
                value = declared.get(InstanceTemplateVariableModel.DEFAULT_VALUE);
            }

            for (Row existing : model.find()
                    .where(InstanceVariableModel.INSTANCE_ID.eq(instanceId))
                    .where(InstanceVariableModel.KEY.eq(key)).all()) {
                model.delete(existing.get(InstanceVariableModel.ID));
            }

            Row row = model.createEmptyRow();
            row.set(InstanceVariableModel.INSTANCE_ID, instanceId);
            row.set(InstanceVariableModel.KEY, key);
            if (handler.isSecretValue()) {
                row.set(InstanceVariableModel.KIND, InstanceVariableModel.KIND_SECRET);
                row.set(InstanceVariableModel.SECRET_VALUE, value);
                InstanceConsoles.registerSecret(instanceId, value);
            } else {
                row.set(InstanceVariableModel.KIND, InstanceVariableModel.KIND_PLAIN);
                row.set(InstanceVariableModel.PLAIN_VALUE, value);
            }
            model.save(row);
        }
    }

    /**
     * Set (create or replace) ONE direct value on an instance or an environment --
     * the automation-API write lane. Exactly one owner id must be non-null (the
     * model's owner hook re-checks); existing rows for the same key are replaced so
     * one key never answers twice. Secret values enter the encrypted carrier only.
     *
     * @throws Violations {@code instance_not_permitted}, {@code variable_key_required},
     *         {@code variable_kind_unknown}, plus the model's carrier/owner invariants
     */
    public void setValue(@Nullable Integer instanceId, @Nullable Integer environmentId,
                         @NonNull String key, @NonNull String kind, @NonNull String value) {
        requireVariableAuthority(instanceId);
        if (key.isBlank()) {
            throw Violations.ofField("key", key,
                Microcopy.of("variable_key_required").withFilter("scope", "violations"));
        }
        if ((instanceId == null) == (environmentId == null)) {
            throw Violations.ofField("environment_id", environmentId,
                Microcopy.of("variable_one_owner").withFilter("scope", "violations"));
        }
        boolean secret = InstanceVariableModel.KIND_SECRET.equals(kind);
        if (!secret && !InstanceVariableModel.KIND_PLAIN.equals(kind)) {
            throw Violations.ofField("kind", kind,
                Microcopy.of("variable_kind_unknown").withFilter("scope", "violations")
                    .withArg("kind", kind));
        }
        InstanceVariableModel model = Models.get(InstanceVariableModel.class);
        for (Row existing : rowsForKey(model, instanceId, environmentId, key)) {
            model.delete(existing.get(InstanceVariableModel.ID));
        }
        Row row = model.createEmptyRow();
        row.set(InstanceVariableModel.INSTANCE_ID, instanceId);
        row.set(InstanceVariableModel.ENVIRONMENT_ID, environmentId);
        row.set(InstanceVariableModel.KEY, key);
        row.set(InstanceVariableModel.KIND, secret
            ? InstanceVariableModel.KIND_SECRET : InstanceVariableModel.KIND_PLAIN);
        if (secret) {
            row.set(InstanceVariableModel.SECRET_VALUE, value);
            if (instanceId != null) {
                // A secret written while a console streams must start being redacted NOW.
                InstanceConsoles.registerSecret(instanceId, value);
            }
        } else {
            row.set(InstanceVariableModel.PLAIN_VALUE, value);
        }
        model.save(row);
    }

    /**
     * Remove one direct value by key from an instance or an environment.
     *
     * @throws Violations {@code instance_not_permitted}
     * @return whether a row existed for the key
     */
    public boolean removeValue(@Nullable Integer instanceId, @Nullable Integer environmentId,
                               @NonNull String key) {
        requireVariableAuthority(instanceId);
        InstanceVariableModel model = Models.get(InstanceVariableModel.class);
        boolean removed = false;
        for (Row existing : rowsForKey(model, instanceId, environmentId, key)) {
            model.delete(existing.get(InstanceVariableModel.ID));
            removed = true;
        }
        return removed;
    }

    /**
     * THE capability gate on a direct variable write: {@code config} on the owning
     * instance, asked on the SERVICE so every surface answers to it.
     *
     * AIDEV-NOTE: a variable write IS a config write, and this is the enforcement the
     * automation API shipped without. {@link #applyToSettings} substitutes {@code {{KEY}}}
     * into {@code command} and {@code cloud_init}, so a written variable becomes part of
     * WHAT THE WORKLOAD RUNS at the next deploy -- and view/console/power all reach the
     * same endpoint through InstanceApi's shared visibility resolver, which checks
     * {@code view} alone. Requiring config moves no boundary: a config holder already
     * rewrites {@code settings.command} through ManageInstanceResource, so this enforces
     * the line HohenheimAccess.CONFIG already declares rather than drawing a new one. A
     * separate {@code variables} verb was rejected for exactly that reason -- it would add
     * a grant-matrix column carrying authority config already covers.
     *
     * AIDEV-NOTE: ENVIRONMENT-owned values (instanceId null) are deliberately NOT gated
     * here. They hang off a project, not an instance, and there is no instance capability
     * to ask about; PaasApi.visibleEnvironment (project membership AND an API key covering
     * the instance vocabulary) is their gate, and EnvironmentVariableResource is admin-only.
     *
     * @throws Violations {@code instance_not_permitted}
     */
    private static void requireVariableAuthority(@Nullable Integer instanceId) {
        if (instanceId != null) {
            HohenheimAccess.requireOperationCapability(instanceId, HohenheimAccess.CONFIG);
        }
    }

    private static @NonNull List<Row> rowsForKey(@NonNull InstanceVariableModel model,
                                                 @Nullable Integer instanceId,
                                                 @Nullable Integer environmentId,
                                                 @NonNull String key) {
        if (instanceId == null && environmentId == null) {
            return List.of();
        }
        var query = model.find().where(InstanceVariableModel.KEY.eq(key));
        if (instanceId != null) {
            query.where(InstanceVariableModel.INSTANCE_ID.eq(instanceId));
        } else {
            query.where(InstanceVariableModel.ENVIRONMENT_ID.eq(environmentId));
        }
        return query.all();
    }

    /**
     * Every variable value of one instance, secrets decrypted (the ORM read path owns
     * that): what env injection and file rendering consume. The instance's ENVIRONMENT
     * contributes its values as the baseline and the instance's own row for the same
     * key wins. Null values are omitted.
     */
    public @NonNull Map<String, String> valuesFor(int instanceId) {
        Map<String, String> values = new LinkedHashMap<>();
        Row instance = Models.get(InstanceModel.class).findById(instanceId);
        Integer environmentId = instance == null ? null
            : instance.get(InstanceModel.ENVIRONMENT_ID);
        if (environmentId != null) {
            collect(values, Models.get(InstanceVariableModel.class)
                .findByEnvironmentId(environmentId));
        }
        collect(values, Models.get(InstanceVariableModel.class).findByInstanceId(instanceId));
        return values;
    }

    /** The values of one environment alone (admin surfaces, tests). */
    public @NonNull Map<String, String> valuesForEnvironment(int environmentId) {
        Map<String, String> values = new LinkedHashMap<>();
        collect(values, Models.get(InstanceVariableModel.class)
            .findByEnvironmentId(environmentId));
        return values;
    }

    private static void collect(@NonNull Map<String, String> into, @NonNull Iterable<Row> rows) {
        for (Row row : rows) {
            String key = row.get(InstanceVariableModel.KEY);
            boolean secret = InstanceVariableModel.KIND_SECRET
                .equals(row.get(InstanceVariableModel.KIND));
            String value = secret
                ? row.get(InstanceVariableModel.SECRET_VALUE)
                : row.get(InstanceVariableModel.PLAIN_VALUE);
            if (key != null && value != null) {
                into.put(key, value);
            }
        }
    }

    /**
     * The deploy-facing view of an instance's settings with its variables applied:
     * variables merge into {@code environment_variables} (variable wins over the
     * baseline) and {@code {{KEY}}} tokens substitute inside {@code command} and
     * {@code cloud_init}, nowhere else.
     *
     * AIDEV-NOTE: substitution deliberately does NOT touch {@code image}/{@code tag}.
     * InstanceImagePolicy compares those columns verbatim against the approved
     * template's, so a substitutable image would let a variable value change the
     * effective image while the comparison still passes -- the exact laundering the
     * gate exists to refuse.
     *
     * AIDEV-NOTE: THREE layers, in this order: the derived baseline, then the settings'
     * own {@code environment_variables}, then the declared variables. Anything the
     * operator authored therefore overrides an attached database's derived family, which
     * is the SAME order the site lane documents at {@code SiteInstances}
     * ("injected database variables first, operator-authored ones override"). One rule
     * across both tiers, and the safe direction: attaching a database can never clobber a
     * value the workload already depends on.
     *
     * @param declared the instance's (and its environment's) own variable values
     * @param derived  values derived at resolve time and stored NOWHERE -- an attached
     *                 managed database's connection family
     */
    public @NonNull Map<String, Object> applyToSettings(@NonNull Map<String, Object> settings,
                                                        @NonNull Map<String, String> declared,
                                                        @NonNull Map<String, String> derived) {
        if (declared.isEmpty() && derived.isEmpty()) {
            return settings;
        }
        Map<String, Object> applied = new LinkedHashMap<>(settings);

        Map<String, String> env = new LinkedHashMap<>(derived);
        if (settings.get("environment_variables") instanceof Map<?, ?> baseline) {
            baseline.forEach((name, value) -> {
                if (name != null && value != null) {
                    env.put(String.valueOf(name), String.valueOf(value));
                }
            });
        }
        env.putAll(declared);
        applied.put("environment_variables", env);

        Map<String, String> substitutions = layered(derived, declared);
        if (settings.get("command") instanceof String command && !command.isEmpty()) {
            applied.put("command", substitute(command, substitutions));
        }
        // Cloud-init user-data is CONTENT like a config file, so it takes the same
        // {{KEY}} substitution (secret variables included) -- never image/tag, which
        // the image policy compares verbatim.
        if (settings.get("cloud_init") instanceof String cloudInit && !cloudInit.isEmpty()) {
            applied.put("cloud_init", substitute(cloudInit, substitutions));
        }
        return applied;
    }

    /**
     * The substitution view a deploy renders {@code {{KEY}}} tokens from: derived values
     * underneath, declared values on top.
     */
    public static @NonNull Map<String, String> layered(@NonNull Map<String, String> derived,
                                                       @NonNull Map<String, String> declared) {
        if (derived.isEmpty()) {
            return declared;
        }
        Map<String, String> layered = new LinkedHashMap<>(derived);
        layered.putAll(declared);
        return layered;
    }

    /** Replace every {@code {{KEY}}} token with its variable value (missing keys stay verbatim). */
    public static @NonNull String substitute(@NonNull String text,
                                             @NonNull Map<String, String> variables) {
        String result = text;
        for (Map.Entry<String, String> variable : variables.entrySet()) {
            result = result.replace("{{" + variable.getKey() + "}}", variable.getValue());
        }
        return result;
    }

    /** The declared variable's per-type settings map (empty when unset). */
    @SuppressWarnings("unchecked")
    static @NonNull Map<String, Object> settingsOf(@NonNull Row declared) {
        Object settings = declared.get(InstanceTemplateVariableModel.SETTINGS);
        return settings instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    /**
     * @throws Violations when the declared type has no registered handler
     */
    static @NonNull VariableTypeHandler handlerOf(@NonNull Row declared) {
        String type = declared.get(InstanceTemplateVariableModel.TYPE);
        VariableTypeHandler handler = VariableTypes.getHandler(type);
        if (handler == null) {
            throw Violations.ofField("type", type,
                Microcopy.of("variable_type_unknown").withFilter("scope", "violations")
                    .withArg("type", String.valueOf(type)));
        }
        return handler;
    }
}
