package be.elevenways.hohenheim.server.source;

import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.server.SystemUsers;
import be.elevenways.hohenheim.server.notification.NotificationEvents;
import be.elevenways.hohenheim.server.notification.Alerts;
import be.elevenways.hohenheim.server.sitetype.SiteHealth;
import be.elevenways.hohenheim.server.sitetype.SiteRequestHandler;
import be.elevenways.hohenheim.server.sitetype.SiteTypeHandler;
import be.elevenways.hohenheim.server.sitetype.UpstreamForwarder;
import be.elevenways.protoblast.common.Blast;
import be.elevenways.zenit.common.orm.datasource.Row;
import io.undertow.server.HttpServerExchange;
import io.undertow.util.Headers;

import java.io.File;
import java.util.Map;
import java.util.concurrent.*;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Wraps an inner SiteRequestHandler with git provisioning lifecycle.
 * Manages async clone/build via dual-slot deploys.
 */
public class GitSiteRequestHandler implements SiteRequestHandler {

    private final int siteId;
    private final Row site;
    private final SiteTypeHandler typeHandler;
    private final Map<String, Object> typeSettings;
    private final Map<String, Object> sourceSettings;
    private final File siteDir;
    private final GitRepository gitRepo;
    private final GitDeploymentQueue queue;
    private final @Nullable Integer uid;

    private volatile SiteRequestHandler innerHandler;
    private volatile String currentCommit;
    private volatile boolean lastDeployFailed = false;
    private ScheduledFuture<?> pollFuture;

    GitSiteRequestHandler(int siteId, Row site, SiteTypeHandler typeHandler,
                          Map<String, Object> typeSettings, Map<String, Object> sourceSettings,
                          File siteDir) {
        this.siteId = siteId;
        this.site = site;
        this.typeHandler = typeHandler;
        this.typeSettings = typeSettings;
        this.sourceSettings = sourceSettings;
        this.siteDir = siteDir;

        String repoUrl = (String) sourceSettings.getOrDefault("repository_url", "");
        String branch = (String) sourceSettings.getOrDefault("branch", "main");
        boolean shallow = Boolean.TRUE.equals(sourceSettings.get("shallow_clone"));
        boolean submodules = Boolean.TRUE.equals(sourceSettings.get("submodules"));

        // Drop git ops + build to the site's system user when configured, so a
        // compromised repo can't run as the (sudo-capable) Hohenheim user.
        this.uid = SystemUsers.resolveUid(typeSettings.get("system_user_id"));
        if (uid == null && SystemUsers.daemonRunsAsRoot()) {
            Blast.log("GIT: site", siteId, "has no system user configured;",
                "clone and build commands run as the root daemon user.",
                "Configure a system user for this site to drop privileges.");
        }
        this.gitRepo = new GitRepository(repoUrl, branch, shallow, submodules, uid);

        // Create the deploy queue with deploy action
        this.queue = new GitDeploymentQueue(siteId, this::executeDeploy);

        // Try reconnect from existing on-disk state
        siteDir.mkdirs();
        GitDeployment deployment = createDeployment();
        SiteRequestHandler reconnected = deployment.reconnect();

        if (reconnected != null) {
            // Reconnected from existing slot -- no build needed
            this.innerHandler = reconnected;
            this.currentCommit = gitRepo.getCurrentCommit(getActiveSlotDir());
            Blast.log("GIT: site", siteId, "reconnected from existing slot, commit:", currentCommit);
        } else {
            // No existing state -- kick off initial async deploy
            Blast.log("GIT: site", siteId, "starting initial deploy");
            queue.enqueue("initial");
        }

        // Start polling if configured
        startPolling();
    }

    @Override
    public int getSiteId() {
        return siteId;
    }

