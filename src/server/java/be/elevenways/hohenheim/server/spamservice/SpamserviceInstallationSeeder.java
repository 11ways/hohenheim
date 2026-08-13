package be.elevenways.hohenheim.server.spamservice;

import be.elevenways.hohenheim.model.SpamserviceInstallationModel;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.server.orm.seed.SeedContext;
import be.elevenways.zenit.server.orm.seed.Seeder;
import org.checkerframework.checker.nullness.qual.NonNull;

/**
 * The fixed-row Spamservice installation singleton, created at the SEED boot stage.
 *
 * AIDEV-NOTE: this row used to be a raw INSERT inside M041 and moved here with the
 * 2026-08-13 migration consolidation -- default records are the Seeder's job, never a
 * migration's. It is a STRUCTURAL stub: every mutation updates id=1, so two concurrent
 * first saves cannot insert twins. {@code ensure} rather than {@code sync} on purpose --
 * the port, heap and system user are the operator's to edit and must not be re-asserted
 * on every boot.
 *
 * @author Jelle De Loecker
 */
public final class SpamserviceInstallationSeeder implements Seeder {

    public SpamserviceInstallationSeeder() {}

    @Override
    public void seed(@NonNull SeedContext ctx) {
        ctx.ensure(Models.get(SpamserviceInstallationModel.class),
            SpamserviceInstallationModel.SINGLETON_ID, row -> {
                row.set(SpamserviceInstallationModel.ID,
                    SpamserviceInstallationModel.SINGLETON_ID);
                row.set(SpamserviceInstallationModel.ENABLED, false);
                row.set(SpamserviceInstallationModel.PORT, 8095);
                row.set(SpamserviceInstallationModel.MAX_HEAP_MB, 512);
            });
    }
}
