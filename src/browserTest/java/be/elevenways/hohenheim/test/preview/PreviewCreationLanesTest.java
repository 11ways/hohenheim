package be.elevenways.hohenheim.test.preview;

import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.PreviewDeploymentModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.server.instance.DeployTrigger;
import be.elevenways.hohenheim.test.source.TestSources;
import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.hohenheim.server.cms.ManagePreviewDeploymentResource;
import be.elevenways.hohenheim.server.cms.PreviewDeploymentResource;
import be.elevenways.hohenheim.server.instance.ApplicationKind;
import be.elevenways.hohenheim.server.preview.PreviewBranches;
import be.elevenways.hohenheim.server.preview.PreviewDeployments;
import be.elevenways.hohenheim.test.HohenheimTestBase;
import be.elevenways.hohenheim.test.TenantConduits;
import be.elevenways.zenit.auth.model.GrantSubjectType;
import be.elevenways.zenit.auth.model.UserModel;
import be.elevenways.zenit.auth.model.UserPrincipal;
import be.elevenways.zenit.auth.server.AuthModels;
import be.elevenways.zenit.auth.server.RecordGrants;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.common.validation.Violations;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * The two NEW preview creation lanes beside the webhook one: the branch-pattern
 * opt-in vocabulary and the manual queue (synchronous quota-charged claim, background
 * build), plus the /manage authority gate over manual creation.
 *
 * Hermetic: the checkout target does not exist, so every queued build FAILS after the
 * claim. What this file cannot prove is a manual preview reaching RUNNING -- the
 * build/probe half rides the live lane (PreviewDeploymentLiveTest).
 */
class PreviewCreationLanesTest extends HohenheimTestBase {

    private static Integer siteId;

    /** The APPLICATION previews are built from; the site beside it only lends a hostname. */
    private static Integer applicationId;

    @BeforeAll
    static void setUpSite() {
        HohenheimSettings.VALUES.setValue(
            HohenheimSettings.Previews.BASE_DOMAIN, "preview.test");
        var siteModel = Models.get(SiteModel.class);
        Row site = siteModel.createEmptyRow();
        site.set(SiteModel.NAME, "Preview Lanes Site");
        site.set(SiteModel.SLUG, "prev-lanes");
        
        site.set(SiteModel.SETTINGS, Map.of("image", "alpine", "container_port", 8080));
        
        Map<String, Object> sourceSettings = new LinkedHashMap<>();
        sourceSettings.put("repository_url", "/nonexistent/repo");
        TestSources.attachGitSource(site, sourceSettings);
        site.set(SiteModel.STATUS, "active");
        site.set(SiteModel.ENABLED, true);
        siteModel.save(site);
        siteId = site.get(SiteModel.ID);
        applicationId = site.get(SiteModel.INSTANCE_ID);
    }

    @Test
    void branchPatternsAreAnOptInGlobVocabulary() {
        // 1. Exact names match exactly; nothing else.
        assertThat(PreviewBranches.matches(List.of("develop"), "develop"))
            .as("step 1: an exact pattern covers its branch").isTrue();
        assertThat(PreviewBranches.matches(List.of("develop"), "develop-2"))
            .as("step 1: and nothing that merely starts with it").isFalse();

        // 2. A glob covers its subtree, and only its subtree.
        assertThat(PreviewBranches.matches(List.of("feature/*"), "feature/login"))
            .as("step 2: feature/* covers feature/login").isTrue();
        assertThat(PreviewBranches.matches(List.of("feature/*"), "feature/a/b"))
            .as("step 2: * matches any run, slashes included").isTrue();
        assertThat(PreviewBranches.matches(List.of("feature/*"), "featurette"))
            .as("step 2: featurette is outside feature/*").isFalse();

        // 3. Infix and multi-star patterns fold correctly.
        assertThat(PreviewBranches.matches(List.of("release-*-rc"), "release-1.2-rc"))
            .as("step 3: infix star").isTrue();
        assertThat(PreviewBranches.matches(List.of("*fix*"), "hotfix/urgent"))
            .as("step 3: double star").isTrue();

        // 4. The OPT-IN default: no patterns cover nothing -- the decision that keeps
        //    branch previews from minting one resource commitment per pushed branch.
        assertThat(PreviewBranches.matches(List.of(), "anything"))
            .as("step 4: an empty declaration covers no branch").isFalse();
        assertThat(PreviewBranches.patternsOf(null))
            .as("step 4: absent settings parse to no patterns").isEmpty();
        assertThat(PreviewBranches.patternsOf(List.of(" feature/* ", "", "  ")))
            .as("step 4: blanks are dropped, values trimmed")
            .containsExactly("feature/*");
    }

