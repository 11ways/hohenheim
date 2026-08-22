package be.elevenways.hohenheim.server.preview;

import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.hohenheim.model.BuildOperationModel;
import be.elevenways.hohenheim.model.DnsRecordModel;
import be.elevenways.hohenheim.model.DnsZoneModel;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.PreviewDeploymentModel;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.model.SiteDomainModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.server.ControllerScope;
import be.elevenways.hohenheim.server.ServerMain;
import be.elevenways.hohenheim.server.build.BuildQuota;
import be.elevenways.hohenheim.server.build.BuildRequest;
import be.elevenways.hohenheim.server.build.SandboxedBuilds;
import be.elevenways.hohenheim.server.dns.DnsNames;
import be.elevenways.hohenheim.server.dns.DnsZoneStore;
import be.elevenways.hohenheim.server.docker.DockerClient;
import be.elevenways.hohenheim.server.application.ApplicationReleases;
import be.elevenways.hohenheim.server.application.ReleaseEngine;
import be.elevenways.hohenheim.server.game.GameDomains;
import be.elevenways.hohenheim.server.instance.InstanceService;
import be.elevenways.hohenheim.server.orm.GeneratedRows;
import be.elevenways.hohenheim.server.proxy.ProxyServer;
import be.elevenways.hohenheim.server.runtime.InstanceStatus;
import be.elevenways.hohenheim.server.source.DeployStatuses;
import be.elevenways.hohenheim.server.source.SiteSources;
import be.elevenways.hohenheim.server.source.GitProviderClient;
import be.elevenways.hohenheim.server.source.GitCheckout;
import be.elevenways.hohenheim.server.source.GitProviders;
import be.elevenways.hohenheim.server.source.GitRepository;
import be.elevenways.hohenheim.server.util.EnvVars;
import be.elevenways.protoblast.common.Blast;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.thread.JobRunner;
import be.elevenways.zenit.common.orm.datasource.Datasource;
import be.elevenways.zenit.common.orm.datasource.Datasources;
import be.elevenways.zenit.common.orm.datasource.Db;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.orm.query.SortOrder;
import be.elevenways.zenit.common.validation.Violations;
import be.elevenways.zenit.server.security.SecureTokens;
import be.elevenways.zenit.server.task.record.RecordSchedules;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.io.File;
import java.net.URI;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The preview-deployment lifecycle: create/refresh one git ref OF AN APPLICATION, serve it
 * on a generated hostname, and RECLAIM -- instance, port claims, generated domain row and
 * DNS rows -- when the bounded lifetime ends, the PR closes, an operator acts, or the
 * owning application dies.
 *
 * AIDEV-NOTE: previews are keyed to the APPLICATION (phase-0 brief 7), not to a site. The
 * application is what has a source to build and a spec to run; a site only lends the
 * preview a hostname, because a hostname routes only from some site's domain table. An
 * application no site exposes is refused BY NAME rather than built and left unreachable.
 *
 * A preview deliberately uses the DIRECT deploy lane, not the candidate/probe/switch
 * gate: nothing production-facing is being replaced, so a refused preview is simply a
 * failed preview while production stays untouched. What it DOES reuse from the release
 * engine is everything that made it safe: InstanceService (fenced writes, verified
 * destroy, ledger), the sandboxed digest-pinned build, the release-probe, and the
 * GeneratedRows attribution discipline for every row it materializes.
 *
 * Variable isolation is structural: the desired settings are BUILT here and only
 * {@code preview_environment_variables} ever enters them -- production
 * {@code environment_variables}, injected database credentials and volumes have no
 * code path into a preview spec.
 */
public final class PreviewDeployments {

    /** Per-(application, ref) serialization so a webhook burst cannot race itself. */
    private static final Map<String, Object> LOCKS = new ConcurrentHashMap<>();

    private PreviewDeployments() {
    }

    // -- create / refresh ------------------------------------------------------

    /**
     * {@link #deploy} for fire-and-forget callers (webhooks); failures are logged, and
     * a refusal BEFORE the preview row exists (quota reached, unsupported type, no base
     * domain) is additionally reported onto the commit status when a sha is known --
     * without it a capped preview was invisible everywhere but the controller log.
     */
    public static void deployQuietly(int applicationId, @NonNull String ref, @Nullable String sha,
                                     @Nullable Integer prNumber) {
        try {
            deploy(applicationId, ref, sha, prNumber);
        } catch (Exception e) {
            Blast.log("PREVIEW: deploy of application", applicationId, "ref", ref, "failed -",
                e.getMessage());
            if (e instanceof Violations && sha != null && !sha.isBlank()) {
                reportRefusal(applicationId, sha, reasonOf(e));
            }
        }
    }

