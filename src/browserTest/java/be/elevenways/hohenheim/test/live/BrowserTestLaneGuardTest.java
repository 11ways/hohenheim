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
 * Keeps the three browser lanes honest: every test class lands in exactly one, and
 * {@code @Tag("solo")} -- the only tag that buys a private JVM back -- stays justified.
 *
 * <p>The lanes are {@code browserTest} (shared server + browser, {@code @Tag("shared-server")}
 * inherited from {@code HohenheimTestBase}), {@code browserTestStandalone} (shared JVM, the
 * catch-all by negation) and {@code browserTestSolo} ({@code forkEvery = 1}, holding
 * {@code @Tag("solo")} plus the whole {@code @Tag("slow")} live suite).
 *
 * <p>AIDEV-NOTE: this guard exists because the lane split is a NEGATION rule. A class joins
 * the shared-JVM lane by doing nothing at all, which is what makes the design cheap -- and
 * also what makes a mistake invisible. The failure it is built to catch is a class tagged
 * {@code solo} "because it went red", which is almost always a leak worth fixing rather than
 * a JVM worth buying: the 2026-08-12 collapse took the lane from 513s to 340s precisely by
 * refusing that reflex, and one careless tag is a permanent 4s JVM boot plus a full runtime
 * boot back on the bill.
 */
class BrowserTestLaneGuardTest {

    /** Helpers and fixtures, not test classes to enforce lane membership on. */
    private static final Set<String> HELPERS = Set.of(
        "LiveLane", "LiveLaneReport", "LiveIncusHost", "LiveRemoteHost",
        "FakeIncusTransport", "SlowLaneGuardTest", "BrowserTestLaneGuardTest");

    private static final Pattern EXTENDS = Pattern.compile(
        "class\\s+(\\w+)(?:<[^>]*>)?\\s+extends\\s+([\\w.]+)");

    /**
     * Every solo tag must carry a written justification, and no class may claim two lanes.
     */
    @Test
    void everySoloTagIsJustifiedAndNoClassClaimsTwoLanes() throws IOException {
        // 1. Collect every browser-test source file and its in-tree superclass.
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

        // 2. shared-server and solo are mutually exclusive: browserTestSolo excludes the
        //    shared-server tag, so a class carrying both would run in NO lane at all.
        List<String> both = new ArrayList<>();
        List<String> soloClasses = new ArrayList<>();
        for (String cls : concreteTestClasses(sources)) {
            boolean solo = chainMatches(cls, sources, parent, s -> s.contains("@Tag(\"solo\")"));
            boolean shared = chainMatches(cls, sources, parent,
                s -> s.contains("@Tag(\"shared-server\")"));
            if (solo) {
                soloClasses.add(cls);
            }
            if (solo && shared) {
                both.add(cls);
            }
        }
        assertThat(both)
            .as("step 2: a class tagged BOTH shared-server and solo matches no lane -- "
                + "browserTestSolo excludes shared-server, so these tests would silently "
                + "stop running")
            .isEmpty();

        // 3. Every solo tag must be justified in a comment directly above it. A private JVM
        //    costs a fork boot plus a full runtime boot; the reason must name process-global
        //    state with no reset, not "it failed when I merged it".
        List<String> unjustified = new ArrayList<>();
        for (String cls : soloClasses) {
            String src = sources.get(cls);
            if (src == null || !declaresSoloItself(src)) {
                continue;
            }
            if (!justificationPrecedesSoloTag(src)) {
                unjustified.add(cls);
            }
        }
        assertThat(unjustified)
            .as("step 3: @Tag(\"solo\") without a `// Solo: ...` comment naming the "
                + "process-global state that has no reset -- a private JVM is the most "
                + "expensive thing a test class can ask for, so the reason is part of the ask")
            .isEmpty();

        // 4. The solo lane stays SMALL, and the bound is a MEASURED price, not a taste.
        //    2026-08-12: the same three classes cost 74s as a forkEvery=1 lane and 11.4s
        //    sharing a JVM, so a solo tag is worth about TWENTY SECONDS of the default
        //    lane each. Two classes have earned it; a third must argue for it here.
        assertThat(soloClasses)
            .as("step 4: the solo lane grew -- measured at ~20s of default-lane wall clock "
                + "per entry (forkEvery=1 starts and tears down a worker per class). If the "
                + "new class really cannot share a JVM, raise this bound in the same commit "
                + "and say why in the class comment.")
            .hasSizeLessThanOrEqualTo(3);
    }

    /** Concrete, enforceable test classes: named *Test, not abstract, not a helper. */
    private static List<String> concreteTestClasses(Map<String, String> sources) {
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, String> entry : sources.entrySet()) {
            String cls = entry.getKey();
            if (HELPERS.contains(cls) || !cls.endsWith("Test")) {
                continue;
            }
            if (entry.getValue().contains("abstract class " + cls)) {
                continue;
            }
            out.add(cls);
        }
        return out;
    }

    /** True when the class writes the solo tag itself rather than inheriting it. */
    private static boolean declaresSoloItself(String source) {
        return source.contains("@Tag(\"solo\")");
    }

    /** True when a `// Solo:` justification comment sits in the lines above the tag. */
    private static boolean justificationPrecedesSoloTag(String source) {
        int tag = source.indexOf("@Tag(\"solo\")");
        if (tag < 0) {
            return false;
        }
        // Walk back over the contiguous comment block immediately above the annotation.
        String[] lines = source.substring(0, tag).split("\n", -1);
        for (int i = lines.length - 2; i >= 0; i--) {
            String line = lines[i].trim();
            if (line.isEmpty()) {
                return false;
            }
            if (!line.startsWith("//") && !line.startsWith("*") && !line.startsWith("/*")) {
                return false;
            }
            if (line.contains("Solo:")) {
                return true;
            }
        }
        return false;
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
}
