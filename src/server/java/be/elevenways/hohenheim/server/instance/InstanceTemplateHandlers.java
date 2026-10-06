package be.elevenways.hohenheim.server.instance;

import be.elevenways.hohenheim.HohenheimActivityAction;
import be.elevenways.hohenheim.HohenheimEndpoints;
import be.elevenways.hohenheim.HohenheimIds;
import be.elevenways.hohenheim.HohenheimSlugs;
import be.elevenways.hohenheim.HohenheimViolations;
import be.elevenways.hohenheim.instance.InstanceTemplateOperations;
import be.elevenways.hohenheim.model.InstanceTemplateModel;
import be.elevenways.hohenheim.model.ProjectModel;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.server.HandlerSupport;
import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.hohenheim.server.cms.HohenheimFlash;
import be.elevenways.hohenheim.server.cms.TenantScopes;
import be.elevenways.hohenheim.server.project.Projects;
import be.elevenways.protoblast.common.Blast;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.thread.JobRunner;
import be.elevenways.protoblast.common.time.Now;
import be.elevenways.zenit.cms.common.page.CmsRoutes;
import be.elevenways.zenit.common.conduit.Conduit;
import be.elevenways.zenit.common.data.RecordSource;
import be.elevenways.zenit.common.edit.FieldOption;
import be.elevenways.zenit.common.edit.FormEntry;
import be.elevenways.zenit.common.edit.FormSpec;
import be.elevenways.zenit.common.edit.Nested;
import be.elevenways.zenit.common.edit.OptionSource;
import be.elevenways.zenit.common.edit.Select;
import be.elevenways.zenit.common.orm.activity.ActivityLog;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.refusal.DomainRefusal;
import be.elevenways.zenit.common.refusal.ZenitRefusalReason;
import be.elevenways.zenit.common.result.ActionResult;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.common.security.ExecutionIdentity;
import be.elevenways.zenit.common.validation.Violations;
import be.elevenways.zenit.server.http.body.FormSubmissionRawValues;
import be.elevenways.zenit.server.operation.InputScope;
import be.elevenways.zenit.server.operation.OperationCall;
import be.elevenways.zenit.server.operation.OperationHandlers;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Handlers for the template endpoints beside the zenit-cms panel (the checksummed export download, the paste import)
 * and the server half of the template operations: create from template, approve and unapprove.
 *
 * AIDEV-NOTE: no authorizer asks create authority: the shared funnel refuses a caller without it by name (as the
 * submit always did), and the placements hide their affordance on the same question. The create's input scope is
 * resolved per admitted template and caller: the template's own variable form, the host pick only for an operator and
 * the projects the caller may create into; a blank secret falls back to its declared default as a server-only default,
 * so it validates without ever being rendered (O04).
 *
 * @author Jelle De Loecker
 * @since  0.1.0
 */
public final class InstanceTemplateHandlers {

    /** Background installs run here; the durable install_state is the progress record. */
    private static final JobRunner INSTALL_RUNNER = JobRunner.create("hh-template-install");

    /** The templates a caller may create from: every operation over one selected template reads through it. */
    private static final RecordSource<InstanceTemplateModel> SELECTABLE_TEMPLATES = RecordSource.of(InstanceTemplateModel.class)
        .id(HohenheimIds.id("selectable_template"))
        .project(InstanceTemplateModel.NAME, InstanceTemplateModel.KIND)
        .scopedBy(TenantScopes.INSTANCE_TEMPLATES).build();

    static {
        OperationHandlers.attach(InstanceTemplateOperations.CREATE_INSTANCE_FROM_TEMPLATE)
            .source(SELECTABLE_TEMPLATES)
            .inputScope(InstanceTemplateHandlers::inputScope)
            .handle(InstanceTemplateHandlers::createFromTemplate);
        OperationHandlers.attach(InstanceTemplateOperations.APPROVE_TEMPLATE)
            .applies(template -> template.get(InstanceTemplateModel.APPROVED_AT) == null)
            .authorize(InstanceTemplateHandlers::operatorOnly)
            .handle(InstanceTemplateHandlers::approve);
        OperationHandlers.attach(InstanceTemplateOperations.UNAPPROVE_TEMPLATE)
            .applies(template -> template.get(InstanceTemplateModel.APPROVED_AT) != null)
            .authorize(InstanceTemplateHandlers::operatorOnly)
            .handle(InstanceTemplateHandlers::unapprove);
    }

    private InstanceTemplateHandlers() {
    }

    /** @return the source an operation over one selected template reads its subject through */
    public static @NonNull RecordSource<InstanceTemplateModel> selectableTemplates() {
        return SELECTABLE_TEMPLATES;
    }

