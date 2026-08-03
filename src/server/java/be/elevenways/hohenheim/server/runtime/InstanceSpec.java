package be.elevenways.hohenheim.server.runtime;

import be.elevenways.hohenheim.server.docker.ResourceLimits;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.List;
import java.util.Map;

/**
 * Everything a driver needs to create one instance workload. The owner labels ride the
 * spec because they MUST land at create time -- before any port exists (record-after
 * honesty) and before any named volume is born (Docker never relabels a volume).
 *
 * @param handle        the runtime resource name, e.g. {@code hohenheim-instance-7}
 * @param image         image reference (repo[:tag] resolved by the caller)
 * @param command       command override, or null to keep the image's default
 * @param env           environment variables (name to value, ordered)
 * @param volumes       persistent named volumes: volume name to container path
 * @param publishPort   container TCP port to publish on a loopback ephemeral host port,
 *                      or null for none. TCP only: record-after readback looks up
 *                      {@code {port}/tcp}, so UDP needs the declared pre-allocation
 *                      mode, which does not exist yet -- do not pretend otherwise.
 * @param limits        cgroup resource caps
 * @param ownerLabels   the OwnerLabels pair of the owning record
 */
public record InstanceSpec(@NonNull String handle,
                           @NonNull String image,
                           @Nullable List<String> command,
                           @NonNull Map<String, String> env,
                           @NonNull Map<String, String> volumes,
                           @Nullable Integer publishPort,
                           @NonNull ResourceLimits limits,
                           @NonNull Map<String, String> ownerLabels) {}
