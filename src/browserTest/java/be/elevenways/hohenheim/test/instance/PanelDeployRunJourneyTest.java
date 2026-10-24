package be.elevenways.hohenheim.test.instance;

import be.elevenways.hohenheim.HohenheimCapabilities;
import be.elevenways.hohenheim.HohenheimEndpoints;
import be.elevenways.hohenheim.HohenheimSlugs;
import be.elevenways.hohenheim.instance.InstanceKindFields;
import be.elevenways.hohenheim.instance.InstanceOperations;
import be.elevenways.hohenheim.model.BuildOperationModel;
import be.elevenways.hohenheim.model.DeployTrigger;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.OperationStatus;
import be.elevenways.hohenheim.model.StoredRows;
import be.elevenways.hohenheim.model.ReleaseOperationModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.server.application.ApplicationReleases;
import be.elevenways.hohenheim.server.build.SandboxedBuilds;
import be.elevenways.hohenheim.server.docker.ReleaseKind;
import be.elevenways.hohenheim.server.instance.ApplicationKind;
import be.elevenways.hohenheim.server.instance.InstanceOperationHandlers;
import be.elevenways.hohenheim.server.orm.GeneratedRows;
import be.elevenways.hohenheim.site.SiteOperations;
import be.elevenways.hohenheim.test.ApiSupport;
import be.elevenways.hohenheim.test.HardDeletes;
import be.elevenways.hohenheim.test.HohenheimTestBase;
import be.elevenways.hohenheim.test.ProxyTestSupport;
import be.elevenways.hohenheim.test.TenantConduits;
import be.elevenways.zenit.auth.model.GrantSubjectType;
import be.elevenways.zenit.auth.model.UserModel;
import be.elevenways.zenit.auth.model.UserPrincipal;
import be.elevenways.zenit.auth.server.RecordGrants;
import be.elevenways.zenit.cms.common.action.CmsPlacementSurface;
import be.elevenways.zenit.cms.common.page.CmsEndpoints;
import be.elevenways.zenit.cms.common.page.CmsRoutes;
import be.elevenways.zenit.cms.common.render.action.CmsConfirmation;
import be.elevenways.zenit.common.operation.Operation;
import be.elevenways.zenit.common.operation.OperationRunStatus;
import be.elevenways.zenit.common.operation.ZenitPlacementSurface;
import be.elevenways.zenit.common.orm.datasource.Datasources;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.orm.datasource.sql.SqlDatasource;
import be.elevenways.zenit.common.refusal.DomainRefusal;
import be.elevenways.zenit.common.refusal.ZenitRefusalReason;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.server.operation.OperationPipeline;
import be.elevenways.zenit.server.operation.OperationRequest;
import be.elevenways.zenit.server.operation.OperationRun;
import be.elevenways.zenit.server.operation.OperationRuns;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * Every deploy and rollback a person starts from the panel is a background run: the instance's Deploy now and the
 * site's rollback answer at once with the run's page, refuse a cheap case before any run row exists, end on their
 * own, open the matching page once finished, record the deploy as a person's, and spend the one hh_deploy budget the
 * application's rollback spends. No handler holds a request for a bounded settle window any more, and a build a dead
 * controller left running refuses no deploy once the boot settled it.
 *
 * @author Jelle De Loecker
 * @since  0.10.0
 */
class PanelDeployRunJourneyTest extends HohenheimTestBase {

    private static final String PREFIX = "paneldeploy-";

