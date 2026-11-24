package be.elevenways.hohenheim.instance;

import be.elevenways.hohenheim.HohenheimCapabilities;
import be.elevenways.hohenheim.HohenheimIds;
import be.elevenways.hohenheim.HohenheimMicrocopy;
import be.elevenways.hohenheim.HohenheimVerbs;
import be.elevenways.hohenheim.activity.OperationSentences;
import be.elevenways.hohenheim.model.InstanceVariableModel;
import be.elevenways.zenit.common.edit.FormSpec;
import be.elevenways.zenit.common.operation.Operation;
import be.elevenways.zenit.common.operation.OperationCommand;
import be.elevenways.zenit.common.operation.OperationFact;
import be.elevenways.zenit.common.operation.OperationInput;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.ui.Icon;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.List;

/**
 * An app's own variables read and written one key at a time: the list, a plain value set, a secret generated, a key
 * removed.
 *
 * AIDEV-NOTE: no operation here takes or answers a secret value (a secret is typed in a web form only, Jelle
 * 2026-11-01): the list names a secret and says only whether it holds a value, the set writes a plain value and
 * refuses a key that holds a secret rather than turning it plain, the generate has the server make a secret for a key
 * that holds nothing (never over a value nobody could get back) and answers its key alone, and the unset removes
 * either kind. The service's own config check (InstanceVariables) still answers for every write.
 *
 * @author Jelle De Loecker
 * @since  0.1.0
 */
public final class InstanceVariableOperations {

    private static final OperationCommand COMMAND = OperationCommand.perSubject();

    /**
     * One variable as its list shows it: a secret's value has no representation here.
     *
     * @param key   the variable's name
     * @param kind  what it is; an unknown stored kind reads as a secret
     * @param value a plain variable's value; null for a secret
     * @param set   whether a value is stored
     */
    public record Variable(@NonNull String key, @NonNull VariableKind kind, @Nullable String value, boolean set) {
    }

    /** @param variables the app's own variables, by key */
    public record Variables(@NonNull List<Variable> variables) {
    }

    /**
     * The set's input, its components named as the form's entries are.
     *
     * @param key         the variable's name
     * @param plain_value its plain value
     */
    public record SetInput(@Nullable String key, @Nullable String plain_value) {
    }

    /** @param key the variable's name */
    public record UnsetInput(@Nullable String key) {
    }

    /** @param key the variable's name */
    public record GenerateInput(@Nullable String key) {
    }

    /** The app's own variables: a plain one with its value, a secret one only as set or not. */
    public static final Operation<Row, Void, Variables> LIST_VARIABLES =
        Operation.declare(HohenheimIds.id("list_instance_variables"))
            .happened(OperationSentences.of("list_instance_variables"))
            .label(HohenheimMicrocopy.INSTANCE_VARIABLE.of("list"))
            .description(HohenheimMicrocopy.HOHENHEIM.of("list_instance_variables_description"))
            .icon(Icon.of("list"))
            .one(InstanceOperations.INSTANCE)
            .verb(HohenheimVerbs.LIST_VARIABLES)
            .gate(InstanceOperations.gate(HohenheimCapabilities.VIEW))
            .result(Variables.class)
            .facts(OperationFact.READ_ONLY)
            .register();

    /** Sets one plain variable on the app, which reaches its workload at the next deploy; answers the key. */
    public static final Operation<Row, SetInput, String> SET_VARIABLE =
        Operation.declare(HohenheimIds.id("set_instance_variable"))
            .happened(OperationSentences.of("set_instance_variable"))
            .label(HohenheimMicrocopy.INSTANCE_VARIABLE.of("set"))
            .description(HohenheimMicrocopy.HOHENHEIM.of("set_instance_variable_description"))
            .icon(Icon.of("pen"))
            .one(InstanceOperations.INSTANCE)
            .verb(HohenheimVerbs.SET_VARIABLE)
            .gate(InstanceOperations.gate(HohenheimCapabilities.CONFIG))
            .input(OperationInput.of(FormSpec.builder().add(InstanceVariableModel.KEY)
                    .add(InstanceVariableModel.PLAIN_VALUE).build(), SetInput.class,
                values -> new SetInput(values.get(InstanceVariableModel.KEY),
                    values.get(InstanceVariableModel.PLAIN_VALUE))))
            .result(String.class)
            .command(COMMAND)
            .register();

    /** Removes one variable, plain or secret, from the app; answers the key. */
    public static final Operation<Row, UnsetInput, String> UNSET_VARIABLE =
        Operation.declare(HohenheimIds.id("unset_instance_variable"))
            .happened(OperationSentences.of("unset_instance_variable"))
            .label(HohenheimMicrocopy.INSTANCE_VARIABLE.of("unset"))
            .description(HohenheimMicrocopy.HOHENHEIM.of("unset_instance_variable_description"))
            .icon(Icon.TRASH)
            .one(InstanceOperations.INSTANCE)
            .verb(HohenheimVerbs.UNSET_VARIABLE)
            .gate(InstanceOperations.gate(HohenheimCapabilities.CONFIG))
            .input(OperationInput.of(FormSpec.builder().add(InstanceVariableModel.KEY).build(), UnsetInput.class,
                values -> new UnsetInput(values.get(InstanceVariableModel.KEY))))
            .result(String.class)
            .facts(OperationFact.DESTRUCTIVE)
            .command(COMMAND)
            .register();

    /**
     * Has the server make a secret value for a key the app holds nothing under, which reaches its workload at the next
     * deploy; answers the key, never the value.
     */
    public static final Operation<Row, GenerateInput, String> GENERATE_SECRET =
        Operation.declare(HohenheimIds.id("generate_instance_secret"))
            .happened(OperationSentences.of("generate_instance_secret"))
            .label(HohenheimMicrocopy.INSTANCE_VARIABLE.of("generate_secret"))
            .description(HohenheimMicrocopy.HOHENHEIM.of("generate_instance_secret_description"))
            .icon(Icon.of("key"))
            .one(InstanceOperations.INSTANCE)
            .verb(HohenheimVerbs.GENERATE_SECRET)
            .gate(InstanceOperations.gate(HohenheimCapabilities.CONFIG))
            .input(OperationInput.of(FormSpec.builder().add(InstanceVariableModel.KEY).build(), GenerateInput.class,
                values -> new GenerateInput(values.get(InstanceVariableModel.KEY))))
            .result(String.class)
            .command(COMMAND)
            .register();

    private InstanceVariableOperations() {
    }
}
