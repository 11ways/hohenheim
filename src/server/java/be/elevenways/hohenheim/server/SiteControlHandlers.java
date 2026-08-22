package be.elevenways.hohenheim.server;

import be.elevenways.hohenheim.HohenheimEndpoints;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.hohenheim.server.cms.HohenheimFlash;
import be.elevenways.hohenheim.server.cms.InstanceConsolePage;
import be.elevenways.hohenheim.server.devtunnel.DevTunnelServerHandler;
import be.elevenways.hohenheim.server.instance.InstanceConsoleHandler;
import be.elevenways.hohenheim.server.instance.InstanceConsoles;
import be.elevenways.hohenheim.server.instance.VmFramebufferHandler;
import be.elevenways.hohenheim.server.application.ApplicationDeploys;
import be.elevenways.hohenheim.server.application.ReleaseEngine;
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
 * Per-site operational control: the Deployments tab forms, the instance console and
 * the dev tunnel.
 */
final class SiteControlHandlers {

    private SiteControlHandlers() {
    }

    /**
     * Deploy control (forms on the site's Deployments tab).
     *
     * AIDEV-NOTE: both verbs act on the APPLICATION the site exposes, never on the site.
     * The CANCEL verb is gone with the queue it cancelled: the release engine deploys
     * synchronously behind a health gate, and what a failed candidate does is get destroyed
     * while the prior release keeps serving -- there is no queued job to take back.
     */
    static void initDeployControl() {
        HohenheimEndpoints.SITES_DEPLOY.setHandler(conduit -> {
            Integer siteId = conduit.getParameter(HohenheimEndpoints.SITE_ID);
            if (refusedSiteAccess(conduit, siteId)) {
                return null;
            }
            Integer applicationId = applicationOf(siteId);
            if (applicationId != null) {
                Datasource datasource = Db.current();
                JobRunner.startVirtualThread(() -> Db.run(datasource, () ->
                    ApplicationDeploys.deployQuietly(applicationId, null, "manual")));
            }
            return HandlerSupport.redirectUntyped(deploymentsPageUrl(conduit, siteId));
        });

        HohenheimEndpoints.SITES_ROLLBACK.setHandler(conduit -> {
            Integer siteId = conduit.getParameter(HohenheimEndpoints.SITE_ID);
            if (refusedSiteAccess(conduit, siteId)) {
                return null;
            }
            Integer applicationId = applicationOf(siteId);
            if (applicationId != null) {
                ReleaseEngine.rollback(applicationId);
            }
            return HandlerSupport.redirectUntyped(deploymentsPageUrl(conduit, siteId));
        });
    }

    /** The application a site exposes, or null. */
    private static Integer applicationOf(Integer siteId) {
        if (siteId == null) {
            return null;
        }
        var site = Models.get(SiteModel.class).findById(siteId);
        return site == null ? null : site.get(SiteModel.INSTANCE_ID);
    }

    static void initInstanceConsole() {
        HohenheimEndpoints.INSTANCE_CONSOLE.setHandlerFactory(session ->
            new InstanceConsoleHandler(session,
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
    private static String deploymentsPageUrl(Conduit conduit, Integer siteId) {
        return ReturnTarget.or(ReturnTarget.read(conduit),
            CmsRoutes.subpage(HandlerSupport.ADMIN, "sites", siteId, "deployments").toUrl());
    }

    /** Site-scoped gate: admin or a manage grant on THIS site; true = 403 already written. */
    private static boolean refusedSiteAccess(Conduit conduit, Integer siteId) {
        if (siteId != null && HohenheimAccess.canManageSite(conduit, siteId)) {
            return false;
        }
        conduit.forbidden();
        return true;
    }

}
