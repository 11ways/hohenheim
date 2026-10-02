package be.elevenways.hohenheim.test;

import be.elevenways.hohenheim.HohenheimSources;
import be.elevenways.hohenheim.model.GitProviderModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.hohenheim.server.source.GiteaProviderKind;
import be.elevenways.hohenheim.server.upstream.TenantUpstreams;
import be.elevenways.zenit.auth.AuthEndpoints;
import be.elevenways.zenit.auth.model.GrantSubjectType;
import be.elevenways.zenit.auth.model.UserModel;
import be.elevenways.zenit.auth.model.UserPrincipal;
import be.elevenways.zenit.auth.server.GrantService;
import be.elevenways.zenit.auth.server.AuthModels;
import be.elevenways.zenit.auth.server.RecordGrants;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.field.Field;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.validation.Violations;
import be.elevenways.zenit.server.net.OutboundUrlGuard;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * Where an operator-owned site or git provider connects (an any-address fetch) is written under the non-delegable
 * {@code hohenheim.admin.system} alone, on the model write pipeline: a delegated admin keeps every other column, and a
 * tenant-owned record's upstream stays theirs to aim at the public internet.
 *
 * @author Jelle De Loecker
 * @since 0.9.0
 */
class OperatorTrustedTargetsTest extends HohenheimTestBase {

    @Test
    void onlyTheSystemTierAimsAnOperatorOwnedRecordJourney() throws Exception {
        int adminId = ApiSupport.user("trusted-target-admin@hohenheim.local", "Delegated Admin");
        // zenit-auth's own /admin prefix baseline demands auth.admin.access on top of the panel grant.
        GrantService.createDirectGrant(GrantSubjectType.USER, adminId, AuthEndpoints.PERM_ADMIN_ACCESS.value(), true);
        GrantService.createDirectGrant(GrantSubjectType.USER, adminId, HohenheimSources.ADMIN_ACCESS.value(), true);
        UserPrincipal delegated = new UserPrincipal(adminId, "Delegated Admin");
        int tenantId = ApiSupport.user("trusted-target-tenant@hohenheim.local", "Site Tenant");
        Model sites = Models.get(SiteModel.class);
        Model providers = Models.get(GitProviderModel.class);
        int operatorSite = site("trusted-target-operator");
        int tenantSite = site("trusted-target-tenant");
        int provider = provider("trusted-target-forge");
        RecordGrants.grant(GrantSubjectType.USER, tenantId, SiteModel.MODEL_ID, tenantSite, HohenheimAccess.MANAGE,
            true);
        Integer[] formSite = new Integer[1];
        try {
            // 1. The delegated admin aiming an operator-owned site at loopback is refused on the settings, and the
            //    trust flag and kind are just as much the system tier's.
            Throwable aimed = write(delegated, sites, operatorSite, SiteModel.SETTINGS, forward("127.0.0.1"));
            assertThat(aimed).as("step 1: a delegated admin cannot aim an operator-owned site")
                .isInstanceOf(Violations.class);
            assertThat(((Violations) aimed).all().get(0).fieldName()).as("step 1: refused on the settings")
                .isEqualTo(SiteModel.SETTINGS.getName());
            assertThat(((Violations) aimed).all().get(0).message().key()).as("step 1: by the system-tier rule")
                .isEqualTo("operator_trusted_target");
            assertThat(write(delegated, sites, operatorSite, SiteModel.TRUSTED_UPSTREAM, true))
                .as("step 1: nor flip its trust flag").isInstanceOf(Violations.class);
            assertThat(forwardHost(operatorSite)).as("step 1: the target is unchanged").isEqualTo("203.0.113.10");

            // 2. Every other column stays the delegated admin's.
            assertThat(write(delegated, sites, operatorSite, SiteModel.DESCRIPTION, "renamed by the delegate"))
                .as("step 2: the delegated admin edits the description").isNull();
            assertThat((String) sites.findById(operatorSite).get(SiteModel.DESCRIPTION))
                .isEqualTo("renamed by the delegate");

            // 3. A tenant-owned site is dialled at the public tier whatever it names: its upstream stays editable.
            assertThat(write(delegated, sites, tenantSite, SiteModel.SETTINGS, forward("203.0.113.20")))
                .as("step 3: a tenant-owned site's upstream is not an operator-trusted target").isNull();

            // 4. A create is operator-owned until someone is granted manage: the delegate cannot be born aiming it.
            Throwable born = catchThrowable(() -> TenantConduits.as(delegated, () -> {
                Row row = sites.createEmptyRow();
                row.set(SiteModel.NAME, "trusted-target-born");
                row.set(SiteModel.SLUG, "trusted-target-born");
                row.set(SiteModel.UPSTREAM_KIND, "hohenheim:address");
                row.set(SiteModel.SETTINGS, forward("10.0.0.5"));
                sites.save(row);
            }));
            assertThat(born).as("step 4: a delegated create naming a target is refused")
                .isInstanceOf(Violations.class);

            // 5. The operator provider's base URL is the system tier's; its name is the delegate's.
            Throwable forge = write(delegated, providers, provider, GitProviderModel.BASE_URL, "http://10.0.0.7:3000");
            assertThat(forge).as("step 5: a delegated admin cannot repoint an operator-owned forge")
                .isInstanceOf(Violations.class);
            assertThat(((Violations) forge).all().get(0).fieldName()).as("step 5: refused on the base URL")
                .isEqualTo(GitProviderModel.BASE_URL.getName());
            assertThat(write(delegated, providers, provider, GitProviderModel.NAME, "trusted-target-forge-renamed"))
                .as("step 5: but renames it").isNull();

            // 6. The operator (holding "*", so the system tier) aims both.
            assertThat(write(null, sites, operatorSite, SiteModel.SETTINGS, forward("127.0.0.1")))
                .as("step 6: the operator aims the site at loopback").isNull();
            assertThat(forwardHost(operatorSite)).isEqualTo("127.0.0.1");
            assertThat(write(null, providers, provider, GitProviderModel.BASE_URL, "http://10.0.0.7:3000"))
                .as("step 6: and the forge at the LAN").isNull();

            // 7. The admin form lane: the delegate re-posts the whole edit form of a site the operator created there,
            //    changing the description only, and is accepted; changing the host in the same form is refused.
            String form = "upstream_kind=hohenheim%3Aaddress&settings.forward_scheme=http"
                + "&settings.forward_host=203.0.113.30&settings.forward_port=8080&settings.rewrite_location=false";
            assertThat(adminPostForm("/admin/sites/new", "name=trusted-target-form&" + form).statusCode())
                .as("step 7: the operator creates the site through the form").isIn(302, 303);
            formSite[0] = sites.find().where(SiteModel.NAME.eq("trusted-target-form")).first().get(SiteModel.ID);
            TestSession session = sessionFor(adminId);
            assertThat(httpPostForm("/admin/sites/" + formSite[0],
                    "name=trusted-target-form&description=delegate+notes&" + form, session.token(), session.csrf())
                .statusCode()).as("step 7: the delegate's whole-form save of another field is accepted")
                .isIn(302, 303);
            assertThat((String) sites.findById(formSite[0]).get(SiteModel.DESCRIPTION)).isEqualTo("delegate notes");
            assertThat(httpPostForm("/admin/sites/" + formSite[0],
                    "name=trusted-target-form&" + form.replace("203.0.113.30", "127.0.0.2"), session.token(),
                    session.csrf()).statusCode())
                .as("step 7: the delegate's form aiming it at loopback is refused").isNotIn(302, 303);
            assertThat(forwardHost(formSite[0])).as("step 7: the host is unchanged").isEqualTo("203.0.113.30");
        } finally {
            RecordGrants.revoke(GrantSubjectType.USER, tenantId, SiteModel.MODEL_ID, tenantSite,
                HohenheimAccess.MANAGE);
            HardDeletes.byId(sites, operatorSite);
            HardDeletes.byId(sites, tenantSite);
            HardDeletes.byId(providers, provider);
            if (formSite[0] != null) {
                HardDeletes.byId(sites, formSite[0]);
            }
        }
    }

