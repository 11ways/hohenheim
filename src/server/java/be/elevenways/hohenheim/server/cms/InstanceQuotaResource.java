package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.model.InstanceQuotaModel;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.cms.common.panel.NavGroup;
import be.elevenways.zenit.cms.common.resource.ListChrome;
import be.elevenways.zenit.cms.common.resource.QuickCreateSpec;
import be.elevenways.zenit.cms.common.resource.RowResource;
import be.elevenways.zenit.cms.common.schema.ColumnSpec;
import be.elevenways.zenit.cms.common.schema.TableSpec;
import be.elevenways.zenit.common.edit.FormSpec;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.field.Field;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.ui.Icon;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.List;

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
        .column(ColumnSpec.fromField(InstanceQuotaModel.SUBJECTS).filterable().copyable().build())
        .column(ColumnSpec.fromField(InstanceQuotaModel.MAX_INSTANCES).build())
        .column(ColumnSpec.fromField(InstanceQuotaModel.MAX_MEMORY_MB).build())
        .column(ColumnSpec.fromField(InstanceQuotaModel.MAX_DISK_GB).build())
        .column(ColumnSpec.fromField(InstanceQuotaModel.MAX_NICS).build())
        .column(ColumnSpec.fromField(InstanceQuotaModel.MAX_SITES).build())
        .column(ColumnSpec.fromField(InstanceQuotaModel.MAX_DATABASES).build())
        .build();

    @Override public @NonNull Identifier id() { return Identifier.of("hohenheim", "instance_quota"); }
    @Override public @NonNull Microcopy label() { return Microcopy.of("plural").withFilter("scope", "instance_quota"); }
    @Override public @Nullable Microcopy recordLabel() { return Microcopy.of("singular").withFilter("scope", "instance_quota"); }
    @Override public @NonNull String slug() { return "instance-quotas"; }
    @Override public @NonNull Model model() { return Models.get(InstanceQuotaModel.class); }
    @Override public @NonNull FormSpec formSpec() { return this.formSpec; }
    @Override public @NonNull TableSpec<Row> tableSpec() { return this.tableSpec; }
    @Override public @NonNull ListChrome listChrome() { return ListChrome.MINIMAL; }

    /** The subject expression is the only text a quota carries. */
    @Override
    public @NonNull List<Field<?, ?>> searchFields() {
        return List.of(InstanceQuotaModel.SUBJECTS);
    }

    /** The three caps an override is usually opened for; the rest default to unlimited. */
    @Override
    public @Nullable QuickCreateSpec quickCreate() {
        return QuickCreateSpec.of(InstanceQuotaModel.SUBJECTS.getName(),
            InstanceQuotaModel.MAX_INSTANCES.getName(),
            InstanceQuotaModel.MAX_MEMORY_MB.getName());
    }

    /**
     * Every cap, and only the caps.
     *
     * AIDEV-NOTE: a cap is read at RESERVE time ({@code InstanceQuota}) and never swept
     * retroactively, so lowering one here cannot retire anything already running -- it
     * decides the next reservation, which is exactly what an operator raising a limit
     * mid-incident wants. The negative-value refusal still runs: the cell lane is a
     * sibling of the form lane, not a bypass. SUBJECTS is excluded because it is the
     * row's unique key -- retyping it in place silently re-points the override at
     * another owner, which reads as "I edited a number".
     */
    @Override
    public @NonNull List<Field<?, ?>> inlineEditableFields() {
        return List.of(InstanceQuotaModel.MAX_INSTANCES, InstanceQuotaModel.MAX_MEMORY_MB,
            InstanceQuotaModel.MAX_DISK_GB, InstanceQuotaModel.MAX_NICS,
            InstanceQuotaModel.MAX_SITES, InstanceQuotaModel.MAX_DATABASES);
    }

    @Override public @NonNull NavGroup navGroup() { return HohenheimPanel.DEPLOY_GROUP; }
    @Override public int navOrder() { return 16; }

    /**
     * Demoted out of the sidebar, so this sentence reaches a reader through the panel
     * index and the related-pages menu of the list that names it.
     */
    @Override public @Nullable Microcopy description() { return CmsSupport.navHint("instance_quota"); }

    @Override public boolean showInNav() { return false; }
    @Override public @NonNull Icon icon() { return Icon.of("gauge"); }
}
