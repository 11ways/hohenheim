package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.InstanceTemplateModel;
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
import be.elevenways.zenit.server.http.ReturnTarget;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.util.HashMap;
import java.util.Map;

/**
 * Console tab on an instance: the live output terminal (fed by the instance-console
 * WebSocket) and the command form. Marked {@link TerminalCspPage} so ghostty's wasm
 * boot gets the widened admin CSP on exactly this route.
 */
public final class InstanceConsolePage implements RecordScopedPage<Row>, TerminalCspPage {

    public static final String SLUG = "console";

    @Override public @NonNull Identifier id() { return Identifier.of("hohenheim", "instance_console"); }
    @Override public @NonNull Microcopy label() { return Microcopy.of("console").withFilter("scope", "instance"); }
    @Override public @NonNull String slug() { return SLUG; }
    @Override public @NonNull Icon icon() { return Icon.of("terminal"); }

    @Override
    public @NonNull ActionResult<?> render(@NonNull Conduit conduit,
                                           @NonNull AccessContext accessContext,
                                           @NonNull Row instance) {
        Integer instanceId = instance.get(InstanceModel.ID);
        String status = instance.get(InstanceModel.STATUS);

        Object templateId = instance.get(InstanceModel.TEMPLATE_ID);
        Row template = templateId instanceof Integer id
            ? Models.get(InstanceTemplateModel.class).findById(id) : null;
        String stopCommand = template != null
            ? template.get(InstanceTemplateModel.STOP_COMMAND) : null;

        String error = conduit.getQueryParam("error");
        Map<String, Object> vars = new HashMap<>();
        vars.put("title", instance.get(InstanceModel.NAME));
        vars.put("instanceName", instance.get(InstanceModel.NAME));
        vars.put("instanceId", instanceId);
        vars.put("status", status == null ? InstanceModel.STATUS_CREATED : status);
        vars.put("running", InstanceModel.STATUS_RUNNING.equals(status)
            || InstanceModel.STATUS_STARTING.equals(status));
        vars.put("stopCommand", stopCommand == null ? "" : stopCommand);
        vars.put("commandError", error == null ? "" : error);
        vars.put("returnUrl", ReturnTarget.capture(conduit));
        vars.put("recordTabs", recordTabs(conduit));
        return new RenderTemplateResult(Identifier.of("hohenheim", "cms/instance-console"), vars);
    }
}