    /**
     * The manual lane: validate, charge and claim the preview row NOW (so a refusal --
     * quota by name, unsupported type, missing base domain -- reaches the submitting
     * form synchronously), then build in the background exactly like a webhook deploy.
     *
     * AIDEV-NOTE: QUOTA DECISION (2026-08-10, applies to ALL creation lanes). The
     * charge is the APPLICATION's manage-grant owner pack, always -- the PreviewQuota
     * before-write hook fires on this row save no matter which surface asked, so
     * charge == cap holds structurally and a manual or branch preview can never ride
     * an uncharged side door. Charging the ACTING operator instead was rejected: the
     * environment lives in the application owner's fleet, and an actor-derived charge would
     * book a bucket the row's release path could not re-derive. At the cap every lane
     * REFUSES BY NAME (preview_quota_reached); eviction of the oldest preview was
     * rejected because it destroys a running environment (possibly a PR under active
     * review) to serve a push nobody is watching.
     *
     * @return the claimed preview row, status deploying
     * @throws Violations naming the refusal
     */
    public static @NonNull Row queue(int applicationId, @NonNull String ref, @Nullable String sha,
                                     @Nullable Integer prNumber) throws Exception {
        Row preview;
        synchronized (lockFor(applicationId, ref)) {
            preview = claimLocked(applicationId, ref, prNumber);
        }
        Datasource datasource = Db.current();
        String pinnedSha = sha;
        JobRunner.startVirtualThread(() -> {
            Runnable build = () -> {
                try {
                    deploy(applicationId, ref, pinnedSha, prNumber);
                } catch (Exception e) {
                    // The row already records status failed + last_error.
                    Blast.log("PREVIEW: queued deploy of application", applicationId, "ref", ref,
                        "failed -", e.getMessage());
                }
            };
            if (datasource != null) {
                Db.run(datasource, build);
            } else {
                build.run();
            }
        });
        return preview;
    }

    /**
     * Create or refresh the preview of one ref: quota-charged row, sandboxed build
     * pinned by digest, attributed instance, generated hostname row and DNS rows,
     * bounded lifetime stamped. Refreshing an existing live preview extends its window.
     *
     * @return the preview row, status running
     * @throws Violations naming the refusal (unsupported type, no base domain, quota,
     *         build failure, probe failure)
     */
    public static @NonNull Row deploy(int applicationId, @NonNull String ref, @Nullable String sha,
                                      @Nullable Integer prNumber) throws Exception {
        synchronized (lockFor(applicationId, ref)) {
            return deployLocked(applicationId, ref, sha, prNumber);
        }
    }

    /**
     * The synchronous half of a deploy: validate the application, mint/refresh the
     * quota-charged row (status deploying) and arm the expiry. Callers hold the
     * per-(application, ref) lock.
     */
    private static @NonNull Row claimLocked(int applicationId, @NonNull String ref,
                                            @Nullable Integer prNumber) {
        Row application = ApplicationReleases.requireApplication(applicationId);
        if (!SiteSources.hasRepository(ApplicationReleases.storedSettings(application))) {
            throw Violations.ofField("application_id", applicationId,
                violation("preview_unsupported_type"));
        }
        String baseDomain = str(HohenheimSettings.VALUES.getValue(
            HohenheimSettings.Previews.BASE_DOMAIN));
        if (baseDomain.isEmpty()) {
            throw Violations.ofForm(violation("preview_no_base_domain"));
        }
        // A preview is built from the APPLICATION but must be REACHABLE, and a hostname
        // only routes when it sits in some site's domain table. Refusing here beats
        // building an environment nobody can open.
        Row site = exposingSite(applicationId);
        if (site == null) {
            throw Violations.ofField("application_id", applicationId,
                violation("preview_no_exposing_site"));
        }
        String hostname = hostnameFor(str(site.get(SiteModel.SLUG)), ref, baseDomain);

        PreviewDeploymentModel model = Models.get(PreviewDeploymentModel.class);
        Row preview = model.find()
            .where(PreviewDeploymentModel.APPLICATION_ID.eq(applicationId))
            .where(PreviewDeploymentModel.REF.eq(ref))
            .where(PreviewDeploymentModel.DELETED_AT.isNull())
            .first();
        if (preview == null) {
            // The quota hook charges the application's owner bucket on this save and refuses
            // over-cap creates atomically -- no separate count-then-create window.
            preview = model.createEmptyRow();
            preview.set(PreviewDeploymentModel.APPLICATION_ID, applicationId);
            preview.set(PreviewDeploymentModel.REF, ref);
        }
        Instant expiresAt = expiry();
        preview.set(PreviewDeploymentModel.PR_NUMBER, prNumber);
        preview.set(PreviewDeploymentModel.HOSTNAME, hostname);
        preview.set(PreviewDeploymentModel.STATUS, PreviewDeploymentModel.STATUS_DEPLOYING);
        preview.set(PreviewDeploymentModel.EXPIRES_AT, expiresAt);
        preview.set(PreviewDeploymentModel.LAST_ERROR, null);
        model.save(preview);
        // Armed BEFORE the build: a preview that fails to build still gets reclaimed
        // at its deadline, exactly like the old sweep's status-blind query did.
        armExpiry(preview.get(PreviewDeploymentModel.ID), expiresAt);
        return preview;
    }

