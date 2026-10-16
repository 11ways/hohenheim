package be.elevenways.hohenheim.test;

import be.elevenways.hohenheim.HohenheimCapabilities;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.protoblast.common.i18n.Locale;
import be.elevenways.zenit.auth.cms.SubjectTag;
import be.elevenways.zenit.auth.model.GrantSubjectType;
import be.elevenways.zenit.auth.model.RecordGrantModel;
import be.elevenways.zenit.auth.model.UserModel;
import be.elevenways.zenit.auth.model.UserPrincipal;
import be.elevenways.zenit.auth.server.AuthMail;
import be.elevenways.zenit.auth.server.PasswordService;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.server.setting.ServerSettings;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Response;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.junit.jupiter.api.Test;

import java.net.http.HttpResponse;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * An operator gives a person who has no account a virtual machine from its Access tab: the person is invited by mail,
 * accepts, finds the machine in their /manage, and loses it again when the operator revokes the access.
 *
 * @author Jelle De Loecker
 * @since  0.9.0
 */
class InstanceInviteJourneyTest extends HohenheimTestBase {

    private static final String PREFIX = "invite-journey-";
    private static final String INVITEE = PREFIX + "person@example.com";
    private static final String PASSWORD = "the invited person's own password";

    @Test
    void anInvitedPersonAcceptsFindsTheMachineInManageAndLosesItWhenRevoked() throws Exception {
        List<String> mailed = new CopyOnWriteArrayList<>();
        AuthMail.Sender previousSender = AuthMail.SENDER.require();
        String previousBase = ServerSettings.VALUES.getValue(ServerSettings.Network.MAIN_URL);
        ServerSettings.VALUES.setValue(ServerSettings.Network.MAIN_URL, this.baseUrl());
        AuthMail.SENDER.install(new InvitationCapture(mailed));
        int vmId = vm();
        String accessTab = "/admin/instances/" + vmId + "/page/access";
        BrowserContext personContext = null;
        try {
            // 1. The operator opens the machine's Access tab: beside the people who hold it, a form gives access by
            //    email address, saying a person without an account is invited.
            navigateToApp(accessTab);
            page.waitForSelector("[data-sharing-invite-form]");
            assertThat(page.locator("[data-sharing-invite]").textContent())
                .as("step 1: the form says a person without an account is invited").contains("invitation");

            // 2. The operator invites an address no account holds: the invitation is mailed, never shown, and the
            //    new account holds the machine before anyone accepted, though nobody can sign in as it yet.
            page.fill("[data-sharing-invite-form] pl-input[name='email'] input", INVITEE);
            page.locator("[data-sharing-invite-form] pl-button[type='submit']").click();
            page.waitForCondition(() -> mailed.size() == 1);
            page.waitForSelector("[data-sharing-invite-form]");
            assertThat(page.locator("[data-sharing-invite] [data-secret-reveal]").count())
                .as("step 2: a mailed invitation is never shown to the operator").isZero();
            Row account = Models.get(UserModel.class).findFirst(UserModel.EMAIL, INVITEE);
            assertThat(account).as("step 2: the invitation made the account").isNotNull();
            UUID personId = account.get(UserModel.ID);
            assertThat(PasswordService.findPasswordRow(personId)).as("step 2: with no password yet").isNull();
            assertThat(reaches(personId, vmId)).as("step 2: THE ASSERTION -- the grant holds from the start").isTrue();
            assertThat(adminGet(accessTab).body()).as("step 2: and the Access tab lists them")
                .contains("data-subject=\"" + new SubjectTag(GrantSubjectType.USER, personId).token() + "\"");
            String link = mailed.get(0);
            assertThat(link).as("step 2: the mailed link is an absolute accept link")
                .startsWith(this.baseUrl() + "/accept-invitation/");

            // 3. The person opens the mailed link in their own browser: the page names their address and lets them
            //    choose a name and a password, which signs them in.
            personContext = browser.newContext();
            Page person = personContext.newPage();
            Response opened = person.navigate(link);
            assertThat(opened.status()).as("step 3: the link opens the accept page").isEqualTo(200);
            person.waitForSelector("[data-accept-invitation]");
            assertThat(person.content()).as("step 3: naming the invited address").contains(INVITEE);
            person.fill("pl-input[name='display_name'] input", "Invited Person");
            person.fill("pl-input[name='new_password'] input", PASSWORD);
            person.locator("[data-accept-invitation] pl-button[type='submit']").click();
            person.waitForURL(url -> !url.contains("accept-invitation"));
            assertThat(PasswordService.verifyPassword(personId, PASSWORD)).as("step 3: their password is set").isTrue();

            // 4. Signed in as themselves, the machine is in their /manage.
            Response machines = person.navigate(this.baseUrl() + "/manage/instances");
            assertThat(machines.status()).as("step 4: /manage opens for them").isEqualTo(200);
            assertThat(person.content()).as("step 4: listing the machine").contains(PREFIX + "vm");
            Response overview = person.navigate(this.baseUrl() + "/manage/instances/" + vmId + "/page/overview");
            assertThat(overview.status()).as("step 4: and its page opens").isEqualTo(200);

            // 5. The operator revokes the access on the Access tab. The machine was all they held, so /manage no
            //    longer opens for them at all, its machine page included.
            HttpResponse<String> revoked = adminPostForm(
                "/admin/instances/invoke/zenit.save_record_sharing?ids=" + vmId,
                "_tab=access&_confirmed=1&access.__removed=" + new SubjectTag(GrantSubjectType.USER, personId).token());
            assertThat(revoked.statusCode()).as("step 5: the revocation saves").isIn(302, 303);
            assertThat(reaches(personId, vmId)).as("step 5: the grant is gone").isFalse();
            Response list = person.navigate(this.baseUrl() + "/manage/instances");
            assertThat(list.status()).as("step 5: /manage refuses someone who holds nothing").isEqualTo(403);
            assertThat(person.content()).as("step 5: the machine left their /manage").doesNotContain(PREFIX + "vm");
            Response gone = person.navigate(this.baseUrl() + "/manage/instances/" + vmId + "/page/overview");
            assertThat(gone.status()).as("step 5: and its page is refused too").isEqualTo(403);
        } finally {
            if (personContext != null) {
                personContext.close();
            }
            AuthMail.SENDER.install(previousSender);
            ServerSettings.VALUES.setValue(ServerSettings.Network.MAIN_URL, previousBase);
            Row account = Models.get(UserModel.class).findFirst(UserModel.EMAIL, INVITEE);
            if (account != null) {
                HardDeletes.where(Models.get(RecordGrantModel.class),
                    RecordGrantModel.SUBJECT_ID.eq(account.get(UserModel.ID)));
                HardDeletes.row(Models.get(UserModel.class), account);
            }
            HardDeletes.byId(Models.get(InstanceModel.class), vmId);
        }
    }

