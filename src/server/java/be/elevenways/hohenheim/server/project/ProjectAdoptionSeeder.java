package be.elevenways.hohenheim.server.project;

import be.elevenways.zenit.server.orm.seed.SeedContext;
import be.elevenways.zenit.server.orm.seed.Seeder;
import org.checkerframework.checker.nullness.qual.NonNull;

/**
 * Boot wiring for the one-time {@link ProjectAdoption#run()} heal (the ledgered
 * DATA half of M067): existing per-user-granted sites/instances move into
 * auto-created projects, with quota buckets, override rows and the released-claim
 * ledger following in the same pass. Idempotent by construction (already-adopted
 * sets skip), but ledgered anyway so re-runs are a decision, never an accident.
 */
public final class ProjectAdoptionSeeder implements Seeder {

    public ProjectAdoptionSeeder() {}

    @Override
    public void seed(@NonNull SeedContext ctx) {
        ctx.once("hohenheim.project-adoption", ProjectAdoption::run);
    }
}
