package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.HohenheimViolations;
import be.elevenways.hohenheim.HohenheimActivityAction;
import be.elevenways.hohenheim.HohenheimIds;
import be.elevenways.hohenheim.HohenheimMicrocopy;
import be.elevenways.hohenheim.HohenheimParams;
import be.elevenways.hohenheim.HohenheimSlugs;
import be.elevenways.hohenheim.HohenheimSources;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.PreviewDeploymentModel;
import be.elevenways.hohenheim.preview.PreviewOperations;
import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.hohenheim.model.DeployTrigger;
import be.elevenways.hohenheim.server.preview.PreviewDeployments;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.cms.common.action.ActionPlacement;
import be.elevenways.zenit.cms.common.action.ActionStyle;
import be.elevenways.zenit.cms.common.action.CmsActionResult;
import be.elevenways.zenit.cms.common.action.ConfirmationSpec;
import be.elevenways.zenit.cms.common.action.PanelAction;
import be.elevenways.zenit.cms.common.resource.ListChrome;
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
import be.elevenways.zenit.common.operation.Operation;
import be.elevenways.zenit.common.operation.OperationCommand;
import be.elevenways.zenit.common.operation.OperationFact;
import be.elevenways.zenit.common.operation.OperationForms;
import be.elevenways.zenit.common.operation.OperationGate;
import be.elevenways.zenit.common.operation.OperationInput;
import be.elevenways.zenit.common.orm.history.Change;
import be.elevenways.zenit.common.orm.history.History;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.common.ui.Icon;
import be.elevenways.zenit.common.validation.Violations;
import be.elevenways.zenit.server.operation.OperationHandlers;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Preview inventory twins over application-owned lifecycle rows. References and hostnames are unlocalized identifiers.
 *
 * @author Jelle De Loecker
 * @since 0.1.0
 */
public final class PreviewParts {

    /** The argument naming the application a preview is built of, as an app's parts name their app. */
    public static final String APP = CatalogRecordWrites.APP;

    /**
     * Queues a preview of one ref of an application, as the previews page's create does, answering the preview's id;
     * the same ref again refreshes that preview.
     *
     * AIDEV-NOTE: the page's create posts a relation pick, this a code: both run {@link #queue}, which asks the
     * application's MANAGE of anyone who is no administrator, as the /manage twin's create does.
     */
    public static final Operation<Void, OperationInput.Values, Integer> RUN =
            Operation.declare(HohenheimIds.id("run_preview"))
        .label(HohenheimMicrocopy.HOHENHEIM.of("run_preview"))
        .description(HohenheimMicrocopy.HOHENHEIM.of("run_preview_description"))
        .noSubject()
        .gate(OperationGate.open())
        .input(OperationInput.derived(FormSpec.builder()
            .add(OperationForms.recordId(APP, HohenheimMicrocopy.HELP.of("preview_app"), InstanceModel.MODEL_ID,
                true))
            .add(KindSettingsWrites.entryOf(PreviewDeploymentModel.GIT_REF))
            .build()))
        .result(Integer.class)
        .createdRecord(PreviewDeploymentModel.MODEL_ID, id -> id)
        .facts(OperationFact.IDEMPOTENT)
        .command(OperationCommand.perVerb().onDatasource("default"))
        .register();

    static {
        OperationHandlers.attach(RUN).handle(call -> {
            OperationInput.Values input = Objects.requireNonNull(call.input());
            AccessContext caller = Objects.requireNonNull(call.access(), "a preview run has its caller");
            Object app = input.get(APP);
            Map<String, Object> values = new LinkedHashMap<>();
            values.put(PreviewDeploymentModel.APPLICATION_ID.getName(),
                app == null ? null : Integer.valueOf(String.valueOf(app)));
            values.put(PreviewDeploymentModel.GIT_REF.getName(), input.get(PreviewDeploymentModel.GIT_REF.getName()));
            Object queued = KindSettingsWrites.speaking(() -> queue(values, caller, !HohenheimAccess.isAdmin(caller)),
                Map.of(PreviewDeploymentModel.APPLICATION_ID.getName(), APP));
            return Integer.valueOf(String.valueOf(queued));
        });
    }

    private PreviewParts() {}

    public static @NonNull PanelResource<Row> admin() {
        return entry(HohenheimIds.id("preview_deployment"), false).navOrder(20).showInNav(false)
            .standsUnder(HohenheimSlugs.SITES)
            .authority(ResourceAuthority.<Row>builder().create(HohenheimSources.ADMIN_ACCESS, null).build())
            .tabs(ResourceTabs.<Row>none().withHistory().withContributions()).build();
    }

    public static @NonNull PanelResource<Row> manage() {
        // Reached from the Apps list's toolbar and an app's Deploys tab (ManagePanel's sidebar note).
        return ManageTwin.reached(entry(ManageTwin.id("preview_deployment"), true), TenantScopes.PREVIEWS,
                ResourceTabs.<Row>none().withContributions())
            .navOrder(25)
            .standsUnder(HohenheimSlugs.SITES)
            .build();
    }

