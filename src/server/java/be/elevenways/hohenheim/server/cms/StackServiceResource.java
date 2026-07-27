package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.model.StackFileModel;
import be.elevenways.hohenheim.model.StackModel;
import be.elevenways.hohenheim.model.StackServiceModel;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.cms.common.panel.NavGroup;
import be.elevenways.zenit.cms.common.resource.ResourceParent;
import be.elevenways.zenit.cms.common.resource.RowResource;
import be.elevenways.zenit.cms.common.schema.ColumnSpec;
import be.elevenways.zenit.cms.common.schema.TableSpec;
import be.elevenways.zenit.common.conduit.Conduit;
import be.elevenways.zenit.common.edit.FieldFormEntryRegistry;
import be.elevenways.zenit.common.edit.FormSpec;
import be.elevenways.zenit.common.edit.RelationPick;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.common.ui.Icon;
import be.elevenways.zenit.common.validation.Violations;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Stack service entries: image, command, environment, mounts, ports and
 * dependencies. Hidden from the sidebar -- reached through a stack's
 * Services tab. Saving never deploys; the stack's Deploy action does.
 */
public class StackServiceResource extends RowResource {

    private final FormSpec formSpec = FormSpec.builder()
        .add(RelationPick.of(StackServiceModel.STACK_ID, StackModel.MODEL_ID).build())
        .add(StackServiceModel.NAME)
        .add(StackServiceModel.ENABLED)
        .add(StackServiceModel.IMAGE)
        .add(FieldFormEntryRegistry.INSTANCE.deriveEntry(StackServiceModel.COMMAND))
        .add(FieldFormEntryRegistry.INSTANCE.deriveEntry(StackServiceModel.ENVIRONMENT))
        .add(FieldFormEntryRegistry.INSTANCE.deriveEntry(StackServiceModel.MOUNTS))
        .add(FieldFormEntryRegistry.INSTANCE.deriveEntry(StackServiceModel.PORTS))
        .add(FieldFormEntryRegistry.INSTANCE.deriveEntry(StackServiceModel.DEPENDS_ON))
        .add(StackServiceModel.HEALTH_CMD)
        .add(StackServiceModel.HEALTH_INTERVAL_SECONDS)
        .add(StackServiceModel.HEALTH_TIMEOUT_SECONDS)
        .add(StackServiceModel.HEALTH_RETRIES)
        .add(StackServiceModel.HEALTH_START_PERIOD_SECONDS)
        .add(FieldFormEntryRegistry.INSTANCE.deriveEntry(StackServiceModel.RESTART_POLICY))
        .add(StackServiceModel.MEMORY_LIMIT_MB)
        .add(StackServiceModel.CPU_LIMIT)
        .build();

    private final TableSpec<Row> tableSpec = TableSpec.<Row>builder()
        .column(ColumnSpec.fromField(StackServiceModel.NAME).build())
        .column(ColumnSpec.fromField(StackServiceModel.IMAGE).build())
        .column(ColumnSpec.fromField(StackServiceModel.ENABLED).build())
        .column(ColumnSpec.fromField(StackServiceModel.STACK_ID)
            .relation(RelationPick.of(StackServiceModel.STACK_ID, StackModel.MODEL_ID).build()).build())
        .build();

    @Override public @NonNull Identifier id() { return Identifier.of("hohenheim", "stack_service"); }
    @Override public @NonNull Microcopy label() { return Microcopy.of("plural").withFilter("scope", "stack_service"); }
    @Override public @NonNull String slug() { return "stack-services"; }
    @Override public @NonNull Model model() { return Models.get(StackServiceModel.class); }
    @Override public @NonNull FormSpec formSpec() { return this.formSpec; }
    @Override public @NonNull TableSpec<Row> tableSpec() { return this.tableSpec; }
    @Override public @NonNull NavGroup navGroup() { return HohenheimPanel.INFRA_GROUP; }
    @Override public int navOrder() { return 26; }
    @Override public @NonNull Icon icon() { return Icon.of("cube"); }
    @Override public boolean showInNav() { return false; }

