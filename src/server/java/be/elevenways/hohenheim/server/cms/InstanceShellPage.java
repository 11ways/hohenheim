package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.HohenheimEndpoints;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.hohenheim.server.instance.InstanceShell;
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
 * Shell tab on an instance: an interactive terminal inside the workload, as the workload's
 * own non-root uid. The admin CSP (zenit's STRICT_ADMIN) carries ghostty's wasm concessions
 * panel-wide, so this tab is reached by soft navigation like every other.
 *
 * <p>The page makes NO authorization decision beyond hiding itself: {@code InstanceShell}
 * asks the {@code shell} capability again on its own funnel, which is what the WebSocket
 * handshake reaches. Hide AND enforce -- zenit-cms 404s an unoffered slug, so
 * {@link #visibleFor} gates the route as well as the nav.</p>
 */
public final class InstanceShellPage implements RecordScopedPage<Row> {

    public static final String SLUG = "shell";

    @Override public @NonNull Identifier id() { return Identifier.of("hohenheim", "instance_shell"); }
    @Override public @NonNull Microcopy label() { return Microcopy.of("shell").withFilter("scope", "instance"); }
    @Override public @NonNull String slug() { return SLUG; }
    @Override public @NonNull Icon icon() { return Icon.of("terminal"); }

    /**
     * The tab exists only for a principal that may actually shell into THIS record.
     *
     * AIDEV-NOTE: the CAPABILITY only -- deliberately not the uid/runtime gate. A workload
     * that cannot carry a shell still renders the tab and states WHY inside it; hiding the
     * tab for that case would make "your operator has not granted this" and "this runtime
     * has no shell lane yet" the same silence, and the second one is a fact the holder of
     * the capability is entitled to read.
     */
    @Override
    public boolean visibleFor(@NonNull Row record, @NonNull AccessContext accessContext) {
        return HohenheimAccess.hasInstanceCapability(
            accessContext, record.get(InstanceModel.ID), HohenheimAccess.SHELL);
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
        vars.put("running", InstanceModel.STATUS_RUNNING.equals(status));
        vars.put("recordTabs", recordTabs(conduit));
        vars.put("maxSessions", InstanceShell.MAX_SESSIONS_PER_INSTANCE);
        vars.put("idleMinutes", (int) (InstanceShell.IDLE_TIMEOUT_MS / 60_000));
        // AIDEV-NOTE: WebSocketEndpoint is not a RouteTarget and has no with(...), and
        // pl-terminal takes a wsUrl STRING anyway, so the socket route is RENDERED from
        // its own declaration here -- endpoint-derived, never concatenated.
        vars.put("shellWsUrl", HohenheimEndpoints.INSTANCE_SHELL.toUrl(
            Map.of(HohenheimEndpoints.INSTANCE_ID, instanceId)));

        // A workload that cannot carry a shell is a NAMED state, not an error banner: an
        // Incus workload has no pseudo-terminal lane yet, and a root-running kind is
        // refused on purpose. Both are read HERE so the socket never has to say it.
        InstanceShell.Availability availability =
            new InstanceShell().availabilityOf(instanceId);
        vars.put("available", availability == InstanceShell.Availability.AVAILABLE);
        vars.put("unsupportedRuntime",
            availability == InstanceShell.Availability.NO_RUNTIME_LANE);
        vars.put("runsAsRoot", availability == InstanceShell.Availability.RUNS_AS_ROOT);

        return new RenderTemplateResult(Identifier.of("hohenheim", "cms/instance-shell"), vars);
    }
}
