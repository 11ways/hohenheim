package be.elevenways.hohenheim.test;

import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.URLEncoder;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A new site's slug is zenit's one slugifier: identical to the old regex spelling for an ASCII name, and
 * FOLDING a non-ASCII letter the regex used to drop.
 *
 * WHY IT EXISTS: SiteResource carried its own lowercase-and-regex slugify, a second spelling beside
 * zenit's Slugs; the switch must not move any ASCII slug an existing install already stores.
 */
class SiteSlugFoldTest extends HohenheimTestBase {

    private static final String[] NAMES = {"My  Slug Site!!", "Caf\u00e9 D\u00e9j\u00e0 Vu"};

    @AfterEach
    void cleanUp() {
        for (String name : NAMES) {
            Row site = siteNamed(name);
            if (site != null) {
                Models.get(SiteModel.class).delete(site);
            }
        }
    }

    @Test
    void aNewSiteSlugKeepsAsciiNamesAndFoldsAccents() throws Exception {
        // 1. An ASCII name slugs exactly as the old regex did: runs collapse, ends trimmed.
        assertThat(create(NAMES[0]).statusCode())
            .as("step 1: the ASCII-named site is created").isIn(302, 303);
        assertThat((String) siteNamed(NAMES[0]).get(SiteModel.SLUG))
            .as("step 1: its slug is what the retired regex produced")
            .isEqualTo("my-slug-site");

        // 2. An accented name FOLDS instead of losing its letters ("caf" before).
        assertThat(create(NAMES[1]).statusCode())
            .as("step 2: the accented site is created").isIn(302, 303);
        assertThat((String) siteNamed(NAMES[1]).get(SiteModel.SLUG))
            .as("step 2: accents fold to their ASCII letters")
            .isEqualTo("cafe-deja-vu");
    }

    private HttpResponse<String> create(String name) throws Exception {
        String body = "name=" + URLEncoder.encode(name, StandardCharsets.UTF_8)
            + "&upstream_kind=hohenheim%3Astatic"
            + "&settings.root_path=%2Ftmp"
            + "&enabled=false"
            + "&hostname=";
        return adminPostForm("/admin/sites/new", body);
    }

    private static Row siteNamed(String name) {
        return Models.get(SiteModel.class).find().where(SiteModel.NAME.eq(name)).first();
    }
}
