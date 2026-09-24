package be.elevenways.hohenheim.server.instance;

import be.elevenways.hohenheim.HohenheimEndpoints;
import be.elevenways.hohenheim.HohenheimSlugs;
import be.elevenways.hohenheim.server.HandlerSupport;
import be.elevenways.zenit.cms.common.page.CmsRoutes;
import be.elevenways.hohenheim.server.cms.HohenheimFlash;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.hohenheim.model.InstanceTemplateModel;
import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.hohenheim.server.cms.InstanceFromTemplatePage;
import be.elevenways.protoblast.common.Blast;
import be.elevenways.protoblast.common.thread.JobRunner;
import be.elevenways.zenit.common.conduit.Conduit;
import be.elevenways.zenit.common.orm.activity.ActivityLog;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.result.ActionResult;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.common.validation.Violations;
import be.elevenways.zenit.server.data.RecordSourceGate;
import be.elevenways.zenit.server.http.body.FormSubmissionRawValues;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Handlers for the template endpoints beside the zenit-cms panel: the checksummed
 * export download, the paste import, and the create-from-template submit (whose
 * variable values run the TYPED coercion/validation pipeline).
 */
public final class InstanceTemplateHandlers {

    /** Background installs run here; the durable install_state is the progress record. */
    private static final JobRunner INSTALL_RUNNER = JobRunner.create("hh-template-install");

    private InstanceTemplateHandlers() {
    }

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
                        "imported", "vendored catalog: " + catalogApp);
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
                    "imported", source.isEmpty() ? "paste" : source);
                return HandlerSupport.redirect(
                    CmsRoutes.detail(HohenheimSlugs.ADMIN, HohenheimSlugs.INSTANCE_TEMPLATES, templateId));
            } catch (Violations violations) {
                return importErrorText(conduit, HandlerSupport.violationMessage(violations));
            }
        });

        HohenheimEndpoints.INSTANCES_FROM_TEMPLATE.setHandler(conduit -> {
            Map<String, Object> form = FormSubmissionRawValues.fromConduit(conduit);
            AccessContext ctx = RecordSourceGate.accessContextOf(conduit);
            // Panel-relative, because this ONE endpoint now serves /admin and /manage:
            // a tenant refused (or redirected) into /admin would only meet a 403.
            String panel = HohenheimAccess.isAdmin(ctx) ? HohenheimSlugs.ADMIN : HohenheimSlugs.MANAGE;
            Row template = InstanceTemplates.templateFrom(form);
            if (template == null) {
                return HandlerSupport.redirect(CmsRoutes.list(panel, HohenheimSlugs.INSTANCE_TEMPLATES));
            }
            // The GET page's own gate, asked BEFORE anything renders: the refusal below
            // re-renders the form, and that form carries the template's name, description,
            // variable keys, labels and defaults. A template this actor may not select is
            // answered exactly like one that does not exist, so a guessed id learns nothing.
            try {
                InstanceTemplates.requireSelectable(template, ctx);
            } catch (Violations notSelectable) {
                return HandlerSupport.redirect(CmsRoutes.list(panel, HohenheimSlugs.INSTANCE_TEMPLATES));
            }

            String name = HandlerSupport.submittedString(form, "name");
            // The submitted host is passed on unchanged and INTENTIONALLY unvalidated
            // here: InstancePlacement honours it for an admin and ignores it for
            // everyone else, so this handler has no host decision to make.
            Integer serverId = HandlerSupport.submittedInteger(form, "server_id");

            try {
                int instanceId = new InstanceTemplates()
                    .createFromTemplate(template, name, serverId, form, ctx);
                // The install step runs in the background: the durable install_state
                // (pending -> installing -> installed/failed) IS the progress record,
                // and deploy refuses until it completes.
                if (InstanceTemplates.hasInstallStep(template)) {
                    INSTALL_RUNNER.startVirtualThread(() -> {
                        try {
                            new InstanceInstalls().install(instanceId);
                        } catch (RuntimeException error) {
                            Blast.log("INSTANCE: background install for", instanceId,
                                "failed:", error.getMessage());
                        }
                    });
                }
                return HandlerSupport.redirect(CmsRoutes.detail(panel, HohenheimSlugs.INSTANCES, instanceId));
            } catch (Violations violations) {
                // Typed refusal: re-render the form with the operator's raw values and
                // the per-field violations -- the standard form contract, not a toast.
                return untyped(InstanceFromTemplatePage.renderResult(
                    conduit, ctx, template, form, violations));
            }
        });
    }

    // -- plumbing -------------------------------------------------------------

    private static ActionResult<Object> importError(Conduit conduit, String key) {
        return importErrorText(conduit,
            Microcopy.of(key).withFilter("scope", "violations"));
    }

    private static ActionResult<Object> importErrorText(Conduit conduit, Microcopy message) {
        HohenheimFlash.error(conduit, message);
        return HandlerSupport.redirect(
            CmsRoutes.list(HohenheimSlugs.ADMIN, HohenheimSlugs.INSTANCE_TEMPLATES_IMPORT));
    }

    @SuppressWarnings("unchecked")
    private static ActionResult<Object> untyped(@NonNull ActionResult<?> result) {
        return (ActionResult<Object>) result;
    }
}
