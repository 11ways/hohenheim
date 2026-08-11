package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.model.StackFileModel;
import be.elevenways.hohenheim.model.StackModel;
import be.elevenways.hohenheim.model.StackServiceModel;
import be.elevenways.hohenheim.server.stack.StackRuntime;
import be.elevenways.hohenheim.HohenheimParams;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.cms.common.page.CmsEndpoints;
import be.elevenways.zenit.cms.common.page.CmsRoutes;
import be.elevenways.zenit.cms.common.resource.RecordScopedPage;
import be.elevenways.zenit.common.conduit.Conduit;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.result.ActionResult;
import be.elevenways.zenit.common.result.RenderTemplateResult;
import be.elevenways.zenit.common.routing.RouteTarget;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.common.ui.Icon;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Services tab on a stack: every service with its live container state, its
 * config files, and links into the (nav-hidden) service and file resource forms.
 */
public final class StackServicesPage implements RecordScopedPage<Row> {

    @Override public @NonNull Identifier id() { return Identifier.of("hohenheim", "stack_services"); }
    @Override public @NonNull Microcopy label() { return Microcopy.of("services").withFilter("scope", "stack"); }
    @Override public @NonNull String slug() { return "services"; }
    @Override public @NonNull Icon icon() { return Icon.of("cubes"); }

    @Override
    public @NonNull ActionResult<?> render(@NonNull Conduit conduit,
                                           @NonNull AccessContext accessContext,
                                           @NonNull Row stack) {
        Integer stackId = stack.get(StackModel.ID);
        String panel = CmsSupport.panelSlug(conduit);

        Map<String, String> liveStates = StackRuntime.get().serviceStates(stackId);
        StackFileModel fileModel = Models.get(StackFileModel.class);

        List<Map<String, Object>> services = new ArrayList<>();
        for (Row service : Models.get(StackServiceModel.class).findByStackId(stackId)) {
            Map<String, Object> entry = new HashMap<>();
            String name = service.get(StackServiceModel.NAME);
            Integer serviceId = service.get(StackServiceModel.ID);
            entry.put("id", serviceId);
            entry.put("name", name);
            entry.put("image", service.get(StackServiceModel.IMAGE));
            entry.put("enabled", Boolean.TRUE.equals(service.get(StackServiceModel.ENABLED)));
            String state = liveStates.getOrDefault(name, "missing");
            entry.put("state", state);
            entry.put("stateLabel", stateLabel(state));
            entry.put("stateVariant", stateVariant(state));
            entry.put("editTarget", CmsRoutes.detail(panel, "stack-services", serviceId));

            StringBuilder ports = new StringBuilder();
            for (Row port : service.getRecords(StackServiceModel.PORTS)) {
                if (ports.length() > 0) {
                    ports.append(", ");
                }
                ports.append(port.get(StackServiceModel.PORT_HOST)).append(":")
                    .append(port.get(StackServiceModel.PORT_CONTAINER)).append("/")
                    .append(port.get(StackServiceModel.PORT_PROTOCOL));
            }
            entry.put("ports", ports.toString());

            List<Map<String, Object>> files = new ArrayList<>();
            for (Row file : fileModel.findByServiceId(serviceId)) {
                Map<String, Object> fileEntry = new HashMap<>();
                fileEntry.put("id", file.get(StackFileModel.ID));
                fileEntry.put("path", file.get(StackFileModel.CONTAINER_PATH));
                fileEntry.put("editTarget", CmsRoutes.detail(panel, "stack-files",
                    file.get(StackFileModel.ID)));
                files.add(fileEntry);
            }
            entry.put("files", files);
            // Create form + prefill query parameter: composed off CmsEndpoints, since
            // CmsRoutes.create returns the RouteTarget interface (no with(...)).
            entry.put("addFileTarget", CmsEndpoints.CREATE_FORM
                .with(CmsEndpoints.PANEL_PARAM, panel)
                .with(CmsEndpoints.RESOURCE_PARAM, "stack-files")
                .with(HohenheimParams.STACK_SERVICE_ID_PREFILL, serviceId));

            services.add(entry);
        }

        Map<String, Object> vars = new HashMap<>();
        vars.put("title", stack.get(StackModel.NAME));
        vars.put("stackId", stackId);
        vars.put("stackName", stack.get(StackModel.NAME));
        vars.put("services", services);
        vars.put("addServiceTarget", CmsEndpoints.CREATE_FORM
            .with(CmsEndpoints.PANEL_PARAM, panel)
            .with(CmsEndpoints.RESOURCE_PARAM, "stack-services")
            .with(HohenheimParams.STACK_ID_PREFILL, stackId));
        vars.put("recordTabs", recordTabs(conduit));
        return new RenderTemplateResult(Identifier.of("hohenheim", "cms/stack-services"), vars);
    }

    /** Container states are a closed vocabulary, so they localize as scoped microcopy. */
    static Microcopy stateLabel(String state) {
        return Microcopy.of(state).withFilter("scope", "stack_state");
    }

    private static String stateVariant(String state) {
        return switch (state) {
            case "healthy", "running" -> "success";
            case "starting" -> "warning";
            case "unhealthy" -> "destructive";
            case "stopped" -> "secondary";
            default -> "outline";   // missing / unknown
        };
    }
}
