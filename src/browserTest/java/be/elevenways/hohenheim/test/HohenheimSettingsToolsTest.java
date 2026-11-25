package be.elevenways.hohenheim.test;

import be.elevenways.hohenheim.server.cms.HohenheimSettingsSections;
import be.elevenways.hohenheim.server.mcp.HohenheimMcpModule;
import be.elevenways.zenit.ai.server.mcp.host.McpHostServer;
import be.elevenways.zenit.ai.server.mcp.host.McpHostServers;
import be.elevenways.zenit.ai.server.mcp.host.McpHttpSession;
import be.elevenways.zenit.auth.model.UserModel;
import be.elevenways.zenit.cms.server.page.SettingsBackend;
import be.elevenways.zenit.cms.server.page.SettingsOperations;
import be.elevenways.zenit.cms.server.page.SettingsPage;
import be.elevenways.zenit.common.setting.SettingDefinition;
import be.elevenways.zenit.common.setting.SettingGroup;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static be.elevenways.hohenheim.test.ApiSupport.user;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Hohenheim's settings through the real MCP endpoint as an administrator's key: every section read, a plain, a
 * numeric, a secret and a host-only setting changed or refused by the argument it names, a stale revision refused, and
 * a person without the administrator right refused; and the drift: every setting a section shows is one argument.
 *
 * @author Jelle De Loecker
 * @since  0.1.0
 */
class HohenheimSettingsToolsTest extends HohenheimHttpTestBase {

    private static final Pattern REVISION = Pattern.compile("Revision: (\\S+)");

    private static final List<String> REVIEW = List.of(SettingsOperations.REVISION, "request");

    @Test
    void anAdministratorReadsAndChangesTheSettingsByToolsAlone() {
        URI endpoint = URI.create(baseUrl() + "/zn/mcp/" + HohenheimMcpModule.SERVER_NAME);
        McpHostServer server = Objects.requireNonNull(McpHostServers.routed(HohenheimMcpModule.SERVER_NAME));
        String tag = HohenheimMcpTest.letters().toLowerCase();
        McpHttpSession admin = HohenheimMcpTest.presetSession(server, endpoint,
            TenantConduits.operatorUser().get(UserModel.ID), "Test Admin");
        try {
            // 1. Every section reads, each setting by the argument its tool takes; one only the host sets says so.
            for (HohenheimSettingsSections section : HohenheimSettingsSections.values()) {
                McpHttpSession.Answer read = admin.call("settings_read", Map.of("section", section.key()));
                assertThat(read.error()).as("step 1: " + section.key() + ": " + read.text()).isFalse();
                if (section.settingsDeclaredHere()) {
                    assertThat(read.text()).as("step 1: " + section.key() + " says its revision").contains("Revision:");
                }
            }
            String general = admin.call("settings_read", Map.of("section", "general")).text();
            assertThat(general).as("step 1: a stored name goes by its input name")
                .contains("unknown_hosts_message (").doesNotContain("not_found_message");
            assertThat(general).as("step 1: the port only the host sets").contains("http_port (")
                .contains("Only the host's operator can change this setting.");

            // 2. A plain setting: set, read back, reset to its default by null.
            String email = "ops-" + tag + "@example.test";
            McpHttpSession.Answer set = admin.call("settings_https_set", Map.of("lets_encrypt_email", email));
            assertThat(set.error()).as("step 2: " + set.text()).isFalse();
            assertThat(set.text()).as("step 2: what changed").contains("lets_encrypt_email").contains("Revision:");
            assertThat(admin.call("settings_read", Map.of("section", "https")).text()).as("step 2: read back")
                .contains(email);
            McpHttpSession.Answer reset = admin.call("settings_https_set", nulls("lets_encrypt_email"));
            assertThat(reset.error()).as("step 2: " + reset.text()).isFalse();
            assertThat(admin.call("settings_read", Map.of("section", "https")).text()).as("step 2: reset")
                .doesNotContain(email);

            // 3. A number with a limit: below it refused by its name, then the two capacity settings set and read.
            McpHttpSession.Answer low = admin.call("settings_apps_set", Map.of("reclaim_min_age_hours", 0));
            assertThat(low.error()).as("step 3: below its minimum: " + low.text()).isTrue();
            assertThat(low.text()).as("step 3: the refusal names it").contains("reclaim_min_age_hours");
            McpHttpSession.Answer capacity = admin.call("settings_hosts_set", Map.of("host_memory_reserve_mb", 1536,
                "memory_overcommit_ratio", 1.5));
            assertThat(capacity.error()).as("step 3: " + capacity.text()).isFalse();
            assertThat(admin.call("settings_read", Map.of("section", "hosts")).text()).as("step 3: read back")
                .contains("host_memory_reserve_mb (").contains("1536").contains("1.5");

            // 4. A secret goes in and never comes out: read only as set.
            String key = "proteus-key-" + tag;
            McpHttpSession.Answer secret = admin.call("settings_signin_set", Map.of("access_key", key));
            assertThat(secret.error()).as("step 4: " + secret.text()).isFalse();
            assertThat(secret.text()).as("step 4: the secret is not answered").doesNotContain(key);
            String signin = admin.call("settings_read", Map.of("section", "signin")).text();
            assertThat(signin).as("step 4: read as set, never shown").doesNotContain(key);
            assertThat(line(signin, "access_key")).as("step 4: read as set").contains(": set");

            // 5. A setting only the host sets is refused by its name, saying why.
            McpHttpSession.Answer port = admin.call("settings_general_set", Map.of("http_port", 8081));
            assertThat(port.error()).as("step 5: " + port.text()).isTrue();
            assertThat(port.text()).as("step 5: names it and why").contains("http_port")
                .contains("Only the host's operator can change this setting.");

            // 6. A revision read before another change is refused, naming the revision.
            String before = revisionIn(admin.call("settings_read", Map.of("section", "https")).text());
            assertThat(admin.call("settings_https_set", Map.of("lets_encrypt_email", email)).error())
                .as("step 6: a change moves the revision").isFalse();
            McpHttpSession.Answer stale = admin.call("settings_https_set", Map.of("lets_encrypt_email",
                "late-" + email, SettingsOperations.REVISION, before));
            assertThat(stale.error()).as("step 6: stale: " + stale.text()).isTrue();
            assertThat(stale.text()).as("step 6: the refusal names the revision").contains("revision");

            // 7. A person without the administrator right reads and changes nothing.
            McpHttpSession person = HohenheimMcpTest.presetSession(server, endpoint,
                user("mcp-settings-" + tag + "@surface.test", "Mcp Settings"), "Mcp Settings");
            assertThat(person.call("settings_read", Map.of("section", "general")).error())
                .as("step 7: no read for a non-administrator").isTrue();
            assertThat(person.call("settings_https_set", Map.of("lets_encrypt_email", email)).error())
                .as("step 7: no change for a non-administrator").isTrue();
        } finally {
            admin.call("settings_https_set", nulls("lets_encrypt_email"));
            admin.call("settings_hosts_set", nulls("host_memory_reserve_mb", "memory_overcommit_ratio"));
            admin.call("settings_signin_set", nulls("access_key"));
        }
    }