    private static @NonNull Row deployLocked(int applicationId, @NonNull String ref,
                                             @Nullable String sha,
                                             @Nullable Integer prNumber) throws Exception {
        Row preview = claimLocked(applicationId, ref, prNumber);
        PreviewDeploymentModel model = Models.get(PreviewDeploymentModel.class);
        Row application = ApplicationReleases.requireApplication(applicationId);
        Row site = exposingSite(applicationId);
        // One settings map now: the application carries BOTH the source and the spec.
        Map<String, Object> siteSettings = ApplicationReleases.storedSettings(application);
        Map<String, Object> sourceSettings = siteSettings;
        String hostname = str(preview.get(PreviewDeploymentModel.HOSTNAME));
        int previewId = preview.get(PreviewDeploymentModel.ID);

        try {
            DeployStatuses.report(sourceSettings, sha, GitProviderClient.StatusState.PENDING,
                DeployStatuses.CONTEXT_PREVIEW, "Preview deploying", null);

            // 1. Checkout the ref (control-plane clone; the build runs sandboxed).
            File checkout = GitCheckout.directoryFor(PreviewDeploymentModel.MODEL_ID, previewId);
            String commitSha = GitCheckout.materialize(PreviewDeploymentModel.MODEL_ID,
                previewId, ref, sourceSettings, checkout);
            if (sha == null || sha.isBlank()) {
                sha = commitSha;
            }

            // 2. Sandboxed, digest-pinned build of the preview's image.
            // Previews build against the local daemon, so the policy targets "local".
            SandboxedBuilds.Result build = new SandboxedBuilds(new DockerClient(),
                ServerModel.MODE_LOCAL)
                .run(new BuildRequest(PreviewDeploymentModel.MODEL_ID, previewId,
                    BuildOperationModel.kindOrDefault(siteSettings.get("builder")),
                    checkout.toPath(),
                    str(siteSettings.get("dockerfile")).isEmpty()
                        ? null : str(siteSettings.get("dockerfile")),
                    ControllerScope.handle(ControllerScope.KIND_PREVIEW, previewId) + ":latest",
                    EnvVars.toMap(siteSettings.get("build_arguments")),
                    commitSha, null, BuildQuota.fromSettings()));
            if (!build.succeeded() || build.imageId() == null) {
                throw Violations.ofForm(violation("preview_build_failed")
                    .withArg("reason", build.failureReason() != null
                        ? build.failureReason() : build.status()));
            }

            // 3. Deploy the attributed instance and probe it.
            Map<String, Object> desired = desiredSettings(siteSettings, sourceSettings,
                build.imageId(), commitSha);
            InstanceStatus status = converge(preview, site, desired, hostname);
            Integer port = status.publishedPort();
            if (port == null) {
                throw Violations.ofForm(violation("preview_no_published_port"));
            }
            ReleaseEngine.probe(port, healthPath(siteSettings));

            // 4. Stamp running; route it.
            preview.set(PreviewDeploymentModel.STATUS, PreviewDeploymentModel.STATUS_RUNNING);
            preview.set(PreviewDeploymentModel.HEAD_SHA, sha);
            model.save(preview);
            reloadProxy();
            DeployStatuses.report(sourceSettings, sha, GitProviderClient.StatusState.SUCCESS,
                DeployStatuses.CONTEXT_PREVIEW, "Preview ready", "https://" + hostname + "/");
            Blast.log("PREVIEW:", hostname, "ready (application", applicationId + ", ref", ref + ")");
            return preview;
        } catch (Exception failed) {
            preview.set(PreviewDeploymentModel.STATUS, PreviewDeploymentModel.STATUS_FAILED);
            preview.set(PreviewDeploymentModel.LAST_ERROR, reasonOf(failed));
            model.save(preview);
            DeployStatuses.report(sourceSettings, sha, GitProviderClient.StatusState.FAILURE,
                DeployStatuses.CONTEXT_PREVIEW, "Preview failed: " + reasonOf(failed), null);
            throw failed;
        }
    }

