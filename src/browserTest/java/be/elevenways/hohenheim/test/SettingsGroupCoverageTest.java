package be.elevenways.hohenheim.test;

import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.hohenheim.server.HohenheimRetiredNames;
import be.elevenways.hohenheim.server.HohenheimSettingsBoot;
import be.elevenways.zenit.server.setting.DryFileSource;
import be.elevenways.zenit.server.setting.RetiredName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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
        "proxy", "roles", "ssl", "dns", "logging", "storage", "stacks",
        "database", "security", "process", "auth_proteus", "proxy_auth", "quota",
        // The instance tier's own groups. The loader has forced them since they
        // were declared; this pin had simply drifted behind, which is the failure
        // mode the pin exists to make loud rather than a loader gap ("files"
        // drifted the same way when the file manager landed, caught 2026-08-04).
        "instances", "backup", "files",
        // "builds" (sandboxed builders) had drifted out of this pin the same way;
        // caught 2026-08-04 when "releases" (health-gated releases) was added.
        "builds", "releases",
        // Preview deployments (git-provider wave).
        "previews",
        // Per-host memory capacity (resource-aware placement wave).
        "capacity",
        // Incus daemon/controller knobs. Drifted out of this pin the same way as
        // "stacks", "files", "builds" and "releases" before it; caught 2026-08-07.
        "incus",
        // Host HEALTH (2026-08-09), and deliberately its own group rather than a knob
        // folded into "capacity". The two answer different questions -- capacity rations a
        // measured resource, this judges whether the machine is still answering at all --
        // and a host that has gone silent needs no memory reading to be refused. Filing it
        // under a group labelled "Host capacity" would have contradicted the AIDEV-NOTE
        // sitting on InstanceCapacity.readingIsFresh, which argues at length that neither
        // bound subsumes the other, and would have set the precedent that every host knob
        // lands wherever its first consumer happened to read it.
        "hosts");

    @Test
    void everyDeclaredSettingsGroupIsGuaranteedBeforeValuesLoad() {
        // 1. What the loader guarantees, computed from the declared nested classes.
        Set<String> forced = HohenheimSettingsBoot.forceDefinitions();
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

    /**
     * The operator's {@code settings/local.dry} is gitignored, so a fresh clone boots without
     * it. Absence must be the normal case, and what is left must be the PRODUCTION shape.
     */
    @Test
    void anAbsentSettingsFileLeavesTheProductionDefaults() throws IOException {
        // 1. The retired settings/hohenheim.dry is adopted into local.dry under hohenheim.*,
        //    never read as a file of its own.
        assertThat(HohenheimRetiredNames.RETIRED)
            .as("step 1: the old file is declared adopted under the hohenheim group")
            .contains(RetiredName.adoptedFile("settings/hohenheim.dry", HohenheimSettings.HOHENHEIM));

        // 2. A missing file is not a failure: an empty snapshot, no throw. A boot that
        //    died on the absent file would have made gitignoring it a bad trade.
        Path absent = Path.of("settings/hohenheim.dry.absent-on-purpose");
        assertThat(Files.exists(absent))
            .as("step 2: the probe path must really not exist").isFalse();
        assertThat(new DryFileSource(absent).snapshot())
            .as("step 2: an absent settings source loads as empty").isEmpty();

        // 3. And the code defaults ARE the production shape: 80/443 with Let's Encrypt
        //    on. The tracked file only ever held a developer's overrides of these three
        //    (8080 and letsencrypt off), so there was no default to relocate.
        assertThat(HohenheimSettings.Proxy.HTTP_PORT.getDefaultValue())
            .as("step 3: the HTTP default is the public port").isEqualTo(80);
        assertThat(HohenheimSettings.Proxy.HTTPS_PORT.getDefaultValue())
            .as("step 3: the HTTPS default is the public port").isEqualTo(443);
        assertThat(HohenheimSettings.Ssl.LETSENCRYPT_ENABLED.getDefaultValue())
            .as("step 3: certificates are automatic by default").isTrue();

        // 4. And the operator files stay UNTRACKED, the old one's adoption backup too. They
        //    hold per-deployment values and the proxy trust keys -- tracking them means either
        //    a permanently dirty worktree on every deployment or a secret in git.
        Path gitignore = Path.of(".gitignore");
        assertThat(Files.exists(gitignore))
            .as("step 4: the repo root is the working directory").isTrue();
        assertThat(Files.readString(gitignore).lines().map(String::trim).toList())
            .as("step 4: settings/local.dry and the retired file's backups are gitignored")
            .contains("settings/hohenheim.dry*", "settings/local.dry");
    }
}