    @Test
    void panelDeploysAndRollbacksAreRunsThatRefuseFirstAndShareOneBudgetJourney() throws Exception {
        int app = application(PREFIX + "app");
        UUID limitedId = ApiSupport.user(PREFIX + "limited@surface.test", "Panel Deploy Limited");
        AccessContext operator = operator();
        List<Integer> operationIds = new ArrayList<>();
        List<Integer> releaseIds = new ArrayList<>();
        Integer siteId = null;
        try {
            // 1. No Hohenheim source holds a request for a settle window: the in-request lane is gone.
            assertThat(sourcesNaming("settleWithin", "SETTLE_WINDOW"))
                .as("step 1: nothing calls or declares the settle window").isEmpty();

            // 2. A deploy already under way refuses the next one before any run starts: the operation's availability
            //    answers, so no run row and no release or build is minted.
            Row inFlight = Models.get(ReleaseOperationModel.class).createEmptyRow();
            inFlight.set(ReleaseOperationModel.KIND, ReleaseOperationModel.KIND_RELEASE);
            inFlight.set(ReleaseOperationModel.FOR_MODEL, InstanceModel.MODEL_ID.toString());
            inFlight.set(ReleaseOperationModel.FOR_ID, app);
            inFlight.set(ReleaseOperationModel.STATUS,
                ReleaseOperationModel.LIFECYCLE.stored(OperationStatus.SWITCHING));
            inFlight.set(ReleaseOperationModel.STARTED_AT, Instant.parse("2026-10-01T08:00:00Z"));
            Models.get(ReleaseOperationModel.class).save(inFlight);
            operationIds.add(inFlight.get(ReleaseOperationModel.ID));
            HttpResponse<String> refused = adminPostForm(deployPath(app), tabBody());
            assertThat(refused.statusCode()).as("step 2: refused as unavailable, the framework's answer")
                .isEqualTo(409);
            assertThat(OperationRuns.recentFor(InstanceOperations.INSTANCE, String.valueOf(app), 1, operator))
                .as("step 2: and no run was started").isEmpty();
            assertThat(operationsOf(app)).as("step 2: nor a release minted").isEqualTo(1);
            assertThat(buildsOf(app)).as("step 2: nor a build").isZero();

            // 3. The deploy and the rollback spend ONE budget per caller, refused ones included: five deploys and five
            //    rollbacks fill it, and the eleventh asking of either is refused as rate-limited.
            assertThat(InstanceOperations.DEPLOY.rateLimit()).as("step 3: the deploy spends hh_deploy")
                .isSameAs(HohenheimEndpoints.DEPLOY_LIMIT)
                .isSameAs(InstanceOperations.ROLLBACK.rateLimit())
                .isSameAs(SiteOperations.ROLLBACK_RELEASE.rateLimit());
            for (String capability : List.of(HohenheimCapabilities.VIEW, HohenheimCapabilities.POWER)) {
                RecordGrants.grant(GrantSubjectType.USER, limitedId, InstanceModel.MODEL_ID, app, capability, true);
            }
            AccessContext limited = AccessContext.of(TenantConduits.stubFor(
                new UserPrincipal(limitedId, "Panel Deploy Limited")));
            Row appRow = Models.get(InstanceModel.class).findById(app);
            for (int attempt = 1; attempt <= 10; attempt++) {
                Operation<Row, Void, Void> operation = attempt % 2 == 0
                    ? InstanceOperations.ROLLBACK : InstanceOperations.DEPLOY;
                Throwable refusal = catchThrowable(() -> invoke(operation, limited, appRow));
                assertThat(refusal).as("step 3: asking %d is refused", attempt).isInstanceOf(DomainRefusal.class);
                assertThat(((DomainRefusal) refusal).reason()).as("step 3: asking %d is within the budget", attempt)
                    .isEqualTo(ZenitRefusalReason.OPERATION_UNAVAILABLE);
            }
            Throwable eleventh = catchThrowable(() -> invoke(InstanceOperations.DEPLOY, limited, appRow));
            assertThat(eleventh).as("step 3: the eleventh within the minute is refused")
                .isInstanceOf(DomainRefusal.class);
            assertThat(((DomainRefusal) eleventh).reason()).as("step 3: with the rate-limit refusal")
                .isEqualTo(ZenitRefusalReason.RATE_LIMITED);

            // 4. Once nothing runs, Deploy now answers at once with its run's page, the run ends on its own, and the
            //    finished run opens the Deploys tab. A person's panel deploy records MANUAL.
            Models.get(ReleaseOperationModel.class).delete(operationIds.remove(0));
            assertThat(InstanceOperationHandlers.triggerOf(CmsPlacementSurface.ADMIN_ACTION))
                .as("step 4: a panel deploy is a person's").isEqualTo(DeployTrigger.MANUAL);
            HttpResponse<String> started = adminPostForm(deployPath(app), tabBody());
            assertThat(started.statusCode()).as("step 4: answered at once, as a redirect").isIn(302, 303);
            List<OperationRun> runs = OperationRuns.recentFor(InstanceOperations.INSTANCE, String.valueOf(app), 1,
                operator);
            assertThat(runs).as("step 4: a run was started for the application").hasSize(1);
            long runId = runs.get(0).id();
            assertThat(landingOf(started)).as("step 4: to that run's page")
                .endsWith(CmsRoutes.operationRun(HohenheimSlugs.ADMIN, HohenheimSlugs.INSTANCES, runId).toUrl());
            assertThat(PutOnlineJourneyTest.ended(runId, operator, "step 4").status())
                .as("step 4: and it ended on its own").isNotEqualTo(OperationRunStatus.RUNNING);
            assertThat(adminGet(landingOf(started)).body()).as("step 4: the finished run opens the Deploys tab")
                .contains("/admin/instances/" + app + "/page/" + HohenheimSlugs.Tab.DEPLOYMENTS);
            assertThat(Models.get(ReleaseOperationModel.class).find()
                    .where(ReleaseOperationModel.FOR_MODEL.eq(InstanceModel.MODEL_ID.toString()))
                    .where(ReleaseOperationModel.FOR_ID.eq(app)).all())
                .as("step 4: whatever the run released, it recorded a person's deploy")
                .allSatisfy(operation -> assertThat((Object) operation.get(ReleaseOperationModel.TRIGGER))
                    .isEqualTo(DeployTrigger.MANUAL.word()));

            // 5. The site's rollback refuses a site whose application keeps nothing, before any run starts.
            siteId = ProxyTestSupport.setupInstanceSite("Site " + PREFIX, PREFIX + "site", app).get(SiteModel.ID);
            HttpResponse<String> nothingKept = adminPostForm(siteRollbackPath(siteId), confirmedBody());
            assertThat(nothingKept.statusCode()).as("step 5: refused as unavailable").isEqualTo(409);
            assertThat(OperationRuns.recentFor(SiteOperations.SITE, String.valueOf(siteId), 1, operator))
                .as("step 5: and no run was started").isEmpty();

            // 5b. Who may roll a site back is the operation's own gate, installation administration: a caller holding
            //     power on the application but no admin grant is refused at admission, before its budget is spent
            //     (step 3 emptied it) and before any run, on a surface that is not the admin panel's.
            assertThat(SiteOperations.ROLLBACK_RELEASE.gate().admits(limited))
                .as("step 5b: the declared gate does not admit a non-admin").isFalse();
            assertThat(SiteOperations.ROLLBACK_RELEASE.gate().admits(operator))
                .as("step 5b: and admits the operator").isTrue();
            Row siteRow = Models.get(SiteModel.class).findById(siteId);
            Throwable notAdmin = catchThrowable(() -> invoke(SiteOperations.ROLLBACK_RELEASE, limited, siteRow));
            assertThat(notAdmin).as("step 5b: a non-admin's rollback is refused").isInstanceOf(DomainRefusal.class);
            assertThat(((DomainRefusal) notAdmin).reason()).as("step 5b: as forbidden at admission, not rate-limited")
                .isEqualTo(ZenitRefusalReason.FORBIDDEN);
            assertThat(OperationRuns.recentFor(SiteOperations.SITE, String.valueOf(siteId), 1, operator))
                .as("step 5b: and no run was started").isEmpty();

            // 6. With a release kept, it answers at once with its run's page; the run ends on its own and, finished,
            //    opens the site.
            releaseIds.add(release(app, "kept", InstanceModel.ROLE_RETIRED));
            releaseIds.add(release(app, "live", InstanceModel.ROLE_SERVING));
            HttpResponse<String> rolledBack = adminPostForm(siteRollbackPath(siteId), confirmedBody());
            assertThat(rolledBack.statusCode()).as("step 6: answered at once, as a redirect").isIn(302, 303);
            List<OperationRun> siteRuns = OperationRuns.recentFor(SiteOperations.SITE, String.valueOf(siteId), 1,
                operator);
            assertThat(siteRuns).as("step 6: a run was started for the site").hasSize(1);
            long siteRunId = siteRuns.get(0).id();
            assertThat(landingOf(rolledBack)).as("step 6: to that run's page")
                .endsWith(CmsRoutes.operationRun(HohenheimSlugs.ADMIN, HohenheimSlugs.SITES, siteRunId).toUrl());
            assertThat(PutOnlineJourneyTest.ended(siteRunId, operator, "step 6").status())
                .as("step 6: and it ended on its own").isNotEqualTo(OperationRunStatus.RUNNING);
            assertThat(adminGet(landingOf(rolledBack)).body()).as("step 6: the finished run opens the site")
                .contains("/admin/sites/" + siteId);
        } finally {
            for (Integer id : operationIds) {
                Models.get(ReleaseOperationModel.class).delete(id);
            }
            Models.get(ReleaseOperationModel.class).find()
                .where(ReleaseOperationModel.FOR_MODEL.eq(InstanceModel.MODEL_ID.toString()))
                .where(ReleaseOperationModel.FOR_ID.eq(app)).all()
                .forEach(operation -> Models.get(ReleaseOperationModel.class).delete(operation));
            Models.get(BuildOperationModel.class).find()
                .where(BuildOperationModel.FOR_MODEL.eq(InstanceModel.MODEL_ID.toString()))
                .where(BuildOperationModel.FOR_ID.eq(app)).all()
                .forEach(build -> Models.get(BuildOperationModel.class).delete(build));
            GeneratedRows.as(new GeneratedRows.Attribution(ApplicationReleases.SOURCE,
                InstanceModel.MODEL_ID.toString(), app), () -> {
                for (Row generated : Models.get(InstanceModel.class).find()
                        .where(InstanceModel.GENERATED_FOR_MODEL.eq(InstanceModel.MODEL_ID.toString()))
                        .where(InstanceModel.GENERATED_FOR_ID.eq(app)).all()) {
                    HardDeletes.byId(Models.get(InstanceModel.class), generated.get(InstanceModel.ID));
                }
            });
            if (siteId != null) {
                HardDeletes.byId(Models.get(SiteModel.class), siteId);
            }
            HardDeletes.byId(Models.get(InstanceModel.class), app);
            Models.get(UserModel.class).delete(limitedId);
        }
    }