    /** Best-effort commit-status FAILURE for a refusal that never minted a row. */
    private static void reportRefusal(int applicationId, @NonNull String sha, @NonNull String reason) {
        try {
            Row application = Models.get(InstanceModel.class).findById(applicationId);
            if (application == null) {
                return;
            }
            DeployStatuses.report(ApplicationReleases.storedSettings(application), sha,
                GitProviderClient.StatusState.FAILURE, DeployStatuses.CONTEXT_PREVIEW,
                "Preview refused: " + reason, null);
        } catch (RuntimeException unreported) {
            Blast.log("PREVIEW: could not report refusal for application", applicationId, "-",
                unreported.getMessage());
        }
    }

    // -- teardown --------------------------------------------------------------

    /** {@link #destroy} by (application, ref) for fire-and-forget callers. */
    public static void destroyForRefQuietly(int applicationId, @NonNull String ref,
                                            @NonNull String reason) {
        try {
            Row preview = Models.get(PreviewDeploymentModel.class).find()
                .where(PreviewDeploymentModel.APPLICATION_ID.eq(applicationId))
                .where(PreviewDeploymentModel.REF.eq(ref))
                .where(PreviewDeploymentModel.DELETED_AT.isNull())
                .first();
            if (preview != null) {
                destroy(preview.get(PreviewDeploymentModel.ID), reason);
            }
        } catch (Exception e) {
            Blast.log("PREVIEW: teardown of application", applicationId, "ref", ref, "failed -",
                e.getMessage());
        }
    }

    /**
     * Verified end of life: the instance is destroyed through InstanceService (container
     * removed or observed absent, claims released), the generated domain row and DNS
     * rows die BY EXACT ATTRIBUTION, the checkout directory is deleted, and the row
     * soft-deletes (which releases the quota charge and records the hostname's released
     * route claim under the application's owner -- so the same owner's next preview may retake
     * it while a stranger is quarantined).
     *
     * @throws Violations when the daemon cannot confirm the teardown -- the preview then
     *         stays live (status failed, error recorded) rather than lying
     */
    public static void destroy(int previewId, @NonNull String reason) {
        PreviewDeploymentModel model = Models.get(PreviewDeploymentModel.class);
        Row preview = model.findById(previewId);
        if (preview == null || preview.get(PreviewDeploymentModel.DELETED_AT) != null) {
            return;
        }
        int applicationId = intOf(preview.get(PreviewDeploymentModel.APPLICATION_ID));
        synchronized (lockFor(applicationId, str(preview.get(PreviewDeploymentModel.REF)))) {
            try {
                inScope(previewId, () -> {
                    Integer instanceId = preview.get(PreviewDeploymentModel.INSTANCE_ID);
                    if (instanceId != null) {
                        Row instance = Models.get(InstanceModel.class).findById(instanceId);
                        if (instance != null
                                && instance.get(InstanceModel.DELETED_AT) == null) {
                            new InstanceService().destroy(instanceId);
                        }
                    }
                    removeGeneratedDomains(previewId);
                    removeGeneratedDns(previewId);
                });
            } catch (Exception failed) {
                preview.set(PreviewDeploymentModel.STATUS,
                    PreviewDeploymentModel.STATUS_FAILED);
                preview.set(PreviewDeploymentModel.LAST_ERROR,
                    "teardown failed: " + reasonOf(failed));
                model.save(preview);
                if (failed instanceof RuntimeException runtime) {
                    throw runtime;
                }
                throw new IllegalStateException(failed);
            }
            // Nothing holds the preview's build artifacts any more; keeping NOTHING is
            // correct (the destroyFor precedent) -- without this the per-preview images
            // dangle forever, because a destroyed preview never builds again to prune.
            try {
                be.elevenways.hohenheim.server.build.BuildArtifacts.pruneSuperseded(
                    new DockerClient(), PreviewDeploymentModel.MODEL_ID.toString(),
                    previewId, "");
            } catch (RuntimeException pruneFailed) {
                Blast.log("PREVIEW: artifact prune of preview", previewId, "failed -",
                    pruneFailed.getMessage());
            }
            GitCheckout.deleteTree(
                GitCheckout.directoryFor(PreviewDeploymentModel.MODEL_ID, previewId));
            preview.set(PreviewDeploymentModel.STATUS,
                "expired".equals(reason) ? PreviewDeploymentModel.STATUS_EXPIRED
                                         : PreviewDeploymentModel.STATUS_DESTROYED);
            preview.set(PreviewDeploymentModel.DELETED_AT, Instant.now());
            model.save(preview);
            // AIDEV-NOTE: soft delete fires no remove hooks, so the one-shot expiry
            // schedule must die here explicitly (the InstanceService.destroy
            // precedent). When THIS destroy was itself fired by that schedule, the
            // chain's later run-row writes match zero deleted rows and slog
            // run_write_fenced -- benign: the record and its history are gone.
            new RecordSchedules(Datasources.getDefault())
                .deleteForRecord(PreviewDeploymentModel.MODEL_ID, previewId);
            reloadProxy();
            Blast.log("PREVIEW:", preview.get(PreviewDeploymentModel.HOSTNAME),
                "reclaimed (" + reason + ")");
        }
    }

