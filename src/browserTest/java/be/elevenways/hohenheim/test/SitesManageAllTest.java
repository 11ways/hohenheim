package be.elevenways.hohenheim.test;

import be.elevenways.hohenheim.model.CertificateModel;
import be.elevenways.hohenheim.model.DatabaseModel;
import be.elevenways.hohenheim.model.DnsRecordModel;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.zenit.auth.AuthEndpoints;
import be.elevenways.zenit.auth.AuthKeys;
import be.elevenways.zenit.auth.cms.GrantsEditField;
import be.elevenways.zenit.auth.model.GrantModel;
import be.elevenways.zenit.auth.model.UserModel;
import be.elevenways.zenit.auth.model.UserPrincipal;
import be.elevenways.zenit.auth.server.AuthCookieSupport;
import be.elevenways.zenit.auth.server.AuthModels;
import be.elevenways.zenit.auth.server.GrantAdministration;
import be.elevenways.zenit.auth.server.GrantService;
import be.elevenways.zenit.auth.server.RecordGrants;
import be.elevenways.zenit.auth.server.ZenitAuth;
import be.elevenways.zenit.common.Zenit;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.common.security.KnownPermissions;
import be.elevenways.zenit.common.security.RecordCapabilityDecision;
import be.elevenways.zenit.common.security.csrf.CsrfTokens;
import be.elevenways.zenit.common.session.Session;
import be.elevenways.zenit.common.validation.Violation;
import be.elevenways.zenit.common.validation.Violations;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@code hohenheim.sites.manage_all}: authority over EVERY site WITHOUT
 * {@code hohenheim.admin.access}, which is the only other route to it and which also
 * bypasses every other model and capability.
 *
 * AIDEV-NOTE: the assertions are deliberately paired -- LIST plus BY-ID, on every step. A
 * test that only asked "does the by-id check pass" would go green on a naive implementation
 * that declares the type-level permission and stops there: the walk answers yes per record
 * while the panel probe, the list scope and the resource nav probes all read an id SET, which
 * cannot express "every record" and therefore answers empty. Empty reads as "reaches nothing",
 * so the holder would be 403'd out of /manage and shown an empty list while every by-id check
 * kept saying yes. Both halves, always.
 *
 * AIDEV-NOTE: what deliberately DOES flow from this permission besides the site rows is
 * everything site-manage already confers -- the sites' domains and previews, and the
 * hostname-derived DNS read scope (HostnameAuthority.canManage asks canManageSite). That is
 * not a widening: it is exactly what granting manage on each site individually would give,
 * which is the permission's definition. What must NOT flow is authority on models whose own
 * vocabulary this is not, and step 3 is that boundary.
 */
class SitesManageAllTest extends HohenheimTestBase {

    private static final String MANAGE_ALL = "hohenheim.sites.manage_all";
    private static final String MANAGE_ACCESS = "hohenheim.manage.access";
    /** Registered and DELEGABLE; the control that keeps step 4's refusal non-vacuous. */
    private static final String DELEGABLE_CONTROL = "hohenheim.instances.create";

    private static Integer alphaSiteId;
    private static Integer betaSiteId;
    private static Integer instanceId;
    private static Integer databaseId;
    private static Integer holderId;
    private static Integer peerId;
    private static String holderSession;

    @BeforeAll
    static void seedEverySiteFixtures() {
        alphaSiteId = site("ManageAll Alpha", "manageall-alpha");
        betaSiteId = site("ManageAll Beta", "manageall-beta");

        Row instance = Models.get(InstanceModel.class).createEmptyRow();
        instance.set(InstanceModel.NAME, "manageall-instance");
        instance.set(InstanceModel.KIND, "hohenheim:docker_container");
        instance.set(InstanceModel.SETTINGS,
            new LinkedHashMap<>(Map.of("image", "alpine", "tag", "latest")));
        instance.set(InstanceModel.STATUS, "stopped");
        Models.get(InstanceModel.class).save(instance);
        instanceId = instance.get(InstanceModel.ID);

        Row database = Models.get(DatabaseModel.class).createEmptyRow();
        database.set(DatabaseModel.NAME, "manageall-database");
        database.set(DatabaseModel.ENGINE, "postgres");
        database.set(DatabaseModel.DB_USER, "app");
        database.set(DatabaseModel.DB_NAME, "manageall_database");
        Models.get(DatabaseModel.class).save(database);
        databaseId = database.get(DatabaseModel.ID);

        holderId = user("manage-all@hohenheim.local", "Every Site Holder");
        peerId = user("manage-all-peer@hohenheim.local", "Peer");

        Session session = Zenit.getSessionStore().create();
        session.set(AuthKeys.USER_ID, holderId.longValue());
        session.set(CsrfTokens.TOKEN, ZenitAuth.randomToken());
        Zenit.getSessionStore().save(session);
        holderSession = session.token().secret();
    }

