package be.elevenways.hohenheim.server.api;

import be.elevenways.hohenheim.HohenheimEndpoints;
import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.hohenheim.model.ArtifactOperationModel;
import be.elevenways.hohenheim.model.BuildOperationModel;
import be.elevenways.hohenheim.model.EnvironmentModel;
import be.elevenways.hohenheim.model.InstanceVariableModel;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.server.HandlerSupport;
import be.elevenways.hohenheim.server.instance.DeployTrigger;
import be.elevenways.hohenheim.model.ProjectModel;
import be.elevenways.hohenheim.model.ReleaseOperationModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.server.ServerMain;
import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.hohenheim.server.application.ApplicationDeploys;
import be.elevenways.hohenheim.server.application.ApplicationReleases;
import be.elevenways.hohenheim.server.application.ArtifactDeploys;
import be.elevenways.hohenheim.server.application.ReleaseEngine;
import be.elevenways.hohenheim.server.instance.InstanceKinds;
import be.elevenways.hohenheim.server.instance.InstanceApi;
import be.elevenways.hohenheim.server.instance.InstanceService;
import be.elevenways.hohenheim.server.instance.InstanceVariables;
import be.elevenways.hohenheim.server.project.Projects;
import be.elevenways.hohenheim.server.upstream.kinds.InstanceUpstreamKind;
import be.elevenways.protoblast.common.util.BlastString;
import be.elevenways.zenit.common.conduit.Conduit;
import be.elevenways.zenit.common.orm.activity.ActivityLog;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.orm.query.SortOrder;
import be.elevenways.zenit.common.orm.query.criteria.Criteria;
import be.elevenways.zenit.common.result.ActionResult;
import be.elevenways.zenit.common.routing.ParameterDefinition;
import be.elevenways.zenit.common.routing.RequestBodyTooLargeException;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.common.validation.Violations;
import be.elevenways.zenit.server.http.HttpConduit;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.ArrayList;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * The PaaS automation API (v1): projects/environments, sites, deploy/rollback, the
 * three operation-record lanes (git deployments, releases, sandbox builds) and the
 * variable mechanism -- the operator seam Phase 7 names, over machinery that all
 * exists already. The instance lane's three rules apply verbatim (no authorization
 * decisions of its own, no existence oracle, no field that was not enumerated); see
 * {@link be.elevenways.hohenheim.server.instance.InstanceApi}.
 *
 * AIDEV-NOTE: deploy and rollback deliberately demand no server-side confirmation
 * phrase. The product's ConfirmationSpec is a CLIENT interlock (the phrase never
 * reaches the server -- InstanceInstalls spells this out), so a server-side phrase
 * here would be security theater: a stricter-looking gate the HTML surface does not
 * have, trivially scripted around by every caller. The explicit POST is the API's
 * confirmation; the CLI owns the human interlock, exactly as the dialog does.
 */
public final class PaasApi {

    private PaasApi() {
    }

    public static void init() {
        initProjects();
        initSites();
        initOperations();
        initEnvironmentVariables();
        SiteApi.init();
        AccessListApi.init();
    }

    // -- projects -------------------------------------------------------------

    private static void initProjects() {
        HohenheimEndpoints.API_PROJECTS.setHandler(conduit -> {
            AccessContext ctx = ApiConduits.requireKey(conduit);
            if (ctx == null) {
                return null;
            }
            List<Map<String, Object>> projects = new ArrayList<>();
            for (Row project : Projects.visibleTo(ctx)) {
                projects.add(projectProjection(project));
            }
            return ApiConduits.json(Map.of("projects", projects));
        });

        HohenheimEndpoints.API_PROJECT.setHandler(conduit -> {
            AccessContext ctx = ApiConduits.requireKey(conduit);
            if (ctx == null) {
                return null;
            }
            Integer projectId = conduit.getParameter(HohenheimEndpoints.PROJECT_ID);
            for (Row project : Projects.visibleTo(ctx)) {
                if (project.get(ProjectModel.ID).equals(projectId)) {
                    return ApiConduits.json(projectProjection(project));
                }
            }
            conduit.notFound();
            return null;
        });
    }

