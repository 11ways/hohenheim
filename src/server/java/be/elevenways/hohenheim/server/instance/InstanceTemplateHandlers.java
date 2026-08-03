package be.elevenways.hohenheim.server.instance;

import be.elevenways.hohenheim.HohenheimEndpoints;
import be.elevenways.hohenheim.model.InstanceTemplateModel;
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

import java.net.URLEncoder;
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

    private InstanceTemplateHandlers() {
    }

    public static void init() {
        HohenheimEndpoints.INSTANCE_TEMPLATES_EXPORT.setHandler(conduit -> {
            Integer templateId = conduit.getParameter(HohenheimEndpoints.TEMPLATE_ID);
            Row template = Models.get(InstanceTemplateModel.class).findById(templateId);
            if (template == null) {
                return redirect("/admin/instance-templates");
            }
            String document = new TemplatePortability().export(template);
            String name = String.valueOf((Object) template.get(InstanceTemplateModel.NAME));
            download(conduit, "application/json", name + ".template.json",
                document.getBytes(StandardCharsets.UTF_8));
            return null;
        });

        HohenheimEndpoints.INSTANCE_TEMPLATES_IMPORT.setHandler(conduit -> {
            Map<String, Object> form = FormSubmissionRawValues.fromConduit(conduit);
            String document = submittedString(form, "document");
            String source = submittedString(form, "source");
            if (document.isEmpty()) {
                return importError(conduit, "document_required");
            }
            try {
                int templateId = new TemplatePortability().importDocument(document, source);
                ActivityLog.record(Models.get(InstanceTemplateModel.class), templateId,
                    "imported", source.isEmpty() ? "paste" : source);
                return redirect("/admin/instance-templates/" + templateId);
            } catch (Violations violations) {
                return importErrorText(conduit, firstMessage(conduit, violations));
            }
        });

        HohenheimEndpoints.INSTANCES_FROM_TEMPLATE.setHandler(conduit -> {
            Map<String, Object> form = FormSubmissionRawValues.fromConduit(conduit);
            Object templateIdRaw = form.get("template_id");
            Row template = null;
            try {
                template = templateIdRaw != null ? Models.get(InstanceTemplateModel.class)
                    .findById(Integer.parseInt(submittedString(form, "template_id"))) : null;
            } catch (NumberFormatException malformed) {
                // falls through to the redirect below
            }
            if (template == null) {
                return redirect("/admin/instance-templates");
            }

            String name = submittedString(form, "name");
            Integer serverId = null;
            try {
                String raw = submittedString(form, "server_id");
                if (!raw.isEmpty()) {
                    serverId = Integer.parseInt(raw);
                }
            } catch (NumberFormatException malformed) {
                serverId = null;
            }

            AccessContext ctx = RecordSourceGate.accessContextOf(conduit);
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
                return redirect("/admin/instances/" + instanceId);
            } catch (Violations violations) {
                // Typed refusal: re-render the form with the operator's raw values and
                // the per-field violations -- the standard form contract, not a toast.
                return untyped(InstanceFromTemplatePage.renderResult(
                    conduit, ctx, template, form, violations));
            }
        });
    }

    // -- plumbing -------------------------------------------------------------

    private static String firstMessage(Conduit conduit, Violations violations) {
        var all = violations.all();
        if (all.isEmpty()) {
            return "";
        }
        return all.get(0).message().resolve(conduit.getLocales(), conduit.getMessageResolver());
    }

    private static ActionResult<Object> importError(Conduit conduit, String key) {
        return importErrorText(conduit,
            be.elevenways.protoblast.common.i18n.Microcopy.of(key)
                .withFilter("scope", "violations")
                .resolve(conduit.getLocales(), conduit.getMessageResolver()));
    }

    private static ActionResult<Object> importErrorText(Conduit conduit, String message) {
        return redirect("/admin/instance-templates-import?error="
            + URLEncoder.encode(message, StandardCharsets.UTF_8));
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
    private static ActionResult<Object> redirect(String url) {
        return (ActionResult<Object>) (ActionResult<?>) new RedirectResult(url);
    }

    @SuppressWarnings("unchecked")
    private static ActionResult<Object> untyped(@NonNull ActionResult<?> result) {
        return (ActionResult<Object>) result;
    }
}