    /**
     * The whole permission in one walk: it opens every site on BOTH surfaces, an explicit
     * denial of the panel gate closes both again, it reaches nothing on any other model, and
     * its holder cannot hand it on.
     */
    @Test
    void manageAllOpensEverySiteAndNothingElseAndCannotBeHandedOn() throws Exception {
        // 0. The vocabulary really carries it, and carries it as non-delegable. An
        //    unregistered permission is one no admin can find in the grants editor.
        assertThat(KnownPermissions.all())
            .as("step 0: the permission is offered by the grants editor")
            .contains(MANAGE_ALL);
        assertThat(KnownPermissions.isDelegable(MANAGE_ALL))
            .as("step 0: and it is registered NON-delegable")
            .isFalse();

        // 0b. The control: with nothing granted, the holder reaches nothing at all. Without
        //     this the rest could pass on authority that was already there.
        assertThat(holderGet("/manage").statusCode())
            .as("step 0b: an ungranted login has no /manage panel")
            .isEqualTo(403);
        assertThat(HohenheimAccess.canManageSite(holderContext(), alphaSiteId))
            .as("step 0b: and no site")
            .isFalse();

        GrantService.createDirectGrant("user", holderId, MANAGE_ALL, true);
        try {
            // 1. THE claim, both halves at once. The holder has NO record grant on either
            //    site and NEITHER hohenheim.admin.access NOR hohenheim.manage.access.
            assertThat(GrantService.listDirectGrants("user", holderId))
                .as("step 1: the holder's only grant is manage_all -- no admin, no panel grant")
                .allMatch(grant -> MANAGE_ALL.equals(grant.get(GrantModel.PERMISSION)));

            AccessContext ctx = holderContext();
            assertThat(HohenheimAccess.isAdmin(ctx))
                .as("step 1: and it is emphatically not the admin permission")
                .isFalse();

            // 1a. BY-ID: the walk decides on the type-level row, not on a grant.
            assertThat(ctx.capabilityDecision(SiteModel.MODEL_ID, alphaSiteId,
                    HohenheimAccess.MANAGE))
                .as("step 1a: the type-level row decides a site the holder was never granted")
                .isEqualTo(RecordCapabilityDecision.TYPE_LEVEL);
            assertThat(HohenheimAccess.canManageSite(holderContext(), betaSiteId))
                .as("step 1a: every site, not one of them")
                .isTrue();

            // 1b. SET-WISE: the scope says ALL, so the criteria funnel adds NO constraint.
            //     This is the half a by-id-only test cannot see: under a naive wiring it
            //     answers matchNone() and empties every list.
            assertThat(HohenheimAccess.capabilityScope(holderContext(), SiteModel.MODEL_ID,
                    HohenheimAccess.MANAGE).isAll())
                .as("step 1b: the set-wise face must say ALL, never an empty id set")
                .isTrue();
            assertThat(HohenheimAccess.managedSiteScope(holderContext(),
                    Models.get(SiteModel.class), SiteModel.ID::in))
                .as("step 1b: so the shared scoping funnel constrains nothing")
                .isNull();

            // 1c. THE SURFACES: the panel is reachable and the LIST actually renders a site
            //     nobody granted this holder.
            //     The panel is FOLLOWED to its landing page: a bare isIn(200,302,303) was
            //     satisfied by a redirect to the login form, which is the exact failure
            //     this step exists to catch.
            HttpResponse<String> panel = holderGetFollowingRedirects("/manage");
            assertThat(panel.statusCode())
                .withFailMessage("step 1c: the panel must open on manage_all alone"
                    + " (HTTP %s at %s)", panel.statusCode(), panel.uri())
                .isEqualTo(200);
            assertThat(panel.uri().getPath())
                .withFailMessage("step 1c: the panel must LAND inside /manage, never on a"
                    + " login or setup page (landed on %s)", panel.uri())
                .startsWith("/manage");
            assertThat(panel.body())
                .as("step 1c: and the panel it landed on offers its sites section")
                .contains("/manage/sites");
            HttpResponse<String> list = holderGet(
                "/manage/sites?q=" + encoded("name = \"ManageAll Beta\""));
            assertThat(list.statusCode())
                .as("step 1c: the sites list answers")
                .isEqualTo(200);
            assertThat(list.body())
                .as("step 1c: and it CONTAINS an ungranted site -- the naive-wiring trap")
                .contains("ManageAll Beta");
            assertThat(holderGet("/manage/sites/" + alphaSiteId).statusCode())
                .as("step 1c: and the ungranted record's own page opens")
                .isEqualTo(200);

            // 2. THE PRECEDENCE TRAP. Gate denial sits AHEAD of the type-level row, and an
            //    ALL scope has no candidate for a downstream per-record check to re-confirm,
            //    so the mask that hides a wrong ordering disappears exactly here.
            GrantService.createDirectGrant("user", holderId, MANAGE_ACCESS, false);

            assertThat(holderContext().capabilityDecision(SiteModel.MODEL_ID, alphaSiteId,
                    HohenheimAccess.MANAGE))
                .as("step 2: an explicit gate denial must beat the type-level row")
                .isEqualTo(RecordCapabilityDecision.GATE_DENIED);
            assertThat(HohenheimAccess.capabilityScope(holderContext(), SiteModel.MODEL_ID,
                    HohenheimAccess.MANAGE).isNone())
                .as("step 2: and it must collapse the SET-WISE answer too, never leave it ALL")
                .isTrue();
            assertThat(HohenheimAccess.managedSiteScope(holderContext(),
                    Models.get(SiteModel.class), SiteModel.ID::in))
                .as("step 2: so the funnel matches nothing instead of everything")
                .isNotNull();
            assertThat(holderGet("/manage/sites").statusCode())
                .as("step 2: and the list surface is closed")
                .isEqualTo(403);
            assertThat(HohenheimAccess.canManageSite(holderContext(), alphaSiteId))
                .as("step 2: as is the by-id check")
                .isFalse();

            deleteGrants(holderId, MANAGE_ACCESS);
            assertThat(HohenheimAccess.canManageSite(holderContext(), alphaSiteId))
                .as("step 2: removing the denial restores it, so the refusal was the gate")
                .isTrue();

            // 3. THE BOUNDARY: this is a SITE permission. It confers nothing on the models
            //    hohenheim.admin.access would have handed over with it.
            AccessContext scoped = holderContext();
            assertThat(HohenheimAccess.capabilityScope(scoped, InstanceModel.MODEL_ID,
                    HohenheimAccess.VIEW).isNone())
                .as("step 3: no instance is reachable")
                .isTrue();
            assertThat(HohenheimAccess.capabilityScope(scoped, DatabaseModel.MODEL_ID,
                    HohenheimAccess.VIEW).isNone())
                .as("step 3: no managed database is reachable")
                .isTrue();
            assertThat(HohenheimAccess.capabilityScope(scoped, DnsRecordModel.MODEL_ID,
                    HohenheimAccess.VIEW).isNone())
                .as("step 3: no DNS record grant is conferred")
                .isTrue();
            assertThat(HohenheimAccess.capabilityScope(scoped, CertificateModel.MODEL_ID,
                    HohenheimAccess.VIEW).isNone())
                .as("step 3: no certificate is reachable")
                .isTrue();
            // The two capabilities this permission exists INSTEAD of admin access for.
            assertThat(HohenheimAccess.hasInstanceCapability(scoped, instanceId,
                    HohenheimAccess.EXEC))
                .as("step 3: exec on an instance stays admin-only")
                .isFalse();
            assertThat(HohenheimAccess.hasInstanceCapability(scoped, instanceId,
                    HohenheimAccess.IMAGE_ANY))
                .as("step 3: and so does running an arbitrary image")
                .isFalse();
            assertThat(HohenheimAccess.hasDatabaseCapability(scoped, databaseId,
                    HohenheimAccess.CREDENTIALS))
                .as("step 3: and a managed database's credentials")
                .isFalse();
            // The STATUS comes first on both: a 403 page contains no record name either,
            // so an absence asserted without it passes on a closed surface too.
            HttpResponse<String> instances = holderGet("/manage/instances");
            assertThat(instances.statusCode())
                .withFailMessage("step 3: the instances list must ANSWER for this holder,"
                    + " or the absence below is a refusal page rather than an empty list"
                    + " (HTTP %s)", instances.statusCode())
                .isEqualTo(200);
            assertThat(instances.body())
                .as("step 3: the instances list names no instance")
                .doesNotContain("manageall-instance");
            HttpResponse<String> databases = holderGet("/manage/databases");
            assertThat(databases.statusCode())
                .withFailMessage("step 3: the databases list must answer too (HTTP %s)",
                    databases.statusCode())
                .isEqualTo(200);
            assertThat(databases.body())
                .as("step 3: nor the databases list a database")
                .doesNotContain("manageall-database");

            // 4. CONTAINMENT: even holding the grant-administration boundary, the holder
            //    cannot mint its own authority for a peer.
            GrantService.createDirectGrant("user", holderId,
                AuthEndpoints.PERM_GRANTS_MANAGE.value(), true);
            GrantService.createDirectGrant("user", holderId, DELEGABLE_CONTROL, true);
            AccessContext actor = holderContext();

            // 4a. The control: a DELEGABLE permission the actor holds passes the same policy,
            //     so the refusal below is about delegability and not about the actor.
            GrantAdministration.requireAuthorizedDiff(actor, "user", peerId, "grants",
                List.of(new GrantsEditField.Entry(DELEGABLE_CONTROL, true)));

            // 4b. The claim.
            assertThatThrownBy(() -> GrantAdministration.requireAuthorizedDiff(
                    actor, "user", peerId, "grants",
                    List.of(new GrantsEditField.Entry(MANAGE_ALL, true))))
                .as("step 4b: an every-site holder may not confer every-site authority")
                .isInstanceOf(Violations.class)
                .satisfies(refused -> assertThat(refusalTargets((Violations) refused))
                    .as("step 4b: refused for DELEGABILITY, not for the boundary permission")
                    .contains("grant_delegate"));

            // 5. THE OTHER LANE, and the one step 4 could not see. Non-delegability lives
            //    on the PERMISSION side, and the RECORD-GRANT editor never asks about it --
            //    it asks about a CAPABILITY. So a holder of manage_all plus the panel gate
            //    passed the per-record boundary on EVERY site (the type-level row answers
            //    "allowed" for all of them) and could mint a peer's per-site `manage` grant
            //    installation-wide from /manage/sites/{id}/access: the same spread step 4b
            //    refuses, through a door that never checked.
            GrantService.createDirectGrant("user", holderId, MANAGE_ACCESS, true);
            AccessContext everySite = holderContext();
            assertThat(everySite.capabilityDecision(SiteModel.MODEL_ID, alphaSiteId,
                    HohenheimAccess.MANAGE))
                .as("step 5: the holder's authority over the site is REAL and unchanged")
                .isEqualTo(RecordCapabilityDecision.TYPE_LEVEL);
            assertThatThrownBy(() -> GrantAdministration.requireAuthorizedRecordDiff(
                    everySite, SiteModel.MODEL_ID, alphaSiteId, "access",
                    List.of(new GrantAdministration.RecordGrantChange(
                        "user", peerId, HohenheimAccess.MANAGE, true))))
                .as("step 5: but blanket authority over sites is not authority to hand one out")
                .isInstanceOf(Violations.class)
                .satisfies(refused -> assertThat(refusalTargets((Violations) refused))
                    .as("step 5: refused at the per-record BOUNDARY")
                    .contains("record_boundary"));
            assertThat(HohenheimAccess.canManageSite(TestAccessContexts.contextFor(
                    new UserPrincipal(peerId, "Peer")), alphaSiteId))
                .as("step 5: STATE -- the peer was given nothing")
                .isFalse();
            assertThat(GrantAdministration.holdsAnyDelegableCapability(everySite,
                    SiteModel.MODEL_ID, betaSiteId))
                .as("step 5: and the access page does not offer itself on any site either")
                .isFalse();

            // 6. THE CONTROL, so step 5 is a narrowing and not a shutdown: a RECORD-LEVEL
            //    manage holder still delegates manage on the site it actually holds. That
            //    is the delegation this lane exists for and it is untouched.
            Integer ownerId = user("manage-all-owner@hohenheim.local", "Site Owner");
            RecordGrants.grant("user", ownerId, SiteModel.MODEL_ID, alphaSiteId,
                HohenheimAccess.MANAGE, true);
            AccessContext owner = TestAccessContexts.contextFor(
                new UserPrincipal(ownerId, "Site Owner"));
            assertThat(GrantAdministration.requireAuthorizedRecordDiff(
                    owner, SiteModel.MODEL_ID, alphaSiteId, "access",
                    List.of(new GrantAdministration.RecordGrantChange(
                        "user", peerId, HohenheimAccess.MANAGE, true))))
                .as("step 6: a record-level manage holder may still delegate on its own site")
                .hasSize(1);
            assertThatThrownBy(() -> GrantAdministration.requireAuthorizedRecordDiff(
                    owner, SiteModel.MODEL_ID, betaSiteId, "access",
                    List.of(new GrantAdministration.RecordGrantChange(
                        "user", peerId, HohenheimAccess.MANAGE, true))))
                .as("step 6: and only there -- the boundary is still per record")
                .isInstanceOf(Violations.class);
        } finally {
            deleteGrants(holderId, MANAGE_ALL, MANAGE_ACCESS, DELEGABLE_CONTROL,
                AuthEndpoints.PERM_GRANTS_MANAGE.value());
        }
    }

