package be.elevenways.hohenheim.model;

import be.elevenways.hohenheim.HohenheimMicrocopy;
import be.elevenways.hohenheim.WordedState;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.zenit.common.ui.BadgeVariant;
import org.checkerframework.checker.nullness.qual.NonNull;

/**
 * Where one recorded operation attempt stands (an artifact upload, a build, a release, a stack deploy): the one status
 * vocabulary those records share, each member worded once under {@code operation_status}.
 *
 * AIDEV-NOTE: a record stores a SUBSET of these through its {@link OperationLifecycle}, which also maps the one stored
 * spelling that differs (stack deployments store "success" for {@link #SUCCEEDED}). Never compare a stored status with
 * a token here directly: ask the record's lifecycle.
 *
 * @author Jelle De Loecker
 * @since  0.10.0
 */
public enum OperationStatus implements WordedState {

    /** Recorded, no work started yet. */
    PENDING("pending", BadgeVariant.SECONDARY, "clock", true),
    /** Work is under way. */
    RUNNING("running", BadgeVariant.INFO, "rotate", true),
    /** A release's candidate instance is created and deploying. */
    DEPLOYING("deploying", BadgeVariant.INFO, "rotate", true),
    /** A release's candidate runs; the health gate interrogates it. */
    PROBING("probing", BadgeVariant.INFO, "stethoscope", true),
    /** A release's probe passed; the roles are being flipped. */
    SWITCHING("switching", BadgeVariant.INFO, "shuffle", true),
    /** A release switched traffic; the superseded release drains before its stop and reclaim. */
    DRAINING("draining", BadgeVariant.INFO, "hourglass-half", true),
    SUCCEEDED("succeeded", BadgeVariant.SUCCESS, "check", false),
    FAILED("failed", BadgeVariant.DESTRUCTIVE, "circle-xmark", false),
    /** Found in flight at boot; recovery settled the runtime state and stamped this. */
    INTERRUPTED("interrupted", BadgeVariant.WARNING, "power-off", false),
    /** A build ran out of its time budget. */
    TIMED_OUT("timed_out", BadgeVariant.WARNING, "clock", false),
    /** A build ran out of its disk budget. */
    QUOTA_EXCEEDED("quota_exceeded", BadgeVariant.WARNING, "gauge-high", false),
    /** A build was refused before it started. */
    REFUSED("refused", BadgeVariant.DESTRUCTIVE, "ban", false);

    private final String token;
    private final BadgeVariant variant;
    private final String icon;
    private final boolean inFlight;

    OperationStatus(@NonNull String token, @NonNull BadgeVariant variant, @NonNull String icon, boolean inFlight) {
        this.token = token;
        this.variant = variant;
        this.icon = icon;
        this.inFlight = inFlight;
    }

    /** @return the stored spelling, unless a lifecycle maps another */
    @Override
    public @NonNull String token() {
        return this.token;
    }

    @Override
    public @NonNull BadgeVariant variant() {
        return this.variant;
    }

    @Override
    public @NonNull Microcopy label() {
        return HohenheimMicrocopy.OPERATION_STATUS.of(this.token);
    }

    /** @return the plumage icon beside the words */
    public @NonNull String icon() {
        return this.icon;
    }

    /** @return whether a controller is, or was until a crash, still driving an attempt in this status */
    public boolean inFlight() {
        return this.inFlight;
    }
}