    @Override
    public @Nullable ResourceParent<Row> parent() {
        return ResourceParent.<Row>of("stacks", row -> row.get(StackServiceModel.STACK_ID)).tab("services");
    }

    /** The stack's Services tab links here with ?stack_id= so the pick is preselected. */
    @Override
    public @NonNull Map<String, Object> createValues(@NonNull Conduit conduit) {
        Map<String, Object> values = new LinkedHashMap<>(formSpec().defaultValues());
        String stackId = conduit.getQueryParam("stack_id");
        if (stackId != null && !stackId.isEmpty()) {
            try {
                values.put("stack_id", Integer.parseInt(stackId));
            } catch (NumberFormatException ignored) {
                // Malformed prefill: render the bare form.
            }
        }
        return Map.copyOf(values);
    }

    @Override
    public @NonNull Object persistRow(@NonNull Map<String, Object> coerced,
                                      @NonNull AccessContext accessContext) {
        Map<String, Object> values = CmsSupport.mutable(coerced);
        validate(values, null);
        return super.persistRow(values, accessContext);
    }

    @Override
    public void updateRow(@NonNull Row existing, @NonNull Map<String, Object> coerced,
                          @NonNull AccessContext accessContext) {
        Map<String, Object> values = CmsSupport.mutable(coerced);
        validate(values, existing);
        super.updateRow(existing, values, accessContext);
    }

    /**
     * Config files hang off the service with no FK cascade, and their content is
     * encrypted-at-rest credential material: leaving them behind would keep secrets
     * in a table no UI can reach any more.
     *
     * @throws Violations when a sibling still depends on this service; deleting it
     *         would leave an unresolvable graph that blocks the next deploy
     */
    @Override
    public void deleteRow(@NonNull Row row, @NonNull AccessContext accessContext) {
        Integer serviceId = row.get(StackServiceModel.ID);
        Integer stackId = row.get(StackServiceModel.STACK_ID);
        String name = String.valueOf(row.get(StackServiceModel.NAME));
        if (stackId != null) {
            refuseWhenDependedUpon(stackId, serviceId, name);
        }
        if (serviceId != null) {
            Models.get(StackFileModel.class).find()
                .where(StackFileModel.STACK_SERVICE_ID.eq(serviceId)).delete();
        }
        super.deleteRow(row, accessContext);
    }

    /** Service names become container names and DNS aliases; ports must be real ports.
     *  Canonical (trimmed) values are written BACK into the coerced map and record rows:
     *  validating a trimmed copy while persisting the raw one lets "web " slip past the
     *  sibling check and become an invalid Docker name. */
    private void validate(@NonNull Map<String, Object> coerced, @Nullable Row existing) {
        String name = trimmed(coerced.containsKey("name") ? coerced.get("name")
            : existing != null ? existing.get(StackServiceModel.NAME) : null);
        if (!StackResource.NAME_PATTERN.matcher(name).matches()) {
            throw Violations.ofField("name", name, CmsSupport.violationText("service_name_format"));
        }
        if (coerced.containsKey("name")) {
            coerced.put("name", name);
        }

        Object stackIdValue = coerced.containsKey("stack_id") ? coerced.get("stack_id")
            : existing != null ? existing.get(StackServiceModel.STACK_ID) : null;
        if (!(stackIdValue instanceof Integer stackId)) {
            throw Violations.ofField("stack_id", stackIdValue, CmsSupport.violationText("stack_required"));
        }
        Integer existingId = existing != null ? existing.get(StackServiceModel.ID) : null;
        List<Row> siblings = this.model().find()
            .where(StackServiceModel.STACK_ID.eq(stackId)).all();

        for (Row sibling : siblings) {
            if (sibling.get(StackServiceModel.ID).equals(existingId)) {
                continue;
            }
            if (name.equals(trimmed(sibling.get(StackServiceModel.NAME)))) {
                throw Violations.ofField("name", name, CmsSupport.violationText("service_name_taken"));
            }
        }

        validateMounts(coerced, existingId);
        validatePorts(coerced, stackId, siblings, existingId);
        validateDependsOn(coerced, name, siblings, existingId);
        validateHealth(coerced);

        // Renaming or disabling must not strand siblings' dependencies: their
        // depends_on still names this service, and the next deploy would fail
        // resolving the graph (a broken graph even blocked deletion once).
        if (existing != null) {
            String oldName = trimmed(existing.get(StackServiceModel.NAME));
            boolean renamed = coerced.containsKey("name") && !oldName.equals(name);
            boolean disabled = coerced.containsKey("enabled")
                && Boolean.FALSE.equals(coerced.get("enabled"))
                && Boolean.TRUE.equals(existing.get(StackServiceModel.ENABLED));
            if (renamed || disabled) {
                refuseWhenDependedUpon(stackId, existingId, oldName);
            }
        }
    }

