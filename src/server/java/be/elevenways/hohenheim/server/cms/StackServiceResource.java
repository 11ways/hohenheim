package be.elevenways.hohenheim.server.cms;

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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
        Row duplicate = this.model().find()
            .where(StackServiceModel.STACK_ID.eq(stackId))
            .where(StackServiceModel.NAME.eq(name))
            .first();
        if (duplicate != null
            && (existing == null || !duplicate.get(StackServiceModel.ID).equals(existing.get(StackServiceModel.ID)))) {
            throw Violations.ofField("name", name, CmsSupport.violationText("service_name_taken"));
        }

        if (coerced.get("ports") instanceof List<?> ports) {
            for (Object entry : ports) {
                if (!(entry instanceof Map<?, ?> port)) {
                    continue;
                }
                for (String key : List.of("container_port", "host_port")) {
                    Object raw = port.get(key);
                    if (raw instanceof Number number
                        && (number.intValue() < 1 || number.intValue() > 65535)) {
                        throw Violations.ofField("ports", number.intValue(),
                            CmsSupport.violationText("port_range"));
                    }
                }
            }
        }
    }
}