    /**
     * A RUNNING build row is trusted as a live build only because boot settles the ones a dead controller left: one
     * written before this process started is stamped interrupted and stops refusing the instance's Deploy, while one
     * this process writes stays running and keeps refusing.
     */
    @Test
    void aBuildLeftRunningByAPreviousProcessIsSettledAtBootAndRefusesNoDeploy() {
        int stale = application(PREFIX + "stale");
        int live = application(PREFIX + "live");
        AccessContext operator = operator();
        try {
            // 1. One build a dead controller left running (written before this process started) and one this process
            //    runs now: both read as a deploy under way, so Deploy is refused on both.
            int staleBuild = runningBuild(stale);
            ((SqlDatasource) Datasources.getDefault()).rawUpdate("UPDATE build_operations"
                + " SET created_at = '2000-01-01T00:00:00Z', updated_at = '2000-01-01T00:00:00Z' WHERE id = "
                + staleBuild);
            int liveBuild = runningBuild(live);
            assertThat(ApplicationReleases.deployingAmong(List.of(stale, live)))
                .as("step 1: both running rows read as a deploy under way").containsExactlyInAnyOrder(stale, live);
            assertThat(deployOffer(operator, stale)).as("step 1: the stale row refuses Deploy")
                .isInstanceOf(OperationPipeline.Offer.Unavailable.class);

            // 2. The boot settle stamps the previous process's build interrupted, with its reason and its end; this
            //    process's own build is left running.
            SandboxedBuilds.recoverInterrupted();
            Row settled = StoredRows.byId(Models.get(BuildOperationModel.class), staleBuild);
            assertThat((String) settled.get(BuildOperationModel.STATUS)).as("step 2: settled as interrupted")
                .isEqualTo(BuildOperationModel.LIFECYCLE.stored(OperationStatus.INTERRUPTED));
            assertThat((String) settled.get(BuildOperationModel.FAILURE_REASON)).as("step 2: saying why")
                .contains("controller restart");
            assertThat(settled.get(BuildOperationModel.FINISHED_AT)).as("step 2: and when it ended").isNotNull();
            assertThat((String) StoredRows.byId(Models.get(BuildOperationModel.class), liveBuild)
                    .get(BuildOperationModel.STATUS)).as("step 2: this process's build is untouched")
                .isEqualTo(BuildOperationModel.LIFECYCLE.stored(OperationStatus.RUNNING));

            // 3. The settled application's Deploy is offered again; the live build still refuses its own.
            assertThat(ApplicationReleases.deployingAmong(List.of(stale, live)))
                .as("step 3: only the live build is a deploy under way").containsExactly(live);
            assertThat(deployOffer(operator, stale)).as("step 3: Deploy stands again where the corpse was")
                .isInstanceOf(OperationPipeline.Offer.Available.class);
            assertThat(deployOffer(operator, live)).as("step 3: and stays refused under the live build")
                .isInstanceOfSatisfying(OperationPipeline.Offer.Unavailable.class, refused ->
                    assertThat(refused.reason().key()).isEqualTo("deploy_in_progress"));
        } finally {
            for (int app : List.of(stale, live)) {
                Models.get(BuildOperationModel.class).find()
                    .where(BuildOperationModel.FOR_MODEL.eq(InstanceModel.MODEL_ID.toString()))
                    .where(BuildOperationModel.FOR_ID.eq(app)).all()
                    .forEach(build -> Models.get(BuildOperationModel.class).delete(build));
                HardDeletes.byId(Models.get(InstanceModel.class), app);
            }
        }
    }

