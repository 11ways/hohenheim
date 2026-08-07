package be.elevenways.hohenheim.test.stack;

import be.elevenways.hohenheim.server.stack.StackSpec;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * StackSpec mechanics: dependency ordering, cycle/unknown refusal, and the
 * snapshot map round-trip that rollback depends on.
 */
class StackSpecTest {

    private static StackSpec.ServiceSpec service(String name, List<StackSpec.DependsSpec> depends) {
        return new StackSpec.ServiceSpec(name.hashCode() & 0xffff, name, "alpine:latest", List.of(), Map.of(),
            List.of(), List.of(), depends, List.of(),
            null, 10, 5, 5, 0, "unless-stopped", null, null, List.of());
    }

    @Test
    void sortsServicesTopologically() {
        StackSpec.ServiceSpec a = service("a", List.of(new StackSpec.DependsSpec("b", "started")));
        StackSpec.ServiceSpec b = service("b", List.of(new StackSpec.DependsSpec("c", "healthy")));
        StackSpec.ServiceSpec c = service("c", List.of());

        List<StackSpec.ServiceSpec> sorted = StackSpec.topologicallySorted(List.of(a, b, c));
        assertThat(sorted).extracting(StackSpec.ServiceSpec::name).containsExactly("c", "b", "a");
    }

    @Test
    void keepsDeclarationOrderAmongIndependentServices() {
        List<StackSpec.ServiceSpec> sorted = StackSpec.topologicallySorted(List.of(
            service("z", List.of()), service("a", List.of()), service("m", List.of())));
        assertThat(sorted).extracting(StackSpec.ServiceSpec::name).containsExactly("z", "a", "m");
    }

    @Test
    void refusesCyclesAndUnknownDependencies() {
        StackSpec.ServiceSpec a = service("a", List.of(new StackSpec.DependsSpec("b", "started")));
        StackSpec.ServiceSpec b = service("b", List.of(new StackSpec.DependsSpec("a", "started")));
        assertThatThrownBy(() -> StackSpec.topologicallySorted(List.of(a, b)))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("cycle");

        StackSpec.ServiceSpec orphan = service("x", List.of(new StackSpec.DependsSpec("ghost", "started")));
        assertThatThrownBy(() -> StackSpec.topologicallySorted(List.of(orphan)))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("ghost");

        assertThatThrownBy(() -> StackSpec.topologicallySorted(
            List.of(service("dup", List.of()), service("dup", List.of()))))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Duplicate");
    }

    @Test
    void snapshotMapRoundTripsLosslessly() {
        StackSpec.ServiceSpec rich = new StackSpec.ServiceSpec(
            77, "web", "nginx:1.27", List.of("nginx", "-g", "daemon off;"),
            Map.of("MODE", "prod"),
            List.of(new StackSpec.MountSpec("volume", "data", "/var/lib/data", null),
                new StackSpec.MountSpec("tmpfs", "scratch", "/tmp/scratch", null),
                new StackSpec.MountSpec("volume", "certs", "/certs", "external_certs")),
            List.of(new StackSpec.PortSpec(80, 8080, "tcp", "127.0.0.1"),
                new StackSpec.PortSpec(51820, 51820, "udp", "")),
            List.of(new StackSpec.DependsSpec("db", "healthy")),
            List.of(new StackSpec.FileSpec("/etc/app/config.yaml", "key: value\n", "0600")),
            "wget -q -O /dev/null http://127.0.0.1/", 7, 3, 4, 15,
            "always", 512, 1.5, List.of("NET_RAW", "IPC_LOCK"));
        StackSpec.ServiceSpec db = service("db", List.of());

        StackSpec original = new StackSpec(42, "mystack", "local",
            "ghcr.io", "robot", "s3cret", StackSpec.topologicallySorted(List.of(rich, db)));

        StackSpec revived = StackSpec.fromMap(original.toMap());
        assertThat(revived).isEqualTo(original);
    }

    /**
     * A stored snapshot outlives the record shape it was written from: rollback re-deploys
     * a spec serialized by an older build. Dropping a field must therefore DEGRADE, never
     * throw -- see M084_DropStackSubnet.
     */
    @Test
    void revivesASnapshotWrittenBeforeAFieldWasDropped() {
        StackSpec current = new StackSpec(9, "legacy", "local", null, null, null,
            List.of(service("only", List.of())));

        // 1. Positive anchor: a snapshot written by THIS build round-trips exactly, and
        //    carries no trace of the dropped column.
        Map<String, Object> fresh = current.toMap();
        assertThat(fresh)
            .as("step 1: the dropped field is not written into new snapshots")
            .doesNotContainKey("subnet");
        assertThat(StackSpec.fromMap(fresh))
            .as("step 1: a current snapshot round-trips")
            .isEqualTo(current);

        // 2. An OLD snapshot -- the exact shape a pre-M084 build stored, subnet included --
        //    revives without throwing, and the surviving fields survive intact.
        Map<String, Object> old = new LinkedHashMap<>(fresh);
        old.put("subnet", "172.30.9.0/24");
        StackSpec revived = StackSpec.fromMap(old);
        assertThat(revived)
            .as("step 2: an old snapshot degrades to the current shape rather than throwing")
            .isEqualTo(current);

        // 3. The degradation is not blank-tolerance luck: a whole family of retired keys
        //    is ignored the same way, because reviving is key-driven and never exhaustive.
        Map<String, Object> older = new LinkedHashMap<>(old);
        older.put("adopt_resources", true);
        older.put("some_future_retired_field", List.of("x", "y"));
        assertThat(StackSpec.fromMap(older))
            .as("step 3: any retired key is ignored, not rejected")
            .isEqualTo(current);
    }
}
