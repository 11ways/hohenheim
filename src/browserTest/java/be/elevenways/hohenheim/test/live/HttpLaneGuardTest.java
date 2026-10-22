package be.elevenways.hohenheim.test.live;

import be.elevenways.hawkeye.testSupport.HawkeyeBrowserTestBase;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A shared-server class that never drives a browser runs in browserTestHttp (HohenheimHttpTestBase), so its fork
 * launches none; one in that lane that does drive a browser is refused at run time by its page's stub.
 *
 * AIDEV-NOTE: the scan errs toward the browser lane on purpose. A browser verb is any non-private method of
 * HawkeyeBrowserTestBase but the HTTP-safe few, read by reflection, so a new helper counts as a browser verb until it is
 * named safe here; a field use of page, context, browser or playwright counts too. A false hit only keeps a class
 * in the browser lane, and a missed one fails that class loudly in the HTTP lane (@ServedWithoutBrowser's stub).
 *
 * @author Jelle De Loecker
 * @since  0.1.0
 */
class HttpLaneGuardTest {

    /** The base methods that work without a browser: the server's own lifecycle and plain HTTP fetches. */
    private static final Set<String> HTTP_SAFE = Set.of(
        "fetchServedHtml", "getServerPort", "startServer", "stopServer", "serveLaneBundle", "browserGivenTo");

    /** Browser verbs the Zenit and Hohenheim bases add on top of Hawkeye's. */
    private static final Set<String> SUITE_BROWSER_VERBS = Set.of("openWithoutScript", "navigateToAppAs");

    @Test
    void everySharedServerClassThatDrivesNoBrowserRunsInTheHttpLane() throws IOException {
        // 1. The browser verbs: Hawkeye's non-private helpers but the HTTP-safe ones, plus the suite's own.
        Set<String> verbs = new HashSet<>(SUITE_BROWSER_VERBS);
        for (Method method : HawkeyeBrowserTestBase.class.getDeclaredMethods()) {
            if (!Modifier.isPrivate(method.getModifiers()) && !method.isSynthetic()
                && !HTTP_SAFE.contains(method.getName())) {
                verbs.add(method.getName());
            }
        }
        assertThat(verbs).as("step 1: the base declares browser verbs").contains("navigateToApp", "click");
        Pattern browser = Pattern.compile("\\b(page|context|browser|playwright)\\s*\\.|\\b("
            + verbs.stream().map(Pattern::quote).collect(Collectors.joining("|")) + ")\\s*\\(");

        // 2. Every concrete shared-server class below the suite base that touches none of them is an HTTP-lane class.
        BrowserTestSources tree = BrowserTestSources.read();
        List<String> misplaced = new ArrayList<>();
        int httpClasses = 0;
        for (String cls : tree.sources.keySet()) {
            if (!tree.isConcreteTest(cls) || !below(tree, cls, "HohenheimTestBase")
                || tree.chainMatches(cls, source -> source.contains("@Tag(\"slow\")"))) {
                continue;
            }
            boolean http = below(tree, cls, "HohenheimHttpTestBase");
            httpClasses += http ? 1 : 0;
            if (!http && !drivesBrowser(tree, cls, browser)) {
                misplaced.add(cls);
            }
        }
        assertThat(misplaced)
            .as("step 2: these classes drive no browser yet run in the browser lane, launching one per fork for"
                + " nothing: extend HohenheimHttpTestBase")
            .isEmpty();
        assertThat(httpClasses).as("step 2: the HTTP lane holds classes").isPositive();
    }

    /** @return whether {@code base} is a superclass of {@code cls} in the tree */
    private static boolean below(BrowserTestSources tree, String cls, String base) {
        Set<String> seen = new HashSet<>();
        for (String c = tree.parents.get(cls); c != null && seen.add(c); c = tree.parents.get(c)) {
            if (c.equals(base)) {
                return true;
            }
        }
        return false;
    }

    /** @return whether the class or a tree superclass below the suite base uses a browser verb or field */
    private static boolean drivesBrowser(BrowserTestSources tree, String cls, Pattern browser) {
        Set<String> seen = new HashSet<>();
        for (String c = cls; c != null && !c.equals("HohenheimTestBase") && seen.add(c); c = tree.parents.get(c)) {
            String source = tree.sources.get(c);
            if (source != null && browser.matcher(source).find()) {
                return true;
            }
        }
        return false;
    }
}
