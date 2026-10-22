package be.elevenways.hohenheim.test.migration;

import be.elevenways.zenit.test.support.SchemaSnapshot;
import org.junit.jupiter.api.Test;

import java.io.IOException;

/**
 * Hohenheim's shipped migrations never move: the versions each baseline replaces, the operations each signs and every
 * recorded checksum the integrity check accepts stay as pinned in {@code hohenheim-migrations.snapshot}.
 *
 * @author Jelle De Loecker
 * @since  0.10.0
 */
class HohenheimMigrationPinsTest {

    @Test
    void theShippedMigrationsNeverMove() throws IOException {
        SchemaSnapshot.assertMigrationsPinned(getClass(), "hohenheim-migrations.snapshot",
            "be.elevenways.hohenheim.migration", "be.elevenways.hohenheim.server.migration");
    }
}
