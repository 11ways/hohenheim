package be.elevenways.hohenheim.instance;

import be.elevenways.hohenheim.HohenheimMicrocopy;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.zenit.common.orm.field.EnumField;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.Map;

/**
 * The DECLARED origin of a workload's image: where the driver gets it from, never
 * inferred from the alias shape -- a "prepared" alias and a catalog alias look
 * identical as strings, so a mis-declared origin is a silent misconfiguration, not a
 * naming convention violation. Declared on the kind's settings exactly like
 * {@code Egress}, so it is a stated fact on the workload.
 */
public enum ImageOrigin {

    /**
     * Fetched from the public simplestreams catalog by alias -- the existing behaviour,
     * the default. Linux system containers and cloud-init VMs declare this.
     */
    CATALOG("catalog", "Catalog", "cloud-arrow-down", false),

    /**
     * Already present in the target daemon's own image store, published there by an
     * operator (e.g. {@code incus image import}) -- never fetched from anywhere. The
     * general "prepared template" category (Windows via a Microsoft-signed prepared
     * image is one instance of this, not a special case in the driver).
     */
    PREPARED("prepared", "Prepared template", "hard-drive", false),

    /**
     * No image at all: the workload is created EMPTY ({@code source.type=none}) and an
     * operator installs its OS interactively from attached install media (a cdrom
     * device over a daemon-side ISO volume, see {@code InstanceDevices.attachCdrom}).
     * VM-only by contract -- a system container shares the host kernel and has no
     * firmware to boot media with; the driver refuses the combination by name.
     */
    INSTALL_MEDIA("install_media", "Install media (empty VM)", "compact-disc", true);

    /** The settings key every Incus kind declares this vocabulary under. */
    public static final String SETTING = "image_origin";

    private final @NonNull String key;
    private final @NonNull String displayName;
    private final @NonNull String icon;
    private final boolean vmOnly;

    ImageOrigin(@NonNull String key, @NonNull String displayName, @NonNull String icon, boolean vmOnly) {
        this.key = key;
        this.displayName = displayName;
        this.icon = icon;
        this.vmOnly = vmOnly;
    }

    /** The settings-stored key this value round-trips through. */
    public @NonNull String key() {
        return this.key;
    }

    public @NonNull Microcopy label() {
        return HohenheimMicrocopy.IMAGE_ORIGIN.of(this.key);
    }

    /** Whether only a VM can boot from this origin, so a system container never offers it. */
    public boolean vmOnly() {
        return this.vmOnly;
    }

    /** @return CATALOG for null/blank; throws by name for an unknown key */
    public static @NonNull ImageOrigin fromKey(@Nullable String key) {
        if (key == null || key.isBlank()) {
            return CATALOG;
        }
        for (ImageOrigin origin : values()) {
            if (origin.key.equals(key)) {
                return origin;
            }
        }
        throw new IllegalArgumentException("Unknown image origin key: " + key);
    }

    /** @return the origin a kind's settings declare under {@link #SETTING}; throws by name for an unknown key */
    public static @NonNull ImageOrigin of(@NonNull Map<String, ?> settings) {
        return fromKey(settings.get(SETTING) instanceof String key ? key : null);
    }

    /**
     * The schema-field builder carrying this vocabulary under {@link #SETTING}; InstanceKindFields finishes it.
     *
     * @param vm whether the kind is a VM, the only one offered the {@link #vmOnly()} origins
     */
    public static EnumField.@NonNull Builder fieldBuilder(boolean vm) {
        EnumField.Builder builder = EnumField.builder(SETTING);
        for (ImageOrigin origin : values()) {
            if (vm || !origin.vmOnly) {
                builder.value(origin.key, value -> value.displayName(origin.displayName)
                    .icon(origin.icon)
                    .label(origin.label()));
            }
        }
        return builder.defaultValue(CATALOG.key);
    }
}
