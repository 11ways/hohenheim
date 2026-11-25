package be.elevenways.hohenheim.test.instance;

import be.elevenways.hohenheim.instance.InstanceKindFields;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Docker container app rows for the suites that judge stored memory amounts, built and never deployed.
 *
 * @author Jelle De Loecker
 * @since  0.10.0
 */
public final class TestWorkloads {

    public static final String DOCKER_KIND = "hohenheim:docker_container";

    private TestWorkloads() {
    }

    /**
     * Builds an unsaved Docker container app on {@code serverId}.
     *
     * @param memoryLimitMb its {@code memory_limit_mb}, null for its kind's default
     */
    public static @NonNull Row dockerContainer(@NonNull String name, int serverId, @Nullable Integer memoryLimitMb) {
        return dockerContainer(name, serverId, memoryLimitMb, null);
    }

    /**
     * Builds an unsaved Docker container app on {@code serverId} that reserves less than its maximum.
     *
     * @param memoryLimitMb    its {@code memory_limit_mb} (the maximum), null for its kind's default
     * @param memoryReservedMb its {@code memory_reserved_mb}, null to reserve its maximum
     */
    public static @NonNull Row dockerContainer(@NonNull String name, int serverId, @Nullable Integer memoryLimitMb,
                                               @Nullable Integer memoryReservedMb) {
        Row row = Models.get(InstanceModel.class).createEmptyRow();
        row.set(InstanceModel.NAME, name);
        row.set(InstanceModel.KIND, DOCKER_KIND);
        Map<String, Object> settings = new LinkedHashMap<>();
        settings.put("image", "fake/image");
        if (memoryLimitMb != null) {
            settings.put(InstanceKindFields.MEMORY_LIMIT_MB, memoryLimitMb);
        }
        if (memoryReservedMb != null) {
            settings.put(InstanceKindFields.MEMORY_RESERVED_MB, memoryReservedMb);
        }
        row.set(InstanceModel.SETTINGS, settings);
        row.set(InstanceModel.HOST_ID, serverId);
        return row;
    }
}
