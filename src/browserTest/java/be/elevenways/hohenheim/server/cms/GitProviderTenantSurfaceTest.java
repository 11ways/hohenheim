package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.model.GitProviderModel;
import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.hohenheim.server.source.GiteaProviderKind;
import be.elevenways.hohenheim.test.ApiSupport;
import be.elevenways.hohenheim.test.HohenheimTestBase;
import be.elevenways.hohenheim.test.TenantConduits;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.zenit.auth.model.GrantSubjectType;
import be.elevenways.zenit.auth.model.UserPrincipal;
import be.elevenways.zenit.auth.server.RecordGrants;
import be.elevenways.zenit.cms.common.action.CmsActionResult;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.common.validation.Violations;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * The tenant's git-provider surface: a failed connection test is ONE generic sentence (no
 * port-scan oracle), SHARED cannot be moved through it, and a create hands the tenant its
 * creator grant.
 */
class GitProviderTenantSurfaceTest extends HohenheimTestBase {

    private static final String PREFIX = "gp-tenant-surface-";

    private static int tenantId;

    @BeforeAll
    static void seed() {
        tenantId = ApiSupport.user(PREFIX + "tenant@hohenheim.local", "Git Provider Tenant");
    }

    /**
     * The same unreachable provider tested on both surfaces: the operator reads the client's
     * own reason, the tenant one generic sentence that carries nothing about the network.
     */
    @Test
    void aFailedTestTellsATenantNothingAboutTheNetwork() {
        // 1. A provider whose endpoint refuses every connection (port 1 on loopback).
        Row provider = provider(PREFIX + "unreachable", "http://127.0.0.1:1", false);

        // 2. The operator surface keeps the detailed reason.
        CmsActionResult operator = new GitProviderResource().testConnection(provider);
        assertThat(operator)
            .as("step 2: a failed operator test is an error toast")
            .isInstanceOf(CmsActionResult.Toast.class);
        Microcopy operatorMessage = ((CmsActionResult.Toast) operator).message();
        assertThat(operatorMessage.key())
            .as("step 2: the operator reads the detailed failure sentence")
            .isEqualTo("test_failed");

        // 3. The tenant surface answers ONE generic sentence with no argument at all, so
        //    no connection error, timeout or TLS text can reach the toast.
        CmsActionResult tenant = new ManageGitProviderResource().testConnection(provider);
        assertThat(tenant)
            .as("step 3: a failed tenant test is an error toast too")
            .isInstanceOf(CmsActionResult.Toast.class);
        assertThat(((CmsActionResult.Toast) tenant).message())
            .as("step 3: the tenant toast is the generic sentence and nothing else")
            .isEqualTo(Microcopy.of("test_failed_generic").withFilter("scope", "git_provider"));
    }

    /** SHARED is the operator's declaration: the tenant resource refuses to move it. */
    @Test
    void theTenantSurfaceRefusesToPublishACredential() {
        ManageGitProviderResource resource = new ManageGitProviderResource();
        AccessContext tenant = AccessContext.of(TenantConduits.stubFor(
            new UserPrincipal(tenantId, "Git Provider Tenant")));
        Model providers = Models.get(GitProviderModel.class);
        long before = providers.find().count();

        // 1. A create handed a map that carries shared=true is refused before any write.
        Map<String, Object> published = values(PREFIX + "published");
        published.put(GitProviderModel.SHARED.getName(), true);
        Throwable createRefused = catchThrowable(() -> resource.persistRow(published, tenant));
        assertThat(createRefused)
            .as("step 1: a create publishing the credential is refused")
            .isInstanceOf(Violations.class);
        assertThat(((Violations) createRefused).all().get(0).fieldName())
            .as("step 1: the refusal names the shared field")
            .isEqualTo(GitProviderModel.SHARED.getName());
        assertThat(providers.find().count())
            .as("step 1: and nothing was written").isEqualTo(before);

        // 2. An ordinary create goes through and hands the tenant manage on its provider.
        Object key = resource.persistRow(values(PREFIX + "own"), tenant);
        int providerId = Integer.parseInt(String.valueOf(key));
        assertThat(HohenheimAccess.reachesRecord(tenant, GitProviderModel.MODEL_ID, providerId,
                HohenheimAccess.MANAGE))
            .as("step 2: the creator holds manage on what it registered")
            .isTrue();

        // 3. An update flipping shared on that very row is refused, and the row keeps its value.
        Row stored = providers.findById(providerId);
        Map<String, Object> flip = new HashMap<>();
        flip.put(GitProviderModel.SHARED.getName(), true);
        Throwable updateRefused = catchThrowable(() -> resource.updateRow(stored, flip, tenant));
        assertThat(updateRefused)
            .as("step 3: an update publishing the credential is refused")
            .isInstanceOf(Violations.class);
        assertThat((Boolean) providers.findById(providerId).get(GitProviderModel.SHARED))
            .as("step 3: the stored row stays private").isNotEqualTo(Boolean.TRUE);

        // 4. A write that does not touch shared is unaffected by the refusal.
        Map<String, Object> rename = new HashMap<>();
        rename.put(GitProviderModel.NAME.getName(), PREFIX + "renamed");
        resource.updateRow(providers.findById(providerId), rename, tenant);
        assertThat((String) providers.findById(providerId).get(GitProviderModel.NAME))
            .as("step 4: an ordinary edit still saves").isEqualTo(PREFIX + "renamed");
    }

