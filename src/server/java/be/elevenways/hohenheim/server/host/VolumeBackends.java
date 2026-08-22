package be.elevenways.hohenheim.server.host;

import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.hohenheim.host.VolumeBackend;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.protoblast.common.Blast;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.validation.Violations;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * THE volume-backend probe: what the filesystem under a host's volume root can actually do,
 * read off the KERNEL on the host that would carry the bytes.
 *
 * AIDEV-NOTE: it reads the real host, never a daemon's opinion -- the HostPreflight stance.
 * {@code stat -f} names the filesystem type and {@code findmnt} names the mount options,
 * which is the only way to tell an XFS that can enforce a project quota from one that
 * cannot: both answer "xfs" to stat, and only the {@code prjquota}/{@code pquota} mount
 * option makes the quota real. Guessing there would hand a workspace a cap nothing enforces.
 *
 * AIDEV-NOTE: this probe is per DATA ROOT, not per host. Both live twins (daystrom,
 * nightstrom) run an ext4 root with a SEPARATE btrfs device, so a host-level answer would
 * be right about the machine and wrong about every volume on it.
 */
public final class VolumeBackends {

    /** How long a probe may take before the host counts as unanswerable. */
    static final long PROBE_TIMEOUT_SECONDS = 20;

    /** The mount options that make an XFS project quota actually enforce. */
    private static final List<String> PRJQUOTA_OPTIONS = List.of("prjquota", "pquota");

    /** What a probe found, and the raw evidence it found it in. */
    public record Detection(@NonNull VolumeBackend backend, @NonNull String root,
                            @NonNull String detail) {
    }

    private VolumeBackends() {
    }

    /** @return the volume root this deployment uses under the configured data path */
    public static @NonNull String volumeRoot() {
        String dataPath = HohenheimSettings.VALUES.getValue(HohenheimSettings.Storage.DATA_PATH);
        String base = dataPath == null || dataPath.isBlank() ? "/opt/hohenheim/data" : dataPath.trim();
        return base.endsWith("/") ? base + "volumes" : base + "/volumes";
    }

    /**
     * Probe one inventoried host's volume root and STORE the finding on its record.
     *
     * @throws IllegalArgumentException when no such server exists
     */
    public static @NonNull Detection runAndStore(@NonNull String serverName) {

        Row server = Models.get(ServerModel.class).findByName(serverName);

        if (server == null) {
            throw new IllegalArgumentException("No server named '" + serverName + "'");
        }

        Detection detection = probe(server);
        store(server, detection);
        return detection;
    }

    /** Store a finding on the host record, so placement never has to re-probe. */
    public static void store(@NonNull Row server, @NonNull Detection detection) {
        server.set(ServerModel.VOLUME_BACKEND, detection.backend().token());
        server.set(ServerModel.VOLUME_ROOT, detection.root());
        server.set(ServerModel.VOLUME_BACKEND_DETAIL, detection.detail());
        server.set(ServerModel.VOLUME_PROBED_AT, Instant.now());
        Models.get(ServerModel.class).save(server);
    }

    /**
     * Read the filesystem type and mount options of this host's volume root.
     *
     * AIDEV-NOTE: an UNREADABLE root is {@link VolumeBackend#NONE} with the error text as
     * its detail, never an exception. A host nobody could probe must refuse workspaces the
     * same way a host with no quota does -- throwing here would have taken the whole
     * preflight down over one unreachable machine.
     */
    public static @NonNull Detection probe(@NonNull Row server) {

        String root = volumeRoot();

        // stat -f answers even for a path that does not exist yet only if a parent does,
        // so probe the deepest existing ancestor: the volume root is created on first use.
        String script = "p=" + shellQuote(root) + "; "
            + "while [ ! -e \"$p\" ] && [ \"$p\" != \"/\" ]; do p=$(dirname \"$p\"); done; "
            + "echo \"$p\"; stat -f -c %T \"$p\"; findmnt -no OPTIONS --target \"$p\" || true";

        Result result = run(server, script);

        if (result.exitCode() != 0) {
            return new Detection(VolumeBackend.NONE, root,
                "probe failed (exit " + result.exitCode() + "): " + result.text());
        }

        return classify(root, result.text());
    }