    private static @NonNull Map<String, Object> projectProjection(@NonNull Row project) {
        Map<String, Object> entry = new LinkedHashMap<>();
        Integer projectId = project.get(ProjectModel.ID);
        entry.put("id", projectId);
        entry.put("name", project.get(ProjectModel.NAME));
        entry.put("description", stringOrEmpty(project.get(ProjectModel.DESCRIPTION)));
        entry.put("created_at", String.valueOf((Object) project.get(ProjectModel.CREATED_AT)));
        List<Map<String, Object>> environments = new ArrayList<>();
        for (Row environment : Models.get(EnvironmentModel.class).find()
                .where(EnvironmentModel.PROJECT_ID.eq(projectId))
                .orderBy(EnvironmentModel.ID, SortOrder.ASC).all()) {
            Map<String, Object> env = new LinkedHashMap<>();
            env.put("id", environment.get(EnvironmentModel.ID));
            env.put("name", environment.get(EnvironmentModel.NAME));
            env.put("description", stringOrEmpty(environment.get(EnvironmentModel.DESCRIPTION)));
            environments.add(env);
        }
        entry.put("environments", environments);
        return entry;
    }

    // -- sites ----------------------------------------------------------------

    private static void initSites() {
        HohenheimEndpoints.API_V1_SITES.setHandler(conduit -> {
            AccessContext ctx = ApiConduits.requireKey(conduit);
            if (ctx == null) {
                return null;
            }
            List<Map<String, Object>> sites = new ArrayList<>();
            for (Row site : visibleSites(ctx)) {
                sites.add(siteProjection(site, false));
            }
            return ApiConduits.json(Map.of("sites", sites));
        });

        HohenheimEndpoints.API_V1_SITE.setHandler(conduit -> {
            AccessContext ctx = ApiConduits.requireKey(conduit);
            if (ctx == null) {
                return null;
            }
            Row site = visibleSite(conduit, ctx);
            if (site == null) {
                return null;
            }
            return ApiConduits.json(siteProjection(site, true));
        });

        HohenheimEndpoints.API_V1_SITE_DEPLOY.setHandler(conduit -> {
            AccessContext ctx = ApiConduits.requireKey(conduit);
            if (ctx == null) {
                return null;
            }
            Row site = visibleSite(conduit, ctx);
            if (site == null) {
                return null;
            }
            Integer siteId = site.get(SiteModel.ID);
            Integer applicationId = applicationIdOf(site);
            if (applicationId == null) {
                // Only a site that exposes an application has anything to deploy.
                return ApiConduits.refusal(conduit, Violations.ofForm(
                    ApiConduits.violationText("deploy_not_available")));
            }
            return queueDeploy(conduit, applicationId, Map.of("id", siteId, "status", "queued"));
        });

        HohenheimEndpoints.API_V1_SITE_ARTIFACT.setHandler(conduit -> {
            AccessContext ctx = ApiConduits.requireKey(conduit);
            if (ctx == null) return null;
            Row site = visibleSite(conduit, ctx);
            if (site == null) return null;
            Integer applicationId = artifactApplication(conduit, ctx, site);
            if (applicationId == null) return null;
            if (!(conduit instanceof HttpConduit http)) {
                return ApiConduits.refusal(conduit, Violations.ofForm(
                    ApiConduits.violationText("artifact_upload_failed")));
            }
            // AIDEV-NOTE: the deploy admission (power on a tenant-originated call, every
            // attached database ready) runs HERE, on the request thread and before a byte of
            // the body is accepted. ArtifactDeploys.run asks it again in the background, but
            // there no tenant identity survives, so that check passes for everybody -- the
            // same hole ApplicationDeploys.deployInBackground closes for the git lane.
            try {
                requireReleaseAuthority(applicationId);
            } catch (Violations refused) {
                return ApiConduits.refusal(conduit, refused);
            }
            Path upload = null;
            boolean handedOff = false;
            Row operation = null;
            try {
                upload = ArtifactDeploys.uploadPathFor(applicationId);
                if (http.streamBodyTo(upload, maxUploadBytes()) == 0) {
                    return ApiConduits.refusal(conduit, Violations.ofForm(
                        ApiConduits.violationText("artifact_upload_empty")));
                }
                // Reauthorize after a long upload too: grants/site target may have changed.
                Row currentSite = visibleSite(conduit, AccessContext.of(conduit));
                if (currentSite == null) return null;
                Integer currentApp = artifactApplication(conduit, AccessContext.of(conduit), currentSite);
                if (currentApp == null) return null;
                if (!applicationId.equals(currentApp)) {
                    conduit.notFound();
                    return null;
                }
                // And the admission again: power or a database can have moved meanwhile.
                requireReleaseAuthority(applicationId);
                operation = ArtifactDeploys.accept(site.get(SiteModel.ID), applicationId, upload);
                int operationId = operation.get(ArtifactOperationModel.ID);
                Path acceptedUpload = upload;
                HandlerSupport.inBackground(() ->
                    ArtifactDeploys.run(operationId, acceptedUpload, DeployTrigger.API));
                handedOff = true;
                conduit.setResponseStatus(202);
                return ApiConduits.json(Map.of("operation_id", operationId, "status", "pending",
                    "artifact_sha256", operation.get(ArtifactOperationModel.ARTIFACT_SHA256)));
            } catch (RequestBodyTooLargeException tooLarge) {
                return ApiConduits.refusal(conduit, Violations.ofForm(
                    ApiConduits.violationText("artifact_too_large")));
            } catch (Violations refused) {
                if (operation != null) ArtifactDeploys.handoffFailed(operation);
                return ApiConduits.refusal(conduit, refused);
            } catch (Exception failed) {
                if (operation != null) ArtifactDeploys.handoffFailed(operation);
                return ApiConduits.refusal(conduit, Violations.ofForm(
                    ApiConduits.violationText("artifact_upload_failed")));
            } finally {
                if (!handedOff) ArtifactDeploys.deleteUpload(upload);
            }
        });

        HohenheimEndpoints.API_V1_SITE_ARTIFACT_OPERATION.setHandler(conduit -> {
            AccessContext ctx = ApiConduits.requireKey(conduit);
            if (ctx == null) return null;
            Row site = visibleSite(conduit, ctx);
            if (site == null) return null;
            Integer applicationId = artifactApplication(conduit, ctx, site);
            if (applicationId == null) return null;
            Integer operationId = conduit.getParameter(HohenheimEndpoints.ARTIFACT_OPERATION_ID);
            Map<String, Object> result = operationId == null ? null
                : ArtifactDeploys.operation(site.get(SiteModel.ID), applicationId, operationId);
            if (result == null) {
                conduit.notFound();
                return null;
            }
            return ApiConduits.json(result);
        });

        HohenheimEndpoints.API_V1_SITE_ARTIFACT_CURRENT.setHandler(conduit -> {
            AccessContext ctx = ApiConduits.requireKey(conduit);
            if (ctx == null) return null;
            Row site = visibleSite(conduit, ctx);
            if (site == null) return null;
            Integer applicationId = artifactApplication(conduit, ctx, site);
            return applicationId == null ? null : ApiConduits.json(ArtifactDeploys.current(applicationId));
        });

        HohenheimEndpoints.API_V1_SITE_ROLLBACK.setHandler(conduit -> {
            AccessContext ctx = ApiConduits.requireKey(conduit);
            if (ctx == null) {
                return null;
            }
            Row site = visibleSite(conduit, ctx);
            if (site == null) {
                return null;
            }
            Integer siteId = site.get(SiteModel.ID);
            // ONE rollback lane now: the health-gated release engine over the retained
            // digest-pinned release, the same call the panel row action makes. The second
            // lane this used to offer (re-point the previous checkout slot) died with the
            // host-slot deploy lane it belonged to.
            Integer applicationId = applicationIdOf(site);
            if (applicationId == null) {
                return ApiConduits.refusal(conduit, Violations.ofForm(
                    ApiConduits.violationText("rollback_not_available")));
            }
            try {
                requireReleaseAuthority(applicationId);
                ReleaseEngine.rollback(applicationId);
            } catch (Violations refused) {
                return ApiConduits.refusal(conduit, refused);
            }
            // ReleaseEngine.rollback records the settled rollback itself; the activity
            // origin column already says "api", so a second record would double-count.
            return ApiConduits.json(Map.of("id", siteId, "status", "rolled_back"));
        });
    }

