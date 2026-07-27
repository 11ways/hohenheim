package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.model.StackDeploymentModel;
import be.elevenways.hohenheim.model.StackFileModel;
import be.elevenways.hohenheim.model.StackModel;
import be.elevenways.hohenheim.model.StackServiceModel;
import be.elevenways.hohenheim.server.docker.ServerService;
import be.elevenways.hohenheim.server.security.IpLiterals;
import be.elevenways.hohenheim.server.stack.StackRuntime;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.cms.common.action.CmsActionResult;
import be.elevenways.zenit.cms.common.action.ConfirmationSpec;
import be.elevenways.zenit.cms.common.action.RowAction;
import be.elevenways.zenit.cms.common.panel.NavGroup;
import be.elevenways.zenit.cms.common.resource.RecordScopedPage;
import be.elevenways.zenit.cms.common.resource.RowResource;
import be.elevenways.zenit.cms.common.schema.ColumnSpec;
import be.elevenways.zenit.cms.common.schema.FilterSpec;
import be.elevenways.zenit.cms.common.schema.TableSpec;
import be.elevenways.zenit.common.edit.FieldLabels;
import be.elevenways.zenit.common.edit.FieldOption;
import be.elevenways.zenit.common.edit.FormSpec;
import be.elevenways.zenit.common.edit.OptionSource;
import be.elevenways.zenit.common.edit.Select;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.common.ui.Icon;
import be.elevenways.zenit.common.validation.Violations;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Managed Docker stacks: the record edits desired state only; deploys, stops and
 * rollbacks are explicit row actions so saving a form never restarts containers.
 */
public class StackResource extends RowResource {

    /** Stack names become network/container/volume name segments. */
    static final Pattern NAME_PATTERN = Pattern.compile("[a-z0-9][a-z0-9-]{0,62}");

    private final ServerService serverService = new ServerService();

    private final FormSpec formSpec = FormSpec.builder()
        .add(StackModel.NAME)
        .add(StackModel.ENABLED)
        .add(Select.of(StackModel.SERVER_NAME)
            .options(OptionSource.dynamic(ctx -> serverOptions()))
            .build())
        .add(StackModel.SUBNET)
        .add(StackModel.ADOPT_RESOURCES)
        .add(StackModel.REGISTRY_SERVER)
        .add(StackModel.REGISTRY_USER)
        .add(StackModel.REGISTRY_PASSWORD)
        .add(StackModel.DESCRIPTION)
        .build();

    private final TableSpec<Row> tableSpec = TableSpec.<Row>builder()
        .column(ColumnSpec.fromField(StackModel.NAME).filterable().build())
        .column(ColumnSpec.fromField(StackModel.SERVER_NAME).filterable().build())
        .column(ColumnSpec.fromField(StackModel.STATUS).filterable().build())
        .column(ColumnSpec.fromField(StackModel.ENABLED).filterable().build())
        .filter(FilterSpec.forField(StackModel.NAME, FilterSpec.Kind.TEXT)
            .label(FieldLabels.labelFor(StackModel.NAME)).build())
        .filter(FilterSpec.forField(StackModel.STATUS, FilterSpec.Kind.SELECT)
            .label(FieldLabels.labelFor(StackModel.STATUS)).build())
        .filter(FilterSpec.forField(StackModel.ENABLED, FilterSpec.Kind.BOOLEAN)
            .label(FieldLabels.labelFor(StackModel.ENABLED)).build())
        .build();

    @Override public @NonNull Identifier id() { return Identifier.of("hohenheim", "stack"); }
    @Override public @NonNull Microcopy label() { return Microcopy.of("plural").withFilter("scope", "stack"); }
    @Override public @NonNull String slug() { return "stacks"; }
    @Override public @NonNull Model model() { return Models.get(StackModel.class); }
    @Override public @NonNull FormSpec formSpec() { return this.formSpec; }
    @Override public @NonNull TableSpec<Row> tableSpec() { return this.tableSpec; }
    @Override public @NonNull NavGroup navGroup() { return HohenheimPanel.INFRA_GROUP; }
    @Override public int navOrder() { return 25; }
    @Override public @NonNull Icon icon() { return Icon.of("layer-group"); }

