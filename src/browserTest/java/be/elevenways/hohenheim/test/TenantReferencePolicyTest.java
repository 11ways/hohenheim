package be.elevenways.hohenheim.test;

import be.elevenways.hohenheim.HohenheimSlugs;
import be.elevenways.hohenheim.HohenheimIds;
import be.elevenways.hohenheim.model.AccessListModel;
import be.elevenways.hohenheim.model.GitProviderModel;
import be.elevenways.hohenheim.HohenheimCapabilities;
import be.elevenways.hohenheim.server.source.GiteaProviderKind;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.auth.model.GrantSubjectType;
import be.elevenways.zenit.auth.model.UserPrincipal;
import be.elevenways.zenit.auth.server.RecordGrants;
import be.elevenways.zenit.cms.common.panel.Panel;
import be.elevenways.zenit.cms.common.panel.PanelRegistry;
import be.elevenways.zenit.cms.server.panel.PanelRecordSources;
import be.elevenways.zenit.common.Zenit;
import be.elevenways.zenit.common.data.RecordSource;
import be.elevenways.zenit.common.data.RecordSourceQuery;
import be.elevenways.zenit.common.data.RecordSourceRegistry;
import be.elevenways.zenit.common.edit.EditContext;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.field.Field;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.orm.query.SortOrder;
import be.elevenways.zenit.common.security.AccessContext;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Access lists and git providers carry a core REFERENCE policy beside their owned /manage scope: a tenant's pickers
 * offer the rows it may USE (shared ones plus its own), its /manage list only the rows it OWNS, and neither ever names
 * another tenant's private row.
 */
class TenantReferencePolicyTest extends HohenheimTestBase {

    private static UserPrincipal tenant;
    private static TestSession tenantAuth;

    @BeforeAll
    static void seed() {
        int tenantId = ApiSupport.user("reference-tenant@hohenheim.local", "Reference Tenant");
        int strangerId = ApiSupport.user("reference-stranger@hohenheim.local", "Reference Stranger");
        tenant = new UserPrincipal(tenantId, "Reference Tenant");

        int ownList = list("Reference Own List", false);
        int strangerList = list("Reference Stranger List", false);
        list("Reference Shared List", true);
        RecordGrants.grant(GrantSubjectType.USER, tenantId, AccessListModel.MODEL_ID, ownList,
            HohenheimCapabilities.MANAGE, true);
        RecordGrants.grant(GrantSubjectType.USER, strangerId, AccessListModel.MODEL_ID, strangerList,
            HohenheimCapabilities.MANAGE, true);

        int ownForge = provider("Reference Own Forge", "own", false);
        int strangerForge = provider("Reference Stranger Forge", "stranger", false);
        provider("Reference Shared Forge", "shared", true);
        RecordGrants.grant(GrantSubjectType.USER, tenantId, GitProviderModel.MODEL_ID, ownForge,
            HohenheimCapabilities.MANAGE, true);
        RecordGrants.grant(GrantSubjectType.USER, strangerId, GitProviderModel.MODEL_ID, strangerForge,
            HohenheimCapabilities.MANAGE, true);

        tenantAuth = sessionFor(tenantId);
    }

