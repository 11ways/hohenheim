package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.HohenheimParams;
import be.elevenways.hohenheim.model.InstanceTemplateModel;
import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.protoblast.common.http.Uri;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.cms.common.access.AccessDecision;
import be.elevenways.zenit.cms.common.access.AccessFunction;
import be.elevenways.zenit.cms.common.access.QueryPredicate;
import be.elevenways.zenit.cms.common.action.RowAction;
import be.elevenways.zenit.cms.common.page.CmsEndpoints;
import be.elevenways.zenit.cms.common.resource.RecordScopedPage;
import be.elevenways.zenit.cms.common.resource.RelatedPage;
import be.elevenways.zenit.cms.common.schema.ColumnSpec;
import be.elevenways.zenit.cms.common.schema.TableSpec;
import be.elevenways.zenit.common.edit.FormSpec;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.field.Field;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.routing.RouteTarget;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.common.ui.Icon;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.util.List;

/**
 * The /manage template catalog: the APPROVED templates a tenant may create from, and
 * nothing else about them.
 *
 * Approval is the operator act that makes a template tenant-selectable, so an
 * unapproved row is not merely uncreatable here -- it is invisible. The projection is
 * name, description and version: settings (image, command, environment) and the install
 * script are the RECIPE, and an install script routinely carries download URLs and
 * credentials an operator pasted. The catalog exists so a tenant can pick, not inspect.
 *
 * Import, export, approve, unapprove, edit and delete are absent: every one of them is
 * authority over what the whole installation may run.
 */
public final class ManageInstanceTemplateResource extends InstanceTemplateResource {

    private final FormSpec manageFormSpec = FormSpec.builder()
        .add(InstanceTemplateModel.NAME)
        .add(InstanceTemplateModel.DESCRIPTION)
        .add(InstanceTemplateModel.VERSION)
        .build();

    private final TableSpec<Row> manageTableSpec = TableSpec.<Row>builder()
        .column(ColumnSpec.fromField(InstanceTemplateModel.NAME).subtext("description").build())
        .column(ColumnSpec.fromField(InstanceTemplateModel.DESCRIPTION).hidden().build())
        .column(ColumnSpec.fromField(InstanceTemplateModel.VERSION).build())
        .build();

    @Override public @NonNull Identifier id() { return Identifier.of("hohenheim", "manage_instance_template"); }
    @Override public @NonNull FormSpec formSpec() { return this.manageFormSpec; }
    @Override public @NonNull TableSpec<Row> tableSpec() { return this.manageTableSpec; }
    @Override public boolean creatable() { return false; }
    @Override public boolean updatable() { return false; }
    @Override public boolean deletable() { return false; }

    /**
     * The operator resource's inline cells are dropped with the write surface.
     *
     * AIDEV-NOTE: required twice over. Registration refuses inlineEditableFields() on a
     * resource whose updatable() is false, AND this mirror narrows the formSpec to name,
     * description and version -- two of the three inherited cells back no entry of it at
     * all. The catalog exists so a tenant can PICK a template, never inspect or shape one.
     */
    @Override
    public @NonNull List<Field<?, ?>> inlineEditableFields() {
        return List.of();
    }

    /** Admins see the whole catalog; everyone else only what an operator approved. */
    @Override
    public @NonNull AccessFunction<Row> accessFunction() {
        return ctx -> HohenheimAccess.isAdmin(ctx)
            ? AccessDecision.allowAll()
            : AccessDecision.allow(QueryPredicate.of(InstanceTemplateModel.APPROVED_AT.isNotNull()));
    }

    @Override public @NonNull List<RelatedPage> relatedPages() { return List.of(); }
    @Override public @NonNull List<RecordScopedPage<Row>> subpages() { return List.of(); }

    /**
     * One action: start a create. It is hidden without
     * {@code hohenheim.instances.create} -- and hiding is only the affordance; the
     * create funnel re-asks for the same permission on submit.
     */
    @Override
    public @NonNull List<RowAction<Row>> rowActions() {
        return List.of(RowAction.Url.<Row>builder(
                Identifier.of("hohenheim", "manage_template_create_instance"))
            .label(Microcopy.of("create_instance").withFilter("scope", "instance_template"))
            .icon(Icon.of("plus"))
            .visibleFor((row, ctx) -> HohenheimAccess.canCreateInstances(ctx))
            // A CMS route PLUS a query parameter: composed off CmsEndpoints, since
            // CmsRoutes returns the RouteTarget interface (no with(...)).
            .url(row -> new Uri(CmsEndpoints.LIST
                .with(CmsEndpoints.PANEL_PARAM, "manage")
                .with(CmsEndpoints.RESOURCE_PARAM, "instances-from-template")
                .with(HohenheimParams.FROM_TEMPLATE_TEMPLATE,
                    row.get(InstanceTemplateModel.ID)).toUrl()))
            .build());
    }

    /**
     * NAV-ONLY: the catalog exists to start a create, so it stays out of the nav for a
     * tenant who may not create -- and out of an install with nothing approved. The
     * route itself remains scoped by accessFunction; this hides, it does not enforce.
     */
    @Override
    public boolean hasInScopeRecords(@NonNull AccessContext access) {
        if (HohenheimAccess.isAdmin(access)) {
            return true;
        }
        return HohenheimAccess.canCreateInstances(access)
            && Models.get(InstanceTemplateModel.class).find()
                .where(InstanceTemplateModel.APPROVED_AT.isNotNull()).count() > 0;
    }
}