    private @NonNull List<FieldOption<String>> serverOptions() {
        this.serverService.ensureLocal();
        List<FieldOption<String>> options = new ArrayList<>();
        for (String name : this.serverService.names()) {
            options.add(FieldOption.of(name, name));
        }
        return options;
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

    /** Names become Docker resource names: enforce the safe shape and uniqueness. */
    private void validate(@NonNull Map<String, Object> coerced, @Nullable Row existing) {
        Object nameValue = coerced.get("name");
        String name = nameValue != null ? String.valueOf(nameValue).trim() : "";
        if (!NAME_PATTERN.matcher(name).matches()) {
            throw Violations.ofField("name", name, CmsSupport.violationText("stack_name_format"));
        }
        Row duplicate = Models.get(StackModel.class).findByName(name);
        if (duplicate != null
            && (existing == null || !duplicate.get(StackModel.ID).equals(existing.get(StackModel.ID)))) {
            throw Violations.ofField("name", name, CmsSupport.violationText("stack_name_taken"));
        }
        Object subnetValue = coerced.get("subnet");
        String subnet = subnetValue != null ? String.valueOf(subnetValue).trim() : "";
        if (!subnet.isEmpty() && !isCidr(subnet)) {
            // Without this the mistake only surfaces inside the deploy worker, as a failed
            // deployment record rather than a form violation next to the offending input.
            throw Violations.ofField("subnet", subnet, CmsSupport.violationText("subnet_format"));
        }
        if (existing != null && !name.equals(existing.get(StackModel.NAME))) {
            // The name is embedded in every ownership label, container, network and volume
            // name: renaming a deployed stack would orphan ALL of them (deploy, status,
            // stop and destroy only see the new name). Destroy first, then rename.
            String status = existing.get(StackModel.STATUS);
            boolean neverDeployed = status == null || status.isBlank()
                || StackModel.STATUS_INACTIVE.equals(status);
            if (!neverDeployed) {
                throw Violations.ofField("name", name, CmsSupport.violationText("stack_rename_deployed"));
            }
        }
    }

    /**
     * A CIDR Docker's network driver accepts: an IP literal (v4 or v6, parsed by the
     * shared {@link IpLiterals} authority) plus a prefix length valid for its family.
     */
    private static boolean isCidr(@NonNull String value) {
        int slash = value.indexOf('/');
        if (slash < 0) {
            return false;
        }
        byte[] address = IpLiterals.parse(value.substring(0, slash));
        if (address == null) {
            return false;
        }
        try {
            int prefix = Integer.parseInt(value.substring(slash + 1));
            return prefix >= 0 && prefix <= address.length * 8;
        } catch (NumberFormatException notANumber) {
            return false;
        }
    }

    @Override
    public @Nullable Microcopy deleteConfirmationBody() {
        return Microcopy.of("delete_confirm").withFilter("scope", "stack");
    }

    /** Deleting the record first removes owned containers and the network (volumes stay). */
    @Override
    public void deleteRow(@NonNull Row row, @NonNull AccessContext accessContext) {
        Integer stackId = row.get(StackModel.ID);
        if (stackId != null) {
            try {
                StackRuntime.get().destroy(stackId, false);
            } catch (IOException e) {
                throw Violations.ofForm(CmsSupport.violationText("stack_destroy_failed"));
            }
            // No FK cascades on these tables: files hang off the services and deployment
            // history (with encrypted credential-bearing snapshots) off the stack, and
            // both would linger unreachable forever without an explicit sweep.
            List<Integer> serviceIds = new ArrayList<>();
            for (Row service : Models.get(StackServiceModel.class).find()
                    .where(StackServiceModel.STACK_ID.eq(stackId)).all()) {
                serviceIds.add(service.get(StackServiceModel.ID));
            }
            if (!serviceIds.isEmpty()) {
                Models.get(StackFileModel.class).find()
                    .where(StackFileModel.STACK_SERVICE_ID.in(serviceIds)).delete();
            }
            Models.get(StackServiceModel.class).find()
                .where(StackServiceModel.STACK_ID.eq(stackId)).delete();
            Models.get(StackDeploymentModel.class).find()
                .where(StackDeploymentModel.STACK_ID.eq(stackId)).delete();
        }
        super.deleteRow(row, accessContext);
    }

    @Override
    public @NonNull List<RowAction<Row>> rowActions() {
        List<RowAction<Row>> actions = new ArrayList<>(super.rowActions());
        actions.add(RowAction.Invoke.<Row>builder(Identifier.of("hohenheim", "deploy_stack"))
            .label(Microcopy.of("deploy").withFilter("scope", "stack"))
            .description(Microcopy.of("deploy_hint").withFilter("scope", "stack"))
            .icon(Icon.of("rocket"))
            .confirmation(ConfirmationSpec.builder()
                .title(Microcopy.of("deploy").withFilter("scope", "stack"))
                .body(Microcopy.of("deploy_confirm").withFilter("scope", "stack"))
                .build())
            .handler((row, ctx) -> {
                StackRuntime.get().deployAsync(row.get(StackModel.ID), "manual");
                return CmsActionResult.refreshWithToast(
                    Microcopy.of("deploy_queued").withFilter("scope", "stack"));
            })
            .build());
        actions.add(RowAction.Invoke.<Row>builder(Identifier.of("hohenheim", "stop_stack"))
            .label(Microcopy.of("stop").withFilter("scope", "stack"))
            .icon(Icon.of("circle-stop"))
            .confirmation(ConfirmationSpec.builder()
                .title(Microcopy.of("stop").withFilter("scope", "stack"))
                .body(Microcopy.of("stop_confirm").withFilter("scope", "stack"))
                .build())
            .visibleFor((row, ctx) -> StackModel.STATUS_ACTIVE.equals(row.get(StackModel.STATUS))
                || StackModel.STATUS_DEGRADED.equals(row.get(StackModel.STATUS)))
            .handler((row, ctx) -> {
                StackRuntime.get().stopAsync(row.get(StackModel.ID));
                return CmsActionResult.refreshWithToast(
                    Microcopy.of("stop_queued").withFilter("scope", "stack"));
            })
            .build());
        actions.add(RowAction.Invoke.<Row>builder(Identifier.of("hohenheim", "rollback_stack"))
            .label(Microcopy.of("rollback").withFilter("scope", "stack"))
            .description(Microcopy.of("rollback_hint").withFilter("scope", "stack"))
            .icon(Icon.of("clock-rotate-left"))
            .confirmation(ConfirmationSpec.builder()
                .title(Microcopy.of("rollback").withFilter("scope", "stack"))
                .body(Microcopy.of("rollback_confirm").withFilter("scope", "stack"))
                .build())
            .visibleFor((row, ctx) -> Models.get(StackDeploymentModel.class)
                .findLatestSuccessful(row.get(StackModel.ID)) != null)
            .handler((row, ctx) -> {
                StackRuntime.get().rollbackAsync(row.get(StackModel.ID));
                return CmsActionResult.refreshWithToast(
                    Microcopy.of("rollback_queued").withFilter("scope", "stack"));
            })
            .build());
        actions.add(RowAction.Invoke.<Row>builder(Identifier.of("hohenheim", "refresh_stack"))
            .label(Microcopy.of("refresh_status").withFilter("scope", "stack"))
            .icon(Icon.of("rotate"))
            .handler((row, ctx) -> {
                String status = StackRuntime.get().refreshStatus(row.get(StackModel.ID));
                return CmsActionResult.refreshWithToast(
                    Microcopy.of(status).withFilter("scope", "stack_status"));
            })
            .build());
        return actions;
    }

    @Override
    public @NonNull List<RecordScopedPage<Row>> subpages() {
        List<RecordScopedPage<Row>> pages = new ArrayList<>(
            List.of(new StackServicesPage(), new StackDeploymentsPage()));
        pages.addAll(this.frameworkSubpages());
        return pages;
    }
}
