package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.HohenheimMicrocopy;
import be.elevenways.hohenheim.HohenheimSlugs;
import be.elevenways.hohenheim.instance.InstanceScheduleOperations;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.zenit.common.edit.FormEntry;
import be.elevenways.zenit.common.edit.FormJsonSchema;
import be.elevenways.zenit.common.edit.JsonSchemaType;
import be.elevenways.zenit.common.edit.JsonValue;
import be.elevenways.zenit.common.edit.submit.JsonSchemaValidator.Keyword;
import be.elevenways.zenit.common.operation.Operation;
import be.elevenways.zenit.common.operation.OperationInput;
import be.elevenways.zenit.common.operation.OperationVerb;
import be.elevenways.zenit.common.operation.Operations;
import be.elevenways.zenit.common.operation.PlacementSurfaces;
import be.elevenways.zenit.common.operation.ZenitPlacementSurface;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.common.task.record.RecordScheduleModel;
import be.elevenways.zenit.common.task.record.RecordScheduleStepModel;
import be.elevenways.zenit.common.task.record.StepFailurePolicy;
import be.elevenways.zenit.common.validation.Violations;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;

/**
 * An app's schedule created and changed as operations through its panel resource ({@link PanelRecordWrites}), its
 * chain of steps given whole and written through the steps' own panel resource.
 *
 * AIDEV-NOTE: a step's action is named by the verb of the operation it runs ({@code start}, {@code back_up}), read
 * off the operations placed as schedule steps on an app, and its input entries ({@code command}, {@code note}) are
 * that operation's own; nothing here lists them. Every step is written as the Steps tab writes it, so its gates, the
 * action's own capability and the back up refusal for an app its source cannot back up decide at save, naming the
 * {@code steps} argument. Given steps replace the whole chain in the change's transaction.
 *
 * @author Jelle De Loecker
 * @since  0.1.0
 */
public final class ScheduleWrites {

    /** The argument naming the app a schedule acts on. */
    public static final String APP = "app";

    /** The argument giving the whole chain of steps. */
    public static final String STEPS = "steps";

    /** The entry of a step naming what it runs. */
    private static final String ACTION = "action";

    /** Per word, the operation a step of that action runs: the app's operations placed as schedule steps. */
    private static final Map<String, Operation<?, ?, ?>> ACTIONS = actions();

    /** The steps entry: an ordered list of steps, each an action, its delay, its failure policy and its input. */
    private static final JsonValue CHAIN = JsonValue.of(STEPS).schema(chainShape())
        .help(HohenheimMicrocopy.HELP.of("schedule_steps")).build();

    /** An app's schedule: its name, cron, zone and switch; its app chosen once, its steps given whole. */
    public static final PanelRecordWrites SCHEDULES = PanelRecordWrites.of("schedule", RecordScheduleModel.MODEL_ID,
            InstanceScheduleOperations.SCHEDULE, RecordScheduleModel.ID, HohenheimSlugs.INSTANCE_SCHEDULES)
        // The app is chosen once: a schedule never moves to another app.
        .parent(APP, HohenheimMicrocopy.HELP.of("schedule_app"), RecordScheduleModel.RECORD_ID,
            InstanceModel.MODEL_ID)
        .settings(List.of(RecordScheduleModel.NAME, RecordScheduleModel.CRON, RecordScheduleModel.TIMEZONE,
            RecordScheduleModel.ENABLED))
        .also(List.of(CHAIN), ScheduleWrites::chainChanged)
        .alsoOnCreate(ScheduleWrites::chainCreated)
        .build();

    private ScheduleWrites() {
    }

    /** @return per verb, the app's operation placed as a schedule step */
    private static @NonNull Map<String, Operation<?, ?, ?>> actions() {
        Map<String, Operation<?, ?, ?>> actions = new LinkedHashMap<>();
        for (Operation<?, ?, ?> operation : Operations.registry()) {
            OperationVerb verb = operation.verb();
            if (verb == null || !InstanceModel.MODEL_ID.equals(operation.noun())
                    || !PlacementSurfaces.isPlaced(ZenitPlacementSurface.SCHEDULE_STEP, operation.id())) {
                continue;
            }
            if (actions.putIfAbsent(verb.key(), operation) != null) {
                throw new IllegalStateException("Two schedule step actions of an app are both " + verb.key());
            }
        }
        return actions;
    }

    /** @return the chain's shape: each step's action, delay, failure policy, retries and its action's input entries */
    private static @NonNull Map<String, Object> chainShape() {
        Map<String, Object> step = new LinkedHashMap<>();
        step.put(ACTION, FormJsonSchema.property(JsonSchemaType.STRING, List.copyOf(ACTIONS.keySet()), null));
        Map<String, Object> delay = new LinkedHashMap<>(FormJsonSchema.property(JsonSchemaType.INTEGER, null, null));
        delay.put(Keyword.MINIMUM.token(), 0);
        step.put(RecordScheduleStepModel.OFFSET_SECONDS.getInputName(), delay);
        List<String> policies = new ArrayList<>();
        for (StepFailurePolicy policy : StepFailurePolicy.values()) policies.add(policy.storageKey());
        step.put(RecordScheduleStepModel.FAILURE_POLICY.getInputName(),
            FormJsonSchema.property(JsonSchemaType.STRING, policies, null));
        Map<String, Object> retries = new LinkedHashMap<>(FormJsonSchema.property(JsonSchemaType.INTEGER, null, null));
        retries.put(Keyword.MINIMUM.token(), 0);
        step.put(RecordScheduleStepModel.RETRY_LIMIT.getInputName(), retries);
        for (String entry : inputEntries()) {
            step.put(entry, FormJsonSchema.property(JsonSchemaType.STRING, null, null));
        }
        return FormJsonSchema.array(FormJsonSchema.object(step, List.of(ACTION)));
    }

