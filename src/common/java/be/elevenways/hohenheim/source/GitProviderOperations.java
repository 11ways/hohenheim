package be.elevenways.hohenheim.source;

import be.elevenways.hohenheim.HohenheimMicrocopy;
import be.elevenways.hohenheim.activity.OperationSentences;
import be.elevenways.hohenheim.HohenheimCapabilities;
import be.elevenways.hohenheim.HohenheimIds;
import be.elevenways.hohenheim.HohenheimVerbs;
import be.elevenways.hohenheim.model.GitProviderModel;
import be.elevenways.zenit.common.operation.Operation;
import be.elevenways.zenit.common.operation.OperationFact;
import be.elevenways.zenit.common.operation.OperationGate;
import be.elevenways.zenit.common.operation.SubjectType;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.ui.Icon;
import org.checkerframework.checker.nullness.qual.Nullable;
import be.elevenways.zenit.common.operation.OperationCommand;
import be.elevenways.zenit.common.orm.command.CommandExecution;

/**
 * The git provider operations: today only the connection test both provider entries place on a row.
 *
 * AIDEV-NOTE: the id is the legacy row action's own, {@code hohenheim:test_git_provider}. The gate is manage on the
 * provider, which an operator holds on every provider and a tenant on the ones it registered. A failed probe is an
 * outcome, never a refusal: each placement words it, because the operator surface names the client's reason and the
 * tenant surface must not (the tenant chose the URL, so the reason would be a port-scan oracle).
 *
 * @author Jelle De Loecker
 * @since  0.9.0
 */
public final class GitProviderOperations {

    /** The subject of every git provider operation: one provider record. */
    public static final SubjectType<Row> PROVIDER = SubjectType.record(GitProviderModel.REF);

    /**
     * Lists the provider's repositories through the real client.
     *
     * AIDEV-NOTE: a command, as every check the assistant's test tool routes is: a retried call (the same request)
     * answers the first probe's outcome instead of probing again. A command is no read: it takes the test verb, as
     * the other checks do, though the probe stores nothing.
     */
    public static final Operation<Row, Void, ConnectionTest> TEST_CONNECTION =
        Operation.declare(HohenheimIds.id("test_git_provider"))
            .happened(OperationSentences.of("test_git_provider"))
            .label(HohenheimMicrocopy.GIT_PROVIDER.of("test_connection"))
            .description(HohenheimMicrocopy.HOHENHEIM.of("test_git_provider_description"))
            .icon(Icon.of("plug-circle-check"))
            .one(PROVIDER)
            .verb(HohenheimVerbs.TEST)
            .gate(OperationGate.open().subjectCapability(HohenheimCapabilities.MANAGE))
            .result(ConnectionTest.class)
            .facts(OperationFact.REACHES_OUTSIDE)
            .command(OperationCommand.perSubject().execution(CommandExecution.OUTSIDE_TRANSACTION))
            .register();

    /**
     * One probe's outcome.
     *
     * @param repositories the repositories the provider listed, null when the probe failed
     * @param failure      the client's own reason, null when the probe succeeded; never shown to a tenant
     */
    public record ConnectionTest(@Nullable Integer repositories, @Nullable String failure) {
    }

    private GitProviderOperations() {
    }
}
