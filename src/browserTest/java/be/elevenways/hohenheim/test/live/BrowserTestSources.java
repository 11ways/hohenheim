package be.elevenways.hohenheim.test.live;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The browser-test tree as the lane guards read it: every class's source by simple name, its in-tree superclass, and
 * whether a fact holds anywhere up that chain.
 *
 * AIDEV-NOTE: lane membership is decided by tags and live-helper references INHERITED across files, which no line
 * pattern (and so no blastGuards rule) can state, so BrowserTestLaneGuardTest and SlowLaneGuardTest stay tests; this
 * is their one reading of the tree, which each used to carry a copy of.
 *
 * @author Jelle De Loecker
 * @since  0.1.0
 */
final class BrowserTestSources {

    /** Helpers and fixtures, never test classes a lane rule is enforced on. */
    static final Set<String> HELPERS = Set.of(
        "LiveLane", "LiveLaneReport", "LiveIncusHost", "LiveRemoteHost",
        "FakeIncusTransport", "SlowLaneGuardTest", "BrowserTestLaneGuardTest", "BrowserTestSources");

    private static final Pattern EXTENDS = Pattern.compile(
        "class\\s+(\\w+)(?:<[^>]*>)?\\s+extends\\s+([\\w.]+)");

    /** Every browser-test class's source, by simple name. */
    final Map<String, String> sources;

    /** Each class's in-tree superclass, by simple name. */
    final Map<String, String> parents;

    private BrowserTestSources(Map<String, String> sources, Map<String, String> parents) {
        this.sources = sources;
        this.parents = parents;
    }

    /** Reads the browser-test tree; the guards run from the project root. */
    static BrowserTestSources read() throws IOException {
        Path root = Path.of("src/browserTest/java");
        assertThat(root).as("the lane guards run from the project root").isDirectory();
        Map<String, String> sources = new HashMap<>();
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path file : walk.filter(p -> p.toString().endsWith(".java")).toList()) {
                String name = file.getFileName().toString();
                sources.put(name.substring(0, name.length() - 5), Files.readString(file));
            }
        }
        assertThat(sources).as("the browser test tree is non-empty").isNotEmpty();

        Map<String, String> parents = new HashMap<>();
        for (Map.Entry<String, String> entry : sources.entrySet()) {
            Matcher m = EXTENDS.matcher(entry.getValue());
            while (m.find()) {
                if (m.group(1).equals(entry.getKey())) {
                    String sup = m.group(2);
                    parents.put(entry.getKey(), sup.substring(sup.lastIndexOf('.') + 1));
                    break;
                }
            }
        }
        return new BrowserTestSources(sources, parents);
    }

    /** @return whether the class is a concrete, enforceable test class: named *Test, not abstract, not a helper */
    boolean isConcreteTest(String cls) {
        String source = this.sources.get(cls);
        return source != null && !HELPERS.contains(cls) && cls.endsWith("Test")
            && !source.contains("abstract class " + cls);
    }

    /** @return whether the predicate holds for the class's own source or any in-tree superclass's */
    boolean chainMatches(String cls, Predicate<String> predicate) {
        Set<String> seen = new HashSet<>();
        for (String c = cls; c != null && seen.add(c); c = this.parents.get(c)) {
            String src = this.sources.get(c);
            if (src != null && predicate.test(src)) {
                return true;
            }
        }
        return false;
    }
}