    /** The suite's admin, signed in as a person. */
    private static AccessContext operator() {
        Row admin = Models.get(UserModel.class).find().where(UserModel.EMAIL.eq(TenantConduits.OPERATOR_EMAIL))
            .first();
        return AccessContext.of(TenantConduits.stubFor(new UserPrincipal(admin.get(UserModel.ID),
            "Panel Deploy Admin")));
    }

    /** @return the id of a build of the application written running, as a build's own start writes it */
    private static int runningBuild(int applicationId) {
        Row build = Models.get(BuildOperationModel.class).createEmptyRow();
        build.set(BuildOperationModel.BUILDER_KIND, BuildOperationModel.KIND_DOCKERFILE);
        build.set(BuildOperationModel.FOR_MODEL, InstanceModel.MODEL_ID.toString());
        build.set(BuildOperationModel.FOR_ID, applicationId);
        build.set(BuildOperationModel.STATUS, BuildOperationModel.LIFECYCLE.stored(OperationStatus.RUNNING));
        build.set(BuildOperationModel.STARTED_AT, Instant.parse("2026-10-01T08:00:00Z"));
        Models.get(BuildOperationModel.class).save(build);
        return build.get(BuildOperationModel.ID);
    }

    /** @return what the operator is offered of the application's Deploy right now */
    private static OperationPipeline.Offer deployOffer(AccessContext operator, int applicationId) {
        return OperationPipeline.offer(InstanceOperations.DEPLOY, operator,
            Models.get(InstanceModel.class).findById(applicationId));
    }

