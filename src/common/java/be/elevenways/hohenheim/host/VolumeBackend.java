package be.elevenways.hohenheim.host;

import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.zenit.common.orm.field.EnumField;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * What a host's volume root can actually DO FOR THIS BUILD -- the one declaring home of
 * that vocabulary, carrying the capability facts placement decides on.
 *
 * AIDEV-NOTE: a capability is DETECTED (VolumeBackends probes the real data root), never
 * declared by an operator: an operator who ticks "btrfs" on an ext4 host would get a
 * workspace with no quota and a deploy that believes it snapshotted. {@link #NONE} is the
 * fail-closed member -- an unrecognised filesystem lands there and refuses placement,
 * which is why there is no default arm anywhere that switches on this.
 *
 * AIDEV-NOTE: {@link #isImplemented} is the fact that keeps the product honest. Until
 * 2026-08-23 ZFS and XFS project quota declared a quota they could not deliver: the picker
 * offered such a host, placement accepted it, and the FIRST deploy died in
 * {@code VolumeOperations} with "only btrfs". A capability the operations tier cannot
 * perform is not a capability, so {@link #supportsQuota}/{@link #supportsSnapshot} fold
 * implementedness in and every consumer reads ONE answer. The raw filesystem truth stays
 * on the member ({@link #filesystemEnforcesQuota}) only because the host page must tell
 * "this filesystem cannot enforce a quota" apart from "this one could, and we cannot drive
 * it yet". {@code VolumeOperationsTest} binds the flag to the OPERATIONS, not to a list
 * beside this enum.
 *
 * @author Jelle De Loecker
 * @since  0.1.0
 */
public enum VolumeBackend {

    /** Subvolume per volume, qgroup quota, read-only snapshots -- the implemented one. */
    BTRFS("btrfs", "Btrfs", true, true, true, "layer-group", "success"),

    /** Dataset per volume, {@code quota=} property, native snapshots -- NOT implemented. */
    ZFS("zfs", "ZFS", false, true, true, "database", "warning"),

    /** XFS with project quota ENABLED on the mount: quota yes, snapshot no -- NOT implemented. */
    XFS_PRJQUOTA("xfs_prjquota", "XFS project quota", false, true, false, "hard-drive", "warning"),

    /** A plain filesystem: no quota, no snapshot, and therefore no workspace or application. */
    NONE("none", "None", false, false, false, "circle-xmark", "destructive");

    private final String token;
    private final String displayName;
    private final boolean implemented;
    private final boolean quota;
    private final boolean snapshot;
    private final String icon;
    private final String color;

    VolumeBackend(String token, String displayName, boolean implemented, boolean quota,
                  boolean snapshot, String icon, String color) {
        this.token = token;
        this.displayName = displayName;
        this.implemented = implemented;
        this.quota = quota;
        this.snapshot = snapshot;
        this.icon = icon;
        this.color = color;
    }

    /** @return the stored column value */
    public @NonNull String token() {
        return this.token;
    }

    public @NonNull String displayName() {
        return this.displayName;
    }

    /** @return whether this build carries volume operations for the backend at all */
    public boolean isImplemented() {
        return this.implemented;
    }

    /**
     * @return whether the FILESYSTEM could enforce a per-volume size cap, implemented or
     *         not -- the discriminator between "no quota here" and "no driver here"
     */
    public boolean filesystemEnforcesQuota() {
        return this.quota;
    }

    /** @return whether a per-volume size cap can be ENFORCED by this build, not merely recorded */
    public boolean supportsQuota() {
        return this.implemented && this.quota;
    }

    /** @return whether this build can take a point-in-time copy without copying bytes */
    public boolean supportsSnapshot() {
        return this.implemented && this.snapshot;
    }

    /**
     * The stored tokens of every backend a quota-demanding workload may be placed on, so a
     * picker narrows on the SAME fact placement refuses on.
     */
    public static @NonNull List<String> quotaCapableTokens() {

        List<String> tokens = new ArrayList<>();

        for (VolumeBackend backend : values()) {
            if (backend.supportsQuota()) {
                tokens.add(backend.token());
            }
        }

        return tokens;
    }

    public @NonNull String icon() {
        return this.icon;
    }

    public @NonNull String color() {
        return this.color;
    }

    public @NonNull Microcopy label() {
        return Microcopy.of(this.token).withFilter("scope", "volume_backend");
    }

    /**
     * The schema-field builder carrying this vocabulary, so no stored option set can drift.
     * Callers finish it with their own label/help, never with more values.
     */
    public static EnumField.@NonNull Builder fieldBuilder(@NonNull String name) {

        EnumField.Builder builder = EnumField.builder(name);

        for (VolumeBackend backend : values()) {
            builder.value(backend.token(), value -> value
                .displayName(backend.displayName())
                .label(backend.label())
                .icon(backend.icon())
                .color(backend.color()));
        }

        return builder.defaultValue(NONE.token());
    }

    /**
     * @param  token the stored column value
     * @return the matching backend, or null when unknown (fail closed, never a default)
     */
    public static @Nullable VolumeBackend forToken(@Nullable String token) {

        if (token == null) {
            return null;
        }

        for (VolumeBackend backend : values()) {
            if (backend.token.equals(token)) {
                return backend;
            }
        }

        return null;
    }

    /** @return the stored backend of a host record's raw column value, {@link #NONE} when absent or unknown */
    public static @NonNull VolumeBackend resolve(@Nullable String token) {
        VolumeBackend backend = forToken(token);
        return backend != null ? backend : NONE;
    }
}
