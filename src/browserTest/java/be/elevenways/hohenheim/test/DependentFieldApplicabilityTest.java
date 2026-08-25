package be.elevenways.hohenheim.test;

import be.elevenways.hohenheim.HohenheimPickRules;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.server.instance.DockerContainerKind;
import be.elevenways.hohenheim.server.instance.InstanceKindHandler;
import be.elevenways.hohenheim.server.instance.InstanceKinds;
import be.elevenways.hohenheim.server.instance.WorkspaceKind;
import be.elevenways.protoblast.common.i18n.LocaleChain;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.microcopy.server.DefaultCatalogLoader;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A field that does not apply to what the operator chose says so, or is not there at all.
 *
 * AIDEV-NOTE: both halves of the same defect. A dependent picker whose narrowing cannot
 * resolve renders DISABLED under the framework's "choose the sibling first" placeholder,
 * which is a true sentence only while the sibling is unchosen -- picking "Docker
 * container" left the runtime-image picker saying "Choose kind first" with a kind
 * chosen. And a stored site whose upstream serves files still rendered the instance pick,
 * greyed out, with help text describing an instance it will never have.
 */
class DependentFieldApplicabilityTest extends HohenheimTestBase {

    /**
     * Step 1-4: the runtime-image narrowing tells the three states apart -- nothing
     * chosen, a kind that reads no image, and a kind that does.
     */
    @Test
    void theRuntimeImagePickerSaysWhyItOffersNothing() {
        DefaultCatalogLoader catalogs = new DefaultCatalogLoader();
        // Built exactly as InstanceResource declares it, off the kind handlers' own facts.
        HohenheimPickRules.RuntimeImageRules rules = new HohenheimPickRules.RuntimeImageRules(
            "kind",
            InstanceKinds.kindsWhere(InstanceKindHandler::usesRuntimeImage),
            List.of());

        // 1. Nothing chosen yet: unresolved, which is the DISABLED picker's own state, and
        //    the framework's "choose the kind first" is the right sentence there.
        assertThat(rules.resolve(Map.of()))
            .as("step 1: an unchosen kind narrows to nothing resolvable").isNull();
        assertThat(rules.reasonNothingQualifies(Map.of()))
            .as("step 1: and declares no reason, so the generic placeholder stands").isNull();

        // 2. A kind that never reads the column: RESOLVED (so the empty-text lane runs)
        //    and explained, instead of a disabled picker blaming an unchosen sibling.
        Map<String, Object> docker = Map.of("kind", DockerContainerKind.ID.toString());
        assertThat(rules.resolve(docker))
            .as("step 2: a chosen kind that reads no image still RESOLVES").isNotNull();
        Microcopy notApplicable = rules.reasonNothingQualifies(docker);
        assertThat(notApplicable).as("step 2: and says why").isNotNull();
        assertThat(notApplicable.key())
            .as("step 2: naming the real precondition, not an unchosen sibling")
            .isEqualTo("runtime_image_not_applicable");

        // 3. A kind that DOES run inside an image keeps the pre-existing sentence: the
        //    images exist as a concept here, none is enabled.
        Map<String, Object> workspace = Map.of("kind", WorkspaceKind.ID.toString());
        assertThat(rules.resolve(workspace))
            .as("step 3: an image-reading kind narrows to the enabled images").isNotNull();
        assertThat(rules.reasonNothingQualifies(workspace).key())
            .as("step 3: and its empty state is about enablement")
            .isEqualTo("no_enabled_runtime_image");

        // 4. Both sentences are copy, in both locales -- a reason that renders its own key
        //    is worse than the generic text it replaced.
        for (Microcopy reason : List.of(notApplicable, rules.reasonNothingQualifies(workspace))) {
            for (String tag : List.of("en", "nl")) {
                assertThat(reason.resolve(LocaleChain.ofTags(tag), catalogs))
                    .as("step 4: " + tag + " copy for " + reason.key())
                    .isNotEqualTo(reason.key());
            }
        }
    }