    /** @return the Hohenheim main sources whose text names any of {@code tokens} */
    private static List<Path> sourcesNaming(String... tokens) throws IOException {
        List<Path> naming = new ArrayList<>();
        for (Path root : List.of(Path.of("src/server/java"), Path.of("src/common/java"))) {
            try (Stream<Path> files = Files.walk(root)) {
                for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                    String text = Files.readString(file);
                    if (Stream.of(tokens).anyMatch(text::contains)) {
                        naming.add(file);
                    }
                }
            }
        }
        return naming;
    }

    /** The operation asked straight through the pipeline, as {@code caller}, over one subject row. */
    private static void invoke(Operation<Row, Void, Void> operation, AccessContext caller, Row subject) {
        OperationPipeline.invoke(OperationRequest.of(operation, ZenitPlacementSurface.HTTP_API)
            .caller(caller)
            .subjects(List.of(subject)));
    }

    /** The no-script submit of the Deploys tab's Deploy now form: the deploy operation's invoke, carrying the tab. */
    private static String deployPath(int applicationId) {
        return CmsRoutes.invoke(HohenheimSlugs.ADMIN, HohenheimSlugs.INSTANCES, InstanceOperations.DEPLOY.id())
            .with(CmsEndpoints.SUBJECT_PARAM, String.valueOf(applicationId)).toUrl();
    }

    /** The site row's rollback invoke over one site. */
    private static String siteRollbackPath(int siteId) {
        return CmsRoutes.invoke(HohenheimSlugs.ADMIN, HohenheimSlugs.SITES, SiteOperations.ROLLBACK_RELEASE.id())
            .toUrl() + "?ids=" + siteId;
    }

    private static String tabBody() {
        return ApiSupport.form(CmsEndpoints.TAB_PARAM.getName(), HohenheimSlugs.Tab.DEPLOYMENTS,
            CmsEndpoints.INVOCATION_PARAM.getName(), UUID.randomUUID().toString(),
            CmsConfirmation.FIELD, CmsConfirmation.PLAIN_PROOF);
    }

    private static String confirmedBody() {
        return ApiSupport.form(CmsEndpoints.INVOCATION_PARAM.getName(), UUID.randomUUID().toString(),
            CmsConfirmation.FIELD, CmsConfirmation.PLAIN_PROOF);
    }

    private static long operationsOf(int applicationId) {
        return Models.get(ReleaseOperationModel.class).find()
            .where(ReleaseOperationModel.FOR_MODEL.eq(InstanceModel.MODEL_ID.toString()))
            .where(ReleaseOperationModel.FOR_ID.eq(applicationId)).count();
    }

    private static long buildsOf(int applicationId) {
        return Models.get(BuildOperationModel.class).find()
            .where(BuildOperationModel.FOR_MODEL.eq(InstanceModel.MODEL_ID.toString()))
            .where(BuildOperationModel.FOR_ID.eq(applicationId)).count();
    }

    private static int application(String name) {
        Row app = Models.get(InstanceModel.class).createEmptyRow();
        app.set(InstanceModel.NAME, name);
        app.set(InstanceModel.KIND, ApplicationKind.ID.toString());
        app.set(InstanceModel.SETTINGS, new LinkedHashMap<>(Map.of("image", "alpine", "tag", "latest")));
        app.set(InstanceModel.STATUS, InstanceModel.STATUS_CREATED);
        Models.get(InstanceModel.class).save(app);
        return app.get(InstanceModel.ID);
    }

    /** A release of the application in {@code role}, generated under its attribution as the engine generates one. */
    private static int release(int applicationId, String name, String role) throws Exception {
        int[] created = new int[1];
        GeneratedRows.as(new GeneratedRows.Attribution(ApplicationReleases.SOURCE,
            InstanceModel.MODEL_ID.toString(), applicationId), () -> {
            Map<String, Object> spec = new LinkedHashMap<>();
            spec.put(ReleaseKind.IMAGE.getName(), "registry.test/paneldeploy:stable");
            spec.put("commit_sha", name + "0000aaaa");
            spec.put(InstanceKindFields.MEMORY_LIMIT_MB, 256);
            Row row = Models.get(InstanceModel.class).createEmptyRow();
            row.set(InstanceModel.NAME, PREFIX + name);
            row.set(InstanceModel.KIND, ReleaseKind.ID.toString());
            row.set(InstanceModel.SETTINGS, spec);
            row.set(InstanceModel.RUNTIME_ROLE, role);
            Models.get(InstanceModel.class).save(row);
            created[0] = row.get(InstanceModel.ID);
        });
        return created[0];
    }
}
