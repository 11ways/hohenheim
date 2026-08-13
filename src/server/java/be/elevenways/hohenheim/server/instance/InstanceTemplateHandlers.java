package be.elevenways.hohenheim.server.instance;

import be.elevenways.hohenheim.HohenheimEndpoints;
import be.elevenways.zenit.common.routing.RouteTarget;
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
import be.elevenways.zenit.server.http.HttpConduit;
import be.elevenways.zenit.server.http.RedirectResult;
import be.elevenways.zenit.server.http.body.FormSubmissionRawValues;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * Handlers for the template endpoints beside the zenit-cms panel: the checksummed
 * export download, the paste import, and the create-from-template submit (whose
 * variable values run the TYPED coercion/validation pipeline).
 */
public final class InstanceTemplateHandlers {

    /** Background installs run here; the durable install_state is the progress record. */
    private static final JobRunner INSTALL_RUNNER = JobRunner.create("hh-template-install");

    /** Template administration is an operator lane; only the create redirect is panel-relative. */
    private static final String ADMIN = "admin";

    private static final String TEMPLATES_SLUG = "instance-templates";

    private InstanceTemplateHandlers() {
    }

    public static void init() {
        HohenheimEndpoints.INSTANCE_TEMPLATES_EXPORT.setHandler(conduit -> {
            Integer templateId = conduit.getParameter(HohenheimEndpoints.TEMPLATE_ID);
            Row template = Models.get(InstanceTemplateModel.class).findById(templateId);
            if (template == null) {
                return redirect(CmsRoutes.list(ADMIN, TEMPLATES_SLUG));
            }
            String document = new TemplatePortability().export(template);
            String name = String.valueOf((Object) template.get(InstanceTemplateModel.NAME));
            download(conduit, "application/json", name + ".template.json",
                document.getBytes(StandardCharsets.UTF_8));
            return null;
        });

        HohenheimEndpoints.INSTANCE_TEMPLATES_IMPORT.setHandler(conduit -> {
            Map<String, Object> form = FormSubmissionRawValues.fromConduit(conduit);
            String catalogApp = submittedString(form, "catalog_app");
            if (!catalogApp.isEmpty()) {
                // The vendored community-scripts catalog: pinned content is copied
                // into a NEW unapproved row; the endpoint's admin permission is what
                // keeps script introduction an operator act.
                try {
                    int templateId = CommunityScripts.importApp(catalogApp);
                    ActivityLog.record(Models.get(InstanceTemplateModel.class), templateId,
                        "imported", "vendored catalog: " + catalogApp);
                    return redirect(CmsRoutes.detail(ADMIN, TEMPLATES_SLUG, templateId));
                } catch (Violations violations) {
                    return importErrorText(conduit, firstMessage(violations));
                }
            }
            String document = submittedString(form, "document");
            String source = submittedString(form, "source");
            if (document.isEmpty()) {
                return importError(conduit, "document_required");
            }
            try {
                int templateId = new TemplatePortability().importDocument(document, source);
                ActivityLog.record(Models.get(InstanceTemplateModel.class), templateId,
                    "imported", source.isEmpty() ? "paste" : source);
                return redirect(CmsRoutes.detail(ADMIN, TEMPLATES_SLUG, templateId));
            } catch (Violations violations) {
                return importErrorText(conduit, firstMessage(violations));
            }
        });

        HohenheimEndpoints.INSTANCES_FROM_TEMPLATE.setHandler(conduit -> {
            Map<String, Object> form = FormSubmissionRawValues.fromConduit(conduit);
            AccessContext ctx = RecordSourceGate.accessContextOf(conduit);
            // Panel-relative, because this ONE endpoint now serves /admin and /manage:
            // a tenant refused (or redirected) into /admin would only meet a 403.
            String panel = HohenheimAccess.isAdmin(ctx) ? ADMIN : "manage";
            Row template = InstanceTemplates.templateFrom(form);
            if (template == null) {
                return redirect(CmsRoutes.list(panel, TEMPLATES_SLUG));
            }

            String name = InstanceTemplates.submittedString(form, "name");
            // The submitted host is passed on unchanged and INTENTIONALLY unvalidated
            // here: InstancePlacement honours it for an admin and ignores it for
            // everyone else, so this handler has no host decision to make.
            Integer serverId = InstanceTemplates.submittedInteger(form, "server_id");

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
                return redirect(CmsRoutes.detail(panel, "instances", instanceId));
            } catch (Violations violations) {
                // Typed refusal: re-render the form with the operator's raw values and
                // the per-field violations -- the standard form contract, not a toast.
                return untyped(InstanceFromTemplatePage.renderResult(
                    conduit, ctx, template, form, violations));
            }
        });
    }

    // -- plumbing -------------------------------------------------------------

    /** The first violation's own message, so a refusal keeps its localized text. */
    private static Microcopy firstMessage(Violations violations) {
        var all = violations.all();
        return all.isEmpty()
            ? Microcopy.of("refused").withFilter("scope", "violations")
            : all.get(0).message();
    }

    private static ActionResult<Object> importError(Conduit conduit, String key) {
        return importErrorText(conduit,
            Microcopy.of(key).withFilter("scope", "violations"));
    }

    private static ActionResult<Object> importErrorText(Conduit conduit, Microcopy message) {
        HohenheimFlash.error(conduit, message);
        return redirect(CmsRoutes.list(ADMIN, "instance-templates-import"));
    }

    private static String submittedString(Map<String, Object> values, String name) {
        Object value = values.get(name);
        if (value instanceof List<?> list) {
            value = list.isEmpty() ? null : list.get(0);
        }
        return value == null ? "" : String.valueOf(value).trim();
    }

    private static void download(Conduit conduit, String contentType, String filename, byte[] body) {
        if (conduit instanceof HttpConduit http) {
            String safeName = filename.replaceAll("[^a-zA-Z0-9._-]", "_");
            http.setResponseHeader("Content-Type", contentType);
            http.setResponseHeader("Content-Disposition", "attachment; filename=\"" + safeName + "\"");
        }
        conduit.endWithBytes(contentType, body);
    }

    @SuppressWarnings("unchecked")
    /** THE redirect of this class: the URL comes from a typed route target. */
    private static ActionResult<Object> redirect(@NonNull RouteTarget target) {
        return (ActionResult<Object>) (ActionResult<?>) new RedirectResult(target.toUrl());
    }

    @SuppressWarnings("unchecked")
    private static ActionResult<Object> untyped(@NonNull ActionResult<?> result) {
        return (ActionResult<Object>) result;
    }
}
