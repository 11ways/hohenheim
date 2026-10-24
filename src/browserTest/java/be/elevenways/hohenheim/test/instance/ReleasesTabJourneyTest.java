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
import be.elevenways.hohenheim.model.ReleaseOperationModel;
import be.elevenways.hohenheim.server.application.ApplicationReleases;
import be.elevenways.hohenheim.server.application.SpecSnapshots;
import be.elevenways.hohenheim.server.docker.ReleaseKind;
import be.elevenways.hohenheim.server.instance.ApplicationKind;
import be.elevenways.hohenheim.server.orm.GeneratedRows;
import be.elevenways.hohenheim.test.ApiSupport;
import be.elevenways.hohenheim.test.HardDeletes;
import be.elevenways.hohenheim.test.HohenheimTestBase;
import be.elevenways.hohenheim.test.TenantConduits;
import be.elevenways.protoblast.testsupport.browser.BoundedAssertions;
import be.elevenways.zenit.auth.model.GrantSubjectType;
import be.elevenways.zenit.auth.model.UserModel;
import be.elevenways.zenit.auth.model.UserPrincipal;
import be.elevenways.zenit.auth.server.RecordGrants;
import be.elevenways.zenit.cms.common.page.CmsEndpoints;
import be.elevenways.zenit.cms.common.page.CmsRoutes;
import be.elevenways.zenit.cms.common.render.action.CmsConfirmation;
import be.elevenways.zenit.common.operation.OperationRunStatus;
import be.elevenways.zenit.common.operation.ZenitPlacementSurface;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.datasource.sql.SqlDatasource;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.refusal.DomainRefusal;
import be.elevenways.zenit.common.refusal.ZenitRefusalReason;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.common.security.PrincipalRef;
import be.elevenways.zenit.server.operation.OperationPipeline;
import be.elevenways.zenit.server.operation.OperationRequest;
import be.elevenways.zenit.server.operation.OperationRun;
import be.elevenways.zenit.server.operation.OperationRuns;
import be.elevenways.zenit.test.support.DatasourceProbe;
import com.microsoft.playwright.Locator;
import org.junit.jupiter.api.Test;

import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * An application's Releases history on its Deploys tab: who and what started each release, its commit and state, the
 * running one's live log, a finished one's stored logs, the rollback that names what it changes without a secret and
 * runs in the background on the deploy lane's budget, a tenant held to its own applications, and a page that reads
 * the same at 3 rows as at 30.
 * @author Jelle De Loecker
 * @since  0.10.0
 */
class ReleasesTabJourneyTest extends HohenheimTestBase {

    private static final String PREFIX = "releasestab-";
    private static final String KEPT_SHA = "3333cccc4444dddd";
    private static final String LIVE_SHA = "1111aaaa2222bbbb";
    private static final String RUNNING_SHA = "5555eeee6666ffff";
    private static final String LIVE_IMAGE = "sha256:releasestab-live-image";
    private static final String KEPT_SECRET = "releasestab-kept-secret-91c2";
    private static final String LIVE_SECRET = "releasestab-live-secret-7f3a";
    private static final Instant T0 = Instant.parse("2026-10-01T08:00:00Z");

    private final List<Integer> operationIds = new ArrayList<>();
    private final List<Integer> buildIds = new ArrayList<>();
    private final List<Integer> releaseIds = new ArrayList<>();