    /**
     * Turn the probe's three output lines (probed path, filesystem type, mount options)
     * into a backend.
     *
     * @return {@link VolumeBackend#NONE} for anything unrecognised -- fail closed
     */
    public static @NonNull Detection classify(@NonNull String root, @NonNull String output) {

        List<String> lines = new ArrayList<>();

        for (String line : output.split("\n")) {
            lines.add(line.trim());
        }

        String probed = lines.isEmpty() ? root : lines.get(0);
        String type = lines.size() > 1 ? lines.get(1).toLowerCase(Locale.ROOT) : "";
        String options = lines.size() > 2 ? lines.get(2).toLowerCase(Locale.ROOT) : "";
        String detail = "path=" + probed + " fstype=" + type + " options=" + options;

        if ("btrfs".equals(type)) {
            return new Detection(VolumeBackend.BTRFS, root, detail);
        }

        if ("zfs".equals(type)) {
            return new Detection(VolumeBackend.ZFS, root, detail);
        }

        if ("xfs".equals(type)) {
            for (String option : options.split(",")) {
                if (PRJQUOTA_OPTIONS.contains(option.trim())) {
                    return new Detection(VolumeBackend.XFS_PRJQUOTA, root, detail);
                }
            }
            return new Detection(VolumeBackend.NONE, root,
                detail + " (xfs without prjquota: mount it with prjquota to enforce quotas)");
        }

        return new Detection(VolumeBackend.NONE, root, detail);
    }

    /**
     * Refuse a host that cannot enforce a per-volume quota.
     *
     * @param kindLabel the kind's translated label, so the refusal names what was refused
     * @throws Violations {@code host_no_volume_quota}
     */
    public static void requireQuotaCapableHost(@NonNull String serverName,
                                               @NonNull Microcopy kindLabel) {

        Row server = Models.get(ServerModel.class).findByName(serverName);
        VolumeBackend backend = server == null
            ? VolumeBackend.NONE : ServerModel.volumeBackendOf(server);

        if (backend.supportsQuota()) {
            return;
        }

        throw Violations.ofForm(Microcopy.of("host_no_volume_quota")
            .withFilter("scope", "violations")
            .withArg("name", serverName)
            .withArg("kind", kindLabel)
            .withArg("backend", backend.label()));
    }

    // -- the host exec seam ---------------------------------------------------

    /** One command's outcome; the text is stdout plus stderr, whichever spoke. */
    record Result(int exitCode, @NonNull String text) {
    }

    /**
     * Run a shell snippet on the host: locally for a local host, over the pinned ssh lane
     * for an SSH one ({@code RestoreCapacity.remoteAvailable} is the precedent).
     */
    private static @NonNull Result run(@NonNull Row server, @NonNull String script) {

        List<String> argv = ServerModel.hasSshLane(server)
            ? sshArgv(server, script)
            : List.of("sh", "-c", script);

        if (argv.isEmpty()) {
            return new Result(1, "no ssh lane could be built for this host");
        }

        try {
            Process process = new ProcessBuilder(argv).redirectErrorStream(true).start();
            try {
                byte[] output = process.getInputStream().readAllBytes();
                if (!process.waitFor(PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                    return new Result(1, "the volume probe timed out");
                }
                return new Result(process.exitValue(),
                    new String(output, StandardCharsets.UTF_8).trim());
            } finally {
                process.destroyForcibly();
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return new Result(1, "the volume probe was interrupted");
        } catch (IOException failed) {
            return new Result(1, String.valueOf(failed.getMessage()));
        }
    }

    private static @NonNull List<String> sshArgv(@NonNull Row server, @NonNull String script) {
        try {
            return HostKeys.sshArgv(server, List.of("sh", "-c", shellQuote(script)));
        } catch (RuntimeException refusal) {
            // An unpinned host cannot be probed at all; that refusal is itself the verdict.
            Blast.log("VolumeBackends: cannot reach host", server.get(ServerModel.NAME),
                "-", refusal.getMessage());
            return List.of();
        }
    }

    /** POSIX single-quoting: the only quoting that survives an arbitrary path. */
    private static @NonNull String shellQuote(@Nullable String value) {
        String text = value == null ? "" : value;
        return "'" + text.replace("'", "'\\''") + "'";
    }
}
