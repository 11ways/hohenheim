package be.elevenways.hohenheim.test;

import be.elevenways.hohenheim.HohenheimMicrocopy;
import be.elevenways.zenit.microcopy.server.MicrocopyCatalogScope;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

/**
 * The scope contract of Hohenheim's shipped catalogs: every variant carries a scope Hohenheim owns, never another
 * module's ({@code field} and {@code nav} are plumage's, {@code forms} zenit-forms', {@code cms} zenit-cms'), and no
 * (key, filters) identity collides with a framework module's on the classpath. The mechanism is zenit-microcopy's
 * gate; Hohenheim owns exactly the scopes {@link HohenheimMicrocopy} declares, a declared value no variant uses failing
 * it too.
 *
 * @author Jelle De Loecker
 * @since  0.10.0
 */
class MicrocopyCatalogScopeTest {

    @Test
    void everyShippedVariantCarriesAScopeHohenheimOwns() {
        MicrocopyCatalogScope.ofOwnCatalogs()
            .owning(Arrays.stream(HohenheimMicrocopy.values()).map(HohenheimMicrocopy::scope).toArray(String[]::new))
            .requireClaimed();
    }
}