    /** Forces the class to load, so the operation's handler is attached before boot verifies it. */
    public static void init() {
        HohenheimEndpoints.INSTANCE_TEMPLATES_EXPORT.setHandler(conduit -> {
            Integer templateId = conduit.getParameter(HohenheimEndpoints.TEMPLATE_ID);
            Row template = Models.get(InstanceTemplateModel.class).findById(templateId);
            if (template == null) {
                return HandlerSupport.redirect(
                    CmsRoutes.list(HohenheimSlugs.ADMIN, HohenheimSlugs.INSTANCE_TEMPLATES));
            }
            String document = new TemplatePortability().export(template);
            String name = String.valueOf((Object) template.get(InstanceTemplateModel.NAME));
            HandlerSupport.download(conduit, "application/json", name + ".template.json",
                document.getBytes(StandardCharsets.UTF_8));
            return null;
        });

        HohenheimEndpoints.INSTANCE_TEMPLATES_IMPORT.setHandler(conduit -> {
            Map<String, Object> form = FormSubmissionRawValues.fromConduit(conduit);
            String catalogApp = HandlerSupport.submittedString(form, "catalog_app");
            if (!catalogApp.isEmpty()) {
                // The vendored community-scripts catalog: pinned content is copied
                // into a NEW unapproved row; the endpoint's admin permission is what
                // keeps script introduction an operator act.
                try {
                    int templateId = CommunityScripts.importApp(catalogApp);
                    ActivityLog.record(Models.get(InstanceTemplateModel.class), templateId,
                        HohenheimActivityAction.IMPORTED, "vendored catalog: " + catalogApp);
                    return HandlerSupport.redirect(
                        CmsRoutes.detail(HohenheimSlugs.ADMIN, HohenheimSlugs.INSTANCE_TEMPLATES, templateId));
                } catch (Violations violations) {
                    return importErrorText(conduit, HandlerSupport.violationMessage(violations));
                }
            }
            String document = HandlerSupport.submittedString(form, "document");
            String source = HandlerSupport.submittedString(form, "source");
            if (document.isEmpty()) {
                return importError(conduit, "document_required");
            }
            try {
                int templateId = new TemplatePortability().importDocument(document, source);
                ActivityLog.record(Models.get(InstanceTemplateModel.class), templateId,
                    HohenheimActivityAction.IMPORTED, source.isEmpty() ? "paste" : source);
                return HandlerSupport.redirect(
                    CmsRoutes.detail(HohenheimSlugs.ADMIN, HohenheimSlugs.INSTANCE_TEMPLATES, templateId));
            } catch (Violations violations) {
                return importErrorText(conduit, HandlerSupport.violationMessage(violations));
            }
        });
    }

    // -- approval: the operator act that makes a template tenant-selectable ------------------------------------

    /** Approval is authority over what the whole installation may run: operators alone, from every surface. */
    private static @Nullable DomainRefusal operatorOnly(@NonNull Row template, @Nullable Void input,
                                                        @NonNull AccessContext access) {
        return HohenheimAccess.isAdmin(access) ? null
            : new DomainRefusal(ZenitRefusalReason.FORBIDDEN, "template approval is an operator act");
    }

    /**
     * Stamps who approved the template and when, after the approval-time lane of the vocabulary gate: a function-library
     * script calling helpers the shim lacks must not become tenant-selectable, refused BY NAME before the stamp.
     */
    private static @Nullable Void approve(@NonNull OperationCall<Row, Void> call) {
        Row template = call.subject();
        CommunityScripts.requireVocabularyImplemented(template.get(InstanceTemplateModel.INSTALL_SCRIPT),
            "install script");
        CommunityScripts.requireVocabularyImplemented(template.get(InstanceTemplateModel.UPDATE_SCRIPT),
            "update script");
        template.set(InstanceTemplateModel.APPROVED_AT, Now.instant());
        template.set(InstanceTemplateModel.APPROVED_BY_USER_ID,
            Objects.requireNonNull(call.access(), "an operator approves").principalId());
        Models.get(InstanceTemplateModel.class).save(template);
        ActivityLog.record(Models.get(InstanceTemplateModel.class), template.get(InstanceTemplateModel.ID),
            HohenheimActivityAction.APPROVED, "operator approval");
        return null;
    }

    private static @Nullable Void unapprove(@NonNull OperationCall<Row, Void> call) {
        Row template = call.subject();
        template.set(InstanceTemplateModel.APPROVED_AT, null);
        template.set(InstanceTemplateModel.APPROVED_BY_USER_ID, null);
        Models.get(InstanceTemplateModel.class).save(template);
        ActivityLog.record(Models.get(InstanceTemplateModel.class), template.get(InstanceTemplateModel.ID),
            HohenheimActivityAction.UNAPPROVED, "operator withdrawal");
        return null;
    }

