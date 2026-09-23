package be.elevenways.hohenheim.server;

import be.elevenways.hohenheim.HohenheimEndpoints;
import be.elevenways.hohenheim.HohenheimSlugs;
import be.elevenways.hohenheim.model.InstanceModel;

import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.hohenheim.server.cms.HohenheimFlash;
import be.elevenways.hohenheim.server.cms.InstanceConsolePage;
import be.elevenways.hohenheim.server.devtunnel.DevTunnelServerHandler;
import be.elevenways.hohenheim.server.instance.DeployTrigger;
import be.elevenways.hohenheim.server.instance.InstanceConsoleHandler;
import be.elevenways.hohenheim.server.instance.InstanceConsoles;
import be.elevenways.hohenheim.server.instance.InstanceShellHandler;
import be.elevenways.hohenheim.server.instance.VmFramebufferHandler;
import be.elevenways.hohenheim.server.application.ReleaseEngine;
import be.elevenways.hohenheim.server.instance.InstanceService;
import be.elevenways.protoblast.common.Blast;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.zenit.cms.common.page.CmsRoutes;
import be.elevenways.zenit.common.conduit.Conduit;
import be.elevenways.zenit.common.orm.activity.ActivityLog;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.validation.Violations;
import be.elevenways.zenit.server.data.RecordSourceGate;
import be.elevenways.zenit.server.http.ReturnTarget;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

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
            if (refusedAdmission(conduit, instanceId)) {
                return HandlerSupport.redirectUntyped(deploymentsPageUrl(conduit, instanceId));
            }
            // THE funnel, not the release engine directly: this button sits on a tab that
            // an application AND a source-declared workspace both have, and only
            // InstanceService.deploy knows which verb the record's kind wants.
            report(conduit, settleWithin(SETTLE_WINDOW, "deploy of instance " + instanceId, () ->
                    new InstanceService().deploy(instanceId, DeployTrigger.MANUAL)),
                deploymentsCopy("deploy_done"), deploymentsCopy("deploy_running"),
                deploymentsCopy("deploy_failed"));
            return HandlerSupport.redirectUntyped(deploymentsPageUrl(conduit, instanceId));
        });

        HohenheimEndpoints.INSTANCES_ROLLBACK.setHandler(conduit -> {
            Integer instanceId = conduit.getParameter(HohenheimEndpoints.INSTANCE_ID);
            if (refusedInstancePower(conduit, instanceId)) {
                return null;
            }
            if (refusedAdmission(conduit, instanceId)) {
                return HandlerSupport.redirectUntyped(deploymentsPageUrl(conduit, instanceId));
            }
            report(conduit, settleWithin(SETTLE_WINDOW, "rollback of instance " + instanceId,
                    () -> ReleaseEngine.rollback(instanceId)),
                deploymentsCopy("rollback_done"), deploymentsCopy("rollback_running"),
                deploymentsCopy("rollback_failed"));
            return HandlerSupport.redirectUntyped(deploymentsPageUrl(conduit, instanceId));
        });
    }

    /**
     * How long a Deploys-tab verb may hold the request before it answers "still running".
     *
     * AIDEV-NOTE: the verbs are health-gated (a candidate is started and probed for up to
     * {@code releases.probe_timeout_seconds}), so running them in the request held a
     * thread and the operator's browser for a minute or more, with no bound at all on a
     * slow daemon. The window keeps the useful half: a REFUSAL (not permitted, databases
     * not ready, no retained release, a declined start) is decided before any daemon work
     * and lands inside it as a flash, while real work continues in the background and
     * reports through the durable operation rows the tab already renders.
     */
    static final Duration SETTLE_WINDOW = Duration.ofSeconds(5);

    /** The outcome of a verb after its settle window: done, refused/failed, or still running. */
    record Settled(boolean running, @Nullable RuntimeException failure) {
    }

    /**
     * Run an already-authorized verb in the background and wait at most {@code window} for it.
     * Every failure is logged under {@code label}, including one that lands after the window.
     */
    static @NonNull Settled settleWithin(@NonNull Duration window, @NonNull String label,
                                         @NonNull Runnable verb) {
        CompletableFuture<Void> outcome = new CompletableFuture<>();
        HandlerSupport.inBackground(() -> {
            try {
                verb.run();
                outcome.complete(null);
            } catch (RuntimeException failed) {
                Blast.log("INSTANCE:", label, "refused -", failed.getMessage());
                outcome.completeExceptionally(failed);
            }
        });
        try {
            outcome.get(window.toMillis(), TimeUnit.MILLISECONDS);
            return new Settled(false, null);
        } catch (TimeoutException stillRunning) {
            return new Settled(true, null);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return new Settled(true, null);
        } catch (ExecutionException failed) {
            return new Settled(false, failed.getCause() instanceof RuntimeException runtime
                ? runtime : new IllegalStateException(failed.getCause()));
        }
    }

    /**
     * Flash the verb's outcome: a refusal in its own words, any other failure as the generic
     * sentence (a daemon's text never reaches a delegated tenant; the operation row keeps it).
     */
    private static void report(@NonNull Conduit conduit, @NonNull Settled settled,
                               @NonNull Microcopy done, @NonNull Microcopy running,
                               @NonNull Microcopy failed) {
        if (settled.running()) {
            HohenheimFlash.success(conduit, running);
        } else if (settled.failure() == null) {
            HohenheimFlash.success(conduit, done);
        } else if (settled.failure() instanceof Violations refused) {
            HohenheimFlash.error(conduit, HandlerSupport.violationMessage(refused));
        } else {
            HohenheimFlash.error(conduit, failed);
        }
    }

    /**
     * The deploy admission (power, every attached database ready), asked on the REQUEST
     * thread before the verb goes to the background, where no tenant identity survives;
     * true = refused and flashed in the admission's own words.
     */
    private static boolean refusedAdmission(@NonNull Conduit conduit, @NonNull Integer instanceId) {
        try {
            InstanceService.requireDeployAdmitted(instanceId);
            return false;
        } catch (Violations refused) {
            HohenheimFlash.error(conduit, HandlerSupport.violationMessage(refused));
            return true;
        }
    }

    /** One Deploys-tab outcome sentence. */
    private static @NonNull Microcopy deploymentsCopy(@NonNull String key) {
        return Microcopy.of(key).withFilter("scope", "deployments");
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
                CmsRoutes.subpage(HandlerSupport.ADMIN, HohenheimSlugs.INSTANCES, instanceId,
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
            CmsRoutes.subpage(HandlerSupport.ADMIN, HohenheimSlugs.INSTANCES, instanceId,
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
