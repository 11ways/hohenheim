package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.server.instance.InstanceOperationHandlers;
import be.elevenways.hohenheim.HohenheimIds;
import be.elevenways.hohenheim.HohenheimSlugs;
import be.elevenways.hohenheim.HohenheimEndpoints;
import be.elevenways.hohenheim.HohenheimParams;
import be.elevenways.hohenheim.HohenheimTemplateIds;
import be.elevenways.hohenheim.instance.ConsoleKind;
import be.elevenways.hohenheim.instance.InstanceOperations;
import be.elevenways.hohenheim.model.InstanceLogModel;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.InstanceTemplateModel;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.cms.common.page.CmsEndpoints;
import be.elevenways.zenit.cms.common.page.CmsRoutes;
import be.elevenways.zenit.cms.common.render.action.CmsConfirmation;
import be.elevenways.zenit.cms.common.panel.PanelRequest;
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
 * Console tab on an instance, and its live-console mode: the output terminal (fed by the instance-console WebSocket)
 * and the command form. It is the one tab of the console's modes ({@link ConsoleModes}) in the record strip. The admin
 * CSP (zenit's STRICT_ADMIN) carries ghostty's wasm concessions panel-wide, so this tab is reached by soft navigation
 * like every other.
 */
public final class InstanceConsolePage implements ConsoleModes.Mode {

    public static final String SLUG = "console";

    private final @NonNull ConsoleModes modes;

    InstanceConsolePage(@NonNull ConsoleModes modes) {
        this.modes = modes;
    }

    @Override public @NonNull Identifier id() { return HohenheimIds.id("instance_console"); }
    @Override public @NonNull Microcopy label() { return Microcopy.of("console").withFilter("scope", "instance"); }
    @Override public @NonNull String slug() { return SLUG; }
    @Override public @NonNull Icon icon() { return Icon.of("terminal"); }

    @Override
    public @NonNull Microcopy hint() {
        return Microcopy.of("console").withFilter("scope", "console_mode");
    }

    /**
     * The live console is offered only where the console operation is offered on THIS record -- the offer its socket
     * admits through, so a generated instance's console is never offered here.
     *
     * AIDEV-NOTE: this shipped ungated, which made the page a wider door than the socket
     * it fronts: the live terminal's handshake demands CONSOLE (InstanceConsoleHandler)
     * and the command form's POST demands it too, but {@link #addStoredLogs} reads
     * InstanceLogModel directly and rendered every RETAINED console episode to anyone the
     * resource's view-only scope let through. Same output, same capability. The TAB is wider (any mode offered, see
     * {@link #visibleFor}); the console's own output still renders only under this gate.
     */
    @Override
    public boolean offers(@NonNull Row record, @NonNull AccessContext accessContext) {
        return InstanceOperationHandlers.offered(InstanceOperations.CONSOLE_COMMAND, accessContext, record);
    }

    /** The tab is in the strip while any console mode is offered; zenit-cms 404s it otherwise. */
    @Override
    public boolean visibleFor(@NonNull Row record, @NonNull AccessContext accessContext) {
        return this.modes.anyOffered(record, accessContext);
    }

    @Override
    public @NonNull ActionResult<?> render(@NonNull PanelRequest request, @NonNull Row instance) {
        if (!this.offers(instance, request.access())) {
            // The tab is reachable because another mode is offered: render that mode, never this one's output.
            ConsoleModes.Mode other = this.modes.firstOfferedBesidesHub(instance, request.access());
            if (other == null) {
                throw new IllegalStateException("Console tab rendered with no mode offered on instance "
                    + instance.get(InstanceModel.ID));
            }
            return other.render(request, instance);
        }
        Conduit conduit = request.conduit();
        Integer instanceId = instance.get(InstanceModel.ID);
        String status = instance.get(InstanceModel.STATUS);

        Object templateId = instance.get(InstanceModel.TEMPLATE_ID);
        Row template = templateId instanceof Integer id
            ? Models.get(InstanceTemplateModel.class).findById(id) : null;
        String stopCommand = template != null
            ? template.get(InstanceTemplateModel.STOP_COMMAND) : null;

        Map<String, Object> vars = this.modes.vars(request, instance, this);
        this.addStoredLogs(conduit, request.panelSlug(), vars, instanceId);
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
        // The command form is the placed console operation's own invoke, its line asked here instead of in the
        // action's dialog: the form posts the confirmation proof the dialog would, and lands back on this tab.
        vars.put("commandTarget", CmsRoutes.invoke(request.panelSlug(), HohenheimSlugs.INSTANCES,
                InstanceOperations.CONSOLE_COMMAND.id())
            .with(CmsEndpoints.SUBJECT_PARAM, String.valueOf(instanceId)));
        vars.put("confirmParam", CmsConfirmation.FIELD);
        vars.put("confirmProof", CmsConfirmation.PLAIN_PROOF);
        // AIDEV-NOTE: WebSocketEndpoint is not a RouteTarget and has no with(...), and
        // pl-terminal takes a wsUrl STRING anyway, so the socket route is RENDERED from
        // its own declaration here -- endpoint-derived, never concatenated.
        vars.put("consoleWsUrl", HohenheimEndpoints.INSTANCE_CONSOLE.toUrl(
            Map.of(HohenheimEndpoints.INSTANCE_ID, instanceId)));
        return new RenderTemplateResult(HohenheimTemplateIds.INSTANCE_CONSOLE, vars);
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
    private void addStoredLogs(@NonNull Conduit conduit, @NonNull String panel,
                               @NonNull Map<String, Object> vars,
                               @Nullable Integer instanceId) {
        List<Map<String, Object>> logs = new ArrayList<>();
        InstanceLogModel model = Models.get(InstanceLogModel.class);
        if (instanceId != null) {
            for (Row log : model.findByInstanceId(instanceId, 50)) {
                Map<String, Object> entry = new HashMap<>();
                entry.put("id", log.get(InstanceLogModel.ID));
                entry.put("target", logTarget(panel, instanceId, log.get(InstanceLogModel.ID)));
                entry.put("handle", String.valueOf((Object) log.get(InstanceLogModel.HANDLE)));
                entry.put("lineCount", log.get(InstanceLogModel.LINE_COUNT));
                // The ISO instant; the template words it in the viewer's zone (pl-relative-time, Dates.absoluteText).
                entry.put("createdAtIso", String.valueOf((Object) log.get(InstanceLogModel.CREATED_AT)));
                logs.add(entry);
            }
        }
        vars.put("storedLogs", logs);

        // A malformed id reads as null: the page renders with no selection.
        Integer selected = CmsSupport.prefill(conduit, HohenheimParams.SELECTED_LOG);
        String text = "";
        String atIso = "";
        if (selected != null && instanceId != null) {
            Row log = model.findById(selected);
            // Ownership guard: a log id belonging to another instance must not render.
            if (log != null && instanceId.equals(log.get(InstanceLogModel.INSTANCE_ID))) {
                // Already redacted at ingest, and still the workload's stdout VERBATIM:
                // it leaves here as TEXT and the template renders it as a text node.
                String stored = log.get(InstanceLogModel.LOG_TEXT);
                text = stored != null ? stored : "";
                atIso = String.valueOf((Object) log.get(InstanceLogModel.CREATED_AT));
            }
        }
        vars.put("selectedLogText", text);
        vars.put("selectedLogAtIso", atIso);
    }

    /**
     * This tab, with one stored episode selected.
     *
     * AIDEV-NOTE: composed off CmsEndpoints rather than CmsRoutes.subpage because a CMS
     * route PLUS a query parameter cannot be built from CmsRoutes -- its builders return
     * the RouteTarget interface, which has no with(...).
     */
    private static @NonNull RouteTarget logTarget(@NonNull String panel,
                                                  @NonNull Integer instanceId,
                                                  @NonNull Integer logId) {
        return CmsEndpoints.RECORD_SUBPAGE
            .with(CmsEndpoints.PANEL_PARAM, panel)
            .with(CmsEndpoints.RESOURCE_PARAM, HohenheimSlugs.INSTANCES)
            .with(CmsEndpoints.RESOURCE_ID_PARAM, String.valueOf(instanceId))
            .with(CmsEndpoints.SUBPAGE_PARAM, SLUG)
            .with(HohenheimParams.SELECTED_LOG, logId);
    }

}
