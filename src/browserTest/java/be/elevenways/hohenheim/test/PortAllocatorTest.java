package be.elevenways.hohenheim.test;

import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.hohenheim.server.process.PortAllocator;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests the configurable first port of {@link PortAllocator}. No DB.
 */
class PortAllocatorTest {

    @AfterAll
    static void restoreDefault() {
        HohenheimSettings.VALUES.setValue(HohenheimSettings.Proxy.FIRST_PORT, 4748);
    }

    @Test
    void allocatesFromTheDefaultRange() {
        HohenheimSettings.VALUES.setValue(HohenheimSettings.Proxy.FIRST_PORT, 4748);
        PortAllocator allocator = new PortAllocator();

        int port = allocator.allocate(1);
        assertThat(port).isBetween(4748, 4748 + 5000);
        allocator.release(port);
    }

    @Test
    void honorsAConfiguredFirstPort() {
        HohenheimSettings.VALUES.setValue(HohenheimSettings.Proxy.FIRST_PORT, 24748);
        PortAllocator allocator = new PortAllocator();

        int port = allocator.allocate(1);
        assertThat(port).isBetween(24748, 24748 + 5000);
        allocator.release(port);
    }

    @Test
    void privilegedFirstPortFallsBackToDefault() {
        HohenheimSettings.VALUES.setValue(HohenheimSettings.Proxy.FIRST_PORT, 80);
        PortAllocator allocator = new PortAllocator();

        int port = allocator.allocate(1);
        assertThat(port).isBetween(4748, 4748 + 5000);
        allocator.release(port);
    }

    @Test
    void reservedPortsAreNotHandedOutTwice() {
        HohenheimSettings.VALUES.setValue(HohenheimSettings.Proxy.FIRST_PORT, 4748);
        PortAllocator allocator = new PortAllocator();

        int first = allocator.allocate(1);
        int second = allocator.allocate(2);
        assertThat(second).isNotEqualTo(first);

        allocator.release(first);
        allocator.release(second);
        assertThat(allocator.getReservedCount()).isZero();
    }
}