    @Test
    void aSiteUpstreamNobodyTrustedNeverGainsAnyAddressReachJourney() {
        int adminId = ApiSupport.user("provenance-admin@hohenheim.local", "Provenance Delegate");
        GrantService.createDirectGrant(GrantSubjectType.USER, adminId, HohenheimSources.ADMIN_ACCESS.value(), true);
        UserPrincipal delegated = new UserPrincipal(adminId, "Provenance Delegate");
        int tenantId = ApiSupport.user("provenance-tenant@hohenheim.local", "Provenance Tenant");
        Model sites = Models.get(SiteModel.class);
        int siteId = site("provenance-site");
        try {
            // 1. The operator's upstream is marked as the operator's.
            assertThat(trusted(siteId)).as("step 1: an operator-set upstream is marked").isTrue();

            // 2. While a tenant owns the site, a delegate aims it at loopback (a tenant site never dials it), which
            //    clears the mark; a delegate carrying the mark itself is not believed.
            RecordGrants.grant(GrantSubjectType.USER, tenantId, SiteModel.MODEL_ID, siteId, HohenheimAccess.MANAGE,
                true);
            assertThat(write(delegated, sites, siteId, SiteModel.SETTINGS, forward("127.0.0.1")))
                .as("step 2: a tenant-owned site's upstream is the delegate's to edit").isNull();
            assertThat(trusted(siteId)).as("step 2: the delegate's upstream is unmarked").isFalse();
            assertThat(write(delegated, sites, siteId, SiteModel.TARGET_TRUSTED, true))
                .as("step 2: a hand-carried mark is no refusal").isNull();
            assertThat(trusted(siteId)).as("step 2: and is never taken").isFalse();

            // 3. The tenant's grant goes: the site is operator-owned, yet its unmarked upstream is dialled at the
            //    public tier only, and loopback is refused.
            RecordGrants.revoke(GrantSubjectType.USER, tenantId, SiteModel.MODEL_ID, siteId, HohenheimAccess.MANAGE);
            Row site = sites.findById(siteId);
            assertThat(TenantUpstreams.isTenantOwned(site)).as("step 3: the site is operator-owned now").isFalse();
            assertThat(TenantUpstreams.publicOnly(site)).as("step 3: but its upstream stays public-only").isTrue();
            assertThat(TenantUpstreams.vet("http", "127.0.0.1", TenantUpstreams.publicOnly(site)))
                .as("step 3: so the dial to loopback is refused").isInstanceOf(OutboundUrlGuard.Refused.class);

            // 4. The operator edits another column only (as a request caller; a cell edit of the description): the
            //    loaded row holds the upstream, but this write never set it, so it vouches for nothing.
            assertThat(write(operator(), sites, siteId, SiteModel.DESCRIPTION, "reviewed by the operator"))
                .as("step 4: the operator edits the description").isNull();
            assertThat(trusted(siteId)).as("step 4: a write that never set the upstream does not mark it").isFalse();
            assertThat(TenantUpstreams.publicOnly(sites.findById(siteId))).as("step 4: still public-only").isTrue();

            // 5. The operator re-saves the upstream as the form shows it (the test body is system work, which vouches
            //    only for a target it changes): the upstream is the operator's now, and loopback is reached.
            Object shown = sites.findById(siteId).get(SiteModel.SETTINGS);
            assertThat(write(operator(), sites, siteId, SiteModel.SETTINGS, shown))
                .as("step 5: the operator re-saves the upstream unchanged").isNull();
            assertThat(trusted(siteId)).as("step 5: the re-save marks the upstream").isTrue();
            site = sites.findById(siteId);
            assertThat(TenantUpstreams.publicOnly(site)).as("step 5: any-address reach").isFalse();
            assertThat(TenantUpstreams.vet("http", "127.0.0.1", TenantUpstreams.publicOnly(site)))
                .as("step 5: loopback is dialled").isInstanceOf(OutboundUrlGuard.Allowed.class);
        } finally {
            RecordGrants.revoke(GrantSubjectType.USER, tenantId, SiteModel.MODEL_ID, siteId, HohenheimAccess.MANAGE);
            HardDeletes.byId(sites, siteId);
        }
    }

