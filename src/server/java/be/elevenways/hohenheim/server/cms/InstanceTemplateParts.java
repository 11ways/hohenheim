package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.HohenheimEndpoints;
import be.elevenways.hohenheim.HohenheimIds;
import be.elevenways.hohenheim.HohenheimParams;
import be.elevenways.hohenheim.HohenheimSlugs;
import be.elevenways.hohenheim.instance.InstanceTemplateOperations;
import be.elevenways.hohenheim.model.InstanceTemplateModel;
import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.hohenheim.server.instance.InstanceKinds;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.cms.common.action.ActionPlacement;
import be.elevenways.zenit.cms.common.action.CmsActionResult;
import be.elevenways.zenit.cms.common.action.ConfirmationSpec;
import be.elevenways.zenit.cms.common.action.PanelAction;
import be.elevenways.zenit.cms.common.page.CmsRoutes;
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

import java.util.List;
import java.util.function.Predicate;

/**
 * The template catalog's two entries from shared parts: the operator's catalog on /admin and the tenant's approved
 * catalog on /manage (stage 4 contract 4.9: the tenant twin shares the reads and owns its form, list, scope and its
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
            .description(Microcopy.of("nav_hint").withFilter("scope", "instance_template"))
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
            .tabs(ResourceTabs.<Row>of(List.of(new TemplateContentsPage())).withHistory().withContributions())
            .relatedPages(RelatedPage.toPeer(HohenheimSlugs.INSTANCE_TEMPLATES_IMPORT))
            .build();
    }

    /** The tenant's catalog: approved templates only (operators see every one), to pick from and nothing more. */
    public static @NonNull PanelResource<Row> manage() {
        return base(HohenheimIds.id("manage_instance_template"))
            .navOrder(60)
            .description(Microcopy.of("nav_hint").withFilter("scope", "instance_template"))
            .scope(TenantScopes.INSTANCE_TEMPLATES)
            // NAV-ONLY: the catalog exists to start a create, so it stays out of the nav for a tenant who may not
            // create, and out of an install with nothing approved; the route itself stays scoped.
            .hasInScopeRecords(InstanceTemplateParts::offersTenantCatalog)
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
            .actions(List.of(createInstance(HohenheimIds.id("manage_template_create_instance"),
                HohenheimAccess::canCreateInstances)))
            .build();
    }

    /** The parts both entries share: identity, label, icon, nav group and the plain row reads. */
    private static PanelResource.@NonNull Builder<Row> base(@NonNull Identifier id) {
        return PanelResource.builder(id, HohenheimSlugs.INSTANCE_TEMPLATES,
                SubjectType.record(InstanceTemplateModel.MODEL_ID))
            .label(Microcopy.of("plural").withFilter("scope", "instance_template"))
            .recordLabel(Microcopy.of("singular").withFilter("scope", "instance_template"))
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
            .filter(FilterSpec.forField(InstanceTemplateModel.NAME, FilterSpec.Kind.TEXT)
                .label(FieldLabels.labelFor(InstanceTemplateModel.NAME)).build())
            .filter(FilterSpec.forField(InstanceTemplateModel.KIND, FilterSpec.Kind.SELECT)
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
            .label(Microcopy.of("create_instance").withFilter("scope", "instance_template"))
            .icon(Icon.of("plus"))
            .inlineInRow(true)
            .route((template, request) -> CmsRoutes.list(request.panelSlug(), InstanceFromTemplatePage.SLUG)
                .with(HohenheimParams.FROM_TEMPLATE_TEMPLATE, template.get(InstanceTemplateModel.ID)));
        if (shown != null) {
            link.shownWhen((template, access) -> shown.test(access));
        }
        return link.build();
    }

    private static @NonNull PanelAction<Row> export() {
        return PanelAction.<Row>link(HohenheimIds.id("export_template"), ActionPlacement.ROW)
            .label(Microcopy.of("export").withFilter("scope", "instance_template"))
            .icon(Icon.of("download"))
            .inlineInRow(false)
            .route((template, request) -> HohenheimEndpoints.INSTANCE_TEMPLATES_EXPORT
                .with(HohenheimEndpoints.TEMPLATE_ID, template.get(InstanceTemplateModel.ID)))
            .build();
    }

    private static @NonNull PanelAction<Row> approve() {
        return PanelAction.<Row, Void>places(InstanceTemplateOperations.APPROVE_TEMPLATE, ActionPlacement.ROW,
                (request, result) -> CmsActionResult.refreshWithToast(Microcopy.of("approved_toast")
                    .withFilter("scope", "instance_template")
                    .withArg("name", request.subject().get(InstanceTemplateModel.NAME))))
            .inlineInRow(false)
            .confirmation(confirmation("approve", "approve_confirm"))
            .build();
    }

    private static @NonNull PanelAction<Row> unapprove() {
        return PanelAction.<Row, Void>places(InstanceTemplateOperations.UNAPPROVE_TEMPLATE, ActionPlacement.ROW,
                (request, result) -> CmsActionResult.refreshWithToast(Microcopy.of("unapproved_toast")
                    .withFilter("scope", "instance_template")
                    .withArg("name", request.subject().get(InstanceTemplateModel.NAME))))
            .inlineInRow(false)
            .confirmation(confirmation("unapprove", "unapprove_confirm"))
            .build();
    }

    private static @NonNull ConfirmationSpec confirmation(@NonNull String verb, @NonNull String body) {
        return ConfirmationSpec.builder()
            .title(Microcopy.of(verb).withFilter("scope", "instance_template"))
            .body(Microcopy.of(body).withFilter("scope", "instance_template"))
            .confirmLabel(Microcopy.of(verb).withFilter("scope", "instance_template"))
            .build();
    }

    /** An operator always; a tenant who may create, once anything is approved. */
    private static boolean offersTenantCatalog(@NonNull AccessContext access) {
        if (HohenheimAccess.isAdmin(access)) {
            return true;
        }
        return HohenheimAccess.canCreateInstances(access) && Models.get(InstanceTemplateModel.class).find()
            .where(InstanceTemplateModel.APPROVED_AT.isNotNull()).count() > 0;
    }
}
