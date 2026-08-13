package be.elevenways.hohenheim.test.instance;

import be.elevenways.hohenheim.server.instance.DockerContainerKind;
import be.elevenways.hohenheim.server.instance.IncusContainerKind;
import be.elevenways.hohenheim.server.instance.IncusVmKind;
import be.elevenways.hohenheim.server.runtime.ImageOrigin;
import be.elevenways.hohenheim.server.runtime.InstanceSpec;
import be.elevenways.hohenheim.test.HohenheimTestRuntime;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What a kind DECLARES lands on its spec, and what it declares nothing for carries the
 * tier's documented default -- walked across the three instance kinds.
 *
 * AIDEV-NOTE: written 2026-08-13 with the {@link InstanceSpec} builder that replaced the
 * ladder of five positional convenience constructors, and it is the guard that made the
 * replacement safe to do. The ladder encoded every default POSITIONALLY, so the migration
 * could silently change one (a {@code guestAgent} that flipped to false makes every
 * exec-driven operation refuse by name; an {@code imageOrigin} that flipped to PREPARED
 * stops an image being fetched at all) with nothing failing until a live daemon saw it.
 * The defaults are BEHAVIOUR, not formatting, which is why they are asserted by name here
 * rather than left to whichever live test happens to trip over one.
 */
class InstanceSpecDeclarationTest {

    /** OwnerLabels resolves the controller identity out of the database. */
    @BeforeAll
    static void controllerIdentity() {
        HohenheimTestRuntime.ensureDatasource();
    }

    @Test
    void everyKindCarriesWhatItDeclaresAndTheTierDefaultForWhatItDoesNot() {
        // 1. THE FLOOR: a Docker container declaring nothing but an image. Every
        //    component the kind has no answer for must read as "not declared", never as
        //    an accidental value the driver would then act on.
        InstanceSpec bare = new DockerContainerKind().specFor(7301, Map.of("image", "nginx"));
        assertThat(bare.image()).as("step 1: the declared image lands").isEqualTo("nginx");
        assertThat(bare.command()).as("step 1: no command override").isNull();
        assertThat(bare.env()).as("step 1: no environment").isEmpty();
        assertThat(bare.volumes()).as("step 1: no named volumes").isEmpty();
        assertThat(bare.publications()).as("step 1: no port publication").isEmpty();
        assertThat(bare.publication()).as("step 1: and its single-publication reading agrees")
            .isNull();
        assertThat(bare.cloudInitUserData()).as("step 1: no cloud-init").isNull();
        assertThat(bare.imageFingerprint()).as("step 1: no pinned fingerprint").isNull();
        assertThat(bare.imageOrigin())
            .as("step 1: the CATALOG origin -- a container image is fetched by reference")
            .isEqualTo(ImageOrigin.CATALOG);
        assertThat(bare.secureBoot()).as("step 1: no Secure Boot claim").isFalse();
        assertThat(bare.guestAgent())
            .as("step 1: the guest-agent claim defaults TRUE -- false makes an exec-driven"
                + " operation refuse by name, so a flipped default is a broken tier")
            .isTrue();
        assertThat(bare.tmpfs()).as("step 1: no RAM-backed scratch").isEmpty();
        assertThat(bare.healthCheck()).as("step 1: no declared health probe").isNull();
        assertThat(bare.rootDiskGb()).as("step 1: root inherited from the image").isNull();
        assertThat(bare.networkLimitMbit()).as("step 1: the wire is unshaped").isNull();
        assertThat(bare.hardening())
            .as("step 1: the kind's DECLARED isolation profile, never an inherited one")
            .isEqualTo(DockerContainerKind.HARDENING);
        assertThat(bare.ownerLabels()).as("step 1: attribution rides the spec").isNotEmpty();

        // 2. The SAME kind with declarations: each lands in its own component, and none
        //    of the step-1 defaults moves as a side effect.
        InstanceSpec declared = new DockerContainerKind().specFor(7302, Map.of(
            "image", "redis", "tag", "7",
            "command", "redis-server --appendonly yes",
            "environment_variables", Map.of("TZ", "UTC"),
            "volumes", Map.of("data", "/data"),
            "container_port", 6379));
        assertThat(declared.image()).as("step 2: the tag is folded into the reference")
            .isEqualTo("redis:7");
        assertThat(declared.command())
            .as("step 2: the command override is split into argv")
            .containsExactly("redis-server", "--appendonly", "yes");
        assertThat(declared.env()).as("step 2: the declared environment").containsEntry("TZ", "UTC");
        assertThat(declared.volumes()).as("step 2: the volume is handle-scoped")
            .containsValue("/data");
        assertThat(declared.publication()).as("step 2: the declared port").isNotNull();
        assertThat(declared.publication().containerPort()).as("step 2: on its number")
            .isEqualTo(6379);
        assertThat(declared.guestAgent())
            .as("step 2: and an unrelated default is untouched by all of that").isTrue();
        assertThat(declared.rootDiskGb()).as("step 2: as is this one").isNull();

        // 3. THE PAIR THE ARITY TRAP DROPPED: an Incus VM declares root disk AND the
        //    bandwidth ceiling, plus the two booleans no other kind moves. All four are
        //    the components a call site one argument short used to mis-bind.
        InstanceSpec vm = new IncusVmKind().specFor(7303, Map.of(
            "image", "ubuntu/24.04", "root_disk_gb", 40, "network_limit_mbit", 100,
            "secure_boot", true, "guest_agent", false, "cloud_init", "#cloud-config\n"));
        assertThat(vm.rootDiskGb()).as("step 3: the declared root disk").isEqualTo(40);
        assertThat(vm.networkLimitMbit()).as("step 3: the declared bandwidth ceiling")
            .isEqualTo(100);
        assertThat(vm.secureBoot()).as("step 3: the declared Secure Boot requirement").isTrue();
        assertThat(vm.guestAgent())
            .as("step 3: a DECLARED absence of a guest agent, not the default").isFalse();
        assertThat(vm.cloudInitUserData()).as("step 3: the rendered cloud-init")
            .isEqualTo("#cloud-config\n");
        assertThat(vm.publications())
            .as("step 3: a VM is structurally publication-free").isEmpty();

        // 4. An Incus container: the two quota components land, and the absences the kind
        //    calls structural really are absences on the spec the driver receives.
        InstanceSpec container = new IncusContainerKind().specFor(7304, Map.of(
            "image", "images:debian/12", "root_disk_gb", 10, "network_limit_mbit", 50,
            "environment_variables", Map.of("LANG", "C.UTF-8")));
        assertThat(container.rootDiskGb()).as("step 4: the declared root disk").isEqualTo(10);
        assertThat(container.networkLimitMbit()).as("step 4: the declared ceiling").isEqualTo(50);
        assertThat(container.env()).as("step 4: the declared environment")
            .containsEntry("LANG", "C.UTF-8");
        assertThat(container.command()).as("step 4: a system container boots its own init")
            .isNull();
        assertThat(container.volumes()).as("step 4: the rootfs IS the persistent state")
            .isEmpty();
        assertThat(container.publications()).as("step 4: no proxy devices yet").isEmpty();
        assertThat(container.healthCheck())
            .as("step 4: and no health probe -- only a stack service declares one").isNull();
    }
}