    @Test
    void theManualQueueClaimsSynchronouslyChargesTheOwnerAndRefusesAtTheCapByName()
            throws Exception {
        Integer savedCap = HohenheimSettings.VALUES.getValue(
            HohenheimSettings.Previews.MAX_PER_OWNER);
        HohenheimSettings.VALUES.setValue(HohenheimSettings.Previews.MAX_PER_OWNER, 1);
        try {
            // 1. queue() returns SYNCHRONOUSLY with a claimed, quota-charged row: the
            //    manual lane's refusals and charges happen before the caller's form
            //    round-trip ends, not in a background job nobody sees.
            Row first = PreviewDeployments.queue(applicationId, "manual-a", null, null,
                DeployTrigger.MANUAL);
            assertThat((String) first.get(PreviewDeploymentModel.STATUS))
                .as("step 1: the claim is stamped deploying before any build ran")
                .isEqualTo(PreviewDeploymentModel.STATUS_DEPLOYING);
            assertThat((String) first.get(PreviewDeploymentModel.QUOTA_BUCKET))
                .as("step 1: charged to the owner's preview bucket")
                .startsWith("hohenheim:previews:");
            assertThat((Object) first.get(PreviewDeploymentModel.PR_NUMBER))
                .as("step 1: a manual preview carries no PR number").isNull();

            // 2. THE CAP DECISION: the second manual preview is refused BY NAME,
            //    synchronously -- never an eviction of the running first one.
            Throwable refused = catchThrowable(() ->
                PreviewDeployments.queue(applicationId, "manual-b", null, null,
                DeployTrigger.MANUAL));
            assertThat(refused).as("step 2: over-cap manual create refused by name")
                .isInstanceOf(Violations.class)
                .hasMessageContaining("preview_quota_reached");
            assertThat(Models.get(PreviewDeploymentModel.class).find()
                    .where(PreviewDeploymentModel.APPLICATION_ID.eq(applicationId))
                    .where(PreviewDeploymentModel.REF.eq("manual-a"))
                    .where(PreviewDeploymentModel.DELETED_AT.isNull()).first())
                .as("step 2: and the FIRST preview was not evicted to make room")
                .isNotNull();

            // 3. The background build of the claim FAILS (no such repository) and the
            //    row records it -- a failed manual preview is visible, never silent.
            int firstId = first.get(PreviewDeploymentModel.ID);
            awaitStatus(firstId, PreviewDeploymentModel.STATUS_FAILED);
            Row failed = Models.get(PreviewDeploymentModel.class).findById(firstId);
            assertThat((String) failed.get(PreviewDeploymentModel.LAST_ERROR))
                .as("step 3: the failure reason is recorded on the row").isNotBlank();

            // 4. A FAILED preview still holds its charge until reclaimed (the expiry
            //    schedule armed at claim time is the backstop); destroying it releases
            //    the slot and the refused ref now fits.
            PreviewDeployments.destroy(firstId, "operator");
            Row second = PreviewDeployments.queue(applicationId, "manual-b", null, null,
                DeployTrigger.MANUAL);
            assertThat(second.get(PreviewDeploymentModel.ID))
                .as("step 4: the released slot is claimable again").isNotNull();
            PreviewDeployments.destroy(second.get(PreviewDeploymentModel.ID), "operator");
        } finally {
            HohenheimSettings.VALUES.setValue(
                HohenheimSettings.Previews.MAX_PER_OWNER, savedCap);
        }
    }

