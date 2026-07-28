package be.elevenways.hohenheim.server.process;

import be.elevenways.protoblast.common.Blast;
import be.elevenways.zenit.server.orm.seed.SeedContext;
import be.elevenways.zenit.server.orm.seed.Seeder;
import org.checkerframework.checker.nullness.qual.NonNull;

/**
 * Boot wiring for the one-time {@link SiteApiKeys#hashStoredKeys()} sweep.
 *
 * AIDEV-NOTE: the keys live inside a JSON SchemaField, so this cannot be a
 * MigrationBuilder migration: {@code execute(String sql)} is fire-and-forget
 * (no read-back) and SQLite has no sha256. A ledgered {@link SeedContext#once}
 * block is the framework's ORM-level one-shot, and it is re-entrant by
 * contract -- which the sweep is anyway, since an already-hashed entry is left
 * alone. No configured key is invalidated: hashing a plaintext key IN PLACE
 * keeps it valid, because the key its holder presents still hashes to the
 * stored digest.
 *
 * @author Jelle De Loecker
 */
public final class SiteApiKeySeeder implements Seeder {

    /** Higher than the default so it runs before any content seeder touches sites. */
    private static final int WEIGHT = 100;

    public SiteApiKeySeeder() {}

    @Override
    public int getWeight() {
        return WEIGHT;
    }

    @Override
    public void seed(@NonNull SeedContext ctx) {
        ctx.once("hohenheim.hash-site-api-keys", () -> {
            int rewritten = SiteApiKeys.hashStoredKeys();
            if (rewritten > 0) {
                Blast.log("SEED: hashed the api keys of", rewritten, "site(s);",
                    "the configured keys themselves stay valid");
            }
        });
    }
}
