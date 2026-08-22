package be.elevenways.hohenheim.server.docker;

import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.server.ControllerScope;
import be.elevenways.hohenheim.server.util.EnvVars;
import be.elevenways.protoblast.common.Blast;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * THE named volumes of a docker site: keyed to the SITE record, not to the release
 * instance row that happens to be serving.
 *
 * AIDEV-NOTE: DECIDED 2026-08-08, and it is the fix for silent data loss on a
 * zero-downtime release. The volume name used to be derived from the instance handle
 * ({@code ReleaseKind.specFor}), and {@code SiteReleases.gatedSwap} mints a NEW
 * instance row for every candidate -- so a health-gated release mounted a DIFFERENT,
 * EMPTY volume and orphaned the old one, while the in-place replacement path (which reuses
 * the serving row) kept it and hid the defect. The precedent this follows is
 * {@code DatabaseInstances.dataVolumeOf}: identity belongs to the record that PERSISTS.
 * The reconciler was already written for this name shape -- see
 * {@code DockerReconciler.classifyByNamingScheme}, whose {@code site-<id>-vol-<mount>}
 * branch had nothing minting it until now.
 *
 * AIDEV-NOTE: the volume is CREATED HERE rather than by the mount's {@code VolumeOptions},
 * and that is what makes its ownership survivable. Docker labels a volume at BIRTH only
 * (never relabels), so a volume born with the container's spec labels is attributed to an
 * INSTANCE row -- and once that release is reclaimed the reconciler would read live site
 * data as an orphan. Created here it is labelled to the SITE, which is the record that
 * outlives every release.
 */
public final class SiteVolumes {

    /** The segment that separates a workload handle from a mount's logical name. */
    private static final String MARKER = "-vol-";

    private SiteVolumes() {
    }

    /** THE data volume of one declared site mount, keyed to the site record. */
    public static @NonNull String volumeOf(int siteId, @NonNull String logicalName) {
        return ControllerScope.handle(ControllerScope.KIND_SITE, siteId) + MARKER + logicalName;
    }

    /** The mount's logical name behind a materialized volume name, or null when unshaped. */
    public static @Nullable String logicalOf(@NonNull String volumeName) {
        int marker = volumeName.indexOf(MARKER);
        return marker < 0 ? null : volumeName.substring(marker + MARKER.length());
    }

    /**
     * What one converge resolved, so the caller can report a heal instead of doing it
     * silently.
     *
     * @param volumes materialized volume name -> container path
     * @param adopted legacy instance-keyed volumes whose data was migrated to the site
     */
    public record Resolution(@NonNull Map<String, String> volumes, int adopted) {}

    /**
     * Resolve a site's declared mounts onto real, existing, site-owned volumes, adopting
     * any pre-existing instance-keyed volume's DATA on the way.
     *
     * @param declared logical mount name -> container path, as the site declares them
     * @throws IOException when the daemon refuses, or an adoption could not be VERIFIED --
     *                     deploying an empty volume beside surviving data is the defect
     */
    public static @NonNull Resolution resolve(@NonNull DockerClient docker, int siteId,
                                              @NonNull Map<String, String> declared)
            throws IOException {
        Map<String, String> resolved = new LinkedHashMap<>();
        int adopted = 0;
        if (!declared.isEmpty()) {
            List<Row> owned = SiteInstances.ownedInstances(siteId);
            Map<String, String> labels = OwnerLabels.of(SiteModel.MODEL_ID, siteId);
            for (Map.Entry<String, String> mount : declared.entrySet()) {
                String path = mount.getValue();
                if (path == null || path.isBlank()) {
                    continue;
                }
                String name = volumeOf(siteId, mount.getKey());
                if (!exists(docker, name)) {
                    String legacy = legacyVolumeOf(docker, owned, mount.getKey());
                    if (legacy == null) {
                        docker.createVolume(name, labels);
                    } else {
                        adopt(docker, siteId, legacy, name, labels);
                        adopted++;
                    }
                }
                resolved.put(name, path);
            }
        }
        if (adopted > 0) {
            Blast.log("SITE-VOL: site", siteId, "adopted", adopted, "legacy volume(s)");
        }
        return new Resolution(resolved, adopted);
    }

    // -- adoption -------------------------------------------------------------

    /**
     * The pre-existing volume of one mount, named after a release instance row of THIS
     * site rather than after the site.
     *
     * AIDEV-NOTE: candidates come from the site's own live instance rows, newest first, so
     * ownership is never inferred from a name shape: a volume is a candidate only because a
     * row this site owns declared it. That is the same "attribute, never guess" rule
     * OwnerLabels.removeIfOwnedBy enforces on containers.
     */
    private static @Nullable String legacyVolumeOf(@NonNull DockerClient docker,
                                                   @NonNull List<Row> owned,
                                                   @NonNull String logicalName)
            throws IOException {
        for (Row instance : owned) {
            Integer instanceId = instance.get(InstanceModel.ID);
            if (instanceId == null) {
                continue;
            }
            for (String stored : storedVolumeNames(instance)) {
                String logical = logicalOf(stored);
                String candidate = logical == null
                    // A pre-fix row stores the LOGICAL name (the kind did the prefixing).
                    ? ControllerScope.handle(ControllerScope.KIND_INSTANCE, instanceId)
                        + MARKER + stored
                    : stored;
                if (!logicalName.equals(logical == null ? stored : logical)) {
                    continue;
                }
                if (exists(docker, candidate)) {
                    return candidate;
                }
            }
        }
        return null;
    }