    /**
     * THE deploy hand-off of both API lanes: the application layer admits the deploy on this
     * request's thread and only then queues it; a refusal is answered as a typed 422.
     *
     * AIDEV-NOTE: the order is load-bearing and lives in
     * {@link ApplicationDeploys#deployInBackground}. The build takes minutes, so the deploy
     * runs on a virtual thread -- and there the request scope is gone, so every tenant gate
     * reads "system work" and passes. The old lane queued first and let a tenant holding
     * only the SITE's manage grant deploy the application behind it.
     *
     * @param accepted the body answered once the deploy is queued (each lane keeps its shape)
     */
    public static @NonNull ActionResult<Object> queueDeploy(@NonNull Conduit conduit, int applicationId,
                                                            @NonNull Map<String, Object> accepted) {
        try {
            ApplicationDeploys.deployInBackground(applicationId, null, DeployTrigger.API);
        } catch (Violations refused) {
            return ApiConduits.refusal(conduit, refused);
        }
        return ApiConduits.json(accepted);
    }

    /**
     * The admission a release verb needs on the APPLICATION, asked on the request thread:
     * {@code power} (the gate the panel's instance row actions ride) and every attached
     * database ready. The site's {@code manage} grant only makes the site visible; it never
     * implies power over the workload the site routes to.
     *
     * @throws Violations {@code instance_not_permitted} or {@code database_not_ready}
     */
    private static void requireReleaseAuthority(int applicationId) {
        InstanceService.requireDeployAdmitted(applicationId);
    }

