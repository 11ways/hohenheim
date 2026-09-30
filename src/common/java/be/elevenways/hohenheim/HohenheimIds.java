package be.elevenways.hohenheim;

import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.protoblast.common.registry.Product;
import org.checkerframework.checker.nullness.qual.NonNull;

/**
 * The hohenheim product and its id factory.
 *
 * AIDEV-NOTE: PRODUCT is the only static field on purpose: this class is initialized from arbitrary class-load
 * contexts, so its initializer reaches nothing beyond Product.declare (never Blast, which runs the autoload index).
 *
 * @author Jelle De Loecker
 * @since  0.1.0
 */
public final class HohenheimIds {

    public static final Product PRODUCT = Product.declare("hohenheim");

    private HohenheimIds() {
    }

    /**
     * @return {@code hohenheim:} plus {@code path}
     */
    public static @NonNull Identifier id(@NonNull String path) {
        return PRODUCT.id(path);
    }
}