    /**
     * @throws Violations when any OTHER service of the stack declares a dependency on
     *         {@code name}
     */
    private static void refuseWhenDependedUpon(int stackId, @Nullable Integer serviceId,
                                               @NonNull String name) {
        for (Row sibling : Models.get(StackServiceModel.class).find()
                .where(StackServiceModel.STACK_ID.eq(stackId)).all()) {
            if (sibling.get(StackServiceModel.ID).equals(serviceId)) {
                continue;
            }
            for (Row depends : sibling.getRecords(StackServiceModel.DEPENDS_ON)) {
                if (name.equals(trimmed(depends.get(StackServiceModel.DEPENDS_SERVICE)))) {
                    throw Violations.ofField("name", name,
                        CmsSupport.violationText("service_still_depended_upon")
                            .withArg("service", String.valueOf(sibling.get(StackServiceModel.NAME))));
                }
            }
        }
    }

    /** Mount names become volume-name segments; container paths must be absolute and
     *  must not shadow the service's staged config files. */
    private static void validateMounts(@NonNull Map<String, Object> coerced, @Nullable Integer serviceId) {
        List<Row> files = serviceId == null ? List.of()
            : Models.get(StackFileModel.class).find()
                .where(StackFileModel.STACK_SERVICE_ID.eq(serviceId)).all();
        Set<String> paths = new HashSet<>();
        int index = -1;
        for (Row mount : recordsOf(coerced, "mounts")) {
            index++;
            String mountName = trimmed(mount.get("name"));
            String external = trimmed(mount.get("external_name"));
            String path = trimmed(mount.get("container_path"));
            if (mountName.isEmpty() && external.isEmpty() && path.isEmpty()) {
                continue;   // an untouched blank row the editor added; nothing to check
            }
            mount.set(StackServiceModel.MOUNT_NAME, mountName);
            mount.set(StackServiceModel.MOUNT_EXTERNAL, external);
            mount.set(StackServiceModel.MOUNT_PATH, path);
            boolean volume = !StackServiceModel.MOUNT_TMPFS.equals(trimmed(mount.get("type")));
            // A named volume derives "hohenheim-stack-<stack>-<name>", so the name has to be
            // a safe Docker name segment; an external volume brings its own name instead.
            if (volume && external.isEmpty() && !StackResource.NAME_PATTERN.matcher(mountName).matches()) {
                throw Violations.ofField("mounts." + index + ".name", mountName,
                    CmsSupport.violationText("mount_name_format"));
            }
            if (!path.startsWith("/")) {
                throw Violations.ofField("mounts." + index + ".container_path", path,
                    CmsSupport.violationText("mount_path_absolute"));
            }
            if (!paths.add(path)) {
                throw Violations.ofField("mounts." + index + ".container_path", path,
                    CmsSupport.violationText("mount_path_taken"));
            }
            // The mirror of StackFileResource's shadow refusal: adding the mount AFTER
            // the file must be refused exactly like adding the file after the mount --
            // either order silently hides the staged file at container start.
            String prefix = path.endsWith("/") ? path : path + "/";
            for (Row file : files) {
                String filePath = trimmed(file.get(StackFileModel.CONTAINER_PATH));
                if (filePath.equals(path) || filePath.startsWith(prefix)) {
                    throw Violations.ofField("mounts." + index + ".container_path", path,
                        CmsSupport.violationText("mount_shadows_file").withArg("file", filePath));
                }
            }
        }
    }

