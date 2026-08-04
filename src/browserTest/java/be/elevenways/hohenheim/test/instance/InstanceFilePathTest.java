package be.elevenways.hohenheim.test.instance;

import be.elevenways.hohenheim.server.files.InstanceFilePath;
import be.elevenways.zenit.common.validation.Violations;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * Layer 2 of the file-manager containment argument, on its own: the LEXICAL rule that a
 * submitted path must already BE the one canonical spelling of a path under a declared
 * volume root. No daemon, no container -- this is the half that must hold before anything
 * is asked of one.
 */
class InstanceFilePathTest {

    private static final List<String> ROOTS = List.of("/data", "/srv/world");

    @Test
    void refusesEveryEscapeSpellingAndAcceptsOnlyTheCanonicalOne() {
        // 1. The plain, canonical shapes are accepted and keep their root.
        InstanceFilePath root = InstanceFilePath.parse(ROOTS, "/data");
        assertThat(root.isVolumeRoot())
            .as("step 1: the volume root parses as the root")
            .isTrue();
        InstanceFilePath nested = InstanceFilePath.parse(ROOTS, "/data/config/server.properties");
        assertThat(nested.volumeRoot())
            .as("step 1: a nested path keeps the root it lives under")
            .isEqualTo("/data");
        assertThat(nested.name())
            .as("step 1: the leaf name is the last segment")
            .isEqualTo("server.properties");

        // 2. Traversal: every '..' spelling is refused OUTRIGHT rather than normalized.
        //    Normalizing is what turns /data/../etc/passwd into an ACCEPTED /etc/passwd.
        for (String traversal : List.of("/data/../etc/passwd", "/data/..", "/../data",
                "/data/config/../../etc/shadow", "/data/./config", "/data/a/../a")) {
            assertThat(catchThrowable(() -> InstanceFilePath.parse(ROOTS, traversal)))
                .as("step 2: traversal '" + traversal + "' is refused")
                .isInstanceOf(Violations.class);
        }

        // 3. Absolute paths outside every declared root are refused, INCLUDING ones that
        //    merely share a prefix with a root -- "/database" is not under "/data".
        for (String outside : List.of("/etc/passwd", "/", "/srv", "/database/x", "/datax",
                "/srv/worldly/x")) {
            assertThat(catchThrowable(() -> InstanceFilePath.parse(ROOTS, outside)))
                .as("step 3: '" + outside + "' is outside every declared volume")
                .isInstanceOf(Violations.class);
        }

        // 4. Relative and non-canonical spellings are refused. A '//' or a trailing slash
        //    is an ALIAS of an accepted path, and two spellings of one path is how one of
        //    them ends up on the wrong side of a later comparison.
        for (String noncanonical : List.of("data/x", "", "/data//x", "/data/", "/data/x/")) {
            assertThat(catchThrowable(() -> InstanceFilePath.parse(ROOTS, noncanonical)))
                .as("step 4: non-canonical spelling '" + noncanonical + "' is refused")
                .isInstanceOf(Violations.class);
        }

        // 5. Encoded variants. The HTTP layer percent-decodes ONCE before a handler sees a
        //    value, so a single-encoded '..' arrives as '..' and hits step 2. A DOUBLY
        //    encoded one arrives as the literal text '%2e%2e', which is an ordinary
        //    filename: accepted as a NAME, and structurally incapable of leaving /data.
        assertThat(catchThrowable(() -> InstanceFilePath.parse(ROOTS, "/data/%2e%2e/etc")))
            .as("step 5: a literal %2e%2e segment is not traversal, it is a filename")
            .isNull();
        assertThat(InstanceFilePath.parse(ROOTS, "/data/%2e%2e/etc").volumeRoot())
            .as("step 5: and it stays inside the volume it was parsed against")
            .isEqualTo("/data");

        // 6. Control characters, NUL included: a NUL is how a downstream C consumer can be
        //    made to see a shorter string than the one we validated.
        for (String hostile : List.of("/data/x\0/etc/passwd", "/data/x\n/y", "/data/x\t")) {
            assertThat(catchThrowable(() -> InstanceFilePath.parse(ROOTS, hostile)))
                .as("step 6: control characters are refused")
                .isInstanceOf(Violations.class);
        }

        // 7. The ancestor chain the resolved-containment walk consumes starts AT the volume
        //    root and never above it -- a walk that started at "/" would stat, and trust,
        //    directories nobody declared.
        List<InstanceFilePath> chain = nested.chainFromRoot();
        assertThat(chain.stream().map(InstanceFilePath::absolute).toList())
            .as("step 7: the containment walk covers root, every directory, then the leaf")
            .containsExactly("/data", "/data/config", "/data/config/server.properties");

        // 8. Depth is bounded: an unbounded chain is an unbounded number of daemon stats
        //    per request, which is the amplification a file manager must not offer.
        StringBuilder deep = new StringBuilder("/data");
        for (int i = 0; i <= InstanceFilePath.MAX_DEPTH; i++) {
            deep.append("/d");
        }
        assertThat(catchThrowable(() -> InstanceFilePath.parse(ROOTS, deep.toString())))
            .as("step 8: a path deeper than MAX_DEPTH is refused")
            .isInstanceOf(Violations.class);

        // 9. Root normalization: a declared volume path with a trailing slash is the same
        //    root, and a volume at "/" is NOT a browse root (that would make the whole
        //    image filesystem "the tenant's volume").
        Set<String> roots = InstanceFilePath.rootsOf(List.of("/data/", "/", "relative", "/srv"));
        assertThat(roots)
            .as("step 9: only real, non-root, absolute volume paths become browse roots")
            .containsExactlyInAnyOrder("/data", "/srv");
    }
}
