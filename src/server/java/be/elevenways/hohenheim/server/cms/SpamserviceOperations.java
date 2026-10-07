package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.activity.OperationSentences;
import be.elevenways.hohenheim.HohenheimIds;
import be.elevenways.hohenheim.server.spamservice.SpamserviceManager;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.spamservice.client.ServiceStatus;
import be.elevenways.zenit.common.operation.Operation;
import be.elevenways.zenit.common.operation.OperationFact;
import be.elevenways.zenit.common.operation.OperationGate;
import be.elevenways.zenit.common.ui.Icon;
import be.elevenways.zenit.common.validation.Violations;
import be.elevenways.zenit.server.operation.OperationHandlers;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * The local Spamservice installation's lifecycle verbs as operations: start, stop, restart and the connection test.
 *
 * AIDEV-NOTE: every verb DECLARES why it cannot run (its availability), because all four depend on runtime state the
 * installation form does not show: the admin toolbar draws that reason on the disabled control and the pipeline
 * refuses a POST with it, so a Stop beside a service that was never started explains itself instead of answering the
 * generic "the action failed". The ids are the former header actions' own.
 *
 * @author Jelle De Loecker
 * @since  0.10.0
 */
public final class SpamserviceOperations {

    private static final OperationGate OPERATOR = OperationGate.permission(HohenheimPanel.ACCESS);

    /** Starts the managed Spamservice process. */
    public static final Operation<Void, Void, Void> START = lifecycle("start", "play", false);

    /** Stops the managed Spamservice process. */
    public static final Operation<Void, Void, Void> STOP = lifecycle("stop", "stop", true);

    /** Restarts the managed Spamservice process. */
    public static final Operation<Void, Void, Void> RESTART = lifecycle("restart", "rotate", true);

    /** Calls the management status endpoint; the result is the status the service reports. */
    public static final Operation<Void, Void, String> TEST = Operation.declare(HohenheimIds.id("spamservice_test"))
        .happened(OperationSentences.of("spamservice_test"))
        .label(words("test"))
        .description(words("test_hint"))
        .icon(Icon.of("stethoscope"))
        .noSubject()
        .gate(OPERATOR)
        .result(String.class)
        .facts(OperationFact.REACHES_OUTSIDE)
        .command(CmsCommands.EXTERNAL)
        .register();

    static {
        OperationHandlers.attach(START)
            .availability((none, access) -> configurationReason(SpamserviceManager.get()))
            .handle(call -> {
                SpamserviceManager.get().start();
                return null;
            });
        OperationHandlers.attach(STOP)
            .availability((none, access) -> runningReason(SpamserviceManager.get()))
            .handle(call -> {
                SpamserviceManager.get().stop();
                return null;
            });
        OperationHandlers.attach(RESTART)
            .availability((none, access) -> configurationReason(SpamserviceManager.get()))
            .handle(call -> {
                SpamserviceManager.get().restart();
                return null;
            });
        OperationHandlers.attach(TEST)
            .availability((none, access) -> connectedReason(SpamserviceManager.get()))
            .handle(call -> testConnection());
    }

    private SpamserviceOperations() {
    }

    /** Loads the class, declaring the operations and attaching their handlers; idempotent. */
    public static void init() {
        // The static initializer did the work.
    }

    /** @return the spamservice scope's words for {@code key} */
    static @NonNull Microcopy words(@NonNull String key) {
        return Microcopy.of(key).withFilter("scope", "spamservice");
    }

    private static @NonNull Operation<Void, Void, Void> lifecycle(@NonNull String name, @NonNull String icon,
                                                                 boolean destructive) {
        Operation.Builder<Void, Void, Void> builder = Operation.declare(HohenheimIds.id("spamservice_" + name))
            .happened(OperationSentences.of("spamservice_" + name))
            .label(words(name))
            .description(words(name + "_hint"))
            .icon(Icon.of(icon))
            .noSubject()
            .gate(OPERATOR);
        if (destructive) {
            builder.facts(OperationFact.REACHES_OUTSIDE, OperationFact.DESTRUCTIVE);
        } else {
            builder.facts(OperationFact.REACHES_OUTSIDE);
        }
        return builder.command(CmsCommands.EXTERNAL).register();
    }

    /**
     * Calls the management status endpoint and NAMES whatever went wrong.
     *
     * @throws Violations carrying the service's own failure text
     */
    private static @NonNull String testConnection() {
        try {
            ServiceStatus status = SpamserviceManager.get().requireClient().status();
            return String.valueOf(status.status());
        } catch (RuntimeException failure) {
            // The clientlib's message IS the diagnosis (connection refused, 401, a body the
            // service refused); swallowing it into cms.action.failed is what left the operator
            // with nothing to act on.
            throw Violations.ofForm(words("test_failed").withArg("reason", reasonOf(failure)));
        }
    }

    /** Why the installation itself cannot act, or null when it is configured and enabled. */
    static @Nullable Microcopy configurationReason(@NonNull SpamserviceManager manager) {
        SpamserviceManager.Snapshot snapshot = manager.snapshot();
        if (!snapshot.configured()) {
            return words("not_configured");
        }
        if (!snapshot.enabled()) {
            return words("not_enabled");
        }
        return null;
    }

    /** Why nothing can be stopped, or null when a managed process is actually alive. */
    static @Nullable Microcopy runningReason(@NonNull SpamserviceManager manager) {
        Microcopy configuration = configurationReason(manager);
        if (configuration != null) {
            return configuration;
        }
        return manager.snapshot().pid() == null ? words("not_running") : null;
    }

    /** Why the management API cannot be called, or null when a client is installed. */
    static @Nullable Microcopy connectedReason(@NonNull SpamserviceManager manager) {
        Microcopy running = runningReason(manager);
        if (running != null) {
            return running;
        }
        return manager.client() == null ? words("not_connected") : null;
    }

    private static @NonNull String reasonOf(@NonNull Throwable failure) {
        String message = failure.getMessage();
        return message == null || message.isBlank() ? failure.getClass().getSimpleName() : message;
    }
}