    /**
     * Drift: every setting a section of the settings page shows is reached by exactly one argument of its tool, and a
     * setting only the host sets by none; the spam service's section, whose settings it declares itself, has none.
     */
    @Test
    void everySettingASectionShowsIsOneArgumentOfItsTool() {
        URI endpoint = URI.create(baseUrl() + "/zn/mcp/" + HohenheimMcpModule.SERVER_NAME);
        McpHostServer server = Objects.requireNonNull(McpHostServers.routed(HohenheimMcpModule.SERVER_NAME));
        McpHttpSession admin = HohenheimMcpTest.presetSession(server, endpoint,
            TenantConduits.operatorUser().get(UserModel.ID), "Test Admin");
        Map<String, TreeSet<String>> schemas = new LinkedHashMap<>();
        for (Map<String, Object> tool : admin.tools()) {
            TreeSet<String> names = new TreeSet<>();
            ((Map<?, ?>) ((Map<?, ?>) tool.get("inputSchema")).get("properties")).keySet()
                .forEach(name -> names.add(String.valueOf(name)));
            schemas.put(String.valueOf(tool.get("name")), names);
        }
        int arguments = 0;
        for (HohenheimSettingsSections section : HohenheimSettingsSections.values()) {
            String tool = "settings_" + section.key() + "_set";
            if (!section.settingsDeclaredHere()) {
                assertThat(schemas).as("step 1: " + section.key() + " has no write here").doesNotContainKey(tool);
                continue;
            }
            SettingsPage.Mount mount = Objects.requireNonNull(section.mount(), section.key());
            SettingsBackend.Snapshot snapshot = mount.backend().snapshot();
            Map<String, SettingDefinition<?>> declared = new HashMap<>();
            collect(snapshot.rootGroup(), snapshot.rootGroup(), declared);
            List<String> taken = new ArrayList<>();
            List<String> hostOnly = new ArrayList<>();
            snapshot.settings().forEach((path, state) -> {
                String name = Objects.requireNonNull(declared.get(path), path).getInputName();
                (state.writeRefusal() == SettingsBackend.RefusalKind.HOST_ONLY ? hostOnly : taken).add(name);
            });
            TreeSet<String> offered = new TreeSet<>(Objects.requireNonNull(schemas.get(tool), tool));
            assertThat(offered).as("step 2: " + tool + " takes its revision").contains(SettingsOperations.REVISION);
            offered.removeAll(REVIEW);
            assertThat(offered).as("step 2: " + tool + " takes each setting its section shows, once")
                .containsExactlyInAnyOrderElementsOf(taken);
            assertThat(offered).as("step 2: " + tool + " takes no setting only the host sets")
                .noneMatch(hostOnly::contains);
            arguments += taken.size();
        }
        assertThat(arguments).as("step 3: the sections' settings were read").isGreaterThan(80);
        assertThat(schemas.get("settings_hosts_set")).as("step 3: the capacity settings are arguments")
            .contains("host_memory_reserve_mb", "memory_overcommit_ratio");
    }

    private static void collect(SettingGroup root, SettingGroup group, Map<String, SettingDefinition<?>> out) {
        for (SettingDefinition<?> definition : group.getSettingDefinitions().values()) {
            out.put(definition.getPathFrom(root), definition);
        }
        for (SettingGroup child : group.getChildGroups().values()) collect(root, child, out);
    }

    /** @return the arguments, each null: a reset to the settings' defaults */
    private static Map<String, Object> nulls(String... names) {
        Map<String, Object> arguments = new HashMap<>();
        for (String name : names) arguments.put(name, null);
        return arguments;
    }

    /** @return the line of a reading that names the argument */
    private static String line(String reading, String argument) {
        return reading.lines().filter(line -> line.startsWith(argument + " (")).findFirst()
            .orElseThrow(() -> new AssertionError(argument + " is not read in: " + reading));
    }

    private static String revisionIn(String reading) {
        Matcher matcher = REVISION.matcher(reading);
        assertThat(matcher.find()).as("a reading says its revision: " + reading).isTrue();
        return matcher.group(1);
    }
}