    /**
     * Step 1-4: the SITE form's instance narrowing tells the same three states apart the
     * runtime-image one does -- nothing chosen, a chosen upstream that serves no
     * instance, and the instance upstream itself.
     */
    @Test
    void theInstancePickerSaysWhyItOffersNothing() {
        DefaultCatalogLoader catalogs = new DefaultCatalogLoader();
        // Built exactly as SiteResource declares it, off the kind handlers' own facts.
        HohenheimPickRules.UpstreamInstanceRules rules = new HohenheimPickRules.UpstreamInstanceRules(
            "upstream_kind",
            "hohenheim:instance",
            InstanceKinds.kindsWhere(InstanceKindHandler::supportsSiteUpstream));

        // 1. Nothing chosen yet: unresolved, so the "choose the upstream first"
        //    placeholder is a true sentence.
        assertThat(rules.resolve(Map.of()))
            .as("step 1: an unchosen upstream narrows to nothing resolvable").isNull();
        assertThat(rules.reasonNothingQualifies(Map.of()))
            .as("step 1: and declares no reason").isNull();

        // 2. A chosen upstream that serves FILES still RESOLVES and says the true
        //    precondition, instead of a disabled picker blaming an unchosen sibling.
        Map<String, Object> statics = Map.of("upstream_kind", "hohenheim:static");
        assertThat(rules.resolve(statics))
            .as("step 2: a chosen non-instance upstream still RESOLVES").isNotNull();
        Microcopy notApplicable = rules.reasonNothingQualifies(statics);
        assertThat(notApplicable).as("step 2: and says why").isNotNull();
        assertThat(notApplicable.key())
            .as("step 2: naming the real precondition")
            .isEqualTo("instance_upstream_not_applicable");

        // 3. The instance upstream keeps the pre-existing sentence about exposable kinds.
        Map<String, Object> instance = Map.of("upstream_kind", "hohenheim:instance");
        assertThat(rules.resolve(instance))
            .as("step 3: the instance upstream narrows to the exposable kinds").isNotNull();
        assertThat(rules.reasonNothingQualifies(instance).key())
            .as("step 3: its empty state is about exposable instances")
            .isEqualTo("no_exposable_instance");

        // 4. Both sentences are copy, in both locales.
        for (Microcopy reason : List.of(notApplicable, rules.reasonNothingQualifies(instance))) {
            for (String tag : List.of("en", "nl")) {
                assertThat(reason.resolve(LocaleChain.ofTags(tag), catalogs))
                    .as("step 4: " + tag + " copy for " + reason.key())
                    .isNotEqualTo(reason.key());
            }
        }
    }

    /**
     * Step 1-2: the instance pick is absent from a site whose upstream resolves to no
     * instance, and still offered where a kind has yet to be chosen.
     */
    @Test
    void aSiteThatServesFilesHasNoInstanceField() throws Exception {
        var sites = Models.get(SiteModel.class);
        Row site = sites.createEmptyRow();
        site.set(SiteModel.NAME, "applicability-static-site");
        site.set(SiteModel.SLUG, "applicability-static-site");
        site.set(SiteModel.UPSTREAM_KIND, "hohenheim:static");
        site.set(SiteModel.SETTINGS, Map.of("root_path", "/tmp"));
        site.set(SiteModel.STATUS, SiteModel.STATUS_ACTIVE);
        site.set(SiteModel.ENABLED, true);
        sites.save(site);

        // 1. The stored kind serves files: the instance pick is not a property of it.
        String stored = adminGet("/admin/sites/" + site.get(SiteModel.ID)).body();
        assertThat(stored).as("step 1: the site's own form renders")
            .contains("applicability-static-site");
        assertThat(stored).as("step 1: without the instance pick it can never use")
            .doesNotContain("instance_id");

        // 2. The CREATE form has no stored kind yet, and the Expose journey prefills this
        //    very field: hiding it there would break a working path.
        String create = adminGet("/admin/sites/new").body();
        assertThat(create).as("step 2: a new site is still offered the instance pick")
            .contains("instance_id");
    }
}