    /** Executable bytes require application CONFIG as well as the site's independent manage grant. */
    private static @Nullable Integer artifactApplication(Conduit conduit, AccessContext ctx, Row site) {
        Integer applicationId = applicationIdOf(site);
        if (applicationId == null || !ctx.hasCapability(InstanceModel.MODEL_ID,
                applicationId, HohenheimAccess.CONFIG)) {
            conduit.notFound();
            return null;
        }
        return applicationId;
    }

    /**
     * The sites this context may see: admins everything live, everyone else exactly the
     * ones they hold {@code manage} on -- the SAME scoping shape every site-hanging
     * resource rides ({@link HohenheimAccess#managedSiteScope}), so zenit-auth's scope
     * narrowing applies to a capability-scoped key with no code here.
     */
    private static @NonNull List<Row> visibleSites(@NonNull AccessContext ctx) {
        var model = Models.get(SiteModel.class);
        var query = model.find().where(SiteModel.DELETED_AT.isNull());
        Criteria scope = HohenheimAccess.managedSiteScope(ctx, model, SiteModel.ID::in);
        if (scope != null) {
            query.where(scope);
        }
        return query.orderBy(SiteModel.ID, SortOrder.ASC).all();
    }

    /**
     * Resolve the route's site for this context, ending the response with the uniform
     * 404 when it is absent, trashed OR not permitted (never an existence oracle).
     *
     * @return the row, or null when the response has already been ended
     */
    static @Nullable Row visibleSite(@NonNull Conduit conduit,
                                     @NonNull AccessContext ctx) {
        Integer siteId = conduit.getParameter(HohenheimEndpoints.SITE_ID);
        Row site = siteId == null ? null : Models.get(SiteModel.class).find()
            .where(SiteModel.ID.eq(siteId))
            .where(SiteModel.DELETED_AT.isNull())
            .first();
        if (site == null || !HohenheimAccess.canManageSite(ctx, siteId)) {
            conduit.notFound();
            return null;
        }
        return site;
    }