    @Test
    void aTenantsPickersOfferTheReferencePolicyWhileItsListKeepsTheOwnedScope() throws Exception {
        Panel manage = Objects.requireNonNull(PanelRegistry.get(HohenheimIds.id(HohenheimSlugs.MANAGE)),
            "the /manage panel is registered");
        AccessContext access = AccessContext.of(TenantConduits.stubFor(tenant));
        EditContext onManage = EditContext.of(access).pickerSources(PanelRecordSources.pickers(manage));

        // 1. Each model declares ONE reference policy, and a picker on the /manage surface reads it.
        RecordSource<?> lists = RecordSourceRegistry.INSTANCE.referencePolicyFor(AccessListModel.MODEL_ID);
        RecordSource<?> forges = RecordSourceRegistry.INSTANCE.referencePolicyFor(GitProviderModel.MODEL_ID);
        assertThat(lists).as("step 1: access lists declare a reference policy").isNotNull();
        assertThat(forges).as("step 1: git providers declare a reference policy").isNotNull();
        assertThat(EditContext.pickerSource(onManage, null, AccessListModel.MODEL_ID))
            .as("step 1: the /manage access-list picker reads the policy").isSameAs(lists);
        assertThat(EditContext.pickerSource(onManage, null, GitProviderModel.MODEL_ID))
            .as("step 1: the /manage git-provider picker reads the policy").isSameAs(forges);

        // 2. The policy offers the shared rows beside the owned ones, never a stranger's.
        assertThat(names(lists, AccessListModel.NAME, access))
            .as("step 2: usable access lists")
            .contains("Reference Own List", "Reference Shared List")
            .doesNotContain("Reference Stranger List");
        assertThat(names(forges, GitProviderModel.NAME, access))
            .as("step 2: usable git providers")
            .contains("Reference Own Forge", "Reference Shared Forge")
            .doesNotContain("Reference Stranger Forge");

        // 3. The chooser's own request, over the token the form renders, answers that same offering.
        HttpResponse<String> listPicker = httpPostDry("/zn/records/" + lists.idToken() + "/query",
            Zenit.DRY.stringify(RecordSourceQuery.matchAll()), tenantAuth.token(), tenantAuth.csrf());
        assertThat(listPicker.statusCode()).as("step 3: the chooser query answers").isEqualTo(200);
        assertThat(listPicker.body())
            .as("step 3: the chooser offers the shared list and the own one")
            .contains("Reference Own List", "Reference Shared List")
            .doesNotContain("Reference Stranger List");
        HttpResponse<String> forgePicker = httpPostDry("/zn/records/" + forges.idToken() + "/query",
            Zenit.DRY.stringify(RecordSourceQuery.matchAll()), tenantAuth.token(), tenantAuth.csrf());
        assertThat(forgePicker.body())
            .as("step 3: the chooser offers the shared forge and the own one")
            .contains("Reference Own Forge", "Reference Shared Forge")
            .doesNotContain("Reference Stranger Forge");

        // 4. The /manage lists keep the owned scope: a shared row is usable, never listed or editable there.
        HttpResponse<String> ownedLists = httpGet("/manage/access-lists", tenantAuth.token());
        assertThat(ownedLists.statusCode()).as("step 4: the /manage access-list list renders").isEqualTo(200);
        assertThat(ownedLists.body())
            .as("step 4: the tenant lists its own access list only")
            .contains("Reference Own List")
            .doesNotContain("Reference Shared List")
            .doesNotContain("Reference Stranger List");
        HttpResponse<String> ownedForges = httpGet("/manage/git-providers", tenantAuth.token());
        assertThat(ownedForges.body())
            .as("step 4: the tenant lists its own git provider only")
            .contains("Reference Own Forge")
            .doesNotContain("Reference Shared Forge")
            .doesNotContain("Reference Stranger Forge");
    }

    /** Every name the source offers the caller, read as a picker reads it. */
    private static List<String> names(RecordSource<?> source, Field<?, ?> name, AccessContext access) {
        List<String> names = new ArrayList<>();
        for (Row row : source.forReferences().buildQuery(null, null, null, SortOrder.ASC, null, access).all()) {
            names.add(String.valueOf(row.get(name)));
        }
        return names;
    }

    private static int list(String name, boolean shared) {
        Model model = Models.get(AccessListModel.class);
        Row row = model.createEmptyRow();
        row.set(AccessListModel.NAME, name);
        row.set(AccessListModel.SATISFY, AccessListModel.SATISFY_ANY);
        row.set(AccessListModel.SHARED, shared);
        model.save(row);
        return row.get(AccessListModel.ID);
    }

    private static int provider(String name, String host, boolean shared) {
        Model model = Models.get(GitProviderModel.class);
        Row row = model.createEmptyRow();
        row.set(GitProviderModel.NAME, name);
        Identifier kind = GiteaProviderKind.ID;
        row.set(GitProviderModel.KIND, kind.toString());
        row.set(GitProviderModel.BASE_URL, "https://forge." + host + ".reference.example");
        row.set(GitProviderModel.SHARED, shared);
        row.set(GitProviderModel.ACCESS_TOKEN, "token-" + name);
        model.save(row);
        return row.get(GitProviderModel.ID);
    }
}
