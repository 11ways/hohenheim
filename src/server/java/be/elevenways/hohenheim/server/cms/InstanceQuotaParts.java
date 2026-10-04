package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.HohenheimIds;
import be.elevenways.hohenheim.model.InstanceQuotaModel;
import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.hohenheim.server.instance.InstanceChildDeletes;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.zenit.cms.common.resource.ListChrome;
import be.elevenways.zenit.cms.common.resource.PanelResource;
import be.elevenways.zenit.cms.common.resource.QuickCreateSpec;
import be.elevenways.zenit.cms.common.resource.ResourceForm;
import be.elevenways.zenit.cms.common.resource.ResourceList;
import be.elevenways.zenit.cms.common.resource.ResourceMutations;
import be.elevenways.zenit.cms.common.resource.ResourceReads;
import be.elevenways.zenit.cms.common.resource.ResourceTabs;
import be.elevenways.zenit.cms.common.schema.ColumnSpec;
import be.elevenways.zenit.cms.common.schema.TableSpec;
import be.elevenways.zenit.common.edit.FormSpec;
import be.elevenways.zenit.common.operation.SubjectType;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.ui.Icon;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Per-owner quota overrides from parts: the floor IS the page (list, forms, writes), because an override offers nothing
 * beyond its cap columns. The subject-set key is admin-entered; a picker over known owners arrives with the
 * tenant-facing /manage instance surface.
 *
 * @author Jelle De Loecker
 * @since  0.9.0
 */
public final class InstanceQuotaParts {

    /** The entry's slug, which the instance list names as a related page. */
    public static final String SLUG = "instance-quotas";

    private InstanceQuotaParts() {
    }

    /** @return the operator's quota overrides */
    public static @NonNull PanelResource<Row> admin() {
        // AIDEV-NOTE: every override column the reserve hooks read must be ON this form. M073 added max_disk_gb and
        // max_nics and InstanceDeviceQuota.diskLimitFor/nicLimitFor consult them, but they were absent here for a
        // wave: the columns existed, were enforced, carried form copy, and could not be set by anyone. Adding a cap
        // column without adding it here is the silent-success shape.
        FormSpec form = FormSpec.builder()
            .add(InstanceQuotaModel.SUBJECTS)
            .add(InstanceQuotaModel.MAX_INSTANCES)
            .add(InstanceQuotaModel.MAX_MEMORY_MB)
            .add(InstanceQuotaModel.MAX_DISK_GB)
            .add(InstanceQuotaModel.MAX_NICS)
            .add(InstanceQuotaModel.MAX_SITES)
            .add(InstanceQuotaModel.MAX_DATABASES)
            .build();
        TableSpec<Row> table = TableSpec.<Row>builder()
            .column(ColumnSpec.fromField(InstanceQuotaModel.SUBJECTS).filterable().copyable().build())
            .column(ColumnSpec.fromField(InstanceQuotaModel.MAX_INSTANCES).build())
            .column(ColumnSpec.fromField(InstanceQuotaModel.MAX_MEMORY_MB).build())
            .column(ColumnSpec.fromField(InstanceQuotaModel.MAX_DISK_GB).build())
            .column(ColumnSpec.fromField(InstanceQuotaModel.MAX_NICS).build())
            .column(ColumnSpec.fromField(InstanceQuotaModel.MAX_SITES).build())
            .column(ColumnSpec.fromField(InstanceQuotaModel.MAX_DATABASES).build())
            .build();
        return PanelResource.builder(HohenheimIds.id("instance_quota"), SLUG,
                SubjectType.record(InstanceQuotaModel.MODEL_ID))
            .label(Microcopy.of("plural").withFilter("scope", "instance_quota"))
            .recordLabel(Microcopy.of("singular").withFilter("scope", "instance_quota"))
            // Demoted out of the sidebar, so this sentence reaches a reader through the panel index and the
            // related-pages menu of the list that names it.
            .description(CmsSupport.navHint("instance_quota"))
            .icon(Icon.of("gauge"))
            .navGroup(HohenheimPanel.DEPLOY_GROUP)
            .navOrder(16)
            .showInNav(false)
            .reads(ResourceReads.rows()
                .mapCells((row, column) -> InstanceQuotaModel.SUBJECTS.getName().equals(column.name())
                    ? HohenheimAccess.labelSubjects(row.get(InstanceQuotaModel.SUBJECTS)) : null)
                .title(InstanceQuotaParts::title))
            .form(ResourceForm.<Row>of(form)
                // The three caps an override is usually opened for; the rest default to unlimited.
                .quickCreate(QuickCreateSpec.of(InstanceQuotaModel.SUBJECTS.getName(),
                    InstanceQuotaModel.MAX_INSTANCES.getName(), InstanceQuotaModel.MAX_MEMORY_MB.getName()))
                // AIDEV-NOTE: every cap, and only the caps. A cap is read at RESERVE time (InstanceQuota) and never
                // swept retroactively, so lowering one here cannot retire anything already running: it decides the
                // next reservation, which is what an operator raising a limit mid-incident wants. SUBJECTS is excluded
                // because it is the row's unique key: retyping it in place silently re-points the override at another
                // owner, which reads as "I edited a number".
                .inlineEditable(InstanceQuotaModel.MAX_INSTANCES, InstanceQuotaModel.MAX_MEMORY_MB,
                    InstanceQuotaModel.MAX_DISK_GB, InstanceQuotaModel.MAX_NICS, InstanceQuotaModel.MAX_SITES,
                    InstanceQuotaModel.MAX_DATABASES)
                .build())
            // The subject expression is the only text a quota carries.
            .list(ResourceList.rows(table).chrome(ListChrome.MINIMAL).search(InstanceQuotaModel.SUBJECTS).build())
            .writes(ResourceMutations.rows().create().update().delete(InstanceChildDeletes.QUOTA).build())
            .tabs(ResourceTabs.<Row>none().withHistory().withContributions())
            .build();
    }

    /**
     * The owners' names wherever a quota is SPOKEN ABOUT rather than listed, so a delete confirmation never asks an
     * operator to retire "user:5".
     *
     * AIDEV-NOTE: the label is {@link HohenheimAccess#labelSubjects}' and never a lookup spelled here: the released
     * claim list renders the same packed set, and two labellers is how one of them ends up printing a raw token. The
     * empty (operator) set labels to nothing, so that one case keeps the derived title.
     */
    static @Nullable String title(@NonNull Row quota) {
        String labels = HohenheimAccess.labelSubjects(quota.get(InstanceQuotaModel.SUBJECTS));
        return labels.isEmpty() ? null : labels;
    }
}
