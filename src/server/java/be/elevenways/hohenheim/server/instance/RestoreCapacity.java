package be.elevenways.hohenheim.server.instance;

import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.server.docker.DockerClient;
import be.elevenways.hohenheim.server.docker.ServerService;
import be.elevenways.hohenheim.server.host.HostKeys;
import be.elevenways.hohenheim.server.incus.IncusClient;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.validation.Violations;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * The available-capacity half of "restore verifies checksums AND available capacity
 * before changing live state": the daemon's data root must hold the restored payload
 * with headroom. Local hosts stat the filesystem directly; SSH hosts run {@code df}
 * through the pinned argv. An UNANSWERABLE probe REFUSES -- a capacity check that
 * silently passes when it cannot measure is a check that cannot fail.
 */
final class RestoreCapacity {

    /** Restored bytes must fit with this much slack (extraction working space). */
    private static final double HEADROOM_FACTOR = 1.2;

    private RestoreCapacity() {}

    /**
     * Dispatches on the host's DECLARED runtime: docker hosts stat the daemon's data
     * root (local fs / ssh df), incus hosts ask the daemon's own storage pool.
     *
     * @throws Violations {@code restore_capacity} (insufficient) or
     *         {@code restore_capacity_unknown} (unmeasurable)
     */
    static void require(int serverId, long requiredBytes) {
        if (requiredBytes <= 0) {
            return;
        }
        Row server = Models.get(ServerModel.class).findById(serverId);
        String runtime = server != null ? ServerModel.runtimeOf(server)
            : ServerModel.RUNTIME_DOCKER;
        long available;
        try {
            available = ServerModel.RUNTIME_INCUS.equals(runtime)
                ? incusAvailableBytes(serverId)
                : availableBytes(serverId,
                    new ServerService().clientFor(ServerModel.nameOf(serverId)));
        } catch (IOException | RuntimeException error) {
            throw Violations.ofForm(violation("restore_capacity_unknown")
                .withArg("server", ServerModel.nameOf(serverId))
                .withArg("reason", error.getMessage() != null
                    ? error.getMessage() : error.toString()));
        }
        long needed = (long) (requiredBytes * HEADROOM_FACTOR);
        if (available < needed) {
            throw Violations.ofForm(violation("restore_capacity")
                .withArg("server", ServerModel.nameOf(serverId))
                .withArg("needed", needed)
                .withArg("available", available));
        }
    }

    /** Free space of the pool the default profile's root device names (or the first pool). */
    private static long incusAvailableBytes(int serverId) throws IOException {
        IncusClient incus = new ServerService().incusClientFor(ServerModel.nameOf(serverId));
        String pool = rootPoolOf(incus);
        Map<String, Object> resources = incus.storagePoolResources(pool);
        if (resources.get("space") instanceof Map<?, ?> space
                && space.get("total") instanceof Number total
                && space.get("used") instanceof Number used) {
            return total.longValue() - used.longValue();
        }
        throw new IOException("Incus pool '" + pool + "' reported no space usage");
    }

    private static String rootPoolOf(IncusClient incus) throws IOException {
        Map<String, Object> profile = incus.profile("default");
        if (profile.get("devices") instanceof Map<?, ?> devices
                && devices.get("root") instanceof Map<?, ?> root
                && root.get("pool") instanceof String pool && !pool.isBlank()) {
            return pool;
        }
        List<Map<String, Object>> pools = incus.storagePools();
        if (!pools.isEmpty() && pools.get(0).get("name") instanceof String name) {
            return name;
        }
        throw new IOException("No storage pool visible on the incus host");
    }

    private static long availableBytes(int serverId, DockerClient docker) throws IOException {
        Map<String, Object> info = docker.info();
        String dockerRoot = info.get("DockerRootDir") instanceof String root && !root.isBlank()
            ? root : "/var/lib/docker";
        Row server = Models.get(ServerModel.class).findById(serverId);
        if (server == null || !ServerModel.MODE_SSH.equals(server.get(ServerModel.MODE))) {
            return Files.getFileStore(existingAncestor(Path.of(dockerRoot))).getUsableSpace();
        }
        return remoteAvailable(server, dockerRoot);
    }

    /** df needs an existing path; walk up until one exists (containerized roots). */
    private static Path existingAncestor(Path path) {
        Path current = path;
        while (current != null && !Files.exists(current)) {
            current = current.getParent();
        }
        return current != null ? current : Path.of("/");
    }

    private static long remoteAvailable(Row server, String dockerRoot) throws IOException {
        List<String> argv = HostKeys.sshArgv(server,
            List.of("df", "-B1", "--output=avail", dockerRoot));
        Process process = new ProcessBuilder(argv).start();
        try {
            byte[] output = process.getInputStream().readAllBytes();
            if (!process.waitFor(30, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new IOException("Remote df timed out");
            }
            if (process.exitValue() != 0) {
                throw new IOException("Remote df failed (exit " + process.exitValue() + ")");
            }
            String[] lines = new String(output, StandardCharsets.UTF_8).trim().split("\n");
            return Long.parseLong(lines[lines.length - 1].trim());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("Remote df interrupted");
        } catch (NumberFormatException unparseable) {
            throw new IOException("Remote df answered unexpectedly");
        } finally {
            process.destroyForcibly();
        }
    }

    private static Microcopy violation(String key) {
        return Microcopy.of(key).withFilter("scope", "violations");
    }
}
