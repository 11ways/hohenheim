package be.elevenways.hohenheim.test.live;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Keeps the default browser lane fast: every test class that needs a live
 * capability (a real daemon, an enrolled host, a pulled image) must be tagged
 * {@code @Tag("slow")} so ordinary {@code zenit-dev test} runs exclude it, and
 * must be declared non-hermetic in {@code .zenit-dev.json} so its receipts are
 * never reused. Untagged live classes are how the default lane regressed to 37
 * minutes once already.
 *
 * AIDEV-NOTE: liveness is detected by source scan -- a reference to the live
 * helpers (the lane gate, the enrolled-host fixtures), own or inherited within
 * this source tree, read through {@link BrowserTestSources}; inheritance and the
 * zenit-dev config are beyond a line pattern, so this is no blastGuards rule. A
 * targeted run still executes slow-tagged classes: a --class filter disables the
 * tag exclusion, and `zenit-dev test --all` runs the whole suite including them.
 */
class SlowLaneGuardTest {

    private static final Pattern LIVE_REF = Pattern.compile(
        "\\bLiveLane\\s*\\.|\\bLiveIncusHost\\b|\\bLiveRemoteHost\\b");

    @Test
    void everyLiveCapableTestClassIsSlowTaggedAndDeclaredNonHermetic() throws IOException {
        // 1. Collect every browser-test source file and, 2., its in-tree inheritance, so inherited
        //    liveness and tags count.
        BrowserTestSources tree = BrowserTestSources.read();

        // 3. Find every concrete test class that uses a live capability, own or inherited.
        List<String> untagged = new ArrayList<>();
        List<String> liveClasses = new ArrayList<>();
        for (String cls : tree.sources.keySet()) {
            if (!tree.isConcreteTest(cls) || !tree.chainMatches(cls, s -> LIVE_REF.matcher(s).find())) {
                continue;
            }
            liveClasses.add(cls);
            if (!tree.chainMatches(cls, s -> s.contains("@Tag(\"slow\")"))) {
                untagged.add(cls);
            }
        }
        assertThat(liveClasses)
            .as("step 3: the live-helper scan still finds the live suite at all")
            .isNotEmpty();
        assertThat(untagged)
            .as("step 3: live-capable test classes missing @Tag(\"slow\") -- tag the class "
                + "(or its base) so the default lane stays fast; it still runs under "
                + "`zenit-dev test --all` and under any --class filter")
            .isEmpty();

        // 4. Every live class must be covered by a nonHermeticClasses glob, so a
        //    green receipt for it is never reused across daemon/host state changes.
        List<String> globs = nonHermeticGlobs();
        assertThat(globs)
            .as("step 4: .zenit-dev.json declares nonHermeticClasses")
            .isNotEmpty();
        List<String> undeclared = liveClasses.stream()
            .filter(cls -> globs.stream().noneMatch(g -> globMatches(g, cls)))
            .toList();
        assertThat(undeclared)
            .as("step 4: live-capable classes not matched by any nonHermeticClasses glob "
                + "in .zenit-dev.json -- add the class name so its receipts are never reused")
            .isEmpty();
    }

    /** The nonHermeticClasses entries, extracted from .zenit-dev.json. */
    private static List<String> nonHermeticGlobs() throws IOException {
        String config = Files.readString(Path.of(".zenit-dev.json"));
        Matcher array = Pattern.compile(
            "\"nonHermeticClasses\"\\s*:\\s*\\[([^\\]]*)\\]", Pattern.DOTALL).matcher(config);
        List<String> globs = new ArrayList<>();
        if (array.find()) {
            Matcher entry = Pattern.compile("\"([^\"]+)\"").matcher(array.group(1));
            while (entry.find()) {
                globs.add(entry.group(1));
            }
        }
        return globs;
    }

    /** Same semantics as zenit-dev's glob-to-class matching: * is a wildcard, anchored. */
    private static boolean globMatches(String glob, String simpleClassName) {
        String regex = ("\\Q" + glob.replace("*", "\\E.*\\Q") + "\\E");
        return Pattern.compile("^" + regex + "$").matcher(simpleClassName).matches();
    }
}
