package be.elevenways.hohenheim.server;

import be.elevenways.hohenheim.server.cms.SiteDomainResource;
import be.elevenways.hohenheim.server.cms.SiteResource;
import be.elevenways.hohenheim.server.auth.TenantWrites;
import be.elevenways.hohenheim.server.cms.SiteTerminalCsp;
import be.elevenways.hohenheim.server.dns.DynamicDnsService;
import be.elevenways.hohenheim.server.dns.GeneratedDnsRecords;
import be.elevenways.hohenheim.server.instance.InstanceImagePolicy;
import be.elevenways.hohenheim.server.instance.InstanceQuota;
import be.elevenways.hohenheim.server.process.ReservedEnv;
import be.elevenways.hohenheim.server.process.SiteApiKeys;
import be.elevenways.zenit.common.ZenitModule;

/**
 * The one owner of every model-funnel write hook: secret normalization (site
 * api keys, dyndns tokens), the reserved-env refusal and the site enable
 * invariant all install here, at the MODULES boot stage.
 *
 * AIDEV-NOTE: this is a discovered {@link ZenitModule} ON PURPOSE, so the
 * ordering is STRUCTURAL, not incidental: the boot-stage weights guarantee
 * MODULES (200) completes before STARTHTTP (50) binds the server, and
 * discovery means no reordering of ServerMain.main() (which used to install
 * the dyndns hook AFTER the server was bound, leaving a window where an admin
 * write persisted a plaintext token) can regress it. Tests exercise the same
 * path via ServerZenitRuntime.init(), so they can no longer hide an ordering
 * defect by hand-installing hooks before boot.
 *
 * @author Jelle De Loecker
 */
public final class HohenheimWriteHooks implements ZenitModule {

    @Override
    public void init() {
        // No plaintext site api key reaches the datasource, on ANY write path.
        SiteApiKeys.install();
        // No plaintext dyndns token reaches the datasource, on ANY write path.
        DynamicDnsService.installTokenHashing();
        // No reserved control variable (HOHENHEIM_*, PORT, the security-report
        // pair) can be persisted as an operator environment variable.
        ReservedEnv.install();
        // No disabled site can go live on a hostname an enabled site already
        // owns (form, toggle, delegated save, revision restore).
        SiteResource.installEnableInvariant();
        // No domain row can take a route an enabled site already owns, and every row
        // stamps the live-route claim its unique index arbitrates (form, clone, seeder,
        // API writeback, direct model save).
        SiteDomainResource.installRouteInvariant();
        // A DNS record a system authored carries derived attribution, and no caller can
        // hand-write that attribution onto a row of its own.
        GeneratedDnsRecords.install();
        // A delegated tenant may set only the delegated domain columns, and may author only
        // the allow-listed DNS record types -- on every writer, not just the /manage forms.
        TenantWrites.install();
        // The pl-terminal page gets the wasm concessions; no other admin page does.
        SiteTerminalCsp.install();
        // A tenant-originated instance write may only run an APPROVED template's image;
        // anything else needs the image_any record capability. Installed BEFORE the
        // quota hook fires (beforeValidate vs beforeWrite), so a refused image never
        // spends a reservation.
        InstanceImagePolicy.install();
        // Concurrent instance creates cannot both spend the last quota slot, and the
        // soft-delete transition hands the slot back (the remove hooks never fire on
        // the destroy path -- it soft-deletes through save()).
        InstanceQuota.install();
    }
}
