package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.test.HohenheimTestBase;
import be.elevenways.zenit.cms.common.panel.Panel;
import be.elevenways.zenit.cms.common.panel.PanelRegistry;
import be.elevenways.zenit.cms.server.page.CmsRecordSources;
import be.elevenways.zenit.common.data.RecordSource;
import be.elevenways.zenit.common.data.RecordSourceRegistry;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.orm.query.SortOrder;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * The /manage panel's record-source declarations against a FRESH registry, in both boot
 * orders and under the strict posture a debug server boots with.
 *
 * AIDEV-NOTE: this exists because a fresh install died in the MODULES boot stage with
 * "RecordSource 'hohenheim:site' replaces the framework-derived default but changes its
 * access gates" -- four explicit register(...) calls that predate zenit-cms deriving a
 * default for those models. No test caught it: every suite boots with strict registration
 * OFF (the refusal only slogs) and against a registry the previous class already filled,
 * so the derived-versus-explicit comparison never ran. Both halves are reproduced here.
 */
class ManageSourceRegistrationTest extends HohenheimTestBase {

    @BeforeAll
    static void seedOneSite() {
        var siteModel = Models.get(SiteModel.class);
        Row site = siteModel.createEmptyRow();
        site.set(SiteModel.NAME, "Source Registration Site");
        site.set(SiteModel.SLUG, "source-registration-site");
        site.set(SiteModel.UPSTREAM_KIND, "hohenheim:static");
        site.set(SiteModel.SETTINGS, Map.of("root_path", "/tmp"));
        site.set(SiteModel.STATUS, "active");
        site.set(SiteModel.ENABLED, true);
        siteModel.save(site);
    }

    @Test
    void manageSourcesDeclareTheirNarrowingInBothBootOrders() {

        List<RecordSourceRegistry.Entry> registered = RecordSourceRegistry.INSTANCE.snapshot();
        boolean strictBefore = RecordSourceRegistry.INSTANCE.isStrictRegistration();

        try {
            // 1. A debug server's posture: a refused registration THROWS instead of
            //    slogging, which is exactly how the boot failure presented.
            RecordSourceRegistry.INSTANCE.setStrictRegistration(true);

            // 2. The production boot order: zenit-cms derives its defaults from the
            //    panels' RowResources first, then hohenheim declares its own sources
            //    over them.
            resetRegistry();
            deriveFrameworkDefaults();
            RecordSource<?> derived = RecordSourceRegistry.INSTANCE
                .requireDefaultFor(SiteModel.MODEL_ID);
            assertThat(derived.permission())
                .as("step 2: zenit-cms must really derive a gated default for site,"
                    + " or this test proves nothing")
                .isNotNull();

            assertThatCode(ManagePanel::declareSources)
                .as("step 2: declaring the manage sources over the derived defaults"
                    + " must not be refused")
                .doesNotThrowAnyException();

            // 3. The reverse boot order: the explicit declarations land first and the
            //    derived defaults arrive after. The registry remembers the declared
            //    intent, so this order must be just as quiet.
            resetRegistry();
            ManagePanel.declareSources();
            assertThatCode(ManageSourceRegistrationTest::deriveFrameworkDefaults)
                .as("step 3: deriving the defaults after the explicit declarations"
                    + " must not be refused either")
                .doesNotThrowAnyException();

            // 4. The declaration that won is hohenheim's, not the derived one: no
            //    blanket permission, and the per-principal manage scope intact.
            RecordSource<?> siteSource = RecordSourceRegistry.INSTANCE
                .requireDefaultFor(SiteModel.MODEL_ID);
            assertThat(siteSource)
                .as("step 4: the explicit declaration must hold the site source")
                .isNotSameAs(derived);
            assertThat(siteSource.hasAccessCriteria())
                .as("step 4: the manage scope must survive the deliberate replacement")
                .isTrue();
            assertThat(siteSource.permission())
                .as("step 4: the site source is gated by its scope, never by a"
                    + " blanket permission")
                .isNull();

            // 5. And the scope still BITES: a site exists, yet an anonymous audience
            //    reaches none of it through the source.
            assertThat(Models.get(SiteModel.class).find().count())
                .as("step 5: the seeded site must be readable without a scope")
                .isPositive();
            assertThat(siteSource.buildQuery(null, null, null, SortOrder.ASC, null, null)
                    .count())
                .as("step 5: an anonymous audience must reach no site through the"
                    + " manage-scoped source")
                .isZero();
        } finally {
            RecordSourceRegistry.INSTANCE.setStrictRegistration(strictBefore);
            RecordSourceRegistry.INSTANCE.restoreSnapshot(registered);
            // The memoization is process-wide: leave it claiming every panel again so a
            // later request never re-derives against the restored registry.
            deriveFrameworkDefaults();
        }
    }

    /** Empty the registry and the CMS derivation memo, the state a new process starts in. */
    private static void resetRegistry() {
        RecordSourceRegistry.INSTANCE.restoreSnapshot(List.of());
        CmsRecordSources.resetForTests();
    }

    /** Run zenit-cms's own derivation over every registered panel. */
    private static void deriveFrameworkDefaults() {
        CmsRecordSources.resetForTests();
        for (Panel panel : PanelRegistry.all()) {
            CmsRecordSources.ensureRegistered(panel);
        }
    }
}
