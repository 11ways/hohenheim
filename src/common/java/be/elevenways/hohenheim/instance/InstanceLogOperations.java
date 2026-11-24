package be.elevenways.hohenheim.instance;

import be.elevenways.hohenheim.HohenheimCapabilities;
import be.elevenways.hohenheim.HohenheimIds;
import be.elevenways.hohenheim.HohenheimMicrocopy;
import be.elevenways.hohenheim.HohenheimSources;
import be.elevenways.hohenheim.HohenheimVerbs;
import be.elevenways.hohenheim.activity.OperationSentences;
import be.elevenways.hohenheim.model.BuildOperationModel;
import be.elevenways.hohenheim.model.ReleaseOperationModel;
import be.elevenways.zenit.common.edit.FormSpec;
import be.elevenways.zenit.common.operation.Operation;
import be.elevenways.zenit.common.operation.OperationFact;
import be.elevenways.zenit.common.operation.OperationGate;
import be.elevenways.zenit.common.operation.OperationInput;
import be.elevenways.zenit.common.operation.SubjectType;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.field.IntegerField;
import be.elevenways.zenit.common.ui.Icon;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.time.Instant;
import java.util.List;

/**
 * The reads of what an app printed and what its deploys did: its live log, its deploy history, a build's log and a
 * deploy's step log.
 *
 * AIDEV-NOTE: each reads through the home the panel reads (InstanceConsoles.tail, the Deploys tab's history, the
 * stored logs), so a read here shows exactly what the reader's panel shows: a deploy's step log names hosts,
 * registries and paths, so it is an operator read, while a build's log is the tenant's own build output.
 *
 * @author Jelle De Loecker
 * @since  0.1.0
 */
public final class InstanceLogOperations {

    /** The subject of a deploy's read: one release operation record. */
    public static final SubjectType<Row> DEPLOY = SubjectType.record(ReleaseOperationModel.REF);

    /** The subject of a build's read: one build operation record. */
    public static final SubjectType<Row> BUILD = SubjectType.record(BuildOperationModel.REF);

    /** How many of the newest lines of the app's log to read; the daemon keeps the rest. */
    public static final IntegerField LINES = IntegerField.builder("lines")
        .label(HohenheimMicrocopy.HOHENHEIM_FIELD.of("log_lines"))
        .help(HohenheimMicrocopy.HELP.of("log_lines"))
        .build();

    /** @param lines how many of the newest lines to read; null for the default */
    public record LogInput(@Nullable Integer lines) {
    }

    /** The app's live log: the newest lines its workload printed, every secret value of the app masked. */
    public static final Operation<Row, LogInput, String> READ_LOG =
        Operation.declare(HohenheimIds.id("read_instance_log"))
            .happened(OperationSentences.of("read_instance_log"))
            .label(HohenheimMicrocopy.INSTANCE.of("read_log"))
            .description(HohenheimMicrocopy.HOHENHEIM.of("read_instance_log_description"))
            .icon(Icon.of("terminal"))
            .one(InstanceOperations.INSTANCE)
            .verb(HohenheimVerbs.READ_LOG)
            .gate(InstanceOperations.gate(HohenheimCapabilities.CONSOLE))
            .input(OperationInput.of(FormSpec.builder().add(LINES).build(), LogInput.class,
                values -> new LogInput(values.get(LINES))))
            .result(String.class)
            .facts(OperationFact.READ_ONLY, OperationFact.REACHES_OUTSIDE)
            .register();

    /**
     * One row of an app's deploy history as its reader may read it.
     *
     * @param code    the deploy's or the build's own code
     * @param kind    what the row is (a release, a rollback, a build), in the reader's words
     * @param status  how it ended or where it stands, in the reader's words
     * @param commit  the commit or the branch it was built from; empty when it names none
     * @param trigger who or what started it, in the reader's words; empty when it recorded none
     * @param started when it started; null when it did not
     * @param length  how long it ran, in the reader's words; empty when it did not end
     * @param failure why it failed, as the reader may read it; empty when it did not fail
     */
    public record Deploy(@NonNull String code, @NonNull String kind, @NonNull String status, @NonNull String commit,
                         @NonNull String trigger, @Nullable Instant started, @NonNull String length,
                         @NonNull String failure) {
    }

    /**
     * One page of an app's deploy history, newest first.
     *
     * @param deploys the page's rows
     * @param page    the page read, clamped to the history
     * @param pages   how many pages the history holds
     */
    public record Deploys(@NonNull List<Deploy> deploys, int page, int pages) {
    }

    /** @param page which page of the history to read; null for the newest */
    public record DeploysInput(@Nullable Integer page) {
    }

    /** Which page of the deploy history to read. */
    public static final IntegerField PAGE = IntegerField.builder("page")
        .label(HohenheimMicrocopy.HOHENHEIM_FIELD.of("history_page"))
        .help(HohenheimMicrocopy.HELP.of("history_page"))
        .build();

    /** The app's deploys and the builds that never became one, newest first: what its Deploys tab lists. */
    public static final Operation<Row, DeploysInput, Deploys> LIST_DEPLOYS =
        Operation.declare(HohenheimIds.id("list_instance_deploys"))
            .happened(OperationSentences.of("list_instance_deploys"))
            .label(HohenheimMicrocopy.INSTANCE.of("list_deploys"))
            .description(HohenheimMicrocopy.HOHENHEIM.of("list_instance_deploys_description"))
            .icon(Icon.of("rocket"))
            .one(InstanceOperations.INSTANCE)
            .verb(HohenheimVerbs.LIST_DEPLOYS)
            .gate(InstanceOperations.gate(HohenheimCapabilities.VIEW))
            .input(OperationInput.of(FormSpec.builder().add(PAGE).build(), DeploysInput.class,
                values -> new DeploysInput(values.get(PAGE))))
            .result(Deploys.class)
            .facts(OperationFact.READ_ONLY)
            .register();

    /**
     * What one build printed, as it stored it: the tenant's own checkout and build, which its Deploys tab shows him.
     * Its readers are the source's, the readers of the record it built for.
     */
    public static final Operation<Row, Void, String> READ_BUILD_LOG =
        Operation.declare(HohenheimIds.id("read_build_log"))
            .happened(OperationSentences.of("read_build_log"))
            .label(HohenheimMicrocopy.BUILD_OPERATION.of("read_log"))
            .description(HohenheimMicrocopy.HOHENHEIM.of("read_build_log_description"))
            .icon(Icon.of("file-lines"))
            .one(BUILD)
            .verb(HohenheimVerbs.READ_LOG)
            .gate(OperationGate.open())
            .result(String.class)
            .facts(OperationFact.READ_ONLY)
            .register();

    /** The step log of one deploy: daemon and host text, which only the operator reads. */
    public static final Operation<Row, Void, String> READ_DEPLOY_LOG =
        Operation.declare(HohenheimIds.id("read_deploy_log"))
            .happened(OperationSentences.of("read_deploy_log"))
            .label(HohenheimMicrocopy.RELEASE_OPERATION.of("read_log"))
            .description(HohenheimMicrocopy.HOHENHEIM.of("read_deploy_log_description"))
            .icon(Icon.of("file-lines"))
            .one(DEPLOY)
            .verb(HohenheimVerbs.READ_LOG)
            .gate(HohenheimSources.OPERATOR)
            .result(String.class)
            .facts(OperationFact.READ_ONLY)
            .register();

    private InstanceLogOperations() {
    }
}