    @Test
    void theReleasesHistoryReadsWhoWhatAndWhichAndRollsBackNamingTheChangeJourney() throws Exception {
        int shop = application(PREFIX + "shop");
        int other = application(PREFIX + "other");
        UUID tenantId = ApiSupport.user(PREFIX + "tenant@surface.test", "Releases Tenant");
        UUID limitedId = ApiSupport.user(PREFIX + "limited@surface.test", "Releases Limited");
        Row admin = Models.get(UserModel.class).find().where(UserModel.EMAIL.eq("test@hohenheim.local")).first();
        try {
            // Created in release order, as the engine creates them: the kept release is the older one. Each release's
            // operation stores the redacted snapshot of what it deployed; the kept instance's own row no longer names
            // OLD_FLAG, so step 4 naming it proves the confirmation reads the stored snapshots, not the instances now.
            int kept = release(shop, "kept", spec(KEPT_SHA, 256,
                Map.of("DB_PASSWORD", KEPT_SECRET, "API_URL", "https://api.test")), InstanceModel.ROLE_RETIRED);
            Map<String, Object> liveSpec = spec(LIVE_SHA, 512,
                Map.of("DB_PASSWORD", LIVE_SECRET, "API_URL", "https://api.test"));
            int live = release(shop, "live", liveSpec, InstanceModel.ROLE_SERVING);
            snapshot(operation(shop, OperationStatus.SUCCEEDED, kept, DeployTrigger.WEBHOOK, null, KEPT_SHA,
                "kept release steps", T0, null), spec(KEPT_SHA, 256,
                Map.of("DB_PASSWORD", KEPT_SECRET, "API_URL", "https://api.test", "OLD_FLAG", "on")));
            snapshot(operation(shop, OperationStatus.SUCCEEDED, live, DeployTrigger.MANUAL,
                PrincipalRef.account(admin.get(UserModel.ID)), LIVE_SHA, "live release steps", T0.plusSeconds(3600),
                LIVE_IMAGE), liveSpec);
            build(shop, LIVE_IMAGE, "live build output: Successfully built", T0.plusSeconds(3500));
            Row running = operation(shop, OperationStatus.SWITCHING, null, DeployTrigger.API, null, RUNNING_SHA,
                "running release steps", T0.plusSeconds(7200), null);
            running.set(ReleaseOperationModel.FINISHED_AT, null);
            running.set(ReleaseOperationModel.DURATION_MS, null);
            Models.get(ReleaseOperationModel.class).save(running);

            // 1. The history lists every release with what started it, who, its commit and its state.
            String body = adminGet(url(shop)).body();
            assertThat(body)
                .as("step 1: the application lane's history is titled Releases").contains(">Releases<")
                .as("step 1: a push started the kept release").contains("data-hh-deploy-trigger=\"webhook\"")
                .as("step 1: a person started the live one").contains("data-hh-deploy-trigger=\"manual\"")
                .as("step 1: named").contains("Manual by ")
                .as("step 1: an API key started the running one").contains("data-hh-deploy-trigger=\"api\"")
                .as("step 1: every commit, short").contains("1111aaaa").contains("3333cccc").contains("5555eeee")
                .as("step 1: the live release by where it stands").contains(">Live<")
                .as("step 1: the kept one too").contains("Kept for rollback")
                .as("step 1: a finished release says how long it took").contains(">2 min<");

            // 2. The running release opens its live log, seeded with what it wrote so far; nothing steps back meanwhile.
            assertThat(body)
                .as("step 2: the running release opens the live log").contains("data-hh-deploy-live")
                .contains("<hh-deploy-log")
                .as("step 2: seeded with its release steps so far").contains("running release steps")
                .as("step 2: no rollback while a release runs").doesNotContain("data-hh-rollback");

            // 3. Once it ends, the live log closes and every past release opens its stored logs on its own row.
            running.set(ReleaseOperationModel.STATUS, ReleaseOperationModel.LIFECYCLE.stored(OperationStatus.FAILED));
            running.set(ReleaseOperationModel.FAILURE_REASON, "Did not answer on port 8080 within 60 s");
            running.set(ReleaseOperationModel.FINISHED_AT, T0.plusSeconds(7260));
            Models.get(ReleaseOperationModel.class).save(running);
            body = adminGet(url(shop)).body();
            assertThat(body).as("step 3: nothing runs, so no live log").doesNotContain("data-hh-deploy-live")
                .as("step 3: the failed release states why").contains("Did not answer on port 8080 within 60 s")
                .as("step 3: and keeps its release steps").contains("running release steps");
            String liveRow = body.substring(body.indexOf("data-hh-release-mark=\"live\""),
                body.indexOf("data-hh-release-mark=\"kept\""));
            assertThat(liveRow)
                .as("step 3: the live release opens its stored release steps").contains("live release steps")
                .as("step 3: and the stored log of the build that made its image")
                .contains("live build output: Successfully built");

            // 4. Rollback is the placed rollback operation; its confirmation names both releases and what changes,
            //    a secret variable by name only.
            assertThat(body.split("data-hh-rollback", -1).length - 1)
                .as("step 4: exactly one rollback control").isEqualTo(1);
            assertThat(body).as("step 4: it names the kept release").contains("Roll back to release 3333cccc")
                .as("step 4: no secret value reaches the page").doesNotContain(KEPT_SECRET).doesNotContain(LIVE_SECRET)
                .as("step 4: nor its fingerprint").doesNotContain("hmac:");
            navigateToApp(url(shop));
            waitForHydration();
            click("[data-hh-rollback] [data-cms-page-action-submit]");
            assertIsVisible(".pl-alertdialog-modal[data-open]", "step 4: the rollback asks first");
            Locator dialog = this.page.locator(".pl-alertdialog-modal[data-open]");
            BoundedAssertions.assertThat(dialog)
                .containsText("Go back to release 3333cccc? Release 1111aaaa stops serving.");
            String asked = dialog.textContent();
            assertThat(asked)
                .as("step 4: the memory limit change, from and to").contains("512").contains("256")
                .as("step 4: the changed secret variable, by name").contains("DB_PASSWORD")
                .as("step 4: the variable the kept release adds back").contains("OLD_FLAG")
                .as("step 4: an unchanged variable is not named").doesNotContain("API_URL")
                .as("step 4: never a secret value").doesNotContain(KEPT_SECRET).doesNotContain(LIVE_SECRET)
                .as("step 4: nor its fingerprint").doesNotContain("hmac:");
            click("[data-cms-confirm-cancel]");

            // 5. The same operation refuses an application with nothing kept before any run starts: the operation's
            //    availability asks what the engine would refuse, so no run and no release operation is minted.
            long otherOps = operationsOf(other);
            AccessContext operator = AccessContext.of(TenantConduits.stubFor(
                new UserPrincipal(admin.get(UserModel.ID), "Releases Admin")));
            HttpResponse<String> refused = adminPostForm(invokePath(HohenheimSlugs.ADMIN, other), invokeBody());
            assertThat(refused.statusCode()).as("step 5: refused as unavailable, the framework's answer")
                .isEqualTo(409);
            assertThat(operationsOf(other)).as("step 5: and nothing was minted").isEqualTo(otherOps);
            assertThat(OperationRuns.recentFor(InstanceOperations.INSTANCE, String.valueOf(other), 1, operator))
                .as("step 5: nor any run started").isEmpty();

            // 5b. Where a release is kept, the same operation answers at once with its run's page instead of holding
            //     the request for the gated swap, and the run ends on its own (here refused: nothing of this test
            //     serves).
            HttpResponse<String> started = adminPostForm(invokePath(HohenheimSlugs.ADMIN, shop), invokeBody());
            assertThat(started.statusCode()).as("step 5b: answered at once, as a redirect").isIn(302, 303);
            List<OperationRun> runs = OperationRuns.recentFor(InstanceOperations.INSTANCE, String.valueOf(shop), 1,
                operator);
            assertThat(runs).as("step 5b: a run was started for the application").hasSize(1);
            long runId = runs.get(0).id();
            assertThat(landingOf(started)).as("step 5b: to that run's page")
                .endsWith(CmsRoutes.operationRun(HohenheimSlugs.ADMIN, HohenheimSlugs.INSTANCES, runId).toUrl());
            assertThat(PutOnlineJourneyTest.ended(runId, operator, "step 5b").status())
                .as("step 5b: and it ended on its own").isNotEqualTo(OperationRunStatus.RUNNING);

            // 6. A tenant reads its own application's releases, and nothing of another's.
            RecordGrants.grant(GrantSubjectType.USER, tenantId, InstanceModel.MODEL_ID, shop,
                HohenheimCapabilities.MANAGE, true);
            TestSession tenant = sessionFor(tenantId);
            HttpResponse<String> own = httpGet(manageUrl(shop), tenant.token());
            assertThat(own.statusCode()).as("step 6: the tenant's own Releases render").isEqualTo(200);
            assertThat(own.body()).as("step 6: with its releases").contains("1111aaaa").contains("3333cccc");
            assertThat(httpGet(manageUrl(other), tenant.token()).statusCode())
                .as("step 6: another application's releases are missing, never forbidden").isEqualTo(404);
            assertThat(httpGet("/manage/instances", tenant.token()).body())
                .as("step 6: the tenant's list holds its own application").contains(PREFIX + "shop")
                .as("step 6: and not the other").doesNotContain(PREFIX + "other");
            HttpResponse<String> foreign = httpPostForm(invokePath(HohenheimSlugs.MANAGE, other), invokeBody(),
                tenant.token(), tenant.csrf());
            assertThat(foreign.statusCode()).as("step 6: the other application's rollback is missing too")
                .isEqualTo(404);
            assertThat(operationsOf(other)).as("step 6: and nothing ran").isEqualTo(otherOps);

            // 7. The page reads the same at 3 releases as at 30: everything a row shows beyond its own columns is read
            //    once for the page, and the history pages at 20 rows.
            Map<String, Integer> few = measure(url(shop));
            for (int index = 0; index < 27; index++) {
                // Replaced releases, each of its own image, whose instances were reclaimed long ago.
                operation(shop, OperationStatus.SUCCEEDED, Integer.MAX_VALUE - index, DeployTrigger.MANUAL,
                    PrincipalRef.account(admin.get(UserModel.ID)), String.format("%08x", index) + "deadbeef",
                    "release " + index + " steps", T0.plusSeconds(10_000L + index * 60L),
                    "sha256:releasestab-image-" + index);
            }
            Map<String, Integer> many = measure(url(shop));
            assertThat(many).as("step 7: 30 releases cost the reads 3 did").isEqualTo(few);
            String first = adminGet(url(shop)).body();
            assertThat(first).as("step 7: the history pages").contains("page=2")
                .as("step 7: the oldest release is not on the first page")
                .doesNotContain("data-hh-release-mark=\"kept\"");
            assertThat(adminGet(url(shop) + "?page=2").body()).as("step 7: it is on the second")
                .contains("data-hh-release-mark=\"kept\"").contains("kept release steps");

            // 8. The rollback spends the deploy lane's own budget, the panel deploy's very policy: ten a minute per
            //    caller, refused ones included, and the eleventh is refused as rate-limited before anything is asked.
            assertThat(InstanceOperations.ROLLBACK.rateLimit()).as("step 8: the deploy lane's own policy")
                .isSameAs(HohenheimEndpoints.DEPLOY_LIMIT)
                .isSameAs(InstanceOperations.DEPLOY.rateLimit());
            for (String capability : List.of(HohenheimCapabilities.VIEW, HohenheimCapabilities.POWER)) {
                RecordGrants.grant(GrantSubjectType.USER, limitedId, InstanceModel.MODEL_ID, other, capability, true);
            }
            AccessContext limited = AccessContext.of(TenantConduits.stubFor(
                new UserPrincipal(limitedId, "Releases Limited")));
            Row otherRow = Models.get(InstanceModel.class).findById(other);
            for (int attempt = 1; attempt <= 10; attempt++) {
                Throwable refusal = catchThrowable(() -> rollback(limited, otherRow));
                assertThat(refusal).as("step 8: rollback %d is refused for having nothing kept", attempt)
                    .isInstanceOf(DomainRefusal.class);
                assertThat(((DomainRefusal) refusal).reason()).as("step 8: rollback %d is within the budget", attempt)
                    .isEqualTo(ZenitRefusalReason.OPERATION_UNAVAILABLE);
                assertThat(Objects.requireNonNull(((DomainRefusal) refusal).words()).key())
                    .as("step 8: refused in the release engine's own words").isEqualTo("release_no_rollback_target");
            }
            Throwable eleventh = catchThrowable(() -> rollback(limited, otherRow));
            assertThat(eleventh).as("step 8: the eleventh within the minute is refused")
                .isInstanceOf(DomainRefusal.class);
            assertThat(((DomainRefusal) eleventh).reason()).as("step 8: with the rate-limit refusal")
                .isEqualTo(ZenitRefusalReason.RATE_LIMITED);
            assertThat(operationsOf(other)).as("step 8: and nothing was minted").isEqualTo(otherOps);
        } finally {
            for (Integer id : operationIds) {
                Models.get(ReleaseOperationModel.class).delete(id);
            }
            for (Integer id : buildIds) {
                Models.get(BuildOperationModel.class).delete(id);
            }
            GeneratedRows.as(new GeneratedRows.Attribution(ApplicationReleases.SOURCE,
                InstanceModel.MODEL_ID.toString(), shop), () -> {
                for (Integer id : releaseIds) {
                    HardDeletes.byId(Models.get(InstanceModel.class), id);
                }
            });
            HardDeletes.byId(Models.get(InstanceModel.class), shop);
            HardDeletes.byId(Models.get(InstanceModel.class), other);
            Models.get(UserModel.class).delete(tenantId);
            Models.get(UserModel.class).delete(limitedId);
        }
    }