    /**
     * The /manage authority gate: MANAGE on the APPLICATION is the verb (a preview is a
     * projection of the application it is built from; no new capability exists for it),
     * and the COUNTERFACTUAL proves the gate is load-bearing -- the base admin resource,
     * which does not carry it, happily creates a preview for the same stranger.
     */
    @Test
    void manualCreationFromManageRequiresManageOnTheChosenApplication() throws Exception {
        int strangerId = tenantUser("preview-stranger@test");
        int managerId = tenantUser("preview-manager@test");
        RecordGrants.grant(GrantSubjectType.USER, managerId, InstanceModel.MODEL_ID,
            applicationId, HohenheimAccess.MANAGE, true);
        AccessContext stranger = contextOf(strangerId, "Stranger");
        AccessContext manager = contextOf(managerId, "Manager");
        Map<String, Object> coerced = new LinkedHashMap<>();
        coerced.put(PreviewDeploymentModel.APPLICATION_ID.getName(), applicationId);
        coerced.put(PreviewDeploymentModel.REF.getName(), "gate-ref");

        // 1. COUNTERFACTUAL: the base ADMIN resource carries no per-record gate (its
        //    surface is behind the admin panel permission), so the stranger context
        //    sails through it. This is exactly what /manage must NOT allow.
        Object ungated = new PreviewDeploymentResource().persistRow(coerced, stranger);
        assertThat(ungated)
            .as("step 1: ungated, a stranger creates a preview on a foreign application")
            .isNotNull();
        PreviewDeployments.destroy(((Number) ungated).intValue(), "operator");

        // 2. The /manage resource refuses the SAME submission as its FIRST act: no row,
        //    no quota charge, no schedule -- and the refusal does not distinguish
        //    "exists but not yours" from "no such application".
        Throwable refusedForeign = catchThrowable(() ->
            new ManagePreviewDeploymentResource().persistRow(coerced, stranger));
        assertThat(refusedForeign)
            .as("step 2: a stranger is refused on /manage")
            .isInstanceOf(Violations.class)
            .hasMessageContaining("preview_application_required");
        Map<String, Object> unknownApplication = new LinkedHashMap<>(coerced);
        unknownApplication.put(PreviewDeploymentModel.APPLICATION_ID.getName(), 999999);
        Throwable refusedUnknown = catchThrowable(() ->
            new ManagePreviewDeploymentResource().persistRow(unknownApplication, stranger));
        assertThat(refusedUnknown)
            .as("step 2: an unknown application refuses IDENTICALLY (no existence oracle)")
            .isInstanceOf(Violations.class)
            .hasMessageContaining("preview_application_required");
        assertThat(Models.get(PreviewDeploymentModel.class).find()
                .where(PreviewDeploymentModel.APPLICATION_ID.eq(applicationId))
                .where(PreviewDeploymentModel.REF.eq("gate-ref"))
                .where(PreviewDeploymentModel.DELETED_AT.isNull()).first())
            .as("step 2: the refusal claimed nothing").isNull();

        // 3. The walk-confirmed MANAGE holder creates through the same path.
        Object created = new ManagePreviewDeploymentResource().persistRow(coerced, manager);
        assertThat(created).as("step 3: a manage holder may create").isNotNull();
        PreviewDeployments.destroy(((Number) created).intValue(), "operator");
    }

