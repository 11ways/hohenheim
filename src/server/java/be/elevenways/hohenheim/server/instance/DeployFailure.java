package be.elevenways.hohenheim.server.instance;

import be.elevenways.hohenheim.HohenheimMicrocopy;
import be.elevenways.hohenheim.HohenheimViolations;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.server.docker.ContainerHardening;
import be.elevenways.hohenheim.server.docker.DockerClient;
import be.elevenways.hohenheim.server.runtime.NetworkSubnets;
import be.elevenways.hohenheim.server.security.IsolationUnenforceable;
import be.elevenways.protoblast.common.Blast;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.util.BlastString;
import be.elevenways.zenit.common.operation.OperationRunModel;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.refusal.DomainRefusal;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.common.validation.Violations;
import be.elevenways.zenit.server.operation.OperationCall;
import be.elevenways.zenit.server.operation.OperationRun;
import be.elevenways.zenit.server.operation.OperationRuns;
import be.elevenways.zenit.server.operation.RecordCodes;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * Why a deploy of a workload failed, in words a person can act on: THE vocabulary every deploy surface answers a
 * failure with (its run, the panel, history, the API), as the reason of {@code instance_deploy_failed}, never the
 * failure's own text.
 *
 * AIDEV-NOTE: a member is recognised by the TYPE Hohenheim raised the failure as wherever Hohenheim raises it (the
 * hardening policy, an image the daemon will not give, a host range with no free network block, a host that cannot
 * enforce isolation, a daemon that never answered), and by the daemon's own words only where Docker answers nothing
 * but text (a taken port, its own address pools, a host out of memory). Anything else is {@link #UNKNOWN}, which
 * says so and where the server log has it: an exception's message is diagnostics, never copy. Found 2026-11-25 on
 * Starfleet, where a hardening refusal left the run reading only "This did not finish because of an error".
 *
 * @author Jelle De Loecker
 * @since  0.1.0
 */
public enum DeployFailure {

    /** The container safety rules refused the container's settings: a Hohenheim defect, never the app's. */
    HARDENING_REFUSED("deploy_failed_hardening", failure -> failure instanceof ContainerHardening.Refused),

    /** The daemon answered that the image cannot be had: no such repository or tag, no access, a failed pull. */
    IMAGE_UNAVAILABLE("deploy_failed_image", failure -> failure instanceof DockerClient.ImageUnavailableException),

    /** The host cannot enforce the workload's own network policy. */
    ISOLATION_UNENFORCEABLE("deploy_failed_isolation", failure -> failure instanceof IsolationUnenforceable),

    /** A port the workload publishes is bound on its host by something else. */
    PORT_TAKEN("deploy_failed_port_taken", daemonSaid("port is already allocated", "address already in use")),

    /** The host has no free private network for the workload. */
    NETWORKS_EXHAUSTED("deploy_failed_networks", daemonSaid("all predefined address pools have been fully subnetted",
        "could not find an available, non-overlapping").or(failure -> failure instanceof
            NetworkSubnets.NoFreeNetworkException)),

    /** The host could not give the workload the memory it starts with. */
    HOST_OUT_OF_MEMORY("deploy_failed_memory", daemonSaid("cannot allocate memory")),

    /** The host's daemon never answered. */
    DAEMON_UNREACHABLE("deploy_failed_unreachable", failure -> failure instanceof SocketException
        || failure instanceof SocketTimeoutException || failure instanceof UnknownHostException),

    /** Nothing above: said to be unknown, with where the server log has it. */
    UNKNOWN("deploy_failed_unknown", failure -> false);

    /** How deep a cause chain is walked; a deeper one is cut, never looped. */
    private static final int CAUSE_DEPTH = 8;

    private final @NonNull String key;
    private final @NonNull Predicate<Throwable> recognises;

    DeployFailure(@NonNull String key, @NonNull Predicate<Throwable> recognises) {
        this.key = key;
        this.recognises = recognises;
    }

    /** @return the first member recognising the failure or one of its causes, else {@link #UNKNOWN} */
    public static @NonNull DeployFailure of(@NonNull Throwable failure) {
        List<Throwable> chain = new ArrayList<>();
        for (Throwable link = failure; link != null && chain.size() < CAUSE_DEPTH && !chain.contains(link);
                link = link.getCause()) {
            chain.add(link);
        }
        for (DeployFailure member : values()) {
            if (chain.stream().anyMatch(member.recognises)) {
                return member;
            }
        }
        return UNKNOWN;
    }

    /** @return this failure's words, the reason a sentence about the failed deploy names */
    public @NonNull Microcopy reason() {
        return HohenheimMicrocopy.VIOLATIONS.of(this.key);
    }

    /** @return {@code instance_deploy_failed} for {@code name}, its reason this failure's words */
    public @NonNull Microcopy words(@NonNull Object name) {
        return failed(name, this.reason());
    }

    /**
     * The refusal a deploy of {@code instance} that failed with {@code failure} answers on every surface: its words,
     * the failure kept as its cause for the run it ends ({@link #inRun}) and logged redacted, never shown.
     *
     * @return the form-level refusal in this failure's words
     */
    public static @NonNull Violations refusal(@NonNull Row instance, @NonNull Throwable failure) {
        DeployFailure kind = of(failure);
        int instanceId = instance.get(InstanceModel.ID);
        Object name = instance.get(InstanceModel.NAME);
        Blast.log("INSTANCE: deploy of", name, "(#" + instanceId + ") failed,", kind, "-",
            ConsoleRedaction.reasonOf(InstanceModel.MODEL_ID, instanceId, failure));
        Violations refused = Violations.ofForm(kind.words(name));
        refused.initCause(failure);
        return refused;
    }

    /**
     * Runs a deploy of {@code app} inside the background run {@code call} ends, which then reads the deploy's own
     * words: a refusal's words become the run's, and a failure nobody recognised names the run, under whose code the
     * server log notes it.
     *
     * @throws DomainRefusal in the words the deploy was refused or failed with
     */
    public static void inRun(@NonNull OperationCall<?, ?> call, @NonNull Row app, @NonNull Runnable deploy) {
        try {
            deploy.run();
        } catch (Violations refused) {
            Throwable cause = refused.getCause();
            throw cause == null || of(cause) != UNKNOWN ? HohenheimViolations.runRefusal("deploy", refused)
                : unknownInRun(call, app, cause);
        } catch (DomainRefusal refused) {
            throw refused;
        } catch (RuntimeException failure) {
            throw of(failure) != UNKNOWN ? HohenheimViolations.runRefusal("deploy", refusal(app, failure))
                : unknownInRun(call, app, failure);
        }
    }

    /** @return the run's refusal of a failure nobody recognised, naming the run the log notes it under */
    private static @NonNull DomainRefusal unknownInRun(@NonNull OperationCall<?, ?> call, @NonNull Row app,
                                                       @NonNull Throwable failure) {
        int appId = app.get(InstanceModel.ID);
        String run = runCode(call);
        Blast.log("INSTANCE: run", run, "deployed", app.get(InstanceModel.NAME), "(#" + appId + ") and failed -",
            ConsoleRedaction.reasonOf(InstanceModel.MODEL_ID, appId, failure));
        Microcopy reason = run == null ? HohenheimMicrocopy.VIOLATIONS.of(UNKNOWN.key)
            : HohenheimMicrocopy.VIOLATIONS.of("deploy_failed_unknown_run").withArg("run", run);
        return HohenheimViolations.runRefusal("deploy", Violations.ofForm(failed(app.get(InstanceModel.NAME), reason)));
    }

    /** @return {@code instance_deploy_failed} naming the app and the reason */
    private static @NonNull Microcopy failed(@NonNull Object name, @NonNull Microcopy reason) {
        return HohenheimMicrocopy.VIOLATIONS.of("instance_deploy_failed").withArg("name", String.valueOf(name))
            .withArg("reason", reason);
    }

    /** @return the code of the run {@code call} is, as its caller reads it; null where none is readable */
    private static @Nullable String runCode(@NonNull OperationCall<?, ?> call) {
        OperationRun run = OperationRuns.ofInvocation(call.invocationId(), call.subjectAccess());
        return run == null ? null : codeOf(run, call.subjectAccess());
    }

    /** @return the run's code as {@code reader} reads it; null where it reads none */
    static @Nullable String codeOf(@NonNull OperationRun run, @NonNull AccessContext reader) {
        return RecordCodes.code(OperationRunModel.MODEL_ID, String.valueOf(run.id()), reader);
    }

    /** @return a recogniser of a refusal the daemon answered whose words hold one of these phrases */
    private static @NonNull Predicate<Throwable> daemonSaid(@NonNull String @NonNull ... phrases) {
        return failure -> {
            if (!(failure instanceof DockerClient.ApiException) || failure.getMessage() == null) {
                return false;
            }
            String said = BlastString.lower(failure.getMessage());
            for (String phrase : phrases) {
                if (said.contains(phrase)) {
                    return true;
                }
            }
            return false;
        };
    }
}
