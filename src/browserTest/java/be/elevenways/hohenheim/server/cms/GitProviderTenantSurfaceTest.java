package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.model.GitProviderModel;
import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.hohenheim.server.source.GiteaProviderKind;
import be.elevenways.hohenheim.test.HohenheimTestBase;
import be.elevenways.hohenheim.test.TenantConduits;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.time.Now;
import be.elevenways.zenit.auth.model.UserModel;
import be.elevenways.zenit.auth.model.UserPrincipal;
import be.elevenways.zenit.auth.server.AuthModels;
import be.elevenways.zenit.cms.common.action.CmsActionResult;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.common.validation.Violations;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
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
        Row user = AuthModels.users().createEmptyRow();
        user.set(UserModel.EMAIL, PREFIX + "tenant@hohenheim.local");
        user.set(UserModel.DISPLAY_NAME, "Git Provider Tenant");
        user.set(UserModel.ENABLED, true);
        user.set(UserModel.CREATED_AT, Now.instant());
        user.set(UserModel.UPDATED_AT, Now.instant());
        AuthModels.users().save(user);
        tenantId = user.get(UserModel.ID);
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
