package be.elevenways.hohenheim.test.settings;

import be.elevenways.zenit.server.setting.DryFileSource;
import be.elevenways.zenit.server.setting.DryResourceSource;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Hohenheim ships plumage's own palette: an install picks a brand colour in its local.dry,
 * never through a seed baked into the shipped defaults, and those defaults ship inside the jar.
 *
 * @author Jelle De Loecker
 * @since  0.1.0
 */
class ShippedBrandDefaultsTest {

    @Test
    void theShippedDefaultsLeaveTheBrandColourToPlumage() {
        // 1. The shipped defaults still name the brand.
        Path defaults = Path.of("settings", "default.dry");
        assertThat(defaults).as("the shipped defaults file").exists();
        Object brand = new DryFileSource(defaults).snapshot().get("brand");
        assertThat(brand).as("step 1: settings/default.dry keeps its brand group").isInstanceOf(Map.class);

        // 2. It pins no seed colour: an amber seed there overrode plumage's violet for every
        //    install that did not set its own.
        assertThat(((Map<?, ?>) brand).get("seed_color"))
            .as("step 2: settings/default.dry must not pin brand.seed_color")
            .isNull();

        // 3. The defaults ride the jar: a deployed install, which never gets the checkout's file, reads the same
        //    document from its classpath (a missing copy left installs unnamed, the header showing the panel title).
        assertThat(new DryResourceSource("settings/default.dry").snapshot())
            .as("step 3: the server's classpath carries the shipped defaults, identical to the checkout's file")
            .isEqualTo(new DryFileSource(defaults).snapshot());
    }
}
