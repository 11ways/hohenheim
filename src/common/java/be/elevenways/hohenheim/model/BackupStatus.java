package be.elevenways.hohenheim.model;

import be.elevenways.hohenheim.HohenheimMicrocopy;
import be.elevenways.zenit.common.orm.field.EnumField;
import be.elevenways.zenit.common.ui.ColorHue;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * The status vocabulary of every artifact a backup target holds (an instance backup, a database dump); each member
 * states whether an artifact in it can be restored.
 *
 * AIDEV-NOTE: ONE home for both ledgers. A row goes COMPLETE only after the target confirmed the committed
 * artifact's sha256, so the two models share the meaning as well as the words.
 *
 * @author Jelle De Loecker
 * @since  0.10.0
 */
public enum BackupStatus {

    /** The artifact is being built or uploaded. */
    UPLOADING("uploading", "Uploading", "cloud-arrow-up", ColorHue.BLUE),

    /** Committed on the target and verified end to end. */
    COMPLETE("complete", "Complete", "circle-check", ColorHue.GREEN),

    /**
     * Capture or upload failed; artifact removed, row kept as evidence. The one exception: a boot settle that could
     * not reach the target stamps FAILED with the possibly-surviving key named in the row's error.
     */
    FAILED("failed", "Failed", "circle-exclamation", ColorHue.RED);

    private final @NonNull String key;
    private final @NonNull String displayName;
    private final @NonNull String icon;
    private final @NonNull ColorHue color;

    BackupStatus(@NonNull String key, @NonNull String displayName, @NonNull String icon, @NonNull ColorHue color) {
        this.key = key;
        this.displayName = displayName;
        this.icon = icon;
        this.color = color;
    }

    /** The stored key this member round-trips through. */
    public @NonNull String key() {
        return this.key;
    }

    /** Whether an artifact in this status is committed and verified, so a restore may read it. */
    public boolean restorable() {
        return switch (this) {
            case COMPLETE -> true;
            case UPLOADING, FAILED -> false;
        };
    }

    /** Whether a row in this status is settled: no upload of it runs any more, so its artifact may be removed. */
    public boolean settled() {
        return switch (this) {
            case COMPLETE, FAILED -> true;
            case UPLOADING -> false;
        };
    }

    /** @return the member stored under this key, null for a blank or unknown one */
    public static @Nullable BackupStatus parse(@Nullable String key) {
        for (BackupStatus status : values()) {
            if (status.key.equals(key)) {
                return status;
            }
        }
        return null;
    }

    /** @return whether a ledger row in {@code status} naming {@code key} holds an artifact a restore may read */
    static boolean restorable(@Nullable String status, @Nullable String key) {
        BackupStatus parsed = parse(status);
        return parsed != null && parsed.restorable() && key != null && !key.isBlank();
    }

    /** @return the {@code status} column of a backup ledger, every member with its copy, failed by default */
    static @NonNull EnumField field() {
        EnumField.Builder builder = EnumField.builder("status");
        for (BackupStatus status : values()) {
            builder.value(status.key, value -> value.displayName(status.displayName).icon(status.icon)
                .label(HohenheimMicrocopy.BACKUP_STATUS.of(status.key)).color(status.color));
        }
        return builder.defaultValue(FAILED.key).label(HohenheimMicrocopy.HOHENHEIM_FIELD.of("status")).build();
    }
}
