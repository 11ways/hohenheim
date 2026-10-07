package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.HohenheimFormSections;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.server.upstream.kinds.AddressUpstreamKind;
import be.elevenways.hohenheim.server.upstream.kinds.RedirectUpstreamKind;
import be.elevenways.hohenheim.server.upstream.kinds.StaticUpstreamKind;
import be.elevenways.hohenheim.test.HardDeletes;
import be.elevenways.hohenheim.test.HohenheimTestBase;
import be.elevenways.zenit.common.edit.FormSection;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.field.Field;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.orm.model.Schema;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Configuration tab of an app without a workload (board App-Config-Address): where requests go first, then how
 * the connection behaves, then the rarely changed knobs folded; the static and redirect kinds in the same shape.
 */
class AppConfigAddressJourneyTest extends HohenheimTestBase {

    @Test
    void anAddressAppsConfigurationLeadsWithItsTargetThenItsConnection() {
        // 1. Each kind declares the shape: its target unsectioned (rendered first), the connection or folder knobs
        //    open, the knobs that are right almost always folded under Advanced.
        assertThat(unsectioned(AddressUpstreamKind.SETTINGS_SCHEMA))
            .as("step 1: a proxy leads with where requests go")
            .containsExactly("forward_scheme", "forward_host", "forward_port", "socket");
        assertThat(section(AddressUpstreamKind.SETTINGS_SCHEMA, HohenheimFormSections.FORWARDING))
            .as("step 1: then how the connection behaves, open, the protocol pin last")
            .extracting(FormSection::startsCollapsed, FormSection::entryNames)
            .containsExactly(false, List.of("websocket_upgrade", "rewrite_location", "ignore_certificates",
                "request_timeout", "delay", "upstream_protocol"));
        assertThat(unsectioned(StaticUpstreamKind.SETTINGS_SCHEMA))
            .as("step 1: static files lead with the folder and the fallback file")
            .containsExactly("root_path", "fallback_file");
        assertThat(section(StaticUpstreamKind.SETTINGS_SCHEMA, HohenheimFormSections.LISTING).startsCollapsed())
            .as("step 1: what a folder shows stays open").isFalse();
        assertThat(section(StaticUpstreamKind.SETTINGS_SCHEMA, HohenheimFormSections.FORWARDING))
            .as("step 1: the static delay folds under Connection")
            .extracting(FormSection::startsCollapsed, FormSection::entryNames)
            .containsExactly(true, List.of("delay"));
        assertThat(unsectioned(RedirectUpstreamKind.SETTINGS_SCHEMA))
            .as("step 1: a redirect leads with its target, status and whether the path is kept")
            .containsExactly("target_url", "http_status", "preserve_path");
        assertThat(section(RedirectUpstreamKind.SETTINGS_SCHEMA, HohenheimFormSections.FORWARDING))
            .as("step 1: the redirect delay folds under Connection")
            .extracting(FormSection::startsCollapsed, FormSection::entryNames)
            .containsExactly(true, List.of("delay"));
        for (Schema schema : List.of(AddressUpstreamKind.SETTINGS_SCHEMA, StaticUpstreamKind.SETTINGS_SCHEMA,
                RedirectUpstreamKind.SETTINGS_SCHEMA)) {
            assertThat(schema.getSections()).as("step 1: no settings sub-form claims a second 'Advanced' card")
                .extracting(FormSection::id).doesNotContain(FormSection.ADVANCED_ID);
        }

        List<Runnable> cleanup = new ArrayList<>();
        try {
            // The settings a site created through the form stores: every key, defaults included (step 4 covers a
            // row that lacks one).
            Map<String, Object> settings = new LinkedHashMap<>();
            settings.put("forward_scheme", "http");
            settings.put("forward_host", "10.0.0.12");
            settings.put("forward_port", 3000);
            settings.put("websocket_upgrade", true);
            settings.put("rewrite_location", true);
            settings.put("ignore_certificates", false);
            Row proxy = row(cleanup, settings);

            // 2. The rendered tab: the target fields come before the open Connection section, and the page keeps
            //    exactly one folded "Advanced" card (the site form's own).
            navigateToApp("/admin/sites/" + proxy.get(SiteModel.ID));
            waitForHydration();
            String connection = "pl-card.zf-form-section[data-section$='" + HohenheimFormSections.FORWARDING + "']";
            assertThat(page.locator(connection).count()).as("step 2: the Connection section renders").isEqualTo(1);
            assertThat(page.locator(connection).getAttribute("data-collapsed"))
                .as("step 2: Connection is open").isNotEqualTo("true");
            assertThat(page.locator("pl-card.zf-form-section[data-section='" + FormSection.ADVANCED_ID + "']").count())
                .as("step 2: one Advanced card, the site form's own").isEqualTo(1);
            assertThat(page.locator(connection).textContent()).as("step 2: under its operator name")
                .contains("Connection");
            assertThat((Boolean) page.evaluate("([host, section]) => !!(document.querySelector(host)"
                    + ".compareDocumentPosition(document.querySelector(section)) & Node.DOCUMENT_POSITION_FOLLOWING)",
                List.of("pl-input[name='settings.forward_host']", connection)))
                .as("step 2: where requests go comes before how the connection behaves").isTrue();

            // 3. Saving a connection knob lands on the record: the save is confirmed, and the reloaded tab shows
            //    WebSockets off while the target is kept.
            page.locator(connection + " pl-switch[name='settings.websocket_upgrade'] button[role='switch']").click();
            page.click(".cms-form-actions pl-button[type='submit']");
            page.waitForSelector("text=have been saved");
            navigateToApp("/admin/sites/" + proxy.get(SiteModel.ID));
            waitForHydration();
            assertThat(page.locator("pl-switch[name='settings.websocket_upgrade'] button[role='switch']")
                .getAttribute("aria-checked")).as("step 3: WebSockets is off after the save").isEqualTo("false");
            assertThat(page.locator("pl-input[name='settings.forward_host'] input").inputValue())
                .as("step 3: the target is kept").isEqualTo("10.0.0.12");

            // 4. A row whose stored settings LACK the switch (written before the field existed, or by another
            //    writer) shows its declared default on, and turning it off stores an explicit false.
            Map<String, Object> sparse = new LinkedHashMap<>();
            sparse.put("forward_scheme", "http");
            sparse.put("forward_host", "10.0.0.13");
            sparse.put("forward_port", 3001);
            Row older = row(cleanup, sparse, "w7b-config-sparse");
            navigateToApp("/admin/sites/" + older.get(SiteModel.ID));
            waitForHydration();
            String toggle = "pl-switch[name='settings.websocket_upgrade'] button[role='switch']";
            assertThat(page.locator(toggle).getAttribute("aria-checked"))
                .as("step 4: an absent switch shows its declared default").isEqualTo("true");
            page.locator(toggle).click();
            var request = page.waitForRequest(r -> "POST".equals(r.method()),
                () -> page.click(".cms-form-actions pl-button[type='submit']"));
            page.waitForSelector("text=have been saved");
            Row stored = Models.get(SiteModel.class).findById(older.get(SiteModel.ID));
            assertThat(((Map<?, ?>) stored.get(SiteModel.SETTINGS)).get("websocket_upgrade"))
                .as("step 4: turning an absent switch off stores false (posted: %s; stored: %s)",
                    request.postData(), stored.get(SiteModel.SETTINGS))
                .isEqualTo(false);
            navigateToApp("/admin/sites/" + older.get(SiteModel.ID));
            waitForHydration();
            assertThat(page.locator(toggle).getAttribute("aria-checked"))
                .as("step 4: and the reloaded tab shows it off").isEqualTo("false");
        } finally {
            cleanup.forEach(Runnable::run);
        }
    }

