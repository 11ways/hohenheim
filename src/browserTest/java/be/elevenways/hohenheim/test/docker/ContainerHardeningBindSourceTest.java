package be.elevenways.hohenheim.test.docker;

import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.hohenheim.server.docker.ContainerHardening;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The ONE host path a container may bind: a directory under this deployment's volume root.
 *
 * <p>Hermetic on purpose, unlike {@code ContainerHardeningTest} (which is the live lane's
 * proof that the baseline reaches a real kernel). This rule is a pure decision about a
 * HostConfig map, and it is the only rule that says yes to a shape the policy refused
 * outright until phase-0 brief 7 -- so it needs a test that runs on every machine, every
 * time, not one that skips wherever no Docker socket exists.
 *
 * AIDEV-NOTE: every case drives the PUBLIC {@code applyTo}, never the package-private rule
 * directly. What must hold is that a spec reaching {@code DockerClient.createContainer}
 * cannot carry an escaping bind; asserting the helper in isolation would keep passing if
 * somebody stopped calling it.
 */
class ContainerHardeningBindSourceTest {

    private static String savedDataPath;

    @BeforeAll
    static void setUp() {
        savedDataPath = HohenheimSettings.VALUES.getValue(HohenheimSettings.Storage.DATA_PATH);
        HohenheimSettings.VALUES.setValue(HohenheimSettings.Storage.DATA_PATH, "/srv/hoh-test");
    }

    @AfterAll
    static void tearDown() {
        HohenheimSettings.VALUES.setValue(HohenheimSettings.Storage.DATA_PATH, savedDataPath);
    }

    /**
     * A volume directory the controller owns is mounted; anything else -- the docker
     * socket, /etc, a traversal out of the root, the root itself, a relative path, a blank
     * source -- is refused BY NAME and never reaches the daemon.
     */
    @Test
    void onlyAVolumeRootSourceMayBeBound() {

        String root = "/srv/hoh-test/volumes";

        // 1. The shape the release engine actually emits: <root>/<instance>/<name>.
        Map<String, Object> permitted = specWithBind(root + "/42/home");
        ContainerHardening.applyTo(permitted, ContainerHardening.SERVICE);
        assertThat(hostConfigOf(permitted).get("CapDrop"))
            .as("step 1: a volume-directory bind is accepted and still hardened")
            .isEqualTo(List.of("ALL"));

        // 2. Every escaping source is refused, and the refusal NAMES the source and the
        //    root -- an operator reading it must be able to see why their path lost.
        List<String> refused = List.of(
            "/var/run/docker.sock",
            "/etc",
            "/",
            root,                                 // the root itself is not a volume
            root + "/",                           // nor is a trailing slash a volume
            root + "/../../etc",                  // traversal out, textually visible
            root + "/42/../../../etc/shadow",
            "srv/hoh-test/volumes/42/home",       // relative: not an absolute host path
            "/srv/hoh-test/volumes-evil/42/home", // prefix match without the separator
            "");

        for (String source : refused) {
            Map<String, Object> spec = specWithBind(source);
            assertThatThrownBy(() -> ContainerHardening.applyTo(spec, ContainerHardening.SERVICE))
                .as("step 2: bind source '" + source + "' is refused")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("REFUSED")
                .hasMessageContaining(root);
        }

        // 3. Go's json decoder matches HostConfig fields case-insensitively, so the rule
        //    must too: a lowercased mount entry is the same escape wearing a hat.
        Map<String, Object> folded = new LinkedHashMap<>();
        Map<String, Object> hostConfig = new LinkedHashMap<>();
        List<Map<String, Object>> mounts = new ArrayList<>();
        mounts.add(Map.of("type", "BIND", "source", "/var/run/docker.sock",
            "target", "/var/run/docker.sock"));
        hostConfig.put("mounts", mounts);
        folded.put("HostConfig", hostConfig);
        assertThatThrownBy(() -> ContainerHardening.applyTo(folded, ContainerHardening.SERVICE))
            .as("step 3: a case-folded bind entry is refused exactly like the canonical one")
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("REFUSED");

        // 4. The other mount types are untouched by this rule: a named volume and a tmpfs
        //    still pass, so the rule narrowed binds rather than widening the allow-list.
        Map<String, Object> others = new LinkedHashMap<>();
        Map<String, Object> otherConfig = new LinkedHashMap<>();
        otherConfig.put("Mounts", List.of(
            Map.of("Type", "volume", "Source", "db-data", "Target", "/var/lib/data"),
            Map.of("Type", "tmpfs", "Target", "/scratch",
                "TmpfsOptions", Map.of("SizeBytes", 1024L))));
        others.put("HostConfig", otherConfig);
        ContainerHardening.applyTo(others, ContainerHardening.SERVICE);
        assertThat(hostConfigOf(others).get("SecurityOpt"))
            .as("step 4: named volumes and tmpfs still pass the policy")
            .isEqualTo(List.of("no-new-privileges"));
    }

    /**
     * The rule follows the CONFIGURED root, not a compiled-in path: an operator who moves
     * data_path moves what may be bound, in one place.
     */
    @Test
    void theRuleFollowsTheConfiguredDataPath() {

        Map<String, Object> spec = specWithBind("/srv/moved/volumes/7/home");
        assertThatThrownBy(() -> ContainerHardening.applyTo(spec, ContainerHardening.SERVICE))
            .as("step 1: a path under the OLD root is refused while data_path says otherwise")
            .isInstanceOf(IllegalArgumentException.class);

        HohenheimSettings.VALUES.setValue(HohenheimSettings.Storage.DATA_PATH, "/srv/moved");
        try {
            Map<String, Object> moved = specWithBind("/srv/moved/volumes/7/home");
            ContainerHardening.applyTo(moved, ContainerHardening.SERVICE);
            assertThat(hostConfigOf(moved))
                .as("step 2: and accepted once data_path names it")
                .containsKey("PidsLimit");
        } finally {
            HohenheimSettings.VALUES.setValue(HohenheimSettings.Storage.DATA_PATH,
                "/srv/hoh-test");
        }
    }

    private static Map<String, Object> specWithBind(String source) {
        Map<String, Object> hostConfig = new LinkedHashMap<>();
        hostConfig.put("Mounts", List.of(
            Map.of("Type", "bind", "Source", source, "Target", "/home/site")));
        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("HostConfig", hostConfig);
        return spec;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> hostConfigOf(Map<String, Object> spec) {
        return (Map<String, Object>) spec.get("HostConfig");
    }
}
