package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.HohenheimActivityAction;
import be.elevenways.hohenheim.HohenheimIds;
import be.elevenways.hohenheim.HohenheimParams;
import be.elevenways.hohenheim.HohenheimSources;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.PreviewDeploymentModel;
import be.elevenways.hohenheim.preview.PreviewOperations;
import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.hohenheim.server.instance.DeployTrigger;
import be.elevenways.hohenheim.server.preview.PreviewDeployments;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.zenit.cms.common.action.ActionPlacement;
import be.elevenways.zenit.cms.common.action.ActionStyle;
import be.elevenways.zenit.cms.common.action.CmsActionResult;
import be.elevenways.zenit.cms.common.action.ConfirmationSpec;
import be.elevenways.zenit.cms.common.action.PanelAction;
import be.elevenways.zenit.cms.common.resource.ListChrome;
import be.elevenways.zenit.cms.common.panel.PanelRequest;
import be.elevenways.zenit.cms.common.resource.PanelResource;
import be.elevenways.zenit.cms.common.resource.ResourceAuthority;
import be.elevenways.zenit.cms.common.resource.ResourceFieldBinding;
import be.elevenways.zenit.cms.common.resource.ResourceForm;
import be.elevenways.zenit.cms.common.resource.ResourceList;
import be.elevenways.zenit.cms.common.resource.ResourceMutations;
import be.elevenways.zenit.cms.common.resource.ResourceReads;
import be.elevenways.zenit.cms.common.resource.ResourceTabs;
import be.elevenways.zenit.cms.common.resource.ResourceVerb;
import be.elevenways.zenit.cms.common.schema.ColumnSpec;
import be.elevenways.zenit.cms.common.schema.TableSpec;
import be.elevenways.zenit.common.edit.FieldAccess;
import be.elevenways.zenit.common.edit.FormSpec;
import be.elevenways.zenit.common.edit.RelationPick;
import be.elevenways.zenit.common.orm.activity.ActivityLog;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.common.ui.Icon;
import be.elevenways.zenit.common.validation.Violations;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Preview inventory twins over application-owned lifecycle rows. References and hostnames are unlocalized identifiers.
 *
 * @author Jelle De Loecker
 * @since 0.1.0
 */
public final class PreviewParts {
    public static final String SLUG = "previews";
    private PreviewParts() {}

    public static @NonNull PanelResource<Row> admin() {
        return entry("preview_deployment", false).navOrder(20).showInNav(false)
            .authority(ResourceAuthority.<Row>builder().create(HohenheimSources.ADMIN_ACCESS, null).build())
            .tabs(ResourceTabs.<Row>none().withHistory().withContributions()).build();
    }

    public static @NonNull PanelResource<Row> manage() {
        return entry("manage_preview_deployment", true).navOrder(25).scope(TenantScopes.PREVIEWS)
            .hasInScopeRecords(ManagePanel::hasManageScope)
            .tabs(ResourceTabs.<Row>none().withContributions()).build();
    }

    private static PanelResource.@NonNull Builder<Row> entry(String id, boolean requireApplicationManage) {
        FormSpec form = formSpec();
        List<ResourceFieldBinding> bindings = new ArrayList<>();
        for (var field : form.entries()) {
            boolean input = PreviewDeploymentModel.APPLICATION_ID.getName().equals(field.name())
                || PreviewDeploymentModel.REF.getName().equals(field.name());
            bindings.add(ResourceFieldBinding.of(field.name(), input
                ? FieldAccess.customRecordAware((access, record) -> record == null
                    ? FieldAccess.Decision.EDITABLE : FieldAccess.Decision.READONLY)
                : FieldAccess.alwaysReadonly()));
        }
        return PanelResource.builder(HohenheimIds.id(id), SLUG, PreviewOperations.PREVIEW)
            .label(Microcopy.of("plural").withFilter("scope", "preview_deployment"))
            .recordLabel(Microcopy.of("singular").withFilter("scope", "preview_deployment"))
            .description(CmsSupport.navHint("preview_deployment"))
            .navGroup(HohenheimPanel.DEPLOY_GROUP).icon(Icon.of("flask"))
            .form(ResourceForm.<Row>of(form).bindings(bindings).createDefaults(PreviewParts::createDefaults).build())
            .list(ResourceList.rows(tableSpec()).chrome(ListChrome.MINIMAL)
                .search(PreviewDeploymentModel.HOSTNAME, PreviewDeploymentModel.REF, PreviewDeploymentModel.HEAD_SHA).build())
            .reads(ResourceReads.rows())
            .writes(ResourceMutations.rows().create(call -> queue(call.values(), call.access(), requireApplicationManage))
                .scopeVerifiedBeforeWrite().ownsWriteEnvelope(ResourceVerb.CREATE).build())
            .actions(List.of(destroy()));
    }