    private static AccessContext holderContext() {
        return TestAccessContexts.contextFor(new UserPrincipal(holderId, "Every Site Holder"));
    }

    private static List<String> refusalTargets(Violations refused) {
        return refused.all().stream()
            .map(Violation::message)
            .map(message -> message.filters().get("target"))
            .toList();
    }

    private static void deleteGrants(int userId, String... permissions) {
        List<String> wanted = List.of(permissions);
        for (Row grant : GrantService.listDirectGrants("user", userId)) {
            if (wanted.contains(grant.get(GrantModel.PERMISSION))) {
                GrantService.deleteDirectGrant("user", userId, grant.get(GrantModel.ID));
            }
        }
    }

    private static Integer site(String name, String slug) {
        var model = Models.get(SiteModel.class);
        Row row = model.createEmptyRow();
        row.set(SiteModel.NAME, name);
        row.set(SiteModel.SLUG, slug);
        row.set(SiteModel.SITE_TYPE, "hohenheim:static");
        row.set(SiteModel.SETTINGS, Map.of("root_path", "/tmp"));
        row.set(SiteModel.STATUS, "active");
        row.set(SiteModel.ENABLED, true);
        model.save(row);
        return row.get(SiteModel.ID);
    }

    private static Integer user(String email, String displayName) {
        Row row = AuthModels.users().createEmptyRow();
        row.set(UserModel.EMAIL, email);
        row.set(UserModel.DISPLAY_NAME, displayName);
        row.set(UserModel.ENABLED, true);
        row.set(UserModel.CREATED_AT, Instant.now());
        row.set(UserModel.UPDATED_AT, Instant.now());
        AuthModels.users().save(row);
        return row.get(UserModel.ID);
    }

    private static String encoded(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    /** The same request, followed to its landing page, so a redirect proves where it went. */
    private HttpResponse<String> holderGetFollowingRedirects(String path) throws Exception {
        HttpClient client = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL).build();
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:" + getServerPort() + path))
            .header("Cookie", AuthCookieSupport.sessionCookieName() + "=" + holderSession)
            .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> holderGet(String path) throws Exception {
        HttpClient client = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER).build();
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:" + getServerPort() + path))
            .header("Cookie", AuthCookieSupport.sessionCookieName() + "=" + holderSession)
            .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