    /** The rollback asked straight through the pipeline, as {@code caller}, over one application. */
    private static void rollback(AccessContext caller, Row application) {
        OperationPipeline.invoke(OperationRequest.of(InstanceOperations.ROLLBACK, ZenitPlacementSurface.HTTP_API)
            .caller(caller)
            .subjects(List.of(application)));
    }

    /** The release-model reads of one render, per model, after a warm-up render. */
    private Map<String, Integer> measure(String url) throws Exception {
        DatasourceProbe probe = DatasourceProbe.on((SqlDatasource) Models.get(InstanceModel.class)
            .getResolvedDatasource());
        assertThat(adminGet(url).statusCode()).as("the warm-up render").isEqualTo(200);
        probe.reset();
        assertThat(adminGet(url).statusCode()).as("the measured render").isEqualTo(200);
        Map<String, Integer> reads = new TreeMap<>();
        reads.put("release_operations", probe.queriesAgainst(ReleaseOperationModel.MODEL_ID));
        reads.put("build_operations", probe.queriesAgainst(BuildOperationModel.MODEL_ID));
        reads.put("instances", probe.queriesAgainst(InstanceModel.MODEL_ID));
        return reads;
    }

    private static String url(int applicationId) {
        return "/admin/instances/" + applicationId + "/page/" + HohenheimSlugs.Tab.DEPLOYMENTS;
    }