    /**
     * Destroy every live preview whose generated hostname lives on this site.
     *
     * AIDEV-NOTE: a preview belongs to an APPLICATION, which outlives any one site, so this
     * cascade is keyed on the thing the site actually owns -- the generated domain row.
     * Deleting one exposing site of a multi-site application takes only the previews that
     * were reachable through it.
     */
    public static void destroyForSite(int siteId) {
        for (Row preview : Models.get(PreviewDeploymentModel.class).find()
                .where(PreviewDeploymentModel.DELETED_AT.isNull()).all()) {
            int previewId = preview.get(PreviewDeploymentModel.ID);
            for (Row domain : generatedDomainsOf(previewId)) {
                if (Integer.valueOf(siteId).equals(domain.get(SiteDomainModel.SITE_ID))) {
                    destroy(previewId, "site_deleted");
                    break;
                }
            }
        }
    }

    /** Every live preview of one application; the application-delete cascade. */
    public static void destroyForApplication(int applicationId) {
        for (Row preview : Models.get(PreviewDeploymentModel.class)
                .findLiveByApplicationId(applicationId)) {
            destroy(preview.get(PreviewDeploymentModel.ID), "application_deleted");
        }
    }

    /**
     * A site that exposes this application, lowest id first so the hostname a preview gets
     * is stable across reloads.
     */
    public static @Nullable Row exposingSite(int applicationId) {
        return Models.get(SiteModel.class).find()
            .where(SiteModel.INSTANCE_ID.eq(applicationId))
            .where(SiteModel.DELETED_AT.isNull())
            .orderBy(SiteModel.ID, SortOrder.ASC)
            .first();
    }

    /**
     * Arm (or EXTEND) the preview's bounded lifetime as a one-shot record schedule:
     * the framework sweeper fires {@link PreviewExpireAction} once at the deadline --
     * stored in the database, so a controller that was down past it still enforces it
     * at its next sweep, delayed but never voided. The {@code expires_at} column is
     * DISPLAY data only; this schedule is the enforcement.
     */
    public static void armExpiry(int previewId, @NonNull Instant expiresAt) {
        new RecordSchedules(Datasources.getDefault()).armOnce(
            PreviewDeploymentModel.MODEL_ID, previewId, "expire",
            expiresAt, PreviewExpireAction.ID, null, null);
    }

    // -- routing support -------------------------------------------------------