    /**
     * THE enumerated tenant view of a site. A whitelist, never a row dump -- settings
     * (env maps, api keys, webhook secrets) are absent BY NAME.
     */
    static @NonNull Map<String, Object> siteProjection(@NonNull Row site,
                                                       boolean detail) {
        Map<String, Object> entry = new LinkedHashMap<>();
        Integer siteId = site.get(SiteModel.ID);
        entry.put("id", siteId);
        entry.put("name", site.get(SiteModel.NAME));
        entry.put("slug", site.get(SiteModel.SLUG));
        entry.put("type", String.valueOf((Object) site.get(SiteModel.UPSTREAM_KIND)));
        entry.put("enabled", Boolean.TRUE.equals(site.get(SiteModel.ENABLED)));
        var proxy = ServerMain.getProxyServer();
        var handler = proxy != null && siteId != null
            ? proxy.getDispatcher().findHandlerBySiteId(siteId) : null;
        entry.put("health", handler != null
            ? BlastString.lower(handler.getHealth().name()) : "unknown");
        Integer applicationId = applicationIdOf(site);
        if (applicationId != null) {
            entry.put("application_id", applicationId);
            Row serving = ApplicationReleases.ownedServing(applicationId);
            if (serving != null) {
                entry.put("current_commit", stringOrEmpty(
                    ApplicationReleases.storedSettings(serving).get("commit_sha")));
            }
        }
        Row project = siteId == null ? null : Projects.projectOf(SiteModel.MODEL_ID, siteId);
        if (project != null) {
            entry.put("project", Map.of("id", project.get(ProjectModel.ID),
                "name", project.get(ProjectModel.NAME)));
        }
        if (detail && siteId != null) {
            // The hostnames are the one thing a site IS to the proxy; the write lane
            // (SiteApi) answers with the same rows, so a caller can verify what it made.
            entry.put("domains", SiteApi.domainProjections(siteId));
        }
        if (detail && applicationId != null) {
            Row latest = Models.get(ReleaseOperationModel.class)
                .findForOwner(InstanceModel.MODEL_ID.toString(), applicationId, 1)
                .stream().findFirst().orElse(null);
            if (latest != null) {
                entry.put("release", releaseProjection(latest, false));
            }
            entry.put("rollback_available",
                ReleaseEngine.newestRetired(applicationId) != null);
        }
        return entry;
    }

    // -- operation records: git deployments, releases, builds ------------------

