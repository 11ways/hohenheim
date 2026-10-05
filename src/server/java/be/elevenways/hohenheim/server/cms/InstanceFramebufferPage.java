package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.instance.InstanceOperations;
import be.elevenways.hohenheim.server.instance.InstanceOperationHandlers;
import be.elevenways.hohenheim.HohenheimEndpoints;
import be.elevenways.hohenheim.HohenheimIds;
import be.elevenways.hohenheim.HohenheimTemplateIds;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.cms.common.panel.PanelRequest;
import be.elevenways.zenit.common.conduit.Conduit;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.result.ActionResult;
import be.elevenways.zenit.common.result.RenderTemplateResult;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.common.ui.Icon;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.util.HashMap;
import java.util.Map;

/**
 * Framebuffer console tab: a live VGA rescue console for VM instances only (the tab
 * hides and 404s on a container). The viewer streams server-captured VGA snapshots and
 * carries keyboard input over the {@code vm-framebuffer} WebSocket. No widened CSP is
 * needed -- the pl-framebuffer viewer is pure canvas (no wasm), and same-origin
 * WebSockets already ride the default admin {@code connect-src 'self'}.
 */
public final class InstanceFramebufferPage implements ConsoleModes.Mode {

    public static final String SLUG = "framebuffer";

    private final @NonNull ConsoleModes modes;

    InstanceFramebufferPage(@NonNull ConsoleModes modes) {
        this.modes = modes;
    }

    @Override public @NonNull Identifier id() { return HohenheimIds.id("instance_framebuffer"); }
    @Override public @NonNull Microcopy label() { return Microcopy.of("framebuffer").withFilter("scope", "instance"); }
    @Override public @NonNull String slug() { return SLUG; }
    @Override public @NonNull Icon icon() { return Icon.of("display"); }

    /** A mode of the Console tab, reached through its mode switch. */
    @Override public boolean inTabs() { return false; }

    @Override
    public @NonNull Microcopy hint() {
        return Microcopy.of("screen").withFilter("scope", "console_mode");
    }

    /**
     * Where the open-framebuffer operation is offered: an authored VM (a container has no
     * framebuffer), for a principal holding CONSOLE on THIS record -- the offer the socket's
     * handshake admits through (VmFramebufferHandler), asked here so the tab stops being
     * offered to a viewer whose every connect the socket could only 1008. The
     * {@link InstanceConsolePage} shape; zenit-cms 404s an unoffered slug, so this
     * gates the route as well as the nav.
     */
    @Override
    public boolean offers(@NonNull Row record, @NonNull AccessContext accessContext) {
        return InstanceOperationHandlers.offered(InstanceOperations.OPEN_FRAMEBUFFER, accessContext, record);
    }

    @Override
    public @NonNull ActionResult<?> render(@NonNull PanelRequest request, @NonNull Row instance) {
        Conduit conduit = request.conduit();
        Integer instanceId = instance.get(InstanceModel.ID);
        String status = instance.get(InstanceModel.STATUS);

        Map<String, Object> vars = new HashMap<>();
        vars.put("title", instance.get(InstanceModel.NAME));
        vars.put("instanceName", instance.get(InstanceModel.NAME));
        vars.put("instanceId", instanceId);
        vars.put("running", InstanceModel.STATUS_RUNNING.equals(status)
            || InstanceModel.STATUS_STARTING.equals(status));
        // AIDEV-NOTE: WebSocketEndpoint is not a RouteTarget and has no with(...), and the
        // framebuffer element takes a wsUrl STRING anyway, so the socket route is RENDERED
        // from its own declaration -- never concatenated.
        vars.put("framebufferWsUrl", HohenheimEndpoints.VM_FRAMEBUFFER.toUrl(
            Map.of(HohenheimEndpoints.INSTANCE_ID, instanceId)));
        vars.put("recordTabs", this.modes.strip(request, instance, recordTabs(conduit)));
        vars.put("consoleModes", this.modes.views(request, instance, SLUG));
        return new RenderTemplateResult(
            HohenheimTemplateIds.INSTANCE_FRAMEBUFFER, vars);
    }

}