    private static boolean reaches(UUID userId, int instanceId) {
        AccessContext tenant = AccessContext.of(TenantConduits.stubFor(new UserPrincipal(userId, "Invited Person")));
        return HohenheimAccess.reachesRecord(tenant, InstanceModel.MODEL_ID, instanceId, HohenheimCapabilities.VIEW);
    }

    private static int vm() {
        Model instances = Models.get(InstanceModel.class);
        Row row = instances.createEmptyRow();
        row.set(InstanceModel.NAME, PREFIX + "vm");
        row.set(InstanceModel.KIND, "hohenheim:vm");
        row.set(InstanceModel.SETTINGS, new LinkedHashMap<>(Map.of("image", "alpine/3.22/cloud")));
        row.set(InstanceModel.STATUS, InstanceModel.STATUS_STOPPED);
        instances.save(row);
        return row.get(InstanceModel.ID);
    }

    /** A mail lane that keeps the invitation links it is handed; this journey sends no other mail. */
    private record InvitationCapture(List<String> links) implements AuthMail.Sender {

        @Override
        public void sendVerification(@NonNull String email, @Nullable String displayName, @Nullable Locale locale,
                                     @NonNull String link) {
            throw new AssertionError("no verification mail is expected");
        }

        @Override
        public void sendPasswordReset(@NonNull String email, @Nullable String displayName, @Nullable Locale locale,
                                      @NonNull String link) {
            throw new AssertionError("no reset mail is expected");
        }

        @Override
        public void sendSignInLink(@NonNull String email, @Nullable String displayName, @Nullable Locale locale,
                                   @NonNull String link) {
            throw new AssertionError("no sign-in link is expected");
        }

        @Override
        public void sendInvitation(@NonNull String email, @Nullable String inviter, @Nullable Locale locale,
                                   @NonNull String link) {
            this.links.add(link);
        }
    }
}
