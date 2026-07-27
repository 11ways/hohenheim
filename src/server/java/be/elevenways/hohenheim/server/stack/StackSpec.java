package be.elevenways.hohenheim.server.stack;

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
    @Nullable String subnet,
    boolean adoptResources,
    @Nullable String registryServer,
    @Nullable String registryUser,
    @Nullable String registryPassword,
    @NonNull List<ServiceSpec> services) {

    /** One service; {@code dependsOn} conditions gate its start. */
    public record ServiceSpec(
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
        @Nullable Double cpuLimit) {}

    /** A named-volume or tmpfs mount; {@code externalName} adopts an existing volume verbatim. */
    public record MountSpec(@NonNull String type, @NonNull String name,
                            @NonNull String containerPath, @Nullable String externalName) {}

    /** A fixed host-port publication. Blank {@code hostIp} publishes on all interfaces. */
    public record PortSpec(int containerPort, int hostPort, @NonNull String protocol, @NonNull String hostIp) {}

    /** A start-order dependency on a sibling service. */
    public record DependsSpec(@NonNull String service, @NonNull String condition) {}

    /** A config file uploaded into the container before start. */
    public record FileSpec(@NonNull String containerPath, @NonNull String content, @NonNull String mode) {}

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

        String server = stack.get(StackModel.SERVER_NAME);
        return new StackSpec(
            stackId,
            stack.get(StackModel.NAME),
            server != null && !server.isBlank() ? server : "local",
            blankToNull(stack.get(StackModel.SUBNET)),
            Boolean.TRUE.equals(stack.get(StackModel.ADOPT_RESOURCES)),
            blankToNull(stack.get(StackModel.REGISTRY_SERVER)),
            blankToNull(stack.get(StackModel.REGISTRY_USER)),
            blankToNull(stack.get(StackModel.REGISTRY_PASSWORD)),
            ordered ? topologicallySorted(services) : List.copyOf(services));
    }

    /**
     * Resolve the records WITHOUT ordering the services.
     *
     * <p>AIDEV-NOTE: teardown must never depend on a valid dependency graph. Two
     * individually-valid saves can still form a cycle (A depends on B, then B on A),
     * and {@link #topologicallySorted} rightly refuses that -- but if destroy resolved
     * through it, such a stack could no longer be deleted or cleaned up at all. Deploy
     * keeps the ordered path; stop/destroy use this one.
     */
    public static @NonNull StackSpec fromRecordsUnordered(@NonNull Row stack) {
        return fromRecords(stack, false);
    }

    private static ServiceSpec serviceOf(Row serviceRow, StackFileModel fileModel) {
        List<MountSpec> mounts = new ArrayList<>();
        for (Row mount : serviceRow.getRecords(StackServiceModel.MOUNTS)) {
            String name = stringOr(mount.get(StackServiceModel.MOUNT_NAME), "");
            String path = stringOr(mount.get(StackServiceModel.MOUNT_PATH), "");
            if (name.isBlank() || path.isBlank()) {
                continue;
            }
            mounts.add(new MountSpec(
                stringOr(mount.get(StackServiceModel.MOUNT_TYPE), StackServiceModel.MOUNT_VOLUME),
                name, path,
                blankToNull(mount.get(StackServiceModel.MOUNT_EXTERNAL))));
        }

        List<PortSpec> ports = new ArrayList<>();
        for (Row port : serviceRow.getRecords(StackServiceModel.PORTS)) {
            Integer containerPort = port.get(StackServiceModel.PORT_CONTAINER);
            Integer hostPort = port.get(StackServiceModel.PORT_HOST);
            if (containerPort == null || hostPort == null) {
                continue;
            }
            ports.add(new PortSpec(containerPort, hostPort,
                stringOr(port.get(StackServiceModel.PORT_PROTOCOL), "tcp"),
                stringOr(port.get(StackServiceModel.PORT_HOST_IP), "")));
        }

        List<DependsSpec> depends = new ArrayList<>();
        for (Row dependency : serviceRow.getRecords(StackServiceModel.DEPENDS_ON)) {
            String target = stringOr(dependency.get(StackServiceModel.DEPENDS_SERVICE), "");
            if (target.isBlank()) {
                continue;
            }
            depends.add(new DependsSpec(target,
                stringOr(dependency.get(StackServiceModel.DEPENDS_CONDITION), StackServiceModel.CONDITION_STARTED)));
        }

        List<FileSpec> files = new ArrayList<>();
        Integer serviceId = serviceRow.get(StackServiceModel.ID);
        if (serviceId != null) {
            for (Row file : fileModel.findByServiceId(serviceId)) {
                String path = stringOr(file.get(StackFileModel.CONTAINER_PATH), "");
                if (path.isBlank()) {
                    continue;
                }
                files.add(new FileSpec(path,
                    stringOr(file.get(StackFileModel.CONTENT), ""),
                    stringOr(file.get(StackFileModel.MODE), "0644")));
            }
        }

        List<String> command = serviceRow.get(StackServiceModel.COMMAND);
        Map<String, String> environment = serviceRow.get(StackServiceModel.ENVIRONMENT);

        return new ServiceSpec(
            serviceRow.get(StackServiceModel.NAME),
            serviceRow.get(StackServiceModel.IMAGE),
            command != null ? List.copyOf(command) : List.of(),
            environment != null ? Map.copyOf(environment) : Map.of(),
            List.copyOf(mounts), List.copyOf(ports), List.copyOf(depends), List.copyOf(files),
            blankToNull(serviceRow.get(StackServiceModel.HEALTH_CMD)),
            intOr(serviceRow.get(StackServiceModel.HEALTH_INTERVAL_SECONDS), 10),
            intOr(serviceRow.get(StackServiceModel.HEALTH_TIMEOUT_SECONDS), 5),
            intOr(serviceRow.get(StackServiceModel.HEALTH_RETRIES), 5),
            intOr(serviceRow.get(StackServiceModel.HEALTH_START_PERIOD_SECONDS), 0),
            stringOr(serviceRow.get(StackServiceModel.RESTART_POLICY), "unless-stopped"),
            serviceRow.get(StackServiceModel.MEMORY_LIMIT_MB),
            serviceRow.get(StackServiceModel.CPU_LIMIT));
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
        root.put("subnet", subnet);
        root.put("adopt_resources", adoptResources);
        root.put("registry_server", registryServer);
        root.put("registry_user", registryUser);
        root.put("registry_password", registryPassword);

        List<Map<String, Object>> serviceMaps = new ArrayList<>();
        for (ServiceSpec service : services) {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("name", service.name());
            map.put("image", service.image());
            map.put("command", service.command());
            map.put("environment", service.environment());
            map.put("health_cmd", service.healthCmd());
            map.put("health_interval_seconds", service.healthIntervalSeconds());
            map.put("health_timeout_seconds", service.healthTimeoutSeconds());
            map.put("health_retries", service.healthRetries());
            map.put("health_start_period_seconds", service.healthStartPeriodSeconds());
            map.put("restart_policy", service.restartPolicy());
            map.put("memory_limit_mb", service.memoryLimitMb());
            map.put("cpu_limit", service.cpuLimit());

            List<Map<String, Object>> mounts = new ArrayList<>();
            for (MountSpec mount : service.mounts()) {
                Map<String, Object> mountMap = new LinkedHashMap<>();
                mountMap.put("type", mount.type());
                mountMap.put("name", mount.name());
                mountMap.put("container_path", mount.containerPath());
                mountMap.put("external_name", mount.externalName());
                mounts.add(mountMap);
            }
            map.put("mounts", mounts);

            List<Map<String, Object>> ports = new ArrayList<>();
            for (PortSpec port : service.ports()) {
                Map<String, Object> portMap = new LinkedHashMap<>();
                portMap.put("container_port", port.containerPort());
                portMap.put("host_port", port.hostPort());
                portMap.put("protocol", port.protocol());
                portMap.put("host_ip", port.hostIp());
                ports.add(portMap);
            }
            map.put("ports", ports);

            List<Map<String, Object>> depends = new ArrayList<>();
            for (DependsSpec dependency : service.dependsOn()) {
                Map<String, Object> dependencyMap = new LinkedHashMap<>();
                dependencyMap.put("service", dependency.service());
                dependencyMap.put("condition", dependency.condition());
                depends.add(dependencyMap);
            }
            map.put("depends_on", depends);

            List<Map<String, Object>> files = new ArrayList<>();
            for (FileSpec file : service.files()) {
                Map<String, Object> fileMap = new LinkedHashMap<>();
                fileMap.put("container_path", file.containerPath());
                fileMap.put("content", file.content());
                fileMap.put("mode", file.mode());
                files.add(fileMap);
            }
            map.put("files", files);

            serviceMaps.add(map);
        }
        root.put("services", serviceMaps);
        return root;
    }

    @SuppressWarnings("unchecked")
    public static @NonNull StackSpec fromMap(@NonNull Map<String, Object> root) {
        List<ServiceSpec> services = new ArrayList<>();
        for (Object entry : (List<Object>) root.getOrDefault("services", List.of())) {
            Map<String, Object> map = (Map<String, Object>) entry;

            List<MountSpec> mounts = new ArrayList<>();
            for (Object rawMount : (List<Object>) map.getOrDefault("mounts", List.of())) {
                Map<String, Object> mount = (Map<String, Object>) rawMount;
                mounts.add(new MountSpec(
                    stringOr(mount.get("type"), StackServiceModel.MOUNT_VOLUME),
                    stringOr(mount.get("name"), ""),
                    stringOr(mount.get("container_path"), ""),
                    blankToNull(mount.get("external_name"))));
            }

            List<PortSpec> ports = new ArrayList<>();
            for (Object rawPort : (List<Object>) map.getOrDefault("ports", List.of())) {
                Map<String, Object> port = (Map<String, Object>) rawPort;
                ports.add(new PortSpec(
                    intOr(port.get("container_port"), 0),
                    intOr(port.get("host_port"), 0),
                    stringOr(port.get("protocol"), "tcp"),
                    stringOr(port.get("host_ip"), "")));
            }

            List<DependsSpec> depends = new ArrayList<>();
            for (Object rawDependency : (List<Object>) map.getOrDefault("depends_on", List.of())) {
                Map<String, Object> dependency = (Map<String, Object>) rawDependency;
                depends.add(new DependsSpec(
                    stringOr(dependency.get("service"), ""),
                    stringOr(dependency.get("condition"), StackServiceModel.CONDITION_STARTED)));
            }

            List<FileSpec> files = new ArrayList<>();
            for (Object rawFile : (List<Object>) map.getOrDefault("files", List.of())) {
                Map<String, Object> file = (Map<String, Object>) rawFile;
                files.add(new FileSpec(
                    stringOr(file.get("container_path"), ""),
                    stringOr(file.get("content"), ""),
                    stringOr(file.get("mode"), "0644")));
            }

            List<String> command = new ArrayList<>();
            for (Object arg : (List<Object>) map.getOrDefault("command", List.of())) {
                command.add(String.valueOf(arg));
            }
            Map<String, String> environment = new LinkedHashMap<>();
            Object rawEnvironment = map.get("environment");
            if (rawEnvironment instanceof Map<?, ?> environmentMap) {
                for (Map.Entry<?, ?> variable : environmentMap.entrySet()) {
                    environment.put(String.valueOf(variable.getKey()), String.valueOf(variable.getValue()));
                }
            }

            services.add(new ServiceSpec(
                stringOr(map.get("name"), ""),
                stringOr(map.get("image"), ""),
                List.copyOf(command),
                Map.copyOf(environment),
                List.copyOf(mounts), List.copyOf(ports), List.copyOf(depends), List.copyOf(files),
                blankToNull(map.get("health_cmd")),
                intOr(map.get("health_interval_seconds"), 10),
                intOr(map.get("health_timeout_seconds"), 5),
                intOr(map.get("health_retries"), 5),
                intOr(map.get("health_start_period_seconds"), 0),
                stringOr(map.get("restart_policy"), "unless-stopped"),
                (Integer) map.get("memory_limit_mb"),
                map.get("cpu_limit") instanceof Number cpu ? cpu.doubleValue() : null));
        }

        return new StackSpec(
            intOr(root.get("stack_id"), 0),
            stringOr(root.get("name"), ""),
            stringOr(root.get("server_name"), "local"),
            blankToNull(root.get("subnet")),
            Boolean.TRUE.equals(root.get("adopt_resources")),
            blankToNull(root.get("registry_server")),
            blankToNull(root.get("registry_user")),
            blankToNull(root.get("registry_password")),
            List.copyOf(services));
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
