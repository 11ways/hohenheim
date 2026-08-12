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
 * rebuild the instance as a NEW record -- kind, the RAW stored settings map, resolved
 * image identity, ownership subjects, port semantics, the checksummed volume inventory
 * and (from {@link #FORMAT_VERSION} 2) the {@link InstanceProfile}: the control-plane
 * rows and columns the settings map cannot carry.
 *
 * AIDEV-NOTE: {@code settings} is the instance's RAW stored map, deliberately NOT the
 * deploy-time merge {@code InstanceVariables.applyToSettings} produces. Variable values
 * ride the profile's own inventory; baking them into the settings map too would give one
 * value two authorities in the archive, and a restore would then have to decide which of
 * them wins. Secret variable values DO travel (in the profile, in plaintext) -- that is
 * why the archive is encrypted whole, and the reason no second encryption path exists
 * here: nothing in this manifest is ever written outside a {@code .hib}.
 *
 * @param version           manifest format version (this build reads 1..{@link #FORMAT_VERSION})
 * @param created           ISO instant of the capture
 * @param controllerVersion the controller build that wrote it
 * @param instanceName      the source instance's name
 * @param kind              the instance kind token ({@code hohenheim:docker_container})
 * @param payload           how the payload entries unpack ({@link #PAYLOAD_VOLUME_TARS}
 *                          or {@link #PAYLOAD_INSTANCE_EXPORT})
 * @param settings          the instance's RAW stored settings map
 * @param imageReference    the image reference the workload ran
 * @param imageId           the daemon's immutable image id, when known
 * @param ownership         the packed manage-subject set at capture time (informational:
 *                          restore-to-new charges the RESTORING actor's owner derivation)
 * @param containerPort     the declared container port, or null
 * @param portProtocol      the port's protocol ({@code tcp}; UDP arrives with pre-allocation)
 * @param volumes           per-volume payload inventory with plaintext checksums
 * @param profile           the control-plane profile, or null for a version-1 manifest --
 *                          which DECLARES NOTHING about templates, variables or config
 *                          files rather than declaring them absent
 */
public record BackupManifest(int version,
                             @NonNull String created,
                             @NonNull String controllerVersion,
                             @NonNull String instanceName,
                             @NonNull String kind,
                             @NonNull String payload,
                             @NonNull Map<String, Object> settings,
                             @NonNull String imageReference,
                             @Nullable String imageId,
                             @NonNull String ownership,
                             @Nullable Integer containerPort,
                             @NonNull String portProtocol,
                             @NonNull List<VolumeEntry> volumes,
                             @Nullable InstanceProfile profile) {

    /** The manifest format this build WRITES; it reads every version down to 1. */
    public static final int FORMAT_VERSION = 2;

    /** The first format: no {@link InstanceProfile} (see {@link #profile()}). */
    public static final int VERSION_WITHOUT_PROFILE = 1;

    // AIDEV-NOTE: the bump to 2 is deliberate and the alternative (keep 1, treat the
    // additions as optional) was rejected for ONE reason: with the version left at 1 an
    // empty variable inventory is ambiguous -- "the source had no variables" and "this
    // archive predates the inventory" read identically, and a restore that cannot tell
    // them apart cannot report honestly which of the two it just did. That ambiguity is
    // the same silent-degradation shape the inventory exists to remove. The version is
    // therefore what says whether SILENCE MEANS ANYTHING. Reading stays backward
    // compatible on purpose (fromMap accepts 1..FORMAT_VERSION): live installations hold
    // v1 archives, and a v1 restore must degrade loudly, never fail obscurely.

    // AIDEV-NOTE: payload-kind is its OWN manifest fact, written by the capture seam
    // and dispatched on by restore -- never derived from instance.kind. One authority
    // per fact: `kind` answers "which driver runs this instance", `payload` answers
    // "which capability seam unpacks these bytes"; deriving one from the other would
    // make the kind a second authority over payload interpretation, and a future kind
    // reusing an existing payload shape would then need a mapping table nobody owns.
    // An unknown payload value is a whole-archive refusal, never a guess.

    /** {@link #payload()}: one checksummed tar PER LOGICAL VOLUME (the Docker lane). */
    public static final String PAYLOAD_VOLUME_TARS = "volume_tars";

    /**
     * {@link #payload()}: ONE whole-instance export tarball produced by the source
     * driver's {@code NativeSnapshotSupport}, listed as the single inventory entry.
     */
    public static final String PAYLOAD_INSTANCE_EXPORT = "instance_export";

    /** One captured volume payload: archive entry {@code volumes/<name>.tar}. */
    public record VolumeEntry(@NonNull String name, @NonNull String containerPath,
                              @NonNull String file, @NonNull String sha256, long size) {}

    /**
     * The template the source instance was created from, as the SOURCE controller knew
     * it. The name travels beside the id because an archive is portable: on another
     * controller that id names a different template, and a restore that re-bound by id
     * alone would silently attach the instance to an unrelated catalog entry.
     *
     * @param version the template's operator-bumped catalog version at capture time
     */
    public record TemplateRef(int id, @NonNull String name, int version) {}

    /** One captured {@code instance_variables} row ({@code kind} = plain or secret). */
    public record VariableEntry(@NonNull String key, @NonNull String kind,
                                @NonNull String value) {}

    /**
     * One captured {@code instance_files} row.
     *
     * @param generatedBy the system that authored it, or null when hand-authored; a
     *                    generated row belongs to its DECLARING record and is reported
     *                    as unrestorable rather than re-created without attribution
     */
    public record FileEntry(@NonNull String containerPath, @NonNull String content,
                            @NonNull String mode, @Nullable String generatedBy) {}

    /**
     * The control-plane facts BESIDE the settings map: the template binding, the crash
     * policy, the groupings named for the record, and the table-backed variable and
     * config-file rows. Everything here is dropped on the floor by a restore that only
     * writes name/kind/settings/host.
     *
     * @param environmentName   informational: restore-to-new charges the RESTORING actor,
     *                          so the project grouping cannot be re-established
     * @param backupTargetName  the source's backup destination, re-bound BY NAME
     */
    public record InstanceProfile(@Nullable TemplateRef template,
                                  @NonNull String crashPolicy,
                                  @Nullable String environmentName,
                                  @Nullable String backupTargetName,
                                  @NonNull List<VariableEntry> variables,
                                  @NonNull List<FileEntry> files) {}

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
        instance.put("payload", this.payload);
        instance.put("settings", this.settings);
        instance.put("image_reference", this.imageReference);
        instance.put("image_id", this.imageId);
        instance.put("ownership", this.ownership);
        instance.put("container_port", this.containerPort);
        instance.put("port_protocol", this.portProtocol);
        if (this.profile != null) {
            TemplateRef template = this.profile.template();
            if (template != null) {
                Map<String, Object> ref = new LinkedHashMap<>();
                ref.put("id", template.id());
                ref.put("name", template.name());
                ref.put("version", template.version());
                instance.put("template", ref);
            }
            instance.put("crash_policy", this.profile.crashPolicy());
            instance.put("environment", this.profile.environmentName());
            instance.put("backup_target", this.profile.backupTargetName());
        }
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
        if (this.profile != null) {
            List<Map<String, Object>> variableList = new ArrayList<>();
            for (VariableEntry variable : this.profile.variables()) {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("key", variable.key());
                entry.put("kind", variable.kind());
                entry.put("value", variable.value());
                variableList.add(entry);
            }
            root.put("variables", variableList);
            List<Map<String, Object>> fileList = new ArrayList<>();
            for (FileEntry file : this.profile.files()) {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("path", file.containerPath());
                entry.put("content", file.content());
                entry.put("mode", file.mode());
                entry.put("generated_by", file.generatedBy());
                fileList.add(entry);
            }
            root.put("files", fileList);
        }
        return root;
    }

    /**
     * The NON-sensitive summary stored on the backup row: no settings, no variable
     * values, no file contents -- COUNTS of them, which is what makes a row answer
     * "did this backup carry the instance's variables" without carrying them.
     */
    public @NonNull Map<String, Object> toSummary() {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("version", this.version);
        summary.put("created", this.created);
        summary.put("name", this.instanceName);
        summary.put("kind", this.kind);
        summary.put("payload", this.payload);
        summary.put("image_reference", this.imageReference);
        summary.put("image_id", this.imageId);
        summary.put("volume_count", this.volumes.size());
        summary.put("volume_bytes", this.totalVolumeBytes());
        if (this.profile != null) {
            TemplateRef template = this.profile.template();
            summary.put("template", template != null ? template.name() : null);
            summary.put("template_version", template != null ? template.version() : null);
            summary.put("variable_count", this.profile.variables().size());
            summary.put("file_count", this.profile.files().size());
        }
        return summary;
    }

    /**
     * Parse a manifest map strictly: any missing required field is a refusal, because a
     * manifest a restore half-understands is worse than one it rejects. Versions below
     * {@link #FORMAT_VERSION} parse into the subset they can express (an older archive
     * must still restore); a version ABOVE it is refused whole, since a manifest written
     * by a newer build may mean something different by the fields this one recognises.
     */
    public static @NonNull BackupManifest fromMap(@Nullable Object parsed) throws IOException {
        if (!(parsed instanceof Map<?, ?> root)) {
            throw new IOException("Backup manifest is not a map");
        }
        if (!(root.get("version") instanceof Number version)
                || version.intValue() < VERSION_WITHOUT_PROFILE
                || version.intValue() > FORMAT_VERSION) {
            throw new IOException("Backup manifest version '" + root.get("version")
                + "' is not supported; this build reads versions "
                + VERSION_WITHOUT_PROFILE + " to " + FORMAT_VERSION);
        }
        String created = requireText(root.get("created"), "created");
        String controllerVersion = requireText(root.get("controller_version"), "controller_version");
        if (!(root.get("instance") instanceof Map<?, ?> instance)) {
            throw new IOException("Backup manifest has no 'instance' section");
        }
        String name = requireText(instance.get("name"), "instance.name");
        String kind = requireText(instance.get("kind"), "instance.kind");
        String payload = requireText(instance.get("payload"), "instance.payload");
        if (!PAYLOAD_VOLUME_TARS.equals(payload) && !PAYLOAD_INSTANCE_EXPORT.equals(payload)) {
            throw new IOException("Backup manifest payload kind '" + payload
                + "' is not one this build can unpack; the backup is refused whole");
        }
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

        InstanceProfile profile = version.intValue() > VERSION_WITHOUT_PROFILE
            ? profileFrom(root, instance) : null;

        @SuppressWarnings("unchecked")
        Map<String, Object> typedSettings = (Map<String, Object>) settings;
        return new BackupManifest(version.intValue(), created, controllerVersion, name, kind,
            payload, typedSettings, imageReference, imageId, ownership, containerPort,
            protocol, List.copyOf(volumes), profile);
    }

    /** The profile half of a version-2-or-later manifest, parsed with the same strictness. */
    private static @NonNull InstanceProfile profileFrom(@NonNull Map<?, ?> root,
                                                        @NonNull Map<?, ?> instance)
            throws IOException {
        TemplateRef template = null;
        if (instance.get("template") instanceof Map<?, ?> ref) {
            if (!(ref.get("id") instanceof Number templateId)) {
                throw new IOException("Backup manifest template entry has no id");
            }
            String templateName = requireText(ref.get("name"), "instance.template.name");
            int templateVersion = ref.get("version") instanceof Number number
                ? number.intValue() : 1;
            template = new TemplateRef(templateId.intValue(), templateName, templateVersion);
        }
        String crashPolicy = instance.get("crash_policy") instanceof String policy
                && !policy.isBlank() ? policy : "none";
        String environment = textOrNull(instance.get("environment"));
        String backupTarget = textOrNull(instance.get("backup_target"));

        List<VariableEntry> variables = new ArrayList<>();
        if (root.get("variables") instanceof List<?> list) {
            for (Object element : list) {
                if (!(element instanceof Map<?, ?> entry)) {
                    throw new IOException("Backup manifest variable entry is not a map");
                }
                String key = requireText(entry.get("key"), "variables[].key");
                String kind = requireText(entry.get("kind"), "variables[].kind");
                String value = entry.get("value") instanceof String text ? text : "";
                variables.add(new VariableEntry(key, kind, value));
            }
        }

        List<FileEntry> files = new ArrayList<>();
        if (root.get("files") instanceof List<?> list) {
            for (Object element : list) {
                if (!(element instanceof Map<?, ?> entry)) {
                    throw new IOException("Backup manifest file entry is not a map");
                }
                String path = requireText(entry.get("path"), "files[].path");
                String content = entry.get("content") instanceof String text ? text : "";
                String mode = entry.get("mode") instanceof String text && !text.isBlank()
                    ? text : "0644";
                files.add(new FileEntry(path, content, mode, textOrNull(entry.get("generated_by"))));
            }
        }
        return new InstanceProfile(template, crashPolicy, environment, backupTarget,
            List.copyOf(variables), List.copyOf(files));
    }

    private static @Nullable String textOrNull(@Nullable Object value) {
        return value instanceof String text && !text.isBlank() ? text : null;
    }

    private static @NonNull String requireText(@Nullable Object value, @NonNull String field)
            throws IOException {
        if (value instanceof String text && !text.isBlank()) {
            return text;
        }
        throw new IOException("Backup manifest is missing required field '" + field + "'");
    }
}