    private static List<String> unsectioned(Schema schema) {
        List<String> sectioned = new ArrayList<>();
        for (FormSection section : schema.getSections()) {
            sectioned.addAll(section.entryNames());
        }
        List<String> names = new ArrayList<>();
        for (Field<?, ?> field : schema.getFields().values()) {
            if (!sectioned.contains(field.getName())) {
                names.add(field.getName());
            }
        }
        return names;
    }

    private static FormSection section(Schema schema, String id) {
        for (FormSection section : schema.getSections()) {
            if (section.id().equals(id)) {
                return section;
            }
        }
        throw new AssertionError("schema declares no section '" + id + "'");
    }

    private static Row row(List<Runnable> cleanup, Map<String, Object> settings) {
        return row(cleanup, settings, "w7b-config-proxy");
    }

    private static Row row(List<Runnable> cleanup, Map<String, Object> settings, String slug) {
        SiteModel sites = Models.get(SiteModel.class);
        Row row = sites.createEmptyRow();
        row.set(SiteModel.NAME, slug);
        row.set(SiteModel.SLUG, slug);
        row.set(SiteModel.UPSTREAM_KIND, AddressUpstreamKind.ID.toString());
        row.set(SiteModel.SETTINGS, new LinkedHashMap<>(settings));
        row.set(SiteModel.ENABLED, true);
        sites.save(row);
        cleanup.add(() -> HardDeletes.row(sites, row));
        return row;
    }
}
