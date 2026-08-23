package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.HohenheimEndpoints;
import be.elevenways.hohenheim.HohenheimParams;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.InstanceTemplateModel;
import be.elevenways.hohenheim.server.instance.CommunityScripts;
import be.elevenways.hohenheim.server.instance.InstanceKinds;
import be.elevenways.protoblast.common.http.Uri;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.cms.common.action.CmsActionResult;
import be.elevenways.zenit.cms.common.action.ConfirmationSpec;
import be.elevenways.zenit.cms.common.action.RowAction;
import be.elevenways.zenit.cms.common.page.CmsEndpoints;
import be.elevenways.zenit.cms.common.page.CmsRoutes;
import be.elevenways.zenit.cms.common.panel.NavGroup;
import be.elevenways.zenit.cms.common.resource.ListChrome;
import be.elevenways.zenit.cms.common.resource.RecordScopedPage;
import be.elevenways.zenit.cms.common.resource.RelatedPage;
import be.elevenways.zenit.cms.common.resource.RowResource;
import be.elevenways.zenit.cms.common.schema.ColumnSpec;
import be.elevenways.zenit.cms.common.schema.FilterSpec;
import be.elevenways.zenit.cms.common.schema.TableSpec;
import be.elevenways.zenit.common.edit.FieldFormEntryRegistry;
import be.elevenways.zenit.common.edit.FieldLabels;
import be.elevenways.zenit.common.edit.FormSpec;
import be.elevenways.zenit.common.edit.OptionSource;
import be.elevenways.zenit.common.edit.Select;
import be.elevenways.zenit.common.orm.activity.ActivityLog;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.field.Field;
import be.elevenways.zenit.common.orm.field.attributes.FieldAttributes;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.routing.RouteTarget;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.common.ui.Icon;
import be.elevenways.zenit.common.validation.Violations;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * The template catalog admin. Approval is an EXPLICIT accountable row action, never a
 * form field: it is the one act that makes a template tenant-selectable, so it stamps
 * who and when and rides the activity log.
 */
public class InstanceTemplateResource extends RowResource {

    private final FormSpec formSpec = FormSpec.builder()
        .add(InstanceTemplateModel.NAME)
        .add(InstanceTemplateModel.DESCRIPTION)
        // A template of a generated-only kind is the same lying affordance one level
        // removed: every create from it lands on the OwnedInstances refusal. Same
        // derivation as the instance picker (see the AIDEV-NOTE in InstanceResource).
        .add(Select.of(InstanceTemplateModel.KIND)
            .options(OptionSource.supplied(InstanceKinds::authorableOptions))
            .clearable(!Boolean.TRUE.equals(
                InstanceTemplateModel.KIND.getAttribute(FieldAttributes.REQUIRED)))
            .build())
        .add(FieldFormEntryRegistry.INSTANCE.deriveEntry(InstanceTemplateModel.SETTINGS))
        .add(InstanceTemplateModel.VERSION)
        .add(InstanceTemplateModel.INSTALL_IMAGE)
        .add(InstanceTemplateModel.INSTALL_SCRIPT)
        .add(InstanceTemplateModel.UPDATE_SCRIPT)
        .add(FieldFormEntryRegistry.INSTANCE.deriveEntry(InstanceTemplateModel.REINSTALL_POLICY))
        .add(InstanceTemplateModel.READINESS_LINE)
        .add(InstanceTemplateModel.STOP_COMMAND)
        .build();

    private final TableSpec<Row> tableSpec = TableSpec.<Row>builder()
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

    @Override public @NonNull Identifier id() { return Identifier.of("hohenheim", "instance_template"); }
    @Override public @NonNull Microcopy label() { return Microcopy.of("plural").withFilter("scope", "instance_template"); }
    @Override public @Nullable Microcopy recordLabel() { return Microcopy.of("singular").withFilter("scope", "instance_template"); }
    @Override public @NonNull String slug() { return "instance-templates"; }
    @Override public @NonNull Model model() { return Models.get(InstanceTemplateModel.class); }
    @Override public @NonNull FormSpec formSpec() { return this.formSpec; }
    @Override public @NonNull TableSpec<Row> tableSpec() { return this.tableSpec; }
    @Override public @NonNull ListChrome listChrome() { return ListChrome.MINIMAL; }