    /**
     * The create form opened from an application's Deploys tab names that application; the queue still asks the
     * viewer's MANAGE on it, so a forged parameter only prefills a form that refuses.
     */
    private static @NonNull Map<String, Object> createDefaults(@NonNull PanelRequest request) {
        Map<String, Object> values = new java.util.LinkedHashMap<>(formSpec().defaultValues());
        Integer application;
        try {
            application = CmsSupport.prefill(request.conduit(), HohenheimParams.PREVIEW_APPLICATION);
        } catch (RuntimeException notANumber) {
            application = null;
        }
        if (application != null) {
            values.put(PreviewDeploymentModel.APPLICATION_ID.getName(), application);
        }
        return Map.copyOf(values);
    }

    static @NonNull FormSpec formSpec() {
        return FormSpec.builder()
            .add(RelationPick.of(PreviewDeploymentModel.APPLICATION_ID, InstanceModel.MODEL_ID).build())
            .add(PreviewDeploymentModel.REF).add(PreviewDeploymentModel.PR_NUMBER).add(PreviewDeploymentModel.HEAD_SHA)
            .add(PreviewDeploymentModel.HOSTNAME).add(PreviewDeploymentModel.STATUS)
            .add(PreviewDeploymentModel.EXPIRES_AT).add(PreviewDeploymentModel.INSTANCE_ID)
            .add(PreviewDeploymentModel.LAST_ERROR).build();
    }

    static @NonNull TableSpec<Row> tableSpec() {
        return TableSpec.<Row>builder()
            .column(ColumnSpec.fromField(PreviewDeploymentModel.HOSTNAME).filterable().subtext("ref").copyable().build())
            .column(ColumnSpec.fromField(PreviewDeploymentModel.REF).filterable().hidden().build())
            .column(ColumnSpec.fromField(PreviewDeploymentModel.STATUS).filterable().build())
            .column(ColumnSpec.fromField(PreviewDeploymentModel.EXPIRES_AT).sortable().build())
            .column(ColumnSpec.fromField(PreviewDeploymentModel.CREATED_AT).sortable().build()).build();
    }

    static @NonNull Object queue(Map<String, Object> values, AccessContext access, boolean requireApplicationManage) {
        Object chosen = values.get(PreviewDeploymentModel.APPLICATION_ID.getName());
        // AIDEV-NOTE: admin create authority is the parts' operator permission; the delegated writer must ask the
        // selected application's MANAGE before the queue claims or charges anything.
        if (!(chosen instanceof Number owner)
                || (requireApplicationManage && !HohenheimAccess.canManageInstance(access, owner.intValue()))) {
            throw Violations.ofField(PreviewDeploymentModel.APPLICATION_ID.getName(), chosen,
                CmsSupport.violationText("preview_application_required"));
        }
        String ref = values.get(PreviewDeploymentModel.REF.getName()) instanceof String text ? text.trim() : "";
        if (ref.isEmpty()) throw Violations.ofField(PreviewDeploymentModel.REF.getName(), ref,
            CmsSupport.violationText("preview_ref_required"));
        try {
            Row preview = PreviewDeployments.queue(owner.intValue(), ref, null, null, DeployTrigger.MANUAL);
            ActivityLog.record(Models.get(InstanceModel.class), owner.intValue(),
                HohenheimActivityAction.PREVIEW_TRIGGERED, "manual:" + ref);
            return preview.get(PreviewDeploymentModel.ID);
        } catch (Violations refused) {
            throw refused;
        } catch (Exception failed) {
            throw Violations.ofForm(CmsSupport.violationText("preview_queue_failed")
                .withArg("reason", failed.getMessage() != null ? failed.getMessage() : failed.getClass().getSimpleName()));
        }
    }

    static PanelAction<Row> destroy() {
        return PanelAction.<Row, String>places(PreviewOperations.EXPIRE, ActionPlacement.ROW,
                (request, result) -> CmsActionResult.toast(Microcopy.of("destroyed")
                    .withFilter("scope", "preview_deployment")
                    .withArg("hostname", request.subject().get(PreviewDeploymentModel.HOSTNAME))))
            .label(Microcopy.of("destroy_now").withFilter("scope", "preview_deployment"))
            .icon(Icon.of("trash"))
            .confirmation(ConfirmationSpec.builder()
                .title(Microcopy.of("destroy_now").withFilter("scope", "preview_deployment"))
                .body(Microcopy.of("destroy_confirm").withFilter("scope", "preview_deployment"))
                .style(ActionStyle.DESTRUCTIVE).build()).build();
    }
}