    /**
     * Copy a legacy volume's contents into the site-keyed one, VERIFIED by counting
     * entries on both sides from inside the container that did the copy.
     *
     * AIDEV-NOTE: the legacy volume is deliberately NOT removed afterwards. Autonomous
     * volume removal is a declared non-primitive in this product (OrphanActions and
     * DockerReclaim both refuse volumes), and an adoption that deletes the only copy of
     * tenant data the moment its verification is wrong is exactly the trade this codebase
     * refuses. It surfaces in the reconciler as an orphan of a reclaimed instance, which is
     * an operator decision with the data still there.
     *
     * @throws IOException when the copy could not be verified -- the fresh volume is
     *                     removed again so the next converge retries the adoption instead
     *                     of mounting a half-copy
     */
    private static void adopt(@NonNull DockerClient docker, int siteId, @NonNull String legacy,
                              @NonNull String name, @NonNull Map<String, String> labels)
            throws IOException {
        docker.createVolume(name, labels);
        // AIDEV-NOTE: the copy container is SITE-labelled, which is what keeps it out of the
        // reconciler's collision bucket -- DockerReconciler.classify reads owner labels
        // BEFORE any naming scheme, and this name deliberately does not fit the
        // site-{id}[-vol-{mount}] scheme (checked: an unlabelled leftover under this name
        // would be reported as "matches no naming scheme", never adopted by mistake).
        String handle = ControllerScope.handle(ControllerScope.KIND_SITE, siteId) + "-voladopt";
        // AIDEV-NOTE: the DETECTOR image, not a second knob. This copy needs exactly what
        // that setting already declares -- "a base image that only needs a POSIX shell" --
        // and a product with two settings for one requirement drifts on one of them.
        String image = HohenheimSettings.VALUES.getValue(HohenheimSettings.Builds.DETECTOR_IMAGE);
        if (image == null || image.isBlank()) {
            throw new IOException("REFUSED to adopt volume '" + legacy + "': no builds"
                + ".detector_image is configured, so there is no image to copy it with");
        }
        try {
            docker.ensureImage(image, null);
            OwnerLabels.removeIfOwnedBy(docker, handle, SiteModel.MODEL_ID, siteId);
            Map<String, Object> hostConfig = new LinkedHashMap<>();
            hostConfig.put("NetworkMode", "none");
            hostConfig.put("Mounts", List.of(
                Map.of("Type", "volume", "Source", legacy, "Target", "/from", "ReadOnly", true),
                Map.of("Type", "volume", "Source", name, "Target", "/to")));
            docker.createContainer(handle, Map.of(
                "Image", image,
                "Labels", labels,
                // Counted INSIDE the copy: an exit code says the shell ran, never that the
                // bytes arrived, and this is the only moment both sides are mounted.
                "Cmd", List.of("/bin/sh", "-c",
                    "cp -a /from/. /to/ 2>&1 && printf 'ADOPTED %s %s\\n'"
                        + " \"$(find /from -mindepth 1 | wc -l)\""
                        + " \"$(find /to -mindepth 1 | wc -l)\""),
                "HostConfig", hostConfig), ContainerHardening.SERVICE);
            docker.startContainer(handle);
            String output = awaitCopy(docker, handle, legacy, name);
            String[] counted = output.split("ADOPTED ", 2)[1].trim().split("\\s+");
            if (!counted[0].equals(counted[1])) {
                throw new IOException("REFUSED to adopt volume '" + legacy + "' into '" + name
                    + "': it holds " + counted[0] + " entries and the copy landed "
                    + counted[1] + ". The site keeps no half-copied volume.");
            }
            Blast.log("SITE-VOL: adopted", counted[0], "entries from", legacy, "into", name);
        } catch (IOException | RuntimeException failed) {
            quietlyRemoveVolume(docker, name);
            throw failed instanceof IOException io ? io : new IOException(failed);
        } finally {
            try {
                docker.removeContainer(handle, true);
            } catch (IOException ignored) {
                // Re-removed by the next adoption's removeIfOwnedByNobody.
            }
        }
    }