    /** Templates are hunted for by name, by the image they install from, and by where they were imported from. */
    @Override
    public @NonNull List<Field<?, ?>> searchFields() {
        return List.of(InstanceTemplateModel.NAME, InstanceTemplateModel.DESCRIPTION, InstanceTemplateModel.INSTALL_IMAGE, InstanceTemplateModel.SOURCE);
    }

    @Override public @NonNull NavGroup navGroup() { return HohenheimPanel.DEPLOY_GROUP; }
    @Override public int navOrder() { return 60; }

    @Override
    public @Nullable Microcopy description() {
        return Microcopy.of("nav_hint").withFilter("scope", "instance_template");
    }
    @Override public @NonNull Icon icon() { return Icon.of("clone"); }

    /**
     * The catalog metadata an operator corrects while reading the list: the version string
     * it advertises, the log line that means "ready", and the graceful stop command.
     *
     * AIDEV-NOTE: INSTALL_SCRIPT and UPDATE_SCRIPT are excluded. Their vocabulary gate runs
     * at APPROVE time only, so a cell edit would let an already-approved recipe be rewritten
     * with nothing re-judging it -- and an install script is what the whole installation
     * runs. REINSTALL_POLICY is excluded because it decides whether a reinstall WIPES
     * volumes; that is a data-destruction default, not a label.
     */
    @Override
    public @NonNull List<Field<?, ?>> inlineEditableFields() {
        return List.of(InstanceTemplateModel.VERSION, InstanceTemplateModel.READINESS_LINE,
            InstanceTemplateModel.STOP_COMMAND);
    }

    /** A template with live instances is load-bearing: refuse to delete it. */
    @Override
    public void deleteRow(@NonNull Row existing, @NonNull AccessContext accessContext) {
        Integer id = existing.get(InstanceTemplateModel.ID);
        long referencing = Models.get(InstanceModel.class).find()
            .where(InstanceModel.TEMPLATE_ID.eq(id))
            .where(InstanceModel.DELETED_AT.isNull())
            .count();
        if (referencing > 0) {
            throw Violations.ofForm(CmsSupport.violationText("template_in_use")
                .withArg("name", String.valueOf((Object) existing.get(InstanceTemplateModel.NAME)))
                .withArg("count", referencing));
        }
        super.deleteRow(existing, accessContext);
    }

    @Override
    public @NonNull List<RecordScopedPage<Row>> subpages() {
        List<RecordScopedPage<Row>> pages = new ArrayList<>(List.of(new TemplateContentsPage()));
        pages.addAll(this.frameworkSubpages());
        return pages;
    }

    /** The import page is a sibling PEER, so it is declared as one rather than linked. */
    @Override
    public @NonNull List<RelatedPage> relatedPages() {
        return List.of(RelatedPage.toPeer("instance-templates-import"));
    }

    @Override
    public @NonNull List<RowAction<Row>> rowActions() {
        List<RowAction<Row>> actions = new ArrayList<>(super.rowActions());
        actions.add(this.createInstanceAction());
        actions.add(this.approveAction());
        actions.add(this.unapproveAction());
        actions.add(this.exportAction());
        return actions;
    }

    private @NonNull RowAction<Row> createInstanceAction() {
        return RowAction.Url.<Row>builder(Identifier.of("hohenheim", "template_create_instance"))
            .label(Microcopy.of("create_instance").withFilter("scope", "instance_template"))
            .icon(Icon.of("plus"))
            // A CMS route PLUS a query parameter: composed off CmsEndpoints, since
            // CmsRoutes returns the RouteTarget interface (no with(...)).
            .url(row -> new Uri(CmsEndpoints.LIST
                .with(CmsEndpoints.PANEL_PARAM, "admin")
                .with(CmsEndpoints.RESOURCE_PARAM, "instances-from-template")
                .with(HohenheimParams.FROM_TEMPLATE_TEMPLATE,
                    row.get(InstanceTemplateModel.ID)).toUrl()))
            .build();
    }