    /**
     * Published host ports are a whole-HOST resource: collisions are refused within the
     * stack AND against every other stack on the same server (a second stack binding
     * :8099 would only fail at deploy time otherwise).
     */
    private static void validatePorts(@NonNull Map<String, Object> coerced, int stackId,
                                      @NonNull List<Row> siblings, @Nullable Integer existingId) {
        Set<String> claimed = new HashSet<>();
        List<Row> sameServerForeignServices = servicesOnSameServer(stackId);
        int index = -1;
        for (Row port : recordsOf(coerced, "ports")) {
            index++;
            Integer container = intOrNull(port.get("container_port"));
            if (container == null && intOrNull(port.get("host_port")) == null
                && trimmed(port.get("host_ip")).isEmpty()) {
                continue;   // an untouched blank row the editor added
            }
            if (container == null) {
                throw Violations.ofField("ports." + index + ".container_port",
                    port.get("container_port"),
                    CmsSupport.violationText("port_container_required"));
            }
            for (String key : List.of("container_port", "host_port")) {
                Integer value = intOrNull(port.get(key));
                if (value != null && (value < 1 || value > 65535)) {
                    throw Violations.ofField("ports." + index + "." + key, value,
                        CmsSupport.violationText("port_range"));
                }
            }
            Integer host = intOrNull(port.get("host_port"));
            if (host == null) {
                continue;
            }
            String claim = portClaim(port.get("host_ip"), host, port.get("protocol"));
            if (!claimed.add(claim)) {
                throw Violations.ofField("ports." + index + ".host_port", host,
                    CmsSupport.violationText("host_port_taken"));
            }
            for (Row sibling : siblings) {
                if (sibling.get(StackServiceModel.ID).equals(existingId)) {
                    continue;
                }
                refuseClaimedPort(sibling, claim, host, index, "host_port_taken");
            }
            for (Row foreign : sameServerForeignServices) {
                refuseClaimedPort(foreign, claim, host, index, "host_port_taken_other_stack");
            }
        }
    }

    /** One canonical claim per (bind address, port, protocol): trims both sides, treats
     *  a blank bind address and 0.0.0.0 as the same whole-host bind, defaults tcp. */
    private static @NonNull String portClaim(@Nullable Object hostIp, int host, @Nullable Object protocol) {
        String address = trimmed(hostIp);
        if (address.equals("0.0.0.0")) {
            address = "";
        }
        String proto = trimmed(protocol).toLowerCase(java.util.Locale.ROOT);
        if (proto.isEmpty()) {
            proto = "tcp";
        }
        return address + "|" + host + "|" + proto;
    }

    private static void refuseClaimedPort(@NonNull Row service, @NonNull String claim, int host,
                                          int index, @NonNull String copyKey) {
        for (Row other : service.getRecords(StackServiceModel.PORTS)) {
            Integer otherHost = other.get(StackServiceModel.PORT_HOST);
            if (otherHost == null || otherHost != host) {
                continue;
            }
            if (portClaim(other.get(StackServiceModel.PORT_HOST_IP), otherHost,
                    other.get(StackServiceModel.PORT_PROTOCOL)).equals(claim)) {
                throw Violations.ofField("ports." + index + ".host_port", host,
                    CmsSupport.violationText(copyKey)
                        .withArg("service", String.valueOf(service.get(StackServiceModel.NAME))));
            }
        }
    }

