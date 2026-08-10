package be.elevenways.hohenheim.test;

import be.elevenways.hohenheim.server.proxy.SiteDispatcher;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the brute-force hostname guards on regex domain matching (faithful to
 * the Node original: git/gitlab subdomains, garbled www levels, scanners).
 */
class RegexHostnameGuardTest {

    @Test
    void suspiciousProbeHostnamesAreRejected() {
        assertTrue(SiteDispatcher.isSuspiciousRegexHostname("git.example.com"));
        assertTrue(SiteDispatcher.isSuspiciousRegexHostname("gitlab.example.com"));
        assertTrue(SiteDispatcher.isSuspiciousRegexHostname("www.www.example.com"));
        assertTrue(SiteDispatcher.isSuspiciousRegexHostname("project.notexist.example.com"));
        assertTrue(SiteDispatcher.isSuspiciousRegexHostname("foo.www.example.com"));
        assertTrue(SiteDispatcher.isSuspiciousRegexHostname("wwww.example.com"));
        assertTrue(SiteDispatcher.isSuspiciousRegexHostname("app.git.example.com"));
    }

    @Test
    void ordinaryHostnamesPass() {
        assertFalse(SiteDispatcher.isSuspiciousRegexHostname("example.com"));
        assertFalse(SiteDispatcher.isSuspiciousRegexHostname("www.example.com"));
        assertFalse(SiteDispatcher.isSuspiciousRegexHostname("project.pages.example.com"));
        assertFalse(SiteDispatcher.isSuspiciousRegexHostname("digital.example.com"));
        // The guards are LABEL-anchored: a label merely containing "git" is not the
        // git label (substring matching tripped this one pre-fix).
        assertFalse(SiteDispatcher.isSuspiciousRegexHostname("digit.example.com"));
        assertFalse(SiteDispatcher.isSuspiciousRegexHostname("legit.example.com"));
    }
}
