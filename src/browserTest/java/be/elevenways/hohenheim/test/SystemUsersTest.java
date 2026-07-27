package be.elevenways.hohenheim.test;

import be.elevenways.hohenheim.HohenheimEndpoints;
import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.hohenheim.model.SystemUserModel;
import be.elevenways.hohenheim.server.HohenheimDatabase;
import be.elevenways.hohenheim.server.SystemUsers;
import be.elevenways.hohenheim.server.sitetype.SiteTypes;
import be.elevenways.zenit.common.Zenit;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.*;

/**
 * Pins the uid-resolution contract: null means "inherit the daemon user", a configured
 * user must resolve, and uid 0 (root) is refused outright.
 */
class SystemUsersTest {

    private static boolean initialized = false;

    @BeforeAll
    static void initRuntime() throws Exception {
        if (initialized) return;
        initialized = true;

        SiteTypes.boot();
        HohenheimEndpoints.init();
        TestDatabases.freshDatabase();
        HohenheimTestRuntime.ensureBooted();
        Zenit.getHawkeye().setClientScriptLocation("/cms.js");

        saveUser("hh-uid-test-user", 4242);
        saveUser("hh-uid-test-root", 0);
    }

    private static void saveUser(String name, int uid) {
        var model = Models.get(SystemUserModel.class);
        Row row = model.createEmptyRow();
        row.set(SystemUserModel.NAME, name);
        row.set(SystemUserModel.UID, uid);
        row.set(SystemUserModel.GID, uid);
        row.set(SystemUserModel.HOME, "/nonexistent");
        row.set(SystemUserModel.GECOS, "uid resolution test");
        row.set(SystemUserModel.OBSOLETE, false);
        row.set(SystemUserModel.LAST_SEEN_AT, Instant.now());
        model.save(row);
    }

    @Test
    void unsetUserResolvesToNull() {
        assertThat(SystemUsers.resolveUid(null)).isNull();
        assertThat(SystemUsers.resolveUid("")).isNull();
        assertThat(SystemUsers.resolveUid("   ")).isNull();
    }

    @Test
    void legacyNonPositiveIdResolvesToNull() {
        assertThat(SystemUsers.resolveUid(0)).isNull();
        assertThat(SystemUsers.resolveUid(-1)).isNull();
    }

    @Test
    void configuredUserResolvesToItsUid() {
        assertThat(SystemUsers.resolveUid("hohenheim:hh-uid-test-user")).isEqualTo(4242);
    }

    @Test
    void danglingUserFailsClosed() {
        assertThatThrownBy(() -> SystemUsers.resolveUid("hohenheim:hh-uid-test-missing"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("does not exist");
    }

    @Test
    void rootUserIsRefused() {
        assertThatThrownBy(() -> SystemUsers.resolveUid("hohenheim:hh-uid-test-root"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("root");
    }

    @Test
    void privilegeDropKeepsEnvironmentValuesOutOfArgv() {
        Map<String, String> environment = new LinkedHashMap<>();
        environment.put("PATH", "/safe/bin");
        environment.put("HOME", "/srv/site");
        environment.put("DATABASE_PASSWORD", "argv-secret-value");

        ProcessBuilder builder = SystemUsers.executionBuilder(
            new SystemUsers.RunAsUser("site", 4242, 4243, "/srv/site"), environment,
            List.of("node", "server.js"), true);

        assertThat(builder.command())
            .containsExactly("/usr/bin/setsid", "--wait", "--", "/usr/bin/sudo", "-n",
                "--preserve-env", "-u", "#4242", "-g", "#4243", "--", "node", "server.js")
            .noneMatch(argument -> argument.contains("argv-secret-value"))
            .noneMatch(argument -> argument.startsWith("DATABASE_PASSWORD="));
        assertThat(builder.environment()).containsEntry("DATABASE_PASSWORD", "argv-secret-value");
    }

    @Test
    void explicitChildEnvironmentDoesNotInheritDaemonSecrets() throws Exception {
        ProcessBuilder builder = new ProcessBuilder("sh", "-c", "env");
        Map<String, String> environment = SystemUsers.safeEnvironment("/srv/site");
        environment.put("PORT", "4321");
        SystemUsers.setEnvironment(builder, environment);

        Process process = builder.redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes());
        assertThat(process.waitFor()).isZero();
        assertThat(output)
            .contains("PATH=")
            .contains("HOME=/srv/site")
            .contains("PORT=4321");

        // Clear-then-put proof: EVERY env key the child sees must come from the
        // explicit allowlist (safeEnvironment's PATH/LANG/HOME + the put PORT)
        // or be shell-injected (sh sets PWD/SHLVL/_ even into a cleared env;
        // pinned empirically with `env -i sh -c env`). A doesNotContain list of
        // guessed daemon var names proves nothing about inheritance.
        Set<String> allowed = Set.of("PATH", "LANG", "HOME", "PORT", "PWD", "SHLVL", "_");
        List<String> keys = output.lines()
            .filter(line -> line.contains("="))
            .map(line -> line.substring(0, line.indexOf('=')))
            .toList();
        assertThat(keys).isNotEmpty().allSatisfy(key ->
            assertThat(allowed).as("env key %s leaked from the daemon environment", key)
                .contains(key));
    }
}
