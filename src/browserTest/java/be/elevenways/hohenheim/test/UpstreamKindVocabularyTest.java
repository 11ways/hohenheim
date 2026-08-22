package be.elevenways.hohenheim.test;

import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.server.upstream.UpstreamKindHandler;
import be.elevenways.hohenheim.server.upstream.UpstreamKindHandlers;
import be.elevenways.hohenheim.upstream.UpstreamKindInfo;
import be.elevenways.hohenheim.upstream.UpstreamKinds;
import be.elevenways.protoblast.common.i18n.LocaleChain;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.zenit.microcopy.server.DefaultCatalogLoader;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.validation.Violations;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * THE binding across the declaring sites of the site-upstream vocabulary: the discovered
 * {@link UpstreamKinds} registry (what a site may store), the server-side handler map the
 * proxy dispatches through, the stored column's own value set, and the en + nl microcopy.
 *
 * AIDEV-NOTE: it binds ACROSS sites deliberately (the DatabaseEngineVocabularyTest stance).
 * Comparing the registry to a list of constants sitting beside it would be circular and
 * would prove nothing -- what actually breaks is a kind that registers but has no handler,
 * no label in one language, or a stored value the column refuses.
 */
class UpstreamKindVocabularyTest {

    @BeforeAll
    static void bootRegistry() {
        HohenheimTestRuntime.ensureDatasource();
    }

    private static List<Identifier> registeredIds() {
        List<Identifier> ids = new ArrayList<>();
        for (UpstreamKindInfo entry : UpstreamKinds.REGISTRY) {
            Identifier id = UpstreamKinds.REGISTRY.getId(entry);
            if (id != null) {
                ids.add(id);
            }
        }
        return ids;
    }

    @Test
    @DisplayName("every registered upstream kind dispatches, stores and translates")
    void theFourDeclaringSitesAgree() {

        List<Identifier> registered = registeredIds();

        // 1. The fixture is only meaningful with a populated registry: an empty one would
        //    make every loop below vacuously true.
        assertThat(registered)
            .as("step 1: the upstream registry is populated by compile-time discovery")
            .hasSizeGreaterThanOrEqualTo(6);

        for (Identifier id : registered) {
            String token = id.toString();

            // 2. The proxy resolves every registered kind through the ONE handler map it
            //    dispatches with. A kind with no handler is a site that logs "unknown site
            //    type" and silently serves nothing.
            UpstreamKindHandler handler = UpstreamKindHandlers.getHandler(token);
            assertThat(handler)
                .as("step 2: '%s' registers a dispatchable handler", token)
                .isNotNull();

            // 3. The stored column offers exactly what the registry holds, so a kind that
            //    a form can select is also a value the model accepts.
            assertThat(SiteModel.UPSTREAM_KIND.isValidValue(token))
                .as("step 3: '%s' is storable on sites.upstream_kind", token)
                .isTrue();

            // 4. Both shipped languages name it AND explain it. A missing key renders the
            //    raw English display name, which is how localization rots unseen.
            assertLocalized(handler.getLabel(), token, "label");
            assertLocalized(handler.getDescription(), token, "description");
        }

        // 5. Fail CLOSED: an unregistered token resolves to no handler at all rather than
        //    to a default one, which is what makes SiteDispatcher skip it loudly.
        assertThat(UpstreamKindHandlers.getHandler("hohenheim:teleported"))
            .as("step 5: an unknown upstream resolves to nothing").isNull();
        assertThat(UpstreamKindHandlers.getHandler((String) null))
            .as("step 5: and so does an absent one").isNull();
    }

    @Test
    @DisplayName("the workload kinds that left the vocabulary cannot come back through the column")
    void deletedSiteTypesAreUnstorable() {

        // The workload half of the old site_type set moved to the INSTANCE kind. A stored
        // value that survived here would render as an unknown pill and dispatch to nothing.
        for (String gone : List.of("hohenheim:docker", "hohenheim:node", "hohenheim:java",
                "hohenheim:command", "hohenheim:alchemy", "hohenheim:dead",
                "hohenheim:proxy")) {
            assertThat(SiteModel.UPSTREAM_KIND.isValidValue(gone))
                .as("'%s' is no longer an upstream kind", gone)
                .isFalse();
        }
    }

    @Test
    @DisplayName("a kind that resolves to an instance must name one, and no other kind may")
    void theInstanceLinkInvariantHoldsBothWays() {

        SiteModel sites = Models.get(SiteModel.class);

        // 1. An instance upstream with no instance is a site that can only 502.
        Row missing = sites.createEmptyRow();
        missing.set(SiteModel.NAME, "vocab-instance-missing");
        missing.set(SiteModel.SLUG, "vocab-instance-missing");
        missing.set(SiteModel.UPSTREAM_KIND, "hohenheim:instance");
        assertThatThrownBy(() -> sites.save(missing))
            .as("step 1: an instance upstream must name its instance")
            .isInstanceOf(Violations.class);

        // 2. And the reverse: a static site pointing at an instance is a dangling
        //    reference the "exposed by" lookup would render as a lie.
        Row unexpected = sites.createEmptyRow();
        unexpected.set(SiteModel.NAME, "vocab-static-with-instance");
        unexpected.set(SiteModel.SLUG, "vocab-static-with-instance");
        unexpected.set(SiteModel.UPSTREAM_KIND, "hohenheim:static");
        unexpected.set(SiteModel.INSTANCE_ID, 999999);
        assertThatThrownBy(() -> sites.save(unexpected))
            .as("step 2: only an instance upstream may name an instance")
            .isInstanceOf(Violations.class);

        // 3. The plain shape still saves, so steps 1 and 2 are not refusing everything.
        Row ok = sites.createEmptyRow();
        ok.set(SiteModel.NAME, "vocab-static-plain");
        ok.set(SiteModel.SLUG, "vocab-static-plain");
        ok.set(SiteModel.UPSTREAM_KIND, "hohenheim:static");
        sites.save(ok);
        assertThat(ok.get(SiteModel.ID)).as("step 3: an ordinary static site saves").isNotNull();
        sites.find().where(SiteModel.ID.eq(ok.get(SiteModel.ID))).delete();
    }

    /** Both shipped languages must resolve the token to something other than the token. */
    private static void assertLocalized(Microcopy copy, String token, String what) {
        assertThat(copy).as("'%s' declares a %s token", token, what).isNotNull();
        DefaultCatalogLoader catalogs = new DefaultCatalogLoader();
        for (String tag : List.of("en", "nl")) {
            String resolved = copy.resolve(LocaleChain.ofTags(tag), catalogs);
            assertThat(resolved)
                .as("'%s' has a %s in %s", token, what, tag)
                .isNotBlank()
                .isNotEqualTo(copy.key());
        }
    }
}
