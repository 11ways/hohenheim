package be.elevenways.hohenheim.test;

import be.elevenways.hohenheim.HohenheimSlugs;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.server.cms.SiteWrites;
import be.elevenways.hohenheim.model.StoredRows;
import be.elevenways.zenit.auth.model.UserModel;
import be.elevenways.zenit.auth.server.ApiKeyService;
import be.elevenways.zenit.auth.server.AuthModels;
import be.elevenways.zenit.cms.test.support.PanelResourceCalls;
import be.elevenways.zenit.common.Zenit;
import be.elevenways.zenit.common.data.RecordSourceQuery;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A trashed site leaves every surface that reads live sites -- the site picker's record source and
 * the automation API -- while the admin record page opens it from the Trash, and a restore brings it
 * back to all of them, with no surface spelling a filter of its own.
 *
 * @author Jelle De Loecker
 * @since  0.1.0
 */
class SoftDeleteSurfacesTest extends HohenheimTestBase {

    private static final String NAME = "softdel-surface-site";

    private static String keyAdmin;

    @BeforeAll
    static void seed() {
        int adminId = AuthModels.users().find()
            .where(UserModel.EMAIL.eq("test@hohenheim.local")).first().get(UserModel.ID);
        keyAdmin = ApiKeyService.create(adminId, NAME + "-admin", List.of("hohenheim.*"), null)
            .plaintext();
    }

    @Test
    void aTrashedSiteLeavesEverySurfaceAndARestoreBringsItBack() throws Exception {
        SiteModel sites = Models.get(SiteModel.class);
        // A crashed earlier run may have left the slug behind, trashed or not.
        HardDeletes.where(sites, SiteModel.SLUG.eq(NAME));
        int siteId = site();
        try {
            // 1. POSITIVE ANCHOR: the live site is on every surface.
            assertThat(adminGet("/admin/sites/" + siteId).statusCode())
                .as("step 1: the record page opens").isEqualTo(200);
            assertThat(pickerBody()).as("step 1: the site picker offers it").contains(NAME);
            assertThat(keyGet(keyAdmin, "/api/v1/sites").body())
                .as("step 1: the API lists it").contains(NAME);

            // 2. Trashed through the admin delete, it is on none of them.
            PanelResourceCalls.delete(HohenheimSlugs.ADMIN, HohenheimSlugs.SITES, siteId,
                TenantConduits.operator());
            HttpResponse<String> inTrash = adminGet("/admin/sites/" + siteId);
            assertThat(inTrash.statusCode()).as("step 2: the record page opens from the Trash").isEqualTo(200);
            // The Trash's restore is core's archive restore, placed on the shared invoke route.
            assertThat(inTrash.body()).as("step 2: offering its restore")
                .contains("/admin/sites/invoke/" + SiteWrites.RESTORE.id().getNamespace() + "."
                    + SiteWrites.RESTORE.id().getPath());
            assertThat(pickerBody()).as("step 2: the picker no longer offers it").doesNotContain(NAME);
            assertThat(keyGet(keyAdmin, "/api/v1/sites").body())
                .as("step 2: the API list drops it").doesNotContain(NAME);
            assertThat(keyGet(keyAdmin, "/api/v1/sites/" + siteId).statusCode())
                .as("step 2: and the API detail is the uniform 404").isEqualTo(404);

            // 3. The behaviour's restore puts it back everywhere.
            SiteModel.SOFT_DELETE.restore(StoredRows.byId(sites, siteId));
            assertThat(adminGet("/admin/sites/" + siteId).statusCode())
                .as("step 3: the record page opens again").isEqualTo(200);
            assertThat(pickerBody()).as("step 3: the picker offers it again").contains(NAME);
            assertThat(keyGet(keyAdmin, "/api/v1/sites").body())
                .as("step 3: the API lists it again").contains(NAME);
        } finally {
            HardDeletes.byId(sites, siteId);
        }
    }

    private String pickerBody() throws Exception {
        String query = Zenit.DRY.stringify(new RecordSourceQuery(NAME, null, null, false, null, null));
        HttpResponse<String> picker = httpPostDry("/zn/records/hohenheim.site/query", query,
            sessionToken, csrfToken);
        assertThat(picker.statusCode()).as("the picker query answers").isEqualTo(200);
        return picker.body();
    }

    private static int site() {
        Row row = Models.get(SiteModel.class).createEmptyRow();
        row.set(SiteModel.NAME, NAME);
        row.set(SiteModel.SLUG, NAME);
        row.set(SiteModel.UPSTREAM_KIND, "hohenheim:static");
        row.set(SiteModel.SETTINGS, Map.of("root_path", "/tmp/" + NAME));
        row.set(SiteModel.STATUS, SiteModel.STATUS_ACTIVE);
        row.set(SiteModel.ENABLED, false);
        Models.get(SiteModel.class).save(row);
        return row.get(SiteModel.ID);
    }
}
