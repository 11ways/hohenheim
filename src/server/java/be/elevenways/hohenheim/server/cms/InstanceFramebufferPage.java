package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.HohenheimEndpoints;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.hohenheim.server.instance.IncusVmKind;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.cms.common.resource.RecordScopedPage;
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
public final class InstanceFramebufferPage implements RecordScopedPage<Row> {

    public static final String SLUG = "framebuffer";

    @Override public @NonNull Identifier id() { return Identifier.of("hohenheim", "instance_framebuffer"); }
    @Override public @NonNull Microcopy label() { return Microcopy.of("framebuffer").withFilter("scope", "instance"); }
    @Override public @NonNull String slug() { return SLUG; }
    @Override public @NonNull Icon icon() { return Icon.of("display"); }

    /**
     * VM instances only (a container has no framebuffer), AND only for a principal
     * holding CONSOLE on THIS record -- the same verb the socket's handshake demands
     * (VmFramebufferHandler), asked here so the tab stops being offered to a view-only
     * delegate whose every connect the socket could only 1008. The
     * {@link InstanceConsolePage} shape; zenit-cms 404s an unoffered slug, so this
     * gates the route as well as the nav.
     */
    @Override
    public boolean visibleFor(@NonNull Row record, @NonNull AccessContext accessContext) {
        return IncusVmKind.ID.toString().equals(record.get(InstanceModel.KIND))
            && HohenheimAccess.hasInstanceCapability(accessContext,
                record.get(InstanceModel.ID), HohenheimAccess.CONSOLE);
    }

    @Override
    public @NonNull ActionResult<?> render(@NonNull Conduit conduit,
                                           @NonNull AccessContext accessContext,
                                           @NonNull Row instance) {
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
        vars.put("recordTabs", recordTabs(conduit));
        return new RenderTemplateResult(
            Identifier.of("hohenheim", "cms/instance-framebuffer"), vars);
    }
}
