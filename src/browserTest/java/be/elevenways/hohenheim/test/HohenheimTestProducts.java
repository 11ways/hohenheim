package be.elevenways.hohenheim.test;

import be.elevenways.protoblast.common.annotation.BlastAutoLoad;
import be.elevenways.protoblast.common.registry.Product;

/**
 * Declares the namespace Hohenheim's test fixtures register members under, so a test JVM's boot passes
 * {@code RegistryIndex.verify()}.
 *
 * AIDEV-NOTE: a fixture registered at class load (a test endpoint, a fixture panel) can exist before the boot that
 * verifies; one added after boot is never verified. A new fixture namespace is declared here, in the path alphabet
 * (no dashes): the former hohenheim-test and hohenheimtest fixtures share this one.
 *
 * @author Jelle De Loecker
 * @since  0.1.0
 */
@BlastAutoLoad
public final class HohenheimTestProducts {

    /** Autoload sentinel; reading it declares the fixture product. */
    public static final Product PRODUCT = Product.declare("hohenheim_test");

    private HohenheimTestProducts() {
    }
}
