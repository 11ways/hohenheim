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
import be.elevenways.hohenheim.server.process.IpcChannel;
import be.elevenways.hohenheim.server.process.ManagedProcess;
import be.elevenways.hohenheim.server.process.ManagedProcessSiteHandler;
import be.elevenways.hohenheim.server.process.ProcessTerminalHandler;
import be.elevenways.hohenheim.server.sitetype.SiteHandlers;
import be.elevenways.hohenheim.server.source.GitSiteRequestHandler;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.zenit.cms.common.page.CmsRoutes;
import be.elevenways.zenit.common.conduit.Conduit;
import be.elevenways.zenit.common.orm.activity.ActivityLog;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.validation.Violations;
import be.elevenways.zenit.server.data.RecordSourceGate;
import be.elevenways.zenit.server.http.ReturnTarget;

import java.util.Optional;

/**
 * Per-site operational control: the Processes tab forms, the Deployments tab forms,
 * the process terminal socket, the instance console and the dev tunnel.
 */
final class SiteControlHandlers {

    private SiteControlHandlers() {
    }

    /** Process control (forms on the site's Processes tab). */
    static void initProcessControl() {
        HohenheimEndpoints.SITES_PROCESS_START.setHandler(conduit -> {
            Integer siteId = conduit.getParameter(HohenheimEndpoints.SITE_ID);
            if (refusedSiteAccess(conduit, siteId)) {
                return null;
            }
            ManagedProcessSiteHandler managed = SiteHandlers.managedProcess(siteId);
            if (managed == null) {
                HohenheimFlash.error(conduit, noManagedHandlerReason());
                return HandlerSupport.redirectUntyped(processesPageUrl(conduit, siteId));
            }
            if (managed.startProcess() == null) {
                HohenheimFlash.error(conduit, processMessage("start_failed"));
                return HandlerSupport.redirectUntyped(processesPageUrl(conduit, siteId));
            }
            ActivityLog.record(Models.get(SiteModel.class), siteId, "started_process", null);
            return HandlerSupport.redirectUntyped(processesPageUrl(conduit, siteId));
        });

        HohenheimEndpoints.SITES_PROCESS_KILL.setHandler(conduit -> {
            Integer siteId = conduit.getParameter(HohenheimEndpoints.SITE_ID);
            Long pid = conduit.getParameter(HohenheimEndpoints.PID);
            if (refusedSiteAccess(conduit, siteId)) {
                return null;
            }
            ManagedProcessSiteHandler managed = SiteHandlers.managedProcess(siteId);
            if (managed == null) {
                HohenheimFlash.error(conduit, noManagedHandlerReason());
                return HandlerSupport.redirectUntyped(processesPageUrl(conduit, siteId));
            }
            ManagedProcess proc = managed.getProcess(pid);
            if (proc == null) {
                HohenheimFlash.error(conduit, processGone(pid));
                return HandlerSupport.redirectUntyped(processesPageUrl(conduit, siteId));
            }
            proc.kill();
            ActivityLog.record(Models.get(SiteModel.class), siteId, "killed_process", "PID " + pid);
            return HandlerSupport.redirectUntyped(processesPageUrl(conduit, siteId));
        });

        HohenheimEndpoints.SITES_PROCESS_ISOLATE.setHandler(conduit -> {
            Integer siteId = conduit.getParameter(HohenheimEndpoints.SITE_ID);
            Long pid = conduit.getParameter(HohenheimEndpoints.PID);
            if (refusedSiteAccess(conduit, siteId)) {
                return null;
            }
            ManagedProcessSiteHandler managed = SiteHandlers.managedProcess(siteId);
            if (managed == null) {
                HohenheimFlash.error(conduit, noManagedHandlerReason());
                return HandlerSupport.redirectUntyped(processesPageUrl(conduit, siteId));
            }
            ManagedProcess proc = managed.getProcess(pid);
            if (proc == null) {
                HohenheimFlash.error(conduit, processGone(pid));
                return HandlerSupport.redirectUntyped(processesPageUrl(conduit, siteId));
            }
            proc.setIsolated(!proc.isIsolated());
            ActivityLog.record(Models.get(SiteModel.class), siteId, "isolated_process", "PID " + pid);
            return HandlerSupport.redirectUntyped(processesPageUrl(conduit, siteId));
        });
    }

