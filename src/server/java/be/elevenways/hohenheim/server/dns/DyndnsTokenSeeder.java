package be.elevenways.hohenheim.server.dns;

import be.elevenways.protoblast.common.Blast;
import be.elevenways.zenit.server.orm.seed.SeedContext;
import be.elevenways.zenit.server.orm.seed.Seeder;
import org.checkerframework.checker.nullness.qual.NonNull;

/**
 * Boot wiring for the one-time {@link DynamicDnsService#hashStoredTokens()} sweep.
 *
 * AIDEV-NOTE: mirrors {@code SiteApiKeySeeder}. The token lives in a plain column,
 * but a MigrationBuilder migration still cannot hash it -- {@code execute(String sql)}
 * is fire-and-forget (no read-back) and SQLite has no sha256. A ledgered
 * {@link SeedContext#once} block is the framework's ORM-level one-shot, and it is
 * re-entrant by contract -- which the sweep is anyway, since an already-hashed
 * value is left alone. NO configured client is broken: hashing a plaintext token IN
 * PLACE keeps it valid, because the client still presents the same plaintext, which
 * hashes to the stored digest.
 *
 * @author Jelle De Loecker
 */
public final class DyndnsTokenSeeder implements Seeder {

    /** Higher than the default so it runs before any content seeder touches records. */
    private static final int WEIGHT = 100;

    public DyndnsTokenSeeder() {}

    @Override
    public int getWeight() {
        return WEIGHT;
    }

    @Override
    public void seed(@NonNull SeedContext ctx) {
        ctx.once("hohenheim.hash-dyndns-tokens", () -> {
            int rewritten = DynamicDnsService.hashStoredTokens();
            if (rewritten > 0) {
                Blast.log("SEED: hashed the dyndns token of", rewritten, "record(s);",
                    "the configured tokens themselves stay valid");
            }
        });
    }
}
