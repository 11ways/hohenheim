package be.elevenways.hohenheim.test;

import be.elevenways.hohenheim.HohenheimSlugs;
import be.elevenways.hohenheim.model.BanModel;
import be.elevenways.hohenheim.model.DnsZoneModel;
import be.elevenways.hohenheim.model.RuntimeImageModel;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.model.SiteAuthProviderModel;
import be.elevenways.zenit.cms.common.panel.Panel;
import be.elevenways.zenit.cms.common.panel.PanelRegistry;
import be.elevenways.hohenheim.server.cms.CmsSupport;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.cms.common.panel.Panel;
import be.elevenways.zenit.cms.common.panel.PanelRegistry;
import be.elevenways.zenit.cms.server.page.CmsRecordSources;
import be.elevenways.zenit.common.data.RecordSource;
import be.elevenways.zenit.common.data.RecordSourceRegistry;
import be.elevenways.zenit.common.orm.field.Field;
import be.elevenways.protoblast.common.util.BlastLog;
import be.elevenways.zenit.cms.common.panel.PanelEntry;
import be.elevenways.zenit.cms.common.resource.PanelResource;
import be.elevenways.zenit.cms.server.panel.PartsForms;
import be.elevenways.zenit.test.support.LogLines;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The admin record sources that outgrow the zenit-cms-derived default keep BOTH halves:
 * the projection the dependent pickers narrow on AND the edit link / inline create the
 * derived default would have carried (the five {@code source_capability_dropped} boot
 * lines this pins closed).
 */
class AdminSourcesTest extends HohenheimTestBase {

    @Test
    void theProjectedAdminSourcesKeepTheFacetsOfTheDerivedDefault() {
        // 1. The three explicit sources replaced the derived default WITH its edit link,
        //    so a picker over hosts, images or bans can still open the record.
        for (Identifier modelId : List.of(BanModel.MODEL_ID, ServerModel.MODEL_ID, RuntimeImageModel.MODEL_ID)) {
            RecordSource<?> source = RecordSourceRegistry.INSTANCE.requireById(modelId);
            assertThat(source.hasEditUrl())
                .as("step 1: %s carries the edit link the derived default had", modelId)
                .isTrue();
            assertThat(entryOf(modelId).derivedDefault())
                .as("step 1: %s is an explicit declaration, not the derived glue", modelId)
                .isFalse();
        }

        // 2. And the reason they are explicit at all survives: the projection IS the rule
        //    vocabulary of HohenheimPickRules, and the bans source sorts (buckets) by created_at.
        RecordSource<?> hosts = RecordSourceRegistry.INSTANCE.requireById(ServerModel.MODEL_ID);
        assertThat(names(hosts.projection()))
            .as("step 2: the host pick narrows on runtime and volume_backend")
            .contains(ServerModel.RUNTIME.getName(), ServerModel.VOLUME_BACKEND.getName());
        RecordSource<?> images = RecordSourceRegistry.INSTANCE.requireById(RuntimeImageModel.MODEL_ID);
        assertThat(names(images.projection()))
            .as("step 2: the image pick narrows on enabled and incus_image")
            .contains(RuntimeImageModel.ENABLED.getName(), RuntimeImageModel.INCUS_IMAGE.getName());
        RecordSource<?> bans = RecordSourceRegistry.INSTANCE.requireById(BanModel.MODEL_ID);
        assertThat(bans.sortableFieldNamed(BanModel.CREATED_AT.getName()))
            .as("step 2: the bans chart buckets by created_at")
            .isNotNull();

        // 3. Inline create follows the resource exactly as the derived default would have:
        //    offered when the framework can derive a provider for it, absent otherwise.
        Panel panel = PanelRegistry.getBySlug(HohenheimSlugs.ADMIN);
        assertThat(panel).as("step 3: the inline-create provider names its registered panel").isNotNull();
        boolean imageCreatable = CmsRecordSources.createProviderFor(panel,
            CmsSupport.rowEntry(panel, HohenheimSlugs.RUNTIME_IMAGES)) != null;
        assertThat(images.isCreatable())
            .as("step 3: runtime images offer inline create iff the framework derives a provider")
            .isEqualTo(imageCreatable);

        // 4. Both composed entries derive their model picker source from the panel's declaration.
        for (Identifier modelId : List.of(DnsZoneModel.MODEL_ID, SiteAuthProviderModel.MODEL_ID)) {
            RecordSource<?> source = RecordSourceRegistry.INSTANCE.requireById(modelId);
            assertThat(entryOf(modelId).derivedDefault())
                .as("step 4: %s has the source appropriate to its entry kind", modelId)
                .isTrue();
            assertThat(source.hasEditUrl()).as("step 4: %s links to its record", modelId).isTrue();
            assertThat(source.searchOffered()).as("step 4: %s is searchable", modelId).isTrue();
        }
        assertThat(names(RecordSourceRegistry.INSTANCE.requireById(DnsZoneModel.MODEL_ID).searchFields()))
            .as("step 4: the zone picker still searches the origin")
            .contains(DnsZoneModel.ORIGIN.getName());
        assertThat(names(RecordSourceRegistry.INSTANCE.requireById(SiteAuthProviderModel.MODEL_ID).searchFields()))
            .as("step 4: the auth-provider picker still searches the name")
            .contains(SiteAuthProviderModel.NAME.getName());
    }

    @Test
    void noHohenheimPanelEntrySkipsADerivedInlineCreate() {
        // 1. Re-derive every resource's compact create in every registered panel, as the boot's source wiring does,
        //    and capture what that says: an unsatisfiable derivation is a cms.chooser.inline_create_skipped line.
        List<String> logged = new CopyOnWriteArrayList<>();
        BlastLog.LogSink previous = BlastLog.getLogSink();
        LogLines.captureInto(logged);
        int resources = 0;
        try {
            for (Panel panel : PanelRegistry.all()) {
                for (PanelEntry entry : panel.entries()) {
                    if (entry instanceof PanelResource<?>) {
                        resources++;
                        CmsRecordSources.createProviderFor(panel, entry);
                    }
                }
            }
        } finally {
            BlastLog.setLogSink(previous);
        }
        assertThat(resources).as("step 1: the walk reached the panels' resources").isPositive();
        assertThat(logged)
            .as("step 1: a clean panel build skips no derived inline create")
            .noneMatch(line -> line.startsWith("cms.chooser.inline_create_skipped"));

        // 2. The two resources that used to be skipped say "none" once, on their form, in every panel they sit in.
        Panel admin = PanelRegistry.getBySlug(HohenheimSlugs.ADMIN);
        assertThat(admin).as("step 2: the admin panel is registered").isNotNull();
        for (String slug : List.of(HohenheimSlugs.GAME_DOMAINS, HohenheimSlugs.PREVIEWS)) {
            PanelEntry entry = admin.entryBySlug(slug);
            assertThat(entry).as("step 2: %s is an admin entry", slug).isNotNull();
            assertThat(PartsForms.quickCreateWithheld(entry)).as("step 2: %s withholds its quick create", slug)
                .isTrue();
            assertThat(CmsRecordSources.createProviderFor(admin, entry))
                .as("step 2: %s offers no compact create", slug).isNull();
        }
    }

    private static RecordSourceRegistry.Entry entryOf(Identifier modelId) {
        return RecordSourceRegistry.INSTANCE.registryEntries().stream()
            .filter(entry -> entry.source().id().equals(modelId))
            .findFirst()
            .orElseThrow(() -> new AssertionError("no registry entry for " + modelId));
    }

    private static List<String> names(List<Field<?, ?>> fields) {
        return fields.stream().map(Field::getName).toList();
    }
}
