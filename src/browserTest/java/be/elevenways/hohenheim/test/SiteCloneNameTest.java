package be.elevenways.hohenheim.test;

import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import org.junit.jupiter.api.Test;

import java.net.URLEncoder;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The name a site clone asks for: the dialog opens on a name no site holds, in no language of its
 * own, and a name whose slug IS taken -- by a trashed site the list no longer shows, or by a clone
 * that won a race -- is refused on the name field instead of failing as a generic error.
 *
 * @author Jelle De Loecker
 * @since 0.1.0
 */
class SiteCloneNameTest extends HohenheimTestBase {

    private static final String DUPLICATE = "This value is already in use by another record.";

    private Row site(String name) {
        return Models.get(SiteModel.class).find().withTrashed().where(SiteModel.NAME.eq(name)).first();
    }

    private Integer createRedirectSite(String name) throws Exception {
        HttpResponse<String> response = adminPostForm("/admin/sites/new",
            "name=" + URLEncoder.encode(name, StandardCharsets.UTF_8) + "&upstream_kind=hohenheim%3Aredirect"
            + "&settings.target_url=https%3A%2F%2Fexample.com&settings.http_status=301");
        assertThat(response.statusCode()).as("the site '%s' is created", name).isIn(302, 303);
        return site(name).get(SiteModel.ID);
    }

    private HttpResponse<String> clone(Integer siteId, String body) throws Exception {
        return adminPostForm("/admin/sites/" + siteId + "/action/clone_site", body);
    }

    @Test
    void theCloneNameOpensFreeAndATakenOneIsRefusedOnTheField() throws Exception {
        Integer origin = createRedirectSite("Clone Origin");
        createRedirectSite("Clone Origin 2");

        // 1. Opening the clone (a POST without the confirmation proof only shows its form) offers the
        //    first numbered name no site holds: "Clone Origin 2" is taken, so 3. No English "(copy)".
        HttpResponse<String> opened = clone(origin, "");
        assertThat(opened.statusCode()).as("step 1: the clone form is shown").isEqualTo(200);
        assertThat(opened.body()).as("step 1: it opens on the first free numbered name").contains("Clone Origin 3");
        assertThat(opened.body()).as("step 1: and never on an untranslated suffix").doesNotContain("(copy)");

        // 2. A site that is trashed still holds its slug (the constraint spans every row), yet the
        //    list, and every plain read, no longer shows it.
        Integer retired = createRedirectSite("Retired Name");
        assertThat(adminPostForm("/admin/sites/" + retired + "/delete", confirmed("")).statusCode())
            .as("step 2: the site is deleted").isIn(200, 302, 303);
        popFlash();
        assertThat((Object) site("Retired Name").get(SiteModel.DELETED_AT)).as("step 2: softly").isNotNull();
        assertThat(Models.get(SiteModel.class).find().where(SiteModel.NAME.eq("Retired Name")).first())
            .as("step 2: a plain read does not see it").isNull();

        // 3. Cloning under that name is refused ON the name, in the re-rendered form: the insert's
        //    own conflict is the check, so the answer is the field's refusal, never the generic
        //    failure a check that could not see the trashed row used to hand out.
        HttpResponse<String> refused = clone(origin, confirmed("name=Retired+Name"));
        assertThat(refused.statusCode()).as("step 3: the form comes back instead of a redirect").isEqualTo(200);
        assertThat(refused.body()).as("step 3: carrying the name's refusal").contains(DUPLICATE);
        assertThat(Models.get(SiteModel.class).find().withTrashed().where(SiteModel.NAME.eq("Retired Name")).count())
            .as("step 3: and nothing was created").isEqualTo(1);

        // 4. The name the form opened on is free, so taking it clones.
        HttpResponse<String> cloned = clone(origin, confirmed("name=Clone+Origin+3"));
        assertThat(cloned.statusCode()).as("step 4: the offered name clones").isIn(302, 303);
        assertThat((Boolean) site("Clone Origin 3").get(SiteModel.ENABLED)).as("step 4: disabled, like every clone")
            .isEqualTo(false);
    }
}
