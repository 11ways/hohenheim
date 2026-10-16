package be.elevenways.hohenheim.server.cms;

import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.hohenheim.HohenheimIds;
import be.elevenways.hohenheim.HohenheimMicrocopy;
import be.elevenways.hohenheim.HohenheimSlugs;
import be.elevenways.hohenheim.model.InstanceQuotaModel;
import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.hohenheim.server.instance.InstanceChildDeletes;
import be.elevenways.zenit.cms.common.resource.ListChrome;
import be.elevenways.zenit.cms.common.resource.PanelResource;
import be.elevenways.zenit.cms.common.resource.QuickCreateSpec;
import be.elevenways.zenit.cms.common.resource.ResourceForm;
import be.elevenways.zenit.cms.common.resource.ResourceList;
import be.elevenways.zenit.cms.common.resource.ResourceMutations;
import be.elevenways.zenit.cms.common.resource.ResourceReads;
import be.elevenways.zenit.cms.common.resource.ResourceTabs;
import be.elevenways.zenit.cms.common.resource.RowSave;
import be.elevenways.zenit.cms.common.schema.ColumnSpec;
import be.elevenways.zenit.cms.common.schema.TableSpec;
import be.elevenways.zenit.common.edit.Array;
import be.elevenways.zenit.common.edit.EditContext;
import be.elevenways.zenit.common.edit.FormSpec;
import be.elevenways.zenit.common.operation.SubjectType;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.field.ListField;
import be.elevenways.zenit.common.orm.field.StringField;
import be.elevenways.zenit.common.orm.field.attributes.FieldAttributes;
import be.elevenways.zenit.common.ui.Icon;
import be.elevenways.zenit.auth.model.PermissionGroupModel;
import be.elevenways.zenit.auth.model.UserModel;
import be.elevenways.zenit.auth.server.SubjectLookup;
import be.elevenways.zenit.common.data.DataItem;
import be.elevenways.zenit.common.edit.FieldOption;
import be.elevenways.zenit.common.edit.OptionSource;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.orm.query.SortOrder;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Per-owner quota overrides from parts: the floor IS the page (list, forms, writes), because an override offers nothing
 * beyond its cap columns. The owner is PICKED, one or more accounts and permission groups (several are an owner they
 * share), never typed: an owner token names a UUID nobody can be asked to type.
 *
 * AIDEV-NOTE: the form edits the owner as its subjects ({@link #OWNERS}) while the column stores their pack. The pack
 * is written by {@link HohenheimAccess#packSubjects} alone, the one home every bucket stamp and owner lookup packs
 * through, so an override names exactly the bucket its owner is charged in.
 *
 * @author Jelle De Loecker
 * @since  0.9.0
 */
public final class InstanceQuotaParts {

    /** One owner subject ({@code user:<uuid>}, {@code group:<uuid>}), as the owner picker offers it. */
    private static final StringField OWNER = StringField.builder().name("subject").build();

    /**
     * The owner as the form edits it: the subjects {@link InstanceQuotaModel#SUBJECTS} packs, under the column's name,
     * so the row read hands the reads the packed text to unpack ({@link #ownerValues}) and the save hands the picked
     * subjects to {@link #packOwners}.
     */
    private static final ListField<String> OWNERS = ListField.<String>builder(OWNER)
        .name(InstanceQuotaModel.SUBJECTS.getName())
        .label(InstanceQuotaModel.SUBJECTS.getAttribute(FieldAttributes.LABEL_MICROCOPY))
        .help(InstanceQuotaModel.SUBJECTS.getAttribute(FieldAttributes.HELP))
        .build();

    private InstanceQuotaParts() {
    }

    /** @return the operator's quota overrides */
    public static @NonNull PanelResource<Row> admin() {
        // AIDEV-NOTE: every override column the reserve hooks read must be ON this form. M073 added max_disk_gb and
        // max_nics and OwnerBudget.DISK_GB/NICS.limitFor consult them, but they were once absent here:
        // the columns existed, were enforced, carried form copy, and could not be set by anyone. Adding a cap
        // column without adding it here is the silent-success shape.
        FormSpec form = FormSpec.builder()
            .add(Array.of(OWNERS, OWNER).options(OptionSource.dynamic(InstanceQuotaParts::ownerOptions)).build())
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
        return PanelResource.builder(HohenheimIds.id("instance_quota"), HohenheimSlugs.INSTANCE_QUOTAS,
                SubjectType.record(InstanceQuotaModel.MODEL_ID))
            .label(HohenheimMicrocopy.INSTANCE_QUOTA.of("plural"))
            .recordLabel(HohenheimMicrocopy.INSTANCE_QUOTA.of("singular"))
            // Demoted out of the sidebar, so this sentence reaches a reader through the panel index and the
            // related-pages menu of the list that names it.
            .description(CmsSupport.navHint(HohenheimMicrocopy.INSTANCE_QUOTA))
            .icon(Icon.of("gauge"))
            .navGroup(HohenheimPanel.DEPLOY_GROUP)
            .navOrder(16)
            .showInNav(false)
            .standsUnder(HohenheimSlugs.INSTANCES)
            .reads(ResourceReads.rows().mapValues(InstanceQuotaParts::ownerValues)
                .mapCells((row, column) -> InstanceQuotaModel.SUBJECTS.getName().equals(column.name())
                    ? HohenheimAccess.labelSubjects(row.get(InstanceQuotaModel.SUBJECTS)) : null)
                .title(quota -> Microcopy.literalOrNull(title(quota))))
            .form(ResourceForm.<Row>of(form)
                // The owner and the two caps an override is usually opened for; the rest default to unlimited. The
                // owner is the same multi-select the full form draws, so a shared owner is added here too.
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
            .writes(ResourceMutations.rows().create().update().delete(InstanceChildDeletes.QUOTA)
                .beforeSave(InstanceQuotaParts::packOwners).build())
            .tabs(ResourceTabs.<Row>none().withHistory().withContributions())
            .build();
    }

    /**
     * Every subject an owner can be made of, each once: every account, every permission group, and every subject the
     * stored owner names that neither answers any more (deleted since), offered as itself so saving a cap never drops
     * it from the owner.
     */
    private static @NonNull List<FieldOption<String>> ownerOptions(@Nullable EditContext context) {
        Map<String, String> owners = new LinkedHashMap<>();
        for (Row user : Models.get(UserModel.class).find().orderBy(UserModel.ID, SortOrder.ASC).all()) {
            DataItem item = SubjectLookup.userItem(user);
            if (item != null) {
                owners.putIfAbsent(item.value(), HohenheimAccess.labelSubjects(item.value()));
            }
        }
        for (Row group : Models.get(PermissionGroupModel.class).find()
                .orderBy(PermissionGroupModel.ID, SortOrder.ASC).all()) {
            DataItem item = SubjectLookup.groupItem(group);
            if (item != null) {
                owners.putIfAbsent(item.value(), HohenheimAccess.labelSubjects(item.value()));
            }
        }
        if (context != null && context.record() instanceof Row quota) {
            for (String subject : storedSubjects(quota)) {
                owners.putIfAbsent(subject, HohenheimAccess.labelSubjects(subject));
            }
        }
        List<FieldOption<String>> options = new ArrayList<>(owners.size());
        owners.forEach((subject, label) -> options.add(FieldOption.of(subject, Microcopy.literal(label))));
        return options;
    }

    /** @return the record's form values, its owner as the subjects its stored pack names */
    private static @NonNull Map<String, Object> ownerValues(@NonNull Row quota, @NonNull Map<String, Object> values) {
        Map<String, Object> mapped = new LinkedHashMap<>(values);
        mapped.put(OWNERS.getName(), new ArrayList<>(storedSubjects(quota)));
        return mapped;
    }

    /** @return the subjects the row's stored pack names; none while the row holds no pack (a picked list unsaved) */
    private static @NonNull Set<String> storedSubjects(@NonNull Row quota) {
        return quota.get(InstanceQuotaModel.SUBJECTS.getName()) instanceof String pack
            ? HohenheimAccess.parseSubjects(pack) : Set.of();
    }

    /** Stores the picked subjects as the owner's pack; none picked is the operator's empty pack. */
    private static void packOwners(@NonNull RowSave save) {
        // Only if sent: a write that leaves the owners alone carries no key for them.
        String owners = OWNERS.getName();
        if (save.values().containsKey(owners) && save.values().get(owners) instanceof List<?> picked) {
            LinkedHashSet<String> subjects = new LinkedHashSet<>();
            for (Object subject : picked) {
                subjects.add(String.valueOf(subject));
            }
            save.row().set(InstanceQuotaModel.SUBJECTS, HohenheimAccess.packSubjects(subjects));
        }
    }

    /**
     * The owners' names wherever a quota is SPOKEN ABOUT rather than listed, so a delete confirmation never asks an
     * operator to retire "user:0190f3a2-...".
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