    private static PanelResource.@NonNull Builder<Row> entry(@NonNull Identifier id, boolean requireApplicationManage) {
        FormSpec form = formSpec();
        List<ResourceFieldBinding> bindings = new ArrayList<>();
        for (var field : form.entries()) {
            boolean input = PreviewDeploymentModel.APPLICATION_ID.getName().equals(field.name())
                || PreviewDeploymentModel.GIT_REF.getName().equals(field.name());
            bindings.add(input ? ResourceFieldBinding.frozenAfterCreate(field.name())
                : ResourceFieldBinding.of(field.name(), FieldAccess.alwaysReadonly()));
        }
        return PanelResource.builder(id, HohenheimSlugs.PREVIEWS, PreviewOperations.PREVIEW)
            .label(HohenheimMicrocopy.PREVIEW_DEPLOYMENT.of("plural"))
            .recordLabel(HohenheimMicrocopy.PREVIEW_DEPLOYMENT.of("singular"))
            .description(CmsSupport.navHint(HohenheimMicrocopy.PREVIEW_DEPLOYMENT))
            .navGroup(HohenheimPanel.DEPLOY_GROUP).icon(Icon.of("flask"))
            // No compact create: no pick chooses a preview and its list has no quick-add bar, and the required
            // application is a relation pick no compact form draws (the create page prefills it from a Deploys tab).
            .form(ResourceForm.<Row>of(form).bindings(bindings)
                .createDefaults((access, panel) -> PreviewParts.createDefaults(access)).withoutQuickCreate().build())
            .list(ResourceList.rows(tableSpec()).chrome(ListChrome.MINIMAL)
                .search(PreviewDeploymentModel.HOSTNAME, PreviewDeploymentModel.GIT_REF,
                        PreviewDeploymentModel.HEAD_SHA).build())
            .reads(ResourceReads.rows())
            .writes(ResourceMutations.rows().create(call -> queue(call.values(), call.access(),
                    requireApplicationManage))
                .scopeVerifiedBeforeWrite().ownsWriteEnvelope(ResourceVerb.CREATE).build())
            .actions(List.of(destroy()));
    }

    /**
     * The create form opened from an application's Deploys tab names that application; the queue still asks the
     * viewer's MANAGE on it, so a forged parameter only prefills a form that refuses.
     */
    private static @NonNull Map<String, Object> createDefaults(@NonNull AccessContext access) {
        Map<String, Object> values = new java.util.LinkedHashMap<>(formSpec().defaultValues());
        Integer application;
        try {
            application = CmsSupport.prefill(access.conduit(), HohenheimParams.PREVIEW_APPLICATION);
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
            .add(PreviewDeploymentModel.GIT_REF).add(PreviewDeploymentModel.PR_NUMBER).add(PreviewDeploymentModel.HEAD_SHA)
            .add(PreviewDeploymentModel.HOSTNAME).add(PreviewDeploymentModel.STATUS)
            .add(PreviewDeploymentModel.EXPIRES_AT).add(PreviewDeploymentModel.INSTANCE_ID)
            .add(PreviewDeploymentModel.LAST_ERROR).build();
    }

    static @NonNull TableSpec<Row> tableSpec() {
        return TableSpec.<Row>builder()
            .column(ColumnSpec.fromField(PreviewDeploymentModel.HOSTNAME).filterable().subtext("ref")
                    .copyable().build())
            .column(ColumnSpec.fromField(PreviewDeploymentModel.GIT_REF).filterable().hidden().build())
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
                HohenheimMicrocopy.VIOLATIONS.of("preview_application_required"));
        }
        String ref = values.get(PreviewDeploymentModel.GIT_REF.getName()) instanceof String text ? text.trim() : "";
        if (ref.isEmpty()) throw Violations.ofField(PreviewDeploymentModel.GIT_REF.getName(), ref,
            HohenheimMicrocopy.VIOLATIONS.of("preview_ref_required"));
        try {
            Row preview = PreviewDeployments.queue(owner.intValue(), ref, null, null, DeployTrigger.MANUAL);
            History.event(Models.get(InstanceModel.class), owner.intValue(), Change.fact(
                HohenheimActivityAction.PREVIEW_TRIGGERED, Map.of(HohenheimActivityAction.VIA_KEY, "manual",
                    HohenheimActivityAction.REF_KEY, ref)));
            return preview.get(PreviewDeploymentModel.ID);
        } catch (Violations refused) {
            throw refused;
        } catch (Exception failed) {
            throw Violations.ofForm(HohenheimMicrocopy.VIOLATIONS.of("preview_queue_failed")
                .withArg("reason", HohenheimViolations.reasonOf(failed)));
        }
    }

    static PanelAction<Row> destroy() {
        return PanelAction.<Row, String>places(PreviewOperations.EXPIRE, ActionPlacement.ROW,
                (request, result) -> CmsActionResult.toast(HohenheimMicrocopy.PREVIEW_DEPLOYMENT.of("destroyed")
                    .withArg("hostname", request.subject().get(PreviewDeploymentModel.HOSTNAME))))
            .label(HohenheimMicrocopy.PREVIEW_DEPLOYMENT.of("destroy_now"))
            .icon(Icon.of("trash"))
            .confirmation(ConfirmationSpec.verb(HohenheimMicrocopy.PREVIEW_DEPLOYMENT.of("destroy_now"),
                HohenheimMicrocopy.PREVIEW_DEPLOYMENT.of("destroy_confirm"), ActionStyle.DESTRUCTIVE)).build();
    }
}
