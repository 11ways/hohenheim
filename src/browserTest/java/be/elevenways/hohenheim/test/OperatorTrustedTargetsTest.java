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
import be.elevenways.zenit.common.orm.datasource.context.SaveToDatasource;
import be.elevenways.zenit.common.orm.model.GlobalModelHooks;
import be.elevenways.zenit.common.orm.field.Field;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.validation.Violations;
import be.elevenways.zenit.server.net.OutboundUrlGuard;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;
import java.util.function.Consumer;

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
            assertThat(adminPostForm("/admin/sites/new",
                    "name=trusted-target-form&" + form + "&" + siteCreateEnvelope()).statusCode())
                .as("step 7: the operator creates the site through the form").isIn(302, 303);
            formSite[0] = sites.find().where(SiteModel.NAME.eq("trusted-target-form")).first().get(SiteModel.ID);
            TestSession session = sessionFor(adminId);
            assertThat(httpPostForm("/admin/sites/" + formSite[0],
                    "name=trusted-target-form&description=delegate+notes&" + form + "&" + siteEditEnvelope(formSite[0]),
                    session.token(), session.csrf())
                .statusCode()).as("step 7: the delegate's whole-form save of another field is accepted")
                .isIn(302, 303);
            assertThat((String) sites.findById(formSite[0]).get(SiteModel.DESCRIPTION)).isEqualTo("delegate notes");
            assertThat(httpPostForm("/admin/sites/" + formSite[0],
                    "name=trusted-target-form&" + form.replace("203.0.113.30", "127.0.0.2") + "&"
                        + siteEditEnvelope(formSite[0]), session.token(),
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

    @Test
    void mapConstructedTargetUsesActualChangesJourney() {
        Model providers = Models.get(GitProviderModel.class);
        int id = provider("map-target-provenance");
        int admin = ApiSupport.user("map-target-admin@hohenheim.local", "Map Delegate");
        GrantService.createDirectGrant(GrantSubjectType.USER, admin, HohenheimSources.ADMIN_ACCESS.value(), true);
        UserPrincipal delegated = new UserPrincipal(admin, "Map Delegate");
        try {
            RecordGrants.grant(GrantSubjectType.USER, admin, GitProviderModel.MODEL_ID, id, HohenheimAccess.MANAGE, true);
            assertThat(write(delegated, providers, id, GitProviderModel.BASE_URL, "https://delegate.example.test")).isNull();
            assertThat(providers.findById(id).get(GitProviderModel.TARGET_TRUSTED)).isEqualTo(false);
            RecordGrants.revoke(GrantSubjectType.USER, admin, GitProviderModel.MODEL_ID, id, HohenheimAccess.MANAGE);
            // 1. An operator changes a target through a map row, with no setter history.
            Row mapped = new Row(new LinkedHashMap<>(Map.of("id", id, "base_url", "https://map.example.test")), providers);
            assertThat(mapped.isWritten(GitProviderModel.BASE_URL)).as("step 1: no setter history").isFalse();
            TenantConduits.as(operator(), () -> providers.save(mapped));
            assertThat(providers.findById(id).get(GitProviderModel.TARGET_TRUSTED))
                .as("step 1: the changed target was vouched for by the operator").isEqualTo(true);

            // 2. A delegate cannot exploit the same map-constructor path on an operator-owned target.
            Row attempted = new Row(new LinkedHashMap<>(Map.of("id", id, "base_url", "http://127.0.0.1")), providers);
            assertThat(catchThrowable(() -> TenantConduits.as(new UserPrincipal(admin, "Map Delegate"),
                () -> providers.save(attempted)))).as("step 2: actual change cannot bypass authority")
                .isInstanceOf(Violations.class);
            assertThat(providers.findById(id).get(GitProviderModel.BASE_URL))
                .as("step 2: refused target stayed stored").isEqualTo("https://map.example.test");
        } finally {
            RecordGrants.revoke(GrantSubjectType.USER, admin, GitProviderModel.MODEL_ID, id, HohenheimAccess.MANAGE);
            HardDeletes.byId(providers, id);
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void inPlaceSettingsChangeUsesActualChangesJourney() {
        Model sites = Models.get(SiteModel.class);
        int id = site("in-place-target-provenance");
        int tenant = ApiSupport.user("in-place-owner@hohenheim.local", "In-place Owner");
        int admin = ApiSupport.user("in-place-admin@hohenheim.local", "In-place Delegate");
        GrantService.createDirectGrant(GrantSubjectType.USER, admin, HohenheimSources.ADMIN_ACCESS.value(), true);
        UserPrincipal delegated = new UserPrincipal(admin, "In-place Delegate");
        try {
            // 1. The delegate's tenant-owned target clears provenance.
            RecordGrants.grant(GrantSubjectType.USER, tenant, SiteModel.MODEL_ID, id, HohenheimAccess.MANAGE, true);
            assertThat(write(delegated, sites, id, SiteModel.SETTINGS, forward("203.0.113.21"))).isNull();
            assertThat(trusted(id)).as("step 1: initially untrusted").isFalse();
            RecordGrants.revoke(GrantSubjectType.USER, tenant, SiteModel.MODEL_ID, id, HohenheimAccess.MANAGE);

            // 2. Mutating the loaded map bypasses setters but not the operator's actual-change vouch.
            Row row = sites.findById(id);
            ((Map<String, Object>) row.get("settings")).put("forward_host", "127.0.0.8");
            assertThat(row.isWritten(SiteModel.SETTINGS)).as("step 2: no settings setter").isFalse();
            TenantConduits.as(operator(), () -> sites.save(row));
            assertThat(trusted(id)).as("step 2: changed map target is operator-vouched").isTrue();

            // 3. The delegate's in-place change is refused too.
            Row attempted = sites.findById(id);
            ((Map<String, Object>) attempted.get("settings")).put("forward_host", "127.0.0.9");
            assertThat(catchThrowable(() -> TenantConduits.as(delegated, () -> sites.save(attempted))))
                .as("step 3: mutable map does not bypass the gate").isInstanceOf(Violations.class);
            assertThat(forwardHost(id)).as("step 3: stored target is unchanged").isEqualTo("127.0.0.8");
        } finally {
            RecordGrants.revoke(GrantSubjectType.USER, tenant, SiteModel.MODEL_ID, id, HohenheimAccess.MANAGE);
            HardDeletes.byId(sites, id);
        }
    }

    @Test
    void batchSaveThenNameOnlyDoesNotVouchJourney() {
        Model sites = Models.get(SiteModel.class);
        int id = site("batch-target-provenance");
        int admin = ApiSupport.user("batch-target-admin@hohenheim.local", "Batch Delegate");
        GrantService.createDirectGrant(GrantSubjectType.USER, admin, HohenheimSources.ADMIN_ACCESS.value(), true);
        UserPrincipal delegated = new UserPrincipal(admin, "Batch Delegate");
        try {
            // 1. A tenant-owned target written by a delegate is untrusted, including through saveAll.
            RecordGrants.grant(GrantSubjectType.USER, admin, SiteModel.MODEL_ID, id, HohenheimAccess.MANAGE, true);
            Row row = sites.findById(id);
            row.set(SiteModel.SETTINGS, forward("203.0.113.22"));
            TenantConduits.as(delegated, () -> sites.saveAll(List.of(row)));
            assertThat(trusted(id)).as("step 1: delegated batch target is untrusted").isFalse();
            RecordGrants.revoke(GrantSubjectType.USER, admin, SiteModel.MODEL_ID, id, HohenheimAccess.MANAGE);

            // 2. Reusing that row for an operator name-only save must not replay the earlier intent.
            row.set(SiteModel.NAME, "batch-target-renamed");
            TenantConduits.as(operator(), () -> sites.save(row));
            assertThat(trusted(id)).as("step 2: name-only save does not vouch for the batch target").isFalse();
        } finally {
            RecordGrants.revoke(GrantSubjectType.USER, admin, SiteModel.MODEL_ID, id, HohenheimAccess.MANAGE);
            HardDeletes.byId(sites, id);
        }
    }

    @Test
    void rollbackRetainsIntentAndCommitClearsItJourney() {
        Model users = Models.get(UserModel.class);
        int id = ApiSupport.user("rollback-intent@hohenheim.local", "Before rollback");
        Row row = users.findById(id);
        row.set(UserModel.DISPLAY_NAME, "Pending rollback");
        // 1. An inner successful save cannot clear the outer transaction's intent on rollback.
        assertThat(catchThrowable(() -> AuthModels.datasource().withTransaction(tx -> {
            users.save(row);
            throw new IllegalStateException("deliberate outer rollback");
        }))).isInstanceOf(IllegalStateException.class);
        assertThat(row.isWritten(UserModel.DISPLAY_NAME)).as("step 1: rollback retained intent").isTrue();
        assertThat(users.findById(id).get(UserModel.DISPLAY_NAME)).as("step 1: storage rolled back")
            .isEqualTo("Before rollback");
        // 2. A subsequent outer commit clears precisely the persisted intent.
        AuthModels.datasource().withTransaction(tx -> {
            users.save(row);
            assertThat(row.isWritten(UserModel.DISPLAY_NAME)).as("step 2: intent lives until outer commit").isTrue();
        });
        assertThat(row.isWritten(UserModel.DISPLAY_NAME)).as("step 2: commit completed intent").isFalse();
    }

    @Test
    void upsertAndAfterSaveStagingCompleteOnlyPersistedIntentJourney() {
        Model users = Models.get(UserModel.class);
        int id = ApiSupport.user("after-save-intent@hohenheim.local", "Original");
        Row row = users.findById(id);
        // 1. Successful upsert and upsertAll complete the same lifetime as save.
        row.set(UserModel.DISPLAY_NAME, "Upserted");
        users.upsert(row);
        assertThat(row.isWritten(UserModel.DISPLAY_NAME)).as("step 1: upsert completed intent").isFalse();
        row.set(UserModel.DISPLAY_NAME, "Batch upserted");
        users.upsertAll(List.of(row));
        assertThat(row.isWritten(UserModel.DISPLAY_NAME)).as("step 1: upsertAll completed intent").isFalse();
        // 1b. Insert-if-absent consumes intent only when a row was actually inserted.
        Row inserted = users.createEmptyRow();
        inserted.set(UserModel.ID, id + 1_000_000);
        inserted.set(UserModel.EMAIL, "insert-intent@hohenheim.local");
        inserted.set(UserModel.DISPLAY_NAME, "Inserted intent");
        try {
            assertThat(users.insertIfAbsent(inserted)).as("step 1b: inserted").isTrue();
            assertThat(inserted.isWritten(UserModel.DISPLAY_NAME)).as("step 1b: insert completed intent").isFalse();
            inserted.set(UserModel.DISPLAY_NAME, "Not inserted");
            assertThat(users.insertIfAbsent(inserted)).as("step 1b: existing key was not overwritten").isFalse();
            assertThat(inserted.isWritten(UserModel.DISPLAY_NAME)).as("step 1b: unapplied intent is retained").isTrue();
        } finally {
            HardDeletes.byId(users, id + 1_000_000);
        }
        // 2. An after-save hook's next write, even to the same field, remains staged after commit.
        Consumer<SaveToDatasource> hook = context -> {
            if (context.getRow() == row) {
                row.set(UserModel.DISPLAY_NAME, "Staged after save");
            }
        };
        GlobalModelHooks.addAfterSaveHook(hook);
        try {
            row.set(UserModel.DISPLAY_NAME, "Persisted before hook");
            users.save(row);
            assertThat(users.findById(id).get(UserModel.DISPLAY_NAME)).as("step 2: hook value was not persisted")
                .isEqualTo("Persisted before hook");
            assertThat(row.isWritten(UserModel.DISPLAY_NAME)).as("step 2: after-save staged intent survives").isTrue();
        } finally {
            GlobalModelHooks.removeAfterSaveHook(hook);
        }
        // 3. Persist that staged field normally, then its intent is complete.
        users.save(row);
        assertThat(row.isWritten(UserModel.DISPLAY_NAME)).as("step 3: staged value finally persisted").isFalse();
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
        int id = Models.get(UserModel.class).find().where(UserModel.EMAIL.eq("test@hohenheim.local")).first()
            .get(UserModel.ID);
        return new UserPrincipal(id, "Test Admin");
    }
}
