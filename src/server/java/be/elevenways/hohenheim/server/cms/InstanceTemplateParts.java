package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.HohenheimEndpoints;
import be.elevenways.hohenheim.HohenheimIds;
import be.elevenways.hohenheim.HohenheimMicrocopy;
import be.elevenways.hohenheim.HohenheimParams;
import be.elevenways.hohenheim.HohenheimSlugs;
import be.elevenways.hohenheim.instance.InstanceTemplateOperations;
import be.elevenways.hohenheim.model.InstanceTemplateModel;
import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.hohenheim.server.instance.InstanceKinds;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.protoblast.common.typed.CoreTypes;
import be.elevenways.zenit.cms.common.action.ActionPlacement;
import be.elevenways.zenit.cms.common.action.ActionStyle;
import be.elevenways.zenit.cms.common.action.CmsActionResult;
import be.elevenways.zenit.cms.common.action.PanelAction;
import be.elevenways.zenit.cms.common.page.CmsRoutes;
import be.elevenways.zenit.cms.common.resource.ChildList;
import be.elevenways.zenit.cms.common.resource.ListChrome;
import be.elevenways.zenit.cms.common.resource.PanelResource;
import be.elevenways.zenit.cms.common.resource.RelatedPage;
import be.elevenways.zenit.cms.common.resource.ResourceForm;
import be.elevenways.zenit.cms.common.resource.ResourceList;
import be.elevenways.zenit.cms.common.resource.ResourceMutations;
import be.elevenways.zenit.cms.common.resource.ResourceReads;
import be.elevenways.zenit.cms.common.resource.ResourceTabs;
import be.elevenways.zenit.cms.common.schema.ColumnSpec;
import be.elevenways.zenit.cms.common.schema.FilterSpec;
import be.elevenways.zenit.cms.common.schema.TableSpec;
import be.elevenways.zenit.common.edit.FieldFormEntryRegistry;
import be.elevenways.zenit.common.edit.FieldLabels;
import be.elevenways.zenit.common.edit.FormSpec;
import be.elevenways.zenit.common.edit.OptionSource;
import be.elevenways.zenit.common.edit.Select;
import be.elevenways.zenit.common.operation.SubjectType;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.field.attributes.FieldAttributes;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.common.ui.Icon;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * The template catalog's two entries from shared parts: the operator's catalog on /admin and the tenant's approved
 * catalog on /manage (the tenant twin shares the reads and owns its form, list, scope and its
 * create link).
 *
 * AIDEV-NOTE: approval is an EXPLICIT accountable operation, never a form field: it is the one act that makes a
 * template tenant-selectable, so it stamps who and when and rides the activity log. The tenant catalog exists so a
 * tenant can PICK a template: name, description and version, never the recipe (settings, scripts), and no write.
 *
 * AIDEV-TODO: the admin delete stays the plain row delete until the O2 delete family offers a per-record unavailable
 * reason (opencode-24); until then a template with live instances is refused by the model funnel's
 * InstanceCatalogGuards on submit instead of being offered dead with its count.
 *
 * @author Jelle De Loecker
 * @since  0.1.0
 */
public final class InstanceTemplateParts {

    private InstanceTemplateParts() {}

    /** The operator's catalog: every template, its recipe, approval and export. */
    public static @NonNull PanelResource<Row> admin() {
        return base(HohenheimIds.id("instance_template"))
            .navOrder(60)
            .description(HohenheimMicrocopy.INSTANCE_TEMPLATE.of("nav_hint"))
            .form(ResourceForm.<Row>of(adminForm())
                // The metadata an operator corrects while reading the list. READINESS_KIND is not here: the model
                // refuses a readiness line beside a kind that never reads it, and one inline cell commits ONE column.
                // The scripts are not here: their vocabulary gate runs at approval only. REINSTALL_POLICY decides
                // whether a reinstall wipes volumes, a data-destruction default, not a label.
                .inlineEditable(InstanceTemplateModel.VERSION, InstanceTemplateModel.READINESS_LINE,
                    InstanceTemplateModel.STOP_COMMAND)
                .build())
            .list(ResourceList.rows(adminTable()).chrome(ListChrome.MINIMAL)
                .search(InstanceTemplateModel.NAME, InstanceTemplateModel.DESCRIPTION,
                    InstanceTemplateModel.INSTALL_IMAGE, InstanceTemplateModel.SOURCE)
                .build())
            .writes(ResourceMutations.rows().create().update().delete().build())
            .actions(List.of(createInstance(HohenheimIds.id("template_create_instance"), null), export(),
                approve(), unapprove()))
            .tabs(ResourceTabs.<Row>of(List.of(contentsTab())).withHistory().withContributions())
            .relatedPages(RelatedPage.toPeer(HohenheimSlugs.INSTANCE_TEMPLATES_IMPORT))
            .build();
    }

