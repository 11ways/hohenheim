package be.elevenways.hohenheim.test.live;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

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
 * this source tree. A targeted run still executes slow-tagged classes: a
 * --class filter disables the tag exclusion, and `zenit-dev test --all` runs
 * the whole suite including them.
 */
class SlowLaneGuardTest {

    /** The live helpers themselves plus this scanner; never test classes to enforce on. */
    // BrowserTestLaneGuardTest is a sibling SOURCE SCANNER, not a live test: it names the
    // live helpers and quotes @Tag("slow") in prose, which is exactly what this scan looks
    // for, so without the exemption a guard would enforce lane rules against a guard.
    private static final Set<String> HELPERS = Set.of(
        "LiveLane", "LiveLaneReport", "LiveIncusHost", "LiveRemoteHost",
        "FakeIncusTransport", "SlowLaneGuardTest", "BrowserTestLaneGuardTest");

    private static final Pattern LIVE_REF = Pattern.compile(
        "\\bLiveLane\\s*\\.|\\bLiveIncusHost\\b|\\bLiveRemoteHost\\b");

    private static final Pattern EXTENDS = Pattern.compile(
        "class\\s+(\\w+)(?:<[^>]*>)?\\s+extends\\s+([\\w.]+)");

    @Test
    void everyLiveCapableTestClassIsSlowTaggedAndDeclaredNonHermetic() throws IOException {
        // 1. Collect every browser-test source file.
        Path root = Path.of("src/browserTest/java");
        assertThat(root).as("step 1: guard runs from the project root").isDirectory();
        Map<String, String> sources = new HashMap<>();
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path file : walk.filter(p -> p.toString().endsWith(".java")).toList()) {
                String name = file.getFileName().toString();
                sources.put(name.substring(0, name.length() - 5), Files.readString(file));
            }
        }
        assertThat(sources).as("step 1: the browser test tree is non-empty").isNotEmpty();

        // 2. Build the in-tree inheritance map so inherited liveness and tags count.
        Map<String, String> parent = new HashMap<>();
        for (Map.Entry<String, String> entry : sources.entrySet()) {
            Matcher m = EXTENDS.matcher(entry.getValue());
            while (m.find()) {
                if (m.group(1).equals(entry.getKey())) {
                    String sup = m.group(2);
                    parent.put(entry.getKey(), sup.substring(sup.lastIndexOf('.') + 1));
                    break;
                }
            }
        }

        // 3. Find every concrete test class that uses a live capability, own or inherited.
        List<String> untagged = new ArrayList<>();
        List<String> liveClasses = new ArrayList<>();
        for (Map.Entry<String, String> entry : sources.entrySet()) {
            String cls = entry.getKey();
            String src = entry.getValue();
            if (HELPERS.contains(cls) || !cls.endsWith("Test")) {
                continue;
            }
            if (src.contains("abstract class " + cls)) {
                continue;
            }
            if (!chainMatches(cls, sources, parent, s -> LIVE_REF.matcher(s).find())) {
                continue;
            }
            liveClasses.add(cls);
            if (!chainMatches(cls, sources, parent, s -> s.contains("@Tag(\"slow\")"))) {
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

    private static boolean chainMatches(String cls, Map<String, String> sources,
                                        Map<String, String> parent,
                                        java.util.function.Predicate<String> predicate) {
        Set<String> seen = new HashSet<>();
        for (String c = cls; c != null && seen.add(c); c = parent.get(c)) {
            String src = sources.get(c);
            if (src != null && predicate.test(src)) {
                return true;
            }
        }
        return false;
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
