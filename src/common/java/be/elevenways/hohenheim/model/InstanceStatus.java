package be.elevenways.hohenheim.model;

import be.elevenways.protoblast.common.i18n.Microcopy;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * THE instance status vocabulary: every stored {@code instances.status} token with the facts
 * the rest of the code classifies it by.
 *
 * AIDEV-NOTE: the tokens stay the {@code InstanceModel.STATUS_*} string constants (stored
 * data and compile-time constants callers already use); each member DECLARES its token from
 * that constant, the ManagedDatabase.Engine binding. What moved here are the three
 * hand-kept lists that used to sit beside them: {@code isOperable} was a DENYLIST (a status
 * added later became operable by default) and the live-guest list that gates the isolation
 * sweeps failed OPEN the same way. Every fact is now an exhaustive switch without a default,
 * so a new member does not compile until it is classified, and an unknown stored token
 * resolves to no member at all and every predicate reads false (fail closed).
 *
 * @author Jelle De Loecker
 * @since 0.1.0
 */
public enum InstanceStatus {

    CREATED(InstanceModel.STATUS_CREATED, "Created", "circle", "gray"),
    STARTING(InstanceModel.STATUS_STARTING, "Starting", "hourglass-half", "blue"),
    RUNNING(InstanceModel.STATUS_RUNNING, "Running", "circle-play", "green"),
    STOPPED(InstanceModel.STATUS_STOPPED, "Stopped", "circle-stop", "orange"),
    ERROR(InstanceModel.STATUS_ERROR, "Error", "circle-exclamation", "red"),
    CAPTURING(InstanceModel.STATUS_CAPTURING, "Capturing", "camera", "blue"),
    RESTORING(InstanceModel.STATUS_RESTORING, "Restoring", "clock-rotate-left", "blue"),
    MIGRATING(InstanceModel.STATUS_MIGRATING, "Migrating", "arrow-right-arrow-left", "blue");

    private final String token;
    private final String displayName;
    private final String icon;
    private final String color;

    InstanceStatus(String token, String displayName, String icon, String color) {
        this.token = token;
        this.displayName = displayName;
        this.icon = icon;
        this.color = color;
    }

    /** The stored token. */
    public @NonNull String token() {
        return this.token;
    }

    /** The English display name the status field declares beside its label. */
    public @NonNull String displayName() {
        return this.displayName;
    }

    /** The badge icon name. */
    public @NonNull String icon() {
        return this.icon;
    }

    /** The badge colour token. */
    public @NonNull String color() {
        return this.color;
    }

    /** The translated label of the status badge. */
    public @NonNull Microcopy label() {
        return Microcopy.of(this.token).withFilter("scope", "instance_status");
    }

    /**
     * Whether another operator operation may start on a record in this status: false for
     * the in-flight statuses whose operation must finish first (capture, restore,
     * migration), which {@code InstanceOperationGuard.requireOperable} refuses on and the
     * admin surfaces ask to explain a dead control.
     */
    public boolean operable() {
        return switch (this) {
            case CREATED, STARTING, RUNNING, STOPPED, ERROR -> true;
            case CAPTURING, RESTORING, MIGRATING -> false;
        };
    }

    /**
     * Whether a workload in this status may be ON THE BRIDGE and must therefore be
     * kernel-verified by the isolation sweeps.
     *
     * AIDEV-NOTE: {@code error} is IN: a readiness timeout stamps error and stops nothing,
     * so the guest keeps running with its record claiming failure. The three protected
     * statuses are deliberately OUT although their guests can be live: a sweep's terminal
     * action is a stop, and stop is exactly what their declared contract refuses; their
     * exposure is bounded by the operation's own lifetime and the next sweep after it.
     * {@code created} and {@code stopped} name no guest at all.
     */
    public boolean liveGuest() {
        return switch (this) {
            case STARTING, RUNNING, ERROR -> true;
            case CREATED, STOPPED, CAPTURING, RESTORING, MIGRATING -> false;
        };
    }

    /**
     * Whether the RECORD still leaves something traffic could reach, so a site's
     * {@code instance} upstream resolves an address for it.
     *
     * AIDEV-NOTE: wider than {@link #liveGuest}: the protected statuses keep their
     * container running (a backup must not 503 the site it protects) and {@code error}'s
     * own words are "the daemon may disagree". Only the pair that claims no workload is
     * out, which is what lets the status reconciler's correction reach the proxy.
     */
    public boolean servable() {
        return switch (this) {
            case STARTING, RUNNING, ERROR, CAPTURING, RESTORING, MIGRATING -> true;
            case CREATED, STOPPED -> false;
        };
    }

    /**
     * THE token-to-member lookup.
     *
     * @return the member storing exactly this token, or null for an unknown token
     */
    public static @Nullable InstanceStatus forToken(@Nullable String token) {
        for (InstanceStatus status : values()) {
            if (status.token.equals(token)) {
                return status;
            }
        }
        return null;
    }

    /** The tokens of every member the predicate accepts, in declaration order. */
    public static @NonNull List<String> tokensWhere(@NonNull Predicate<InstanceStatus> fact) {
        List<String> tokens = new ArrayList<>();
        for (InstanceStatus status : values()) {
            if (fact.test(status)) {
                tokens.add(status.token);
            }
        }
        return List.copyOf(tokens);
    }
}