    /**
     * The Contents tab: the framework's child list with a section per declaration kind (variables, config files, the
     * managed databases where this boot serves them, volumes), each narrowed to the template.
     */
    private static @NonNull ChildList<Row> contentsTab() {
        List<String> sections = new ArrayList<>(List.of(HohenheimSlugs.INSTANCE_TEMPLATE_VARIABLES,
            HohenheimSlugs.INSTANCE_TEMPLATE_FILES));
        if (InstanceAttachmentParts.databasesServed()) {
            sections.add(HohenheimSlugs.INSTANCE_TEMPLATE_DATABASES);
        }
        sections.add(HohenheimSlugs.INSTANCE_TEMPLATE_VOLUMES);
        ChildList<Row> tab = ChildList.<Row>sections(HohenheimSlugs.Tab.CONTENTS,
                HohenheimMicrocopy.INSTANCE_TEMPLATE.of("contents"), sections.toArray(String[]::new))
            .icon(Icon.of("list-check"));
        for (String section : sections) {
            tab.hide(section, TemplateChildParts.OWNER_COLUMN);
        }
        return tab;
    }

    /** The tenant's catalog: approved templates only (operators see every one), to pick from and nothing more. */
    public static @NonNull PanelResource<Row> manage() {
        // Reached through Put something online and the Apps list's toolbar (ManagePanel's sidebar note).
        return ManageTwin.reached(base(ManageTwin.id("instance_template")), TenantScopes.INSTANCE_TEMPLATES,
                ResourceTabs.none())
            .navOrder(60)
            .standsUnder(HohenheimSlugs.APPS)
            .description(HohenheimMicrocopy.INSTANCE_TEMPLATE.of("nav_hint"))
            .form(ResourceForm.<Row>of(FormSpec.builder()
                .add(InstanceTemplateModel.NAME)
                .add(InstanceTemplateModel.DESCRIPTION)
                .add(InstanceTemplateModel.VERSION)
                .build()).build())
            .list(ResourceList.rows(TableSpec.<Row>builder()
                    .column(ColumnSpec.fromField(InstanceTemplateModel.NAME).subtext("description").build())
                    .column(ColumnSpec.fromField(InstanceTemplateModel.DESCRIPTION).hidden().build())
                    .column(ColumnSpec.fromField(InstanceTemplateModel.VERSION).build())
                    .build())
                .chrome(ListChrome.MINIMAL)
                .search(InstanceTemplateModel.NAME, InstanceTemplateModel.DESCRIPTION)
                .build())
            .actions(List.of(createInstance(ManageTwin.id("template_create_instance"),
                HohenheimAccess::canCreateInstances)))
            .build();
    }

    /** The parts both entries share: identity, label, icon, nav group and the plain row reads. */
    private static PanelResource.@NonNull Builder<Row> base(@NonNull Identifier id) {
        return PanelResource.builder(id, HohenheimSlugs.INSTANCE_TEMPLATES,
                SubjectType.record(InstanceTemplateModel.MODEL_ID))
            .label(HohenheimMicrocopy.INSTANCE_TEMPLATE.of("plural"))
            .recordLabel(HohenheimMicrocopy.INSTANCE_TEMPLATE.of("singular"))
            .icon(Icon.of("clone"))
            .navGroup(HohenheimPanel.DEPLOY_GROUP)
            .reads(ResourceReads.rows());
    }

    private static @NonNull FormSpec adminForm() {
        return FormSpec.builder()
            .add(InstanceTemplateModel.NAME)
            .add(InstanceTemplateModel.DESCRIPTION)
            // A template of a generated-only kind is the same lying affordance one level removed: every create from it
            // lands on the OwnedInstances refusal. Same derivation as the instance picker (InstanceParts).
            .add(Select.of(InstanceTemplateModel.KIND)
                .options(OptionSource.supplied(InstanceKinds::authorableOptions))
                .clearable(!Boolean.TRUE.equals(InstanceTemplateModel.KIND.getAttribute(FieldAttributes.REQUIRED)))
                .build())
            .add(FieldFormEntryRegistry.INSTANCE.deriveEntry(InstanceTemplateModel.SETTINGS))
            .add(InstanceTemplateModel.VERSION)
            .add(InstanceTemplateModel.INSTALL_IMAGE)
            .add(InstanceTemplateModel.INSTALL_SCRIPT)
            .add(InstanceTemplateModel.UPDATE_SCRIPT)
            .add(FieldFormEntryRegistry.INSTANCE.deriveEntry(InstanceTemplateModel.REINSTALL_POLICY))
            // READINESS_KIND decides whether the line below is ever read; leaving it out left the enum unreachable.
            .add(FieldFormEntryRegistry.INSTANCE.deriveEntry(InstanceTemplateModel.READINESS_KIND))
            .add(InstanceTemplateModel.READINESS_LINE)
            .add(InstanceTemplateModel.READINESS_TARGET)
            .add(InstanceTemplateModel.STOP_COMMAND)
            .build();
    }

