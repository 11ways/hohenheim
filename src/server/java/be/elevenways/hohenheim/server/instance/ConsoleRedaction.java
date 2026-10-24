package be.elevenways.hohenheim.server.instance;

import be.elevenways.hohenheim.HohenheimViolations;
import be.elevenways.hohenheim.model.DatabaseEngineModel;
import be.elevenways.hohenheim.model.DatabaseModel;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.instance.VariableKind;
import be.elevenways.hohenheim.model.InstanceVariableModel;
import be.elevenways.hohenheim.model.PreviewDeploymentModel;
import be.elevenways.hohenheim.model.RuntimeImageModel;
import be.elevenways.hohenheim.model.StackModel;
import be.elevenways.hohenheim.model.StackServiceModel;
import be.elevenways.hohenheim.model.StoredRows;
import be.elevenways.protoblast.common.Blast;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.field.Field;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.orm.model.Schema;
import be.elevenways.zenit.common.security.KnownValueRedactor;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Tenant-safe redaction of an instance's console/log stream: the values the control plane KNOWS are secret for THIS
 * instance are handed to zenit's {@link KnownValueRedactor}, which replaces them with its placeholder before the text
 * reaches any ring, any viewer or any stored row.
 *
 * AIDEV-NOTE: the mechanism (by known value, never by pattern; the minimum length; the held-back tail across chunk
 * boundaries) is {@link KnownValueRedactor}'s. This class only answers which values are secret: the
 * {@code instance_variables} rows of kind {@code secret}, plus the same rows on the instance's environment, and for
 * every other owner of quoted output ({@link #secretsOfOwner}) the {@code .secret()} fields of its records plus the
 * secrets of the instances it owns. Its minimum length is the ONE floor of every redaction in Hohenheim (consoles,
 * build logs, release steps, failure reasons).
 *
 * AIDEV-NOTE: it FAILS CLOSED. A secret set that could not be read (the table unreachable, the owner's record gone,
 * an owner model with no rule here) is {@link Secrets#readable() unreadable}, and every redaction built on it masks
 * the whole text ({@link SecretRedactor}): an empty set would mean "nothing to hide", which is exactly the leak.
 *
 * AIDEV-NOTE: cloud-init user-data is {@code .secret()} as a whole DOCUMENT and is
 * deliberately NOT a redaction subject. It is content, not a value: matching a multi-KB YAML
 * blob against a console stream would never hit, while the secrets INSIDE it arrive through
 * {@code {{KEY}}} substitution from the very variables this class collects, so they are
 * covered by their own value.
 *
 * AIDEV-NOTE: an ADMIN sees the redacted stream too, and that is a decision, not an
 * oversight. Redaction happens at INGEST, on the single session ring that every viewer and
 * the stored log share; a viewer-dependent stream would mean keeping the raw text somewhere,
 * which is exactly the leak being closed (a stored secret is a leak even if every reader
 * redacts). The values themselves stay reachable at their own surface -- the variable rows,
 * whose {@code .secret()} declaration governs who may unmask them -- so nothing is lost
 * except the accident of a secret echoed into a log.
 */
public final class ConsoleRedaction {

    private ConsoleRedaction() {
    }

    /**
     * The secret values one owner's output may carry, or the fact that they could not be read.
     *
     * @param values   the accepted values (each at least {@link KnownValueRedactor#MIN_LENGTH} long)
     * @param readable false when the values could not be read: every redaction over it masks the whole text
     */
    public record Secrets(@NonNull Set<String> values, boolean readable) {

        /** No secret at all, known for certain. */
        public static final Secrets NONE = new Secrets(Set.of(), true);

        /** Values that could not be read; redaction over it fails closed. */
        public static final Secrets UNREADABLE = new Secrets(Set.of(), false);

        /** @return both sets' values; unreadable when either is */
        public @NonNull Secrets and(@NonNull Secrets other) {
            Set<String> union = new LinkedHashSet<>(this.values);
            union.addAll(other.values);
            return new Secrets(Collections.unmodifiableSet(union), this.readable && other.readable);
        }
    }

    /** Reads an owner's values into {@code into}, naming a too-short one in {@code refused}; throws when it cannot. */
    private interface Collector {
        void collect(@NonNull Set<String> into, @NonNull Set<String> refused);
    }

    /**
     * Every secret value that could legitimately appear in one instance's output: its own
     * secret variables plus the ones its environment contributes as the deploy baseline.
     * A value {@link KnownValueRedactor#accepts} refuses is dropped here, once, and the variables
     * carrying one are named in a warning (never their values): they are NOT redacted.
     *
     * @return the values; {@link Secrets#UNREADABLE} (logged) when the variable table could not be read
     */
    public static @NonNull Secrets secretsOf(int instanceId) {
        return read(InstanceModel.MODEL_ID, instanceId, (into, refused) -> collectInstance(into, refused, instanceId));
    }

    /**
     * Every secret value the quoted output of one owner record (a build's, a deploy's, a provisioning's) may carry.
     *
     * AIDEV-NOTE: the owner models are a closed list. An instance is {@link #secretsOf}; a preview carries its
     * application's secrets and its own instance's; a runtime image has NONE, deliberately, because it builds a
     * packaged context with no build arguments and no variables, so no value can be substituted into its text; a
     * stack carries its own and its services' secret fields plus the secrets of the instances those services own; a
     * database and an engine carry their own credentials, each other's (the engine a database lives on, the
     * databases an engine hosts) and the secrets of the instance each owns. Any other model has no rule and is
     * UNREADABLE, so its text is masked whole until a rule is added here.
     *
     * @return the values; {@link Secrets#UNREADABLE} (logged) when they could not be read or the model has no rule
     */
    public static @NonNull Secrets secretsOfOwner(@NonNull Identifier model, int ownerId) {
        if (InstanceModel.MODEL_ID.equals(model)) {
            return secretsOf(ownerId);
        }
        return read(model, ownerId, (into, refused) -> collectOwner(into, refused, model, ownerId));
    }

    /** A redactor over one instance's secret values ({@link #secretsOf}), for ONE stream of its output. */
    public static @NonNull SecretRedactor redactorFor(int instanceId) {
        return SecretRedactor.of(secretsOf(instanceId));
    }

    /**
     * Redaction of one WHOLE text (the {@code logs} tail read, a console backlog) as the one stream it is: a fresh
     * redactor is fed all of it, then flushed, so a multi-line secret is matched across its lines and a secret the
     * text was cut off in is masked. Never call it per line of a text: a line alone never shows a multi-line value.
     *
     * @return the redacted text; the placeholder for a non-empty text when the secrets could not be read
     */
    public static @NonNull String redactWhole(@NonNull String text, int instanceId) {
        return redactWhole(text, secretsOf(instanceId));
    }

    /** {@link #redactWhole(String, int)} through a secret set read once by the caller; null for null. */
    public static @Nullable String redactWhole(@Nullable String text, @NonNull Secrets secrets) {
        return SecretRedactor.of(secrets).redactWhole(text);
    }

    /**
     * A failure reason of one owner as it may be stored, logged or sent: daemon, git or build text can quote a secret
     * value, so it is redacted whole through the owner's secrets ({@link #secretsOfOwner}).
     *
     * @return null for null
     */
    public static @Nullable String redactedReason(@NonNull Identifier model, int ownerId, @Nullable String reason) {
        return reason == null ? null : redactWhole(reason, secretsOfOwner(model, ownerId));
    }

    /** @return {@link HohenheimViolations#reasonOf} of the failure, redacted as {@link #redactedReason} */
    public static @NonNull String reasonOf(@NonNull Identifier model, int ownerId, @NonNull Throwable failure) {
        String reason = redactedReason(model, ownerId, HohenheimViolations.reasonOf(failure));
        return reason != null ? reason : KnownValueRedactor.PLACEHOLDER;
    }

    private static @NonNull Secrets read(@NonNull Identifier model, int ownerId, @NonNull Collector collector) {
        Set<String> secrets = new LinkedHashSet<>();
        Set<String> refused = new TreeSet<>();
        try {
            collector.collect(secrets, refused);
        } catch (RuntimeException unreadable) {
            // Fail closed: a redaction that cannot know the values masks everything it would emit, and says so.
            Blast.log("REDACTION: could not read the secret values of", model + " #" + ownerId,
                "- its output is MASKED whole:", HohenheimViolations.reasonOf(unreadable));
            return Secrets.UNREADABLE;
        }
        if (!refused.isEmpty()) {
            Blast.log("REDACTION: secret values", refused, "of", model + " #" + ownerId, "are shorter than",
                KnownValueRedactor.MIN_LENGTH, "characters and are NOT redacted from its output");
        }
        return new Secrets(Collections.unmodifiableSet(secrets), true);
    }

    private static void collectInstance(@NonNull Set<String> into, @NonNull Set<String> refused, int instanceId) {
        // Trashed included: a destroyed record's logs still carry its environment secrets.
        Row instance = StoredRows.byId(Models.get(InstanceModel.class), instanceId);
        Integer environmentId = instance == null ? null : instance.get(InstanceModel.ENVIRONMENT_ID);
        if (environmentId != null) {
            collectVariables(into, refused, Models.get(InstanceVariableModel.class).findByEnvironmentId(environmentId));
        }
        collectVariables(into, refused, Models.get(InstanceVariableModel.class).findByInstanceId(instanceId));
    }

    private static void collectOwner(@NonNull Set<String> into, @NonNull Set<String> refused,
                                     @NonNull Identifier model, int ownerId) {
        if (PreviewDeploymentModel.MODEL_ID.equals(model)) {
            Row preview = required(Models.get(PreviewDeploymentModel.class), model, ownerId);
            for (Integer instanceId : new Integer[] {preview.get(PreviewDeploymentModel.APPLICATION_ID),
                    preview.get(PreviewDeploymentModel.INSTANCE_ID)}) {
                if (instanceId != null) {
                    collectInstance(into, refused, instanceId);
                }
            }
        } else if (RuntimeImageModel.MODEL_ID.equals(model)) {
            required(Models.get(RuntimeImageModel.class), model, ownerId);
        } else if (StackModel.MODEL_ID.equals(model)) {
            collectFields(into, refused, required(Models.get(StackModel.class), model, ownerId), StackModel.SCHEMA);
            for (Row service : Models.get(StackServiceModel.class).findByStackId(ownerId)) {
                collectFields(into, refused, service, StackServiceModel.SCHEMA);
                Integer serviceId = service.get(StackServiceModel.ID);
                if (serviceId != null) {
                    collectOwned(into, refused, StackServiceModel.MODEL_ID, serviceId);
                }
            }
        } else if (DatabaseModel.MODEL_ID.equals(model)) {
            Row database = required(Models.get(DatabaseModel.class), model, ownerId);
            collectFields(into, refused, database, DatabaseModel.SCHEMA);
            collectOwned(into, refused, model, ownerId);
            Integer engineId = database.get(DatabaseModel.ENGINE_ID);
            Row engine = engineId == null ? null : StoredRows.byId(Models.get(DatabaseEngineModel.class), engineId);
            if (engine != null) {
                collectFields(into, refused, engine, DatabaseEngineModel.SCHEMA);
                collectOwned(into, refused, DatabaseEngineModel.MODEL_ID, engineId);
            }
        } else if (DatabaseEngineModel.MODEL_ID.equals(model)) {
            collectFields(into, refused, required(Models.get(DatabaseEngineModel.class), model, ownerId),
                DatabaseEngineModel.SCHEMA);
            collectOwned(into, refused, model, ownerId);
            for (Row database : Models.get(DatabaseModel.class).findAll(DatabaseModel.ENGINE_ID, ownerId)) {
                collectFields(into, refused, database, DatabaseModel.SCHEMA);
            }
        } else {
            throw new IllegalStateException("no secret rule for owner model " + model);
        }
    }

    /** @return the owner's record, trashed included; throws (so the set is unreadable) when it is gone */
    private static @NonNull Row required(@NonNull Model model, @NonNull Identifier modelId, int ownerId) {
        Row row = StoredRows.byId(model, ownerId);
        if (row == null) {
            throw new IllegalStateException("the record " + modelId + " #" + ownerId + " is gone");
        }
        return row;
    }

    private static void collectOwned(@NonNull Set<String> into, @NonNull Set<String> refused,
                                     @NonNull Identifier model, int ownerId) {
        for (Row owned : OwnedInstances.ownedBy(model, ownerId)) {
            Integer instanceId = owned.get(InstanceModel.ID);
            if (instanceId != null) {
                collectInstance(into, refused, instanceId);
            }
        }
    }

    /** Every value of a field the schema declares {@code .secret()}, map values and list items included. */
    private static void collectFields(@NonNull Set<String> into, @NonNull Set<String> refused,
                                      @NonNull Row row, @NonNull Schema schema) {
        for (Field<?, ?> field : schema.getFields().values()) {
            if (field.isSecret()) {
                collectValue(into, refused, field.getName(), row.get(field.getName()));
            }
        }
    }

    private static void collectValue(@NonNull Set<String> into, @NonNull Set<String> refused,
                                     @NonNull String name, @Nullable Object value) {
        if (value instanceof Map<?, ?> map) {
            map.forEach((key, item) -> collectValue(into, refused, name + "." + key, item));
        } else if (value instanceof Collection<?> items) {
            for (Object item : items) {
                collectValue(into, refused, name, item);
            }
        } else if (value instanceof String text) {
            accept(into, refused, name, text);
        }
    }

    private static void collectVariables(@NonNull Set<String> into, @NonNull Set<String> refused,
                                         @NonNull Iterable<Row> rows) {
        for (Row row : rows) {
            // Fail closed: a kind nobody recognizes is redacted like a secret, from
            // whichever column carries its value.
            if (!VariableKind.of(row.get(InstanceVariableModel.KIND)).isSecret()) {
                continue;
            }
            String key = String.valueOf((Object) row.get(InstanceVariableModel.KEY));
            accept(into, refused, key, row.get(InstanceVariableModel.SECRET_VALUE));
            accept(into, refused, key, row.get(InstanceVariableModel.PLAIN_VALUE));
        }
    }

    private static void accept(@NonNull Set<String> into, @NonNull Set<String> refused,
                               @NonNull String name, @Nullable String value) {
        if (KnownValueRedactor.accepts(value)) {
            into.add(value);
        } else if (value != null && !value.isEmpty()) {
            refused.add(name);
        }
    }
}
