package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.model.InstanceTemplateFileModel;
import be.elevenways.hohenheim.model.InstanceTemplateModel;
import be.elevenways.hohenheim.model.InstanceTemplateVariableModel;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.cms.common.resource.RecordScopedPage;
import be.elevenways.zenit.common.conduit.Conduit;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.result.ActionResult;
import be.elevenways.zenit.common.result.RenderTemplateResult;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.common.ui.Icon;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Contents tab on a template: its typed variables and config files, with links into
 * the (nav-hidden) variable and file resource forms.
 */
public final class TemplateContentsPage implements RecordScopedPage<Row> {

    @Override public @NonNull Identifier id() { return Identifier.of("hohenheim", "template_contents"); }
    @Override public @NonNull Microcopy label() { return Microcopy.of("contents").withFilter("scope", "instance_template"); }
    @Override public @NonNull String slug() { return "contents"; }
    @Override public @NonNull Icon icon() { return Icon.of("list-check"); }

    @Override
    public @NonNull ActionResult<?> render(@NonNull Conduit conduit,
                                           @NonNull AccessContext accessContext,
                                           @NonNull Row template) {
        Integer templateId = template.get(InstanceTemplateModel.ID);
        String basePath = CmsSupport.panelBase(conduit);

        List<Map<String, Object>> variables = new ArrayList<>();
        for (Row variable : Models.get(InstanceTemplateVariableModel.class)
                .findByTemplateId(templateId)) {
            Map<String, Object> entry = new HashMap<>();
            entry.put("key", variable.get(InstanceTemplateVariableModel.KEY));
            entry.put("label", variable.get(InstanceTemplateVariableModel.LABEL));
            entry.put("type", String.valueOf(variable.get(InstanceTemplateVariableModel.TYPE)));
            entry.put("required", Boolean.TRUE.equals(
                variable.get(InstanceTemplateVariableModel.REQUIRED)));
            entry.put("defaultValue", variable.get(InstanceTemplateVariableModel.DEFAULT_VALUE));
            entry.put("editUrl", basePath + "/instance-template-variables/"
                + variable.get(InstanceTemplateVariableModel.ID));
            variables.add(entry);
        }

        List<Map<String, Object>> files = new ArrayList<>();
        for (Row file : Models.get(InstanceTemplateFileModel.class).findByTemplateId(templateId)) {
            Map<String, Object> entry = new HashMap<>();
            entry.put("path", file.get(InstanceTemplateFileModel.CONTAINER_PATH));
            entry.put("mode", file.get(InstanceTemplateFileModel.MODE));
            entry.put("editUrl", basePath + "/instance-template-files/"
                + file.get(InstanceTemplateFileModel.ID));
            files.add(entry);
        }

        Map<String, Object> vars = new HashMap<>();
        vars.put("title", template.get(InstanceTemplateModel.NAME));
        vars.put("templateName", template.get(InstanceTemplateModel.NAME));
        vars.put("variables", variables);
        vars.put("files", files);
        vars.put("addVariableUrl",
            basePath + "/instance-template-variables/new?template_id=" + templateId);
        vars.put("addFileUrl",
            basePath + "/instance-template-files/new?template_id=" + templateId);
        vars.put("recordTabs", recordTabs(conduit));
        return new RenderTemplateResult(Identifier.of("hohenheim", "cms/template-contents"), vars);
    }
}
