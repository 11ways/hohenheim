package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.HohenheimIds;
import be.elevenways.hohenheim.HohenheimMicrocopy;
import be.elevenways.hohenheim.HohenheimSlugs;
import be.elevenways.hohenheim.instance.VariableKind;
import be.elevenways.hohenheim.model.InstanceVariableModel;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.cms.common.resource.ChildList;
import be.elevenways.zenit.cms.common.resource.ListChrome;
import be.elevenways.zenit.cms.common.resource.PanelResource;
import be.elevenways.zenit.cms.common.resource.ResourceList;
import be.elevenways.zenit.cms.common.resource.ResourceParent;
import be.elevenways.zenit.cms.common.resource.ResourceReads;
import be.elevenways.zenit.cms.common.resource.ResourceTabs;
import be.elevenways.zenit.cms.common.schema.ColumnSpec;
import be.elevenways.zenit.cms.common.schema.TableSpec;
import be.elevenways.zenit.common.data.RowScope;
import be.elevenways.zenit.common.operation.SubjectType;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.cms.common.schema.SortSpec;
import be.elevenways.zenit.common.ui.Icon;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.Objects;

/**
 * The read-only variables of one instance, as the child list its Provisioning tab embeds on both panels.
 *
 * AIDEV-NOTE: PLAIN_VALUE is not statically secret, so it is never a field column: the value cell is computed and
 * answers only for a kind {@link VariableKind#of} reads as plain (an unknown kind reads as secret), and SECRET_VALUE
 * is no column at all. Search and sort are on the key alone. No form, inline, write or reveal part: the list is the
 * unchanged read-only view, never an editor (stage 4 contract 10, A-G8, O07).
 *
 * @author Jelle De Loecker
 * @since  0.1.0
 */
public final class InstanceVariableParts {

    /** The computed column carrying a plain value, absent for a secret or unknown kind. */
    public static final String VALUE_COLUMN = "value";

    /** Instance-owned values only: an environment's value is never shown as an instance's. */
    public static final RowScope ROWS = RowScope.within(() -> InstanceVariableModel.INSTANCE_ID.isNotNull());

    /** The Provisioning tab's embedded section over this entry. */
    public static final ChildList<Row> PROVISIONING = ChildList.sections(HohenheimSlugs.Tab.PROVISIONING,
        HohenheimMicrocopy.TEMPLATE_CONTENTS.of("variables"), HohenheimSlugs.INSTANCE_VARIABLES);

    private InstanceVariableParts() {}

    /** The identity, nav placement, parent, reads and list both twins share. */
    private static PanelResource.@NonNull Builder<Row> entry(@NonNull Identifier id) {
        TableSpec<Row> table = tableSpec();
        return PanelResource.builder(Objects.requireNonNull(id, "id cannot be null"), HohenheimSlugs.INSTANCE_VARIABLES,
                SubjectType.record(InstanceVariableModel.MODEL_ID))
            .label(HohenheimMicrocopy.INSTANCE_VARIABLE.of("plural"))
            .recordLabel(HohenheimMicrocopy.INSTANCE_VARIABLE.of("singular"))
            .icon(Icon.of("sliders"))
            .navGroup(HohenheimPanel.DEPLOY_GROUP)
            .showInNav(false)
            .standsUnder(HohenheimSlugs.INSTANCES)
            .parent(ResourceParent.of(HohenheimSlugs.INSTANCES, InstanceVariableModel.INSTANCE_ID)
                .tab(HohenheimSlugs.Tab.PROVISIONING))
            .reads(ResourceReads.rows())
            .list(ResourceList.rows(table).chrome(ListChrome.MINIMAL).search(InstanceVariableModel.KEY)
                .computed(Objects.requireNonNull(table.column(VALUE_COLUMN)),
                    (row, request) -> plainValue(row))
                .build());
    }

    /**
     * The admin panel's entry: every instance-owned value.
     *
     * AIDEV-NOTE: the panel's other entry over the variable model, EnvironmentParts.variables(), reads the
     * environment-owned rows; this one stays the source of the panel's variable pickers, as it was while it was the
     * panel's only resource over the model.
     */
    public static @NonNull PanelResource<Row> admin() {
        return entry(HohenheimIds.id("instance_variable")).scope(ROWS).pickerSource().build();
    }

    /** The /manage entry: the values of instances the viewer may view. */
    public static @NonNull PanelResource<Row> manage() {
        return ManageTwin.reached(entry(ManageTwin.id("instance_variable")), TenantScopes.INSTANCE_VARIABLES,
            ResourceTabs.none()).build();
    }

    static @NonNull TableSpec<Row> tableSpec() {
        return TableSpec.<Row>builder()
            .column(ColumnSpec.fromField(InstanceVariableModel.KEY).sortable().build())
            .column(ColumnSpec.fromField(InstanceVariableModel.KIND).sortable(false).build())
            .column(ColumnSpec.virtual(VALUE_COLUMN,
                HohenheimMicrocopy.INSTANCE_PROVISIONING.of("value")).sortable(false).build())
            .defaultSort(SortSpec.asc(InstanceVariableModel.KEY.getName()))
            .build();
    }

    /** @return the stored value of a plain variable; null for a secret or a kind nobody recognizes */
    static @Nullable String plainValue(@NonNull Row row) {
        if (VariableKind.of(row.get(InstanceVariableModel.KIND)).isSecret()) {
            return null;
        }
        Object value = row.get(InstanceVariableModel.PLAIN_VALUE);
        return value == null ? null : String.valueOf(value);
    }
}
