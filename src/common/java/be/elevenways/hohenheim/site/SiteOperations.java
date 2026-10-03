package be.elevenways.hohenheim.site;

import be.elevenways.hohenheim.HohenheimIds;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.zenit.common.edit.FormSpec;
import be.elevenways.zenit.common.operation.Operation;
import be.elevenways.zenit.common.operation.OperationCommand;
import be.elevenways.zenit.common.orm.lease.LeaseKeys;
import be.elevenways.zenit.common.orm.command.CommandExecution;
import be.elevenways.zenit.common.operation.OperationFact;
import be.elevenways.zenit.common.operation.OperationGate;
import be.elevenways.zenit.common.operation.OperationInput;
import be.elevenways.zenit.common.operation.SubjectType;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.field.StringField;
import be.elevenways.zenit.common.text.Texts;
import be.elevenways.zenit.common.ui.Icon;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * The site operations the panels place: switching a site on and off, cloning it and rolling its release back.
 *
 * AIDEV-NOTE: enable and disable are two operations, one verb each (A-F13), where the admin used to place one
 * state-dependent toggle; each applies to one state only, so a row shows exactly one of them. Every gate here is
 * {@link OperationGate#open()}: who may act is the server-attached authorizer (SiteOperationHandlers), reach of the
 * site for the switches and installation administration for clone and rollback, so a surface outside the panels gets
 * the same rule the panels' audiences had.
 *
 * @author Jelle De Loecker
 * @since  0.9.0
 */
public final class SiteOperations {
    private static final LeaseKeys KEYS = LeaseKeys.declare(HohenheimIds.id("site_command"));
    private static final OperationCommand COMMAND = OperationCommand.serializedBy(KEYS,
        invocation -> invocation.subjectKeys().get(0));

    /** The subject of every site operation: one site record. */
    public static final SubjectType<Row> SITE = SubjectType.record(SiteModel.MODEL_ID);

    /** Puts a disabled site's hostnames into the route table; the enable route invariant may refuse it. */
    public static final Operation<Row, Void, Void> ENABLE = Operation.declare(HohenheimIds.id("enable_site"))
        .label(label("enable", "Enable"))
        .icon(Icon.of("power-off"))
        .one(SITE)
        .gate(OperationGate.open())
        .facts(OperationFact.REACHES_OUTSIDE)
        .command(COMMAND)
        .register();

    /** Takes an enabled site's hostnames out of the route table. */
    public static final Operation<Row, Void, Void> DISABLE = Operation.declare(HohenheimIds.id("disable_site"))
        .label(label("disable", "Disable"))
        .icon(Icon.of("power-off"))
        .one(SITE)
        .gate(OperationGate.open())
        .facts(OperationFact.REACHES_OUTSIDE, OperationFact.DESTRUCTIVE)
        .command(COMMAND)
        .register();

    /** The copy's name, the one thing a clone asks. */
    public static final StringField CLONE_NAME = StringField.builder("name")
        .label(Microcopy.of("clone_name").withFilter("scope", "site"))
        .required()
        .build();

    /** Copies a site and its hostnames under a new name; the copy starts disabled. Its result is the copy's id. */
    public static final Operation<Row, CloneInput, Integer> CLONE = Operation.declare(HohenheimIds.id("clone_site"))
        .label(label("clone", "Clone"))
        .icon(Icon.of("copy"))
        .one(SITE)
        .gate(OperationGate.open())
        .input(OperationInput.of(FormSpec.builder().add(CLONE_NAME).build(), CloneInput.class,
            values -> new CloneInput(Texts.trimmedOrNull(values.get(CLONE_NAME)))))
        .result(Integer.class)
        .command(COMMAND)
        .register();

    /** The clone's input. */
    public record CloneInput(@Nullable String name) {
    }

    /** Rolls a Docker-backed site back to its application's retained release, through the forward health gate. */
    public static final Operation<Row, Void, Void> ROLLBACK_RELEASE =
        Operation.declare(HohenheimIds.id("rollback_release"))
            .label(label("rollback", "Roll back"))
            .icon(Icon.of("clock-rotate-left"))
            .one(SITE)
            .gate(OperationGate.open())
            .facts(OperationFact.REACHES_OUTSIDE, OperationFact.DESTRUCTIVE)
            .command(COMMAND.execution(CommandExecution.OUTSIDE_TRANSACTION))
            .register();

    private SiteOperations() {
    }

    private static @NonNull Microcopy label(@NonNull String key, @NonNull String fallback) {
        return Microcopy.of(key).withFilter("scope", "site").withFallback(fallback);
    }
}
