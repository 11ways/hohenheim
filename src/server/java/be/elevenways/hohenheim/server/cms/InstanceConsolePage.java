package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.HohenheimEndpoints;
import be.elevenways.hohenheim.HohenheimParams;
import be.elevenways.hohenheim.instance.ConsoleKind;
import be.elevenways.hohenheim.model.InstanceLogModel;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.InstanceTemplateModel;
import be.elevenways.hohenheim.server.auth.HohenheimAccess;
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
import be.elevenways.zenit.server.http.ReturnTarget;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Console tab on an instance: the live output terminal (fed by the instance-console
 * WebSocket) and the command form. The admin CSP (zenit's STRICT_ADMIN) carries ghostty's
 * wasm concessions panel-wide, so this tab is reached by soft navigation like every other.
 */
public final class InstanceConsolePage implements RecordScopedPage<Row> {

    public static final String SLUG = "console";

    @Override public @NonNull Identifier id() { return Identifier.of("hohenheim", "instance_console"); }
    @Override public @NonNull Microcopy label() { return Microcopy.of("console").withFilter("scope", "instance"); }
    @Override public @NonNull String slug() { return SLUG; }
    @Override public @NonNull Icon icon() { return Icon.of("terminal"); }

    /**
     * The tab exists only for a principal that may actually attach to THIS record's
     * console -- the per-record half of the hide-and-enforce pair (zenit-cms 404s an
     * unoffered slug, so this is a gate on the route as well as on the nav).
     *
     * AIDEV-NOTE: this shipped ungated, which made the page a wider door than the socket
     * it fronts: the live terminal's handshake demands CONSOLE (InstanceConsoleHandler)
     * and the command form's POST demands it too, but {@link #addStoredLogs} reads
     * InstanceLogModel directly and rendered every RETAINED console episode to anyone the
     * resource's view-only scope let through. Same output, same capability.
     */
    @Override
    public boolean visibleFor(@NonNull Row record, @NonNull AccessContext accessContext) {
        return HohenheimAccess.hasInstanceCapability(
            accessContext, record.get(InstanceModel.ID), HohenheimAccess.CONSOLE);
    }

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

        Map<String, Object> vars = new HashMap<>();
        this.addStoredLogs(conduit, vars, instanceId);
        vars.put("title", instance.get(InstanceModel.NAME));
        vars.put("instanceName", instance.get(InstanceModel.NAME));
        vars.put("instanceId", instanceId);
        vars.put("status", status == null ? InstanceModel.STATUS_CREATED : status);
        vars.put("running", InstanceModel.STATUS_RUNNING.equals(status)
            || InstanceModel.STATUS_STARTING.equals(status));
        vars.put("stopCommand", stopCommand == null ? "" : stopCommand);
        // The console's shape is the kind setting's fact (ConsoleKind, one home): an
        // interactive terminal takes keystrokes on the socket and has no command form.
        // An unknown token renders the plain shape; the deploy already refused it.
        Object settings = instance.get(InstanceModel.SETTINGS);
        ConsoleKind consoleKind = settings instanceof Map<?, ?> map
            ? ConsoleKind.declaredIn(castSettings(map)) : ConsoleKind.PLAIN;
        vars.put("interactive", consoleKind != null && consoleKind.interactive());
        vars.put("returnUrl", ReturnTarget.capture(conduit));
        // AIDEV-NOTE: the hidden field NAME comes from the framework constant --
        // ReturnTarget is server-only, so the common template cannot reach it.
        vars.put("returnParam", ReturnTarget.PARAM);
        vars.put("commandTarget", HohenheimEndpoints.INSTANCE_CONSOLE_COMMAND
            .with(HohenheimEndpoints.INSTANCE_ID, instanceId));
        // AIDEV-NOTE: WebSocketEndpoint is not a RouteTarget and has no with(...), and
        // pl-terminal takes a wsUrl STRING anyway, so the socket route is RENDERED from
        // its own declaration here -- endpoint-derived, never concatenated.
        vars.put("consoleWsUrl", HohenheimEndpoints.INSTANCE_CONSOLE.toUrl(
            Map.of(HohenheimEndpoints.INSTANCE_ID, instanceId)));
        vars.put("recordTabs", recordTabs(conduit));
        return new RenderTemplateResult(Identifier.of("hohenheim", "cms/instance-console"), vars);
    }

    @SuppressWarnings("unchecked")
    private static @NonNull Map<String, Object> castSettings(@NonNull Map<?, ?> settings) {
        return (Map<String, Object>) settings;
    }

    /**
     * The persisted console episodes of this instance, and the one {@code ?log=<id>}
     * selects. Retention without a reader would be storage for nobody, so the history the
     * sweeper prunes is the history this tab renders.
     */
    private void addStoredLogs(@NonNull Conduit conduit, @NonNull Map<String, Object> vars,
                               @Nullable Integer instanceId) {
        List<Map<String, Object>> logs = new ArrayList<>();
        InstanceLogModel model = Models.get(InstanceLogModel.class);
        if (instanceId != null) {
            for (Row log : model.findByInstanceId(instanceId, 50)) {
                Map<String, Object> entry = new HashMap<>();
                entry.put("id", log.get(InstanceLogModel.ID));
                entry.put("target", logTarget(conduit, instanceId, log.get(InstanceLogModel.ID)));
                entry.put("handle", String.valueOf((Object) log.get(InstanceLogModel.HANDLE)));
                entry.put("lineCount", log.get(InstanceLogModel.LINE_COUNT));
                entry.put("createdAt", String.valueOf((Object) log.get(InstanceLogModel.CREATED_AT)));
                logs.add(entry);
            }
        }
        vars.put("storedLogs", logs);

        String selected = conduit.getQueryParam("log");
        String text = "";
        String title = "";
        if (selected != null && !selected.isBlank() && instanceId != null) {
            try {
                Row log = model.findById(Integer.parseInt(selected));
                // Ownership guard: a log id belonging to another instance must not render.
                if (log != null && instanceId.equals(log.get(InstanceLogModel.INSTANCE_ID))) {
                    // Already redacted at ingest, and still the workload's stdout VERBATIM:
                    // it leaves here as TEXT and the template renders it as a text node.
                    String stored = log.get(InstanceLogModel.LOG_TEXT);
                    text = stored != null ? stored : "";
                    title = String.valueOf((Object) log.get(InstanceLogModel.CREATED_AT));
                }
            } catch (NumberFormatException ignored) {
                // Bad id: render the page with no selection.
            }
        }
        vars.put("selectedLogText", text);
        vars.put("selectedLogTitle", title);
    }

    /**
     * This tab, with one stored episode selected.
     *
     * AIDEV-NOTE: composed off CmsEndpoints rather than CmsRoutes.subpage because a CMS
     * route PLUS a query parameter cannot be built from CmsRoutes -- its builders return
     * the RouteTarget interface, which has no with(...).
     */
    private static @NonNull RouteTarget logTarget(@NonNull Conduit conduit,
                                                  @NonNull Integer instanceId,
                                                  @NonNull Integer logId) {
        return CmsEndpoints.RECORD_SUBPAGE
            .with(CmsEndpoints.PANEL_PARAM, CmsSupport.panelSlug(conduit))
            .with(CmsEndpoints.RESOURCE_PARAM, "instances")
            .with(CmsEndpoints.RESOURCE_ID_PARAM, String.valueOf(instanceId))
            .with(CmsEndpoints.SUBPAGE_PARAM, SLUG)
            .with(HohenheimParams.SELECTED_LOG, logId);
    }
}
