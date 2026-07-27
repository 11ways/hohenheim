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
        validate(coerced, null);
        return super.persistRow(coerced, accessContext);
    }

    @Override
    public void updateRow(@NonNull Row existing, @NonNull Map<String, Object> coerced,
                          @NonNull AccessContext accessContext) {
        validate(coerced, existing);
        super.updateRow(existing, coerced, accessContext);
    }

    /**
     * Config files hang off the service with no FK cascade, and their content is
     * encrypted-at-rest credential material: leaving them behind would keep secrets
     * in a table no UI can reach any more.
     */
    @Override
    public void deleteRow(@NonNull Row row, @NonNull AccessContext accessContext) {
        Integer serviceId = row.get(StackServiceModel.ID);
        if (serviceId != null) {
            Models.get(StackFileModel.class).find()
                .where(StackFileModel.STACK_SERVICE_ID.eq(serviceId)).delete();
        }
        super.deleteRow(row, accessContext);
    }

    /** Service names become container names and DNS aliases; ports must be real ports. */
    private void validate(@NonNull Map<String, Object> coerced, @Nullable Row existing) {
        Object nameValue = coerced.get("name");
        String name = nameValue != null ? String.valueOf(nameValue).trim() : "";
        if (!StackResource.NAME_PATTERN.matcher(name).matches()) {
            throw Violations.ofField("name", name, CmsSupport.violationText("service_name_format"));
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
            if (name.equals(sibling.get(StackServiceModel.NAME))) {
                throw Violations.ofField("name", name, CmsSupport.violationText("service_name_taken"));
            }
        }

        validateMounts(coerced);
        validatePorts(coerced, siblings, existingId);
        validateDependsOn(coerced, name, siblings, existingId);
        validateHealth(coerced);
    }

    /** Mount names become volume-name segments; container paths must be absolute. */
    private static void validateMounts(@NonNull Map<String, Object> coerced) {
        Set<String> paths = new HashSet<>();
        for (Row mount : recordsOf(coerced, "mounts")) {
            String mountName = trimmed(mount.get("name"));
            String external = trimmed(mount.get("external_name"));
            if (mountName.isEmpty() && external.isEmpty() && trimmed(mount.get("container_path")).isEmpty()) {
                continue;   // an untouched blank row the editor added; nothing to check
            }
            boolean volume = !StackServiceModel.MOUNT_TMPFS.equals(trimmed(mount.get("type")));
            // A named volume derives "hohenheim-stack-<stack>-<name>", so the name has to be
            // a safe Docker name segment; an external volume brings its own name instead.
            if (volume && external.isEmpty() && !StackResource.NAME_PATTERN.matcher(mountName).matches()) {
                throw Violations.ofField("mounts", mountName,
                    CmsSupport.violationText("mount_name_format"));
            }
            String path = trimmed(mount.get("container_path"));
            if (!path.startsWith("/")) {
                throw Violations.ofField("mounts", path,
                    CmsSupport.violationText("mount_path_absolute"));
            }
            if (!paths.add(path)) {
                throw Violations.ofField("mounts", path,
                    CmsSupport.violationText("mount_path_taken"));
            }
        }
    }

    /** Published host ports are a whole-host resource, so they must not collide stack-wide. */
    private static void validatePorts(@NonNull Map<String, Object> coerced,
                                      @NonNull List<Row> siblings, @Nullable Integer existingId) {
        Set<String> claimed = new HashSet<>();
        for (Row port : recordsOf(coerced, "ports")) {
            Integer container = intOrNull(port.get("container_port"));
            if (container == null && intOrNull(port.get("host_port")) == null
                && trimmed(port.get("host_ip")).isEmpty()) {
                continue;   // an untouched blank row the editor added
            }
            if (container == null) {
                throw Violations.ofField("ports", port.get("container_port"),
                    CmsSupport.violationText("port_container_required"));
            }
            for (String key : List.of("container_port", "host_port")) {
                Integer value = intOrNull(port.get(key));
                if (value != null && (value < 1 || value > 65535)) {
                    throw Violations.ofField("ports", value, CmsSupport.violationText("port_range"));
                }
            }
            Integer host = intOrNull(port.get("host_port"));
            if (host == null) {
                continue;
            }
            String claim = trimmed(port.get("host_ip")) + "|" + host + "|"
                + (trimmed(port.get("protocol")).isEmpty() ? "tcp" : trimmed(port.get("protocol")));
            if (!claimed.add(claim)) {
                throw Violations.ofField("ports", host, CmsSupport.violationText("host_port_taken"));
            }
            for (Row sibling : siblings) {
                if (sibling.get(StackServiceModel.ID).equals(existingId)) {
                    continue;
                }
                for (Row siblingPort : sibling.getRecords(StackServiceModel.PORTS)) {
                    Integer siblingHost = siblingPort.get(StackServiceModel.PORT_HOST);
                    if (siblingHost == null || !siblingHost.equals(host)) {
                        continue;
                    }
                    String siblingClaim = orEmpty(siblingPort.get(StackServiceModel.PORT_HOST_IP))
                        + "|" + siblingHost + "|"
                        + orEmpty(siblingPort.get(StackServiceModel.PORT_PROTOCOL));
                    if (siblingClaim.equals(claim)) {
                        throw Violations.ofField("ports", host,
                            CmsSupport.violationText("host_port_taken"));
                    }
                }
            }
        }
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
        for (Row depends : recordsOf(coerced, "depends_on")) {
            String target = trimmed(depends.get("service"));
            if (target.isEmpty()) {
                continue;   // an untouched blank row the editor added
            }
            if (!known.contains(target)) {
                throw Violations.ofField("depends_on", target,
                    CmsSupport.violationText("depends_unknown_service"));
            }
            if (target.equals(name)) {
                throw Violations.ofField("depends_on", target,
                    CmsSupport.violationText("depends_on_self"));
            }
        }
    }

    /** Healthcheck timings are periods, so zero or negative values are meaningless. */
    private static void validateHealth(@NonNull Map<String, Object> coerced) {
        for (String key : List.of("health_interval_seconds", "health_timeout_seconds",
                "health_retries", "health_start_period_seconds")) {
            Object raw = coerced.get(key);
            if (raw instanceof Number number && number.intValue() < 0) {
                throw Violations.ofField(key, number, CmsSupport.violationText("health_positive"));
            }
            if ("health_retries".equals(key) && raw instanceof Number retries && retries.intValue() == 0) {
                throw Violations.ofField(key, retries, CmsSupport.violationText("health_positive"));
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

    private static @NonNull String orEmpty(@Nullable Object value) {
        return value != null ? String.valueOf(value) : "";
    }

    private static @Nullable Integer intOrNull(@Nullable Object value) {
        return value instanceof Number number ? number.intValue() : null;
    }
}
