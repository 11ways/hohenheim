package be.elevenways.hohenheim.server.runtime;

import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * The DECLARED origin of a workload's image: where the driver gets it from, never
 * inferred from the alias shape -- a "prepared" alias and a catalog alias look
 * identical as strings, so a mis-declared origin is a silent misconfiguration, not a
 * naming convention violation. Declared on the kind's settings exactly like
 * {@link Egress}, so it is a stated fact on the workload.
 */
public enum ImageOrigin {

    /**
     * Fetched from the public simplestreams catalog by alias -- the existing behaviour,
     * the default. Linux system containers and cloud-init VMs declare this.
     */
    CATALOG("catalog"),

    /**
     * Already present in the target daemon's own image store, published there by an
     * operator (e.g. {@code incus image import}) -- never fetched from anywhere. The
     * general "prepared template" category (Windows via a Microsoft-signed prepared
     * image is one instance of this, not a special case in the driver).
     */
    PREPARED("prepared"),

    /**
     * No image at all: the workload is created EMPTY ({@code source.type=none}) and an
     * operator installs its OS interactively from attached install media (a cdrom
     * device over a daemon-side ISO volume, see {@code InstanceDevices.attachCdrom}).
     * VM-only by contract -- a system container shares the host kernel and has no
     * firmware to boot media with; the driver refuses the combination by name.
     */
    INSTALL_MEDIA("install_media");

    private final @NonNull String key;

    ImageOrigin(@NonNull String key) {
        this.key = key;
    }

    /** The settings-stored key this value round-trips through. */
    public @NonNull String key() {
        return this.key;
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
}
