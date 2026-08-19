package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.model.StackFileModel;
import be.elevenways.hohenheim.model.StackModel;
import be.elevenways.hohenheim.model.StackServiceModel;
import be.elevenways.hohenheim.ports.PortLedger;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.cms.common.panel.NavGroup;
import be.elevenways.zenit.cms.common.resource.ListChrome;
import be.elevenways.zenit.cms.common.resource.ResourceParent;
import be.elevenways.zenit.cms.common.resource.RowResource;
import be.elevenways.zenit.cms.common.schema.ColumnSpec;
import be.elevenways.zenit.cms.common.schema.TableSpec;
import be.elevenways.zenit.common.conduit.Conduit;
import be.elevenways.hohenheim.server.docker.ContainerHardening;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.server.stack.StackInstances;
import be.elevenways.hohenheim.server.stack.StackServiceKind;
import be.elevenways.zenit.common.edit.Array;
import be.elevenways.zenit.common.edit.FieldFormEntryRegistry;
import be.elevenways.zenit.common.edit.FieldOption;
import be.elevenways.zenit.common.edit.FormSpec;
import be.elevenways.zenit.common.edit.OptionSource;
import be.elevenways.zenit.common.edit.RelationPick;
import be.elevenways.zenit.common.orm.field.Field;
import be.elevenways.zenit.common.orm.field.StringField;
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
        // A CLOSED multi-select over the allow-list, not free text: the set of
        // capabilities a service may declare is enumerated in ContainerHardening, and the
        // form offers exactly that set with each entry's reason as its label help.
        .add(Array.of(StackServiceModel.CAPABILITIES,
                StringField.builder().name("capability").build())
            .tags()
            .options(OptionSource.of(capabilityOptions()))
            .build())
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
        .column(ColumnSpec.fromField(StackServiceModel.IMAGE).copyable().build())
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
    @Override public @NonNull ListChrome listChrome() { return ListChrome.MINIMAL; }

    /** A service is found by its name or by the image it runs. */
    @Override
    public @NonNull List<Field<?, ?>> searchFields() {
        return List.of(StackServiceModel.NAME, StackServiceModel.IMAGE);
    }

    @Override public @NonNull NavGroup navGroup() { return HohenheimPanel.DEPLOY_GROUP; }
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
            // The owned instance must die WITH the record: InstanceService.destroy
            // soft-deletes, so no remove hook fires and nothing else would ever take the
            // workload down. Refusing here beats leaving a running container behind a
            // deleted record -- the next deploy's prune is the SECOND line, not the first.
            try {
                StackInstances.destroyFor(serviceId);
            } catch (java.io.IOException undeletable) {
                throw Violations.ofForm(CmsSupport.violationText("stack_destroy_failed"));
            }
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
        validatePorts(coerced, stackId, existingId);
        validateDependsOn(coerced, name, siblings, existingId);
        validateHealth(coerced);
        validateCapabilities(coerced);

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
     * Published host ports are a whole-HOST resource, arbitrated by the PORT LEDGER:
     * the claim keys are checked against {@code port_allocations}, which sees EVERY
     * recording authority (other stacks, and -- as they migrate in -- docker sites,
     * managed databases and managed processes), not just sibling stack rows.
     *
     * AIDEV-NOTE: this read is the FRIENDLY refusal (field-pathed, names the holder) and
     * ONLY that -- it is advisory, not the arbiter. Since the stack tier lowered onto the
     * instance runtime contract, exclusivity is decided where every other tier decides it:
     * at DEPLOY, by the ledger's unique claim-key index, under the service's owned
     * INSTANCE as the owner. That is why the own-holder exemption below asks about the
     * instance and not about the service record.
     */
    private static void validatePorts(@NonNull Map<String, Object> coerced, int stackId,
                                      @Nullable Integer existingId) {
        Set<String> claimed = new HashSet<>();
        Row stack = Models.get(StackModel.class).findById(stackId);
        Integer stackServer = stack != null ? stack.get(StackModel.SERVER_ID) : null;
        int serverId = stackServer != null ? stackServer : ServerModel.localServerId();
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
            if (!claimed.add(PortLedger.portClaim(port.get("host_ip"), host, port.get("protocol")))) {
                throw Violations.ofField("ports." + index + ".host_port", host,
                    CmsSupport.violationText("host_port_taken"));
            }
            // Sibling DECLARATIONS on the same host, which the ledger cannot see yet:
            // since the lowering a claim exists only from the DEPLOY, so two services can
            // be authored with the same host port and only collide much later. This is a
            // record-level uniqueness check (the duplicate-name shape), not a second
            // arbiter -- the ledger still decides at deploy.
            String declaredBy = declaringSibling(serverId, existingId,
                PortLedger.claimKeyOf(serverId, port.get("host_ip"), host, port.get("protocol")));
            if (declaredBy != null) {
                throw Violations.ofField("ports." + index + ".host_port", host,
                    CmsSupport.violationText("port_held").withArg("holder", declaredBy));
            }
            Row holder = PortLedger.holderOf(
                PortLedger.claimKeyOf(serverId, port.get("host_ip"), host, port.get("protocol")));
            Row ownInstance = existingId != null ? StackInstances.owned(existingId) : null;
            if (holder != null && !(ownInstance != null && PortLedger.isOwnedBy(holder,
                    InstanceModel.MODEL_ID, ownInstance.get(InstanceModel.ID)))) {
                throw Violations.ofField("ports." + index + ".host_port", host,
                    CmsSupport.violationText("port_held")
                        .withArg("holder", PortLedger.describeHolder(holder)));
            }
        }
    }

    /**
     * The name of another stack service on the same host that already DECLARES this
     * claim key, or null.
     *
     * @return "stack/service", which is what the operator has to go and change
     */
    private static @Nullable String declaringSibling(int serverId, @Nullable Integer existingId,
                                                     @NonNull String claimKey) {
        StackModel stacks = Models.get(StackModel.class);
        for (Row stack : stacks.find().all()) {
            Integer stackServer = stack.get(StackModel.SERVER_ID);
            if ((stackServer != null ? stackServer : ServerModel.localServerId()) != serverId) {
                continue;
            }
            for (Row service : Models.get(StackServiceModel.class)
                    .findByStackId(stack.get(StackModel.ID))) {
                if (service.get(StackServiceModel.ID).equals(existingId)) {
                    continue;
                }
                for (Row port : service.getRecords(StackServiceModel.PORTS)) {
                    Integer host = port.get(StackServiceModel.PORT_HOST);
                    if (host != null && claimKey.equals(PortLedger.claimKeyOf(serverId,
                            port.get(StackServiceModel.PORT_HOST_IP), host,
                            port.get(StackServiceModel.PORT_PROTOCOL)))) {
                        return stack.get(StackModel.NAME) + "/"
                            + service.get(StackServiceModel.NAME);
                    }
                }
            }
        }
        return null;
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
    /**
     * Refuse a capability the create funnel would refuse anyway, so the operator sees it
     * in the form rather than in a deployment log three clicks later.
     *
     * AIDEV-NOTE: this is a SECOND reader of the same allow-list, never a second list.
     * ContainerHardening.declaring stays THE authority (it runs on the deploy path, which
     * this resource is not on the way to), and this call exists only to move the moment of
     * refusal earlier.
     */
    private static void validateCapabilities(@NonNull Map<String, Object> coerced) {
        if (!(coerced.get("capabilities") instanceof List<?> declared)) {
            return;
        }
        List<String> names = new ArrayList<>();
        for (Object entry : declared) {
            names.add(String.valueOf(entry));
        }
        try {
            ContainerHardening.declaring(StackServiceKind.HARDENING, "this service", names);
        } catch (IllegalArgumentException notDeclarable) {
            throw Violations.ofField("capabilities", names,
                CmsSupport.violationText("capability_not_declarable")
                    .withArg("capabilities",
                        String.join(", ", ContainerHardening.DECLARABLE.keySet())));
        }
    }

    /** The allow-list as closed form options; the names are policy tokens, never localized. */
    private static @NonNull List<FieldOption<String>> capabilityOptions() {
        List<FieldOption<String>> options = new ArrayList<>();
        for (Map.Entry<String, String> entry : ContainerHardening.DECLARABLE.entrySet()) {
            options.add(FieldOption.of(entry.getKey(), Microcopy.literal(entry.getKey())));
        }
        return options;
    }

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