    /**
     * The same rules on the MODEL write pipeline, which a revision restore, a peer write or a
     * direct save reach past every resource: SHARED is frozen for a tenant, and editing or
     * deleting a provider needs manage on it -- a shared provider a tenant may USE included.
     */
    @Test
    void theModelPipelineFreezesSharedAndAsksManageOnEveryWrite() {
        Model providers = Models.get(GitProviderModel.class);
        UserPrincipal principal = new UserPrincipal(tenantId, "Git Provider Tenant");
        Row operatorShared = provider(PREFIX + "operator-shared", "https://git.operator.example", true);
        int sharedId = operatorShared.get(GitProviderModel.ID);
        Row own = provider(PREFIX + "model-own", "https://git.tenant.example", false);
        int ownId = own.get(GitProviderModel.ID);
        RecordGrants.grant(
            GrantSubjectType.USER, tenantId,
            GitProviderModel.MODEL_ID, ownId, HohenheimAccess.MANAGE, true);
        try {
            // 1. Publishing its own credential by a direct save is refused as a frozen column.
            Throwable flip = catchThrowable(() -> TenantConduits.as(principal, () -> {
                Row row = providers.findById(ownId);
                row.set(GitProviderModel.SHARED, true);
                providers.save(row);
            }));
            assertThat(flip).as("step 1: a tenant cannot flip shared").isInstanceOf(Violations.class);
            assertThat(((Violations) flip).all().get(0).fieldName())
                .as("step 1: the refusal names the shared field").isEqualTo(GitProviderModel.SHARED.getName());
            assertThat((Boolean) providers.findById(ownId).get(GitProviderModel.SHARED))
                .as("step 1: the provider stays private").isNotEqualTo(Boolean.TRUE);

            // 2. Nor can a direct create be born shared.
            long before = providers.find().count();
            Throwable bornShared = catchThrowable(() -> TenantConduits.as(principal, () -> {
                Row row = providers.createEmptyRow();
                row.set(GitProviderModel.NAME, PREFIX + "born-shared");
                row.set(GitProviderModel.KIND, GiteaProviderKind.ID.toString());
                row.set(GitProviderModel.BASE_URL, "https://git.born.example");
                row.set(GitProviderModel.SHARED, true);
                providers.save(row);
            }));
            assertThat(bornShared).as("step 2: a shared create is refused").isInstanceOf(Violations.class);
            assertThat(providers.find().count()).as("step 2: and nothing was written").isEqualTo(before);

            // 3. The operator's shared provider (usable, not managed) cannot be edited...
            Throwable retarget = catchThrowable(() -> TenantConduits.as(principal, () -> {
                Row row = providers.findById(sharedId);
                row.set(GitProviderModel.BASE_URL, "https://attacker.example");
                providers.save(row);
            }));
            assertThat(retarget).as("step 3: editing a provider without manage is refused")
                .isInstanceOf(Violations.class);
            assertThat(((Violations) retarget).all().get(0).message().key())
                .as("step 3: by the authority check").isEqualTo("tenant_git_provider_not_managed");
            assertThat((String) providers.findById(sharedId).get(GitProviderModel.BASE_URL))
                .as("step 3: the stored base URL is untouched").isEqualTo("https://git.operator.example");

            // 4. ...nor deleted.
            Throwable delete = catchThrowable(() -> TenantConduits.as(principal, () ->
                providers.find().where(GitProviderModel.ID.eq(sharedId)).delete()));
            assertThat(delete).as("step 4: deleting a provider without manage is refused")
                .isInstanceOf(Violations.class);
            assertThat(providers.findById(sharedId)).as("step 4: it still exists").isNotNull();

            // 5. Positive control: the tenant edits and deletes the provider it manages.
            TenantConduits.as(principal, () -> {
                Row row = providers.findById(ownId);
                row.set(GitProviderModel.NAME, PREFIX + "model-own-renamed");
                providers.save(row);
            });
            assertThat((String) providers.findById(ownId).get(GitProviderModel.NAME))
                .as("step 5: a managed provider is editable").isEqualTo(PREFIX + "model-own-renamed");
            TenantConduits.as(principal, () ->
                providers.find().where(GitProviderModel.ID.eq(ownId)).delete());
            assertThat(providers.findById(ownId)).as("step 5: and deletable").isNull();
        } finally {
            RecordGrants.revoke(
                GrantSubjectType.USER, tenantId,
                GitProviderModel.MODEL_ID, ownId, HohenheimAccess.MANAGE);
            providers.find().where(GitProviderModel.ID.in(List.of(sharedId, ownId))).delete();
        }
    }

    private static Map<String, Object> values(String name) {
        Map<String, Object> values = new HashMap<>();
        values.put(GitProviderModel.NAME.getName(), name);
        values.put(GitProviderModel.KIND.getName(), GiteaProviderKind.ID.toString());
        values.put(GitProviderModel.BASE_URL.getName(), "https://git.example.com");
        values.put(GitProviderModel.ACCESS_TOKEN.getName(), "token-" + name);
        return values;
    }

    private static Row provider(String name, String baseUrl, boolean shared) {
        Model providers = Models.get(GitProviderModel.class);
        Row row = providers.createEmptyRow();
        row.set(GitProviderModel.NAME, name);
        row.set(GitProviderModel.KIND, GiteaProviderKind.ID.toString());
        row.set(GitProviderModel.BASE_URL, baseUrl);
        row.set(GitProviderModel.SHARED, shared);
        row.set(GitProviderModel.ACCESS_TOKEN, "token-" + name);
        providers.save(row);
        return row;
    }
}
