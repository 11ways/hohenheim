package be.elevenways.hohenheim.server.backup;

import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The versioned manifest every instance backup carries: everything a restore needs to
 * rebuild the instance as a NEW record -- kind, full settings (variables and secret
 * values included, which is why the ARCHIVE is encrypted whole), resolved image
 * identity, ownership subjects, port semantics and the checksummed volume inventory.
 *
 * @param version           manifest format version (this build reads {@link #FORMAT_VERSION})
 * @param created           ISO instant of the capture
 * @param controllerVersion the controller build that wrote it
 * @param instanceName      the source instance's name
 * @param kind              the instance kind token ({@code hohenheim:docker_container})
 * @param settings          the FULL instance settings map, secrets included
 * @param imageReference    the image reference the workload ran
 * @param imageId           the daemon's immutable image id, when known
 * @param ownership         the packed manage-subject set at capture time (informational:
 *                          restore-to-new charges the RESTORING actor's owner derivation)
 * @param containerPort     the declared container port, or null
 * @param portProtocol      the port's protocol ({@code tcp}; UDP arrives with pre-allocation)
 * @param volumes           per-volume payload inventory with plaintext checksums
 */
public record BackupManifest(int version,
                             @NonNull String created,
                             @NonNull String controllerVersion,
                             @NonNull String instanceName,
                             @NonNull String kind,
                             @NonNull Map<String, Object> settings,
                             @NonNull String imageReference,
                             @Nullable String imageId,
                             @NonNull String ownership,
                             @Nullable Integer containerPort,
                             @NonNull String portProtocol,
                             @NonNull List<VolumeEntry> volumes) {

    /** The one manifest format this build writes and reads. */
    public static final int FORMAT_VERSION = 1;

    /** One captured volume payload: archive entry {@code volumes/<name>.tar}. */
    public record VolumeEntry(@NonNull String name, @NonNull String containerPath,
                              @NonNull String file, @NonNull String sha256, long size) {}

    /** Total plaintext payload bytes (the capacity check's input). */
    public long totalVolumeBytes() {
        long total = 0;
        for (VolumeEntry volume : this.volumes) {
            total += volume.size();
        }
        return total;
    }

    /** Serialize to the map DRY writes into {@code manifest.dry}. */
    public @NonNull Map<String, Object> toMap() {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("version", this.version);
        root.put("created", this.created);
        root.put("controller_version", this.controllerVersion);
        Map<String, Object> instance = new LinkedHashMap<>();
        instance.put("name", this.instanceName);
        instance.put("kind", this.kind);
        instance.put("settings", this.settings);
        instance.put("image_reference", this.imageReference);
        instance.put("image_id", this.imageId);
        instance.put("ownership", this.ownership);
        instance.put("container_port", this.containerPort);
        instance.put("port_protocol", this.portProtocol);
        root.put("instance", instance);
        List<Map<String, Object>> volumeList = new ArrayList<>();
        for (VolumeEntry volume : this.volumes) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("name", volume.name());
            entry.put("path", volume.containerPath());
            entry.put("file", volume.file());
            entry.put("sha256", volume.sha256());
            entry.put("size", volume.size());
            volumeList.add(entry);
        }
        root.put("volumes", volumeList);
        return root;
    }

    /**
     * The NON-sensitive summary stored on the backup row (no settings, no variables).
     */
    public @NonNull Map<String, Object> toSummary() {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("version", this.version);
        summary.put("created", this.created);
        summary.put("name", this.instanceName);
        summary.put("kind", this.kind);
        summary.put("image_reference", this.imageReference);
        summary.put("image_id", this.imageId);
        summary.put("volume_count", this.volumes.size());
        summary.put("volume_bytes", this.totalVolumeBytes());
        return summary;
    }

    /**
     * Parse a manifest map strictly: any missing required field is a refusal, because a
     * manifest a restore half-understands is worse than one it rejects.
     */
    public static @NonNull BackupManifest fromMap(@Nullable Object parsed) throws IOException {
        if (!(parsed instanceof Map<?, ?> root)) {
            throw new IOException("Backup manifest is not a map");
        }
        if (!(root.get("version") instanceof Number version)
                || version.intValue() != FORMAT_VERSION) {
            throw new IOException("Backup manifest version '" + root.get("version")
                + "' is not supported; this build reads version " + FORMAT_VERSION);
        }
        String created = requireText(root.get("created"), "created");
        String controllerVersion = requireText(root.get("controller_version"), "controller_version");
        if (!(root.get("instance") instanceof Map<?, ?> instance)) {
            throw new IOException("Backup manifest has no 'instance' section");
        }
        String name = requireText(instance.get("name"), "instance.name");
        String kind = requireText(instance.get("kind"), "instance.kind");
        if (!(instance.get("settings") instanceof Map<?, ?> settings)) {
            throw new IOException("Backup manifest has no 'instance.settings' map");
        }
        String imageReference = requireText(instance.get("image_reference"), "instance.image_reference");
        String imageId = instance.get("image_id") instanceof String id && !id.isBlank() ? id : null;
        String ownership = instance.get("ownership") instanceof String packed ? packed : "";
        Integer containerPort = instance.get("container_port") instanceof Number port
            ? port.intValue() : null;
        String protocol = instance.get("port_protocol") instanceof String proto && !proto.isBlank()
            ? proto : "tcp";

        List<VolumeEntry> volumes = new ArrayList<>();
        if (root.get("volumes") instanceof List<?> list) {
            for (Object element : list) {
                if (!(element instanceof Map<?, ?> entry)) {
                    throw new IOException("Backup manifest volume entry is not a map");
                }
                String volumeName = requireText(entry.get("name"), "volumes[].name");
                String path = requireText(entry.get("path"), "volumes[].path");
                String file = requireText(entry.get("file"), "volumes[].file");
                String sha = requireText(entry.get("sha256"), "volumes[].sha256");
                if (!(entry.get("size") instanceof Number size)) {
                    throw new IOException("Backup manifest volume '" + volumeName + "' has no size");
                }
                volumes.add(new VolumeEntry(volumeName, path, file, sha, size.longValue()));
            }
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> typedSettings = (Map<String, Object>) settings;
        return new BackupManifest(version.intValue(), created, controllerVersion, name, kind,
            typedSettings, imageReference, imageId, ownership, containerPort, protocol,
            List.copyOf(volumes));
    }

    private static @NonNull String requireText(@Nullable Object value, @NonNull String field)
            throws IOException {
        if (value instanceof String text && !text.isBlank()) {
            return text;
        }
        throw new IOException("Backup manifest is missing required field '" + field + "'");
    }
}
