package be.elevenways.hohenheim.server.application;

import be.elevenways.hohenheim.model.DeployTrigger;
import be.elevenways.hohenheim.model.ReleaseOperationModel;
import be.elevenways.protoblast.common.Blast;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.security.Accountability;
import be.elevenways.zenit.common.security.PrincipalRef;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.Arrays;
import java.util.Map;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Who or what started a release operation: the deploy entry point's trigger and the principal its work is accountable
 * to ({@link Accountability#current()}, read when the entry point enters the scope).
 *
 * AIDEV-NOTE: the principal is recorded for EVERY trigger, so a SYSTEM deploy reached from a person's resize or database
 * attach still names whose request caused it. Only {@link DeployTrigger#pressedByPerson()} decides whether the row reads
 * "{trigger} by {actor}" or "{trigger}, requested by {actor}"; without a principal it reads the trigger alone.
 *
 * AIDEV-NOTE: a THREAD scope, the GeneratedRows.as shape. The entry points that name a trigger enter it
 * (ApplicationDeploys.deploy, ArtifactDeploys.run, ApplicationReleases.restore, ReleaseEngine.rollback) and
 * ReleaseEngine reads it where it creates the operation, several frames down on the same thread under the application's
 * operation lock. A release started with no scope records {@link DeployTrigger#SYSTEM} with NO principal and slogs its
 * call stack: refusing the deploy would turn missing bookkeeping into an outage, and a guessed principal could name
 * someone who never caused it.
 * @param principal null when the work is accountable to nobody (no identity was declared)
 *
 * @author Jelle De Loecker
 * @since  0.1.0
 */
public record DeployAttribution(@NonNull DeployTrigger trigger, @Nullable PrincipalRef principal) {

    private static final ThreadLocal<@Nullable DeployAttribution> ACTIVE = new ThreadLocal<>();

    /** @return the attribution of deploy work this thread does now on {@code trigger} */
    public static @NonNull DeployAttribution of(@NonNull DeployTrigger trigger) {
        return new DeployAttribution(trigger, Accountability.current().actorReference());
    }

    /** Runs {@code work} with every release operation it creates attributed to {@code trigger} and this thread. */
    public static <T> T supply(@NonNull DeployTrigger trigger, @NonNull Supplier<T> work) {
        DeployAttribution outer = ACTIVE.get();
        ACTIVE.set(of(trigger));
        try {
            return work.get();
        } finally {
            if (outer == null) {
                ACTIVE.remove();
            } else {
                ACTIVE.set(outer);
            }
        }
    }

    /** {@link #supply} for work without a result. */
    public static void run(@NonNull DeployTrigger trigger, @NonNull Runnable work) {
        supply(trigger, () -> {
            work.run();
            return null;
        });
    }

    /** @return the entered attribution, else {@link DeployTrigger#SYSTEM} attributed to nobody, logged with its callers */
    static @NonNull DeployAttribution current() {
        DeployAttribution active = ACTIVE.get();
        if (active != null) {
            return active;
        }
        String callers = Arrays.stream(Thread.currentThread().getStackTrace()).skip(2).limit(12)
            .map(StackTraceElement::toString).collect(Collectors.joining(" < "));
        Blast.slog("hohenheim.release.unattributed", Map.of("callers", callers));
        return new DeployAttribution(DeployTrigger.SYSTEM, null);
    }

    /** Stages the trigger and the principal pair onto a new operation row. */
    void stamp(@NonNull Row operation) {
        operation.set(ReleaseOperationModel.TRIGGER, this.trigger.word());
        ReleaseOperationModel.TRIGGERER.write(operation, this.principal);
    }
}