    private static boolean trusted(int siteId) {
        return Boolean.TRUE.equals(Models.get(SiteModel.class).findById(siteId).get(SiteModel.TARGET_TRUSTED));
    }

    /** One column written by a direct save, as {@code principal} (null: the test body's operator). */
    private static Throwable write(UserPrincipal principal, Model model, int id, Field<?, ?> field, Object value) {
        Runnable save = () -> {
            Row row = model.findById(id);
            row.set(field.getName(), value);
            model.save(row);
        };
        return catchThrowable(() -> {
            if (principal == null) {
                save.run();
            } else {
                TenantConduits.as(principal, save);
            }
        });
    }

    private static Map<String, Object> forward(String host) {
        return new LinkedHashMap<>(Map.of("forward_host", host, "forward_port", 80));
    }

    private static Object forwardHost(int siteId) {
        Object settings = Models.get(SiteModel.class).findById(siteId).get(SiteModel.SETTINGS);
        return settings instanceof Map<?, ?> map ? map.get("forward_host") : null;
    }

    private static int site(String name) {
        Model sites = Models.get(SiteModel.class);
        Row site = sites.createEmptyRow();
        site.set(SiteModel.NAME, name);
        site.set(SiteModel.SLUG, name);
        site.set(SiteModel.UPSTREAM_KIND, "hohenheim:address");
        site.set(SiteModel.SETTINGS, forward("203.0.113.10"));
        site.set(SiteModel.STATUS, SiteModel.STATUS_ACTIVE);
        site.set(SiteModel.ENABLED, true);
        sites.save(site);
        return site.get(SiteModel.ID);
    }

    private static int provider(String name) {
        Model providers = Models.get(GitProviderModel.class);
        Row row = providers.createEmptyRow();
        row.set(GitProviderModel.NAME, name);
        row.set(GitProviderModel.KIND, GiteaProviderKind.ID.toString());
        row.set(GitProviderModel.BASE_URL, "https://git.operator.example");
        row.set(GitProviderModel.ACCESS_TOKEN, "token-" + name);
        providers.save(row);
        return row.get(GitProviderModel.ID);
    }

    /** The seeded operator account (it holds "*"), as a request caller rather than the test body's system work. */
    private static UserPrincipal operator() {
        int id = AuthModels.users().find().where(UserModel.EMAIL.eq("test@hohenheim.local")).first()
            .get(UserModel.ID);
        return new UserPrincipal(id, "Test Admin");
    }
}