    /** THE operator act that makes a template tenant-selectable. */
    private @NonNull RowAction<Row> approveAction() {
        return RowAction.Invoke.<Row>builder(Identifier.of("hohenheim", "approve_template"))
            .label(Microcopy.of("approve").withFilter("scope", "instance_template"))
            .icon(Icon.of("circle-check"))
            .visibleFor((row, ctx) -> row.get(InstanceTemplateModel.APPROVED_AT) == null)
            .confirmation(ConfirmationSpec.builder()
                .title(Microcopy.of("approve").withFilter("scope", "instance_template"))
                .body(Microcopy.of("approve_confirm").withFilter("scope", "instance_template"))
                .confirmLabel(Microcopy.of("approve").withFilter("scope", "instance_template"))
                .build())
            .handler((row, ctx) -> {
                // The approval-time lane of the vocabulary gate: a function-library
                // script calling helpers the shim lacks must not become tenant-
                // selectable -- refused BY NAME here, before the stamp.
                CommunityScripts.requireVocabularyImplemented(
                    row.get(InstanceTemplateModel.INSTALL_SCRIPT), "install script");
                CommunityScripts.requireVocabularyImplemented(
                    row.get(InstanceTemplateModel.UPDATE_SCRIPT), "update script");
                row.set(InstanceTemplateModel.APPROVED_AT, Instant.now());
                row.set(InstanceTemplateModel.APPROVED_BY_USER_ID, ctx.access().principal().id());
                this.model().save(row);
                ActivityLog.record(this.model(), row.get(InstanceTemplateModel.ID),
                    "approved", "operator approval");
                return CmsActionResult.refreshWithToast(
                    Microcopy.of("approved_toast").withFilter("scope", "instance_template")
                        .withArg("name", row.get(InstanceTemplateModel.NAME)));
            })
            .build();
    }

    private @NonNull RowAction<Row> unapproveAction() {
        return RowAction.Invoke.<Row>builder(Identifier.of("hohenheim", "unapprove_template"))
            .label(Microcopy.of("unapprove").withFilter("scope", "instance_template"))
            .icon(Icon.of("circle-xmark"))
            .visibleFor((row, ctx) -> row.get(InstanceTemplateModel.APPROVED_AT) != null)
            .confirmation(ConfirmationSpec.builder()
                .title(Microcopy.of("unapprove").withFilter("scope", "instance_template"))
                .body(Microcopy.of("unapprove_confirm").withFilter("scope", "instance_template"))
                .confirmLabel(Microcopy.of("unapprove").withFilter("scope", "instance_template"))
                .build())
            .handler((row, ctx) -> {
                row.set(InstanceTemplateModel.APPROVED_AT, null);
                row.set(InstanceTemplateModel.APPROVED_BY_USER_ID, null);
                this.model().save(row);
                ActivityLog.record(this.model(), row.get(InstanceTemplateModel.ID),
                    "unapproved", "operator withdrawal");
                return CmsActionResult.refreshWithToast(
                    Microcopy.of("unapproved_toast").withFilter("scope", "instance_template")
                        .withArg("name", row.get(InstanceTemplateModel.NAME)));
            })
            .build();
    }

    private @NonNull RowAction<Row> exportAction() {
        return RowAction.Url.<Row>builder(Identifier.of("hohenheim", "export_template"))
            .label(Microcopy.of("export").withFilter("scope", "instance_template"))
            .icon(Icon.of("download"))
            .url(row -> new Uri(HohenheimEndpoints.INSTANCE_TEMPLATES_EXPORT
                .with(HohenheimEndpoints.TEMPLATE_ID, row.get(InstanceTemplateModel.ID)).toUrl()))
            .build();
    }
}