    private static String manageUrl(int applicationId) {
        return "/manage/instances/" + applicationId + "/page/" + HohenheimSlugs.Tab.DEPLOYMENTS;
    }

    /** The no-script submit of the tab's rollback form: the rollback operation's invoke, carrying the tab. */
    private static String invokePath(String panel, int applicationId) {
        return CmsRoutes.invoke(panel, HohenheimSlugs.INSTANCES, InstanceOperations.ROLLBACK.id())
            .with(CmsEndpoints.SUBJECT_PARAM, String.valueOf(applicationId)).toUrl();
    }

    private static String invokeBody() {
        return ApiSupport.form(CmsEndpoints.TAB_PARAM.getName(), HohenheimSlugs.Tab.DEPLOYMENTS,
            CmsEndpoints.INVOCATION_PARAM.getName(), UUID.randomUUID().toString(),
            CmsConfirmation.FIELD, CmsConfirmation.PLAIN_PROOF);
    }

    private static long operationsOf(int applicationId) {
        return Models.get(ReleaseOperationModel.class).find()
            .where(ReleaseOperationModel.FOR_MODEL.eq(InstanceModel.MODEL_ID.toString()))
            .where(ReleaseOperationModel.FOR_ID.eq(applicationId)).count();
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

    private static Map<String, Object> spec(String sha, int memoryMb, Map<String, String> environment) {
        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put(ReleaseKind.IMAGE.getName(), "registry.test/shop:stable");
        spec.put("commit_sha", sha);
        spec.put(InstanceKindFields.MEMORY_LIMIT_MB, memoryMb);
        spec.put(InstanceKindFields.ENVIRONMENT_VARIABLES, new LinkedHashMap<>(environment));
        return spec;
    }

    private int release(int applicationId, String name, Map<String, Object> spec, String role) throws Exception {
        int[] created = new int[1];
        GeneratedRows.as(new GeneratedRows.Attribution(ApplicationReleases.SOURCE,
            InstanceModel.MODEL_ID.toString(), applicationId), () -> {
            Row row = Models.get(InstanceModel.class).createEmptyRow();
            row.set(InstanceModel.NAME, PREFIX + name);
            row.set(InstanceModel.KIND, ReleaseKind.ID.toString());
            row.set(InstanceModel.SETTINGS, spec);
            row.set(InstanceModel.RUNTIME_ROLE, role);
            Models.get(InstanceModel.class).save(row);
            created[0] = row.get(InstanceModel.ID);
        });
        this.releaseIds.add(created[0]);
        return created[0];
    }

    private Row operation(int applicationId, OperationStatus status, Integer candidate, DeployTrigger trigger,
                          PrincipalRef by, String sha, String steps, Instant startedAt, String imageId) {
        Row op = Models.get(ReleaseOperationModel.class).createEmptyRow();
        op.set(ReleaseOperationModel.KIND, ReleaseOperationModel.KIND_RELEASE);
        op.set(ReleaseOperationModel.FOR_MODEL, InstanceModel.MODEL_ID.toString());
        op.set(ReleaseOperationModel.FOR_ID, applicationId);
        op.set(ReleaseOperationModel.STATUS, ReleaseOperationModel.LIFECYCLE.stored(status));
        op.set(ReleaseOperationModel.CANDIDATE_INSTANCE_ID, candidate);
        op.set(ReleaseOperationModel.TRIGGER, trigger.word());
        if (by != null) {
            ReleaseOperationModel.TRIGGERER.write(op, by);
        }
        op.set(ReleaseOperationModel.COMMIT_SHA, sha);
        op.set(ReleaseOperationModel.STEP_LOG, steps);
        op.set(ReleaseOperationModel.STARTED_AT, startedAt);
        op.set(ReleaseOperationModel.FINISHED_AT, startedAt.plusSeconds(120));
        op.set(ReleaseOperationModel.DURATION_MS, 120_000);
        op.set(ReleaseOperationModel.IMAGE_ID, imageId);
        Models.get(ReleaseOperationModel.class).save(op);
        this.operationIds.add(op.get(ReleaseOperationModel.ID));
        return op;
    }

    /** Store the redacted snapshot of what the operation deployed, as release attribution does. */
    private static void snapshot(Row operation, Map<String, Object> spec) {
        operation.set(ReleaseOperationModel.SPEC_SNAPSHOT, SpecSnapshots.snapshot(spec));
        Models.get(ReleaseOperationModel.class).save(operation);
    }

    private void build(int applicationId, String imageId, String log, Instant startedAt) {
        Row build = Models.get(BuildOperationModel.class).createEmptyRow();
        build.set(BuildOperationModel.FOR_MODEL, InstanceModel.MODEL_ID.toString());
        build.set(BuildOperationModel.FOR_ID, applicationId);
        build.set(BuildOperationModel.STATUS, BuildOperationModel.LIFECYCLE.stored(OperationStatus.SUCCEEDED));
        build.set(BuildOperationModel.SOURCE_REF, LIVE_SHA);
        build.set(BuildOperationModel.IMAGE_ID, imageId);
        build.set(BuildOperationModel.LOG, log);
        build.set(BuildOperationModel.STARTED_AT, startedAt);
        build.set(BuildOperationModel.FINISHED_AT, startedAt.plusSeconds(60));
        Models.get(BuildOperationModel.class).save(build);
        this.buildIds.add(build.get(BuildOperationModel.ID));
    }
}
