package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.model.PreviewDeploymentModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.server.preview.PreviewDeployments;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.cms.common.action.ActionStyle;
import be.elevenways.zenit.cms.common.action.CmsActionResult;
import be.elevenways.zenit.cms.common.action.ConfirmationSpec;
import be.elevenways.zenit.cms.common.action.RowAction;
import be.elevenways.zenit.cms.common.panel.NavGroup;
import be.elevenways.zenit.cms.common.resource.ResourceFieldBinding;
import be.elevenways.zenit.cms.common.resource.RowResource;
import be.elevenways.zenit.cms.common.schema.ColumnSpec;
import be.elevenways.zenit.cms.common.schema.TableSpec;
import be.elevenways.zenit.common.edit.FieldAccess;
import be.elevenways.zenit.common.edit.FormSpec;
import be.elevenways.zenit.common.edit.RelationPick;
import be.elevenways.zenit.common.orm.activity.ActivityLog;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.common.ui.Icon;
import be.elevenways.zenit.common.validation.Violations;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Preview deployment inventory: lifecycle-owned rows are read-only, but a preview can
 * be CREATED here for a chosen ref -- the manual lane beside the webhook one. The
 * create form is site + ref only (every other field is EDIT/DETAIL-scoped on the
 * model, so a hand-crafted submission cannot half-author hostname or status); the
 * submit claims the quota-charged row synchronously and builds in the background,
 * which is why quota and unsupported-type refusals land on the form by name.
 */
public class PreviewDeploymentResource extends RowResource {

    private final FormSpec formSpec = FormSpec.builder()
        .add(RelationPick.of(PreviewDeploymentModel.SITE_ID, SiteModel.MODEL_ID).build())
        .add(PreviewDeploymentModel.REF)
        .add(PreviewDeploymentModel.PR_NUMBER)
        .add(PreviewDeploymentModel.HEAD_SHA)
        .add(PreviewDeploymentModel.HOSTNAME)
        .add(PreviewDeploymentModel.STATUS)
        .add(PreviewDeploymentModel.EXPIRES_AT)
        .add(PreviewDeploymentModel.INSTANCE_ID)
        .add(PreviewDeploymentModel.LAST_ERROR)
        .build();

    private final TableSpec<Row> tableSpec = TableSpec.<Row>builder()
        .column(ColumnSpec.fromField(PreviewDeploymentModel.HOSTNAME).filterable().build())
        .column(ColumnSpec.fromField(PreviewDeploymentModel.REF).filterable().build())
        .column(ColumnSpec.fromField(PreviewDeploymentModel.STATUS).filterable().build())
        .column(ColumnSpec.fromField(PreviewDeploymentModel.EXPIRES_AT).sortable().build())
        .column(ColumnSpec.fromField(PreviewDeploymentModel.CREATED_AT).sortable().build())
        .build();

    @Override public @NonNull Identifier id() { return Identifier.of("hohenheim", "preview_deployment"); }
    @Override public @NonNull Microcopy label() { return Microcopy.of("plural").withFilter("scope", "preview_deployment"); }
    @Override public @NonNull String slug() { return "previews"; }
    @Override public @NonNull Model model() { return Models.get(PreviewDeploymentModel.class); }
    @Override public @NonNull FormSpec formSpec() { return this.formSpec; }
    @Override public @NonNull TableSpec<Row> tableSpec() { return this.tableSpec; }
    @Override public @NonNull NavGroup navGroup() { return HohenheimPanel.DEPLOY_GROUP; }
    @Override public int navOrder() { return 20; }

    @Override public boolean showInNav() { return false; }


    /**

     * Declared even though /admin hides this peer: the DELEGATED subclass shows it (a tenant

     * panel has no Sites list to hang the header link on) and inherits this sentence.

     */

    @Override

    public @Nullable Microcopy description() {

        return CmsSupport.navHint("preview_deployment");

    }
    @Override public @NonNull Icon icon() { return Icon.of("flask"); }

    @Override public boolean creatable() { return true; }
    @Override public boolean updatable() { return false; }

    /**
     * The create submit reaches OUTSIDE the datasource (arms a record schedule, spawns
     * the build); a scoped-create rollback would orphan those, so the subclass gate
     * refuses out-of-scope callers as its first statement instead.
     */
    @Override public boolean verifiesScopeBeforeMutating() { return true; }

