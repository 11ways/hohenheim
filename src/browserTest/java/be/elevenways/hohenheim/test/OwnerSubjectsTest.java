package be.elevenways.hohenheim.test;

import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.server.auth.GrantSubjects;
import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.protoblast.common.time.Now;
import be.elevenways.zenit.auth.model.GrantSubjectType;
import be.elevenways.zenit.auth.model.UserModel;
import be.elevenways.zenit.auth.model.UserPrincipal;
import be.elevenways.zenit.auth.server.AuthModels;
import be.elevenways.zenit.auth.server.RecordGrants;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.security.AccessContext;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * THE owner identity of a record ({@link HohenheimAccess#manageSubjectsOf}) answers from the
 * grants that still DECIDE something, spells its subjects through zenit-auth's subject
 * vocabulary in the stored format, and the one creator-grant funnel plants exactly that owner.
 *
 * AIDEV-NOTE: the counterfactual is an expired grant. The walk stops honouring it at its
 * expiry, but the owner set used to keep counting it, so a tenant whose manage grant had
 * lapsed was still "the owner" for sameOwner, the quota bucket and the released-claim ledger.
 */
class OwnerSubjectsTest extends HohenheimTestBase {

    private static int user(String email, String name) {
        Row row = AuthModels.users().createEmptyRow();
        row.set(UserModel.EMAIL, email);
        row.set(UserModel.DISPLAY_NAME, name);
        row.set(UserModel.ENABLED, true);
        row.set(UserModel.CREATED_AT, Now.instant());
        row.set(UserModel.UPDATED_AT, Now.instant());
        AuthModels.users().save(row);
        return row.get(UserModel.ID);
    }

    private static int site(String slug) {
        Model model = Models.get(SiteModel.class);
        Row row = model.createEmptyRow();
        row.set(SiteModel.NAME, slug);
        row.set(SiteModel.SLUG, slug);
        row.set(SiteModel.UPSTREAM_KIND, "hohenheim:static");
        row.set(SiteModel.SETTINGS, Map.of("root_path", "/tmp"));
        row.set(SiteModel.STATUS, SiteModel.STATUS_ACTIVE);
        row.set(SiteModel.ENABLED, false);
        model.save(row);
        return row.get(SiteModel.ID);
    }

    @Test
    void anExpiredManageGrantNoLongerMakesItsHolderTheOwner() {
        int holder = user("owner-subjects@hohenheim.local", "Owner Subjects Holder");
        int tenantSite = site("owner-subjects-tenant");
        int operatorSite = site("owner-subjects-operator");

        // 1. A live grant makes its holder the owner, spelled in the STORED token format
        //    production's packed owner sets already carry.
        RecordGrants.grant(GrantSubjectType.USER, holder, SiteModel.MODEL_ID, tenantSite,
            HohenheimAccess.MANAGE, true, Now.instant().plusSeconds(3_600));
        assertThat(HohenheimAccess.manageSubjectsOf(tenantSite))
            .as("step 1: a live grant is ownership").containsExactly("user:" + holder);
        assertThat(GrantSubjects.userToken(holder)).as("step 1: the token format never moves")
            .isEqualTo("user:" + holder);
        assertThat(HohenheimAccess.sameOwner(tenantSite, operatorSite))
            .as("step 1: a tenant-held site is not the operator's").isFalse();

        // 2. The same grant, expired, decides nothing -- in the walk AND here.
        RecordGrants.grant(GrantSubjectType.USER, holder, SiteModel.MODEL_ID, tenantSite,
            HohenheimAccess.MANAGE, true, Now.instant().minusSeconds(60));
        assertThat(HohenheimAccess.manageSubjectsOf(tenantSite))
            .as("step 2: an expired grant is no longer ownership").isEmpty();
        assertThat(HohenheimAccess.sameOwner(tenantSite, operatorSite))
            .as("step 2: so the site compares as operator-owned again").isTrue();
    }

    @Test
    void subjectTokensReadThroughTheSubjectVocabularyAndFailClosed() {
        int named = user("owner-subjects-label@hohenheim.local", "Labelled Subject");

        // 1. A known token parses to its member and labels as the subject's display name.
        GrantSubjects.Subject subject = GrantSubjects.parse("user:" + named);
        assertThat(subject).as("step 1: a stored token parses").isNotNull();
        assertThat(subject.type()).isEqualTo(GrantSubjectType.USER);
        assertThat(subject.id()).isEqualTo(named);
        assertThat(HohenheimAccess.subjectLabel("user:" + named))
            .as("step 1: and labels as the subject").isEqualTo("Labelled Subject");

        // 2. An unknown type or a malformed id parses to nothing and labels as its raw token.
        assertThat(GrantSubjects.parse("robot:1")).as("step 2: an unknown type").isNull();
        assertThat(GrantSubjects.parse("group:x")).as("step 2: a malformed id").isNull();
        assertThat(HohenheimAccess.subjectLabel("robot:1")).as("step 2: shown raw")
            .isEqualTo("robot:1");
    }

    @Test
    void theCreatorGrantFunnelPlantsTheCreationOwnerAndDropsTheStaleMemo() {
        int creator = user("owner-subjects-creator@hohenheim.local", "Creator");
        int created = site("owner-subjects-created");
        AccessContext ctx = AccessContext.of(TenantConduits.stubFor(
            new UserPrincipal(creator, "Creator")));

        // 1. Before the grant the request memo answers "not yours" (and caches it).
        assertThat(HohenheimAccess.reachesRecord(ctx, SiteModel.MODEL_ID, created,
            HohenheimAccess.MANAGE)).as("step 1: nothing planted yet").isFalse();

        // 2. The funnel plants manage for exactly the creation owner...
        HohenheimAccess.grantCreatorManage(SiteModel.MODEL_ID, created, ctx);
        assertThat(HohenheimAccess.manageSubjectsOf(created))
            .as("step 2: the creation owner owns the record")
            .isEqualTo(Set.of(GrantSubjects.userToken(creator)));

        // 3. ...and drops the memo, so the SAME request now sees its own grant.
        assertThat(HohenheimAccess.reachesRecord(ctx, SiteModel.MODEL_ID, created,
            HohenheimAccess.MANAGE)).as("step 3: the stale memo was dropped").isTrue();

        // 4. The compensation removes exactly that grant again.
        HohenheimAccess.revokeCreatorManage(SiteModel.MODEL_ID, created, ctx);
        assertThat(HohenheimAccess.manageSubjectsOf(created))
            .as("step 4: the compensated create owns nothing").isEmpty();
    }
}
