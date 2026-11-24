package be.elevenways.hohenheim.server.docker;

import be.elevenways.hohenheim.model.HostModel;
import be.elevenways.hohenheim.server.options.HostOptions;
import be.elevenways.zenit.server.orm.seed.SeedContext;
import be.elevenways.zenit.server.orm.seed.Seeder;
import org.checkerframework.checker.nullness.qual.NonNull;

/**
 * The implicit {@code local} host row, created at the SEED boot stage because it is an
 * installation invariant and not a page concern.
 *
 * AIDEV-NOTE: this row's identity is its RESERVED NAME ("local") -- the resource guards
 * refuse renaming, re-targeting and deleting it -- so there is no stable surrogate primary
 * key to hand {@code SeedContext.ensure}, and {@link HostModel#localHostId()} IS the
 * must-exist create-if-missing derivation (re-run every boot, so a lost row comes back).
 * It used to be reached from {@code ServerResource.listRows} and {@code HostOptions
 * .refresh}, which made a GET render of /admin/hosts perform an INSERT with full write
 * hooks; the form path had already been named as the same defect once (see
 * {@link HostService#clientFor}). Populating the server-name registry here too means the
 * first render finds it fresh instead of repopulating shared state mid-request.
 */
public final class LocalHostSeeder implements Seeder {

    public LocalHostSeeder() {}

    @Override
    public void seed(@NonNull SeedContext ctx) {
        HostModel.localHostId();
        HostOptions.refresh();
    }
}
