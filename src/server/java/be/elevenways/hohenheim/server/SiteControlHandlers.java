package be.elevenways.hohenheim.server;

import be.elevenways.hohenheim.HohenheimEndpoints;
import be.elevenways.hohenheim.model.InstanceModel;

import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.hohenheim.server.cms.HohenheimFlash;
import be.elevenways.hohenheim.server.cms.InstanceConsolePage;
import be.elevenways.hohenheim.server.devtunnel.DevTunnelServerHandler;
import be.elevenways.hohenheim.server.instance.InstanceConsoleHandler;
import be.elevenways.hohenheim.server.instance.InstanceConsoles;
import be.elevenways.hohenheim.server.instance.InstanceShellHandler;
import be.elevenways.hohenheim.server.instance.VmFramebufferHandler;
import be.elevenways.hohenheim.server.application.ReleaseEngine;
import be.elevenways.hohenheim.server.instance.InstanceService;
import be.elevenways.protoblast.common.Blast;
import be.elevenways.protoblast.common.thread.JobRunner;
import be.elevenways.zenit.common.orm.datasource.Datasource;
import be.elevenways.zenit.common.orm.datasource.Db;
import be.elevenways.zenit.cms.common.page.CmsRoutes;
import be.elevenways.zenit.common.conduit.Conduit;
import be.elevenways.zenit.common.orm.activity.ActivityLog;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.validation.Violations;
import be.elevenways.zenit.server.data.RecordSourceGate;
import be.elevenways.zenit.server.http.ReturnTarget;

/**
 * Operational control handlers: the instance Deploys-tab forms, the instance console
 * and the dev tunnel.
 */
final class SiteControlHandlers {

    private SiteControlHandlers() {
    }

    /**
     * Deploy control (forms on the instance's Deploys tab).
     *
     * AIDEV-NOTE: keyed by the APPLICATION instance since brief 9 moved the tab off the
     * site -- the verbs act on the record that owns the releases. The CANCEL verb is
     * gone with the queue it cancelled: the release engine deploys synchronously behind
     * a health gate, and what a failed candidate does is get destroyed while the prior
     * release keeps serving -- there is no queued job to take back.
     */
    static void initDeployControl() {
        HohenheimEndpoints.INSTANCES_DEPLOY.setHandler(conduit -> {
            Integer instanceId = conduit.getParameter(HohenheimEndpoints.INSTANCE_ID);
            if (refusedInstancePower(conduit, instanceId)) {
                return null;
            }
            Datasource datasource = Db.currentOrDefault();
            // THE funnel, not the release engine directly: this button sits on a tab that
            // an application AND a source-declared workspace both have, and only
            // InstanceService.deploy knows which verb the record's kind wants.
            JobRunner.startVirtualThread(() -> Db.run(datasource, () -> {
                try {
                    new InstanceService().deploy(instanceId, "manual");
                } catch (RuntimeException refused) {
                    Blast.log("INSTANCE: manual deploy of", instanceId, "refused -",
                        refused.getMessage());
                }
            }));
            return HandlerSupport.redirectUntyped(deploymentsPageUrl(conduit, instanceId));
        });

        HohenheimEndpoints.INSTANCES_ROLLBACK.setHandler(conduit -> {
            Integer instanceId = conduit.getParameter(HohenheimEndpoints.INSTANCE_ID);
            if (refusedInstancePower(conduit, instanceId)) {
                return null;
            }
            ReleaseEngine.rollback(instanceId);
            return HandlerSupport.redirectUntyped(deploymentsPageUrl(conduit, instanceId));
        });
    }

    static void initInstanceConsole() {
        HohenheimEndpoints.INSTANCE_CONSOLE.setHandlerFactory(session ->
            new InstanceConsoleHandler(session,
                session.getParameter(HohenheimEndpoints.INSTANCE_ID)));

        // The interactive shell rides the SAME transport as the console and is a SEPARATE
        // endpoint on purpose: it answers to `shell`, not `console` (see the endpoint's
        // own note). Registered here so nothing new has to be added to HohenheimHandlers.
        HohenheimEndpoints.INSTANCE_SHELL.setHandlerFactory(session ->
            new InstanceShellHandler(session,
                session.getParameter(HohenheimEndpoints.INSTANCE_ID)));

        HohenheimEndpoints.VM_FRAMEBUFFER.setHandlerFactory(session ->
            new VmFramebufferHandler(session,
                session.getParameter(HohenheimEndpoints.INSTANCE_ID)));

        HohenheimEndpoints.INSTANCE_CONSOLE_COMMAND.setHandler(conduit -> {
            Integer instanceId = conduit.getParameter(HohenheimEndpoints.INSTANCE_ID);
            // The 403 is the UX half; InstanceConsoles.sendCommand asks the same CONSOLE
            // capability again on the funnel, so a direct POST is refused either way.
            if (instanceId == null || !HohenheimAccess.hasInstanceCapability(
                    RecordSourceGate.accessContextOf(conduit), instanceId,
                    HohenheimAccess.CONSOLE)) {
                conduit.forbidden();
                return null;
            }
            String backUrl = ReturnTarget.or(ReturnTarget.read(conduit),
                CmsRoutes.subpage(HandlerSupport.ADMIN, "instances", instanceId,
                    InstanceConsolePage.SLUG).toUrl());
            String command = HandlerSupport.formMap(conduit)
                .getOrDefault("command", "").strip();
            if (command.isEmpty()) {
                return HandlerSupport.redirectUntyped(backUrl);
            }
            try {
                InstanceConsoles.sendCommand(instanceId, command);
            } catch (Violations refused) {
                // NEVER a silent swallow: the refusal rides the session flash.
                HohenheimFlash.error(conduit, HandlerSupport.violationMessage(refused));
                return HandlerSupport.redirectUntyped(backUrl);
            }
            ActivityLog.record(Models.get(InstanceModel.class),
                instanceId, "console_command", command);
            return HandlerSupport.redirectUntyped(backUrl);
        });
    }

    static void initDevTunnel() {
        HohenheimEndpoints.DEV_TUNNEL.setHandlerFactory(DevTunnelServerHandler::new);
    }

    /**
     * The submitted {@code _return} target (the /admin or /manage page the form
     * rendered on), validated by {@link ReturnTarget}; forged values fall back
     * to the admin page.
     */
    private static String deploymentsPageUrl(Conduit conduit, Integer instanceId) {
        return ReturnTarget.or(ReturnTarget.read(conduit),
            CmsRoutes.subpage(HandlerSupport.ADMIN, "instances", instanceId,
                "deployments").toUrl());
    }

    /** Instance-scoped gate: the POWER capability on THIS record; true = 403 written. */
    private static boolean refusedInstancePower(Conduit conduit, Integer instanceId) {
        if (instanceId != null && HohenheimAccess.hasInstanceCapability(
                RecordSourceGate.accessContextOf(conduit), instanceId,
                HohenheimAccess.POWER)) {
            return false;
        }
        conduit.forbidden();
        return true;
    }

}