    /** The proxy upstream of one preview, or null when it is not serving. */
    public static @Nullable URI upstreamOf(int previewId) {
        Row preview = Models.get(PreviewDeploymentModel.class).findById(previewId);
        if (preview == null || preview.get(PreviewDeploymentModel.DELETED_AT) != null
                || !PreviewDeploymentModel.STATUS_RUNNING.equals(
                    preview.get(PreviewDeploymentModel.STATUS))) {
            return null;
        }
        Integer instanceId = preview.get(PreviewDeploymentModel.INSTANCE_ID);
        if (instanceId == null) {
            return null;
        }
        try {
            InstanceStatus status = new InstanceService().liveStatus(instanceId);
            Integer port = status.publishedPort();
            return status.running() && port != null
                ? URI.create("http://127.0.0.1:" + port + "/") : null;
        } catch (RuntimeException e) {
            Blast.log("PREVIEW: upstream of preview", previewId, "unresolvable -",
                e.getMessage());
            return null;
        }
    }

    // -- internals -------------------------------------------------------------

    /**
     * The preview's container spec. Only preview-declared variables enter it; resource
     * limits are inherited (they cap, never leak), volumes and injected database
     * credentials never do.
     */
    private static @NonNull Map<String, Object> desiredSettings(
            @NonNull Map<String, Object> siteSettings,
            @NonNull Map<String, Object> sourceSettings,
            @NonNull String imageDigest, @NonNull String commitSha) {
        Map<String, Object> desired = new LinkedHashMap<>();
        desired.put("image", imageDigest);
        desired.put("built_image_id", imageDigest);
        desired.put("commit_sha", commitSha);
        Object port = siteSettings.get("container_port");
        if (port instanceof Number number && number.intValue() > 0) {
            desired.put("container_port", number.intValue());
        }
        String healthPath = str(siteSettings.get("health_path"));
        if (!healthPath.isEmpty()) {
            desired.put("health_path", healthPath);
        }
        Map<String, String> env = EnvVars.toMap(
            sourceSettings.get("preview_environment_variables"));
        if (!env.isEmpty()) {
            desired.put("environment_variables", env);
        }
        Object memory = siteSettings.get("memory_limit_mb");
        if (memory instanceof Number number && number.intValue() > 0) {
            desired.put("memory_limit_mb", number.intValue());
        }
        Object cpu = siteSettings.get("cpu_limit");
        if (cpu instanceof Number number && number.doubleValue() > 0) {
            desired.put("cpu_limit", number.doubleValue());
        }
        return desired;
    }

    /** Instance + generated domain + DNS rows, all inside the preview's attribution. */
    private static @NonNull InstanceStatus converge(@NonNull Row preview, @NonNull Row site,
                                                    @NonNull Map<String, Object> desired,
                                                    @NonNull String hostname) throws Exception {
        int previewId = preview.get(PreviewDeploymentModel.ID);
        int hostSiteId = site.get(SiteModel.ID);
        InstanceStatus[] status = new InstanceStatus[1];
        inScope(previewId, () -> {
            Integer instanceId = preview.get(PreviewDeploymentModel.INSTANCE_ID);
            Row instance = instanceId != null
                ? Models.get(InstanceModel.class).findById(instanceId) : null;
            if (instance == null || instance.get(InstanceModel.DELETED_AT) != null) {
                instance = Models.get(InstanceModel.class).createEmptyRow();
                instance.set(InstanceModel.NAME, "preview-" + hostname);
                instance.set(InstanceModel.KIND,
                    be.elevenways.hohenheim.server.docker.ReleaseKind.ID.toString());
                instance.set(InstanceModel.SERVER_ID, ServerModel.localServerId());
                instance.set(InstanceModel.RUNTIME_ROLE, InstanceModel.ROLE_SERVING);
            }
            instance.set(InstanceModel.SETTINGS, desired);
            Models.get(InstanceModel.class).save(instance);
            int freshInstanceId = instance.get(InstanceModel.ID);
            preview.set(PreviewDeploymentModel.INSTANCE_ID, freshInstanceId);
            Models.get(PreviewDeploymentModel.class).save(preview);

            status[0] = new InstanceService().deploy(freshInstanceId);

            ensureGeneratedDomain(previewId, hostSiteId, hostname);
            reconcileGeneratedDns(previewId, hostname);
        });
        return status[0];
    }

