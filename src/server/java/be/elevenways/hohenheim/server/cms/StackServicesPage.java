package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.HohenheimSlugs;
import be.elevenways.hohenheim.HohenheimIds;
import be.elevenways.hohenheim.HohenheimMicrocopy;
import be.elevenways.hohenheim.HohenheimTemplateIds;
import be.elevenways.hohenheim.model.StackFileModel;
import be.elevenways.hohenheim.model.StackModel;
import be.elevenways.hohenheim.model.StackServiceModel;
import be.elevenways.hohenheim.server.stack.StackRuntime;
import be.elevenways.hohenheim.HohenheimParams;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.cms.common.page.CmsEndpoints;
import be.elevenways.zenit.cms.common.page.CmsRoutes;
import be.elevenways.zenit.cms.common.panel.PanelRequest;
import be.elevenways.zenit.cms.common.resource.RecordTab;
import be.elevenways.zenit.common.conduit.Conduit;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.result.ActionResult;
import be.elevenways.zenit.common.result.RenderTemplateResult;
import be.elevenways.zenit.common.routing.RouteTarget;
import be.elevenways.zenit.common.ui.BadgeVariant;
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
public final class StackServicesPage implements RecordTab.Rendered<Row> {

    @Override public @NonNull Identifier id() { return HohenheimIds.id("stack_services"); }
    @Override public @NonNull Microcopy label() { return HohenheimMicrocopy.STACK.of("services"); }
    @Override public @NonNull String slug() { return HohenheimSlugs.Tab.SERVICES; }
    @Override public @NonNull Icon icon() { return Icon.of("cubes"); }

    @Override
    public @NonNull ActionResult<?> render(@NonNull PanelRequest request, @NonNull Row stack) {
        Conduit conduit = request.conduit();
        Integer stackId = stack.get(StackModel.ID);
        String panel = request.panelSlug();

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
            entry.put("stateLabel", HohenheimMicrocopy.STACK_STATE.of(state));
            entry.put("stateVariant", stateVariant(state));
            entry.put("editTarget", CmsRoutes.detail(panel, HohenheimSlugs.STACK_SERVICES, serviceId));

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
                fileEntry.put("editTarget", CmsRoutes.detail(panel, HohenheimSlugs.STACK_FILES,
                    file.get(StackFileModel.ID)));
                files.add(fileEntry);
            }
            entry.put("files", files);
            // Create form + prefill query parameter: composed off CmsEndpoints, since
            // CmsRoutes.create returns the RouteTarget interface (no with(...)).
            entry.put("addFileTarget", CmsEndpoints.CREATE_FORM
                .with(CmsEndpoints.PANEL_PARAM, panel)
                .with(CmsEndpoints.RESOURCE_PARAM, HohenheimSlugs.STACK_FILES)
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
            .with(CmsEndpoints.RESOURCE_PARAM, HohenheimSlugs.STACK_SERVICES)
            .with(HohenheimParams.STACK_ID_PREFILL, stackId));
        // The front door of a FAILED stack states the reason and links the row that
        // carries it: a status badge alone sent the operator hunting through tabs.
        String failure = StackFailures.reasonOf(stack);
        vars.put("failureReason", failure != null ? failure : "");
        vars.put("deploymentsTarget", failure != null
            ? CmsRoutes.subpage(panel, HohenheimSlugs.STACKS, stackId, HohenheimSlugs.Tab.DEPLOYMENTS) : null);
        vars.put("head", recordHead(conduit));
        return new RenderTemplateResult(HohenheimTemplateIds.STACK_SERVICES, vars);
    }

    /**
     * AIDEV-NOTE: this switch stays hand-written, unlike the sibling deployment-status
     * one which now reads the colour off its EnumField. These states are LIVE runtime
     * facts derived from the Docker daemon by {@code StackRuntime.serviceStates} plus the
     * synthetic "missing" -- there is no status COLUMN and no enum vocabulary to declare
     * a colour on, so there is no second list here to remove. The default arm is the
     * fail-closed half: an unrecognised daemon state renders neutral, never "success".
     */
    private static BadgeVariant stateVariant(String state) {
        return switch (state) {
            case "healthy", "running" -> BadgeVariant.SUCCESS;
            case "starting" -> BadgeVariant.WARNING;
            case "unhealthy" -> BadgeVariant.DESTRUCTIVE;
            case "stopped" -> BadgeVariant.SECONDARY;
            default -> BadgeVariant.OUTLINE;   // missing / unknown
        };
    }
}