    @Override
    public void handleRequest(HttpServerExchange exchange, UpstreamForwarder forwarder) {
        SiteRequestHandler handler = innerHandler;
        if (handler != null) {
            handler.handleRequest(exchange, forwarder);
        } else {
            exchange.setStatusCode(503);
            exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "text/plain");
            exchange.getResponseSender().send("Deployment in progress");
        }
    }

    @Override
    public SiteHealth getHealth() {
        if (innerHandler == null) {
            return queue.isDeploying() ? SiteHealth.DEPLOYING : SiteHealth.DOWN;
        }
        if (lastDeployFailed) return SiteHealth.DEGRADED;
        return innerHandler.getHealth();
    }

    @Override
    public void destroy() {
        // Cancel polling
        if (pollFuture != null) {
            pollFuture.cancel(false);
            pollFuture = null;
        }

        // Cancel queue (blocks until build thread exits)
        queue.cancel();

        // Destroy inner handler
        SiteRequestHandler handler = innerHandler;
        if (handler != null) {
            handler.destroy();
            innerHandler = null;
        }
    }

    /**
     * Trigger a deploy from an external source (webhook, manual button).
     */
    public void enqueueDeploy(String reason) {
        queue.enqueue(reason);
    }

    public String getCurrentCommit() {
        return currentCommit;
    }

    /** The handler currently serving traffic (a process/static/docker handler), or null mid-deploy. */
    public @Nullable SiteRequestHandler innerHandler() {
        return innerHandler;
    }

    /** Whether the inactive slot holds a matching checkout a rollback could activate. */
    public boolean hasPreviousSlot() {
        String active = GitDeployment.activeSlotName(siteDir);
        String previous = "a".equals(active) ? "b" : "a";
        File previousDir = new File(siteDir, previous);
        return previousDir.isDirectory() && gitRepo.isMatchingRepo(previousDir);
    }

    // -----------------------------------------------------------------------
    // Deploy execution (called by GitDeploymentQueue on its thread)
    // -----------------------------------------------------------------------

    /** Queue reason that activates the previous slot instead of building a new one. */
    public static final String REASON_ROLLBACK = "rollback";

    private void executeDeploy(String reason) {
        GitDeployment deployment = createDeployment();
        SiteRequestHandler oldHandler = innerHandler;
        Integer recordId = DeploymentRecords.started(siteId, reason);

        GitDeployment.DeployResult result;
        if (REASON_ROLLBACK.equals(reason)) {
            String active = GitDeployment.activeSlotName(siteDir);
            String previous = "a".equals(active) ? "b" : "a";
            result = deployment.activateSlot(previous);
        } else {
            result = deployment.execute(oldHandler);
        }

        if (result.success()) {
            // Symlink was already flipped inside GitDeployment
            // Now swap the volatile handler reference
            innerHandler = result.handler();
            currentCommit = result.commitSha();
            lastDeployFailed = false;

            // Destroy old handler (kills old processes)
            if (oldHandler != null) {
                oldHandler.destroy();
            }
        } else {
            lastDeployFailed = true;
            // Old handler (if any) keeps serving
            notifyDeployFailed(result.error());
        }

        DeploymentRecords.finished(recordId, result, deployment.getLog());

        if (!result.success()) {
            // The queue's contract is throw-means-failure: without this it
            // records and logs the failed deploy as a success.
            throw new IllegalStateException(result.error() != null ? result.error() : "Deploy failed");
        }
    }

    /**
     * Roll back to the previous slot: enqueued through the same serial queue as
     * deploys, so it can never race an in-flight build.
     */
    public void enqueueRollback() {
        queue.enqueue(REASON_ROLLBACK);
    }

    /**
     * Cancel the in-flight deploy (if any) without disabling future deploys.
     *
     * @return true when a running deploy was interrupted
     */
    public boolean cancelCurrentDeploy() {
        return queue.cancelCurrent();
    }

    public boolean isDeploying() {
        return queue.isDeploying();
    }

    /** Alert the notification channels about a failed (not cancelled) deploy, best-effort. */
    private void notifyDeployFailed(String error) {
        if (error != null && error.endsWith("cancelled")) {
            return;   // operator-initiated, not a failure
        }
        try {
            String name = site.get(SiteModel.NAME);
            Alerts.send(NotificationEvents.DEPLOY_FAILED,
                "Deploy failed: " + name,
                "Deploying site '" + name + "' failed: " + error
                    + "\nSee the Deployments tab for the build log.");
        } catch (Exception e) {
            Blast.log("GIT: could not send deploy-failure notification -", e.getMessage());
        }
    }

    private GitDeployment createDeployment() {
        return new GitDeployment(siteId, site, typeHandler, typeSettings,
            sourceSettings, gitRepo, siteDir, uid);
    }

    private File getActiveSlotDir() {
        String name = GitDeployment.activeSlotName(siteDir);
        return name == null ? null : new File(siteDir, name);
    }

    // -----------------------------------------------------------------------
    // Polling
    // -----------------------------------------------------------------------

    private void startPolling() {
        Object pollObj = sourceSettings.get("poll_interval");
        int pollSeconds = pollObj instanceof Integer p ? p : 0;
        if (pollSeconds <= 0) return;

        pollFuture = GitProvisioner.getPollScheduler().scheduleAtFixedRate(
            this::pollForChanges,
            pollSeconds, pollSeconds, TimeUnit.SECONDS
        );
    }

    private void pollForChanges() {
        try {
            File activeDir = getActiveSlotDir();
            if (activeDir == null || !activeDir.isDirectory()) return;

            if (gitRepo.hasNewCommits(activeDir)) {
                Blast.log("GIT: poll detected new commits for site", siteId);
                queue.enqueue("poll");
            }
        } catch (Exception e) {
            Blast.log("GIT: poll error for site", siteId, "-", e.getMessage());
        }
    }
}
