package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.model.InstanceFileModel;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.InstanceTemplateModel;
import be.elevenways.hohenheim.model.InstanceVariableModel;
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
 * Provisioning tab on an instance: its template, install state, variables (SECRET
 * VALUES NEVER RENDERED -- key and kind only) and config files.
 */
public final class InstanceProvisioningPage implements RecordScopedPage<Row> {

    @Override public @NonNull Identifier id() { return Identifier.of("hohenheim", "instance_provisioning"); }
    @Override public @NonNull Microcopy label() { return Microcopy.of("provisioning").withFilter("scope", "instance"); }
    @Override public @NonNull String slug() { return "provisioning"; }
    @Override public @NonNull Icon icon() { return Icon.of("wand-magic-sparkles"); }

    @Override
    public @NonNull ActionResult<?> render(@NonNull Conduit conduit,
                                           @NonNull AccessContext accessContext,
                                           @NonNull Row instance) {
        Integer instanceId = instance.get(InstanceModel.ID);
        String basePath = CmsSupport.panelBase(conduit);

        Object templateId = instance.get(InstanceModel.TEMPLATE_ID);
        Row template = templateId instanceof Integer id
            ? Models.get(InstanceTemplateModel.class).findById(id) : null;

        List<Map<String, Object>> variables = new ArrayList<>();
        for (Row variable : Models.get(InstanceVariableModel.class).findByInstanceId(instanceId)) {
            boolean secret = InstanceVariableModel.KIND_SECRET
                .equals(variable.get(InstanceVariableModel.KIND));
            Map<String, Object> entry = new HashMap<>();
            entry.put("key", variable.get(InstanceVariableModel.KEY));
            entry.put("secret", secret);
            // The one rendering rule of this page: a secret VALUE never reaches the
            // template vars, masked or otherwise -- only the fact that one exists.
            entry.put("value", secret ? "" :
                String.valueOf((Object) variable.get(InstanceVariableModel.PLAIN_VALUE)));
            variables.add(entry);
        }

        List<Map<String, Object>> files = new ArrayList<>();
        for (Row file : Models.get(InstanceFileModel.class).findByInstanceId(instanceId)) {
            Map<String, Object> entry = new HashMap<>();
            entry.put("path", file.get(InstanceFileModel.CONTAINER_PATH));
            entry.put("mode", file.get(InstanceFileModel.MODE));
            entry.put("editUrl", basePath + "/instance-files/" + file.get(InstanceFileModel.ID));
            files.add(entry);
        }

        String installState = instance.get(InstanceModel.INSTALL_STATE);
        Map<String, Object> vars = new HashMap<>();
        vars.put("title", instance.get(InstanceModel.NAME));
        vars.put("instanceName", instance.get(InstanceModel.NAME));
        vars.put("templateName", template != null
            ? String.valueOf((Object) template.get(InstanceTemplateModel.NAME)) : "");
        vars.put("installState", installState == null ? InstanceModel.INSTALL_NONE : installState);
        vars.put("installStateLabel", Microcopy.of(
                installState == null ? InstanceModel.INSTALL_NONE : installState)
            .withFilter("scope", "install_state"));
        String installError = instance.get(InstanceModel.INSTALL_ERROR);
        vars.put("installError", installError == null ? "" : installError);
        vars.put("variables", variables);
        vars.put("files", files);
        vars.put("addFileUrl", basePath + "/instance-files/new?instance_id=" + instanceId);
        vars.put("recordTabs", recordTabs(conduit));
        return new RenderTemplateResult(Identifier.of("hohenheim", "cms/instance-provisioning"), vars);
    }
}