    /** Every service of OTHER stacks bound to the same server as {@code stackId}'s stack. */
    private static @NonNull List<Row> servicesOnSameServer(int stackId) {
        Row stack = Models.get(StackModel.class).findById(stackId);
        if (stack == null) {
            return List.of();
        }
        String server = trimmed(stack.get(StackModel.SERVER_NAME));
        List<Row> foreign = new ArrayList<>();
        for (Row otherStack : Models.get(StackModel.class).find().all()) {
            if (otherStack.get(StackModel.ID).equals(stackId)
                || !server.equals(trimmed(otherStack.get(StackModel.SERVER_NAME)))) {
                continue;
            }
            foreign.addAll(Models.get(StackServiceModel.class).find()
                .where(StackServiceModel.STACK_ID.eq(otherStack.get(StackModel.ID))).all());
        }
        return foreign;
    }

    /** A dependency naming no sibling service can never be satisfied at deploy time. */
    private static void validateDependsOn(@NonNull Map<String, Object> coerced, @NonNull String name,
                                          @NonNull List<Row> siblings, @Nullable Integer existingId) {
        Set<String> known = new HashSet<>();
        known.add(name);
        for (Row sibling : siblings) {
            if (!sibling.get(StackServiceModel.ID).equals(existingId)) {
                known.add(String.valueOf(sibling.get(StackServiceModel.NAME)));
            }
        }
        int index = -1;
        for (Row depends : recordsOf(coerced, "depends_on")) {
            index++;
            String target = trimmed(depends.get("service"));
            if (target.isEmpty()) {
                continue;   // an untouched blank row the editor added
            }
            depends.set(StackServiceModel.DEPENDS_SERVICE, target);
            if (!known.contains(target)) {
                throw Violations.ofField("depends_on." + index + ".service", target,
                    CmsSupport.violationText("depends_unknown_service"));
            }
            if (target.equals(name)) {
                throw Violations.ofField("depends_on." + index + ".service", target,
                    CmsSupport.violationText("depends_on_self"));
            }
        }
    }

    /**
     * Healthcheck timings are periods: negatives are meaningless everywhere, and zero
     * is refused for interval, timeout and retries too (Docker rejects them at create,
     * which is exactly the deploy-time failure this validation exists to prevent).
     * Only the start period may legitimately be zero.
     */
    private static void validateHealth(@NonNull Map<String, Object> coerced) {
        for (String key : List.of("health_interval_seconds", "health_timeout_seconds",
                "health_retries", "health_start_period_seconds")) {
            Object raw = coerced.get(key);
            if (!(raw instanceof Number number)) {
                continue;
            }
            boolean zeroAllowed = "health_start_period_seconds".equals(key);
            if (number.intValue() < 0 || (!zeroAllowed && number.intValue() == 0)) {
                throw Violations.ofField(key, number, CmsSupport.violationText("health_positive"));
            }
        }
    }

    /**
     * Coerced sub-schema records. AIDEV-NOTE: a Records entry coerces to a List of ROWS
     * (SubmittedValueCoercion.coerceRecords), never a List of Maps -- reading them as maps
     * silently yields nothing and every check over them becomes dead code.
     */
    private static @NonNull List<Row> recordsOf(@NonNull Map<String, Object> coerced, @NonNull String key) {
        if (!(coerced.get(key) instanceof List<?> list)) {
            return List.of();
        }
        List<Row> records = new ArrayList<>();
        for (Object entry : list) {
            if (entry instanceof Row record) {
                records.add(record);
            }
        }
        return records;
    }

    private static @NonNull String trimmed(@Nullable Object value) {
        return value != null ? String.valueOf(value).trim() : "";
    }

    private static @Nullable Integer intOrNull(@Nullable Object value) {
        return value instanceof Number number ? number.intValue() : null;
    }
}