    /**
     * A preview is built from the APPLICATION but must be REACHABLE, and a hostname only
     * routes when some site's domain table carries it -- so an application no site exposes
     * is refused BY NAME rather than building an environment nobody can open.
     */
    @Test
    void anApplicationNoSiteExposesCannotHaveAPreview() throws Exception {
        int orphan = application("prev-orphan-app");

        // 1. No site names this application, so the claim refuses by name -- before any
        //    quota charge, any checkout and any build.
        Throwable refused = catchThrowable(() ->
            PreviewDeployments.queue(orphan, "orphan-ref", null, null,
                DeployTrigger.MANUAL));
        assertThat(refused)
            .as("step 1: an application with no exposing site is refused by name")
            .isInstanceOf(Violations.class)
            .hasMessageContaining("preview_no_exposing_site");
        assertThat(Models.get(PreviewDeploymentModel.class).find()
                .where(PreviewDeploymentModel.APPLICATION_ID.eq(orphan))
                .where(PreviewDeploymentModel.DELETED_AT.isNull()).first())
            .as("step 1: and the refusal claimed no row").isNull();

        // 2. THE FALSIFICATION: give the SAME application a site to lend it a hostname
        //    and the very same call now claims -- so step 1 refused for the missing
        //    site, never for some other property of this fixture.
        var siteModel = Models.get(SiteModel.class);
        Row lender = siteModel.createEmptyRow();
        lender.set(SiteModel.NAME, "Preview Orphan Lender");
        lender.set(SiteModel.SLUG, "prev-orphan");
        lender.set(SiteModel.UPSTREAM_KIND, "hohenheim:instance");
        lender.set(SiteModel.INSTANCE_ID, orphan);
        lender.set(SiteModel.STATUS, "active");
        lender.set(SiteModel.ENABLED, true);
        siteModel.save(lender);

        Row claimed = PreviewDeployments.queue(orphan, "orphan-ref", null, null,
                DeployTrigger.MANUAL);
        assertThat((String) claimed.get(PreviewDeploymentModel.STATUS))
            .as("step 2: with an exposing site the same claim lands")
            .isEqualTo(PreviewDeploymentModel.STATUS_DEPLOYING);
        assertThat((String) claimed.get(PreviewDeploymentModel.HOSTNAME))
            .as("step 2: and the hostname is derived from the LENDING site's slug")
            .startsWith("prev-orphan--").endsWith(".preview.test");
        PreviewDeployments.destroy(claimed.get(PreviewDeploymentModel.ID), "operator");
    }

    // -- helpers --------------------------------------------------------------

    /** A git-sourced application instance, with no site exposing it. */
    private static int application(String name) {
        Row row = Models.get(InstanceModel.class).createEmptyRow();
        row.set(InstanceModel.NAME, name);
        row.set(InstanceModel.KIND, ApplicationKind.ID.toString());
        row.set(InstanceModel.SETTINGS, new LinkedHashMap<>(Map.of(
            "image", "alpine", "container_port", 8080,
            "repository_url", "/nonexistent/repo")));
        Models.get(InstanceModel.class).save(row);
        return row.get(InstanceModel.ID);
    }

    private static AccessContext contextOf(int userId, String name) {
        return AccessContext.of(TenantConduits.stubFor(new UserPrincipal(userId, name)));
    }

    private static int tenantUser(String email) {
        Row user = AuthModels.users().createEmptyRow();
        user.set(UserModel.EMAIL, email);
        user.set(UserModel.DISPLAY_NAME, email);
        user.set(UserModel.ENABLED, true);
        user.set(UserModel.CREATED_AT, Instant.now());
        user.set(UserModel.UPDATED_AT, Instant.now());
        AuthModels.users().save(user);
        return user.get(UserModel.ID);
    }

    private static void awaitStatus(int previewId, String status) throws InterruptedException {
        for (int i = 0; i < 200; i++) {
            Row row = Models.get(PreviewDeploymentModel.class).findById(previewId);
            if (row != null && status.equals(row.get(PreviewDeploymentModel.STATUS))) {
                return;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("preview " + previewId + " never reached status " + status);
    }
}