    /** Deploy control (forms on the site's Deployments tab). */
    static void initDeployControl() {
        HohenheimEndpoints.SITES_DEPLOY.setHandler(conduit -> {
            Integer siteId = conduit.getParameter(HohenheimEndpoints.SITE_ID);
            if (refusedSiteAccess(conduit, siteId)) {
                return null;
            }
            gitHandler(siteId).ifPresent(git -> {
                git.enqueueDeploy("manual");
                ActivityLog.record(Models.get(SiteModel.class), siteId, "deploy_triggered", null);
            });
            return HandlerSupport.redirectUntyped(deploymentsPageUrl(conduit, siteId));
        });

        HohenheimEndpoints.SITES_DEPLOY_CANCEL.setHandler(conduit -> {
            Integer siteId = conduit.getParameter(HohenheimEndpoints.SITE_ID);
            if (refusedSiteAccess(conduit, siteId)) {
                return null;
            }
            gitHandler(siteId).ifPresent(git -> {
                if (git.cancelCurrentDeploy()) {
                    ActivityLog.record(Models.get(SiteModel.class), siteId, "deploy_cancelled", null);
                }
            });
            return HandlerSupport.redirectUntyped(deploymentsPageUrl(conduit, siteId));
        });

        HohenheimEndpoints.SITES_ROLLBACK.setHandler(conduit -> {
            Integer siteId = conduit.getParameter(HohenheimEndpoints.SITE_ID);
            if (refusedSiteAccess(conduit, siteId)) {
                return null;
            }
            gitHandler(siteId).ifPresent(git -> {
                git.enqueueRollback();
                ActivityLog.record(Models.get(SiteModel.class), siteId, "rollback_triggered", null);
            });
            return HandlerSupport.redirectUntyped(deploymentsPageUrl(conduit, siteId));
        });
    }

    static void initTerminal() {
        HohenheimEndpoints.PROCESS_TERMINAL.setHandlerFactory(session -> {
            Integer siteId = session.getParameter(HohenheimEndpoints.SITE_ID);
            Long pid = session.getParameter(HohenheimEndpoints.PID);
            ManagedProcess proc = null;
            IpcChannel ipc = null;
            Optional<ManagedProcessSiteHandler> managedOpt = managedHandler(siteId);
            if (managedOpt.isPresent() && pid != null) {
                ManagedProcessSiteHandler managed = managedOpt.get();
                proc = managed.getProcess(pid);
                ipc = managed.getIpcChannel(pid);
            }
            return new ProcessTerminalHandler(session, siteId, proc, ipc);
        });
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
     * Why a process-control action could not run: no live handler for the site.
     *
     * AIDEV-NOTE: this whole branch used to be {@code ifPresent(...)}, which turned every
     * such action into a plain page reload after doing NOTHING -- the textbook
     * silent-success shape. There are exactly two ways to have no handler and the operator
     * can act on both, so both are named: the node's processes role is off (the site is
     * served by another node, or this one is a control-plane-only node), or the site
     * faulted at construction and is serving a {@code FaultedSiteHandler} 503 whose reason
     * is on the site's own page.
     */
    private static Microcopy noManagedHandlerReason() {
        return processMessage(HohenheimRoles.enabled(HohenheimRoles.Role.PROCESSES)
            ? "no_handler" : "role_disabled");
    }

    /** A site process-control outcome message. */
    private static Microcopy processMessage(String key) {
        return Microcopy.of(key).withFilter("scope", "site_process");
    }

    /** The refusal for a pid that has already exited. */
    private static Microcopy processGone(Long pid) {
        return processMessage("gone").withArg("pid", pid);
    }

    /**
     * The submitted {@code _return} target (the /admin or /manage page the form
     * rendered on), validated by {@link ReturnTarget}; forged values fall back
     * to the admin page.
     */
    /**
     * AIDEV-NOTE: a literal since SiteProcessesPage was deleted with the site-upstream
     * rename (phase-0 design section 3). It resolves to nothing now; these process-control
     * handlers die with the host-user lane in brief 6 and this constant with them.
     */
    private static final String PROCESSES_SLUG = "processes";

    private static String processesPageUrl(Conduit conduit, Integer siteId) {
        return ReturnTarget.or(ReturnTarget.read(conduit),
            CmsRoutes.subpage(HandlerSupport.ADMIN, "sites", siteId, PROCESSES_SLUG).toUrl());
    }

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

    private static Optional<ManagedProcessSiteHandler> managedHandler(Integer siteId) {
        return Optional.ofNullable(SiteHandlers.managedProcess(siteId));
    }

    static Optional<GitSiteRequestHandler> gitHandler(Integer siteId) {
        return Optional.ofNullable(SiteHandlers.git(siteId));
    }
}
