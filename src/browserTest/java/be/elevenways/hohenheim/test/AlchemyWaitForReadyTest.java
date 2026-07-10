package be.elevenways.hohenheim.test;

import be.elevenways.hohenheim.HohenheimEndpoints;
import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.server.HohenheimDatabase;
import be.elevenways.hohenheim.server.process.ManagedProcessSiteHandler;
import be.elevenways.hohenheim.server.sitetype.SiteRequestHandler;
import be.elevenways.hohenheim.server.sitetype.SiteTypes;
import be.elevenways.hohenheim.server.sitetype.types.AlchemySiteType;
import be.elevenways.hohenheim.server.sitetype.types.NodeSiteType;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the per-site-type {@code wait_for_ready} default: when a site's settings don't specify
 * it, an Alchemy site holds processes until ready (Alchemy always signals via janeway) while a
 * plain Node site does not. Exercises the construction-time {@code defaultWaitForReady()} override.
 */
class AlchemyWaitForReadyTest {

    @BeforeAll
    static void boot() throws Exception {
        File db = File.createTempFile("hohenheim-alchemy-wfr", ".db");
        db.delete();
        db.deleteOnExit();
        HohenheimSettings.VALUES.setValue(HohenheimSettings.Database.PATH, db.getAbsolutePath());

        SiteTypes.boot();
        HohenheimEndpoints.init();
        HohenheimDatabase.init();
        HohenheimTestRuntime.ensureBooted();
    }

    @Test
    void alchemyDefaultsToWaitForReadyWhenSettingAbsent() {
        assertThat(waitForReadyOf(new AlchemySiteType()))
            .as("Alchemy holds processes until ready by default")
            .isTrue();
    }

    @Test
    void plainNodeDoesNotWaitForReadyByDefault() {
        assertThat(waitForReadyOf(new NodeSiteType()))
            .as("Plain Node does not wait for ready by default")
            .isFalse();
    }

    @Test
    void explicitSettingOverridesTheTypeDefault() {
        // An alchemy site that explicitly opts out must be honoured, not forced.
        assertThat(waitForReadyOf(new AlchemySiteType(), Map.of("wait_for_ready", false)))
            .as("explicit wait_for_ready=false overrides the alchemy default")
            .isFalse();
    }

    private static boolean waitForReadyOf(NodeSiteType type) {
        return waitForReadyOf(type, Map.of());
    }

    private static boolean waitForReadyOf(NodeSiteType type, Map<String, Object> settings) {
        Row site = Models.get(SiteModel.class).createEmptyRow();
        site.set(SiteModel.ID, 1);
        site.set(SiteModel.NAME, "wfr-test");

        // Fail-fast validation requires a real script, so construction spawns a real
        // (idle) node child; destroy() reaps it once the flag has been read.
        Map<String, Object> withScript = new HashMap<>(settings);
        withScript.put("script", idleScript().getAbsolutePath());

        SiteRequestHandler handler = type.createHandler(site, withScript);
        assertThat(handler)
            .as("a valid script must not produce a faulted handler")
            .isInstanceOf(ManagedProcessSiteHandler.class);
        try {
            return ((ManagedProcessSiteHandler) handler).isWaitForReady();
        } finally {
            handler.destroy();
        }
    }

    private static File idleScript() {
        try {
            File script = File.createTempFile("hohenheim-wfr-idle", ".js");
            script.deleteOnExit();
            Files.writeString(script.toPath(), "setInterval(function () {}, 1 << 30);\n");
            return script;
        } catch (IOException e) {
            throw new RuntimeException("Failed to write idle test script", e);
        }
    }
}
