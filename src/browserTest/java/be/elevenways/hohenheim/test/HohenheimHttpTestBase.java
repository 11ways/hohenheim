package be.elevenways.hohenheim.test;

import be.elevenways.hawkeye.testSupport.ServedWithoutBrowser;
import org.junit.jupiter.api.Tag;

/**
 * The shared-server base of a class that talks to the served app over HTTP alone: it runs in browserTestHttp, whose
 * forks launch no browser, and its {@code page} and {@code context} refuse any use.
 *
 * AIDEV-NOTE: the tag plans the lane, the annotation keeps the browser away. HttpLaneGuardTest fails a browser-lane
 * class that never drives a browser; the annotation's stub fails a class here that does, at run time.
 *
 * @author Jelle De Loecker
 * @since  0.1.0
 */
@ServedWithoutBrowser
@Tag("served-http")
public abstract class HohenheimHttpTestBase extends HohenheimTestBase {
}
