package be.elevenways.hohenheim.server.stack;

import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.model.StackFileModel;
import be.elevenways.hohenheim.model.StackModel;
import be.elevenways.hohenheim.model.StackServiceModel;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * The fully resolved, immutable description of one stack deploy: everything the
 * deployer needs, decoupled from live rows so a deployment's snapshot can be
 * re-deployed verbatim later (rollback). Serializes to plain maps for DRY storage.
 *
 * @param services in DEPENDENCY order (topologically sorted at construction)
 */
public record StackSpec(
    int stackId,
    @NonNull String name,
    @NonNull String serverName,
    @Nullable String registryServer,
    @Nullable String registryUser,
    @Nullable String registryPassword,
    @NonNull List<ServiceSpec> services) {

    /**
     * One service; {@code dependsOn} conditions gate its start.
     *
     * @param serviceId the STACK SERVICE record id -- the row that OWNS this service's
     *        instance. It rides the snapshot too, because a rollback re-deploys onto the
     *        same owned instances and an id-less snapshot could not name them.
     */
    public record ServiceSpec(
        int serviceId,
        @NonNull String name,
        @NonNull String image,
        @NonNull List<String> command,
        @NonNull Map<String, String> environment,
        @NonNull List<MountSpec> mounts,
        @NonNull List<PortSpec> ports,
        @NonNull List<DependsSpec> dependsOn,
        @NonNull List<FileSpec> files,
        @Nullable String healthCmd,
        int healthIntervalSeconds,
        int healthTimeoutSeconds,
        int healthRetries,
        int healthStartPeriodSeconds,
        @NonNull String restartPolicy,
        @Nullable Integer memoryLimitMb,
        @Nullable Double cpuLimit,
        @NonNull List<String> capabilities) {

        /**
         * THE one reading of a service, over either of its two stored shapes: a live
         * {@code stack_services} row or a snapshot map. Both spell every field by the
         * record's own column name, which is what lets one reader serve them.
         *
         * AIDEV-NOTE: reading stays KEY-DRIVEN and tolerant (defaults, never a throw on a
         * missing key): a snapshot written by an older build must still revive, see
         * {@link StackSpec#fromMap}.
         */
        static @NonNull ServiceSpec read(int serviceId, @NonNull Function<String, Object> field,
                                         @NonNull List<?> mounts, @NonNull List<?> ports,
                                         @NonNull List<?> dependsOn, @NonNull List<?> files) {
            return new ServiceSpec(
                serviceId,
                stringOr(field.apply(StackServiceModel.NAME.getName()), ""),
                stringOr(field.apply(StackServiceModel.IMAGE.getName()), ""),
                stringList(field.apply(StackServiceModel.COMMAND.getName())),
                stringMap(field.apply(StackServiceModel.ENVIRONMENT.getName())),
                readAll(mounts, MountSpec::read), readAll(ports, PortSpec::read),
                readAll(dependsOn, DependsSpec::read), readAll(files, FileSpec::read),
                blankToNull(field.apply(StackServiceModel.HEALTH_CMD.getName())),
                intOr(field.apply(StackServiceModel.HEALTH_INTERVAL_SECONDS.getName()), 10),
                intOr(field.apply(StackServiceModel.HEALTH_TIMEOUT_SECONDS.getName()), 5),
                intOr(field.apply(StackServiceModel.HEALTH_RETRIES.getName()), 5),
                intOr(field.apply(StackServiceModel.HEALTH_START_PERIOD_SECONDS.getName()), 0),
                stringOr(field.apply(StackServiceModel.RESTART_POLICY.getName()), "unless-stopped"),
                field.apply(StackServiceModel.MEMORY_LIMIT_MB.getName()) instanceof Number memory
                    ? memory.intValue() : null,
                field.apply(StackServiceModel.CPU_LIMIT.getName()) instanceof Number cpu
                    ? cpu.doubleValue() : null,
                stringList(field.apply(StackServiceModel.CAPABILITIES.getName())));
        }

        /** The snapshot shape {@link #read} revives. */
        @NonNull Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put(SNAPSHOT_SERVICE_ID, this.serviceId);
            map.put(StackServiceModel.NAME.getName(), this.name);
            map.put(StackServiceModel.IMAGE.getName(), this.image);
            map.put(StackServiceModel.COMMAND.getName(), this.command);
            map.put(StackServiceModel.ENVIRONMENT.getName(), this.environment);
            map.put(StackServiceModel.HEALTH_CMD.getName(), this.healthCmd);
            map.put(StackServiceModel.HEALTH_INTERVAL_SECONDS.getName(), this.healthIntervalSeconds);
            map.put(StackServiceModel.HEALTH_TIMEOUT_SECONDS.getName(), this.healthTimeoutSeconds);
            map.put(StackServiceModel.HEALTH_RETRIES.getName(), this.healthRetries);
            map.put(StackServiceModel.HEALTH_START_PERIOD_SECONDS.getName(), this.healthStartPeriodSeconds);
            map.put(StackServiceModel.RESTART_POLICY.getName(), this.restartPolicy);
            map.put(StackServiceModel.MEMORY_LIMIT_MB.getName(), this.memoryLimitMb);
            map.put(StackServiceModel.CPU_LIMIT.getName(), this.cpuLimit);
            map.put(StackServiceModel.CAPABILITIES.getName(), this.capabilities);
            map.put(StackServiceModel.MOUNTS.getName(), mapsOf(this.mounts, MountSpec::toMap));
            map.put(StackServiceModel.PORTS.getName(), mapsOf(this.ports, PortSpec::toMap));
            map.put(StackServiceModel.DEPENDS_ON.getName(), mapsOf(this.dependsOn, DependsSpec::toMap));
            map.put(SNAPSHOT_FILES, mapsOf(this.files, FileSpec::toMap));
            return map;
        }
    }

    /** A named-volume or tmpfs mount; {@code externalName} adopts an existing volume verbatim. */
    public record MountSpec(@NonNull String type, @NonNull String name,
                            @NonNull String containerPath, @Nullable String externalName) {

        /** @return the mount a stored record or snapshot entry declares; null for an incomplete one */
        static @Nullable MountSpec read(@NonNull Function<String, Object> field) {
            String name = stringOr(field.apply(StackServiceModel.MOUNT_NAME.getName()), "");
            String path = stringOr(field.apply(StackServiceModel.MOUNT_PATH.getName()), "");
            if (name.isBlank() || path.isBlank()) {
                return null;
            }
            return new MountSpec(
                stringOr(field.apply(StackServiceModel.MOUNT_TYPE.getName()), StackServiceModel.MOUNT_VOLUME),
                name, path, blankToNull(field.apply(StackServiceModel.MOUNT_EXTERNAL.getName())));
        }

        /** Whether this mount is a named volume the stack MATERIALIZES (not tmpfs, not adopted). */
        public boolean materialized() {
            return !StackServiceModel.MOUNT_TMPFS.equals(this.type) && this.externalName == null;
        }

        @NonNull Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put(StackServiceModel.MOUNT_TYPE.getName(), this.type);
            map.put(StackServiceModel.MOUNT_NAME.getName(), this.name);
            map.put(StackServiceModel.MOUNT_PATH.getName(), this.containerPath);
            map.put(StackServiceModel.MOUNT_EXTERNAL.getName(), this.externalName);
            return map;
        }
    }

    /**
     * A fixed host-port publication. Blank {@code hostIp} publishes on all interfaces.
     *
     * AIDEV-NOTE: {@link #toMap} is ALSO the entry shape of the {@code stack_service} kind's
     * {@code ports} setting (StackInstances.desiredSettings), so the snapshot and the
     * instance settings cannot drift apart; StackServiceKind declares the same names.
     */
    public record PortSpec(int containerPort, int hostPort, @NonNull String protocol, @NonNull String hostIp) {

        /** @return the publication a stored record or snapshot entry declares; null for an incomplete one */
        static @Nullable PortSpec read(@NonNull Function<String, Object> field) {
            if (!(field.apply(StackServiceModel.PORT_CONTAINER.getName()) instanceof Number container)
                    || !(field.apply(StackServiceModel.PORT_HOST.getName()) instanceof Number host)) {
                return null;
            }
            return new PortSpec(container.intValue(), host.intValue(),
                stringOr(field.apply(StackServiceModel.PORT_PROTOCOL.getName()), "tcp"),
                stringOr(field.apply(StackServiceModel.PORT_HOST_IP.getName()), ""));
        }

        @NonNull Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put(StackServiceModel.PORT_CONTAINER.getName(), this.containerPort);
            map.put(StackServiceModel.PORT_HOST.getName(), this.hostPort);
            map.put(StackServiceModel.PORT_PROTOCOL.getName(), this.protocol);
            map.put(StackServiceModel.PORT_HOST_IP.getName(), this.hostIp);
            return map;
        }
    }

    /** A start-order dependency on a sibling service. */
    public record DependsSpec(@NonNull String service, @NonNull String condition) {

        /** @return the dependency a stored record or snapshot entry declares; null without a target */
        static @Nullable DependsSpec read(@NonNull Function<String, Object> field) {
            String target = stringOr(field.apply(StackServiceModel.DEPENDS_SERVICE.getName()), "");
            if (target.isBlank()) {
                return null;
            }
            return new DependsSpec(target, stringOr(
                field.apply(StackServiceModel.DEPENDS_CONDITION.getName()),
                StackServiceModel.CONDITION_STARTED));
        }

        @NonNull Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put(StackServiceModel.DEPENDS_SERVICE.getName(), this.service);
            map.put(StackServiceModel.DEPENDS_CONDITION.getName(), this.condition);
            return map;
        }
    }

    /** A config file uploaded into the container before start. */
    public record FileSpec(@NonNull String containerPath, @NonNull String content, @NonNull String mode) {

        /** @return the file a stored record or snapshot entry declares; null without a path */
        static @Nullable FileSpec read(@NonNull Function<String, Object> field) {
            String path = stringOr(field.apply(StackFileModel.CONTAINER_PATH.getName()), "");
            if (path.isBlank()) {
                return null;
            }
            return new FileSpec(path,
                stringOr(field.apply(StackFileModel.CONTENT.getName()), ""),
                stringOr(field.apply(StackFileModel.MODE.getName()), "0644"));
        }

        @NonNull Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put(StackFileModel.CONTAINER_PATH.getName(), this.containerPath);
            map.put(StackFileModel.CONTENT.getName(), this.content);
            map.put(StackFileModel.MODE.getName(), this.mode);
            return map;
        }
    }

    /** The snapshot key a service's record id rides under (the record calls it {@code id}). */
    private static final String SNAPSHOT_SERVICE_ID = "service_id";

    /** The snapshot key of a service's config files, which live in their own table on the record side. */
    private static final String SNAPSHOT_FILES = "files";

    /**
     * Resolve the deployable spec from the live records: enabled services only,
     * dependency-ordered.
     *
     * @throws IllegalStateException on unknown dependencies or dependency cycles
     */
    public static @NonNull StackSpec fromRecords(@NonNull Row stack) {
        return fromRecords(stack, true);
    }

    private static @NonNull StackSpec fromRecords(@NonNull Row stack, boolean ordered) {
        int stackId = stack.get(StackModel.ID);
        StackServiceModel serviceModel = Models.get(StackServiceModel.class);
        StackFileModel fileModel = Models.get(StackFileModel.class);

        List<ServiceSpec> services = new ArrayList<>();
        for (Row serviceRow : serviceModel.findByStackId(stackId)) {
            if (!Boolean.TRUE.equals(serviceRow.get(StackServiceModel.ENABLED))) {
                continue;
            }
            services.add(serviceOf(serviceRow, fileModel));
        }

        return new StackSpec(
            stackId,
            stack.get(StackModel.NAME),
            ServerModel.nameOf(stack.get(StackModel.SERVER_ID)),
            blankToNull(stack.get(StackModel.REGISTRY_SERVER)),
            blankToNull(stack.get(StackModel.REGISTRY_USER)),
            blankToNull(stack.get(StackModel.REGISTRY_PASSWORD)),
            ordered ? topologicallySorted(services) : List.copyOf(services));
    }

    /**
     * Resolve the records WITHOUT ordering the services.
     *
     * AIDEV-NOTE: teardown must never depend on a valid dependency graph. Two
     * individually-valid saves can still form a cycle (A depends on B, then B on A),
     * and {@link #topologicallySorted} rightly refuses that -- but if destroy resolved
     * through it, such a stack could no longer be deleted or cleaned up at all. Deploy
     * keeps the ordered path; stop/destroy use this one.
     */
    public static @NonNull StackSpec fromRecordsUnordered(@NonNull Row stack) {
        return fromRecords(stack, false);
    }

    private static ServiceSpec serviceOf(Row serviceRow, StackFileModel fileModel) {
        Integer serviceId = serviceRow.get(StackServiceModel.ID);
        List<Row> files = serviceId == null ? List.of() : fileModel.findByServiceId(serviceId);
        return ServiceSpec.read(intOr(serviceId, 0), serviceRow::get,
            serviceRow.getRecords(StackServiceModel.MOUNTS),
            serviceRow.getRecords(StackServiceModel.PORTS),
            serviceRow.getRecords(StackServiceModel.DEPENDS_ON),
            files);
    }

    /**
     * Kahn's algorithm over depends_on edges; stable (declaration order among ready
     * services). Dependencies on disabled/unknown services or cycles fail loudly.
     */
    public static @NonNull List<ServiceSpec> topologicallySorted(@NonNull List<ServiceSpec> services) {
        Map<String, ServiceSpec> byName = new LinkedHashMap<>();
        for (ServiceSpec service : services) {
            if (byName.put(service.name(), service) != null) {
                throw new IllegalStateException("Duplicate service name '" + service.name() + "'");
            }
        }
        for (ServiceSpec service : services) {
            for (DependsSpec dependency : service.dependsOn()) {
                if (!byName.containsKey(dependency.service())) {
                    throw new IllegalStateException("Service '" + service.name()
                        + "' depends on unknown or disabled service '" + dependency.service() + "'");
                }
            }
        }

        List<ServiceSpec> sorted = new ArrayList<>(services.size());
        Set<String> placed = new LinkedHashSet<>();
        while (placed.size() < services.size()) {
            boolean progressed = false;
            for (ServiceSpec service : services) {
                if (placed.contains(service.name())) {
                    continue;
                }
                boolean ready = true;
                for (DependsSpec dependency : service.dependsOn()) {
                    if (!placed.contains(dependency.service())) {
                        ready = false;
                        break;
                    }
                }
                if (ready) {
                    sorted.add(service);
                    placed.add(service.name());
                    progressed = true;
                }
            }
            if (!progressed) {
                List<String> remaining = new ArrayList<>();
                for (ServiceSpec service : services) {
                    if (!placed.contains(service.name())) {
                        remaining.add(service.name());
                    }
                }
                throw new IllegalStateException("Dependency cycle among services " + remaining);
            }
        }
        return List.copyOf(sorted);
    }

    // -- snapshot serialization (plain maps for DRY storage) -----------------

    public @NonNull Map<String, Object> toMap() {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("stack_id", stackId);
        root.put("name", name);
        root.put("server_name", serverName);
        root.put("registry_server", registryServer);
        root.put("registry_user", registryUser);
        root.put("registry_password", registryPassword);
        root.put("services", mapsOf(services, ServiceSpec::toMap));
        return root;
    }

    /**
     * Revive a stored snapshot, which may predate the current record shape.
     *
     * AIDEV-NOTE: reviving is KEY-DRIVEN, never positional or exhaustive, and that is
     * what makes dropping a field safe: a snapshot written before M084 still carries
     * {@code "subnet"} and is simply not asked for it, so an old rollback deserializes
     * instead of throwing. Any field REMOVED from this record must keep that property --
     * never switch this to a strict/unknown-key-rejecting read.
     */
    @SuppressWarnings("unchecked")
    public static @NonNull StackSpec fromMap(@NonNull Map<String, Object> root) {
        List<ServiceSpec> services = new ArrayList<>();
        for (Object entry : listOf(root.get("services"))) {
            if (!(entry instanceof Map<?, ?> map)) {
                continue;
            }
            services.add(ServiceSpec.read(intOr(map.get(SNAPSHOT_SERVICE_ID), 0), map::get,
                listOf(map.get(StackServiceModel.MOUNTS.getName())),
                listOf(map.get(StackServiceModel.PORTS.getName())),
                listOf(map.get(StackServiceModel.DEPENDS_ON.getName())),
                listOf(map.get(SNAPSHOT_FILES))));
        }

        return new StackSpec(
            intOr(root.get("stack_id"), 0),
            stringOr(root.get("name"), ""),
            stringOr(root.get("server_name"), "local"),
            blankToNull(root.get("registry_server")),
            blankToNull(root.get("registry_user")),
            blankToNull(root.get("registry_password")),
            List.copyOf(services));
    }

    // -- shared shape helpers ----------------------------------------------------

    /**
     * Read every entry of a stored list, whichever shape it has: a table-stored record
     * {@link Row} or a snapshot map. An entry the reader rejects (incomplete) is skipped.
     */
    private static <T> @NonNull List<T> readAll(@NonNull List<?> entries,
                                                @NonNull Function<Function<String, Object>, T> reader) {
        List<T> read = new ArrayList<>();
        for (Object entry : entries) {
            Function<String, Object> field;
            if (entry instanceof Row row) {
                field = row::get;
            } else if (entry instanceof Map<?, ?> map) {
                field = map::get;
            } else {
                continue;
            }
            T value = reader.apply(field);
            if (value != null) {
                read.add(value);
            }
        }
        return List.copyOf(read);
    }

    private static <T> @NonNull List<Map<String, Object>> mapsOf(@NonNull List<T> values,
                                                                 @NonNull Function<T, Map<String, Object>> writer) {
        List<Map<String, Object>> maps = new ArrayList<>(values.size());
        for (T value : values) {
            maps.add(writer.apply(value));
        }
        return maps;
    }

    private static @NonNull List<?> listOf(@Nullable Object value) {
        return value instanceof List<?> list ? list : List.of();
    }

    private static @NonNull List<String> stringList(@Nullable Object value) {
        List<String> strings = new ArrayList<>();
        for (Object entry : listOf(value)) {
            strings.add(String.valueOf(entry));
        }
        return List.copyOf(strings);
    }

    private static @NonNull Map<String, String> stringMap(@Nullable Object value) {
        Map<String, String> strings = new LinkedHashMap<>();
        if (value instanceof Map<?, ?> map) {
            map.forEach((key, entry) -> strings.put(String.valueOf(key), String.valueOf(entry)));
        }
        return Map.copyOf(strings);
    }

    private static @Nullable String blankToNull(@Nullable Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value);
        return text.isBlank() ? null : text;
    }

    private static @NonNull String stringOr(@Nullable Object value, @NonNull String fallback) {
        if (value == null) {
            return fallback;
        }
        String text = String.valueOf(value);
        return text.isBlank() ? fallback : text;
    }

    private static int intOr(@Nullable Object value, int fallback) {
        return value instanceof Number number ? number.intValue() : fallback;
    }
}
