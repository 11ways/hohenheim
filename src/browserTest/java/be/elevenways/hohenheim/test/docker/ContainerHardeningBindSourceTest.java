package be.elevenways.hohenheim.test.docker;

import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.server.docker.ContainerHardening;
import be.elevenways.hohenheim.server.docker.OwnerLabels;
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
 *
 * AIDEV-NOTE: there are TWO rules here, and the second one arrived on 2026-08-23 because
 * the first was never enough. The volume-ROOT rule is a statement about this DEPLOYMENT
 * and says nothing about which INSTANCE, so instance A's spec could legally bind instance
 * B's data directory -- proven live, and caught only by a kernel-side mountinfo assertion.
 * {@code aVolumeOfAnotherInstanceIsRefused} is the falsification of that hole and must
 * never be weakened into "somewhere under the root is enough".
 */
class ContainerHardeningBindSourceTest {

    /** The instance every spec here is created for. */
    private static final int OWNER = 42;

    /** Another instance, whose volume directory sits under the SAME permitted root. */
    private static final int NEIGHBOUR = 43;

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
        Map<String, Object> permitted = specWithBind(root + "/" + OWNER + "/home");
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
            root + "/" + OWNER + "/../../../etc/shadow",
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

        Map<String, Object> spec = specWithBind("/srv/moved/volumes/7/home", 7);
        assertThatThrownBy(() -> ContainerHardening.applyTo(spec, ContainerHardening.SERVICE))
            .as("step 1: a path under the OLD root is refused while data_path says otherwise")
            .isInstanceOf(IllegalArgumentException.class);

        HohenheimSettings.VALUES.setValue(HohenheimSettings.Storage.DATA_PATH, "/srv/moved");
        try {
            Map<String, Object> moved = specWithBind("/srv/moved/volumes/7/home", 7);
            ContainerHardening.applyTo(moved, ContainerHardening.SERVICE);
            assertThat(hostConfigOf(moved))
                .as("step 2: and accepted once data_path names it")
                .containsKey("PidsLimit");
        } finally {
            HohenheimSettings.VALUES.setValue(HohenheimSettings.Storage.DATA_PATH,
                "/srv/hoh-test");
        }
    }

    /**
     * THE cross-instance hole: a bind source under the volume root that belongs to
     * ANOTHER instance is refused, however legitimate the root looks.
     *
     * AIDEV-NOTE: this is the falsification of the defect proven live on 2026-08-23 --
     * injecting one extra bind naming a neighbour instance's volume directory passed the
     * hardening funnel with no refusal, because the only rule was "somewhere under the
     * volume root". Containment between instances rested entirely on
     * {@code InstanceVolumes.hostPathFor} being the only minter of those paths, which
     * nothing enforced. It is enforced here now.
     */
    @Test
    void aVolumeOfAnotherInstanceIsRefused() {

        String root = "/srv/hoh-test/volumes";

        // 1. The positive anchor: this instance's OWN volume directory still mounts, and
        //    is still hardened. Without it every refusal below could be a policy that
        //    refuses everything.
        Map<String, Object> own = specWithBind(root + "/" + OWNER + "/home");
        ContainerHardening.applyTo(own, ContainerHardening.SERVICE);
        assertThat(hostConfigOf(own).get("CapDrop"))
            .as("step 1: an instance mounts its own volume directory")
            .isEqualTo(List.of("ALL"));

        // 2. THE HOLE. The neighbour's directory sits under the SAME volume root the outer
        //    rule permits, so only the per-instance rule can refuse it -- and it must name
        //    both instances, or an operator cannot tell what was refused from what.
        Map<String, Object> neighbour = specWithBind(root + "/" + NEIGHBOUR + "/home");
        assertThatThrownBy(() -> ContainerHardening.applyTo(neighbour, ContainerHardening.SERVICE))
            .as("step 2: another instance's volume directory is not this instance's to bind")
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("REFUSED")
            .hasMessageContaining("#" + OWNER)
            .hasMessageContaining("#" + NEIGHBOUR);

        // 3. FAIL CLOSED: a container that declares no instance owner may bind NOTHING,
        //    not everything. An unattributable spec is the shape a bind injected past the
        //    kind would have, so "no labels" must never mean "no check".
        Map<String, Object> anonymous = specWithBind(root + "/" + OWNER + "/home", null);
        assertThatThrownBy(() -> ContainerHardening.applyTo(anonymous, ContainerHardening.SERVICE))
            .as("step 3: no declared instance owner means no bind at all")
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("no instance owner");

        // 4. And an owner of another MODEL is not an instance either: a build operation or
        //    a site owns no volume directories, so it may bind none.
        Map<String, Object> otherModel = new LinkedHashMap<>();
        Map<String, Object> hostConfig = new LinkedHashMap<>();
        hostConfig.put("Mounts", List.of(Map.of("Type", "bind",
            "Source", root + "/" + OWNER + "/home", "Target", "/home/site")));
        otherModel.put("Labels", Map.of(OwnerLabels.MODEL, "hohenheim:build_operation",
            OwnerLabels.ID, String.valueOf(OWNER)));
        otherModel.put("HostConfig", hostConfig);
        assertThatThrownBy(() -> ContainerHardening.applyTo(otherModel, ContainerHardening.SERVICE))
            .as("step 4: an owner that is not an instance owns no volume directory")
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("no instance owner");

        // 5. The path must be one InstanceVolumes could have MINTED, not merely something
        //    under the root: two segments, an all-digits instance id, a plain volume name.
        //    A shape this policy cannot attribute to an instance is refused, never trusted.
        for (String unmintable : List.of(
                root + "/" + OWNER,                    // the instance directory itself
                root + "/" + OWNER + "/home/sub",      // deeper than a volume directory
                root + "/" + OWNER + "/",              // a trailing slash is not a name
                root + "/notanumber/home",             // no instance to attribute it to
                root + "/" + OWNER + "/.")) {          // a plain name is a plain name
            Map<String, Object> spec = specWithBind(unmintable);
            assertThatThrownBy(() ->
                    ContainerHardening.applyTo(spec, ContainerHardening.SERVICE))
                .as("step 5: bind source '" + unmintable + "' is not a mintable volume path")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("REFUSED");
        }
    }

    /** A spec binding one host path, owned by instance {@link #OWNER}. */
    private static Map<String, Object> specWithBind(String source) {
        return specWithBind(source, OWNER);
    }

    /**
     * A spec binding one host path on behalf of one instance.
     *
     * AIDEV-NOTE: the labels are built from the CONSTANTS rather than through
     * {@code OwnerLabels.of}, which mints a controller token out of the control-plane
     * database. Parsing needs no datasource, which is what keeps this class hermetic --
     * and the funnel only ever PARSES.
     */
    private static Map<String, Object> specWithBind(String source, Integer instanceId) {
        Map<String, Object> hostConfig = new LinkedHashMap<>();
        hostConfig.put("Mounts", List.of(
            Map.of("Type", "bind", "Source", source, "Target", "/home/site")));
        Map<String, Object> spec = new LinkedHashMap<>();
        if (instanceId != null) {
            spec.put("Labels", Map.of(OwnerLabels.MODEL, InstanceModel.MODEL_ID.toString(),
                OwnerLabels.ID, String.valueOf(instanceId)));
        }
        spec.put("HostConfig", hostConfig);
        return spec;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> hostConfigOf(Map<String, Object> spec) {
        return (Map<String, Object>) spec.get("HostConfig");
    }
}
