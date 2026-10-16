package be.elevenways.hohenheim.server.host;

import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.server.docker.ServerService;
import be.elevenways.hohenheim.server.incus.IncusClient;
import be.elevenways.hohenheim.server.instance.InstanceVolumes;
import be.elevenways.hohenheim.server.runtime.IncusDefinitions;
import be.elevenways.protoblast.common.Blast;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.io.IOException;
import java.time.Instant;
import java.util.Map;

/**
 * A host's disk: the filesystem holding its instance data, measured on the heartbeat and stored as the
 * {@link HostFact#DISK_TOTAL} and {@link HostFact#DISK_FREE} facts beside its memory reading.
 *
 * AIDEV-NOTE: instance data is where the workloads keep it. An Incus host keeps its machines' disks in the storage pool
 * its default profile's root device names; every other host keeps app volumes under the volume root
 * ({@link VolumeBackends#volumeRoot}), which on the live twins is a separate device from the daemon's own root.
 *
 * @author Jelle De Loecker
 * @since  0.10.0
 */
public final class HostDisk {

    /**
     * A stored disk reading.
     *
     * @param space      the filesystem's size and free bytes
     * @param measuredAt when it was measured, null for a reading without its stamp
     * @param fresh      whether it is inside the host facts' freshness bound ({@code capacity.facts_max_age_hours})
     */
    public record Reading(@NonNull DiskSpace space, @Nullable Instant measuredAt, boolean fresh) {
    }

    private HostDisk() {
    }

    /**
     * Measures the named host's instance-data filesystem now; a host that cannot answer is logged and measured as
     * nothing.
     *
     * @return the space, null when the host is unknown or could not be measured
     */
    public static @Nullable DiskSpace measure(@NonNull String serverName) {
        Row server = Models.get(ServerModel.class).findByName(serverName);
        if (server == null) {
            return null;
        }
        try {
            return ServerModel.isIncus(server)
                ? incusSpace(new ServerService().incusClientFor(serverName))
                : InstanceVolumes.spaceOn(serverName);
        } catch (IOException | RuntimeException unanswered) {
            Blast.log("HOST DISK: could not measure", serverName, "-", unanswered.getMessage());
            return null;
        }
    }

    /** Records a reading on the row, stamped {@code at}, without saving it. */
    public static void record(@NonNull Row server, @NonNull DiskSpace space, @NonNull Instant at) {
        HostPreflight.recordReadings(server,
            Map.of(HostFact.DISK_TOTAL, space.totalBytes(), HostFact.DISK_FREE, space.freeBytes()), at);
    }

    /** @return the host's stored disk reading, null when it was never measured */
    public static @Nullable Reading storedOf(@NonNull Row server) {
        if (!(server.get(ServerModel.CAPABILITIES) instanceof Map<?, ?> capabilities)
                || !(capabilities.get(HostFact.DISK_TOTAL.token()) instanceof Number total)
                || !(capabilities.get(HostFact.DISK_FREE.token()) instanceof Number free)
                || total.longValue() <= 0 || free.longValue() < 0 || free.longValue() > total.longValue()) {
            return null;
        }
        Instant measuredAt = HostPreflight.factMeasuredAt(server, HostFact.DISK_TOTAL.token());
        return new Reading(new DiskSpace(total.longValue(), free.longValue()), measuredAt,
            HostPreflight.measuredRecently(measuredAt));
    }

    /** The space of the pool the default profile's root disk lives on ({@link IncusDefinitions#managedPoolName}). */
    public static @NonNull DiskSpace incusSpace(@NonNull IncusClient incus) throws IOException {
        String pool = IncusDefinitions.managedPoolName(incus);
        Map<String, Object> resources = incus.storagePoolResources(pool);
        if (resources.get("space") instanceof Map<?, ?> space
                && space.get("total") instanceof Number total
                && space.get("used") instanceof Number used) {
            return new DiskSpace(total.longValue(), Math.max(0, total.longValue() - used.longValue()));
        }
        throw new IOException("Incus pool '" + pool + "' reported no space usage");
    }
}