    private static void initOperations() {
        // AIDEV-NOTE: deployments and releases are the SAME record now (the deleted
        // `deployments` table was the host-slot lane's private history). Both routes stay
        // for their consumers and answer from release_operations through ONE listing; only
        // the envelope key differs.
        HohenheimEndpoints.API_V1_SITE_DEPLOYMENTS.setHandler(conduit ->
            releaseListing(conduit, "deployments"));

        HohenheimEndpoints.API_V1_SITE_DEPLOYMENT_LOG.setHandler(conduit -> {
            Row site = requireVisibleSite(conduit);
            if (site == null) {
                return null;
            }
            Integer applicationId = applicationIdOf(site);
            Row deployment = applicationId == null ? null
                : childRow(conduit, HohenheimEndpoints.DEPLOYMENT_ID,
                    id -> Models.get(ReleaseOperationModel.class).find()
                        .where(ReleaseOperationModel.FOR_MODEL.eq(
                            InstanceModel.MODEL_ID.toString()))
                        .where(ReleaseOperationModel.FOR_ID.eq(applicationId))
                        .where(ReleaseOperationModel.ID.eq(id))
                        .first());
            if (deployment == null) {
                return null;
            }
            return ApiConduits.json(Map.of("id", deployment.get(ReleaseOperationModel.ID),
                "log", stringOrEmpty(deployment.get(ReleaseOperationModel.STEP_LOG))));
        });

        HohenheimEndpoints.API_V1_SITE_RELEASES.setHandler(conduit ->
            releaseListing(conduit, "releases"));

        HohenheimEndpoints.API_V1_SITE_RELEASE.setHandler(conduit -> {
            Row site = requireVisibleSite(conduit);
            if (site == null) {
                return null;
            }
            Integer applicationId = applicationIdOf(site);
            Row release = applicationId == null ? null
                : childRow(conduit, HohenheimEndpoints.RELEASE_ID,
                id -> Models.get(ReleaseOperationModel.class).find()
                    .where(ReleaseOperationModel.FOR_MODEL.eq(InstanceModel.MODEL_ID.toString()))
                    .where(ReleaseOperationModel.FOR_ID.eq(applicationId))
                    .where(ReleaseOperationModel.ID.eq(id))
                    .first());
            if (release == null) {
                return null;
            }
            return ApiConduits.json(releaseProjection(release, true));
        });

        HohenheimEndpoints.API_V1_SITE_BUILDS.setHandler(conduit -> {
            Row site = requireVisibleSite(conduit);
            if (site == null) {
                return null;
            }
            int siteId = site.get(SiteModel.ID);
            List<Map<String, Object>> builds = new ArrayList<>();
            for (Row row : Models.get(BuildOperationModel.class)
                    .findForOwner(SiteModel.MODEL_ID.toString(), siteId, 50)) {
                builds.add(buildProjection(row));
            }
            return ApiConduits.json(Map.of("id", siteId, "builds", builds));
        });

        HohenheimEndpoints.API_V1_SITE_BUILD_LOG.setHandler(conduit -> {
            Row site = requireVisibleSite(conduit);
            if (site == null) {
                return null;
            }
            Row build = childRow(conduit, HohenheimEndpoints.BUILD_ID,
                id -> Models.get(BuildOperationModel.class).find()
                    .where(BuildOperationModel.FOR_MODEL.eq(SiteModel.MODEL_ID.toString()))
                    .where(BuildOperationModel.FOR_ID.eq(site.get(SiteModel.ID)))
                    .where(BuildOperationModel.ID.eq(id))
                    .first());
            if (build == null) {
                return null;
            }
            // BuildCredentials already redacted per-build tokens out of this log at
            // capture time; serving it raw introduces nothing the record does not hold.
            return ApiConduits.json(Map.of("id", build.get(BuildOperationModel.ID),
                "log", stringOrEmpty(build.get(BuildOperationModel.LOG))));
        });
    }

    /**
     * The newest release operations of a visible site's application, under {@code key}.
     *
     * @return the answer, or null when the response has already been ended
     */
    private static @Nullable ActionResult<Object> releaseListing(@NonNull Conduit conduit,
                                                                 @NonNull String key) {
        Row site = requireVisibleSite(conduit);
        if (site == null) {
            return null;
        }
        int siteId = site.get(SiteModel.ID);
        Integer applicationId = applicationIdOf(site);
        List<Map<String, Object>> operations = new ArrayList<>();
        if (applicationId != null) {
            for (Row row : Models.get(ReleaseOperationModel.class)
                    .findForOwner(InstanceModel.MODEL_ID.toString(), applicationId, 50)) {
                operations.add(releaseProjection(row, false));
            }
        }
        return ApiConduits.json(Map.of("id", siteId, key, operations));
    }

    /** requireKey + visibleSite in one step for the operation-record reads. */
    private static @Nullable Row requireVisibleSite(@NonNull Conduit conduit) {
        AccessContext ctx = ApiConduits.requireKey(conduit);
        return ctx == null ? null : visibleSite(conduit, ctx);
    }

    /**
     * A child record of an already-authorized site, 404 when the id names anything
     * else -- a record of ANOTHER site answers exactly like a missing one, so ids
     * stay unenumerable across sites.
     *
     * @return the row, or null when the response has already been ended
     */
    private static @Nullable Row childRow(@NonNull Conduit conduit,
                                          @NonNull ParameterDefinition<Integer> parameter,
                                          @NonNull Function<Integer, @Nullable Row> lookup) {
        Integer id = conduit.getParameter(parameter);
        Row row = id == null ? null : lookup.apply(id);
        if (row == null) {
            conduit.notFound();
            return null;
        }
        return row;
    }

