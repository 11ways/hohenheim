package be.elevenways.hohenheim.test;

import be.elevenways.hawkeye.testSupport.BrowserNavigationRecovery;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BrowserNavigationRecoveryTest {
    @Test
    void onlyAnExplicitCriticalNetworkChangeCanRetryTheNavigation() {
        assertThat(BrowserNavigationRecovery.criticalNetworkChange("document", "net::ERR_NETWORK_CHANGED")).isTrue();
        assertThat(BrowserNavigationRecovery.criticalNetworkChange("script", "net::ERR_NETWORK_CHANGED")).isTrue();
        assertThat(BrowserNavigationRecovery.criticalNetworkChange("xhr", "net::ERR_NETWORK_CHANGED")).isFalse();
        assertThat(BrowserNavigationRecovery.criticalNetworkChange("script", "net::ERR_CONNECTION_REFUSED")).isFalse();
        assertThat(BrowserNavigationRecovery.criticalNetworkChange("unknown", "net::ERR_NETWORK_CHANGED")).isFalse();

        AtomicInteger attempts = new AtomicInteger();
        BrowserNavigationRecovery.run(() -> {
            if (attempts.incrementAndGet() == 1) throw new AssertionError("critical script was interrupted");
        }, () -> true);
        assertThat(attempts.get()).as("one interrupted navigation is recovered exactly once").isEqualTo(2);
    }

    @Test
    void anApplicationFailureAndARepeatedNetworkFailureStillFail() {
        AssertionError failure = new AssertionError("hydration failed");
        AtomicInteger attempts = new AtomicInteger();
        assertThatThrownBy(() -> BrowserNavigationRecovery.run(() -> {
            attempts.incrementAndGet();
            throw failure;
        }, () -> false)).isSameAs(failure);
        assertThat(attempts.get()).as("an ordinary hydration failure gets no retry").isEqualTo(1);

        attempts.set(0);
        assertThatThrownBy(() -> BrowserNavigationRecovery.run(() -> {
            attempts.incrementAndGet();
            throw failure;
        }, () -> true)).isSameAs(failure);
        assertThat(attempts.get()).as("a second interruption is final").isEqualTo(2);
    }
}
