package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.model.StackFileModel;
import be.elevenways.hohenheim.model.StackServiceModel;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.cms.common.panel.NavGroup;
import be.elevenways.zenit.cms.common.resource.ResourceParent;
import be.elevenways.zenit.cms.common.resource.RowResource;
import be.elevenways.zenit.cms.common.schema.ColumnSpec;
import be.elevenways.zenit.cms.common.schema.TableSpec;
import be.elevenways.zenit.common.conduit.Conduit;
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
import java.util.Map;

/**
 * Managed config files of stack services. Hidden from the sidebar -- reached
 * through a stack's Services tab. Content is encrypted at rest but visible to
 * editors (encryption is not secrecy).
 */
public class StackFileResource extends RowResource {

    private final FormSpec formSpec = FormSpec.builder()
        .add(RelationPick.of(StackFileModel.STACK_SERVICE_ID, StackServiceModel.MODEL_ID).build())
        .add(StackFileModel.CONTAINER_PATH)
        .add(StackFileModel.CONTENT)
        .add(StackFileModel.MODE)
        .build();

    private final TableSpec<Row> tableSpec = TableSpec.<Row>builder()
        .column(ColumnSpec.fromField(StackFileModel.CONTAINER_PATH).build())
        .column(ColumnSpec.fromField(StackFileModel.MODE).build())
        .column(ColumnSpec.fromField(StackFileModel.STACK_SERVICE_ID)
            .relation(RelationPick.of(StackFileModel.STACK_SERVICE_ID, StackServiceModel.MODEL_ID).build())
            .build())
        .build();

    @Override public @NonNull Identifier id() { return Identifier.of("hohenheim", "stack_file"); }
    @Override public @NonNull Microcopy label() { return Microcopy.of("plural").withFilter("scope", "stack_file"); }
    @Override public @NonNull String slug() { return "stack-files"; }
    @Override public @NonNull Model model() { return Models.get(StackFileModel.class); }
    @Override public @NonNull FormSpec formSpec() { return this.formSpec; }
    @Override public @NonNull TableSpec<Row> tableSpec() { return this.tableSpec; }
    @Override public @NonNull NavGroup navGroup() { return HohenheimPanel.DEPLOY_GROUP; }
    @Override public int navOrder() { return 27; }
    @Override public @NonNull Icon icon() { return Icon.of("file-code"); }
    @Override public boolean showInNav() { return false; }

    @Override
    public @Nullable ResourceParent<Row> parent() {
        return ResourceParent.<Row>of("stacks", row -> {
            Object serviceId = row.get(StackFileModel.STACK_SERVICE_ID);
            if (!(serviceId instanceof Integer id)) {
                return null;
            }
            Row service = Models.get(StackServiceModel.class).find()
                .where(StackServiceModel.ID.eq(id)).first();
            return service != null ? service.get(StackServiceModel.STACK_ID) : null;
        }).tab("services");
    }

    /** The Services tab links here with ?stack_service_id= so the pick is preselected. */
    @Override
    public @NonNull Map<String, Object> createValues(@NonNull Conduit conduit) {
        Map<String, Object> values = new LinkedHashMap<>(formSpec().defaultValues());
        String serviceId = conduit.getQueryParam("stack_service_id");
        if (serviceId != null && !serviceId.isEmpty()) {
            try {
                values.put("stack_service_id", Integer.parseInt(serviceId));
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

    /** Paths must be absolute, traversal-free, unshadowed and unique; modes must be octal.
     *  The trimmed path is written BACK so the validated value is the stored value. */
    private void validate(@NonNull Map<String, Object> coerced, @Nullable Row existing) {
        Object pathValue = coerced.get("container_path");
        String path = pathValue != null ? String.valueOf(pathValue).trim() : "";
        if (!path.startsWith("/") || path.contains("..")) {
            throw Violations.ofField("container_path", path,
                CmsSupport.violationText("file_path_absolute"));
        }
        if (coerced.containsKey("container_path")) {
            coerced.put("container_path", path);
        }
        Object modeValue = coerced.get("mode");
        if (modeValue != null && !String.valueOf(modeValue).isBlank()) {
            try {
                Integer.parseInt(String.valueOf(modeValue).trim(), 8);
            } catch (NumberFormatException error) {
                throw Violations.ofField("mode", modeValue,
                    CmsSupport.violationText("file_mode_format"));
            }
        }

        Object serviceIdValue = coerced.containsKey("stack_service_id")
            ? coerced.get("stack_service_id")
            : existing != null ? existing.get(StackFileModel.STACK_SERVICE_ID) : null;
        if (!(serviceIdValue instanceof Integer serviceId)) {
            throw Violations.ofField("stack_service_id", serviceIdValue,
                CmsSupport.violationText("service_required"));
        }

        Integer existingId = existing != null ? existing.get(StackFileModel.ID) : null;
        for (Row sibling : this.model().find()
                .where(StackFileModel.STACK_SERVICE_ID.eq(serviceId)).all()) {
            if (sibling.get(StackFileModel.ID).equals(existingId)) {
                continue;
            }
            if (path.equals(String.valueOf(sibling.get(StackFileModel.CONTAINER_PATH)).trim())) {
                throw Violations.ofField("container_path", path,
                    CmsSupport.violationText("file_path_taken"));
            }
        }

        // Files are staged into the container BEFORE it starts, so a mount covering the
        // path silently hides the file the moment the container runs -- an operator would
        // deploy a secret that simply is not there.
        Row service = Models.get(StackServiceModel.class).findById(serviceId);
        if (service == null) {
            return;
        }
        for (Row mount : service.getRecords(StackServiceModel.MOUNTS)) {
            String mountPath = String.valueOf(mount.get(StackServiceModel.MOUNT_PATH)).trim();
            if (mountPath.isEmpty() || !mountPath.startsWith("/")) {
                continue;
            }
            String prefix = mountPath.endsWith("/") ? mountPath : mountPath + "/";
            if (path.equals(mountPath) || path.startsWith(prefix)) {
                throw Violations.ofField("container_path", path,
                    CmsSupport.violationText("file_path_shadowed"));
            }
        }
    }
}