    /**
     * The generated hostname row: standard site_domains write pipeline, so the route
     * conflict scan, the released-claim quarantine and the live_route_key claim all
     * judge it exactly like a hand-authored domain -- the attribution is the only
     * difference, and it is what scopes cleanup.
     */
    private static void ensureGeneratedDomain(int previewId, int hostSiteId,
                                              @NonNull String hostname) {
        var domains = Models.get(SiteDomainModel.class);
        List<Row> owned = generatedDomainsOf(previewId);
        Row keep = null;
        for (Row row : owned) {
            if (hostname.equalsIgnoreCase(str(row.get(SiteDomainModel.HOSTNAME)))
                    && keep == null) {
                keep = row;
            } else {
                domains.delete(row.get(SiteDomainModel.ID));
            }
        }
        if (keep != null) {
            return;
        }
        Row domain = domains.createEmptyRow();
        domain.set(SiteDomainModel.SITE_ID, hostSiteId);
        domain.set(SiteDomainModel.HOSTNAME, hostname);
        domain.set(SiteDomainModel.MATCH_TYPE, SiteDomainModel.MATCH_EXACT);
        // Plain HTTP + wildcard-cert SNI: a preview never triggers ACME issuance.
        domain.set(SiteDomainModel.FORCE_SSL, false);
        domain.set(SiteDomainModel.EXCLUDE_FROM_LETSENCRYPT, true);
        domains.save(domain);
    }

    /** A/AAAA rows for the hostname when a hosted zone covers it; removed otherwise. */
    private static void reconcileGeneratedDns(int previewId, @NonNull String hostname) {
        var records = Models.get(DnsRecordModel.class);
        List<Row> owned = generatedDnsOf(previewId);
        Row zone = GameDomains.zoneFor(hostname);
        Row server = Models.get(ServerModel.class).findById(ServerModel.localServerId());
        String v4 = server != null ? str(server.get(ServerModel.PUBLIC_IPV4)) : "";
        String v6 = server != null ? str(server.get(ServerModel.PUBLIC_IPV6)) : "";
        Set<Integer> touchedZones = new LinkedHashSet<>();
        for (Row row : owned) {
            touchedZones.add(row.get(DnsRecordModel.ZONE_ID));
            records.delete(row.get(DnsRecordModel.ID));
        }
        if (zone != null && (!v4.isEmpty() || !v6.isEmpty())) {
            int zoneId = zone.get(DnsZoneModel.ID);
            String relative = DnsNames.relative(zone.get(DnsZoneModel.ORIGIN), hostname);
            if (relative != null) {
                if (!v4.isEmpty()) {
                    writeDnsRow(records, zoneId, relative, DnsRecordModel.TYPE_A, v4);
                }
                if (!v6.isEmpty()) {
                    writeDnsRow(records, zoneId, relative, DnsRecordModel.TYPE_AAAA, v6);
                }
                touchedZones.add(zoneId);
            }
        }
        for (Integer zoneId : touchedZones) {
            if (zoneId != null) {
                DnsZoneStore.INSTANCE.bumpSerialAndReload(zoneId);
            }
        }
    }

    private static void writeDnsRow(@NonNull DnsRecordModel records, int zoneId,
                                    @NonNull String name, @NonNull String type,
                                    @NonNull String value) {
        Row record = records.createEmptyRow();
        record.set(DnsRecordModel.ZONE_ID, zoneId);
        record.set(DnsRecordModel.NAME, name);
        record.set(DnsRecordModel.TYPE, type);
        record.set(DnsRecordModel.VALUE, value);
        record.set(DnsRecordModel.ENABLED, true);
        records.save(record);
    }

    private static void removeGeneratedDomains(int previewId) {
        var domains = Models.get(SiteDomainModel.class);
        for (Row row : generatedDomainsOf(previewId)) {
            domains.delete(row.get(SiteDomainModel.ID));
        }
    }

    private static void removeGeneratedDns(int previewId) {
        var records = Models.get(DnsRecordModel.class);
        Set<Integer> zones = new LinkedHashSet<>();
        for (Row row : generatedDnsOf(previewId)) {
            zones.add(row.get(DnsRecordModel.ZONE_ID));
            records.delete(row.get(DnsRecordModel.ID));
        }
        for (Integer zoneId : zones) {
            if (zoneId != null) {
                DnsZoneStore.INSTANCE.bumpSerialAndReload(zoneId);
            }
        }
    }

