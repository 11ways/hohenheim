package be.elevenways.hohenheim.server.docker;

import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.server.options.ServerOptions;
import be.elevenways.zenit.server.orm.seed.SeedContext;
import be.elevenways.zenit.server.orm.seed.Seeder;
import org.checkerframework.checker.nullness.qual.NonNull;

/**
 * The implicit {@code local} host row, created at the SEED boot stage because it is an
 * installation invariant and not a page concern.
 *
 * AIDEV-NOTE: this row's identity is its RESERVED NAME ("local") -- the resource guards
 * refuse renaming, re-targeting and deleting it -- so there is no stable surrogate primary
 * key to hand {@code SeedContext.ensure}, and {@link ServerModel#localServerId()} IS the
 * must-exist create-if-missing derivation (re-run every boot, so a lost row comes back).
 * It used to be reached from {@code ServerResource.listRows} and {@code ServerOptions
 * .refresh}, which made a GET render of /admin/servers perform an INSERT with full write
 * hooks; the form path had already been named as the same defect once (see
 * {@link ServerService#clientFor}). Populating the server-name registry here too means the
 * first render finds it fresh instead of repopulating shared state mid-request.
 */
public final class LocalServerSeeder implements Seeder {

    public LocalServerSeeder() {}

    @Override
    public void seed(@NonNull SeedContext ctx) {
        ServerModel.localServerId();
        ServerOptions.refresh();
    }
}