    /**
     * The application a site exposes, or null when it exposes none.
     *
     * AIDEV-NOTE: the API's site endpoints stayed site-addressed on purpose -- an API
     * consumer names the thing it knows, its site -- but every DEPLOY verb now acts on the
     * application behind it. A site with no application refuses by name instead of quietly
     * doing nothing, which is what the dead git-handler lookup did.
     */
    private static @Nullable Integer applicationIdOf(@NonNull Row site) {
        Integer instanceId = site.get(SiteModel.INSTANCE_ID);
        if (instanceId == null) {
            return null;
        }
        Row instance = Models.get(InstanceModel.class).find()
            .where(InstanceModel.ID.eq(instanceId))
            .where(InstanceModel.DELETED_AT.isNull())
            .first();
        return instance != null && InstanceKinds.isReleaseManaged(
            instance.get(InstanceModel.KIND)) ? instanceId : null;
    }

    private static @NonNull Map<String, Object> releaseProjection(@NonNull Row row,
                                                                  boolean withStepLog) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("id", row.get(ReleaseOperationModel.ID));
        entry.put("kind", String.valueOf((Object) row.get(ReleaseOperationModel.KIND)));
        entry.put("status", String.valueOf((Object) row.get(ReleaseOperationModel.STATUS)));
        entry.put("image", stringOrEmpty(row.get(ReleaseOperationModel.IMAGE_ID)));
        entry.put("failure_reason", stringOrEmpty(row.get(ReleaseOperationModel.FAILURE_REASON)));
        entry.put("started_at", String.valueOf((Object) row.get(ReleaseOperationModel.STARTED_AT)));
        entry.put("finished_at", String.valueOf((Object) row.get(ReleaseOperationModel.FINISHED_AT)));
        entry.put("duration_ms", row.get(ReleaseOperationModel.DURATION_MS));
        if (withStepLog) {
            entry.put("step_log", stringOrEmpty(row.get(ReleaseOperationModel.STEP_LOG)));
        }
        return entry;
    }

    private static @NonNull Map<String, Object> buildProjection(@NonNull Row row) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("id", row.get(BuildOperationModel.ID));
        entry.put("builder_kind", String.valueOf((Object) row.get(BuildOperationModel.BUILDER_KIND)));
        entry.put("status", String.valueOf((Object) row.get(BuildOperationModel.STATUS)));
        entry.put("source_ref", stringOrEmpty(row.get(BuildOperationModel.SOURCE_REF)));
        entry.put("image", stringOrEmpty(row.get(BuildOperationModel.IMAGE_ID)));
        entry.put("exit_code", row.get(BuildOperationModel.EXIT_CODE));
        entry.put("failure_reason", stringOrEmpty(row.get(BuildOperationModel.FAILURE_REASON)));
        entry.put("started_at", String.valueOf((Object) row.get(BuildOperationModel.STARTED_AT)));
        entry.put("finished_at", String.valueOf((Object) row.get(BuildOperationModel.FINISHED_AT)));
        entry.put("duration_ms", row.get(BuildOperationModel.DURATION_MS));
        return entry;
    }

    // -- environment variables -------------------------------------------------

    private static void initEnvironmentVariables() {
        HohenheimEndpoints.API_ENVIRONMENT_VARIABLES.setHandler(conduit -> {
            Row environment = visibleEnvironment(conduit);
            if (environment == null) {
                return null;
            }
            int environmentId = environment.get(EnvironmentModel.ID);
            return ApiConduits.json(Map.of("id", environmentId,
                "variables", InstanceApi.variableProjection(
                    Models.get(InstanceVariableModel.class).findByEnvironmentId(environmentId))));
        });

        HohenheimEndpoints.API_ENVIRONMENT_VARIABLE_SET.setHandler(conduit -> {
            Row environment = visibleEnvironment(conduit);
            if (environment == null) {
                return null;
            }
            int environmentId = environment.get(EnvironmentModel.ID);
            String key = ApiConduits.formValue(conduit, "key");
            try {
                new InstanceVariables().setValue(null, environmentId, key,
                    InstanceApi.kindOrDefault(ApiConduits.formValue(conduit, "kind")),
                    ApiConduits.formValue(conduit, "value"));
            } catch (Violations refused) {
                return ApiConduits.refusal(conduit, refused);
            }
            ActivityLog.record(Models.get(EnvironmentModel.class), environmentId,
                "variable_set", key);
            return ApiConduits.json(Map.of("id", environmentId, "status", "set", "key", key));
        });

        HohenheimEndpoints.API_ENVIRONMENT_VARIABLE_DELETE.setHandler(conduit -> {
            Row environment = visibleEnvironment(conduit);
            if (environment == null) {
                return null;
            }
            int environmentId = environment.get(EnvironmentModel.ID);
            String key = ApiConduits.formValue(conduit, "key");
            if (!new InstanceVariables().removeValue(null, environmentId, key)) {
                return ApiConduits.refusal(conduit, Violations.ofField("key", key,
                    ApiConduits.violationText("variable_not_found")));
            }
            ActivityLog.record(Models.get(EnvironmentModel.class), environmentId,
                "variable_deleted", key);
            return ApiConduits.json(Map.of("id", environmentId, "status", "deleted", "key", key));
        });
    }

    /**
     * Resolve the route's environment for this key, uniform 404 otherwise: the caller
     * must hold the very permission the environment editor lives behind
     * ({@code HohenheimPanel.ACCESS}, which is what {@link HohenheimAccess#isAdmin}
     * asks), and the environment must hang off a real project.
     *
     * AIDEV-NOTE: admin-only because the UI is. EnvironmentVariableResource is
     * registered on HohenheimPanel alone, and ManagePanel offers NO environment peer at
     * all -- its project tier is deliberately a read-only projection -- so ANY tenant
     * write here is by construction a wider door than the admin UI, which is the one
     * thing docs/paas-api.md promises this surface never is. The predecessor asked for
     * project membership plus a {@code cap:hohenheim:instance#manage} token, and neither
     * is authority: membership is grant-derived, and a scope token only NARROWS a key,
     * it never grants. An environment value is folded in as the deploy baseline for
     * every instance grouped under it and OVERRIDES that instance's own
     * {@code environment_variables} entry, so the old gate let a member author what a
     * workload runs with on instances it held nothing over. ProjectGuards keeps
     * grouping and ownership equal AT THE INSTANCE WRITE, which is why the ordinary
     * flow never showed this -- but a grant revoked afterwards moves ownership without
     * re-validating the grouping, and that drift is reachable. A per-instance
     * capability walk was rejected as the fix: the affected set is unbounded (an
     * instance can join the environment after the write) and empty for a fresh
     * environment, so it would be vacuous exactly where it needs to bite. Pinned by
     * PaasApiTest.theEnvironmentLaneIsNoWiderThanItsAdminUi.
     *
     * @return the row, or null when the response has already been ended
     */
    private static @Nullable Row visibleEnvironment(@NonNull Conduit conduit) {
        AccessContext ctx = ApiConduits.requireKey(conduit);
        if (ctx == null) {
            return null;
        }
        Integer environmentId = conduit.getParameter(HohenheimEndpoints.ENVIRONMENT_ID);
        Row environment = environmentId == null ? null
            : Models.get(EnvironmentModel.class).findById(environmentId);
        Integer projectId = environment == null ? null
            : environment.get(EnvironmentModel.PROJECT_ID);
        Row project = projectId == null ? null
            : Models.get(ProjectModel.class).findById(projectId);
        if (project == null || !HohenheimAccess.isAdmin(ctx)) {
            conduit.notFound();
            return null;
        }
        return environment;
    }

    // -- plumbing -------------------------------------------------------------

    private static @NonNull String stringOrEmpty(@Nullable Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    /** The upload cap in bytes; a DISK guard, since the body never enters the heap. */
    private static long maxUploadBytes() {
        Integer mb = HohenheimSettings.VALUES.getValue(HohenheimSettings.Builds.MAX_UPLOAD_MB);
        return (mb == null || mb < 1 ? 512L : mb.longValue()) * 1024L * 1024L;
    }


}