    /** Rows carrying EXACTLY this preview's attribution; nothing else is ever touched. */
    private static @NonNull List<Row> generatedDomainsOf(int previewId) {
        return Models.get(SiteDomainModel.class).find()
            .where(SiteDomainModel.GENERATED_BY.eq(PreviewDomains.SOURCE))
            .where(SiteDomainModel.GENERATED_FOR_MODEL.eq(
                PreviewDeploymentModel.MODEL_ID.toString()))
            .where(SiteDomainModel.GENERATED_FOR_ID.eq(previewId))
            .all();
    }

    private static @NonNull List<Row> generatedDnsOf(int previewId) {
        return Models.get(DnsRecordModel.class).find()
            .where(DnsRecordModel.GENERATED_BY.eq(PreviewDomains.SOURCE))
            .where(DnsRecordModel.GENERATED_FOR_MODEL.eq(
                PreviewDeploymentModel.MODEL_ID.toString()))
            .where(DnsRecordModel.GENERATED_FOR_ID.eq(previewId))
            .all();
    }

    /**
     * Deterministic generated hostname: {@code <site-slug>--<ref-slug>.<base>}. The
     * double hyphen cannot appear in either slug (single hyphens only), so two
     * different (site, ref) pairs compose to distinct labels -- but a DNS label caps at
     * 63 chars, and plain truncation made every pair sharing a 63-char prefix collide
     * (and a 63+-char site slug truncated the ref away entirely): a cross-tenant denial
     * of preview creation, since refuseRouteConflicts refuses the second row. An
     * over-long label therefore keeps a truncated prefix plus a digest of the FULL
     * composed label, so distinctness survives truncation and the derivation stays a
     * pure function.
     */
    public static @NonNull String hostnameFor(@NonNull String siteSlug, @NonNull String ref,
                                              @NonNull String baseDomain) {
        String label = labelOf(siteSlug) + "--" + labelOf(ref);
        if (label.length() > 63) {
            String digest = SecureTokens.sha256Hex(label).substring(0, 8);
            String prefix = label.substring(0, 63 - 1 - digest.length());
            while (prefix.endsWith("-")) {
                prefix = prefix.substring(0, prefix.length() - 1);
            }
            label = prefix + "-" + digest;
        }
        return label + "." + baseDomain.toLowerCase(Locale.ROOT);
    }

    private static @NonNull String labelOf(@NonNull String value) {
        String label = value.toLowerCase(Locale.ROOT)
            .replaceAll("[^a-z0-9]+", "-")
            .replaceAll("^-+|-+$", "");
        return label.isEmpty() ? "x" : label;
    }

    private static @NonNull Instant expiry() {
        Integer minutes = HohenheimSettings.VALUES.getValue(
            HohenheimSettings.Previews.LIFETIME_MINUTES);
        long effective = minutes != null && minutes > 0 ? minutes : 1440;
        return Instant.now().plusSeconds(effective * 60);
    }

    private static @NonNull String healthPath(@NonNull Map<String, Object> siteSettings) {
        String path = str(siteSettings.get("health_path"));
        return path.isEmpty() ? "/" : path;
    }


    private static void reloadProxy() {
        ProxyServer proxy = ServerMain.getProxyServer();
        if (proxy != null) {
            proxy.reload();
        }
    }

    private interface ScopedWork {
        void run() throws Exception;
    }

    private static void inScope(int previewId, @NonNull ScopedWork work) throws Exception {
        GeneratedRows.as(new GeneratedRows.Attribution(PreviewDomains.SOURCE,
            PreviewDeploymentModel.MODEL_ID.toString(), previewId), work::run);
    }

    private static @NonNull Object lockFor(int applicationId, @NonNull String ref) {
        return LOCKS.computeIfAbsent(applicationId + "\n" + ref, ignored -> new Object());
    }

    private static @NonNull Microcopy violation(@NonNull String key) {
        return Microcopy.of(key).withFilter("scope", "violations");
    }

    @SuppressWarnings("unchecked")
    private static @NonNull Map<String, Object> castMap(@Nullable Object value) {
        return value instanceof Map<?, ?> ? (Map<String, Object>) value : Map.of();
    }

    private static int intOf(@Nullable Object value) {
        return value instanceof Number number ? number.intValue() : -1;
    }

    private static @NonNull String reasonOf(@NonNull Throwable e) {
        return e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
    }

    private static @NonNull String str(@Nullable Object value) {
        return value == null ? "" : value.toString().trim();
    }
}