    private static @NonNull TableSpec<Row> adminTable() {
        return TableSpec.<Row>builder()
            .column(ColumnSpec.fromField(InstanceTemplateModel.NAME).filterable().subtext("version").build())
            .column(ColumnSpec.fromField(InstanceTemplateModel.VERSION).hidden().build())
            .column(ColumnSpec.fromField(InstanceTemplateModel.KIND).filterable().build())
            .column(ColumnSpec.fromField(InstanceTemplateModel.INSTALL_IMAGE).copyable().build())
            .column(ColumnSpec.fromField(InstanceTemplateModel.REINSTALL_POLICY).build())
            .column(ColumnSpec.fromField(InstanceTemplateModel.APPROVED_AT).build())
            .column(ColumnSpec.fromField(InstanceTemplateModel.SOURCE).build())
            .filter(FilterSpec.leaf(InstanceTemplateModel.NAME, CoreTypes.CONTAINS)
                .label(FieldLabels.labelFor(InstanceTemplateModel.NAME)).build())
            .filter(FilterSpec.leaf(InstanceTemplateModel.KIND, CoreTypes.EQUALS)
                .label(FieldLabels.labelFor(InstanceTemplateModel.KIND)).build())
            .build();
    }

    /**
     * Start a create from this template: the hosting panel's from-template wizard with the template selected.
     *
     * @param shown null offers it to everyone who reaches the row; else hiding is the affordance only, the create
     *              funnel asks the same authority again on submit
     */
    private static @NonNull PanelAction<Row> createInstance(@NonNull Identifier id,
                                                            @Nullable Predicate<AccessContext> shown) {
        PanelAction.LinkBuilder<Row> link = PanelAction.<Row>link(id, ActionPlacement.ROW)
            .label(HohenheimMicrocopy.INSTANCE_TEMPLATE.of("create_instance"))
            .icon(Icon.of("plus"))
            .inlineInRow(true)
            .route((template, request) -> CmsRoutes.list(request.panelSlug(), HohenheimSlugs.PUT_ONLINE)
                .with(HohenheimParams.FROM_TEMPLATE_TEMPLATE, template.get(InstanceTemplateModel.ID)));
        if (shown != null) {
            link.shownWhen((template, access) -> shown.test(access));
        }
        return link.build();
    }

    private static @NonNull PanelAction<Row> export() {
        return PanelAction.<Row>link(HohenheimIds.id("export_template"), ActionPlacement.ROW)
            .label(HohenheimMicrocopy.INSTANCE_TEMPLATE.of("export"))
            .icon(Icon.of("download"))
            .inlineInRow(false)
            .route((template, request) -> HohenheimEndpoints.INSTANCE_TEMPLATES_EXPORT
                .with(HohenheimEndpoints.TEMPLATE_ID, template.get(InstanceTemplateModel.ID)))
            .build();
    }

    private static @NonNull PanelAction<Row> approve() {
        return PanelAction.<Row, Void>places(InstanceTemplateOperations.APPROVE_TEMPLATE, ActionPlacement.ROW,
                (request, result) -> CmsActionResult.refreshWithToast(HohenheimMicrocopy.INSTANCE_TEMPLATE
                    .of("approved_toast")
                    .withArg("name", request.subject().get(InstanceTemplateModel.NAME))))
            .inlineInRow(false)
            .confirmation(Confirmations.of(HohenheimMicrocopy.INSTANCE_TEMPLATE.of("approve"),
                HohenheimMicrocopy.INSTANCE_TEMPLATE.of("approve_confirm"), ActionStyle.DEFAULT))
            .build();
    }

    private static @NonNull PanelAction<Row> unapprove() {
        return PanelAction.<Row, Void>places(InstanceTemplateOperations.UNAPPROVE_TEMPLATE, ActionPlacement.ROW,
                (request, result) -> CmsActionResult.refreshWithToast(HohenheimMicrocopy.INSTANCE_TEMPLATE
                    .of("unapproved_toast")
                    .withArg("name", request.subject().get(InstanceTemplateModel.NAME))))
            .inlineInRow(false)
            .confirmation(Confirmations.of(HohenheimMicrocopy.INSTANCE_TEMPLATE.of("unapprove"),
                HohenheimMicrocopy.INSTANCE_TEMPLATE.of("unapprove_confirm"), ActionStyle.DEFAULT))
            .build();
    }

    /** An operator always; a tenant who may create, once anything is approved. */
    /**
     * Whether this viewer may start something from the catalog: an admin always, anyone else only with the right to
     * create instances and at least one approved template. /manage offers Put something online by it too.
     */
    static boolean offersTenantCatalog(@NonNull AccessContext access) {
        if (HohenheimAccess.isAdmin(access)) {
            return true;
        }
        return HohenheimAccess.canCreateInstances(access) && Models.get(InstanceTemplateModel.class).find()
            .where(InstanceTemplateModel.APPROVED_AT.isNotNull()).count() > 0;
    }
}