    @Override
    public @NonNull List<ResourceFieldBinding> fieldBindings() {
        List<ResourceFieldBinding> bindings = new ArrayList<>();
        for (var entry : this.formSpec.entries()) {
            boolean creationInput = PreviewDeploymentModel.SITE_ID.getName().equals(entry.name())
                || PreviewDeploymentModel.REF.getName().equals(entry.name());
            // Site and ref are the CREATE inputs; once the row exists the lifecycle
            // engine owns every column. The enforcement point passes record == null
            // exactly on CREATE; the record-less RENDER path also decides null, which
            // is safe here because updatable() == false already forces the whole
            // existing-record form read-only and 404s UPDATE_SUBMIT.
            bindings.add(ResourceFieldBinding.of(entry.name(), creationInput
                ? FieldAccess.customRecordAware((ctx, record) -> record == null
                    ? FieldAccess.Decision.EDITABLE : FieldAccess.Decision.READONLY)
                : FieldAccess.alwaysReadonly()));
        }
        return bindings;
    }

    /**
     * The manual creation lane: claim the quota-charged preview row synchronously
     * (refusals become form violations) and build in the background. The activity row
     * mirrors the webhook lane's {@code preview_triggered}, with the actor recorded by
     * the ordinary accountability resolver.
     */
    @Override
    public @NonNull Object persistRow(@NonNull Map<String, Object> coerced,
                                      @NonNull AccessContext accessContext) {
        this.requireCreateAuthority(coerced, accessContext);
        Object siteId = coerced.get(PreviewDeploymentModel.SITE_ID.getName());
        if (!(siteId instanceof Number siteNumber)) {
            throw Violations.ofField(PreviewDeploymentModel.SITE_ID.getName(), siteId,
                CmsSupport.violationText("preview_site_required"));
        }
        String ref = coerced.get(PreviewDeploymentModel.REF.getName()) instanceof String value
            ? value.trim() : "";
        if (ref.isEmpty()) {
            throw Violations.ofField(PreviewDeploymentModel.REF.getName(), ref,
                CmsSupport.violationText("preview_ref_required"));
        }
        try {
            Row preview = PreviewDeployments.queue(siteNumber.intValue(), ref, null, null);
            ActivityLog.record(Models.get(SiteModel.class), siteNumber.intValue(),
                "preview_triggered", "manual:" + ref);
            return preview.get(PreviewDeploymentModel.ID);
        } catch (Violations refused) {
            throw refused;
        } catch (Exception failed) {
            throw Violations.ofForm(CmsSupport.violationText("preview_queue_failed")
                .withArg("reason", failed.getMessage() != null
                    ? failed.getMessage() : failed.getClass().getSimpleName()));
        }
    }

    /**
     * Admin surface: the panel gate is the authority. The /manage subclass narrows
     * this to a walk-confirmed manage grant on the chosen site.
     */
    protected void requireCreateAuthority(@NonNull Map<String, Object> coerced,
                                          @NonNull AccessContext accessContext) {
    }

    @Override
    public @NonNull List<RowAction<Row>> rowActions() {
        List<RowAction<Row>> actions = new ArrayList<>(super.rowActions());
        actions.add(RowAction.Invoke.<Row>builder(Identifier.of("hohenheim", "destroy_preview"))
            .label(Microcopy.of("destroy_now").withFilter("scope", "preview_deployment"))
            .icon(Icon.of("trash"))
            .confirmation(ConfirmationSpec.builder()
                .title(Microcopy.of("destroy_now").withFilter("scope", "preview_deployment"))
                .body(Microcopy.of("destroy_confirm").withFilter("scope", "preview_deployment"))
                .style(ActionStyle.DESTRUCTIVE)
                .build())
            .handler((row, ctx) -> {
                try {
                    PreviewDeployments.destroy(
                        row.get(PreviewDeploymentModel.ID), "operator");
                    return CmsActionResult.toast(
                        Microcopy.of("destroyed").withFilter("scope", "preview_deployment")
                            .withArg("hostname",
                                row.get(PreviewDeploymentModel.HOSTNAME)));
                } catch (Exception failed) {
                    return CmsActionResult.errorToast(
                        Microcopy.of("destroy_failed").withFilter("scope", "preview_deployment")
                            .withArg("reason", failed.getMessage() != null
                                ? failed.getMessage() : failed.toString()));
                }
            })
            .build());
        return actions;
    }
}
