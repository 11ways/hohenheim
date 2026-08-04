package be.elevenways.hohenheim.test.project;

import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.InstanceQuotaModel;
import be.elevenways.hohenheim.model.ProjectModel;
import be.elevenways.hohenheim.model.ReleasedRouteClaimModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.hohenheim.server.instance.InstanceQuota;
import be.elevenways.hohenheim.server.project.ProjectAdoption;
import be.elevenways.hohenheim.server.project.Projects;
import be.elevenways.hohenheim.test.HohenheimTestBase;
import be.elevenways.zenit.auth.model.UserModel;
import be.elevenways.zenit.auth.model.UserPrincipal;
import be.elevenways.zenit.auth.server.AuthModels;
import be.elevenways.zenit.auth.server.RecordGrants;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.Models;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * COUNTERFACTUAL 4 -- the migration is honest: existing per-user-granted records land
 * in one auto-created project per distinct owner set, REACH IS PRESERVED for every
 * user who could reach a record before, operator-owned records stay untouched, and
 * everything keyed on the old owner packing (quota override rows, charged buckets,
 * the released-claim ledger) follows in the same pass. Counts are asserted exactly.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ProjectAdoptionTest extends HohenheimTestBase {

    private static final String PREFIX = "proj-adopt-";

    private static Integer userXId;
    private static Integer userYId;
    private static UserPrincipal principalX;
    private static UserPrincipal principalY;

    private static Integer instanceX1;
    private static Integer instanceX2;
    private static Integer instanceY1;
    private static Integer instanceOperator;
    private static Integer siteSharedId;
    private static Integer siteXId;
    private static Integer quotaOverrideId;
    private static Integer claimId;

    private static ProjectAdoption.Result result;

    @BeforeAll
    static void seedLegacyLandscape() {
        userXId = user("adopt-x@project.test", "Adopt X");
        userYId = user("adopt-y@project.test", "Adopt Y");
        principalX = new UserPrincipal(userXId, "Adopt X");
        principalY = new UserPrincipal(userYId, "Adopt Y");

        instanceX1 = instance(PREFIX + "x1");
        instanceX2 = instance(PREFIX + "x2");
        instanceY1 = instance(PREFIX + "y1");
        instanceOperator = instance(PREFIX + "op");
        siteSharedId = site(PREFIX + "shared");
        siteXId = site(PREFIX + "site-x");

        // The pre-project world: direct per-user manage grants.
        RecordGrants.grant("user", userXId, InstanceModel.MODEL_ID, instanceX1,
            HohenheimAccess.MANAGE, true);
        RecordGrants.grant("user", userXId, InstanceModel.MODEL_ID, instanceX2,
            HohenheimAccess.MANAGE, true);
        RecordGrants.grant("user", userYId, InstanceModel.MODEL_ID, instanceY1,
            HohenheimAccess.MANAGE, true);
        RecordGrants.grant("user", userXId, SiteModel.MODEL_ID, siteSharedId,
            HohenheimAccess.MANAGE, true);
        RecordGrants.grant("user", userYId, SiteModel.MODEL_ID, siteSharedId,
            HohenheimAccess.MANAGE, true);
        RecordGrants.grant("user", userXId, SiteModel.MODEL_ID, siteXId,
            HohenheimAccess.MANAGE, true);

        // A per-owner quota override keyed on the OLD packing.
        Row cap = Models.get(InstanceQuotaModel.class).createEmptyRow();
        cap.set(InstanceQuotaModel.SUBJECTS,
            HohenheimAccess.packSubjects(Set.of("user:" + userXId)));
        cap.set(InstanceQuotaModel.MAX_INSTANCES, 7);
        Models.get(InstanceQuotaModel.class).save(cap);
        quotaOverrideId = cap.get(InstanceQuotaModel.ID);

        // A released-claim ledger row remembering the OLD owner packing.
        Row claim = Models.get(ReleasedRouteClaimModel.class).createEmptyRow();
        claim.set(ReleasedRouteClaimModel.CLAIM_KEY, PREFIX + "claim-key");
        claim.set(ReleasedRouteClaimModel.HOSTNAME, PREFIX + "released.test");
        claim.set(ReleasedRouteClaimModel.FORMER_SITE_ID, siteXId);
        claim.set(ReleasedRouteClaimModel.FORMER_SUBJECTS,
            HohenheimAccess.packSubjects(Set.of("user:" + userXId)));
        claim.set(ReleasedRouteClaimModel.RELEASED_AT, Instant.now());
        Models.get(ReleasedRouteClaimModel.class).save(claim);
        claimId = claim.get(ReleasedRouteClaimModel.ID);
    }

    @AfterAll
    static void cleanUp() {
        if (claimId != null) {
            Models.get(ReleasedRouteClaimModel.class).delete(claimId);
        }
        if (quotaOverrideId != null) {
            Models.get(InstanceQuotaModel.class).delete(quotaOverrideId);
        }
        Model instances = Models.get(InstanceModel.class);
        for (Row row : instances.find().where(InstanceModel.NAME.startsWith(PREFIX)).all()) {
            instances.delete(row.get(InstanceModel.ID));
        }
        Model sites = Models.get(SiteModel.class);
        for (Row row : sites.find().where(SiteModel.NAME.startsWith(PREFIX)).all()) {
            sites.delete(row.get(SiteModel.ID));
        }
        // Adoption-created projects for this class's owner sets (throwaway members).
        Model projects = Models.get(ProjectModel.class);
        for (Row row : projects.find().all()) {
            String name = row.get(ProjectModel.NAME);
            if (name != null && name.contains("Adopt ")) {
                projects.delete(row.get(ProjectModel.ID));
            }
        }
    }

    private static int user(String email, String name) {
        Row user = AuthModels.users().createEmptyRow();
        user.set(UserModel.EMAIL, email);
        user.set(UserModel.DISPLAY_NAME, name);
        user.set(UserModel.ENABLED, true);
        user.set(UserModel.CREATED_AT, Instant.now());
        user.set(UserModel.UPDATED_AT, Instant.now());
        AuthModels.users().save(user);
        return user.get(UserModel.ID);
    }

    private static int instance(String name) {
        Row row = Models.get(InstanceModel.class).createEmptyRow();
        row.set(InstanceModel.NAME, name);
        row.set(InstanceModel.KIND, "hohenheim:docker_container");
        row.set(InstanceModel.SETTINGS, new LinkedHashMap<>(
            Map.of("image", "alpine", "tag", "latest", "command", "sleep 300")));
        row.set(InstanceModel.STATUS, InstanceModel.STATUS_CREATED);
        Models.get(InstanceModel.class).save(row);
        return row.get(InstanceModel.ID);
    }

    private static int site(String name) {
        Row row = Models.get(SiteModel.class).createEmptyRow();
        row.set(SiteModel.NAME, name);
        row.set(SiteModel.SLUG, name);
        row.set(SiteModel.SITE_TYPE, "hohenheim:static");
        row.set(SiteModel.SETTINGS, Map.of("root_path", "/tmp"));
        row.set(SiteModel.STATUS, SiteModel.STATUS_ACTIVE);
        row.set(SiteModel.ENABLED, false);
        Models.get(SiteModel.class).save(row);
        return row.get(SiteModel.ID);
    }

    // -- the journey ----------------------------------------------------------

    /** The heal itself, with EXACT counts derived from the landscape it saw. */
    @Test
    @Order(1)
    void adoptionCreatesOneProjectPerDistinctOwnerSetWithExactCounts() {
        // 1. Reach BEFORE, through the real precedence walk.
        assertThat(HohenheimAccess.canManageInstance(principalX, instanceX1)).isTrue();
        assertThat(HohenheimAccess.canManageInstance(principalX, instanceX2)).isTrue();
        assertThat(HohenheimAccess.canManageSite(principalX, siteSharedId)).isTrue();
        assertThat(HohenheimAccess.canManageSite(principalX, siteXId)).isTrue();
        assertThat(HohenheimAccess.canManageInstance(principalY, instanceY1)).isTrue();
        assertThat(HohenheimAccess.canManageSite(principalY, siteSharedId)).isTrue();
        assertThat(HohenheimAccess.canManageInstance(principalX, instanceY1))
            .as("step 1: X never reached Y's instance").isFalse();

        long operatorUsedBefore = InstanceQuota.usedBy("");

        // 2. Run the heal. The landscape THIS class planted is three distinct owner
        //    sets ({X}, {Y}, {X,Y}); a shared server may carry more from neighbours,
        //    so the exact assertions below are on THIS class's sets and records.
        result = ProjectAdoption.run();
        assertThat(result.projectsCreated())
            .as("step 2: at least the three distinct owner sets became projects")
            .isGreaterThanOrEqualTo(3);

        // 3. Each record now answers to exactly ONE project subject, and records that
        //    shared an owner set share a project while others do not.
        Row projectX = Projects.projectOf(InstanceModel.MODEL_ID, instanceX1);
        Row projectY = Projects.projectOf(InstanceModel.MODEL_ID, instanceY1);
        Row projectShared = Projects.projectOf(SiteModel.MODEL_ID, siteSharedId);
        assertThat(projectX).as("step 3: {X} became a project").isNotNull();
        assertThat(projectY).as("step 3: {Y} became a project").isNotNull();
        assertThat(projectShared).as("step 3: {X,Y} became a project").isNotNull();
        assertThat((Integer) projectX.get(ProjectModel.ID))
            .as("step 3: X's two instances and X's site share ONE project")
            .isEqualTo(Projects.projectOf(InstanceModel.MODEL_ID, instanceX2)
                .get(ProjectModel.ID))
            .isEqualTo(Projects.projectOf(SiteModel.MODEL_ID, siteXId)
                .get(ProjectModel.ID));
        assertThat((Integer) projectShared.get(ProjectModel.ID))
            .as("step 3: the shared {X,Y} set is a DIFFERENT project than {X}")
            .isNotEqualTo(projectX.get(ProjectModel.ID));
        assertThat(HohenheimAccess.sameOwner(InstanceModel.MODEL_ID, instanceX1, instanceX2))
            .as("step 3: sameOwner still holds within the adopted project").isTrue();
        assertThat(HohenheimAccess.sameOwner(InstanceModel.MODEL_ID, instanceX1, instanceY1))
            .as("step 3: and still separates different owners").isFalse();

        // 4. REACH AFTER: everyone reaches exactly what they reached before, now
        //    through membership expansion instead of direct grants.
        assertThat(HohenheimAccess.canManageInstance(principalX, instanceX1))
            .as("step 4: X still reaches x1").isTrue();
        assertThat(HohenheimAccess.canManageInstance(principalX, instanceX2))
            .as("step 4: X still reaches x2").isTrue();
        assertThat(HohenheimAccess.canManageSite(principalX, siteSharedId))
            .as("step 4: X still reaches the shared site").isTrue();
        assertThat(HohenheimAccess.canManageSite(principalX, siteXId))
            .as("step 4: X still reaches its own site").isTrue();
        assertThat(HohenheimAccess.canManageInstance(principalY, instanceY1))
            .as("step 4: Y still reaches y1").isTrue();
        assertThat(HohenheimAccess.canManageSite(principalY, siteSharedId))
            .as("step 4: Y still reaches the shared site").isTrue();
        assertThat(HohenheimAccess.canManageInstance(principalX, instanceY1))
            .as("step 4: X still does NOT reach Y's instance").isFalse();
        assertThat(HohenheimAccess.canManageSite(principalY, siteXId))
            .as("step 4: Y still does NOT reach X's site").isFalse();

        // 5. The DIRECT user grants are gone: one authority, not two.
        assertThat(HohenheimAccess.manageSubjectsOf(InstanceModel.MODEL_ID, instanceX1))
            .as("step 5: the record's subject set is the project group alone")
            .containsExactly(Projects.subjectOf(projectX));

        // 6. The operator-owned instance was left alone.
        assertThat(HohenheimAccess.manageSubjectsOf(InstanceModel.MODEL_ID, instanceOperator))
            .as("step 6: operator-owned means an EMPTY set, before and after")
            .isEmpty();
        assertThat(Projects.projectOf(InstanceModel.MODEL_ID, instanceOperator))
            .as("step 6: and no project was manufactured for the operator")
            .isNull();

        // 7. The quota followed the owner: X's live instances are charged to the
        //    project bucket now, and the operator bucket handed those slots back.
        String packX = HohenheimAccess.packSubjects(Projects.ownerSubjectsOf(projectX));
        assertThat(InstanceQuota.usedBy(packX))
            .as("step 7: X's project bucket carries its two live instances")
            .isEqualTo(2);
        assertThat((String) Models.get(InstanceModel.class).findById(instanceX1)
                .get(InstanceModel.QUOTA_BUCKET))
            .as("step 7: the charged-bucket column was restamped")
            .isEqualTo(InstanceQuota.bucketKeyOf(packX));
        assertThat(InstanceQuota.usedBy(""))
            .as("step 7: the operator bucket released the adopted slots (x1, x2, y1)")
            .isEqualTo(operatorUsedBefore - 3);

        // 8. The per-owner override row follows, so the CAP survives the move.
        Row cap = Models.get(InstanceQuotaModel.class).findById(quotaOverrideId);
        assertThat((String) cap.get(InstanceQuotaModel.SUBJECTS))
            .as("step 8: the override row is keyed to the project now")
            .isEqualTo(packX);
        assertThat(InstanceQuota.limitFor(packX))
            .as("step 8: and the project inherits the cap")
            .isEqualTo(7);

        // 9. The released-claim quarantine remembers the NEW owner spelling.
        Row claim = Models.get(ReleasedRouteClaimModel.class).findById(claimId);
        assertThat((String) claim.get(ReleasedRouteClaimModel.FORMER_SUBJECTS))
            .as("step 9: the ledger's former-owner column was rewritten")
            .isEqualTo(packX);
    }

    /** Running the heal again moves NOTHING: adopted sets are recognized, not re-adopted. */
    @Test
    @Order(2)
    void adoptionIsIdempotent() {
        ProjectAdoption.Result second = ProjectAdoption.run();
        assertThat(second.projectsCreated())
            .as("step 1: a second run creates no projects").isZero();
        assertThat(second.recordsAdopted())
            .as("step 1: and adopts no records").isZero();
        assertThat(second.bucketsMoved())
            .as("step 1: and moves no reservations").isZero();
    }
}
