package be.elevenways.hohenheim.test;

import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.hohenheim.server.HohenheimSettingsFiles;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The settings loader must define EVERY declared group before values load, because the
 * keys of a group that is not defined yet are dropped silently at load time.
 */
class SettingsGroupCoverageTest {

    /**
     * Every group hohenheim declares. The loader DERIVES its own coverage, so this pin is
     * the drift alarm, not the source of truth: adding a group here without the loader
     * covering it must fail loudly, which is the opposite of the settings file quietly
     * losing those keys.
     */
    private static final Set<String> DECLARED_GROUPS = Set.of(
        "proxy", "ssl", "dns", "logging", "node", "storage", "stacks",
        "database", "security", "process", "auth_proteus", "proxy_auth");

    @Test
    void everyDeclaredSettingsGroupIsGuaranteedBeforeValuesLoad() {
        // 1. What the loader guarantees, computed from the declared nested classes.
        Set<String> forced = HohenheimSettingsFiles.forceDefinitions();
        assertThat(forced).as("step 1: the loader guarantees some groups").isNotEmpty();

        // 2. It must cover every declared group. A hand-written list had drifted here and
        //    omitted "stacks", so every stacks.* key in the settings file was dropped on
        //    the migrate-only path -- silently, because an undefined key is not an error.
        assertThat(forced)
            .as("step 2: no declared settings group may be missing from the loader")
            .containsExactlyInAnyOrderElementsOf(DECLARED_GROUPS);

        // 3. And forcing them really DEFINED them: the groups are now registered on the
        //    context's root, which is what makes their file keys survive the load.
        assertThat(HohenheimSettings.HOHENHEIM.getChildGroups().keySet())
            .as("step 3: forcing a definition registers the group, it does not just name it")
            .containsAll(DECLARED_GROUPS);
    }
}