    /** Wait for the copy container to exit 0 and hand back everything it printed. */
    private static @NonNull String awaitCopy(@NonNull DockerClient docker,
                                             @NonNull String handle, @NonNull String legacy,
                                             @NonNull String name) throws IOException {
        long deadline = System.currentTimeMillis() + 300_000;
        while (true) {
            Map<String, Object> inspect = docker.inspectContainer(handle);
            Object state = inspect.get("State");
            String status = state instanceof Map<?, ?> s ? String.valueOf(s.get("Status")) : "";
            if ("exited".equals(status)) {
                int exitCode = state instanceof Map<?, ?> s
                    && s.get("ExitCode") instanceof Number code ? code.intValue() : -1;
                String logs = docker.containerLogs(handle, true, true, 200);
                if (exitCode != 0 || !logs.contains("ADOPTED ")) {
                    throw new IOException("REFUSED to adopt volume '" + legacy + "' into '"
                        + name + "': the copy exited " + exitCode + " and reported: "
                        + logs.trim());
                }
                return logs;
            }
            if (System.currentTimeMillis() > deadline) {
                throw new IOException("REFUSED to adopt volume '" + legacy + "' into '" + name
                    + "': the copy did not finish within 300 seconds");
            }
            try {
                Thread.sleep(250);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IOException("Adoption of volume '" + legacy + "' was interrupted");
            }
        }
    }

    // -- the row heal ---------------------------------------------------------

    /**
     * Rewrite the volume names stored on this site's live instance rows to the ones the
     * release just resolved.
     *
     * AIDEV-NOTE: this is the half that makes ROLLBACK land on the same volume. A rollback
     * re-deploys a RETIRED row from its STORED settings and never passes through
     * {@code desiredSettings}, so a row left holding the old (or pre-fix logical) name
     * would mount a different volume than the release that replaced it -- the same defect
     * one generation later.
     *
     * AIDEV-NOTE: the call sites used to have to run this AFTER the release wrote its own
     * rows, and nothing enforced that ordering. {@code gatedSwap} flipped the superseded
     * row's role with the Row object it had loaded BEFORE the spec was resolved, and
     * {@code Models.save} writes every column present on a Row -- so a heal that ran
     * first was clobbered by that stale settings map, reported success, and left the
     * rollback target pointing at the old volume. FIXED AT THE ROOT 2026-08-08: the role
     * flip is now a fenced single-column write ({@code InstanceOperationGuard.stampRole})
     * and the remaining whole-row saves reload first ({@code SiteReleases.reload}), so
     * no release write can carry a stale settings map back over this heal. The call sites
     * stay where they are because that is where {@code desired} is known good, not
     * because the order still matters.
     *
     * @param desired the release's resolved kind settings (its {@code volumes} map decides)
     * @return how many rows were rewritten
     */
    public static int heal(int siteId, @NonNull Map<String, Object> desired) {
        Map<String, String> resolved = EnvVars.toMap(desired.get("volumes"));
        if (resolved.isEmpty()) {
            return 0;
        }
        Map<String, String> byLogical = new LinkedHashMap<>();
        resolved.forEach((name, path) -> {
            String logical = logicalOf(name);
            if (logical != null) {
                byLogical.put(logical, name);
            }
        });
        int healed = 0;
        for (Row instance : SiteInstances.ownedInstances(siteId)) {
            if (!(instance.get(InstanceModel.SETTINGS) instanceof Map<?, ?> settings)) {
                continue;
            }
            Map<String, String> stored = EnvVars.toMap(settings.get("volumes"));
            if (stored.isEmpty()) {
                continue;
            }
            Map<String, String> rewritten = new LinkedHashMap<>();
            boolean changed = false;
            for (Map.Entry<String, String> entry : stored.entrySet()) {
                String logical = logicalOf(entry.getKey());
                String target = byLogical.get(logical == null ? entry.getKey() : logical);
                if (target == null || target.equals(entry.getKey())) {
                    rewritten.put(entry.getKey(), entry.getValue());
                } else {
                    rewritten.put(target, entry.getValue());
                    changed = true;
                }
            }
            if (!changed) {
                continue;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> updated = new LinkedHashMap<>((Map<String, Object>) settings);
            updated.put("volumes", rewritten);
            instance.set(InstanceModel.SETTINGS, updated);
            int instanceId = instance.get(InstanceModel.ID);
            SiteInstances.inScopeUnchecked(siteId,
                () -> Models.get(InstanceModel.class).save(instance));
            Blast.log("SITE-VOL: rewrote the volume names of instance", instanceId,
                "to the site-keyed spelling");
            healed++;
        }
        if (healed > 0) {
            Blast.log("SITE-VOL: site", siteId, "rewrote", healed, "instance row(s)");
        }
        return healed;
    }

    // -- daemon helpers -------------------------------------------------------

    private static @NonNull List<String> storedVolumeNames(@NonNull Row instance) {
        if (!(instance.get(InstanceModel.SETTINGS) instanceof Map<?, ?> settings)) {
            return List.of();
        }
        return new ArrayList<>(EnvVars.toMap(settings.get("volumes")).keySet());
    }

    private static boolean exists(@NonNull DockerClient docker, @NonNull String name)
            throws IOException {
        try {
            docker.inspectVolume(name);
            return true;
        } catch (DockerClient.ApiException e) {
            if (e.isNotFound()) {
                return false;   // OBSERVED absent, which is what the caller may act on
            }
            throw e;
        }
    }

    private static void quietlyRemoveVolume(@NonNull DockerClient docker, @NonNull String name) {
        try {
            docker.removeVolume(name, true);
        } catch (IOException ignored) {
            // A surviving empty volume is inert; the next converge adopts into it again.
        }
    }
}
