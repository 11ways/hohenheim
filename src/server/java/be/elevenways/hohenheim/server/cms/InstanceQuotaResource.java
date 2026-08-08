package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.model.InstanceQuotaModel;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.cms.common.panel.NavGroup;
import be.elevenways.zenit.cms.common.resource.RowResource;
import be.elevenways.zenit.cms.common.schema.ColumnSpec;
import be.elevenways.zenit.cms.common.schema.TableSpec;
import be.elevenways.zenit.common.edit.FormSpec;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.ui.Icon;
import org.checkerframework.checker.nullness.qual.NonNull;

/**
 * Per-owner quota overrides: a plain generated resource (the floor IS the page --
 * list, forms, CRUD), because a quota override offers nothing beyond its cap
 * columns. The subject-set key is admin-entered for now; a picker over known owners
 * arrives with the tenant-facing /manage instance surface.
 */
public final class InstanceQuotaResource extends RowResource {

    // AIDEV-NOTE: every override column the reserve hooks read must be ON this form.
    // M073 added max_disk_gb/max_nics and InstanceDeviceQuota.diskLimitFor/nicLimitFor
    // consult them, but they were absent here for a wave -- so the columns existed,
    // were enforced, carried form copy, and could not be set by anyone. Adding a cap
    // column without adding it here is the silent-success shape.
    private final FormSpec formSpec = FormSpec.builder()
        .add(InstanceQuotaModel.SUBJECTS)
        .add(InstanceQuotaModel.MAX_INSTANCES)
        .add(InstanceQuotaModel.MAX_MEMORY_MB)
        .add(InstanceQuotaModel.MAX_DISK_GB)
        .add(InstanceQuotaModel.MAX_NICS)
        .add(InstanceQuotaModel.MAX_SITES)
        .add(InstanceQuotaModel.MAX_DATABASES)
        .build();

    private final TableSpec<Row> tableSpec = TableSpec.<Row>builder()
        .column(ColumnSpec.fromField(InstanceQuotaModel.SUBJECTS).filterable().build())
        .column(ColumnSpec.fromField(InstanceQuotaModel.MAX_INSTANCES).build())
        .column(ColumnSpec.fromField(InstanceQuotaModel.MAX_MEMORY_MB).build())
        .column(ColumnSpec.fromField(InstanceQuotaModel.MAX_DISK_GB).build())
        .column(ColumnSpec.fromField(InstanceQuotaModel.MAX_NICS).build())
        .column(ColumnSpec.fromField(InstanceQuotaModel.MAX_SITES).build())
        .column(ColumnSpec.fromField(InstanceQuotaModel.MAX_DATABASES).build())
        .build();

    @Override public @NonNull Identifier id() { return Identifier.of("hohenheim", "instance_quota"); }
    @Override public @NonNull Microcopy label() { return Microcopy.of("plural").withFilter("scope", "instance_quota"); }
    @Override public @NonNull String slug() { return "instance-quotas"; }
    @Override public @NonNull Model model() { return Models.get(InstanceQuotaModel.class); }
    @Override public @NonNull FormSpec formSpec() { return this.formSpec; }
    @Override public @NonNull TableSpec<Row> tableSpec() { return this.tableSpec; }
    @Override public @NonNull NavGroup navGroup() { return HohenheimPanel.INFRA_GROUP; }
    @Override public int navOrder() { return 16; }
    @Override public @NonNull Icon icon() { return Icon.of("gauge"); }
}
