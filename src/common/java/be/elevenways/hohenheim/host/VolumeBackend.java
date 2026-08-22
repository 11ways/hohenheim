package be.elevenways.hohenheim.host;

import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.zenit.common.orm.field.EnumField;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * What the filesystem under a host's volume root can actually DO -- the one declaring home
 * of that vocabulary, carrying the two capability facts placement decides on.
 *
 * AIDEV-NOTE: a capability is DETECTED (VolumeBackends probes the real data root), never
 * declared by an operator: an operator who ticks "btrfs" on an ext4 host would get a
 * workspace with no quota and a deploy that believes it snapshotted. {@link #NONE} is the
 * fail-closed member -- an unrecognised filesystem lands there and refuses placement,
 * which is why there is no default arm anywhere that switches on this.
 *
 * AIDEV-NOTE: the per-member OPERATIONS section 5 of the phase-0 design names (create,
 * setQuota, usage, snapshot, deleteSnapshot, destroy) is deliberately NOT here yet: it has
 * no consumer until the workspace runtime lands (brief 8), and a mechanism that ships
 * unwired is the FieldAccess lesson. The two facts below have a consumer TODAY (the
 * placement refusal and the host page), so they ship.
 *
 * @author Jelle De Loecker
 * @since  0.1.0
 */
public enum VolumeBackend {

    /** Subvolume per volume, qgroup quota, read-only snapshots. */
    BTRFS("btrfs", "Btrfs", true, true, "layer-group", "success"),

    /** Dataset per volume, {@code quota=} property, native snapshots. */
    ZFS("zfs", "ZFS", true, true, "database", "success"),

    /** XFS with project quota ENABLED on the mount: quota yes, snapshot no. */
    XFS_PRJQUOTA("xfs_prjquota", "XFS project quota", true, false, "hard-drive", "warning"),

    /** A plain filesystem: no quota, no snapshot, and therefore no workspace or application. */
    NONE("none", "None", false, false, "circle-xmark", "destructive");

    private final String token;
    private final String displayName;
    private final boolean quota;
    private final boolean snapshot;
    private final String icon;
    private final String color;

    VolumeBackend(String token, String displayName, boolean quota, boolean snapshot,
                  String icon, String color) {
        this.token = token;
        this.displayName = displayName;
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

    /** @return whether a per-volume size cap can be ENFORCED, not merely recorded */
    public boolean supportsQuota() {
        return this.quota;
    }

    /** @return whether a pre-deploy point-in-time copy is possible without copying bytes */
    public boolean supportsSnapshot() {
        return this.snapshot;
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