    // -- the create-from-template operation ---------------------------------------------------------------------

    /**
     * The input one admitted template asks of this caller: its variable form, the host pick for an operator alone and
     * the projects the caller may create into, each replacing the declared entry of the same name.
     *
     * AIDEV-NOTE: shared with "Put something online", whose input declares these entries under the same names; any
     * other entry of {@code declared} is kept as it is.
     */
    public static @NonNull InputScope inputScope(@Nullable Row template, @NonNull FormSpec declared,
                                                 @Nullable AccessContext access) {
        if (template == null) {
            return InputScope.of(declared);
        }
        int templateId = template.get(InstanceTemplateModel.ID);
        InstanceTemplates templates = new InstanceTemplates();
        Map<String, FormEntry> replaced = new LinkedHashMap<>();
        replaced.put(InstanceTemplateOperations.VARIABLES, Nested.of(InstanceTemplateOperations.VARIABLES)
            .subSpec(templates.variableFormSpec(templateId)).build());
        replaced.put(InstanceTemplateOperations.SERVER_ID.getName(), Select.of(InstanceTemplateOperations.SERVER_ID)
            .options(OptionSource.of(access != null && HohenheimAccess.isAdmin(access) ? servers() : List.of()))
            .build());
        replaced.put(InstanceTemplateOperations.PROJECT_ID.getName(), Select.of(InstanceTemplateOperations.PROJECT_ID)
            .options(OptionSource.of(access == null ? List.of() : projects(access))).build());
        FormSpec.Builder form = FormSpec.builder();
        for (FormEntry entry : declared.entries()) {
            form.add(replaced.getOrDefault(entry.name(), entry));
        }
        declared.steps().forEach(form::step);
        Map<String, Object> secrets = templates.secretDefaults(templateId);
        return InputScope.of(form.build()).withServerDefaults(secrets.isEmpty() ? Map.of()
            : Map.of(InstanceTemplateOperations.VARIABLES, secrets));
    }

    /**
     * THE accepted-create consumer: the shared funnel, then the template's install step in the background.
     *
     * AIDEV-NOTE: SYSTEM authority for the install: the create was the gate, and the install stamps pipeline-owned
     * columns no tenant write may author. It stays the creator's action (runAsSystem keeps the caller's attribution).
     * The durable install_state (pending, installing, installed or failed) IS the progress record.
     */
    private static @NonNull Integer createFromTemplate(
            @NonNull OperationCall<Row, InstanceTemplateOperations.CreateFromTemplate> call) {
        Row template = call.subject();
        int instanceId = new InstanceTemplates().createFromTemplate(template, Objects.requireNonNull(call.input()),
            call.access());
        if (InstanceTemplates.hasInstallStep(template)) {
            INSTALL_RUNNER.startVirtualThread(() -> ExecutionIdentity.runAsSystem("template-install", () -> {
                try {
                    new InstanceInstalls().install(instanceId);
                } catch (RuntimeException error) {
                    Blast.log("INSTANCE: background install for", instanceId, "failed:", error.getMessage());
                }
            }));
        }
        return instanceId;
    }

    private static @NonNull List<FieldOption<Integer>> servers() {
        List<FieldOption<Integer>> options = new ArrayList<>();
        for (Row server : Models.get(ServerModel.class).find().all()) {
            options.add(FieldOption.of(server.get(ServerModel.ID),
                Microcopy.literal(String.valueOf((Object) server.get(ServerModel.NAME)))));
        }
        return options;
    }

    /** The projects the caller may create into: THE one visibility policy. */
    private static @NonNull List<FieldOption<Integer>> projects(@NonNull AccessContext access) {
        List<FieldOption<Integer>> options = new ArrayList<>();
        for (Row project : Projects.visibleTo(access)) {
            options.add(FieldOption.of(project.get(ProjectModel.ID),
                Microcopy.literal(String.valueOf((Object) project.get(ProjectModel.NAME)))));
        }
        return options;
    }

    // -- plumbing -------------------------------------------------------------

    private static ActionResult<Object> importError(Conduit conduit, String key) {
        return importErrorText(conduit,
            HohenheimViolations.text(key));
    }

    private static ActionResult<Object> importErrorText(Conduit conduit, Microcopy message) {
        HohenheimFlash.error(conduit, message);
        return HandlerSupport.redirect(
            CmsRoutes.list(HohenheimSlugs.ADMIN, HohenheimSlugs.INSTANCE_TEMPLATES_IMPORT));
    }
}
