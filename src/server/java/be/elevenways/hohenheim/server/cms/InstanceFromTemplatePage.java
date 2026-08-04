package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.HohenheimEndpoints;
import be.elevenways.hohenheim.model.InstanceTemplateModel;
import be.elevenways.hohenheim.model.ProjectModel;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.hohenheim.server.instance.InstanceTemplates;
import be.elevenways.hohenheim.server.project.Projects;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.cms.common.resource.PanelPage;
import be.elevenways.zenit.common.conduit.Conduit;
import be.elevenways.zenit.common.edit.EditView;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.result.ActionResult;
import be.elevenways.zenit.common.result.RenderTemplateResult;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.common.ui.Icon;
import be.elevenways.zenit.server.http.RedirectResult;
import be.elevenways.zenit.common.validation.Violations;
import be.elevenways.zenit.forms.server.render.FormStateTranslator;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * "Create instance from template": name + host + the template's typed variables
 * rendered as an ordinary zenit form. Reached from the template list's row action;
 * the POST is the host-declared INSTANCES_FROM_TEMPLATE endpoint.
 */
public final class InstanceFromTemplatePage extends PanelPage {

    @Override public @NonNull Identifier id() { return Identifier.of("hohenheim", "instances_from_template"); }
    @Override public @NonNull Microcopy label() { return Microcopy.of("create_instance").withFilter("scope", "instance_template"); }
    @Override public @NonNull String slug() { return "instances-from-template"; }
    @Override public @NonNull Icon icon() { return Icon.of("plus"); }
    @Override public boolean showInNav() { return false; }

    @Override
    public @NonNull ActionResult<?> render(@NonNull Conduit conduit,
                                           @NonNull AccessContext accessContext) {
        Row template = templateFromQuery(conduit);
        if (template == null) {
            return new RedirectResult(CmsSupport.panelBase(conduit) + "/instance-templates");
        }
        // The approval gate, ASKED AT RENDER as well as at submit: a tenant following a
        // guessed ?template= id must not be shown a form whose submit can only refuse.
        // The submit half (InstanceTemplates.createFromTemplate) remains the gate.
        try {
            InstanceTemplates.requireSelectable(template, accessContext);
        } catch (Violations unapproved) {
            return new RedirectResult(CmsSupport.panelBase(conduit) + "/instance-templates");
        }
        return renderResult(conduit, accessContext, template, Map.of(), null);
    }

    /**
     * The one renderer both the GET page and the POST handler's violation re-render
     * use, so a refused submit keeps the operator's raw values and shows per-field
     * violations.
     */
    public static @NonNull ActionResult<?> renderResult(@NonNull Conduit conduit,
                                                        @NonNull AccessContext accessContext,
                                                        @NonNull Row template,
                                                        @NonNull Map<String, Object> rawValues,
                                                        @Nullable Violations violations) {
        InstanceTemplates templates = new InstanceTemplates();
        int templateId = template.get(InstanceTemplateModel.ID);

        Map<String, Object> values = new LinkedHashMap<>(templates.variableDefaults(templateId));
        rawValues.forEach((key, value) -> {
            if (value != null) {
                values.put(key, value);
            }
        });

        // The host pick is an OPERATOR control and is not rendered for anyone else.
        // InstancePlacement ignores a submitted server_id for a non-admin anyway, so
        // rendering a select whose value is discarded would be a control that lies about
        // what it does -- and a tenant naming a host is the thing placement exists to
        // prevent.
        boolean chooseHost = HohenheimAccess.isAdmin(accessContext);
        List<Map<String, Object>> servers = new ArrayList<>();
        if (chooseHost) {
            for (Row server : Models.get(ServerModel.class).find().all()) {
                Map<String, Object> entry = new HashMap<>();
                entry.put("id", server.get(ServerModel.ID));
                entry.put("name", String.valueOf((Object) server.get(ServerModel.NAME)));
                servers.add(entry);
            }
        }

        // The project pick: every project for an admin, the actor's member projects
        // otherwise. Empty list = control absent (a select with nothing to say).
        List<Map<String, Object>> projects = new ArrayList<>();
        for (Row project : selectableProjects(accessContext)) {
            Map<String, Object> entry = new HashMap<>();
            entry.put("id", project.get(ProjectModel.ID));
            entry.put("name", String.valueOf((Object) project.get(ProjectModel.NAME)));
            projects.add(entry);
        }

        String panelBase = CmsSupport.panelBase(conduit);
        Map<String, Object> vars = new HashMap<>();
        vars.put("chooseHost", chooseHost);
        vars.put("projects", projects);
        vars.put("chooseProject", !projects.isEmpty());
        vars.put("projectId", rawValues.get("project_id") != null
            ? String.valueOf(rawValues.get("project_id")) : "");
        vars.put("formAction", HohenheimEndpoints.INSTANCES_FROM_TEMPLATE.toUrl());
        vars.put("cancelUrl", panelBase + "/instance-templates");
        vars.put("title", Microcopy.of("create_instance").withFilter("scope", "instance_template")
            .resolve(conduit.getLocales(), conduit.getMessageResolver()));
        vars.put("templateId", templateId);
        vars.put("templateName", String.valueOf((Object) template.get(InstanceTemplateModel.NAME)));
        vars.put("templateDescription", nullSafe(template.get(InstanceTemplateModel.DESCRIPTION)));
        vars.put("approved", template.get(InstanceTemplateModel.APPROVED_AT) != null);
        vars.put("hasInstall", InstanceTemplates.hasInstallStep(template));
        vars.put("instanceName", nullSafe(rawValues.get("name")));
        vars.put("serverId", rawValues.get("server_id") != null
            ? String.valueOf(rawValues.get("server_id"))
            : String.valueOf(ServerModel.localServerId()));
        vars.put("servers", servers);
        vars.put("formError", violations != null ? formLevelText(conduit, violations) : "");
        vars.put("variableForm", new FormStateTranslator().translate(
            templates.variableFormSpec(templateId), Map.of(), EditView.CREATE, accessContext,
            values, violations != null ? violations.all() : List.of(), null));
        return new RenderTemplateResult(Identifier.of("hohenheim", "cms/instance-from-template"), vars);
    }

    /** The projects the actor may create into: all for admins, memberships otherwise. */
    private static @NonNull List<Row> selectableProjects(@NonNull AccessContext ctx) {
        if (HohenheimAccess.isAdmin(ctx)) {
            return Models.get(ProjectModel.class).find().all();
        }
        Long principalId = ctx.principalId();
        if (principalId == null || ctx.isAnonymous()) {
            return List.of();
        }
        return Projects.projectsOf(principalId.intValue());
    }

    /** The ?template= row, or null when absent/malformed/missing. */
    public static @Nullable Row templateFromQuery(@NonNull Conduit conduit) {
        String raw = conduit.getQueryParam("template");
        if (raw == null || raw.isEmpty()) {
            return null;
        }
        try {
            return Models.get(InstanceTemplateModel.class).findById(Integer.parseInt(raw));
        } catch (NumberFormatException malformed) {
            return null;
        }
    }

    private static @NonNull String formLevelText(@NonNull Conduit conduit,
                                                 @NonNull Violations violations) {
        StringBuilder text = new StringBuilder();
        for (var violation : violations.all()) {
            // Form-level violations are spelled ("form", "") by Violations.ofForm.
            if ("form".equals(violation.fieldName())) {
                if (text.length() > 0) {
                    text.append(" ");
                }
                text.append(violation.message()
                    .resolve(conduit.getLocales(), conduit.getMessageResolver()));
            }
        }
        return text.toString();
    }

    private static @NonNull String nullSafe(@Nullable Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}