    /** @return every input entry any step action takes, each once */
    private static @NonNull Set<String> inputEntries() {
        Set<String> entries = new LinkedHashSet<>();
        for (Operation<?, ?, ?> operation : ACTIONS.values()) {
            for (FormEntry entry : operation.input().form().entries()) entries.add(entry.name());
        }
        return entries;
    }

    /** Replaces the chain once the schedule's own settings are written, when the change gives steps. */
    private static @NonNull Runnable chainChanged(@NonNull Row schedule, OperationInput.@NonNull Values input,
                                                  @NonNull Set<String> sent, @NonNull AccessContext caller) {
        if (!sent.contains(STEPS)) return () -> { };
        List<Map<String, Object>> chain = chain(input.get(STEPS));
        Integer id = schedule.get(RecordScheduleModel.ID);
        return () -> replace(Objects.requireNonNull(id), chain, caller);
    }

    /** Writes the chain under the schedule a create made. */
    private static @NonNull Consumer<Integer> chainCreated(OperationInput.@NonNull Values input,
                                                           @NonNull Set<String> sent, @NonNull AccessContext caller) {
        List<Map<String, Object>> chain = chain(input.get(STEPS));
        return id -> replace(id, chain, caller);
    }

    /**
     * @return each step given as the Steps tab's form holds it, by stored names, in run order
     * @throws Violations naming {@code steps} for an input entry its action does not take
     */
    private static @NonNull List<Map<String, Object>> chain(@Nullable Object given) {
        List<Map<String, Object>> chain = new ArrayList<>();
        if (!(given instanceof List<?> steps)) return chain;
        Set<String> inputs = inputEntries();
        for (Object each : steps) {
            Map<?, ?> step = (Map<?, ?>) each;
            String word = String.valueOf(step.get(ACTION));
            Operation<?, ?, ?> operation = Objects.requireNonNull(ACTIONS.get(word), "the schema admits only actions");
            Set<String> taken = new LinkedHashSet<>();
            for (FormEntry entry : operation.input().form().entries()) taken.add(entry.name());
            Map<String, Object> stepInput = new LinkedHashMap<>();
            for (String entry : inputs) {
                Object value = step.get(entry);
                if (value == null) continue;
                if (!taken.contains(entry)) {
                    throw Violations.ofField(STEPS, entry, HohenheimMicrocopy.VIOLATIONS.of("schedule_step_input")
                        .withArg("entry", entry).withArg("action", word));
                }
                stepInput.put(entry, value);
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put(RecordScheduleStepModel.POSITION.getName(), chain.size());
            row.put(RecordScheduleStepModel.ACTION.getName(), operation.id().toString());
            if (!taken.isEmpty()) row.put(RecordScheduleStepModel.INPUT.getName(), stepInput);
            putGiven(row, step, RecordScheduleStepModel.OFFSET_SECONDS.getName(),
                RecordScheduleStepModel.OFFSET_SECONDS.getInputName());
            putGiven(row, step, RecordScheduleStepModel.FAILURE_POLICY.getName(),
                RecordScheduleStepModel.FAILURE_POLICY.getInputName());
            putGiven(row, step, RecordScheduleStepModel.RETRY_LIMIT.getName(),
                RecordScheduleStepModel.RETRY_LIMIT.getInputName());
            chain.add(row);
        }
        return chain;
    }

    private static void putGiven(@NonNull Map<String, Object> row, @NonNull Map<?, ?> step, @NonNull String stored,
                                 @NonNull String argument) {
        Object value = step.get(argument);
        if (value != null) row.put(stored, value);
    }

    /**
     * Removes the schedule's steps and writes the given ones, each as its Steps tab does.
     *
     * @throws Violations naming {@code steps} in the step's own words when one is refused
     */
    private static void replace(@NonNull Integer scheduleId, @NonNull List<Map<String, Object>> chain,
                                @NonNull AccessContext caller) {
        for (Row old : Models.get(RecordScheduleStepModel.class).findChain(scheduleId)) {
            PanelRecordWrites.deleteChild(HohenheimSlugs.INSTANCE_SCHEDULE_STEPS, old, caller);
        }
        for (Map<String, Object> step : chain) {
            try {
                PanelRecordWrites.createChild(HohenheimSlugs.INSTANCE_SCHEDULE_STEPS,
                    RecordScheduleStepModel.SCHEDULE_ID, scheduleId, step, caller);
            } catch (Violations refused) {
                throw Violations.ofField(STEPS, step.get(RecordScheduleStepModel.ACTION.getName()),
                    refused.all().getFirst().message());
            }
        }
    }
}
